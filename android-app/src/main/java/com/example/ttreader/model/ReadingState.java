package com.example.ttreader.model;

public class ReadingState {
    public static final String MODE_VISUAL = "visual";
    public static final String MODE_VOICE = "voice";

    public final String languagePair;
    public final String workId;
    public final String lastMode;
    public final int visualPage;
    public final int visualCharIndex;
    public final int voiceSentenceIndex;
    public final int voiceCharIndex;
    public final long updatedMs;

    public ReadingState(String languagePair, String workId, String lastMode,
                        int visualPage, int visualCharIndex,
                        int voiceSentenceIndex, int voiceCharIndex,
                        long updatedMs) {
        this.languagePair = languagePair;
        this.workId = workId;
        this.lastMode = lastMode == null ? "" : lastMode;
        this.visualPage = visualPage;
        this.visualCharIndex = visualCharIndex;
        this.voiceSentenceIndex = voiceSentenceIndex;
        this.voiceCharIndex = voiceCharIndex;
        this.updatedMs = updatedMs;
    }

    public boolean isVisualMode() {
        return MODE_VISUAL.equals(lastMode);
    }

    public boolean isVoiceMode() {
        return MODE_VOICE.equals(lastMode);
    }
}
