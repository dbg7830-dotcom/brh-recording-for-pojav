package com.screenrecorder.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Settings screen — currently exposes FPS cap for recording.
 * Resolution is fixed at 460p (816x460) and is not configurable,
 * since the .rawvid → MP4 render pipeline uses the baked-in dimensions.
 *
 * Future: render quality preset (CRF), custom output path.
 */
public class RecordingSettingsScreen extends Screen {

    private final Screen parent;

    private static final int[] FPS_OPTIONS = {15, 20, 30};
    private int fpsIndex = 2; // default 30

    public RecordingSettingsScreen(Screen parent) {
        super(Text.translatable("screenrecorder.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2, bw = 200, bh = 20, sy = height / 2 - 40;

        // FPS cycle button
        addDrawableChild(ButtonWidget.builder(getFpsLabel(), btn -> {
            fpsIndex = (fpsIndex + 1) % FPS_OPTIONS.length;
            btn.setMessage(getFpsLabel());
            // Apply to RecordingManager at runtime if desired
        }).dimensions(cx - bw/2, sy, bw, bh).build());

        // Info label row (resolution is fixed — shown as read-only)
        // Done button
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"),
                btn -> client.setScreen(parent))
                .dimensions(cx - bw/2, sy + 40, bw, bh).build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);
        super.render(ctx, mx, my, delta);

        ctx.drawCenteredTextWithShadow(textRenderer, this.title,
                width / 2, height / 2 - 80, 0xFFFFFF);

        ctx.drawCenteredTextWithShadow(textRenderer,
                net.minecraft.text.Text.literal("Resolution: 816\u00d7460 (460p, fixed)"),
                width / 2, height / 2 - 14, 0x9E9E9E);

        ctx.drawCenteredTextWithShadow(textRenderer,
                net.minecraft.text.Text.literal("Output: .minecraft/recordings/"),
                width / 2, height / 2 + 28, 0x9E9E9E);
    }

    private Text getFpsLabel() {
        return Text.literal("Recording FPS: " + FPS_OPTIONS[fpsIndex]);
    }
}
