package com.example.uqureader.webapp.reader;

import java.util.Collections;
import java.util.List;

public final class ReaderWork {
    public final String id;
    public final String title;
    public final String assetName;
    public final int tokenCount;
    public final int charCount;
    public final List<ReaderToken> tokens;

    public ReaderWork(String id, String title, String assetName, List<ReaderToken> tokens, int charCount) {
        this.id = id;
        this.title = title;
        this.assetName = assetName;
        this.tokens = tokens == null ? Collections.emptyList() : Collections.unmodifiableList(tokens);
        this.tokenCount = this.tokens.size();
        this.charCount = charCount;
    }
}
