package com.screenrecorder.recording;

import com.screenrecorder.ScreenRecorderMod;
import com.screenrecorder.util.FrameCapture;
import com.screenrecorder.util.RawFrameWriter;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core recording state machine.
 *
 * Strategy: OBS-style full-framebuffer capture, deferred encoding.
 *
 * DURING GAMEPLAY:
 *   1. glReadPixels captures the complete rendered frame (game + HUD + hotbar +
 *      health + hunger + item cooldowns + XP bar — everything the player sees).
 *   2. Frames are scaled down to 816x460 (460p, 16:9) on the CPU.
 *   3. Raw RGBA bytes are written to a .rawvid file via a background IO thread.
 *      Zero compression, zero encoding — the GPU read + scale is the only cost.
 *
 * OUT OF GAME (from Gallery):
 *   The PojavLauncher FFmpeg plugin reads the .rawvid file and encodes it to
 *   H.264 MP4 at whatever pace the CPU allows, with no gameplay to interrupt.
 *
 * .rawvid file format (simple, no external library needed):
 *   Header (32 bytes):
 *     [0..3]   magic: "SCRV"
 *     [4..7]   version: 1 (int32 LE)
 *     [8..11]  width in pixels (int32 LE)
 *     [12..15] height in pixels (int32 LE)
 *     [16..19] fps (int32 LE)
 *     [20..31] reserved (zeros)
 *   Body:
 *     Repeated frames, each:
 *     [0..3]  frame timestamp ms (int32 LE)
 *     [4..N]  raw RGBA bytes, width*height*4
 *
 * The header gives FFmpeg everything it needs:
 *   ffmpeg -f rawvideo -pix_fmt rgba -s WxH -r FPS -i recording.rawvid out.mp4
 */
public class RecordingManager {

    // Target capture resolution — 460p 16:9
    public static final int CAPTURE_WIDTH  = 816;
    public static final int CAPTURE_HEIGHT = 460;
    public static final int CAPTURE_FPS    = 30;

    private static RecordingManager instance;

    private final AtomicReference<RecordingState> state = new AtomicReference<>(RecordingState.IDLE);

    private FrameCapture  frameCapture;
    private RawFrameWriter frameWriter;
    private File           outputFile;

    private long recordingStartTime;
    private long pausedDuration;
    private long pauseStartTime;

    // Frame timing — cap to CAPTURE_FPS
    private long lastFrameTimeNs = 0;
    private static final long FRAME_INTERVAL_NS = 1_000_000_000L / CAPTURE_FPS;

    private RecordingManager() {}

    public static RecordingManager getInstance() {
        if (instance == null) instance = new RecordingManager();
        return instance;
    }

    public void initialize() {
        frameCapture = new FrameCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT);
        ScreenRecorderMod.LOGGER.info(
            "RecordingManager ready — " + CAPTURE_WIDTH + "x" + CAPTURE_HEIGHT +
            " @ " + CAPTURE_FPS + "fps, OBS-style framebuffer capture");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean startRecording() {
        if (state.get() != RecordingState.IDLE) return false;

        outputFile = createOutputFile();
        ScreenRecorderMod.LOGGER.info("Starting recording → " + outputFile.getName());

        try {
            frameWriter = new RawFrameWriter(outputFile, CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS);
            frameWriter.start();

            recordingStartTime = System.currentTimeMillis();
            pausedDuration     = 0;
            lastFrameTimeNs    = 0;

            state.set(RecordingState.RECORDING);
            ScreenRecorderMod.LOGGER.info("Recording started");
            return true;

        } catch (Exception e) {
            ScreenRecorderMod.LOGGER.error("Failed to start recording", e);
            cleanup();
            return false;
        }
    }

    public boolean pauseRecording() {
        if (state.get() != RecordingState.RECORDING) return false;
        pauseStartTime = System.currentTimeMillis();
        state.set(RecordingState.PAUSED);
        ScreenRecorderMod.LOGGER.info("Recording paused at " + (getRecordingDurationMs()/1000) + "s");
        return true;
    }

    public boolean resumeRecording() {
        if (state.get() != RecordingState.PAUSED) return false;
        pausedDuration += System.currentTimeMillis() - pauseStartTime;
        lastFrameTimeNs = 0; // reset frame timer so next frame isn't skipped
        state.set(RecordingState.RECORDING);
        ScreenRecorderMod.LOGGER.info("Recording resumed");
        return true;
    }

    public void stopRecording() {
        if (state.get() == RecordingState.IDLE) return;
        long duration = getRecordingDurationMs();
        state.set(RecordingState.IDLE);

        if (frameWriter != null) {
            frameWriter.finish(); // flushes + closes file, handles its own exceptions
        }
        ScreenRecorderMod.LOGGER.info(
            "Recording saved: " + outputFile.getName() +
            " (" + (duration / 1000) + "s, " + (outputFile.length() / 1024 / 1024) + " MB raw)");
        cleanup();
    }

    /**
     * Called by GameRendererMixin just before swapBuffers().
     * Captures the complete framebuffer — game world + all HUD elements.
     * Frame-rate limited to CAPTURE_FPS; excess frames are skipped (not dropped to queue).
     */
    public void onFrameEnd() {
        if (state.get() != RecordingState.RECORDING) return;

        // FPS cap — skip frames that arrive too soon
        long now = System.nanoTime();
        if (lastFrameTimeNs != 0 && (now - lastFrameTimeNs) < FRAME_INTERVAL_NS) return;
        lastFrameTimeNs = now;

        // Read framebuffer (scaled to 460p), hand off to IO thread immediately
        byte[] frame = frameCapture.captureAndScale();
        if (frame != null) {
            int timestampMs = (int) getRecordingDurationMs();
            frameWriter.enqueueFrame(frame, timestampMs);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void cleanup() {
        frameWriter = null;
    }

    private File createOutputFile() {
        File dir = new File(MinecraftClient.getInstance().runDirectory, "recordings");
        dir.mkdirs();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        return new File(dir, "recording_" + ts + ".rawvid");
    }

    public RecordingState getState()          { return state.get(); }
    public File           getOutputFile()     { return outputFile; }

    public long getRecordingDurationMs() {
        if (state.get() == RecordingState.IDLE) return 0;
        long base = System.currentTimeMillis() - recordingStartTime - pausedDuration;
        if (state.get() == RecordingState.PAUSED)
            base -= (System.currentTimeMillis() - pauseStartTime);
        return Math.max(0, base);
    }
}
