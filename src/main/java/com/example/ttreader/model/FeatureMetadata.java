package com.example.UquReader.model;

import java.util.Collections;
import java.util.List;

public class FeatureMetadata {
    public final String code;
    public final String titleRu;
    public final String titleTt;
    public final String descriptionRu;
    public final List<String> phoneticForms;
    public final List<String> examples;

    public FeatureMetadata(String code, String titleRu, String titleTt, String descriptionRu, List<String> phoneticForms, List<String> examples) {
        this.code = code;
        this.titleRu = titleRu;
        this.titleTt = titleTt;
        this.descriptionRu = descriptionRu;
        this.phoneticForms = phoneticForms == null ? Collections.emptyList() : phoneticForms;
        this.examples = examples == null ? Collections.emptyList() : examples;
    }
}
