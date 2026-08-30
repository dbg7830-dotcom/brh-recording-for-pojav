package com.screenrecorder;

import net.fabricmc.api.ModInitializer;
import net.minecraft.client.option.KeyBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScreenRecorderMod implements ModInitializer {

    public static final String MOD_ID = "screenrecorder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Populated by ScreenRecorderClient during client init
    public static KeyBinding startRecordingKey;
    public static KeyBinding pauseRecordingKey;
    public static KeyBinding stopRecordingKey;

    @Override
    public void onInitialize() {
        LOGGER.info("ScreenRecorder Mod initialised");
        // All setup happens client-side in ScreenRecorderClient
    }
}
