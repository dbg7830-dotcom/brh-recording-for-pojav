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
 * Gallery screen — browse .rawvid recordings, render them to MP4,
 * play finished videos, or delete entries.
 *
 * Layout (ReplayMod-inspired, bottom-left control buttons):
 * ┌──────────────────────────────────────────────────────────────┐
 * │  🎬 My Recordings          3 recordings   [🔍 Search...   ] │
 * ├──────────────────────────────────────────────────────────────┤
 * │▌ ┌──────────┐  recording_2024-06-15_14-32-07      [.rawvid] │
 * │  │  🎬      │  Jun 15, 2024  14:32  •  210 MB               │
 * │  │ Loading… │  [ 🎬 Render to MP4 ]  [ 📁 Folder ]  [ 🗑 ] │
 * │  └──────────┘                                               │
 * │▌ ┌──────────┐  recording_2024-06-14_09-10-55        [MP4✓] │
 * │  │ PREVIEW  │  Jun 14, 2024  09:10  •  198 MB               │
 * │  └──────────┘  [ ▶ Play MP4 ]  [ 📁 Folder ]  [ 🗑 Delete ]│
 * ├──────────────────────────────────────────────────────────────┤
 * │ [↻ Refresh]   < Prev   Page 1 / 2   Next >       [ ✕ Back ]│
 * └──────────────────────────────────────────────────────────────┘
 */
public class RecordingGalleryScreen extends Screen {

    // Layout
    private static final int THUMB_W   = 120;
    private static final int THUMB_H   = 68;
    private static final int ROW_H     = 84;
    private static final int ROW_PAD   = 6;
    private static final int ROWS_PAGE = 5;

    // Colours (ARGB)
    private static final int C_BG      = 0xFF1A1A2E;
    private static final int C_ROW_A   = 0xFF16213E;
    private static final int C_ROW_B   = 0xFF0F3460;
    private static final int C_HOV     = 0xFF533483;
    private static final int C_ACCENT  = 0xFFE94560;
    private static final int C_TEXT    = 0xFFEEEEEE;
    private static final int C_SUB     = 0xFF9E9E9E;
    private static final int C_THBG    = 0xFF0D0D1A;
    private static final int C_GREEN   = 0xFF81C784;
    private static final int C_BLUE    = 0xFF4FC3F7;
    private static final int C_RED     = 0xFFEF5350;
    private static final int C_ORANGE  = 0xFFFFB74D;

    private final Screen parent;
    private final ThumbnailCache thumbs = new ThumbnailCache();

    private List<RecordingEntry> all      = new ArrayList<>();
    private List<RecordingEntry> filtered = new ArrayList<>();
    private int    page         = 0;
    private String renderStatus = "";

    private TextFieldWidget searchBox;
    private ButtonWidget    prevBtn, nextBtn;

    public RecordingGalleryScreen(Screen parent) {
        super(Text.translatable("screenrecorder.gallery.title"));
        this.parent = parent;
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        loadEntries();

        int bh = 20, footY = height - bh - 6;

        prevBtn = ButtonWidget.builder(Text.literal("< Prev"),
                btn -> { page--; }).dimensions(width/2 - 110, footY, 60, bh).build();
        nextBtn = ButtonWidget.builder(Text.literal("Next >"),
                btn -> { page++; }).dimensions(width/2 + 50, footY, 60, bh).build();

        addDrawableChild(ButtonWidget.builder(Text.literal("↻ Refresh"),
                btn -> { loadEntries(); applyFilter(searchBox.getText()); })
                .dimensions(6, footY, 70, bh).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"),
                btn -> close())
                .dimensions(width - 76, footY, 70, bh).build());
        addDrawableChild(prevBtn);
        addDrawableChild(nextBtn);

        searchBox = new TextFieldWidget(textRenderer,
                width - 174, 8, 168, 18, Text.literal("Search…"));
        searchBox.setPlaceholder(Text.literal("Search recordings…"));
        searchBox.setChangedListener(q -> { page = 0; applyFilter(q); });
        addDrawableChild(searchBox);

        applyFilter("");
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
        ScreenRecorderMod.LOGGER.info("Gallery: loaded " + all.size() + " recording(s)");
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

    // ── Render ───────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, C_BG);
        renderHeader(ctx);
        renderRows(ctx, mx, my);
        renderFooter(ctx);
        prevBtn.active = page > 0;
        nextBtn.active = page < totalPages() - 1;
        super.render(ctx, mx, my, delta);
    }

    private void renderHeader(DrawContext ctx) {
        ctx.fill(0, 0, width, 30, 0xFF0D0D1A);
        ctx.fill(0, 29, width, 30, C_ACCENT);
        ctx.drawTextWithShadow(textRenderer,
                Text.translatable("screenrecorder.gallery.title"), 8, 10, C_ACCENT);
        String cnt = filtered.size() + " recording" + (filtered.size() != 1 ? "s" : "");
        ctx.drawTextWithShadow(textRenderer, cnt,
                8 + textRenderer.getWidth("My Recordings") + 14, 10, C_SUB);
    }

    private void renderRows(DrawContext ctx, int mx, int my) {
        List<RecordingEntry> entries = pageEntries();
        int rowY = 34 + ROW_PAD;

        for (int i = 0; i < entries.size(); i++) {
            RecordingEntry e = entries.get(i);
            int rowX = ROW_PAD, rowW = width - ROW_PAD * 2;
            boolean hov = mx >= rowX && mx <= rowX + rowW
                       && my >= rowY && my <= rowY + ROW_H;

            // Row background + accent bar
            ctx.fill(rowX, rowY, rowX + rowW, rowY + ROW_H,
                    hov ? C_HOV : (i % 2 == 0 ? C_ROW_A : C_ROW_B));
            ctx.fill(rowX, rowY, rowX + 3, rowY + ROW_H, C_ACCENT);

            // Thumbnail
            int tx = rowX + 8, ty = rowY + (ROW_H - THUMB_H) / 2;
            renderThumb(ctx, e, tx, ty);

            // Text column
            int textX = tx + THUMB_W + 10;
            int textY = rowY + 8;

            // Recording name
            ctx.drawTextWithShadow(textRenderer, e.displayName, textX, textY, C_TEXT);

            // Status badge (top-right of row)
            String badge  = e.renderedMp4 != null ? "MP4 ✓" : ".rawvid";
            int    badgeC = e.renderedMp4 != null ? C_GREEN : C_SUB;
            ctx.drawTextWithShadow(textRenderer, "[" + badge + "]",
                    rowX + rowW - textRenderer.getWidth("[" + badge + "]") - 8,
                    textY, badgeC);

            // Date + size
            ctx.drawTextWithShadow(textRenderer,
                    e.dateString + "  •  " + e.sizeString, textX, textY + 13, C_SUB);

            // Action row or render progress
            if (e.rendering) {
                String prog = renderStatus.isEmpty() ? "⏳ Rendering…" : "⏳ " + renderStatus;
                ctx.drawTextWithShadow(textRenderer, prog,
                        textX, rowY + ROW_H - 22, C_ORANGE);
            } else {
                renderActionLabels(ctx, e, textX, rowY + ROW_H - 22, mx, my);
            }

            rowY += ROW_H + ROW_PAD;
        }

        if (entries.isEmpty()) {
            String msg = filtered.isEmpty() && searchBox.getText().isEmpty()
                    ? "No recordings yet — start one from the main menu!"
                    : "No recordings match your search.";
            ctx.drawCenteredTextWithShadow(textRenderer, msg,
                    width / 2, (height - 30) / 2 + 30, C_SUB);
        }
    }

    private void renderThumb(DrawContext ctx, RecordingEntry e, int x, int y) {
        ctx.fill(x, y, x + THUMB_W, y + THUMB_H, C_THBG);

        Identifier id = thumbs.getThumbnail(e);
        if (id != null) {
            ctx.drawTexture(id, x, y, 0, 0, THUMB_W, THUMB_H);
        } else {
            // Placeholder while thumbnail loads
            ctx.drawCenteredTextWithShadow(textRenderer, "🎬",
                    x + THUMB_W / 2, y + THUMB_H / 2 - 10, C_SUB);
            ctx.drawCenteredTextWithShadow(textRenderer, "Loading…",
                    x + THUMB_W / 2, y + THUMB_H / 2 + 2, C_SUB);
        }

        // Thin accent border
        ctx.fill(x,            y,            x + THUMB_W,     y + 1,          C_ACCENT);
        ctx.fill(x,            y + THUMB_H - 1, x + THUMB_W, y + THUMB_H,    C_ACCENT);
        ctx.fill(x,            y,            x + 1,           y + THUMB_H,    C_ACCENT);
        ctx.fill(x + THUMB_W - 1, y,         x + THUMB_W,     y + THUMB_H,   C_ACCENT);
    }

    private void renderActionLabels(DrawContext ctx, RecordingEntry e,
                                    int x, int y, int mx, int my) {
        String[] labels = getActionLabels(e);
        int[]    colors = getActionColors(e);
        int cx = x;
        for (int j = 0; j < labels.length; j++) {
            int lw = textRenderer.getWidth(labels[j]);
            boolean hov = mx >= cx && mx <= cx + lw
                       && my >= y  && my <= y + textRenderer.fontHeight;
            ctx.drawTextWithShadow(textRenderer, labels[j], cx, y,
                    hov ? 0xFFFFFFFF : colors[j]);
            cx += lw + 10;
        }
    }

    private String[] getActionLabels(RecordingEntry e) {
        if (e.renderedMp4 != null)
            return new String[]{"[ ▶ Play MP4 ]", "[ 📁 Folder ]", "[ 🗑 Delete ]"};
        if (PojavFFmpegRenderer.canRender())
            return new String[]{"[ 🎬 Render to MP4 ]", "[ 📁 Folder ]", "[ 🗑 Delete ]"};
        return new String[]{"[ 📁 Folder ]", "[ 🗑 Delete ]",
                            "[ ⚠ FFmpeg not found ]"};
    }

    private int[] getActionColors(RecordingEntry e) {
        if (e.renderedMp4 != null)
            return new int[]{C_GREEN, C_BLUE, C_RED};
        if (PojavFFmpegRenderer.canRender())
            return new int[]{C_ORANGE, C_BLUE, C_RED};
        return new int[]{C_BLUE, C_RED, C_SUB};
    }

    private void renderFooter(DrawContext ctx) {
        int fy = height - 28;
        ctx.fill(0, fy, width, height, 0xFF0D0D1A);
        ctx.fill(0, fy, width, fy + 1, C_ACCENT);
        ctx.drawCenteredTextWithShadow(textRenderer,
                "Page " + (page + 1) + " / " + totalPages(),
                width / 2, height - 14, C_SUB);
        if (!renderStatus.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, renderStatus, 8, height - 14, C_ORANGE);
        }
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (super.mouseClicked(mx, my, btn)) return true;

        List<RecordingEntry> entries = pageEntries();
        int rowY = 34 + ROW_PAD;

        for (RecordingEntry e : entries) {
            int textX = ROW_PAD + 8 + THUMB_W + 10;
            int actY  = rowY + ROW_H - 22;

            if (my >= rowY && my <= rowY + ROW_H && !e.rendering) {
                String[] labels = getActionLabels(e);
                int cx = textX;
                for (String label : labels) {
                    int lw = textRenderer.getWidth(label);
                    if (mx >= cx && mx <= cx + lw
                            && my >= actY && my <= actY + textRenderer.fontHeight) {
                        handleAction(e, label);
                        return true;
                    }
                    cx += lw + 10;
                }
            }
            rowY += ROW_H + ROW_PAD;
        }
        return false;
    }

    private void handleAction(RecordingEntry e, String label) {
        if      (label.contains("Render"))  startRender(e);
        else if (label.contains("Play"))    playFile(e.renderedMp4);
        else if (label.contains("Folder"))  openFolder(e);
        else if (label.contains("Delete"))  confirmDelete(e);
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private void startRender(RecordingEntry e) {
        renderStatus = "Starting…";
        PojavFFmpegRenderer.renderAsync(
            e,
            progress -> {
                renderStatus = progress.length() > 55
                        ? progress.substring(0, 52) + "…" : progress;
            },
            mp4 -> {
                renderStatus = "";
                if (mp4 != null) {
                    MinecraftClient.getInstance().execute(() -> {
                        loadEntries();
                        applyFilter(searchBox.getText());
                    });
                }
            }
        );
    }

    private void playFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(file);
            } else {
                // Android: fire ACTION_VIEW intent
                Runtime.getRuntime().exec(new String[]{
                    "am", "start", "-a", "android.intent.action.VIEW",
                    "-d", "file://" + file.getAbsolutePath(), "-t", "video/mp4"
                });
            }
        } catch (IOException ex) {
            ScreenRecorderMod.LOGGER.error("Could not open file: " + file, ex);
        }
    }

    private void openFolder(RecordingEntry e) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(e.file.getParentFile());
            } else {
                Runtime.getRuntime().exec(new String[]{
                    "am", "start", "-a", "android.intent.action.VIEW",
                    "-d", "file://" + e.file.getParent(), "-t", "resource/folder"
                });
            }
        } catch (IOException ex) {
            ScreenRecorderMod.LOGGER.error("Could not open folder", ex);
        }
    }

    private void confirmDelete(RecordingEntry e) {
        client.setScreen(new ConfirmScreen(
            confirmed -> {
                if (confirmed) {
                    e.file.delete();
                    if (e.renderedMp4 != null) e.renderedMp4.delete();
                    loadEntries();
                    applyFilter(searchBox.getText());
                }
                client.setScreen(this);
            },
            Text.translatable("screenrecorder.gallery.delete_confirm_title"),
            Text.translatable("screenrecorder.gallery.delete_confirm_body", e.displayName)
        ));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if      (v < 0 && page < totalPages() - 1) page++;
        else if (v > 0 && page > 0)                page--;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox.isFocused()) return super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == 263 && page > 0)                { page--; return true; }
        if (keyCode == 262 && page < totalPages() - 1) { page++; return true; }
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
