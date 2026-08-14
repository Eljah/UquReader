package com.example.uqureader.webapp.reader;

public final class MorphFeatureData {
    public final String code;
    public final String canonical;
    public final String actual;

    public MorphFeatureData(String code, String canonical, String actual) {
        this.code = code == null ? "" : code;
        this.canonical = canonical == null ? "" : canonical;
        this.actual = actual == null ? "" : actual;
    }
}
