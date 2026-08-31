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
 * Adds recording buttons to the main menu (title screen).
 * Same layout as GameMenuScreenMixin but also includes Settings button.
 * Buttons sit in the bottom-left corner and never overlap vanilla UI.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    private ButtonWidget recStartButton;
    private ButtonWidget recPauseButton;
    private ButtonWidget recResumeButton;
    private ButtonWidget recStopButton;
    private ButtonWidget recGalleryButton;
    private ButtonWidget recSettingsButton;

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        RecordingManager manager = RecordingManager.getInstance();

        int w     = 150;
        int h     = 20;
        int x     = 4;
        int gap   = 4;
        int baseY = this.height - 4 - h;

        // Row 1 (bottom): Gallery + Settings side by side
        int halfW = (w - gap) / 2;
        recGalleryButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.gallery"),
                btn -> this.client.setScreen(new RecordingGalleryScreen(this))
        ).dimensions(x, baseY, halfW, h).build();

        recSettingsButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.settings"),
                btn -> this.client.setScreen(new RecordingSettingsScreen(this))
        ).dimensions(x + halfW + gap, baseY, halfW, h).build();

        // Row 2: Stop
        recStopButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.stop"),
                btn -> { manager.stopRecording(); updateButtons(); }
        ).dimensions(x, baseY - h - gap, w, h).build();

        // Row 3: Start / Pause / Resume (same position, mutually exclusive)
        recStartButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.start"),
                btn -> { manager.startRecording(); updateButtons(); }
        ).dimensions(x, baseY - (h + gap) * 2, w, h).build();

        recPauseButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.pause"),
                btn -> { manager.pauseRecording(); updateButtons(); }
        ).dimensions(x, baseY - (h + gap) * 2, w, h).build();

        recResumeButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.resume"),
                btn -> { manager.resumeRecording(); updateButtons(); }
        ).dimensions(x, baseY - (h + gap) * 2, w, h).build();

        this.addDrawableChild(recStartButton);
        this.addDrawableChild(recPauseButton);
        this.addDrawableChild(recResumeButton);
        this.addDrawableChild(recStopButton);
        this.addDrawableChild(recGalleryButton);
        this.addDrawableChild(recSettingsButton);

        updateButtons();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onRender(DrawContext ctx, int mx, int my, float delta, CallbackInfo ci) {
        updateButtons();
    }

    private void updateButtons() {
        if (recStartButton == null) return;
        RecordingState state = RecordingManager.getInstance().getState();
        recStartButton.visible  = (state == RecordingState.IDLE);
        recPauseButton.visible  = (state == RecordingState.RECORDING);
        recResumeButton.visible = (state == RecordingState.PAUSED);
        recStopButton.visible   = (state != RecordingState.IDLE);
        recGalleryButton.visible  = true;
        recSettingsButton.visible = true;
    }
}
