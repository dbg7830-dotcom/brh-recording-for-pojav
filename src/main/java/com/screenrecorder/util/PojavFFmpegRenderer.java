package com.screenrecorder.util;

import com.screenrecorder.ScreenRecorderMod;
import com.screenrecorder.recording.RecordingManager;
import com.screenrecorder.screen.RecordingEntry;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Renders a .rawvid file to MP4 using the PojavLauncher / Zalith FFmpeg plugin.
 *
 * The .rawvid file contains raw RGBA frames at 816x460 (460p).
 * We feed it directly to FFmpeg as rawvideo — no intermediate conversion needed.
 *
 * FFmpeg command:
 *   ffmpeg -y
 *     -f rawvideo -pix_fmt rgba -s 816x460 -r 30
 *     -i recording.rawvid           ← skip our 32-byte header via -ss or -skip_frame
 *     -c:v libx264
 *     -preset fast                  ← balanced speed/quality for post-render
 *     -crf 23                       ← good quality (lower = bigger/better)
 *     -pix_fmt yuv420p              ← required for universal player compatibility
 *     -movflags +faststart          ← moov at start, playable before full download
 *     output.mp4
 *
 * The 32-byte header is stripped by seeking past it with -skip_frame / input offset.
 */
public class PojavFFmpegRenderer {

    private static final ExecutorService RENDER_THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ScreenRecorder-Render");
        t.setDaemon(true);
        return t;
    });

    public static void renderAsync(RecordingEntry entry,
                                   Consumer<String> onProgress,
                                   Consumer<File>   onComplete) {
        entry.rendering = true;
        RENDER_THREAD.submit(() -> {
            try {
                File mp4 = doRender(entry, onProgress);
                entry.renderedMp4 = mp4;
                entry.rendering   = false;
                onComplete.accept(mp4);
            } catch (Exception e) {
                ScreenRecorderMod.LOGGER.error("Render failed: " + entry.displayName, e);
                entry.rendering = false;
                onComplete.accept(null);
            }
        });
    }

    private static File doRender(RecordingEntry entry,
                                  Consumer<String> progress) throws Exception {
        String ffmpeg = PojavFfmpegLocator.locate();
        File   output = new File(entry.file.getParent(), entry.displayName + ".mp4");

        int w   = RecordingManager.CAPTURE_WIDTH;
        int h   = RecordingManager.CAPTURE_HEIGHT;
        int fps = RecordingManager.CAPTURE_FPS;

        progress.accept("Starting FFmpeg render…");

        // Pipe raw RGBA frames from .rawvid → FFmpeg → H.264 MP4
        ProcessBuilder pb = new ProcessBuilder(
            ffmpeg,
            "-y",                               // overwrite output if exists
            "-f",        "rawvideo",
            "-pix_fmt",  "rgba",
            "-s",        w + "x" + h,
            "-r",        String.valueOf(fps),
            "-i",        "pipe:0",              // raw frames piped via stdin
            "-c:v",      "libx264",
            "-preset",   "fast",
            "-crf",      "23",
            "-pix_fmt",  "yuv420p",             // required for broad player compatibility
            "-movflags", "+faststart",           // moov atom at start — playable before full download
            output.getAbsolutePath()
        );
        pb.redirectErrorStream(false);

        Process proc = pb.start();

        // Log FFmpeg stderr in background
        Thread stderrLog = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                String line;
                String lastProgress = "";
                while ((line = r.readLine()) != null) {
                    ScreenRecorderMod.LOGGER.debug("[FFmpeg] " + line);
                    // Surface frame= progress lines to the gallery UI
                    if (line.startsWith("frame=")) {
                        // e.g. "frame= 240 fps= 18 q=28.0 size=   1024kB time=00:00:08.00 ..."
                        String progressLine = line.trim();
                        if (!progressLine.equals(lastProgress)) {
                            progress.accept(progressLine.length() > 55
                                    ? progressLine.substring(0, 52) + "…"
                                    : progressLine);
                            lastProgress = progressLine;
                        }
                    }
                }
            } catch (IOException ignored) {}
        }, "FFmpeg-Stderr");
        stderrLog.setDaemon(true);
        stderrLog.start();

        // Feed raw frames: skip our 32-byte header, stream the rest into FFmpeg stdin
        // Each frame in the file is: [4 bytes timestamp][width*height*4 bytes rgba]
        // FFmpeg only wants the rgba bytes — strip the per-frame timestamp too.
        progress.accept("Feeding frames to encoder…");
        feedFramesToFfmpeg(entry.file, proc.getOutputStream(), w, h);

        proc.getOutputStream().close();

        int exitCode = proc.waitFor();
        stderrLog.join(5000);

        if (exitCode == 0 && output.exists() && output.length() > 0) {
            ScreenRecorderMod.LOGGER.info("Render complete → " + output.getName() +
                    " (" + (output.length() / 1024 / 1024) + " MB)");
            return output;
        } else {
            ScreenRecorderMod.LOGGER.error("FFmpeg exited with code " + exitCode);
            return null;
        }
    }

    /**
     * Read the .rawvid file and stream only the raw RGBA pixel data to FFmpeg stdin,
     * stripping the file header (32 bytes) and per-frame timestamp (4 bytes each).
     *
     * File layout:
     *   [32 bytes file header]
     *   repeat:
     *     [4 bytes timestamp (LE int32)]
     *     [width * height * 4 bytes RGBA]
     */
    private static void feedFramesToFfmpeg(File rawvid, OutputStream ffmpegIn,
                                            int w, int h) throws IOException {
        int frameBytes = w * h * 4;
        byte[] buf = new byte[frameBytes];

        try (FileInputStream fis = new FileInputStream(rawvid)) {
            // Skip the 32-byte file header (skipNBytes guarantees full skip or throws)
            fis.skipNBytes(32);

            byte[] tsBytes = new byte[4];
            BufferedOutputStream bos = new BufferedOutputStream(ffmpegIn, 1024 * 1024);

            while (true) {
                // Read per-frame timestamp (4 bytes) — discard it
                int tsRead = fis.readNBytes(tsBytes, 0, 4);
                if (tsRead < 4) break; // EOF

                // Read frame pixels
                int read = fis.readNBytes(buf, 0, frameBytes);
                if (read < frameBytes) break; // truncated last frame

                bos.write(buf, 0, frameBytes);
            }
            bos.flush();
        }
    }

    public static boolean canRender() {
        try {
            PojavFfmpegLocator.locate();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
