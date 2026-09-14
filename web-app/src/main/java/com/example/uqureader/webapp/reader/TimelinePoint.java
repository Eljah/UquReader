package com.example.uqureader.webapp.reader;

public final class TimelinePoint {
    public final String eventType;
    public final long bucketStartMs;
    public final long eventCount;
    public final long totalVisibleMs;
    public final long firstSeenAtMs;
    public final long lastSeenAtMs;

    public TimelinePoint(String eventType, long bucketStartMs, long eventCount, long totalVisibleMs,
                         long firstSeenAtMs, long lastSeenAtMs) {
        this.eventType = eventType == null ? "" : eventType;
        this.bucketStartMs = bucketStartMs;
        this.eventCount = eventCount;
        this.totalVisibleMs = totalVisibleMs;
        this.firstSeenAtMs = firstSeenAtMs;
        this.lastSeenAtMs = lastSeenAtMs;
    }
}
