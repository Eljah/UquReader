package com.example.ttreader.model;

public class UsageStat {
    public final String lemma;
    public final String pos;
    public final String eventType;
    public final String featureCode;
    public final int count;
    public final long lastSeenMs;

    public UsageStat(String lemma, String pos, String eventType, String featureCode, int count, long lastSeenMs) {
        this.lemma = lemma;
        this.pos = pos;
        this.eventType = eventType;
        this.featureCode = featureCode;
        this.count = count;
        this.lastSeenMs = lastSeenMs;
    }
}
