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
        method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;swapBuffers()V",
            shift = At.Shift.BEFORE
        )
    )
    private void onBeforeSwapBuffers(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (RecordingManager.getInstance().getState() == RecordingState.RECORDING) {
            RecordingManager.getInstance().onFrameEnd();
        }
    }
}


/**
 * Hooks into the render loop just before the framebuffer is swapped to the screen.
 *
 * At this exact injection point, the framebuffer contains:
 *   ✓ Rendered world geometry
 *   ✓ Entity models and name tags
 *   ✓ Particle effects
 *   ✓ Hotbar (all slots, selected item highlight)
 *   ✓ Item cooldown overlays (the pie-slice triangles on items like Ender Pearls)
 *   ✓ Health, hunger, armour, oxygen bars
 *   ✓ XP bar and XP level number
 *   ✓ Boss bars (Ender Dragon, Wither, etc.)
 *   ✓ Status effect icons
 *   ✓ Chat messages
 *   ✓ Tab list
 *   ✓ Crosshair
 *   ✓ Scoreboard
 *   ✓ Any HUD added by other mods (Sodium, etc.)
 *
 * MobileGlues note:
 *   MobileGlues performs buffer swap via eglSwapBuffers(). Injecting BEFORE
 *   Window.swapBuffers() guarantees we read the completed frame, not a partial one.
 *   The GLES 3.0 spec guarantees glReadPixels() is synchronous on the current
 *   context, so there is no race condition here.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;swapBuffers()V",
            shift  = At.Shift.BEFORE
        )
    )
    private void onBeforeSwapBuffers(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (RecordingManager.getInstance().getState() == RecordingState.RECORDING) {
            RecordingManager.getInstance().onFrameEnd();
        }
    }
}
