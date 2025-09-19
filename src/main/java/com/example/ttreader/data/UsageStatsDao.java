package com.example.ttreader.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.ttreader.model.UsageStat;

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
        String lemmaKey = lemma == null ? "" : lemma;
        String posKey = pos == null ? "" : pos;
        String featureKey = featureCode == null ? "" : featureCode;

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
    }

    public List<UsageStat> getLemmaStats() {
        List<UsageStat> stats = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT lemma, pos, event_type, feature_code, count, last_seen_ms FROM usage_stats WHERE feature_code='' ORDER BY lemma, pos, event_type",
                null)) {
            while (c.moveToNext()) {
                stats.add(new UsageStat(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getInt(4),
                        c.getLong(5)));
            }
        }
        return stats;
    }

    public List<UsageStat> getFeatureStats() {
        List<UsageStat> stats = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT lemma, pos, event_type, feature_code, count, last_seen_ms FROM usage_stats WHERE feature_code<>'' ORDER BY feature_code",
                null)) {
            while (c.moveToNext()) {
                stats.add(new UsageStat(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getInt(4),
                        c.getLong(5)));
            }
        }
        return stats;
    }
}
