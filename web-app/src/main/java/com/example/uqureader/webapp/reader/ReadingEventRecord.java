package com.example.uqureader.webapp.reader;

public final class ReadingEventRecord {
    public final String eventType;
    public final String workId;
    public final int pageIndex;
    public final int tokenIndex;
    public final String lemma;
    public final String pos;
    public final String featureKey;
    public final int charIndex;
    public final int visibleMs;
    public final long occurredAtMs;

    public ReadingEventRecord(String eventType, String workId, int pageIndex, int tokenIndex,
                              String lemma, String pos, String featureKey, int charIndex,
                              int visibleMs, long occurredAtMs) {
        this.eventType = eventType == null ? "" : eventType;
        this.workId = workId == null ? "" : workId;
        this.pageIndex = pageIndex;
        this.tokenIndex = tokenIndex;
        this.lemma = lemma == null ? "" : lemma;
        this.pos = pos == null ? "" : pos;
        this.featureKey = featureKey == null ? "" : featureKey;
        this.charIndex = charIndex;
        this.visibleMs = Math.max(0, visibleMs);
        this.occurredAtMs = occurredAtMs;
    }
}
