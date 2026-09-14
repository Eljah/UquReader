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
    public final int pageIndex;
    public final int sourcePage;
    public final String role;
    public final String footnoteId;

    public ReaderToken(int index, int charStart, int charEnd, String prefix, String surface,
                       String analysis, MorphologyData morphology, List<String> translations) {
        this(index, charStart, charEnd, prefix, surface, analysis, morphology, List.of(), translations);
    }

    public ReaderToken(int index, int charStart, int charEnd, String prefix, String surface,
                       String analysis, MorphologyData morphology, List<ReaderAnalysisVariant> analyses,
                       List<String> translations) {
        this(index, charStart, charEnd, prefix, surface, analysis, morphology, analyses, translations,
                -1, -1, "body", "");
    }

    public ReaderToken(int index, int charStart, int charEnd, String prefix, String surface,
                       String analysis, MorphologyData morphology, List<ReaderAnalysisVariant> analyses,
                       List<String> translations, int pageIndex, int sourcePage, String role, String footnoteId) {
        this.index = index;
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.prefix = prefix == null ? "" : prefix;
        this.surface = surface == null ? "" : surface;
        this.analysis = analysis == null ? "" : analysis;
        this.morphology = morphology;
        this.analyses = analyses == null ? Collections.emptyList() : Collections.unmodifiableList(analyses);
        this.translations = translations == null ? Collections.emptyList() : Collections.unmodifiableList(translations);
        this.pageIndex = pageIndex;
        this.sourcePage = sourcePage;
        this.role = role == null || role.isBlank() ? "body" : role;
        this.footnoteId = footnoteId == null ? "" : footnoteId;
    }
}
