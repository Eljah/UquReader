package com.example.uqureader.webapp.reader;

public final class FeatureStat {
    public final String featureKey;
    public final long exposureCount;
    public final long committedCount;
    public final long lookupCount;
    public final long totalVisibleMs;
    public final long lastSeenAtMs;

    public FeatureStat(String featureKey, long exposureCount, long committedCount,
                       long lookupCount, long totalVisibleMs, long lastSeenAtMs) {
        this.featureKey = featureKey == null ? "" : featureKey;
        this.exposureCount = exposureCount;
        this.committedCount = committedCount;
        this.lookupCount = lookupCount;
        this.totalVisibleMs = totalVisibleMs;
        this.lastSeenAtMs = lastSeenAtMs;
    }
}
