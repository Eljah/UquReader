package com.example.uqureader.webapp.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Command line tool that enriches {@code *.morph2.tsv} files with Russian translations resolved
 * from the bundled Tat-Rus dictionary. The program analyses the candidate lemmas extracted from the
 * HFST-style analyses and writes the translations as the third column of the TSV file. When several
 * parts of speech are possible for the same lemma, the translations are filtered so that they match
 * the detected part of speech, falling back to all dictionary entries if no matches are found.
 */
public final class Morph2TranslationAugmenter {

    private static final String DEFAULT_DICTIONARY = "data/tat_rus_dictionary.db";

    private final PrintStream out;
    private final PrintStream err;

    public Morph2TranslationAugmenter(PrintStream out, PrintStream err) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    public static void main(String[] args) {
        Morph2TranslationAugmenter augmenter = new Morph2TranslationAugmenter(System.out, System.err);
        int exitCode = augmenter.run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        if (args == null || args.length == 0) {
            printUsage();
            return 1;
        }

        Path dictionary = Path.of(DEFAULT_DICTIONARY);
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--dictionary".equals(arg) || "-d".equals(arg)) {
                if (i + 1 >= args.length) {
                    err.println("Опция --dictionary требует путь к базе словаря.");
                    return 1;
                }
                dictionary = Path.of(args[++i]);
                continue;
            }
            Path file = Path.of(arg);
            if (!Files.exists(file)) {
                err.printf("Файл не найден: %s%n", file);
                return 2;
            }
            if (!Files.isRegularFile(file)) {
                err.printf("Не является файлом: %s%n", file);
                return 2;
            }
            files.add(file);
        }

        if (files.isEmpty()) {
            err.println("Не указаны файлы для обработки.");
            printUsage();
            return 1;
        }
        if (!Files.exists(dictionary)) {
            err.printf("Файл словаря не найден: %s%n", dictionary);
            return 2;
        }

        String jdbcUrl = "jdbc:sqlite:" + dictionary.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement lookup = connection.prepareStatement(
                     "SELECT rus_lemma, tat_tags FROM tat_rus_dictionary "
                             + "WHERE tat_lemma = ? COLLATE NOCASE")) {
            Map<String, List<DictionaryEntry>> dictionaryCache = new HashMap<>();
            int failures = 0;
            for (Path file : files) {
                try {
                    processFile(file, lookup, dictionaryCache);
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
        } catch (SQLException ex) {
            err.printf("Не удалось подключиться к базе словаря: %s%n", ex.getMessage());
            return 4;
        }
    }

    private void processFile(Path file,
                             PreparedStatement lookup,
                             Map<String, List<DictionaryEntry>> dictionaryCache) throws IOException {
        List<String> lines = new ArrayList<>();
        int tokens = 0;
        int tokensWithTranslations = 0;
        int translationsWritten = 0;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                tokens++;
                AugmentedLine augmented;
                try {
                    augmented = augmentLine(line, lookup, dictionaryCache);
                } catch (SQLException ex) {
                    throw new IOException("Ошибка обращения к словарю: " + ex.getMessage(), ex);
                }
                if (augmented.translationCount() > 0) {
                    tokensWithTranslations++;
                    translationsWritten += augmented.translationCount();
                }
                lines.add(augmented.value());
            }
        }

        Path output = deriveOutputPath(file);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    writer.newLine();
                }
                writer.write(lines.get(i));
            }
        }

        out.printf("# %s → %s — обработано токенов: %d, с переводами: %d, записано переводов: %d%n",
                file,
                output,
                tokens,
                tokensWithTranslations,
                translationsWritten);
    }

    private AugmentedLine augmentLine(String line,
                                      PreparedStatement lookup,
                                      Map<String, List<DictionaryEntry>> dictionaryCache) throws SQLException {
        if (line == null || line.isEmpty()) {
            return new AugmentedLine(line == null ? "" : line, 0);
        }
        String[] columns = line.split("\t", -1);
        if (columns.length < 2) {
            return new AugmentedLine(line, 0);
        }
        String token = columns[0];
        String analysis = columns[1];

        List<LemmaCandidate> candidates = extractCandidates(analysis);
        if (candidates.isEmpty()) {
            String value = token + '\t' + analysis;
            return new AugmentedLine(value, 0);
        }

        List<String> formatted = new ArrayList<>();
        int translationCount = 0;
        for (LemmaCandidate candidate : candidates) {
            List<DictionaryEntry> entries = lookupDictionaryEntries(candidate.lookupLemma(), lookup, dictionaryCache);
            List<String> translations = selectTranslations(entries, candidate.pos());
            if (translations.isEmpty()) {
                continue;
            }
            translationCount += translations.size();
            StringBuilder builder = new StringBuilder();
            builder.append(candidate.displayLemma());
            if (candidate.posDisplay() != null) {
                builder.append('[').append(candidate.posDisplay()).append(']');
            }
            builder.append(':').append(' ');
            for (int i = 0; i < translations.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(translations.get(i));
            }
            formatted.add(builder.toString());
        }

        String value;
        if (formatted.isEmpty()) {
            value = token + '\t' + analysis;
        } else {
            value = token + '\t' + analysis + '\t' + String.join(" | ", formatted);
        }
        return new AugmentedLine(value, translationCount);
    }

    private List<LemmaCandidate> extractCandidates(String analysis) {
        if (analysis == null) {
            return Collections.emptyList();
        }
        String trimmed = analysis.strip();
        if (trimmed.isEmpty() || "NR".equalsIgnoreCase(trimmed) || "N".equals(trimmed) || "Error".equalsIgnoreCase(trimmed)) {
            return Collections.emptyList();
        }

        Map<String, LemmaCandidate> ordered = new LinkedHashMap<>();
        String[] analyses = analysis.split(";");
        for (String entry : analyses) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            LemmaCandidate candidate = parseCandidate(e);
            if (candidate == null) {
                continue;
            }
            ordered.putIfAbsent(candidate.cacheKey(), candidate);
        }
        if (ordered.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(ordered.values());
    }

    private LemmaCandidate parseCandidate(String entry) {
        String[] parts = entry.split("\\+");
        if (parts.length == 0) {
            return null;
        }
        String lemma = parts[0].trim();
        if (lemma.isEmpty()) {
            return null;
        }
        String lookup = Normalizer.normalize(lemma, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String posDisplay = null;
        String pos = null;
        for (int i = 1; i < parts.length; i++) {
            String tag = parts[i];
            if (tag == null || tag.isEmpty()) {
                continue;
            }
            String normalised = tag.trim();
            int idx = normalised.indexOf('(');
            if (idx > 0) {
                normalised = normalised.substring(0, idx);
            }
            if (normalised.isEmpty()) {
                continue;
            }
            if (isPartOfSpeechTag(normalised)) {
                posDisplay = normalised;
                pos = normalised.toUpperCase(Locale.ROOT);
                break;
            }
        }
        return new LemmaCandidate(lemma, lookup, posDisplay, pos);
    }

    private boolean isPartOfSpeechTag(String tag) {
        return PART_OF_SPEECH_TAGS.contains(tag.toUpperCase(Locale.ROOT));
    }

    private List<DictionaryEntry> lookupDictionaryEntries(String lemma,
                                                          PreparedStatement lookup,
                                                          Map<String, List<DictionaryEntry>> dictionaryCache) throws SQLException {
        List<DictionaryEntry> cached = dictionaryCache.get(lemma);
        if (cached != null) {
            return cached;
        }
        List<DictionaryEntry> entries = new ArrayList<>();
        lookup.setString(1, lemma);
        try (ResultSet rs = lookup.executeQuery()) {
            while (rs.next()) {
                String translation = rs.getString(1);
                String tagsJson = rs.getString(2);
                Set<String> tags = parseTags(tagsJson);
                entries.add(new DictionaryEntry(translation, tags));
            }
        }
        List<DictionaryEntry> unmodifiable = Collections.unmodifiableList(entries);
        dictionaryCache.put(lemma, unmodifiable);
        return unmodifiable;
    }

    private Set<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptySet();
        }
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonArray()) {
            return Collections.emptySet();
        }
        JsonArray array = element.getAsJsonArray();
        Set<String> tags = new LinkedHashSet<>();
        for (JsonElement value : array) {
            if (!value.isJsonPrimitive()) {
                continue;
            }
            String tag = value.getAsString();
            if (tag == null) {
                continue;
            }
            String lower = tag.trim().toLowerCase(Locale.ROOT);
            if (!lower.isEmpty()) {
                tags.add(lower);
            }
        }
        return tags;
    }

    private List<String> selectTranslations(List<DictionaryEntry> entries, String pos) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> all = new LinkedHashSet<>();
        LinkedHashSet<String> filtered = new LinkedHashSet<>();
        for (DictionaryEntry entry : entries) {
            String translation = entry.translation();
            if (translation == null) {
                continue;
            }
            String trimmed = translation.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            all.add(trimmed);
            if (pos != null && matchesPos(pos, entry.tags())) {
                filtered.add(trimmed);
            }
        }
        if (pos == null) {
            return new ArrayList<>(all);
        }
        if (!filtered.isEmpty()) {
            return new ArrayList<>(filtered);
        }
        boolean anyTagged = entries.stream().anyMatch(entry -> entry.tags() != null && !entry.tags().isEmpty());
        if (!anyTagged) {
            return new ArrayList<>(all);
        }
        return Collections.emptyList();
    }

    private boolean matchesPos(String pos, Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        List<String> equivalents = POS_EQUIVALENTS.get(pos);
        if (equivalents == null || equivalents.isEmpty()) {
            return true;
        }
        for (String expected : equivalents) {
            if (matchesExpected(tags, expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesExpected(Set<String> tags, String expected) {
        boolean wildcard = expected.endsWith("*");
        String probe = wildcard ? expected.substring(0, expected.length() - 1) : expected;
        for (String tag : tags) {
            if (wildcard) {
                if (tag.startsWith(probe)) {
                    return true;
                }
            } else if (tag.equals(probe)) {
                return true;
            }
        }
        return false;
    }

    private void printUsage() {
        err.println("Использование: java -cp web-app-<версия>.jar "
                + "com.example.uqureader.webapp.cli.Morph2TranslationAugmenter [--dictionary <путь к БД>] <файл.morph2.tsv> [<файл.morph2.tsv> ...]");
        err.println("Каждый указанный файл будет сохранён в новый *.morph3 файл с переводами для обнаруженных основ.");
    }

    private record AugmentedLine(String value, int translationCount) {
    }

    private record LemmaCandidate(String displayLemma,
                                  String lookupLemma,
                                  String posDisplay,
                                  String pos) {
        String cacheKey() {
            return lookupLemma + '|' + (pos == null ? "" : pos);
        }
    }

    private record DictionaryEntry(String translation, Set<String> tags) {
    }

    private static final Set<String> PART_OF_SPEECH_TAGS = Set.of(
            "N",
            "V",
            "ADJ",
            "ADV",
            "NUM",
            "PN",
            "PART",
            "PCL",
            "POST",
            "POSTP",
            "PROP",
            "CNJ",
            "CONJ",
            "MOD",
            "INTRJ",
            "INTERJ",
            "DET",
            "AUX",
            "PRON"
    );

    private static final Map<String, List<String>> POS_EQUIVALENTS;

    static {
        Map<String, List<String>> map = new HashMap<>();
        map.put("N", List.of("n", "np"));
        map.put("V", List.of("v"));
        map.put("ADJ", List.of("adj*"));
        map.put("ADV", List.of("adv*"));
        map.put("NUM", List.of("num*"));
        map.put("PN", List.of("prn*"));
        map.put("PRON", List.of("prn*"));
        map.put("PART", List.of("part*", "pcl*"));
        map.put("PCL", List.of("part*", "pcl*"));
        map.put("POST", List.of("post*"));
        map.put("POSTP", List.of("post*"));
        map.put("PROP", List.of("np*"));
        map.put("CNJ", List.of("cnj*"));
        map.put("CONJ", List.of("cnj*"));
        map.put("MOD", List.of("mod*", "adv*"));
        map.put("INTRJ", List.of("ij*"));
        map.put("INTERJ", List.of("ij*"));
        map.put("DET", List.of("det*"));
        map.put("AUX", List.of("aux*", "vbser"));
        POS_EQUIVALENTS = Collections.unmodifiableMap(map);
    }

    private Path deriveOutputPath(Path input) {
        String fileName = input.getFileName().toString();
        int idx = fileName.lastIndexOf(".morph2");
        if (idx >= 0) {
            int end = idx + ".morph2".length();
            String suffix = fileName.substring(end);
            String base = fileName.substring(0, idx);
            return input.resolveSibling(base + ".morph3" + suffix);
        }
        return input.resolveSibling(fileName + ".morph3");
    }
}

