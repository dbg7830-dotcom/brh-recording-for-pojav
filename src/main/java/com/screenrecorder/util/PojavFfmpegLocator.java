package com.screenrecorder.util;

import com.screenrecorder.ffmpeg.FFmpegLocator;

/**
 * Delegates to com.screenrecorder.ffmpeg.FFmpegLocator.
 * Kept for backwards compatibility so nothing else needs updating.
 */
public class PojavFfmpegLocator {

    public static String locate() throws Exception {
        return FFmpegLocator.locate();
    }
}
