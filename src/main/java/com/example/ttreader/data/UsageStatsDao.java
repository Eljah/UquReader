package com.example.ttreader.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.ttreader.model.UsageEvent;

import java.util.ArrayList;
import java.util.List;

public class UsageStatsDao {
    public static final String EVENT_EXPOSURE = "exposure";
    public static final String EVENT_LOOKUP = "lookup";
    public static final String EVENT_FEATURE = "feature";

    private final SQLiteDatabase db;

    public UsageStatsDao(SQLiteDatabase db) {
        this.db = db;
    }

    public void recordEvent(String lemma, String pos, String featureCode, String eventType, long timestamp) {
        recordEvent(lemma, pos, featureCode, eventType, timestamp, null);
    }

    public void recordEvent(String lemma, String pos, String featureCode, String eventType, long timestamp, String bookId) {
        String lemmaKey = lemma == null ? "" : lemma;
        String posKey = pos == null ? "" : pos;
        String featureKey = featureCode == null ? "" : featureCode;
        String bookKey = bookId == null ? "" : bookId;

        int count = 0;
        try (Cursor c = db.rawQuery(
                "SELECT count FROM usage_stats WHERE lemma=? AND pos=? AND feature_code=? AND event_type=?",
                new String[]{lemmaKey, posKey, featureKey, eventType})) {
            if (c.moveToFirst()) count = c.getInt(0);
        }
        count += 1;

        ContentValues cv = new ContentValues();
        cv.put("lemma", lemmaKey);
        cv.put("pos", posKey);
        cv.put("feature_code", featureKey);
        cv.put("event_type", eventType);
        cv.put("count", count);
        cv.put("last_seen_ms", timestamp);
        db.insertWithOnConflict("usage_stats", null, cv, SQLiteDatabase.CONFLICT_REPLACE);

        ContentValues eventValues = new ContentValues();
        eventValues.put("lemma", lemmaKey);
        eventValues.put("pos", posKey);
        eventValues.put("feature_code", featureKey);
        eventValues.put("event_type", eventType);
        eventValues.put("book_id", bookKey);
        eventValues.put("timestamp_ms", timestamp);
        db.insert("usage_events", null, eventValues);
    }

    public List<UsageEvent> getLemmaEvents(String bookId) {
        return getEvents(false, bookId);
    }

    public List<UsageEvent> getFeatureEvents(String bookId) {
        return getEvents(true, bookId);
    }

    private List<UsageEvent> getEvents(boolean features, String bookId) {
        List<UsageEvent> events = new ArrayList<>();
        List<String> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT lemma, pos, feature_code, event_type, timestamp_ms, book_id FROM usage_events WHERE ");
        if (features) {
            sql.append("feature_code<>''");
        } else {
            sql.append("feature_code=''");
        }
        if (bookId != null) {
            sql.append(" AND book_id=?");
            args.add(bookId);
        }
        if (features) {
            sql.append(" ORDER BY feature_code COLLATE NOCASE, timestamp_ms");
        } else {
            sql.append(" ORDER BY lemma COLLATE NOCASE, pos COLLATE NOCASE, timestamp_ms");
        }
        String[] selectionArgs = args.isEmpty() ? null : args.toArray(new String[0]);
        try (Cursor c = db.rawQuery(sql.toString(), selectionArgs)) {
            while (c.moveToNext()) {
                events.add(new UsageEvent(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getLong(4),
                        c.getString(5)));
            }
        }
        return events;
    }
}
