package com.example.uqureader.webapp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Morph3Fb2ExporterTest {

    private static final Path MORPH_FILE = Path.of("src/main/resources/markup/qubiz_qabiz.txt.morph3.tsv");
    private static final Path ORIGINAL_FILE = Path.of("src/main/resources/markup/qubiz_qabiz.txt");
    private static final Path OUTPUT_FILE = MORPH_FILE.getParent().resolve("Qubiz qabiz.fb2");

    private ByteArrayOutputStream outBuffer;
    private ByteArrayOutputStream errBuffer;

    @BeforeEach
    void deleteOutputBefore() throws Exception {
        Files.deleteIfExists(OUTPUT_FILE);
        outBuffer = new ByteArrayOutputStream();
        errBuffer = new ByteArrayOutputStream();
    }

    @AfterEach
    void deleteOutputAfter() throws Exception {
        Files.deleteIfExists(OUTPUT_FILE);
    }

    @Test
    void runWithExplicitMorphAndOriginal() throws Exception {
        Morph3Fb2Exporter exporter = new Morph3Fb2Exporter(
                new PrintStream(outBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        int exitCode = exporter.run(new String[]{
                MORPH_FILE.toString(),
                ORIGINAL_FILE.toString()
        });

        assertEquals(0, exitCode, "Экспорт должен завершиться без ошибок");
        assertTrue(Files.exists(OUTPUT_FILE), "Должен быть создан fb2-файл");
        assertTrue(Files.size(OUTPUT_FILE) > 0, "Сгенерированный fb2-файл не должен быть пустым");
        assertInlineWordMarkup();
    }

    @Test
    void runWithOriginalOnly() throws Exception {
        Morph3Fb2Exporter exporter = new Morph3Fb2Exporter(
                new PrintStream(outBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        int exitCode = exporter.run(new String[]{ORIGINAL_FILE.toString()});

        assertEquals(0, exitCode, "Экспорт должен завершиться без ошибок");
        assertTrue(Files.exists(OUTPUT_FILE), "Должен быть создан fb2-файл");
        assertTrue(Files.size(OUTPUT_FILE) > 0, "Сгенерированный fb2-файл не должен быть пустым");
        assertInlineWordMarkup();
    }

    private void assertInlineWordMarkup() throws Exception {
        String content = Files.readString(OUTPUT_FILE, StandardCharsets.UTF_8);
        assertTrue(content.contains("<style name=\"morph\" m:surface=\"КУБЫЗ\">КУБЫЗ</style>"),
                "Слова должны оставаться внутри инлайновой разметки");
        assertFalse(content.contains("<m:w"),
                "Самостоятельные элементы m:w больше не должны использоваться, чтобы не провоцировать переносы строк");
    }
}
