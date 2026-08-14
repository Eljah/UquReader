package com.example.uqureader.webapp.reader;

public final class LemmaStat {
    public final String lemma;
    public final String pos;
    public final long exposureCount;
    public final long committedCount;
    public final long lookupCount;
    public final long ttsCount;
    public final long totalVisibleMs;
    public final long lastSeenAtMs;

    public LemmaStat(String lemma, String pos, long exposureCount, long committedCount,
                     long lookupCount, long ttsCount, long totalVisibleMs, long lastSeenAtMs) {
        this.lemma = lemma == null ? "" : lemma;
        this.pos = pos == null ? "" : pos;
        this.exposureCount = exposureCount;
        this.committedCount = committedCount;
        this.lookupCount = lookupCount;
        this.ttsCount = ttsCount;
        this.totalVisibleMs = totalVisibleMs;
        this.lastSeenAtMs = lastSeenAtMs;
    }
}
