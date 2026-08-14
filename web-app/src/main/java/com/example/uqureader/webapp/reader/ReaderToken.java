package com.example.uqureader.webapp.reader;

import java.util.Collections;
import java.util.List;

public final class ReaderToken {
    public final int index;
    public final int charStart;
    public final int charEnd;
    public final String prefix;
    public final String surface;
    public final String analysis;
    public final MorphologyData morphology;
    public final List<String> translations;

    public ReaderToken(int index, int charStart, int charEnd, String prefix, String surface,
                       String analysis, MorphologyData morphology, List<String> translations) {
        this.index = index;
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.prefix = prefix == null ? "" : prefix;
        this.surface = surface == null ? "" : surface;
        this.analysis = analysis == null ? "" : analysis;
        this.morphology = morphology;
        this.translations = translations == null ? Collections.emptyList() : Collections.unmodifiableList(translations);
    }
}
