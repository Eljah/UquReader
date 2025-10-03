package com.example.uqureader.webapp.morphology;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MorphologyAnalyzerFallbackTest {

    @Test
    void fallbackPrefersMarkupEntriesWithTranslations() {
        MorphologyAnalyzer analyzer = MorphologyAnalyzer.loadDefault();
        String analysis = analyzer.lookup("И");
        assertTrue(analysis.contains("и: быть"),
                "Fallback dictionary should retain translation annotations when choosing the most informative entry");
    }
}
