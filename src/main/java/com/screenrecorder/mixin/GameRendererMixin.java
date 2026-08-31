package com.screenrecorder.mixin;

import com.screenrecorder.recording.RecordingManager;
import com.screenrecorder.recording.RecordingState;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(
        method = "render",
        at = @At("TAIL")
    )
    private void onRenderTail(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (RecordingManager.getInstance().getState() == RecordingState.RECORDING) {
            RecordingManager.getInstance().onFrameEnd();
        }
    }
}
