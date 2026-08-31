package com.screenrecorder.screen;

import com.screenrecorder.ScreenRecorderMod;
import com.screenrecorder.util.PojavFFmpegRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Gallery screen — browse .rawvid recordings, render to MP4, play, delete.
 *
 * Each row has real ButtonWidget buttons (not text labels) so they are
 * always clearly readable and tappable on mobile screens.
 *
 * Rows are rebuilt every time the page changes or entries are refreshed.
 * Desktop.open() is never called — Android uses `am start` intent instead,
 * which avoids the UnsupportedOperationException crash.
 */
public class RecordingGalleryScreen extends Screen {

    // Layout
    private static final int THUMB_W    = 100;
    private static final int THUMB_H    = 56;
    private static final int ROW_H      = 76;
    private static final int ROW_PAD    = 5;
    private static final int LIST_TOP   = 32;
    private static final int FOOTER_H   = 28;
    private static final int BTN_W      = 90;
    private static final int BTN_H      = 16;
    private static final int ROWS_PAGE  = 5;

    // Colours
    private static final int C_BG      = 0xFF1A1A2E;
    private static final int C_ROW_A   = 0xFF16213E;
    private static final int C_ROW_B   = 0xFF0F3460;
    private static final int C_ACCENT  = 0xFFE94560;
    private static final int C_TEXT    = 0xFFEEEEEE;
    private static final int C_SUB     = 0xFF9E9E9E;
    private static final int C_THBG    = 0xFF0D0D1A;
    private static final int C_GREEN   = 0xFF81C784;
    private static final int C_SUB2    = 0xFF888888;

    private final Screen parent;
    private final ThumbnailCache thumbs = new ThumbnailCache();

    private List<RecordingEntry> all      = new ArrayList<>();
    private List<RecordingEntry> filtered = new ArrayList<>();
    private int    page         = 0;
    private String renderStatus = "";

    // Row buttons — rebuilt when page changes
    private final List<ButtonWidget> rowButtons = new ArrayList<>();

    private TextFieldWidget searchBox;
    private ButtonWidget    prevBtn, nextBtn;

    public RecordingGalleryScreen(Screen parent) {
        super(Text.translatable("screenrecorder.gallery.title"));
        this.parent = parent;
    }

    // ── Init / rebuild ────────────────────────────────────────────────────────

    @Override
    protected void init() {
        loadEntries();
        applyFilter("");
        buildFooterButtons();
        buildRowButtons();
    }

    private void buildFooterButtons() {
        int bh = 20, footY = height - FOOTER_H + 4;

        prevBtn = ButtonWidget.builder(Text.literal("< Prev"),
                btn -> changePage(-1)).dimensions(width / 2 - 110, footY, 60, bh).build();
        nextBtn = ButtonWidget.builder(Text.literal("Next >"),
                btn -> changePage(1)).dimensions(width / 2 + 50, footY, 60, bh).build();

        addDrawableChild(ButtonWidget.builder(Text.literal("↻"),
                btn -> { loadEntries(); applyFilter(searchBox != null ? searchBox.getText() : ""); rebuildRowButtons(); })
                .dimensions(6, footY, 30, bh).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"),
                btn -> close())
                .dimensions(width - 76, footY, 70, bh).build());

        addDrawableChild(prevBtn);
        addDrawableChild(nextBtn);

        searchBox = new TextFieldWidget(textRenderer,
                width - 170, 8, 162, 16, Text.literal("Search…"));
        searchBox.setPlaceholder(Text.literal("Search…"));
        searchBox.setChangedListener(q -> { page = 0; applyFilter(q); rebuildRowButtons(); });
        addDrawableChild(searchBox);
    }

    private void buildRowButtons() {
        rowButtons.clear();
        List<RecordingEntry> entries = pageEntries();
        int rowY = LIST_TOP + ROW_PAD;

        for (RecordingEntry e : entries) {
            int textX = ROW_PAD + 8 + THUMB_W + 8;
            int btnY  = rowY + ROW_H - BTN_H - 6;
            int bx    = textX;

            if (e.renderedMp4 != null) {
                // Play button
                ButtonWidget play = ButtonWidget.builder(Text.literal("▶ Play"),
                        btn -> playFile(e.renderedMp4))
                        .dimensions(bx, btnY, BTN_W, BTN_H).build();
                rowButtons.add(play);
                addDrawableChild(play);
                bx += BTN_W + 4;
            } else if (PojavFFmpegRenderer.canRender()) {
                // Render button
                ButtonWidget render = ButtonWidget.builder(Text.literal("Render MP4"),
                        btn -> { if (!e.rendering) startRender(e); })
                        .dimensions(bx, btnY, BTN_W, BTN_H).build();
                rowButtons.add(render);
                addDrawableChild(render);
                bx += BTN_W + 4;
            }

            // Delete button — always present
            ButtonWidget del = ButtonWidget.builder(Text.literal("Delete"),
                    btn -> confirmDelete(e))
                    .dimensions(bx, btnY, BTN_W - 20, BTN_H).build();
            rowButtons.add(del);
            addDrawableChild(del);

            rowY += ROW_H + ROW_PAD;
        }

        updatePaginationButtons();
    }

    private void rebuildRowButtons() {
        // Remove old row buttons from children, then rebuild
        for (ButtonWidget b : rowButtons) remove(b);
        rowButtons.clear();
        buildRowButtons();
    }

    private void changePage(int delta) {
        page = Math.max(0, Math.min(page + delta, totalPages() - 1));
        rebuildRowButtons();
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    private void loadEntries() {
        File dir = new File(MinecraftClient.getInstance().runDirectory, "recordings");
        dir.mkdirs();
        all.clear();
        File[] files = dir.listFiles(f ->
                f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".rawvid"));
        if (files != null)
            for (File f : files) all.add(new RecordingEntry(f));
        all.sort(Comparator.comparingLong((RecordingEntry e) -> e.lastModified).reversed());
    }

    private void applyFilter(String q) {
        String lq = q.trim().toLowerCase(Locale.ROOT);
        filtered = lq.isEmpty() ? new ArrayList<>(all)
                : all.stream().filter(e ->
                    e.displayName.toLowerCase(Locale.ROOT).contains(lq) ||
                    e.dateString.toLowerCase(Locale.ROOT).contains(lq))
                  .collect(Collectors.toList());
        page = Math.max(0, Math.min(page, totalPages() - 1));
    }

    private int totalPages() {
        return Math.max(1, (int) Math.ceil(filtered.size() / (double) ROWS_PAGE));
    }

    private List<RecordingEntry> pageEntries() {
        int from = page * ROWS_PAGE;
        int to   = Math.min(from + ROWS_PAGE, filtered.size());
        return from >= filtered.size() ? List.of() : filtered.subList(from, to);
    }

    private void updatePaginationButtons() {
        if (prevBtn != null) prevBtn.active = page > 0;
        if (nextBtn != null) nextBtn.active = page < totalPages() - 1;
    }

    // ── Render ───────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // Background
        ctx.fill(0, 0, width, height, C_BG);

        // Header bar
        ctx.fill(0, 0, width, LIST_TOP, 0xFF0D0D1A);
        ctx.fill(0, LIST_TOP - 1, width, LIST_TOP, C_ACCENT);
        ctx.drawTextWithShadow(textRenderer,
                Text.translatable("screenrecorder.gallery.title"), 8, 10, C_ACCENT);
        ctx.drawTextWithShadow(textRenderer,
                filtered.size() + " recording" + (filtered.size() != 1 ? "s" : ""),
                8 + textRenderer.getWidth("My Recordings") + 12, 10, C_SUB);

        // Rows
        renderRows(ctx, mx, my);

        // Footer bar
        int fy = height - FOOTER_H;
        ctx.fill(0, fy, width, height, 0xFF0D0D1A);
        ctx.fill(0, fy, width, fy + 1, C_ACCENT);
        ctx.drawCenteredTextWithShadow(textRenderer,
                "Page " + (page + 1) + " / " + totalPages(),
                width / 2, height - 10, C_SUB);
        if (!renderStatus.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, renderStatus, 40, height - 10, 0xFFFFB74D);
        }

        updatePaginationButtons();
        super.render(ctx, mx, my, delta);
    }

    private void renderRows(DrawContext ctx, int mx, int my) {
        List<RecordingEntry> entries = pageEntries();
        int rowY = LIST_TOP + ROW_PAD;

        for (int i = 0; i < entries.size(); i++) {
            RecordingEntry e = entries.get(i);
            int rowX = ROW_PAD;
            int rowW = width - ROW_PAD * 2;

            // Row bg
            ctx.fill(rowX, rowY, rowX + rowW, rowY + ROW_H,
                    i % 2 == 0 ? C_ROW_A : C_ROW_B);
            // Accent left bar
            ctx.fill(rowX, rowY, rowX + 3, rowY + ROW_H, C_ACCENT);

            // Thumbnail
            int tx = rowX + 8;
            int ty = rowY + (ROW_H - THUMB_H) / 2;
            renderThumb(ctx, e, tx, ty);

            // Text
            int textX = tx + THUMB_W + 8;
            int textY = rowY + 6;

            // Name (clip if too long)
            String name = e.displayName;
            int maxNameW = rowW - textX - 60;
            while (name.length() > 4 && textRenderer.getWidth(name) > maxNameW) {
                name = name.substring(0, name.length() - 4) + "…";
            }
            ctx.drawTextWithShadow(textRenderer, name, textX, textY, C_TEXT);

            // Badge top-right
            String badge  = e.renderedMp4 != null ? "MP4 ✓" : ".rawvid";
            int    badgeC = e.renderedMp4 != null ? C_GREEN : C_SUB2;
            ctx.drawTextWithShadow(textRenderer, badge,
                    rowX + rowW - textRenderer.getWidth(badge) - 6, textY, badgeC);

            // Date + size
            ctx.drawTextWithShadow(textRenderer,
                    e.dateString + "  " + e.sizeString,
                    textX, textY + 12, C_SUB);

            // Rendering status
            if (e.rendering) {
                ctx.drawTextWithShadow(textRenderer,
                        "⏳ " + (renderStatus.isEmpty() ? "Rendering…" : renderStatus),
                        textX, rowY + ROW_H - 22, 0xFFFFB74D);
            }

            rowY += ROW_H + ROW_PAD;
        }

        if (entries.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    filtered.isEmpty() && (searchBox == null || searchBox.getText().isEmpty())
                            ? "No recordings yet — start one from the pause menu!"
                            : "No recordings match your search.",
                    width / 2, LIST_TOP + (height - LIST_TOP - FOOTER_H) / 2, C_SUB);
        }
    }

    private void renderThumb(DrawContext ctx, RecordingEntry e, int x, int y) {
        ctx.fill(x, y, x + THUMB_W, y + THUMB_H, C_THBG);
        Identifier id = thumbs.getThumbnail(e);
        if (id != null) {
            ctx.drawTexture(id, x, y, 0, 0, THUMB_W, THUMB_H);
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer, "🎬",
                    x + THUMB_W / 2, y + THUMB_H / 2 - 4, C_SUB);
        }
        // Border
        ctx.fill(x,            y,            x + THUMB_W, y + 1,          C_ACCENT);
        ctx.fill(x,            y + THUMB_H-1, x + THUMB_W, y + THUMB_H,   C_ACCENT);
        ctx.fill(x,            y,            x + 1,        y + THUMB_H,   C_ACCENT);
        ctx.fill(x + THUMB_W-1, y,           x + THUMB_W, y + THUMB_H,   C_ACCENT);
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private void startRender(RecordingEntry e) {
        renderStatus = "Starting…";
        PojavFFmpegRenderer.renderAsync(e,
            progress -> renderStatus = progress.length() > 50
                    ? progress.substring(0, 47) + "…" : progress,
            mp4 -> {
                renderStatus = "";
                MinecraftClient.getInstance().execute(() -> {
                    loadEntries();
                    applyFilter(searchBox != null ? searchBox.getText() : "");
                    rebuildRowButtons();
                });
            }
        );
    }

    /**
     * Open a file using an Android intent.
     * NEVER uses java.awt.Desktop — that crashes on Android even when
     * isDesktopSupported() returns true (Caciocavallo partial AWT support).
     */
    private void playFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            Runtime.getRuntime().exec(new String[]{
                "am", "start",
                "-a", "android.intent.action.VIEW",
                "-d", "file://" + file.getAbsolutePath(),
                "-t", "video/mp4",
                "--flags", "0x10000001"  // FLAG_ACTIVITY_NEW_TASK | FLAG_GRANT_READ_URI_PERMISSION
            });
        } catch (IOException ex) {
            ScreenRecorderMod.LOGGER.error("Could not play file: " + file, ex);
        }
    }

    private void confirmDelete(RecordingEntry e) {
        client.setScreen(new ConfirmScreen(
            confirmed -> {
                if (confirmed) {
                    e.file.delete();
                    if (e.renderedMp4 != null) e.renderedMp4.delete();
                    loadEntries();
                    applyFilter(searchBox != null ? searchBox.getText() : "");
                    rebuildRowButtons();
                }
                client.setScreen(this);
            },
            Text.translatable("screenrecorder.gallery.delete_confirm_title"),
            Text.translatable("screenrecorder.gallery.delete_confirm_body", e.displayName)
        ));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if      (v < 0 && page < totalPages() - 1) changePage(1);
        else if (v > 0 && page > 0)                changePage(-1);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused())
            return super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == 263 && page > 0)                { changePage(-1); return true; }
        if (keyCode == 262 && page < totalPages() - 1) { changePage(1);  return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        thumbs.dispose();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
