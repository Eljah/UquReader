package com.example.ttreader.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public class MemoryDao {
    private final SQLiteDatabase db;

    public MemoryDao(SQLiteDatabase db) { this.db = db; }

    public double getCurrentStrength(String lemma, String featureKey, long nowMs, double halfLifeDays) {
        double s = 0; long last = nowMs;
        try (Cursor c = db.rawQuery("SELECT strength, last_seen_ms FROM memory WHERE lemma=? AND IFNULL(feature_key,'~')=IFNULL(?, '~')",
                new String[]{lemma, featureKey})) {
            if (c.moveToFirst()) {
                s = c.getDouble(0); last = c.getLong(1);
            }
        }
        double dtDays = (nowMs - last) / (1000.0*60*60*24);
        double decay = Math.pow(0.5, dtDays / Math.max(halfLifeDays, 0.1));
        return s * decay;
    }

    public void updateOnLookup(String lemma, String featureKey, long nowMs, double increment) {
        double s = 0;
        try (Cursor c = db.rawQuery("SELECT strength FROM memory WHERE lemma=? AND IFNULL(feature_key,'~')=IFNULL(?, '~')",
                new String[]{lemma, featureKey})) {
            if (c.moveToFirst()) s = c.getDouble(0);
        }
        s = Math.min(10.0, s + increment);
        ContentValues cv = new ContentValues();
        cv.put("lemma", lemma);
        cv.put("feature_key", featureKey);
        cv.put("strength", s);
        cv.put("last_seen_ms", nowMs);
        db.insertWithOnConflict("memory", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
