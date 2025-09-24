package com.example.ttreader.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

public class DeviceStatsDao {
    private final SQLiteDatabase db;
    private final DbWriteQueue writeQueue;

    public DeviceStatsDao(SQLiteDatabase db) {
        this(db, DbWriteQueue.getInstance());
    }

    public DeviceStatsDao(SQLiteDatabase db, DbWriteQueue queue) {
        this.db = db;
        this.writeQueue = queue;
    }

    public void recordPauseReaction(DeviceIdentity device, long pauseOffsetMs,
                                    long targetOffsetMs, long deltaMs, long charDelta,
                                    String languagePair, String workId, long recordedAtMs) {
        if (device == null || !device.shouldTrack()) {
            return;
        }
        final String descriptor = sanitize(device.stableKey());
        final String displayName = sanitize(device.displayName);
        final int vendorId = device.vendorId;
        final int productId = device.productId;
        final int sourceFlags = device.sourceFlags;
        final int bluetoothLikely = device.bluetoothLikely ? 1 : 0;
        final long safePauseOffset = Math.max(0L, pauseOffsetMs);
        final long safeTargetOffset = Math.max(0L, targetOffsetMs);
        final long safeDelta = Math.max(0L, deltaMs);
        final long safeCharDelta = Math.max(0L, charDelta);
        final String safeLanguage = sanitize(languagePair);
        final String safeWork = sanitize(workId);
        final long timestamp = recordedAtMs <= 0 ? System.currentTimeMillis() : recordedAtMs;

        writeQueue.enqueue(() -> {
            ContentValues event = new ContentValues();
            event.put("descriptor", descriptor);
            event.put("display_name", displayName);
            event.put("vendor_id", vendorId);
            event.put("product_id", productId);
            event.put("source_flags", sourceFlags);
            event.put("bluetooth_likely", bluetoothLikely);
            event.put("pause_offset_ms", safePauseOffset);
            event.put("target_offset_ms", safeTargetOffset);
            event.put("delta_ms", safeDelta);
            event.put("char_delta", safeCharDelta);
            event.put("language_pair", safeLanguage);
            event.put("work_id", safeWork);
            event.put("recorded_at_ms", timestamp);
            db.insert("device_pause_events", null, event);

            int count = 0;
            double avgDelay = 0;
            try (Cursor c = db.rawQuery(
                    "SELECT sample_count, avg_reaction_delay_ms FROM device_reaction_stats WHERE descriptor=?",
                    new String[]{descriptor})) {
                if (c.moveToFirst()) {
                    count = c.getInt(0);
                    avgDelay = c.getDouble(1);
                }
            }
            int newCount = count + 1;
            double newAvg = newCount == 0 ? safeDelta
                    : ((avgDelay * count) + safeDelta) / Math.max(1, newCount);
            ContentValues stats = new ContentValues();
            stats.put("descriptor", descriptor);
            stats.put("display_name", displayName);
            stats.put("vendor_id", vendorId);
            stats.put("product_id", productId);
            stats.put("source_flags", sourceFlags);
            stats.put("bluetooth_likely", bluetoothLikely);
            stats.put("sample_count", newCount);
            stats.put("avg_reaction_delay_ms", newAvg);
            stats.put("last_seen_ms", timestamp);
            db.insertWithOnConflict("device_reaction_stats", null, stats, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    public DeviceReactionStats getStats(String descriptor) {
        String key = sanitize(descriptor);
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        try (Cursor c = db.rawQuery(
                "SELECT descriptor, display_name, vendor_id, product_id, source_flags, bluetooth_likely, sample_count, avg_reaction_delay_ms, last_seen_ms " +
                        "FROM device_reaction_stats WHERE descriptor=?",
                new String[]{key})) {
            if (c.moveToFirst()) {
                return new DeviceReactionStats(
                        c.getString(0),
                        c.getString(1),
                        c.getInt(2),
                        c.getInt(3),
                        c.getInt(4),
                        c.getInt(5) != 0,
                        c.getInt(6),
                        c.getDouble(7),
                        c.getLong(8));
            }
        }
        return null;
    }

    public List<DeviceReactionStats> getAllStats() {
        List<DeviceReactionStats> results = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT descriptor, display_name, vendor_id, product_id, source_flags, bluetooth_likely, sample_count, avg_reaction_delay_ms, last_seen_ms " +
                        "FROM device_reaction_stats ORDER BY avg_reaction_delay_ms ASC, descriptor ASC",
                null)) {
            while (c.moveToNext()) {
                results.add(new DeviceReactionStats(
                        c.getString(0),
                        c.getString(1),
                        c.getInt(2),
                        c.getInt(3),
                        c.getInt(4),
                        c.getInt(5) != 0,
                        c.getInt(6),
                        c.getDouble(7),
                        c.getLong(8)));
            }
        }
        return results;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value;
    }

    public static final class DeviceReactionStats {
        public final String descriptor;
        public final String displayName;
        public final int vendorId;
        public final int productId;
        public final int sourceFlags;
        public final boolean bluetoothLikely;
        public final int sampleCount;
        public final double averageDelayMs;
        public final long lastSeenMs;

        DeviceReactionStats(String descriptor, String displayName, int vendorId, int productId,
                            int sourceFlags, boolean bluetoothLikely, int sampleCount,
                            double averageDelayMs, long lastSeenMs) {
            this.descriptor = descriptor == null ? "" : descriptor;
            this.displayName = displayName == null ? "" : displayName;
            this.vendorId = vendorId;
            this.productId = productId;
            this.sourceFlags = sourceFlags;
            this.bluetoothLikely = bluetoothLikely;
            this.sampleCount = sampleCount;
            this.averageDelayMs = averageDelayMs;
            this.lastSeenMs = lastSeenMs;
        }
    }
}
