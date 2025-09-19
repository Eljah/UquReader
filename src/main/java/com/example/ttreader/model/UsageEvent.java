package com.example.ttreader.model;

public class UsageEvent {
    public final String lemma;
    public final String pos;
    public final String featureCode;
    public final String eventType;
    public final long timestampMs;
    public final String bookId;

    public UsageEvent(String lemma, String pos, String featureCode, String eventType, long timestampMs, String bookId) {
        this.lemma = lemma;
        this.pos = pos;
        this.featureCode = featureCode;
        this.eventType = eventType;
        this.timestampMs = timestampMs;
        this.bookId = bookId;
    }
}
