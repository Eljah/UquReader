package com.example.ttreader.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.ttreader.model.ReadingState;

public class ReadingStateDao {
    private static final String TABLE_NAME = "reading_state";

    private final SQLiteDatabase db;
    private final DbWriteQueue writeQueue;

    public ReadingStateDao(SQLiteDatabase db) {
        this(db, DbWriteQueue.getInstance());
    }

    public ReadingStateDao(SQLiteDatabase db, DbWriteQueue queue) {
        this.db = db;
        this.writeQueue = queue;
    }

    public ReadingState getState(String languagePair, String workId) {
        String lang = sanitize(languagePair);
        String work = sanitize(workId);
        try (Cursor c = db.query(TABLE_NAME,
                new String[]{"language_pair", "work_id", "last_mode", "visual_page",
                        "visual_char_index", "visual_card_height", "voice_sentence_index",
                        "voice_char_index", "updated_ms"},
                "language_pair=? AND work_id=?",
                new String[]{lang, work}, null, null, null)) {
            if (c.moveToFirst()) {
                return new ReadingState(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getInt(3),
                        c.getInt(4),
                        c.getInt(5),
                        c.getInt(6),
                        c.getInt(7),
                        c.getLong(8));
            }
        }
        return null;
    }

    public void updateVisualState(String languagePair, String workId, int pageIndex,
                                  int charIndex, long timestamp, boolean setAsLastMode) {
        final String lang = sanitize(languagePair);
        final String work = sanitize(workId);
        final int safePage = Math.max(0, pageIndex);
        final int safeChar = Math.max(0, charIndex);
        final long safeTimestamp = Math.max(0L, timestamp);
        final boolean updateLast = setAsLastMode;
        writeQueue.enqueue(() -> {
            ContentValues update = new ContentValues();
            update.put("visual_page", safePage);
            update.put("visual_char_index", safeChar);
            update.put("updated_ms", safeTimestamp);
            if (updateLast) {
                update.put("last_mode", ReadingState.MODE_VISUAL);
            }
            int rows = db.update(TABLE_NAME, update,
                    "language_pair=? AND work_id=?", new String[]{lang, work});
            if (rows == 0) {
                ContentValues insert = new ContentValues();
                insert.put("language_pair", lang);
                insert.put("work_id", work);
                insert.put("visual_page", safePage);
                insert.put("visual_char_index", safeChar);
                insert.put("visual_card_height", 0);
                insert.put("voice_sentence_index", -1);
                insert.put("voice_char_index", -1);
                insert.put("updated_ms", safeTimestamp);
                insert.put("last_mode", updateLast ? ReadingState.MODE_VISUAL : "");
                db.insertWithOnConflict(TABLE_NAME, null, insert, SQLiteDatabase.CONFLICT_REPLACE);
            }
        });
    }

    public void updateVoiceState(String languagePair, String workId, int sentenceIndex,
                                 int charIndex, long timestamp, boolean setAsLastMode) {
        final String lang = sanitize(languagePair);
        final String work = sanitize(workId);
        final int safeSentence = Math.max(-1, sentenceIndex);
        final int safeChar = Math.max(-1, charIndex);
        final long safeTimestamp = Math.max(0L, timestamp);
        final boolean updateLast = setAsLastMode;
        writeQueue.enqueue(() -> {
            ContentValues update = new ContentValues();
            update.put("voice_sentence_index", safeSentence);
            update.put("voice_char_index", safeChar);
            update.put("updated_ms", safeTimestamp);
            if (updateLast) {
                update.put("last_mode", ReadingState.MODE_VOICE);
            }
            int rows = db.update(TABLE_NAME, update,
                    "language_pair=? AND work_id=?", new String[]{lang, work});
            if (rows == 0) {
                ContentValues insert = new ContentValues();
                insert.put("language_pair", lang);
                insert.put("work_id", work);
                insert.put("visual_page", 0);
                insert.put("visual_char_index", 0);
                insert.put("visual_card_height", 0);
                insert.put("voice_sentence_index", safeSentence);
                insert.put("voice_char_index", safeChar);
                insert.put("updated_ms", safeTimestamp);
                insert.put("last_mode", updateLast ? ReadingState.MODE_VOICE : "");
                db.insertWithOnConflict(TABLE_NAME, null, insert, SQLiteDatabase.CONFLICT_REPLACE);
            }
        });
    }

    public void updateVisualCardHeight(String languagePair, String workId, int cardHeight,
                                       long timestamp) {
        final String lang = sanitize(languagePair);
        final String work = sanitize(workId);
        final int safeHeight = Math.max(0, cardHeight);
        final long safeTimestamp = Math.max(0L, timestamp);
        writeQueue.enqueue(() -> {
            ContentValues update = new ContentValues();
            update.put("visual_card_height", safeHeight);
            update.put("updated_ms", safeTimestamp);
            int rows = db.update(TABLE_NAME, update,
                    "language_pair=? AND work_id=?", new String[]{lang, work});
            if (rows == 0) {
                ContentValues insert = new ContentValues();
                insert.put("language_pair", lang);
                insert.put("work_id", work);
                insert.put("visual_page", 0);
                insert.put("visual_char_index", 0);
                insert.put("visual_card_height", safeHeight);
                insert.put("voice_sentence_index", -1);
                insert.put("voice_char_index", -1);
                insert.put("updated_ms", safeTimestamp);
                insert.put("last_mode", "");
                db.insertWithOnConflict(TABLE_NAME, null, insert, SQLiteDatabase.CONFLICT_REPLACE);
            }
        });
    }

    private String sanitize(String value) {
        return value == null ? "" : value;
    }
}
