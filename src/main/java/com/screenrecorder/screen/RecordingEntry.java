package com.screenrecorder.screen;

import java.io.File;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Metadata for one .rawvid recording in the gallery.
 * Also tracks whether a rendered MP4 exists alongside it.
 */
public class RecordingEntry {

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm");
    private static final DecimalFormat SIZE_FMT = new DecimalFormat("#.##");

    public final File   file;
    public final String displayName;  // filename without extension
    public final String dateString;
    public final String sizeString;
    public final long   lastModified;

    public volatile boolean rendering  = false;
    public volatile File    renderedMp4 = null;

    public RecordingEntry(File file) {
        this.file         = file;
        this.lastModified = file.lastModified();

        String raw = file.getName();
        this.displayName = raw.endsWith(".rawvid") ? raw.substring(0, raw.length() - 7) : raw;

        LocalDateTime dt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(lastModified), ZoneId.systemDefault());
        this.dateString = dt.format(DISPLAY_FMT);
        this.sizeString = formatSize(file.length());

        // Check if a rendered MP4 already exists
        File mp4 = new File(file.getParent(), displayName + ".mp4");
        if (mp4.exists() && mp4.length() > 0) this.renderedMp4 = mp4;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024)         return bytes + " B";
        if (bytes < 1024*1024)    return SIZE_FMT.format(bytes / 1024.0) + " KB";
        return SIZE_FMT.format(bytes / (1024.0 * 1024.0)) + " MB";
    }
}
