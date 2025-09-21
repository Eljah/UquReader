package com.example.ttreader.model;

import java.util.Collections;
import java.util.List;

public class Token {
    public String prefix = "";
    public String surface = "";
    public String analysis;
    public Morphology morphology;
    public List<String> translations = Collections.emptyList();

    public boolean hasMorphology() {
        return morphology != null;
    }
}
