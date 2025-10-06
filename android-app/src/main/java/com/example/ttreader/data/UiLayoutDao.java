package com.example.ttreader.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public class UiLayoutDao {
    private static final String TABLE_NAME = "ui_layout";
    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_INT_VALUE = "int_value";
    private static final String COLUMN_UPDATED_MS = "updated_ms";

    private static final String KEY_PAGE_CONTROLS_HEIGHT = "page_controls_height";
    private static final String KEY_PAGE_CONTAINER_HEIGHT = "reader_page_height";

    private final SQLiteDatabase db;
    private final DbWriteQueue writeQueue;

    public UiLayoutDao(SQLiteDatabase db) {
        this(db, DbWriteQueue.getInstance());
    }

    public UiLayoutDao(SQLiteDatabase db, DbWriteQueue queue) {
        this.db = db;
        this.writeQueue = queue;
    }

    public Integer getPageControlsHeight() {
        return getIntValue(KEY_PAGE_CONTROLS_HEIGHT);
    }

    public void savePageControlsHeight(int height) {
        final int safeHeight = Math.max(0, height);
        final long timestamp = System.currentTimeMillis();
        final String key = KEY_PAGE_CONTROLS_HEIGHT;
        writeQueue.enqueue(() -> {
            ContentValues values = new ContentValues();
            values.put(COLUMN_NAME, key);
            values.put(COLUMN_INT_VALUE, safeHeight);
            values.put(COLUMN_UPDATED_MS, timestamp);
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    public Integer getReaderPageHeight(String workId) {
        return getIntValue(buildKey(KEY_PAGE_CONTAINER_HEIGHT, workId));
    }

    public void saveReaderPageHeight(String workId, int height) {
        final int safeHeight = Math.max(0, height);
        final long timestamp = System.currentTimeMillis();
        final String key = buildKey(KEY_PAGE_CONTAINER_HEIGHT, workId);
        writeQueue.enqueue(() -> {
            ContentValues values = new ContentValues();
            values.put(COLUMN_NAME, key);
            values.put(COLUMN_INT_VALUE, safeHeight);
            values.put(COLUMN_UPDATED_MS, timestamp);
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    private Integer getIntValue(String key) {
        String safeKey = sanitize(key);
        try (Cursor cursor = db.query(TABLE_NAME, new String[]{COLUMN_INT_VALUE},
                COLUMN_NAME + "=?", new String[]{safeKey}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        }
        return null;
    }

    private String buildKey(String base, String suffix) {
        if (suffix == null || suffix.trim().isEmpty()) {
            return base;
        }
        return base + ":" + suffix;
    }

    private String sanitize(String value) {
        return value == null ? "" : value;
    }
}
