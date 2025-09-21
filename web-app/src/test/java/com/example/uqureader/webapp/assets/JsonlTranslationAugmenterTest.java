package com.example.uqureader.webapp.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlTranslationAugmenterTest {

    @TempDir
    Path tempDir;

    @Test
    void augmentAddsTranslationsAndIsIdempotent() throws Exception {
        Path assets = Files.createDirectory(tempDir.resolve("assets"));
        Path dictionary = tempDir.resolve("tat_rus_dictionary.db");
        createDictionary(dictionary);

        Path jsonl = assets.resolve("sample.jsonl");
        Files.writeString(jsonl,
                "{\"prefix\": \"\", \"surface\": \"сүз\", \"analysis\": \"сүз+N+Sg+Nom;\"}\n"
                        + "{\"prefix\": \"\", \"surface\": \"бар\", \"analysis\": \"бар+N+Sg+Nom;бар+PN;\"}\n"
                        + "{\"prefix\": \"\", \"surface\": \"Рус\", \"analysis\": \"Rus\"}\n",
                StandardCharsets.UTF_8);

        JsonlTranslationAugmenter augmenter = new JsonlTranslationAugmenter();
        JsonlTranslationAugmenter.Report report = augmenter.augment(assets, dictionary);

        String content = Files.readString(jsonl, StandardCharsets.UTF_8);
        String[] lines = content.split("\\R");
        assertTrue(lines[0].contains("\"translations\": [\"слово\"]"));
        assertTrue(lines[1].contains("\"translations\": [\"есть\", \"каждый\"]")
                || lines[1].contains("\"translations\": [\"каждый\", \"есть\"]"));
        assertTrue(!lines[2].contains("\"translations\""));

        assertEquals(1, report.getFilesProcessed());
        assertEquals(3, report.getTokensProcessed());
        assertEquals(2, report.getTokensWithTranslations());
        assertEquals(3, report.getTranslationsWritten());

        String before = content;
        JsonlTranslationAugmenter.Report second = augmenter.augment(assets, dictionary);
        String after = Files.readString(jsonl, StandardCharsets.UTF_8);
        assertEquals(before, after);
        assertEquals(1, second.getFilesProcessed());
        assertEquals(3, second.getTokensProcessed());
        assertEquals(2, second.getTokensWithTranslations());
        assertEquals(3, second.getTranslationsWritten());
    }

    private void createDictionary(Path dbPath) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE tat_rus_dictionary (tat_lemma TEXT, rus_lemma TEXT)");
            statement.executeUpdate("INSERT INTO tat_rus_dictionary (tat_lemma, rus_lemma) VALUES\n"
                    + "('сүз', 'слово'),\n"
                    + "('бар', 'есть'),\n"
                    + "('бар', 'каждый')");
        }
    }
}
