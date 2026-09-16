package com.example.uqureader.webapp.reader;

public final class FeatureStat {
    public final String featureKey;
    public final String language;
    public final String workId;
    public final long exposureCount;
    public final long committedCount;
    public final long lookupCount;
    public final long totalVisibleMs;
    public final long firstSeenAtMs;
    public final long lastSeenAtMs;

    public FeatureStat(String featureKey, long exposureCount, long committedCount,
                       long lookupCount, long totalVisibleMs, long lastSeenAtMs) {
        this(featureKey, "", "", exposureCount, committedCount, lookupCount, totalVisibleMs, 0, lastSeenAtMs);
    }

    public FeatureStat(String featureKey, String language, String workId, long exposureCount, long committedCount,
                       long lookupCount, long totalVisibleMs, long lastSeenAtMs) {
        this(featureKey, language, workId, exposureCount, committedCount, lookupCount, totalVisibleMs, 0, lastSeenAtMs);
    }

    public FeatureStat(String featureKey, String language, String workId, long exposureCount, long committedCount,
                       long lookupCount, long totalVisibleMs, long firstSeenAtMs, long lastSeenAtMs) {
        this.featureKey = featureKey == null ? "" : featureKey;
        this.language = language == null ? "" : language;
        this.workId = workId == null ? "" : workId;
        this.exposureCount = exposureCount;
        this.committedCount = committedCount;
        this.lookupCount = lookupCount;
        this.totalVisibleMs = totalVisibleMs;
        this.firstSeenAtMs = firstSeenAtMs;
        this.lastSeenAtMs = lastSeenAtMs;
    }
}
