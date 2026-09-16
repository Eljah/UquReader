package com.example.uqureader.webapp.reader;

public final class TimelineBounds {
    public final long languageStartMs;
    public final long languageEndMs;
    public final long workStartMs;
    public final long workEndMs;
    public final long itemLanguageStartMs;
    public final long itemLanguageEndMs;
    public final long itemWorkStartMs;
    public final long itemWorkEndMs;

    public TimelineBounds(long languageStartMs, long languageEndMs, long workStartMs, long workEndMs,
                          long itemLanguageStartMs, long itemLanguageEndMs,
                          long itemWorkStartMs, long itemWorkEndMs) {
        this.languageStartMs = languageStartMs;
        this.languageEndMs = languageEndMs;
        this.workStartMs = workStartMs;
        this.workEndMs = workEndMs;
        this.itemLanguageStartMs = itemLanguageStartMs;
        this.itemLanguageEndMs = itemLanguageEndMs;
        this.itemWorkStartMs = itemWorkStartMs;
        this.itemWorkEndMs = itemWorkEndMs;
    }
}
