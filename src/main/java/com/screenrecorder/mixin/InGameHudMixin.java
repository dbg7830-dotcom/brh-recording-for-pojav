package com.screenrecorder.mixin;

import com.screenrecorder.recording.RecordingManager;
import com.screenrecorder.recording.RecordingState;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.MinecraftClient;

/**
 * Draws a recording status indicator on the HUD.
 * - Red circle + "REC" when recording
 * - Yellow circle + "PAUSED" when paused
 * - Shows elapsed time
 *
 * The indicator is drawn in the top-right corner, similar to ReplayMod's style.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    private static final int REC_COLOR = 0xFFFF3333;       // red
    private static final int PAUSED_COLOR = 0xFFFFAA00;    // amber
    private static final int TEXT_COLOR = 0xFFFFFFFF;      // white
    private static final int BACKGROUND_COLOR = 0x88000000; // semi-transparent black

    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderHud(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter, CallbackInfo ci) {
        RecordingManager manager = RecordingManager.getInstance();
        RecordingState state = manager.getState();

        if (state == RecordingState.IDLE) return;

        MinecraftClient client = MinecraftClient.getInstance();
        int screenWidth = client.getWindow().getScaledWidth();

        boolean isRecording = state == RecordingState.RECORDING;
        String statusText = isRecording ? "● REC" : "⏸ PAUSED";
        int statusColor = isRecording ? REC_COLOR : PAUSED_COLOR;

        // Format elapsed time as MM:SS
        long durationMs = manager.getRecordingDurationMs();
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        String timeText = String.format("%02d:%02d", minutes, seconds);

        // Build stable full text for width calculation (dot always included for consistent sizing)
        String fullText = statusText + " " + timeText;
        String measureText = isRecording ? "● REC " + timeText : fullText;

        int textWidth = client.textRenderer.getWidth(measureText);
        int padding = 4;
        int x = screenWidth - textWidth - padding * 2 - 4;
        int y = 4;
        int bgWidth = textWidth + padding * 2;
        int bgHeight = client.textRenderer.fontHeight + padding * 2;

        // Draw background
        context.fill(x, y, x + bgWidth, y + bgHeight, BACKGROUND_COLOR);

        // Draw text with status color for the indicator dot/icon
        // Split: draw status part in status color, time in white
        int textX = x + padding;
        int textY = y + padding;

        // When recording, blink the dot by alternating its colour every 500ms
        boolean dotVisible = !isRecording || (System.currentTimeMillis() % 1000) < 500;
        int dotColor = dotVisible ? statusColor : BACKGROUND_COLOR;

        if (isRecording) {
            // "● REC MM:SS" — dot blinks, rest is always visible
            context.drawText(client.textRenderer, "●", textX, textY, dotColor, true);
            int dotWidth = client.textRenderer.getWidth("● ");
            context.drawText(client.textRenderer, "REC " + timeText,
                    textX + dotWidth, textY, TEXT_COLOR, true);
        } else {
            // "⏸ PAUSED MM:SS" — static amber, no blink
            context.drawText(client.textRenderer, fullText, textX, textY, statusColor, true);
        }
    }
}
