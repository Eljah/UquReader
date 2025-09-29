package com.example.uqureader.webapp.morphology;

import com.example.uqureader.webapp.morphology.RemoteMorphologyClient.WordMarkup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoteMarkupFormatterTest {

    @Test
    void convertsMarkupToResourceFormat() {
        List<WordMarkup> tokens = List.of(
                new WordMarkup("Комедия", List.of("комедия+N+Sg+Nom")),
                new WordMarkup("1", List.of("Num")),
                new WordMarkup("иске", List.of("NR", "иске+Adj"))
        );

        String formatted = RemoteMorphologyClient.formatAsMarkup(tokens);

        assertEquals("Комедия\tкомедия+N+Sg+Nom;\n1\tNum\nиске\tNR;иске+Adj;", formatted);
    }

    @Test
    void fallsBackToErrorWhenAnalysesMissing() {
        List<WordMarkup> tokens = List.of(new WordMarkup("?", List.of(" ")));

        String formatted = RemoteMorphologyClient.formatAsMarkup(tokens);

        assertEquals("?\tError", formatted);
    }
}
