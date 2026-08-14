package com.example.uqureader.webapp.reader;

public final class ReadingState {
    public final String workId;
    public final int pageIndex;
    public final int charIndex;
    public final long updatedAtMs;

    public ReadingState(String workId, int pageIndex, int charIndex, long updatedAtMs) {
        this.workId = workId == null ? "" : workId;
        this.pageIndex = Math.max(0, pageIndex);
        this.charIndex = Math.max(0, charIndex);
        this.updatedAtMs = updatedAtMs <= 0 ? System.currentTimeMillis() : updatedAtMs;
    }
}
