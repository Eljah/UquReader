package com.example.uqureader.webapp.reader;

import java.util.Collections;
import java.util.List;

public final class ReaderStats {
    public final List<LemmaStat> lemmas;
    public final List<FeatureStat> features;

    public ReaderStats(List<LemmaStat> lemmas, List<FeatureStat> features) {
        this.lemmas = lemmas == null ? Collections.emptyList() : Collections.unmodifiableList(lemmas);
        this.features = features == null ? Collections.emptyList() : Collections.unmodifiableList(features);
    }
}
