package com.example.ttreader.model;

public class UsageEvent {
    public final String languagePair;
    public final String workId;
    public final String lemma;
    public final String pos;
    public final String eventType;
    public final String featureCode;
    public final long timestampMs;
    public final int charIndex;

    public UsageEvent(String languagePair, String workId, String lemma, String pos,
                      String eventType, String featureCode, long timestampMs, int charIndex) {
        this.languagePair = languagePair;
        this.workId = workId;
        this.lemma = lemma;
        this.pos = pos;
        this.eventType = eventType;
        this.featureCode = featureCode;
        this.timestampMs = timestampMs;
        this.charIndex = charIndex;
    }
}
