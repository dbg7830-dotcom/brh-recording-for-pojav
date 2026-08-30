package com.screenrecorder;

import com.screenrecorder.recording.RecordingManager;
import com.screenrecorder.recording.RecordingState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class ScreenRecorderClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ScreenRecorderMod.LOGGER.info("ScreenRecorder Client initialized");

        // Register keybindings (must be done in client entrypoint)
        ScreenRecorderMod.startRecordingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.screenrecorder.start",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                "category.screenrecorder.keys"
        ));
        ScreenRecorderMod.pauseRecordingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.screenrecorder.pause",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                "category.screenrecorder.keys"
        ));
        ScreenRecorderMod.stopRecordingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.screenrecorder.stop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F11,
                "category.screenrecorder.keys"
        ));

        // Initialize RecordingManager (allocates FrameCapture buffers)
        RecordingManager.getInstance().initialize();

        // Tick event for keybinding handling
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RecordingManager manager = RecordingManager.getInstance();

            while (ScreenRecorderMod.startRecordingKey.wasPressed()) {
                if (manager.getState() == RecordingState.IDLE) {
                    manager.startRecording();
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.translatable("screenrecorder.message.started"), true);
                    }
                } else if (manager.getState() == RecordingState.PAUSED) {
                    manager.resumeRecording();
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.translatable("screenrecorder.message.resumed"), true);
                    }
                }
            }

            while (ScreenRecorderMod.pauseRecordingKey.wasPressed()) {
                if (manager.getState() == RecordingState.RECORDING) {
                    manager.pauseRecording();
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.translatable("screenrecorder.message.paused"), true);
                    }
                }
            }

            while (ScreenRecorderMod.stopRecordingKey.wasPressed()) {
                if (manager.getState() != RecordingState.IDLE) {
                    manager.stopRecording();
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.translatable("screenrecorder.message.stopped"), true);
                    }
                }
            }
        });
    }
}
