package com.screenrecorder.ffmpeg;

import com.screenrecorder.ScreenRecorderMod;
import net.minecraft.client.MinecraftClient;

import java.io.File;

/**
 * Locates the FFmpeg binary from the PojavLauncher FFmpegPlugin APK.
 *
 * The FFmpegPlugin installs as a standard Android APK.
 * Its package ID is net.kdt.pojavlaunch.ffmpeg.
 * The binary lives inside that app's data directory.
 *
 * Zalith Launcher (com.movtery.zalithlauncher) also ships FFmpeg
 * in its own files directory — confirmed from crash log paths.
 */
public class FFmpegLocator {

    public static class FFmpegNotFoundException extends Exception {
        public FFmpegNotFoundException(String msg) { super(msg); }
    }

    private static String cached = null;

    private static final String[] KNOWN_PATHS = {
        // PojavLauncher FFmpegPlugin (net.kdt.pojavlaunch.ffmpeg)
        "/data/data/net.kdt.pojavlaunch.ffmpeg/files/ffmpeg",
        "/data/user/0/net.kdt.pojavlaunch.ffmpeg/files/ffmpeg",

        // Zalith Launcher built-in FFmpeg (com.movtery.zalithlauncher — confirmed package)
        "/data/data/com.movtery.zalithlauncher/files/ffmpeg",
        "/data/user/0/com.movtery.zalithlauncher/files/ffmpeg",

        // PojavLauncher itself sometimes ships FFmpeg
        "/data/data/net.kdt.pojavlaunch/files/ffmpeg",
        "/data/user/0/net.kdt.pojavlaunch/files/ffmpeg",

        // DroidBridge
        "/data/data/com.droidbridge.launcher/files/ffmpeg",
        "/data/user/0/com.droidbridge.launcher/files/ffmpeg",

        // Termux (users sometimes have this installed alongside)
        "/data/data/com.termux/files/usr/bin/ffmpeg",
        "/data/user/0/com.termux/files/usr/bin/ffmpeg",
    };

    /**
     * Find the FFmpeg binary. Result is cached after the first successful call.
     * @throws FFmpegNotFoundException with a helpful message if not found anywhere.
     */
    public static String locate() throws FFmpegNotFoundException {
        if (cached != null) return cached;

        // 1. Known fixed paths
        for (String path : KNOWN_PATHS) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) {
                ScreenRecorderMod.LOGGER.info("[FFmpegLocator] Found at: " + path);
                cached = path;
                return cached;
            }
        }

        // 2. System PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File f = new File(dir, "ffmpeg");
                if (f.canExecute()) {
                    ScreenRecorderMod.LOGGER.info("[FFmpegLocator] Found on PATH: " + f);
                    cached = f.getAbsolutePath();
                    return cached;
                }
            }
        }

        // 3. .minecraft/ffmpeg — drop-in fallback for any binary the user places manually
        File localFfmpeg = new File(
                MinecraftClient.getInstance().runDirectory, "ffmpeg");
        if (localFfmpeg.exists()) {
            localFfmpeg.setExecutable(true);
            ScreenRecorderMod.LOGGER.info("[FFmpegLocator] Using .minecraft/ffmpeg");
            cached = localFfmpeg.getAbsolutePath();
            return cached;
        }

        throw new FFmpegNotFoundException(
            "FFmpeg not found. Install the PojavLauncher FFmpegPlugin APK from:\n" +
            "https://github.com/PojavLauncherTeam/FFmpegPlugin/releases\n" +
            "Or drop an ffmpeg binary into your .minecraft folder."
        );
    }

    /** Clear cached path — useful if the plugin was installed after game launch. */
    public static void clearCache() {
        cached = null;
    }
}
