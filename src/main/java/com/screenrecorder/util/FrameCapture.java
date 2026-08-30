package com.screenrecorder.util;

import com.screenrecorder.ScreenRecorderMod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Captures the OpenGL framebuffer and scales it to the target resolution.
 *
 * Captures EVERYTHING the player sees:
 *   - Game world (chunks, entities, particles)
 *   - Hotbar, health bar, hunger bar, armour bar
 *   - Item cooldown triangles (the pie-slice overlay on items)
 *   - XP bar, XP level number
 *   - Boss bars, status effect icons
 *   - Chat, tab list, scoreboard
 *   - Any other HUD mods the player has installed
 *
 * This works because we read the default framebuffer (id=0) AFTER
 * InGameHud.render() has composited everything onto it, and BEFORE
 * Window.swapBuffers() sends it to the screen.
 *
 * MobileGlues compatibility:
 *   - glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE) — supported on all GLES 3.0+
 *   - GL_PACK_ALIGNMENT = 1 — required for non-power-of-two widths on
 *     some Android GPU drivers (Adreno, Mali, PowerVR)
 *   - Software bilinear downscale — avoids FBO blit issues on ANGLE/Zink
 *   - No PBOs — async PBO reads are buggy on older Adreno GLES drivers
 *
 * Output: raw RGBA bytes at targetWidth x targetHeight, top-to-bottom row order.
 */
public class FrameCapture {

    private final int targetW;
    private final int targetH;

    // Native buffer for glReadPixels — allocated once, reused every frame
    private ByteBuffer nativePixels;
    private int        nativeW; // actual framebuffer dimensions (may change on resize)
    private int        nativeH;

    // Heap arrays for scaling pipeline
    private byte[] srcRgba;   // raw pixels from GPU (native resolution, Y-flipped)
    private byte[] dstRgba;   // scaled output (targetW x targetH, correct orientation)

    private boolean initialized = false;

    public FrameCapture(int targetW, int targetH) {
        this.targetW = targetW;
        this.targetH = targetH;
        // Pre-allocate output buffer — size is always fixed
        this.dstRgba = new byte[targetW * targetH * 4];
    }

    /**
     * Capture and scale the current framebuffer.
     * Must be called from the GL/render thread.
     *
     * @return RGBA byte array (targetW x targetH, top-to-bottom), or null on failure.
     *         The returned array is reused — copy it before the next call if needed.
     *         (RawFrameWriter always copies before enqueuing, so this is safe.)
     */
    public byte[] captureAndScale() {
        try {
            // Bind default framebuffer — the composed game + HUD image
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

            // Read viewport dimensions (LWJGL requires IntBuffer, not int[])
            IntBuffer viewport = MemoryUtil.memAllocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            int srcW = viewport.get(2);
            int srcH = viewport.get(3);
            MemoryUtil.memFree(viewport);

            if (srcW <= 0 || srcH <= 0) return null;

            // Reallocate native buffer if framebuffer size changed
            if (!initialized || srcW != nativeW || srcH != nativeH) {
                if (nativePixels != null) MemoryUtil.memFree(nativePixels);
                nativeW      = srcW;
                nativeH      = srcH;
                nativePixels = MemoryUtil.memAlloc(srcW * srcH * 4);
                srcRgba      = new byte[srcW * srcH * 4];
                initialized  = true;
                ScreenRecorderMod.LOGGER.info(
                    "FrameCapture: native=" + srcW + "x" + srcH +
                    " → target=" + targetW + "x" + targetH);
            }

            // Read pixels — GLES-safe settings
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            nativePixels.clear();
            GL11.glReadPixels(0, 0, srcW, srcH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, nativePixels);
            nativePixels.get(srcRgba);
            nativePixels.rewind();

            // Scale + flip Y in one pass (OpenGL origin = bottom-left)
            bilinearScaleFlip(srcRgba, srcW, srcH, dstRgba, targetW, targetH);

            return dstRgba;

        } catch (Exception e) {
            ScreenRecorderMod.LOGGER.error("Frame capture error", e);
            return null;
        }
    }

    /**
     * Bilinear downscale with simultaneous Y-flip.
     *
     * Why bilinear and not nearest-neighbour?
     *   Nearest-neighbour at 460p from e.g. 1080p produces aliased hotbar icons
     *   and jagged text. Bilinear gives much cleaner results for <2x downscale
     *   with only modest extra cost (all integer arithmetic, no floating point).
     *
     * Why combine scale + flip?
     *   Avoids an extra full-buffer copy. We read src rows bottom-to-top
     *   (compensating for GL's inverted Y) while writing dst top-to-bottom.
     */
    private static void bilinearScaleFlip(byte[] src, int srcW, int srcH,
                                           byte[] dst, int dstW, int dstH) {
        // Fixed-point scale factors (16-bit fraction)
        int xScale = ((srcW - 1) << 16) / dstW;
        int yScale = ((srcH - 1) << 16) / dstH;

        for (int dy = 0; dy < dstH; dy++) {
            // Y-flip: dst row 0 = src row (srcH-1), etc.
            int srcYFixed = (dstH - 1 - dy) * yScale;
            int sy0 = srcYFixed >> 16;
            int sy1 = Math.min(sy0 + 1, srcH - 1);
            int fy  = srcYFixed & 0xFFFF; // fractional part

            int dstRow = dy * dstW * 4;

            for (int dx = 0; dx < dstW; dx++) {
                int srcXFixed = dx * xScale;
                int sx0 = srcXFixed >> 16;
                int sx1 = Math.min(sx0 + 1, srcW - 1);
                int fx  = srcXFixed & 0xFFFF;

                // Sample 4 texels
                int i00 = (sy0 * srcW + sx0) * 4;
                int i10 = (sy0 * srcW + sx1) * 4;
                int i01 = (sy1 * srcW + sx0) * 4;
                int i11 = (sy1 * srcW + sx1) * 4;

                // Bilinear interpolation per channel
                for (int c = 0; c < 4; c++) {
                    int v00 = src[i00+c] & 0xFF;
                    int v10 = src[i10+c] & 0xFF;
                    int v01 = src[i01+c] & 0xFF;
                    int v11 = src[i11+c] & 0xFF;

                    // Lerp along X, then along Y (fixed-point, avoids float)
                    int top    = v00 + (((v10 - v00) * fx) >> 16);
                    int bottom = v01 + (((v11 - v01) * fx) >> 16);
                    int result = top  + (((bottom - top) * fy) >> 16);

                    dst[dstRow + dx * 4 + c] = (byte) result;
                }
            }
        }
    }

    public void cleanup() {
        if (nativePixels != null) {
            MemoryUtil.memFree(nativePixels);
            nativePixels = null;
        }
        initialized = false;
    }
}
