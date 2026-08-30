package com.screenrecorder.mixin;

import com.screenrecorder.recording.RecordingManager;
import com.screenrecorder.recording.RecordingState;
import com.screenrecorder.screen.RecordingGalleryScreen;
import com.screenrecorder.screen.RecordingSettingsScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds recording control buttons to the Minecraft main menu (title screen).
 *
 * Button layout (ReplayMod-inspired, bottom-left corner):
 *   Row 1 (top):    [▶ Start] or [⏸ Pause] / [▶ Resume]   (state-dependent)
 *   Row 2:          [⏹ Stop Recording]                      (hidden when IDLE)
 *   Row 3:          [🎬 My Recordings]  [⚙ Settings]       (always visible)
 *
 * All buttons update each frame to stay in sync with RecordingManager state,
 * including changes triggered by keybindings while on this screen.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    private ButtonWidget startButton;
    private ButtonWidget pauseButton;
    private ButtonWidget resumeButton;
    private ButtonWidget stopButton;
    private ButtonWidget galleryButton;
    private ButtonWidget settingsButton;

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        RecordingManager manager = RecordingManager.getInstance();

        int btnH   = 20;
        int margin = 4;
        int gap    = 2;

        // Bottom row: Gallery + Settings (split into two halves)
        int bottomRowW = 150;
        int halfW      = (bottomRowW - gap) / 2;
        int x          = margin;
        int baseY      = this.height - margin - btnH;

        // Row 3 — always visible
        galleryButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.gallery"),
                btn -> this.client.setScreen(new RecordingGalleryScreen(this))
        ).dimensions(x, baseY, halfW, btnH).build();

        settingsButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.settings"),
                btn -> this.client.setScreen(new RecordingSettingsScreen(this))
        ).dimensions(x + halfW + gap, baseY, halfW, btnH).build();

        // Row 2 — Stop (visible only when not IDLE)
        stopButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.stop"),
                btn -> {
                    manager.stopRecording();
                    updateButtonVisibility();
                }
        ).dimensions(x, baseY - btnH - margin, bottomRowW, btnH).build();

        // Row 1 — Start / Pause / Resume (mutually exclusive)
        startButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.start"),
                btn -> {
                    manager.startRecording();
                    updateButtonVisibility();
                }
        ).dimensions(x, baseY - (btnH + margin) * 2, bottomRowW, btnH).build();

        pauseButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.pause"),
                btn -> {
                    manager.pauseRecording();
                    updateButtonVisibility();
                }
        ).dimensions(x, baseY - (btnH + margin) * 2, bottomRowW, btnH).build();

        resumeButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.resume"),
                btn -> {
                    manager.resumeRecording();
                    updateButtonVisibility();
                }
        ).dimensions(x, baseY - (btnH + margin) * 2, bottomRowW, btnH).build();

        this.addDrawableChild(startButton);
        this.addDrawableChild(pauseButton);
        this.addDrawableChild(resumeButton);
        this.addDrawableChild(stopButton);
        this.addDrawableChild(galleryButton);
        this.addDrawableChild(settingsButton);

        updateButtonVisibility();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onRender(DrawContext context, int mouseX, int mouseY,
                          float delta, CallbackInfo ci) {
        updateButtonVisibility();
    }

    private void updateButtonVisibility() {
        if (startButton == null) return;
        RecordingState state = RecordingManager.getInstance().getState();

        startButton.visible  = (state == RecordingState.IDLE);
        pauseButton.visible  = (state == RecordingState.RECORDING);
        resumeButton.visible = (state == RecordingState.PAUSED);
        stopButton.visible   = (state != RecordingState.IDLE);
        // gallery + settings always visible
        galleryButton.visible  = true;
        settingsButton.visible = true;
    }
}
