package com.screenrecorder.mixin;

import com.screenrecorder.recording.RecordingManager;
import com.screenrecorder.recording.RecordingState;
import com.screenrecorder.screen.RecordingGalleryScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds recording buttons to the in-game pause menu (Game Menu).
 *
 * Looking at the screenshot, the vanilla pause menu has these buttons centred:
 *   Back to Game
 *   Advancements | Statistics
 *   Feedback...  | Server Links...
 *   Options...   | Player Reporting
 *   Disconnect
 *
 * We place our buttons in the BOTTOM-LEFT corner so they never overlap
 * any vanilla button regardless of screen size. Layout:
 *
 *   y = height - 4 - 20        → [⏹ Stop Recording]   (hidden when IDLE)
 *   y = height - 4 - 20*2 - 4  → [▶ Start] or [⏸ Pause] or [▶ Resume]
 *   y = height - 4 - 20*3 - 8  → [🎬 Recordings]
 *
 * Button width = 150px, positioned at x=4.
 * Vanilla buttons are centred around width/2 and are 200px wide, so they
 * start at width/2 - 100 = never less than ~140px from left on any screen.
 * 150+4=154px — safe on any screen 308px or wider (all supported devices).
 */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {

    private ButtonWidget recStartButton;
    private ButtonWidget recPauseButton;
    private ButtonWidget recResumeButton;
    private ButtonWidget recStopButton;
    private ButtonWidget recGalleryButton;

    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        RecordingManager manager = RecordingManager.getInstance();

        int w      = 150;
        int h      = 20;
        int x      = 4;
        int gap    = 4;
        int baseY  = this.height - 4 - h;  // bottom of screen with 4px margin

        // Row 1 (bottom): Stop — only visible while recording or paused
        recStopButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.stop"),
                btn -> {
                    manager.stopRecording();
                    updateButtons();
                }
        ).dimensions(x, baseY, w, h).build();

        // Row 2: Start / Pause / Resume (mutually exclusive, same position)
        recStartButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.start"),
                btn -> {
                    manager.startRecording();
                    updateButtons();
                }
        ).dimensions(x, baseY - h - gap, w, h).build();

        recPauseButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.pause"),
                btn -> {
                    manager.pauseRecording();
                    updateButtons();
                }
        ).dimensions(x, baseY - h - gap, w, h).build();

        recResumeButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.resume"),
                btn -> {
                    manager.resumeRecording();
                    updateButtons();
                }
        ).dimensions(x, baseY - h - gap, w, h).build();

        // Row 3 (top): Gallery — always visible
        recGalleryButton = ButtonWidget.builder(
                Text.translatable("screenrecorder.button.gallery"),
                btn -> this.client.setScreen(new RecordingGalleryScreen(this))
        ).dimensions(x, baseY - (h + gap) * 2, w, h).build();

        this.addDrawableChild(recGalleryButton);
        this.addDrawableChild(recStartButton);
        this.addDrawableChild(recPauseButton);
        this.addDrawableChild(recResumeButton);
        this.addDrawableChild(recStopButton);

        updateButtons();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onRender(DrawContext ctx, int mx, int my, float delta, CallbackInfo ci) {
        // Keep buttons in sync if state changed via keybind while menu was open
        updateButtons();
    }

    private void updateButtons() {
        if (recStartButton == null) return;
        RecordingState state = RecordingManager.getInstance().getState();

        recStartButton.visible  = (state == RecordingState.IDLE);
        recPauseButton.visible  = (state == RecordingState.RECORDING);
        recResumeButton.visible = (state == RecordingState.PAUSED);
        recStopButton.visible   = (state != RecordingState.IDLE);
        recGalleryButton.visible = true;
    }
          }
