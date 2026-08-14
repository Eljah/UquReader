package com.example.uqureader.webapp.reader;

import java.util.Collections;
import java.util.List;

public final class GrammarFeature {
    public final String code;
    public final String titleRu;
    public final String titleTt;
    public final String descriptionRu;
    public final List<String> phoneticForms;
    public final List<String> examples;

    public GrammarFeature(String code, String titleRu, String titleTt, String descriptionRu,
                          List<String> phoneticForms, List<String> examples) {
        this.code = code == null ? "" : code;
        this.titleRu = titleRu == null ? "" : titleRu;
        this.titleTt = titleTt == null ? "" : titleTt;
        this.descriptionRu = descriptionRu == null ? "" : descriptionRu;
        this.phoneticForms = phoneticForms == null ? Collections.emptyList() : Collections.unmodifiableList(phoneticForms);
        this.examples = examples == null ? Collections.emptyList() : Collections.unmodifiableList(examples);
    }
}
