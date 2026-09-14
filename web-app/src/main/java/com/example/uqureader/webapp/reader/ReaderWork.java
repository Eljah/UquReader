package com.example.uqureader.webapp.reader;

import java.util.Collections;
import java.util.List;

public final class ReaderWork {
    public final String id;
    public final String title;
    public final String assetName;
    public final String language;
    public final int tokenCount;
    public final int charCount;
    public final List<ReaderToken> tokens;
    public final List<List<ReaderToken>> pages;
    public final int pageCount;
    public final boolean sourcePaged;

    public ReaderWork(String id, String title, String assetName, List<ReaderToken> tokens, int charCount) {
        this.id = id;
        this.title = title;
        this.assetName = assetName;
        this.language = inferLanguage(id, assetName);
        this.tokens = tokens == null ? Collections.emptyList() : Collections.unmodifiableList(tokens);
        this.tokenCount = this.tokens.size();
        this.charCount = charCount;
        this.pages = Collections.unmodifiableList(buildPages(this.tokens));
        this.pageCount = this.pages.size();
        this.sourcePaged = this.tokens.stream().anyMatch(token -> token.pageIndex >= 0);
    }

    private static String inferLanguage(String id, String assetName) {
        String value = ((id == null ? "" : id) + " " + (assetName == null ? "" : assetName)).toLowerCase();
        if (value.contains("elnet") || value.contains("puncheryshte")) {
            return "mhr";
        }
        return "tt";
    }

    private static List<List<ReaderToken>> buildPages(List<ReaderToken> tokens) {
        if (tokens == null || tokens.isEmpty() || tokens.stream().noneMatch(token -> token.pageIndex >= 0)) {
            return Collections.emptyList();
        }
        int maxPage = tokens.stream().mapToInt(token -> Math.max(0, token.pageIndex)).max().orElse(0);
        java.util.ArrayList<List<ReaderToken>> result = new java.util.ArrayList<>();
        for (int page = 0; page <= maxPage; page++) {
            java.util.ArrayList<ReaderToken> pageTokens = new java.util.ArrayList<>();
            for (ReaderToken token : tokens) {
                if (token.pageIndex == page) {
                    pageTokens.add(token);
                }
            }
            result.add(Collections.unmodifiableList(pageTokens));
        }
        return result;
    }
}
