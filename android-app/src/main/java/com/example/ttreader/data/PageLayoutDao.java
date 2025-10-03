package com.example.ttreader.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists pre-computed reader page layouts so that pagination only needs to be
 * calculated once per work and viewport size.
 */
public class PageLayoutDao {
    private static final String TABLE_NAME = "page_layout";

    private final SQLiteDatabase database;

    public PageLayoutDao(SQLiteDatabase database) {
        this.database = database;
    }

    public static final class PageLayoutEntry {
        public final int pageIndex;
        public final int totalPages;
        public final int startCharInclusive;
        public final int endCharInclusive;
        public final int startWordIndex;
        public final int endWordIndex;

        public PageLayoutEntry(int pageIndex, int totalPages, int startCharInclusive,
                               int endCharInclusive, int startWordIndex, int endWordIndex) {
            this.pageIndex = pageIndex;
            this.totalPages = totalPages;
            this.startCharInclusive = startCharInclusive;
            this.endCharInclusive = endCharInclusive;
            this.startWordIndex = startWordIndex;
            this.endWordIndex = endWordIndex;
        }
    }

    public List<PageLayoutEntry> loadLayout(String languagePair, String workId,
                                            int viewportWidth, int viewportHeight,
                                            int textSizePx) {
        if (database == null || languagePair == null || workId == null) {
            return Collections.emptyList();
        }
        String selection = "language_pair = ? AND work_id = ? AND viewport_width = ? " +
                "AND viewport_height = ? AND text_size_px = ?";
        String[] selectionArgs = new String[]{
                languagePair,
                workId,
                String.valueOf(Math.max(0, viewportWidth)),
                String.valueOf(Math.max(0, viewportHeight)),
                String.valueOf(Math.max(0, textSizePx))
        };
        Cursor cursor = database.query(TABLE_NAME,
                new String[]{"page_index", "total_pages", "start_char", "end_char",
                        "start_word", "end_word"},
                selection,
                selectionArgs,
                null,
                null,
                "page_index ASC");
        if (cursor == null) {
            return Collections.emptyList();
        }
        try {
            if (!cursor.moveToFirst()) {
                return Collections.emptyList();
            }
            List<PageLayoutEntry> entries = new ArrayList<>(cursor.getCount());
            do {
                int pageIndex = cursor.getInt(0);
                int totalPages = cursor.getInt(1);
                int startChar = cursor.getInt(2);
                int endChar = cursor.getInt(3);
                int startWord = cursor.getInt(4);
                int endWord = cursor.getInt(5);
                entries.add(new PageLayoutEntry(pageIndex, totalPages, startChar,
                        endChar, startWord, endWord));
            } while (cursor.moveToNext());
            return entries;
        } finally {
            cursor.close();
        }
    }

    public void replaceLayout(String languagePair, String workId, int viewportWidth,
                              int viewportHeight, int textSizePx,
                              List<PageLayoutEntry> entries, long timestampMs) {
        if (database == null || languagePair == null || workId == null) {
            return;
        }
        database.beginTransaction();
        try {
            String where = "language_pair = ? AND work_id = ? AND viewport_width = ? " +
                    "AND viewport_height = ? AND text_size_px = ?";
            String[] args = new String[]{
                    languagePair,
                    workId,
                    String.valueOf(Math.max(0, viewportWidth)),
                    String.valueOf(Math.max(0, viewportHeight)),
                    String.valueOf(Math.max(0, textSizePx))
            };
            database.delete(TABLE_NAME, where, args);
            if (entries != null && !entries.isEmpty()) {
                for (PageLayoutEntry entry : entries) {
                    if (entry == null) continue;
                    ContentValues values = new ContentValues();
                    values.put("language_pair", languagePair);
                    values.put("work_id", workId);
                    values.put("viewport_width", Math.max(0, viewportWidth));
                    values.put("viewport_height", Math.max(0, viewportHeight));
                    values.put("text_size_px", Math.max(0, textSizePx));
                    values.put("page_index", entry.pageIndex);
                    values.put("total_pages", Math.max(0, entry.totalPages));
                    values.put("start_char", Math.max(0, entry.startCharInclusive));
                    values.put("end_char", Math.max(-1, entry.endCharInclusive));
                    values.put("start_word", entry.startWordIndex);
                    values.put("end_word", entry.endWordIndex);
                    values.put("updated_ms", timestampMs);
                    database.insert(TABLE_NAME, null, values);
                }
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }
}
