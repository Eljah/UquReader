package com.example.ttreader.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PaginationDao {
    private static final String TABLE_NAME = "visual_pagination";

    private final SQLiteDatabase db;
    private final DbWriteQueue writeQueue;

    public PaginationDao(SQLiteDatabase db) {
        this(db, DbWriteQueue.getInstance());
    }

    public PaginationDao(SQLiteDatabase db, DbWriteQueue queue) {
        this.db = db;
        this.writeQueue = queue;
    }

    public Snapshot getSnapshot(String languagePair, String workId) {
        String lang = sanitize(languagePair);
        String work = sanitize(workId);
        try (Cursor c = db.query(TABLE_NAME,
                new String[]{"content_width", "content_height", "text_size",
                        "line_spacing_extra", "line_spacing_multiplier", "letter_spacing",
                        "document_signature", "page_breaks", "updated_ms"},
                "language_pair=? AND work_id=?",
                new String[]{lang, work}, null, null, null)) {
            if (c.moveToFirst()) {
                int contentWidth = c.getInt(0);
                int contentHeight = c.getInt(1);
                float textSize = c.getFloat(2);
                float lineSpacingExtra = c.getFloat(3);
                float lineSpacingMultiplier = c.getFloat(4);
                float letterSpacing = c.getFloat(5);
                int documentSignature = c.getInt(6);
                String encoded = c.getString(7);
                long updatedMs = c.getLong(8);
                List<PageBreak> pageBreaks = decodePageBreaks(encoded);
                if (!pageBreaks.isEmpty()) {
                    return new Snapshot(lang, work, contentWidth, contentHeight, textSize,
                            lineSpacingExtra, lineSpacingMultiplier, letterSpacing,
                            documentSignature, pageBreaks, updatedMs);
                }
            }
        }
        return null;
    }

    public void saveSnapshot(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        final Snapshot safe = snapshot;
        writeQueue.enqueue(() -> {
            ContentValues values = new ContentValues();
            values.put("language_pair", safe.languagePair);
            values.put("work_id", safe.workId);
            values.put("content_width", safe.contentWidth);
            values.put("content_height", safe.contentHeight);
            values.put("text_size", safe.textSize);
            values.put("line_spacing_extra", safe.lineSpacingExtra);
            values.put("line_spacing_multiplier", safe.lineSpacingMultiplier);
            values.put("letter_spacing", safe.letterSpacing);
            values.put("document_signature", safe.documentSignature);
            values.put("page_breaks", encodePageBreaks(safe.pageBreaks));
            values.put("updated_ms", safe.updatedMs);
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    public void deleteSnapshot(String languagePair, String workId) {
        final String lang = sanitize(languagePair);
        final String work = sanitize(workId);
        writeQueue.enqueue(() -> db.delete(TABLE_NAME, "language_pair=? AND work_id=?",
                new String[]{lang, work}));
    }

    private static List<PageBreak> decodePageBreaks(String encoded) {
        if (TextUtils.isEmpty(encoded)) {
            return Collections.emptyList();
        }
        String[] parts = encoded.split(",");
        List<PageBreak> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (TextUtils.isEmpty(part)) continue;
            String[] bounds = part.split(":");
            if (bounds.length != 2) continue;
            try {
                int start = Integer.parseInt(bounds[0].trim());
                int end = Integer.parseInt(bounds[1].trim());
                if (end > start) {
                    result.add(new PageBreak(start, end));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static String encodePageBreaks(List<PageBreak> breaks) {
        if (breaks == null || breaks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (PageBreak page : breaks) {
            if (page == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(page.start).append(':').append(page.end);
        }
        return sb.toString();
    }

    private String sanitize(String value) {
        return value == null ? "" : value;
    }

    public static final class Snapshot {
        public final String languagePair;
        public final String workId;
        public final int contentWidth;
        public final int contentHeight;
        public final float textSize;
        public final float lineSpacingExtra;
        public final float lineSpacingMultiplier;
        public final float letterSpacing;
        public final int documentSignature;
        public final List<PageBreak> pageBreaks;
        public final long updatedMs;

        public Snapshot(String languagePair, String workId, int contentWidth, int contentHeight,
                        float textSize, float lineSpacingExtra, float lineSpacingMultiplier,
                        float letterSpacing, int documentSignature, List<PageBreak> pageBreaks,
                        long updatedMs) {
            this.languagePair = sanitize(languagePair);
            this.workId = sanitize(workId);
            this.contentWidth = Math.max(0, contentWidth);
            this.contentHeight = Math.max(0, contentHeight);
            this.textSize = textSize;
            this.lineSpacingExtra = lineSpacingExtra;
            this.lineSpacingMultiplier = lineSpacingMultiplier;
            this.letterSpacing = letterSpacing;
            this.documentSignature = documentSignature;
            this.pageBreaks = pageBreaks == null ? Collections.emptyList() : new ArrayList<>(pageBreaks);
            this.updatedMs = Math.max(0L, updatedMs);
        }

        private String sanitize(String value) {
            return value == null ? "" : value;
        }
    }

    public static final class PageBreak {
        public final int start;
        public final int end;

        public PageBreak(int start, int end) {
            this.start = Math.max(0, start);
            this.end = Math.max(this.start, end);
        }
    }
}
