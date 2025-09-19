package com.example.UquReader.model;

public class Token {
    public String prefix = "";
    public String surface = "";
    public String analysis;
    public Morphology morphology;

    public boolean hasMorphology() {
        return morphology != null;
    }
}
