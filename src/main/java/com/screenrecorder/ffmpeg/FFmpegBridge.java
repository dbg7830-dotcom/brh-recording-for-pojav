package com.screenrecorder.ffmpeg;

import com.screenrecorder.ScreenRecorderMod;

import java.io.*;
import java.util.function.Consumer;

/**
 * Self-contained bridge to the PojavLauncher FFmpegPlugin.
 *
 * The plugin (net.kdt.pojavlaunch.ffmpeg) is a standalone Android APK that
 * ships a statically-linked ffmpeg binary for AArch64. When installed, it
 * exposes the binary at a known path inside its app data directory.
 *
 * This class is the ONLY place in the mod that knows about FFmpeg.
 * Nothing outside this package touches FFmpeg paths or processes directly.
 *
 * Usage:
 *   FFmpegBridge.renderRawvid(inputFile, outputMp4, width, height, fps,
 *       progressLine -> updateUI(progressLine),
 *       success -> handleDone(success));
 */
public class FFmpegBridge {

    /** Result callback — called on the render thread, not the game thread. */
    public interface RenderCallback {
        void onDone(boolean success, File outputFile);
    }

    /**
     * Encode a .rawvid file to MP4 asynchronously.
     * Returns immediately; callback fires when done.
     */
    public static void renderRawvid(File rawvid, File outputMp4,
                                     int width, int height, int fps,
                                     Consumer<String> onProgress,
                                     RenderCallback   onDone) {
        Thread t = new Thread(() -> {
            try {
                String ffmpeg = FFmpegLocator.locate();
                ScreenRecorderMod.LOGGER.info("[FFmpegBridge] Using binary: " + ffmpeg);
                ScreenRecorderMod.LOGGER.info("[FFmpegBridge] Encoding: " + rawvid.getName()
                        + " → " + outputMp4.getName());

                Process proc = buildProcess(ffmpeg, rawvid, outputMp4, width, height, fps);

                // Log stderr in background
                Thread err = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getErrorStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            ScreenRecorderMod.LOGGER.debug("[ffmpeg] " + line);
                            // Surface progress lines to the UI
                            if (line.startsWith("frame=") || line.startsWith("size=")) {
                                String trimmed = line.trim();
                                onProgress.accept(trimmed.length() > 50
                                        ? trimmed.substring(0, 47) + "…" : trimmed);
                            }
                        }
                    } catch (IOException ignored) {}
                }, "ffmpeg-stderr");
                err.setDaemon(true);
                err.start();

                // Feed frames: skip 32-byte file header + strip 4-byte timestamp per frame
                onProgress.accept("Feeding frames to encoder…");
                feedFrames(rawvid, proc.getOutputStream(), width, height);
                proc.getOutputStream().close();

                int exit = proc.waitFor();
                err.join(5000);

                if (exit == 0 && outputMp4.exists() && outputMp4.length() > 0) {
                    ScreenRecorderMod.LOGGER.info("[FFmpegBridge] Done — "
                            + (outputMp4.length() / 1024 / 1024) + " MB");
                    onDone.onDone(true, outputMp4);
                } else {
                    ScreenRecorderMod.LOGGER.error("[FFmpegBridge] ffmpeg exited with code " + exit);
                    onDone.onDone(false, null);
                }

            } catch (FFmpegLocator.FFmpegNotFoundException e) {
                ScreenRecorderMod.LOGGER.error("[FFmpegBridge] " + e.getMessage());
                onProgress.accept("FFmpeg not found — is the plugin installed?");
                onDone.onDone(false, null);
            } catch (Exception e) {
                ScreenRecorderMod.LOGGER.error("[FFmpegBridge] Render error", e);
                onProgress.accept("Error: " + e.getMessage());
                onDone.onDone(false, null);
            }
        }, "screenrecorder-render");
        t.setDaemon(true);
        t.start();
    }

    private static Process buildProcess(String ffmpeg, File input, File output,
                                         int w, int h, int fps) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg,
                "-y",
                "-f",        "rawvideo",
                "-pix_fmt",  "rgba",
                "-s",        w + "x" + h,
                "-r",        String.valueOf(fps),
                "-i",        "pipe:0",
                "-c:v",      "libx264",
                "-preset",   "fast",
                "-crf",      "23",
                "-pix_fmt",  "yuv420p",
                "-movflags", "+faststart",
                output.getAbsolutePath()
        );
        pb.redirectErrorStream(false);
        return pb.start();
    }

    /**
     * Stream raw RGBA pixels from a .rawvid file into FFmpeg stdin.
     * Strips the 32-byte file header and the 4-byte per-frame timestamp.
     *
     * .rawvid layout:
     *   [32 bytes file header]
     *   repeat:
     *     [4 bytes timestamp LE]
     *     [width * height * 4 bytes RGBA]
     */
    private static void feedFrames(File rawvid, OutputStream out,
                                    int w, int h) throws IOException {
        int frameBytes = w * h * 4;
        byte[] buf = new byte[frameBytes];
        byte[] ts  = new byte[4];

        try (FileInputStream fis = new FileInputStream(rawvid);
             BufferedOutputStream bos = new BufferedOutputStream(out, 1024 * 1024)) {

            fis.skipNBytes(32); // file header

            while (true) {
                if (fis.readNBytes(ts,  0, 4)         < 4)         break; // timestamp
                if (fis.readNBytes(buf, 0, frameBytes) < frameBytes) break; // pixels
                bos.write(buf, 0, frameBytes);
            }
            bos.flush();
        }
    }

    /** @return true if FFmpeg is available right now (no side effects). */
    public static boolean isAvailable() {
        try {
            FFmpegLocator.locate();
            return true;
        } catch (FFmpegLocator.FFmpegNotFoundException e) {
            return false;
        }
    }
}
