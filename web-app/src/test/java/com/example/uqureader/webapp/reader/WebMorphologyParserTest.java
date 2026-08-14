package com.example.uqureader.webapp.reader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebMorphologyParserTest {
    @Test
    void splitsPossessiveAndDirectionalSuffixesRightToLeft() {
        MorphologyData morphology = WebMorphologyParser.parse(
                "\u044f\u0448\u0435\u043d\u04d9",
                "\u044f\u0448\u044c+N+Sg+POSS_3(\u0421\u042b)+DIR(\u0413\u0410);");

        assertEquals(List.of("\u044f\u0448\u044c", "\u0435", "\u043d\u04d9"), morphology.segments);
        assertEquals("\u0435", morphology.features.get(1).actual);
        assertEquals("\u043d\u04d9", morphology.features.get(2).actual);
    }
}
