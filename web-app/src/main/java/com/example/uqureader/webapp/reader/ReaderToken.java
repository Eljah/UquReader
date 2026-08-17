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
    public final List<ReaderAnalysisVariant> analyses;
    public final List<String> translations;

    public ReaderToken(int index, int charStart, int charEnd, String prefix, String surface,
                       String analysis, MorphologyData morphology, List<String> translations) {
        this(index, charStart, charEnd, prefix, surface, analysis, morphology, List.of(), translations);
    }

    public ReaderToken(int index, int charStart, int charEnd, String prefix, String surface,
                       String analysis, MorphologyData morphology, List<ReaderAnalysisVariant> analyses,
                       List<String> translations) {
        this.index = index;
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.prefix = prefix == null ? "" : prefix;
        this.surface = surface == null ? "" : surface;
        this.analysis = analysis == null ? "" : analysis;
        this.morphology = morphology;
        this.analyses = analyses == null ? Collections.emptyList() : Collections.unmodifiableList(analyses);
        this.translations = translations == null ? Collections.emptyList() : Collections.unmodifiableList(translations);
    }
}
