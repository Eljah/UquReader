package com.example.UquReader.model;

import java.util.ArrayList;
import java.util.List;

public class Morphology {
    public final String lemma;
    public final String pos;
    public final List<MorphFeature> features;
    public final List<String> segments;
    public final String featureKey;
    public final String analysis;

    public Morphology(String lemma, String pos, List<MorphFeature> features, List<String> segments, String featureKey, String analysis) {
        this.lemma = lemma;
        this.pos = pos;
        this.features = features;
        this.segments = segments;
        this.featureKey = featureKey;
        this.analysis = analysis;
    }

    public List<String> getFeatureCodes() {
        List<String> codes = new ArrayList<>();
        for (MorphFeature f : features) codes.add(f.code);
        return codes;
    }

    public String getSegmentedSurface() {
        if (segments == null || segments.isEmpty()) return "";
        return String.join("-", segments);
    }
}
