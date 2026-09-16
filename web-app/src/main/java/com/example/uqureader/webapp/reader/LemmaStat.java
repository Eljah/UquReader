package com.example.uqureader.webapp.reader;

public final class LemmaStat {
    public final String lemma;
    public final String pos;
    public final String language;
    public final String workId;
    public final long exposureCount;
    public final long committedCount;
    public final long lookupCount;
    public final long ttsCount;
    public final long totalVisibleMs;
    public final long firstSeenAtMs;
    public final long lastSeenAtMs;
    public final int firstPageIndex;
    public final int firstTokenIndex;
    public final int firstCharIndex;

    public LemmaStat(String lemma, String pos, long exposureCount, long committedCount,
                     long lookupCount, long ttsCount, long totalVisibleMs, long lastSeenAtMs) {
        this(lemma, pos, "", "", exposureCount, committedCount, lookupCount, ttsCount, totalVisibleMs, 0, lastSeenAtMs);
    }

    public LemmaStat(String lemma, String pos, String language, String workId, long exposureCount, long committedCount,
                     long lookupCount, long ttsCount, long totalVisibleMs, long lastSeenAtMs) {
        this(lemma, pos, language, workId, exposureCount, committedCount, lookupCount, ttsCount, totalVisibleMs,
                0, lastSeenAtMs);
    }

    public LemmaStat(String lemma, String pos, String language, String workId, long exposureCount, long committedCount,
                     long lookupCount, long ttsCount, long totalVisibleMs, long firstSeenAtMs, long lastSeenAtMs) {
        this(lemma, pos, language, workId, exposureCount, committedCount, lookupCount, ttsCount, totalVisibleMs,
                firstSeenAtMs, lastSeenAtMs, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public LemmaStat(String lemma, String pos, String language, String workId, long exposureCount, long committedCount,
                     long lookupCount, long ttsCount, long totalVisibleMs, long firstSeenAtMs, long lastSeenAtMs,
                     int firstPageIndex, int firstTokenIndex, int firstCharIndex) {
        this.lemma = lemma == null ? "" : lemma;
        this.pos = pos == null ? "" : pos;
        this.language = language == null ? "" : language;
        this.workId = workId == null ? "" : workId;
        this.exposureCount = exposureCount;
        this.committedCount = committedCount;
        this.lookupCount = lookupCount;
        this.ttsCount = ttsCount;
        this.totalVisibleMs = totalVisibleMs;
        this.firstSeenAtMs = firstSeenAtMs;
        this.lastSeenAtMs = lastSeenAtMs;
        this.firstPageIndex = firstPageIndex;
        this.firstTokenIndex = firstTokenIndex;
        this.firstCharIndex = firstCharIndex;
    }
}
