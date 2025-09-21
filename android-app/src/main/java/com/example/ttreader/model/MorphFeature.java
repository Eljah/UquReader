package com.example.ttreader.model;

public class MorphFeature {
    public final String code;
    public final String canonical;
    public String actual;

    public MorphFeature(String code, String canonical) {
        this.code = code;
        this.canonical = canonical;
        this.actual = "";
    }
}
