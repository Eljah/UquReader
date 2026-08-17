package com.example.uqureader.webapp.reader;

import java.util.Collections;
import java.util.List;

public final class ReaderAnalysisVariant {
    public final String analysis;
    public final String lemma;
    public final List<String> segments;
    public final List<String> gloss;
    public final List<String> pos;
    public final List<String> translations;
    public final List<String> posDescriptions;
    public final List<String> glossDescriptions;
    public final List<ReaderAnalysisFeatureRow> featureRows;
    public final MorphologyData morphology;

    public ReaderAnalysisVariant(String analysis, String lemma, List<String> segments,
                                 List<String> gloss, List<String> pos, MorphologyData morphology) {
        this(analysis, lemma, segments, gloss, pos, List.of(), List.of(), List.of(), List.of(), morphology);
    }

    public ReaderAnalysisVariant(String analysis, String lemma, List<String> segments,
                                 List<String> gloss, List<String> pos, List<String> translations,
                                 List<String> posDescriptions, List<String> glossDescriptions,
                                 List<ReaderAnalysisFeatureRow> featureRows,
                                 MorphologyData morphology) {
        this.analysis = analysis == null ? "" : analysis;
        this.lemma = lemma == null ? "" : lemma;
        this.segments = segments == null ? Collections.emptyList() : Collections.unmodifiableList(segments);
        this.gloss = gloss == null ? Collections.emptyList() : Collections.unmodifiableList(gloss);
        this.pos = pos == null ? Collections.emptyList() : Collections.unmodifiableList(pos);
        this.translations = translations == null ? Collections.emptyList() : Collections.unmodifiableList(translations);
        this.posDescriptions = posDescriptions == null ? Collections.emptyList() : Collections.unmodifiableList(posDescriptions);
        this.glossDescriptions = glossDescriptions == null ? Collections.emptyList() : Collections.unmodifiableList(glossDescriptions);
        this.featureRows = featureRows == null ? Collections.emptyList() : Collections.unmodifiableList(featureRows);
        this.morphology = morphology;
    }
}
