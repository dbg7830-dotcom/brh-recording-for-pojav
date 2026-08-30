package com.screenrecorder.util;

import com.screenrecorder.ScreenRecorderMod;

import java.io.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Writes raw RGBA frames to a .rawvid file on a dedicated IO thread.
 *
 * The game thread calls enqueueFrame() — which copies the pixel data and
 * returns immediately. The IO thread drains the queue and writes to disk.
 * This means the GL thread is never blocked by disk IO.
 *
 * .rawvid header (32 bytes):
 *   "SCRV"          4 bytes  magic
 *   version=1       4 bytes  int32 LE
 *   width           4 bytes  int32 LE
 *   height          4 bytes  int32 LE
 *   fps             4 bytes  int32 LE
 *   reserved        12 bytes zeros
 *
 * Per frame:
 *   timestampMs     4 bytes  int32 LE
 *   rgba data       width*height*4 bytes
 *
 * FFmpeg reads this with:
 *   ffmpeg -f rawvideo -pix_fmt rgba -s WxH -r FPS -i file.rawvid -vf vflip out.mp4
 * (No vflip needed — FrameCapture already corrects orientation.)
 */
public class RawFrameWriter {

    private static final int    QUEUE_CAPACITY = 12; // max frames buffered in RAM
    private static final byte[] STOP_SENTINEL  = new byte[0];

    private final File    outputFile;
    private final int     width, height, fps;
    private final int     frameBytes; // width * height * 4

    private final BlockingQueue<QueuedFrame> queue =
            new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private Thread         ioThread;
    private volatile boolean running = false;
    private long           framesWritten = 0;

    public RawFrameWriter(File outputFile, int width, int height, int fps) {
        this.outputFile = outputFile;
        this.width      = width;
        this.height     = height;
        this.fps        = fps;
        this.frameBytes = width * height * 4;
    }

    public void start() throws IOException {
        running = true;

        ioThread = new Thread(() -> {
            try (FileOutputStream fos = new FileOutputStream(outputFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, 2 * 1024 * 1024)) {

                DataOutputStream dos = new DataOutputStream(bos);

                // Write header
                dos.write(new byte[]{'S','C','R','V'});         // magic
                writeInt32LE(dos, 1);                           // version
                writeInt32LE(dos, width);
                writeInt32LE(dos, height);
                writeInt32LE(dos, fps);
                dos.write(new byte[12]);                        // reserved

                // Drain queue until sentinel
                while (true) {
                    QueuedFrame qf = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (qf == null) {
                        if (!running) break; // finished and queue empty
                        continue;
                    }
                    if (qf.data == STOP_SENTINEL) break;

                    writeInt32LE(dos, qf.timestampMs);
                    dos.write(qf.data);
                    framesWritten++;
                }

                dos.flush();
                ScreenRecorderMod.LOGGER.info(
                    "RawFrameWriter finished: " + framesWritten + " frames written to " +
                    outputFile.getName() + " (" + (outputFile.length() / 1024 / 1024) + " MB)");

            } catch (Exception e) {
                ScreenRecorderMod.LOGGER.error("RawFrameWriter IO error", e);
            }
        }, "ScreenRecorder-IO");

        ioThread.setDaemon(true);
        ioThread.start();
        ScreenRecorderMod.LOGGER.info("RawFrameWriter started → " + outputFile.getName());
    }

    /**
     * Enqueue a frame. Copies the data so the caller can reuse their buffer.
     * If the queue is full (disk too slow), the frame is dropped silently.
     */
    public void enqueueFrame(byte[] rgba, int timestampMs) {
        if (!running) return;

        // Copy — FrameCapture reuses its output buffer
        byte[] copy = new byte[rgba.length];
        System.arraycopy(rgba, 0, copy, 0, rgba.length);

        if (!queue.offer(new QueuedFrame(copy, timestampMs))) {
            // Queue full — disk IO can't keep up; drop frame rather than stall render thread
            ScreenRecorderMod.LOGGER.debug("Frame queue full — dropped frame at " + timestampMs + "ms");
        }
    }

    /**
     * Signal end of recording. Blocks until the IO thread finishes flushing.
     */
    public void finish() {
        running = false;
        // Force sentinel in even if queue is full (clear one slot first if needed)
        boolean offered = queue.offer(new QueuedFrame(STOP_SENTINEL, 0));
        if (!offered) {
            queue.clear();
            queue.offer(new QueuedFrame(STOP_SENTINEL, 0));
        }
        if (ioThread != null) {
            try {
                ioThread.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ScreenRecorderMod.LOGGER.warn("Interrupted while waiting for IO thread to finish");
            }
        }
    }

    // Little-endian int32 write (matches FFmpeg rawvideo expectations)
    private static void writeInt32LE(DataOutputStream dos, int value) throws IOException {
        dos.write( value        & 0xFF);
        dos.write((value >>  8) & 0xFF);
        dos.write((value >> 16) & 0xFF);
        dos.write((value >> 24) & 0xFF);
    }

    private static class QueuedFrame {
        final byte[] data;
        final int    timestampMs;
        QueuedFrame(byte[] data, int timestampMs) {
            this.data        = data;
            this.timestampMs = timestampMs;
        }
    }
}
