package com.example.uqureader.webapp.reader;

public final class ReadingEvent {
    public final String clientEventId;
    public final String eventType;
    public final String workId;
    public final String language;
    public final int pageIndex;
    public final int tokenIndex;
    public final String lemma;
    public final String pos;
    public final String featureKey;
    public final int charIndex;
    public final int visibleMs;
    public final long occurredAtMs;

    public ReadingEvent(String clientEventId, String eventType, String workId, int pageIndex,
                        int tokenIndex, String lemma, String pos, String featureKey,
                        int charIndex, int visibleMs, long occurredAtMs) {
        this(clientEventId, eventType, workId, "", pageIndex, tokenIndex, lemma, pos, featureKey,
                charIndex, visibleMs, occurredAtMs);
    }

    public ReadingEvent(String clientEventId, String eventType, String workId, String language, int pageIndex,
                        int tokenIndex, String lemma, String pos, String featureKey,
                        int charIndex, int visibleMs, long occurredAtMs) {
        this.clientEventId = clientEventId == null ? "" : clientEventId;
        this.eventType = eventType == null ? "" : eventType;
        this.workId = workId == null ? "" : workId;
        this.language = language == null ? "" : language;
        this.pageIndex = pageIndex;
        this.tokenIndex = tokenIndex;
        this.lemma = lemma == null ? "" : lemma;
        this.pos = pos == null ? "" : pos;
        this.featureKey = featureKey == null ? "" : featureKey;
        this.charIndex = charIndex;
        this.visibleMs = Math.max(0, visibleMs);
        this.occurredAtMs = occurredAtMs <= 0 ? System.currentTimeMillis() : occurredAtMs;
    }
}
