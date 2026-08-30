package com.screenrecorder.screen;

import com.screenrecorder.ScreenRecorderMod;
import com.screenrecorder.util.PojavFfmpegLocator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generates and caches thumbnail textures for the gallery.
 *
 * For entries that have a rendered MP4 alongside the .rawvid:
 *   Uses FFmpeg to extract the first frame of the MP4 → 160x90 RGBA thumbnail.
 *
 * For entries with only a .rawvid (not yet rendered):
 *   Reads the very first frame directly from the .rawvid binary
 *   (skip 32-byte header + 4-byte timestamp, read width*height*4 bytes,
 *   then scale to 160x90 in software) — no FFmpeg needed, instant.
 *
 * Extraction always runs off the main thread. A null return from getThumbnail()
 * means "still loading" — callers should draw a placeholder icon instead.
 */
public class ThumbnailCache {

    private static final int THUMB_W = 160;
    private static final int THUMB_H = 90;

    private final Map<String, Identifier> cache   = new HashMap<>();
    private final Map<String, Boolean>    loading = new HashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ScreenRecorder-Thumb");
        t.setDaemon(true);
        return t;
    });

    /**
     * Returns the GL texture identifier for a recording's thumbnail,
     * or null if still loading (triggers async extraction on first call).
     */
    public Identifier getThumbnail(RecordingEntry entry) {
        String key = entry.file.getAbsolutePath();

        if (cache.containsKey(key)) return cache.get(key);

        if (!loading.getOrDefault(key, false)) {
            loading.put(key, true);
            executor.submit(() -> generateThumbnail(entry, key));
        }
        return null;
    }

    private void generateThumbnail(RecordingEntry entry, String key) {
        try {
            byte[] rgba160x90;

            if (entry.renderedMp4 != null && entry.renderedMp4.exists()) {
                // Prefer MP4 thumbnail via FFmpeg
                rgba160x90 = extractFromMp4(entry.renderedMp4);
            } else {
                // Fall back to first frame from .rawvid
                rgba160x90 = extractFromRawvid(entry.file);
            }

            if (rgba160x90 == null) return;

            final byte[] pixels = rgba160x90;
            MinecraftClient.getInstance().execute(() -> {
                try {
                    NativeImage img = new NativeImage(NativeImage.Format.RGBA, THUMB_W, THUMB_H, false);
                    for (int y = 0; y < THUMB_H; y++) {
                        for (int x = 0; x < THUMB_W; x++) {
                            int i = (y * THUMB_W + x) * 4;
                            int r = pixels[i]   & 0xFF;
                            int g = pixels[i+1] & 0xFF;
                            int b = pixels[i+2] & 0xFF;
                            int a = pixels[i+3] & 0xFF;
                            // NativeImage stores ABGR internally
                            img.setColor(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                        }
                    }

                    NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
                    Identifier id = Identifier.of("screenrecorder",
                            "thumb_" + Math.abs(key.hashCode()));
                    MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
                    cache.put(key, id);
                    ScreenRecorderMod.LOGGER.info("Thumbnail ready: " + entry.displayName);

                } catch (Exception e) {
                    ScreenRecorderMod.LOGGER.error("Failed to upload thumbnail texture", e);
                }
            });

        } catch (Exception e) {
            ScreenRecorderMod.LOGGER.warn("Thumbnail generation failed for " + entry.displayName + ": " + e.getMessage());
        }
    }

    /** Extract first frame from MP4 using FFmpeg → scale to 160x90 RGBA */
    private byte[] extractFromMp4(File mp4) throws Exception {
        String ffmpeg = PojavFfmpegLocator.locate();
        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg,
                "-ss", "0",
                "-i",  mp4.getAbsolutePath(),
                "-frames:v", "1",
                "-vf",  "scale=" + THUMB_W + ":" + THUMB_H,
                "-f",   "rawvideo",
                "-pix_fmt", "rgba",
                "pipe:1"
        );
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        byte[] pixels = proc.getInputStream().readAllBytes();
        proc.waitFor();

        int expected = THUMB_W * THUMB_H * 4;
        return pixels.length == expected ? pixels : null;
    }

    /**
     * Extract first frame directly from a .rawvid file.
     * Layout: [32 header][4 timestamp][width*height*4 rgba] ...
     * We read that first frame and scale it down to 160x90.
     */
    private byte[] extractFromRawvid(File rawvid) throws Exception {
        try (FileInputStream fis = new FileInputStream(rawvid)) {
            // Skip 32-byte file header
            byte[] header = new byte[32];
            if (fis.read(header) < 32) return null;

            // Read width/height from header (LE int32 at offsets 8 and 12)
            int srcW = readInt32LE(header, 8);
            int srcH = readInt32LE(header, 12);
            if (srcW <= 0 || srcH <= 0 || srcW > 7680 || srcH > 4320) return null;

            // Skip 4-byte frame timestamp
            if (fis.skip(4) < 4) return null;

            // Read first frame pixels
            byte[] srcFrame = new byte[srcW * srcH * 4];
            int read = fis.readNBytes(srcFrame, 0, srcFrame.length);
            if (read < srcFrame.length) return null;

            // Scale to 160x90 with nearest-neighbour (fast, good enough for thumbnail)
            byte[] dst = new byte[THUMB_W * THUMB_H * 4];
            int xScale = ((srcW - 1) << 16) / THUMB_W;
            int yScale = ((srcH - 1) << 16) / THUMB_H;

            for (int dy = 0; dy < THUMB_H; dy++) {
                int sy = (dy * yScale) >> 16;
                for (int dx = 0; dx < THUMB_W; dx++) {
                    int sx  = (dx * xScale) >> 16;
                    int src = (sy * srcW + sx) * 4;
                    int d   = (dy * THUMB_W + dx) * 4;
                    dst[d]   = srcFrame[src];
                    dst[d+1] = srcFrame[src+1];
                    dst[d+2] = srcFrame[src+2];
                    dst[d+3] = srcFrame[src+3];
                }
            }
            return dst;
        }
    }

    private static int readInt32LE(byte[] buf, int offset) {
        return  (buf[offset]   & 0xFF)
             | ((buf[offset+1] & 0xFF) << 8)
             | ((buf[offset+2] & 0xFF) << 16)
             | ((buf[offset+3] & 0xFF) << 24);
    }

    /** Release all GPU textures. Call when closing the gallery screen. */
    public void dispose() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Identifier id : cache.values()) {
            mc.getTextureManager().destroyTexture(id);
        }
        cache.clear();
        loading.clear();
    }
}
