package com.example.uqureader.webapp.cli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Command line tool that backfills {@code *.morph.tsv} files with naive suffix analyses for tokens
 * that remained unrecognised (marked as {@code NR}). The utility keeps the original file intact
 * and writes the amended content to a sibling file with the {@code .morph2.tsv} suffix.
 */
public final class NaiveMorphologyPostProcessor {

    private static final String INPUT_SUFFIX = ".morph.tsv";
    private static final String OUTPUT_SUFFIX = ".morph2.tsv";

    private final NaiveTatarSuffixAnalyzer analyzer;
    private final PrintStream out;
    private final PrintStream err;

    public NaiveMorphologyPostProcessor(NaiveTatarSuffixAnalyzer analyzer,
                                        PrintStream out,
                                        PrintStream err) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    public static void main(String[] args) {
        NaiveTatarSuffixAnalyzer analyzer =
                NaiveTatarSuffixAnalyzer.fromClasspathOrDefault("/suffixes_tat.json", 4);
        NaiveMorphologyPostProcessor processor =
                new NaiveMorphologyPostProcessor(analyzer, System.out, System.err);
        int exitCode = processor.run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        if (args == null || args.length == 0) {
            printUsage();
            return 1;
        }

        List<Path> files = new ArrayList<>(args.length);
        for (String arg : args) {
            Path path = Path.of(arg);
            if (!Files.exists(path)) {
                err.printf("Файл не найден: %s%n", path);
                return 2;
            }
            if (!Files.isRegularFile(path)) {
                err.printf("Не является файлом: %s%n", path);
                return 2;
            }
            files.add(path);
        }

        int failures = 0;
        for (Path file : files) {
            try {
                processFile(file);
            } catch (IOException ex) {
                failures++;
                err.printf("Не удалось обработать файл %s: %s%n", file, ex.getMessage());
            }
        }

        if (failures > 0) {
            err.printf("Завершено с ошибками (%d файлов не обработано).%n", failures);
            return 3;
        }
        return 0;
    }

    private void processFile(Path file) throws IOException {
        Path output = deriveOutputPath(file);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        int unknownCount = 0;
        int replacedCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String processed = line;
                int tabIndex = line.indexOf('\t');
                if (tabIndex >= 0) {
                    String token = line.substring(0, tabIndex);
                    String analysis = line.substring(tabIndex + 1);
                    if (requiresCompletion(analysis)) {
                        unknownCount++;
                        Replacement replacement = buildReplacement(token);
                        if (replacement.hasAnalyses()) {
                            replacedCount++;
                            logFoundWord(token, replacement);
                        }
                        processed = token + '\t' + replacement.text();
                    }
                }
                writer.write(processed);
                writer.newLine();
            }
        }

        out.printf("# %s — найдено неопределённых форм: %d, дополнено: %d. Результат: %s%n",
                file, unknownCount, replacedCount, output);
    }

    private Replacement buildReplacement(String token) {
        List<NaiveTatarSuffixAnalyzer.Analysis> analyses = analyzer.analyze(token);
        List<String> formatted = new ArrayList<>(analyses.size());
        for (NaiveTatarSuffixAnalyzer.Analysis analysis : analyses) {
            String notation = analysis.toHfstNotation();
            if (notation != null && !notation.isBlank()) {
                formatted.add(notation);
            }
        }
        if (formatted.isEmpty()) {
            return Replacement.noReplacement();
        }
        StringJoiner joiner = new StringJoiner(";");
        for (String entry : formatted) {
            joiner.add(entry);
        }
        String joined = joiner.toString();
        if (joined.isEmpty()) {
            return Replacement.noReplacement();
        }
        return Replacement.withAnalyses(joined + ';', formatted);
    }

    private void logFoundWord(String token, Replacement replacement) {
        out.printf("  найдено: %s -> %s%n", token, replacement.text());
    }

    private static boolean requiresCompletion(String analysis) {
        if (analysis == null) {
            return false;
        }
        String trimmed = analysis.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        return "NR".equalsIgnoreCase(trimmed) || "N".equals(trimmed);
    }

    private static Path deriveOutputPath(Path inputFile) {
        Path fileName = inputFile.getFileName();
        String candidate = fileName != null ? fileName.toString() : inputFile.toString();
        if (candidate.endsWith(INPUT_SUFFIX)) {
            String replaced = candidate.substring(0, candidate.length() - INPUT_SUFFIX.length()) + OUTPUT_SUFFIX;
            return inputFile.resolveSibling(replaced);
        }
        return inputFile.resolveSibling(candidate + OUTPUT_SUFFIX);
    }

    private void printUsage() {
        err.println("Использование: java -cp web-app-<версия>.jar "
                + "com.example.uqureader.webapp.cli.NaiveMorphologyPostProcessor <файл.morph.tsv> [<файл.morph.tsv> ...]");
        err.println("Для каждого указанного файла будет создан соседний <имя>.morph2.tsv "
                + "с наивными разборами вместо пометок NR.");
    }

    private static final class Replacement {

        private static final Replacement NONE = new Replacement("NR", List.of());

        private final String text;
        private final List<String> analyses;

        private Replacement(String text, List<String> analyses) {
            this.text = text;
            this.analyses = List.copyOf(analyses);
        }

        static Replacement noReplacement() {
            return NONE;
        }

        static Replacement withAnalyses(String text, List<String> analyses) {
            return new Replacement(text, analyses);
        }

        String text() {
            return text;
        }

        boolean hasAnalyses() {
            return !analyses.isEmpty();
        }
    }
}

