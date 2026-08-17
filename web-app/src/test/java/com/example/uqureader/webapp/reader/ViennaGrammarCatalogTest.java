package com.example.uqureader.webapp.reader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViennaGrammarCatalogTest {
    @Test
    void describesKnownViennaTags() {
        assertTrue(ViennaGrammarCatalog.describePos("-case").contains("падеж"));
        assertTrue(ViennaGrammarCatalog.describePos("no").contains("существительное"));
        assertTrue(ViennaGrammarCatalog.describePos("ad/av/no").contains("прилагательное"));
        assertTrue(ViennaGrammarCatalog.describeGloss("-PST1.3SG").contains("прошедшее"));
        assertTrue(ViennaGrammarCatalog.describeGloss("-ACC").contains("винительный"));
        assertFalse(ViennaGrammarCatalog.describeGloss("-CVB.SIM.3PL").isBlank());
        assertTrue(ViennaGrammarCatalog.describeGloss("NEG").contains("отрицание"));
        assertTrue(ViennaGrammarCatalog.describeGloss("VLAK").contains("множественное"));
        assertTrue(ViennaGrammarCatalog.describeFeatureGloss("listen").isBlank());
        assertTrue(ViennaGrammarCatalog.describeFeatureGloss("-CVB.SIM.3SG").contains("деепричастие"));
    }
}
