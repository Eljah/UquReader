package com.example.ttreader.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.ttreader.model.UsageEvent;
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

    public void recordEvent(String languagePair, String workId, String lemma, String pos,
                            String featureCode, String eventType, long timestamp, int charIndex) {
        String languageKey = sanitize(languagePair);
        String workKey = sanitize(workId);
        String lemmaKey = sanitize(lemma);
        String posKey = sanitize(pos);
        String featureKey = sanitize(featureCode);
        int safeCharIndex = charIndex < 0 ? -1 : charIndex;

        int count = 0;
        int lastPosition = -1;
        try (Cursor c = db.rawQuery(
                "SELECT count, last_position FROM usage_stats WHERE language_pair=? AND work_id=? AND lemma=? AND pos=? AND feature_code=? AND event_type=?",
                new String[]{languageKey, workKey, lemmaKey, posKey, featureKey, eventType})) {
            if (c.moveToFirst()) {
                count = c.getInt(0);
                lastPosition = c.getInt(1);
            }
        }
        count += 1;
        if (safeCharIndex >= 0) {
            lastPosition = safeCharIndex;
        }

        ContentValues cv = new ContentValues();
        cv.put("language_pair", languageKey);
        cv.put("work_id", workKey);
        cv.put("lemma", lemmaKey);
        cv.put("pos", posKey);
        cv.put("feature_code", featureKey);
        cv.put("event_type", eventType);
        cv.put("count", count);
        cv.put("last_seen_ms", timestamp);
        cv.put("last_position", lastPosition);
        db.insertWithOnConflict("usage_stats", null, cv, SQLiteDatabase.CONFLICT_REPLACE);

        ContentValues event = new ContentValues();
        event.put("language_pair", languageKey);
        event.put("work_id", workKey);
        event.put("lemma", lemmaKey);
        event.put("pos", posKey);
        event.put("feature_code", featureKey);
        event.put("event_type", eventType);
        event.put("timestamp_ms", timestamp);
        event.put("char_index", safeCharIndex);
        db.insert("usage_event_log", null, event);
    }

    public List<UsageStat> getLemmaStats(String languagePair, String workId) {
        List<UsageStat> stats = new ArrayList<>();
        String languageKey = sanitize(languagePair);
        boolean aggregateAcrossWorks = isEmpty(workId);
        if (aggregateAcrossWorks) {
            String sql = "SELECT language_pair, lemma, pos, event_type, feature_code, SUM(count) AS total_count, " +
                    "MAX(last_seen_ms) AS last_seen_ms, MAX(last_position) AS last_position " +
                    "FROM usage_stats WHERE language_pair=? AND feature_code='' " +
                    "GROUP BY language_pair, lemma, pos, event_type, feature_code " +
                    "ORDER BY lemma COLLATE NOCASE, pos COLLATE NOCASE, event_type";
            try (Cursor c = db.rawQuery(sql, new String[]{languageKey})) {
                while (c.moveToNext()) {
                    stats.add(new UsageStat(
                            c.getString(0),
                            "",
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            c.getString(4),
                            c.getInt(5),
                            c.getLong(6),
                            c.getInt(7)));
                }
            }
        } else {
            String workKey = sanitize(workId);
            String sql = "SELECT language_pair, work_id, lemma, pos, event_type, feature_code, count, last_seen_ms, last_position " +
                    "FROM usage_stats WHERE language_pair=? AND work_id=? AND feature_code='' " +
                    "ORDER BY lemma COLLATE NOCASE, pos COLLATE NOCASE, event_type";
            try (Cursor c = db.rawQuery(sql, new String[]{languageKey, workKey})) {
                while (c.moveToNext()) {
                    stats.add(new UsageStat(
                            c.getString(0),
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            c.getString(4),
                            c.getString(5),
                            c.getInt(6),
                            c.getLong(7),
                            c.getInt(8)));
                }
            }
        }
        return stats;
    }

    public List<UsageStat> getFeatureStats(String languagePair, String workId) {
        List<UsageStat> stats = new ArrayList<>();
        String languageKey = sanitize(languagePair);
        boolean aggregateAcrossWorks = isEmpty(workId);
        if (aggregateAcrossWorks) {
            String sql = "SELECT language_pair, lemma, pos, event_type, feature_code, SUM(count) AS total_count, " +
                    "MAX(last_seen_ms) AS last_seen_ms, MAX(last_position) AS last_position " +
                    "FROM usage_stats WHERE language_pair=? AND feature_code<>'' " +
                    "GROUP BY language_pair, lemma, pos, event_type, feature_code " +
                    "ORDER BY feature_code COLLATE NOCASE, lemma COLLATE NOCASE";
            try (Cursor c = db.rawQuery(sql, new String[]{languageKey})) {
                while (c.moveToNext()) {
                    stats.add(new UsageStat(
                            c.getString(0),
                            "",
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            c.getString(4),
                            c.getInt(5),
                            c.getLong(6),
                            c.getInt(7)));
                }
            }
        } else {
            String workKey = sanitize(workId);
            String sql = "SELECT language_pair, work_id, lemma, pos, event_type, feature_code, count, last_seen_ms, last_position " +
                    "FROM usage_stats WHERE language_pair=? AND work_id=? AND feature_code<>'' " +
                    "ORDER BY feature_code COLLATE NOCASE, lemma COLLATE NOCASE";
            try (Cursor c = db.rawQuery(sql, new String[]{languageKey, workKey})) {
                while (c.moveToNext()) {
                    stats.add(new UsageStat(
                            c.getString(0),
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            c.getString(4),
                            c.getString(5),
                            c.getInt(6),
                            c.getLong(7),
                            c.getInt(8)));
                }
            }
        }
        return stats;
    }

    public List<UsageEvent> getEvents(String languagePair, String workId, String lemma, String pos, String eventType) {
        List<UsageEvent> events = new ArrayList<>();
        String languageKey = sanitize(languagePair);
        String lemmaKey = sanitize(lemma);
        String posKey = sanitize(pos);
        StringBuilder sql = new StringBuilder("SELECT language_pair, work_id, lemma, pos, event_type, feature_code, timestamp_ms, char_index " +
                "FROM usage_event_log WHERE language_pair=? AND lemma=? AND pos=? AND event_type=?");
        List<String> args = new ArrayList<>();
        args.add(languageKey);
        args.add(lemmaKey);
        args.add(posKey);
        args.add(eventType);
        if (!isEmpty(workId)) {
            sql.append(" AND work_id=?");
            args.add(sanitize(workId));
        }
        sql.append(" ORDER BY timestamp_ms ASC");
        try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                events.add(new UsageEvent(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getString(5),
                        c.getLong(6),
                        c.getInt(7)));
            }
        }
        return events;
    }

    public TimeBounds getTimeBounds(String languagePair) {
        String languageKey = sanitize(languagePair);
        try (Cursor c = db.rawQuery(
                "SELECT MIN(timestamp_ms), MAX(timestamp_ms) FROM usage_event_log WHERE language_pair=?",
                new String[]{languageKey})) {
            if (c.moveToFirst()) {
                long start = c.isNull(0) ? 0L : c.getLong(0);
                long end = c.isNull(1) ? 0L : c.getLong(1);
                return new TimeBounds(start, end);
            }
        }
        return new TimeBounds(0L, 0L);
    }

    public PositionBounds getCharBounds(String languagePair, String workId) {
        String languageKey = sanitize(languagePair);
        String workKey = sanitize(workId);
        try (Cursor c = db.rawQuery(
                "SELECT MIN(char_index), MAX(char_index) FROM usage_event_log WHERE language_pair=? AND work_id=? AND char_index>=0",
                new String[]{languageKey, workKey})) {
            if (c.moveToFirst()) {
                int min = c.isNull(0) ? 0 : c.getInt(0);
                int max = c.isNull(1) ? 0 : c.getInt(1);
                return new PositionBounds(min, max);
            }
        }
        return new PositionBounds(0, 0);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value;
    }

    public static class TimeBounds {
        public final long start;
        public final long end;

        public TimeBounds(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    public static class PositionBounds {
        public final int min;
        public final int max;

        public PositionBounds(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }
}
