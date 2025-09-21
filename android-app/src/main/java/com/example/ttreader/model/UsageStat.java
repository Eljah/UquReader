package com.example.ttreader.model;

public class UsageStat {
    public final String languagePair;
    public final String workId;
    public final String lemma;
    public final String pos;
    public final String eventType;
    public final String featureCode;
    public final int count;
    public final long lastSeenMs;
    public final int lastPosition;

    public UsageStat(String languagePair, String workId, String lemma, String pos,
                     String eventType, String featureCode, int count, long lastSeenMs, int lastPosition) {
        this.languagePair = languagePair;
        this.workId = workId;
        this.lemma = lemma;
        this.pos = pos;
        this.eventType = eventType;
        this.featureCode = featureCode;
        this.count = count;
        this.lastSeenMs = lastSeenMs;
        this.lastPosition = lastPosition;
    }
}
