package com.example.uqureader.webapp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

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

    @Test
    void normalisesDuplicateCommaAndEllipsis() throws Exception {
        Path tempDir = Files.createTempDirectory("morph3-exporter-");
        Path morphFile = tempDir.resolve("sample.txt.morph3.tsv");
        Path originalFile = tempDir.resolve("sample.txt");
        Path outputFile = tempDir.resolve("Sample.fb2");

        try {
            String originalText = "Сүз,, сүз...";
            Files.writeString(originalFile, originalText, StandardCharsets.UTF_8);

            String morphContent = String.join("\n",
                    "Сүз\tсүз+N+Sg+Nom;",
                    ",\tType2",
                    ",\tType2",
                    "сүз\tсүз+N+Sg+Nom;",
                    ".\tType1",
                    ".\tType1",
                    ".\tType1");
            Files.writeString(morphFile, morphContent, StandardCharsets.UTF_8);

            Morph3Fb2Exporter exporter = new Morph3Fb2Exporter(
                    new PrintStream(outBuffer, true, StandardCharsets.UTF_8),
                    new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

            int exitCode = exporter.run(new String[]{morphFile.toString(), originalFile.toString()});

            assertEquals(0, exitCode, "Экспорт должен завершиться без ошибок");
            assertTrue(Files.exists(outputFile), "Должен быть создан fb2-файл с нормализованной пунктуацией");

            String content = Files.readString(outputFile, StandardCharsets.UTF_8);
            assertFalse(content.contains(",,"), "Двойные запятые должны заменяться одиночной");
            assertTrue(content.contains("…"), "Троеточие должно превращаться в единый символ");
            assertTrue(content.contains("m:surface=\",\""), "Запятая должна остаться в разметке");
            assertTrue(content.contains("m:surface=\"сүз…\""), "Троеточие должно попасть в разметку с символом … внутри поверхности слова");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private void assertInlineWordMarkup() throws Exception {
        String content = Files.readString(OUTPUT_FILE, StandardCharsets.UTF_8);
        assertTrue(content.contains("<style name=\"morph\" m:analysis=\"кубыз+N+Sg+Nom;\" m:surface=\"КУБЫЗ\">КУБЫЗ</style>"),
                "Слова должны оставаться внутри инлайновой разметки");
        assertFalse(content.contains("<m:w"),
                "Самостоятельные элементы m:w больше не должны использоваться, чтобы не провоцировать переносы строк");
    }

    private void deleteRecursively(Path dir) throws Exception {
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
        }
    }
}
