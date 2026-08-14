package com.example.uqureader.webapp.reader;

import java.util.Collections;
import java.util.List;

public final class MorphologyData {
    public final String lemma;
    public final String pos;
    public final List<MorphFeatureData> features;
    public final List<String> segments;
    public final String featureKey;
    public final String analysis;

    public MorphologyData(String lemma, String pos, List<MorphFeatureData> features,
                          List<String> segments, String featureKey, String analysis) {
        this.lemma = lemma == null ? "" : lemma;
        this.pos = pos == null ? "" : pos;
        this.features = features == null ? Collections.emptyList() : Collections.unmodifiableList(features);
        this.segments = segments == null ? Collections.emptyList() : Collections.unmodifiableList(segments);
        this.featureKey = featureKey == null ? "" : featureKey;
        this.analysis = analysis == null ? "" : analysis;
    }
}
