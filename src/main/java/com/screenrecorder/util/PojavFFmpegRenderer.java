package com.screenrecorder.util;

import com.screenrecorder.ffmpeg.FFmpegBridge;
import com.screenrecorder.recording.RecordingManager;
import com.screenrecorder.screen.RecordingEntry;

import java.io.File;
import java.util.function.Consumer;

/**
 * Thin wrapper — delegates all FFmpeg work to com.screenrecorder.ffmpeg.FFmpegBridge.
 * Kept here so the gallery screen doesn't need to change its import.
 */
public class PojavFFmpegRenderer {

    public static void renderAsync(RecordingEntry entry,
                                   Consumer<String> onProgress,
                                   Consumer<File>   onComplete) {
        entry.rendering = true;

        FFmpegBridge.renderRawvid(
            entry.file,
            new File(entry.file.getParent(), entry.displayName + ".mp4"),
            RecordingManager.CAPTURE_WIDTH,
            RecordingManager.CAPTURE_HEIGHT,
            RecordingManager.CAPTURE_FPS,
            onProgress,
            (success, mp4) -> {
                entry.rendering   = false;
                entry.renderedMp4 = success ? mp4 : null;
                onComplete.accept(success ? mp4 : null);
            }
        );
    }

    public static boolean canRender() {
        return FFmpegBridge.isAvailable();
    }
}
