package com.example.uqureader.webapp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Morph2TranslationAugmenterTest {

    private ByteArrayOutputStream outBuffer;
    private ByteArrayOutputStream errBuffer;

    @BeforeEach
    void setUp() {
        outBuffer = new ByteArrayOutputStream();
        errBuffer = new ByteArrayOutputStream();
    }

    @AfterEach
    void tearDown() {
        outBuffer.reset();
        errBuffer.reset();
    }

    @Test
    void runUsesSqliteDictionary() throws Exception {
        Path tempDir = Files.createTempDirectory("morph2-augmenter-");
        try {
            Path dictionary = tempDir.resolve("dict.db");
            Path morph2 = tempDir.resolve("sample.txt.morph2.tsv");
            Files.writeString(morph2, "сүз\tсүз+N+Sg+Nom;\n", StandardCharsets.UTF_8);

            createDictionary(dictionary);

            Morph2TranslationAugmenter augmenter = new Morph2TranslationAugmenter(
                    new PrintStream(outBuffer, true, StandardCharsets.UTF_8),
                    new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

            int exitCode = augmenter.run(new String[]{
                    "--dictionary", dictionary.toString(),
                    morph2.toString()
            });

            assertEquals(0, exitCode, "Дополнение переводами должно завершаться успешно");

            Path morph3 = tempDir.resolve("sample.txt.morph3.tsv");
            assertTrue(Files.exists(morph3), "Ожидается создание .morph3 файла");

            String content = Files.readString(morph3, StandardCharsets.UTF_8);
            assertTrue(content.contains("слово"), "Перевод из словаря должен появиться в выходных данных");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private void createDictionary(Path dictionary) throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
        String jdbcUrl = "jdbc:sqlite:" + dictionary.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tat_rus_dictionary (tat_lemma TEXT, rus_lemma TEXT, tat_tags TEXT)");
            statement.execute("INSERT INTO tat_rus_dictionary (tat_lemma, rus_lemma, tat_tags)"
                    + " VALUES ('сүз', 'слово', '[\"n\"]')");
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup for temporary test files
                }
            });
        } catch (IOException ignored) {
            // swallow cleanup errors in tests
        }
    }
}
