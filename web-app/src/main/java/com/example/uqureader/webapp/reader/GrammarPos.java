package com.example.uqureader.webapp.reader;

public final class GrammarPos {
    public final String code;
    public final String titleTt;
    public final String titleRu;

    public GrammarPos(String code, String titleTt, String titleRu) {
        this.code = code == null ? "" : code;
        this.titleTt = titleTt == null ? "" : titleTt;
        this.titleRu = titleRu == null ? "" : titleRu;
    }
}
