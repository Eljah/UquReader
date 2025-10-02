package com.example.uqureader.webapp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MorphologyServiceTest {

    private MorphologyService service;

    @BeforeAll
    void setUp() {
        service = new MorphologyService();
    }

    @AfterAll
    void tearDown() {
        service.close();
    }

    @Test
    void analyzeTokenReturnsKnownTag() {
        JsonObject actual = service.analyzeToken("Комедия");
        Assertions.assertEquals("Комедия", actual.get("token").getAsString());
        Assertions.assertEquals("комедия+N+Sg+Nom;", actual.get("tag").getAsString());
    }

    @Test
    void analyzeTextMatchesBundledMarkup() throws IOException {
        String text = readResource("/texts/berenche_teatr.txt");
        String expectedMarkup = readResource("/markup/berenche_teatr.txt.morph.tsv");

        JsonObject analysis = service.analyzeText(text);
        Assertions.assertEquals(6378, analysis.get("tokens_count").getAsInt());
        Assertions.assertEquals(1659, analysis.get("unique_tokens_count").getAsInt());
        Assertions.assertEquals(936, analysis.get("sentenes_count").getAsInt());

        String actualMarkup = service.markup(text);
        Assertions.assertEquals(expectedMarkup, actualMarkup);

        JsonArray sentences = analysis.getAsJsonArray("sentences");
        Assertions.assertFalse(sentences.isEmpty());
        JsonArray firstSentence = sentences.get(0).getAsJsonArray();
        Assertions.assertEquals("Комедия", firstSentence.get(0).getAsJsonArray().get(0).getAsString());
    }

    private String readResource(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing resource: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }
}
