package com.screenrecorder.util;

import com.screenrecorder.ScreenRecorderMod;
import net.minecraft.client.MinecraftClient;

import java.io.File;

/**
 * Locates the FFmpeg binary provided by the PojavLauncher FFmpeg plugin.
 *
 * The plugin installs ffmpeg at one of these well-known paths depending on launcher:
 *
 *   Zalith Launcher (Android):
 *     /data/data/com.zalith.launcher/files/ffmpeg
 *     /sdcard/Android/data/com.zalith.launcher/files/ffmpeg
 *
 *   PojavLauncher (Android):
 *     /data/data/net.kdt.pojavlaunch/files/ffmpeg
 *     /data/user/0/net.kdt.pojavlaunch/files/ffmpeg
 *
 *   DroidBridge (Android):
 *     /data/data/com.droidbridge.launcher/files/ffmpeg
 *
 *   System PATH (desktop / Termux):
 *     just "ffmpeg" — found via PATH lookup
 *
 * Falls back to the PATH if none of the above exist.
 */
public class PojavFfmpegLocator {

    private static String cached = null;

    private static final String[] KNOWN_PATHS = {
        // Zalith Launcher
        "/data/data/com.zalith.launcher/files/ffmpeg",
        "/sdcard/Android/data/com.zalith.launcher/files/ffmpeg",
        // PojavLauncher (original)
        "/data/data/net.kdt.pojavlaunch/files/ffmpeg",
        "/data/user/0/net.kdt.pojavlaunch/files/ffmpeg",
        // DroidBridge
        "/data/data/com.droidbridge.launcher/files/ffmpeg",
        // Termux (often available alongside launchers)
        "/data/data/com.termux/files/usr/bin/ffmpeg",
    };

    public static String locate() throws Exception {
        if (cached != null) return cached;

        // 1. Known launcher paths
        for (String path : KNOWN_PATHS) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) {
                ScreenRecorderMod.LOGGER.info("Found PojavFFmpeg at: " + path);
                cached = path;
                return cached;
            }
        }

        // 2. System PATH
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            File f = new File(dir, "ffmpeg");
            if (f.canExecute()) {
                ScreenRecorderMod.LOGGER.info("Found ffmpeg on PATH: " + f.getAbsolutePath());
                cached = f.getAbsolutePath();
                return cached;
            }
        }

        // 3. .minecraft/ffmpeg (user-placed binary as last resort)
        File localFfmpeg = new File(MinecraftClient.getInstance().runDirectory, "ffmpeg");
        if (localFfmpeg.exists()) {
            localFfmpeg.setExecutable(true);
            cached = localFfmpeg.getAbsolutePath();
            ScreenRecorderMod.LOGGER.info("Using local ffmpeg: " + cached);
            return cached;
        }

        throw new Exception(
            "FFmpeg not found! Install the PojavLauncher FFmpeg plugin, " +
            "or place an ffmpeg binary in your .minecraft folder."
        );
    }
}
