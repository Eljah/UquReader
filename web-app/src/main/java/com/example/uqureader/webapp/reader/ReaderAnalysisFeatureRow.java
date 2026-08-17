package com.example.uqureader.webapp.reader;

import java.util.Collections;
import java.util.List;

public final class ReaderAnalysisFeatureRow {
    public final String segment;
    public final String pos;
    public final String gloss;
    public final List<String> descriptions;

    public ReaderAnalysisFeatureRow(String segment, String pos, String gloss, List<String> descriptions) {
        this.segment = segment == null ? "" : segment;
        this.pos = pos == null ? "" : pos;
        this.gloss = gloss == null ? "" : gloss;
        this.descriptions = descriptions == null ? Collections.emptyList() : Collections.unmodifiableList(descriptions);
    }
}
