package com.example.uqureader.webapp.assets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Populates JSONL morphology files with Russian translations resolved from the tat-rus dictionary.
 */
public final class JsonlTranslationAugmenter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Result of an augmentation run.
     */
    public static final class Report {
        private final int filesProcessed;
        private final int tokensProcessed;
        private final int tokensWithTranslations;
        private final int translationsWritten;

        Report(int filesProcessed, int tokensProcessed, int tokensWithTranslations, int translationsWritten) {
            this.filesProcessed = filesProcessed;
            this.tokensProcessed = tokensProcessed;
            this.tokensWithTranslations = tokensWithTranslations;
            this.translationsWritten = translationsWritten;
        }

        public int getFilesProcessed() {
            return filesProcessed;
        }

        public int getTokensProcessed() {
            return tokensProcessed;
        }

        public int getTokensWithTranslations() {
            return tokensWithTranslations;
        }

        public int getTranslationsWritten() {
            return translationsWritten;
        }
    }

    /**
     * Updates every <code>*.jsonl</code> file in the provided directory by appending a {@code translations}
     * property that contains candidate Russian lemmas for the token.
     *
     * @param assetsDirectory directory with JSONL files
     * @param dictionaryFile  SQLite dictionary file produced by {@link
     *                        com.example.uqureader.webapp.dictionary.TatRusDictionaryImporter}
     * @return statistics about the augmentation
     * @throws IOException  when files cannot be read or written
     * @throws SQLException when the dictionary cannot be queried
     */
    public Report augment(Path assetsDirectory, Path dictionaryFile) throws IOException, SQLException {
        Objects.requireNonNull(assetsDirectory, "assetsDirectory");
        Objects.requireNonNull(dictionaryFile, "dictionaryFile");

        if (!Files.isDirectory(assetsDirectory)) {
            throw new IOException("Assets directory not found: " + assetsDirectory.toAbsolutePath());
        }
        if (!Files.exists(dictionaryFile)) {
            throw new IOException("Dictionary file not found: " + dictionaryFile.toAbsolutePath());
        }

        int filesProcessed = 0;
        int tokensProcessed = 0;
        int tokensWithTranslations = 0;
        int translationsWritten = 0;

        Map<String, List<String>> cache = new HashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dictionaryFile.toAbsolutePath());
             PreparedStatement lookup = connection.prepareStatement(
                     "SELECT DISTINCT rus_lemma FROM tat_rus_dictionary "
                             + "WHERE tat_lemma = ? COLLATE NOCASE ORDER BY rus_lemma COLLATE NOCASE")) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(assetsDirectory, "*.jsonl")) {
                for (Path file : stream) {
                    FileReport report = augmentFile(file, lookup, cache);
                    if (report.tokensProcessed > 0) {
                        filesProcessed++;
                        tokensProcessed += report.tokensProcessed;
                        tokensWithTranslations += report.tokensWithTranslations;
                        translationsWritten += report.translationsWritten;
                    }
                }
            }
        }

        return new Report(filesProcessed, tokensProcessed, tokensWithTranslations, translationsWritten);
    }

    private FileReport augmentFile(Path file,
                                   PreparedStatement lookup,
                                   Map<String, List<String>> cache) throws IOException, SQLException {
        List<String> output = new ArrayList<>();
        boolean modified = false;
        int tokensProcessed = 0;
        int tokensWithTranslations = 0;
        int translationsWritten = 0;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    output.add(line);
                    continue;
                }
                JsonObject object = GSON.fromJson(line, JsonObject.class);
                tokensProcessed++;
                List<String> translations = resolveTranslations(object, lookup, cache);
                if (translations.isEmpty()) {
                    if (object.has("translations")) {
                        object.remove("translations");
                    }
                } else {
                    JsonArray existingArray = object.has("translations") && object.get("translations").isJsonArray()
                            ? object.getAsJsonArray("translations")
                            : null;
                    if (!matches(existingArray, translations)) {
                        JsonArray array = new JsonArray();
                        for (String translation : translations) {
                            array.add(translation);
                        }
                        object.remove("translations");
                        object.add("translations", array);
                    }
                    tokensWithTranslations++;
                    translationsWritten += translations.size();
                }
                String serialised = toJsonLine(object);
                if (!serialised.equals(line)) {
                    modified = true;
                }
                output.add(serialised);
            }
        }

        if (modified) {
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (int i = 0; i < output.size(); i++) {
                    if (i > 0) {
                        writer.newLine();
                    }
                    writer.write(output.get(i));
                }
            }
        }

        return new FileReport(tokensProcessed, tokensWithTranslations, translationsWritten);
    }

    private List<String> resolveTranslations(JsonObject object,
                                             PreparedStatement lookup,
                                             Map<String, List<String>> cache) throws SQLException {
        if (object == null || !object.has("analysis")) {
            return Collections.emptyList();
        }
        JsonElement element = object.get("analysis");
        if (!element.isJsonPrimitive()) {
            return Collections.emptyList();
        }
        String analysis = element.getAsString();
        if (analysis == null || analysis.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> translations = new LinkedHashSet<>();
        for (String lemma : extractCandidateLemmas(analysis)) {
            List<String> values = lookupTranslations(lemma, lookup, cache);
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    translations.add(value);
                }
            }
        }
        return new ArrayList<>(translations);
    }

    private List<String> extractCandidateLemmas(String analysis) {
        if (analysis == null || analysis.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = analysis.split(";");
        Set<String> lemmas = new LinkedHashSet<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int plus = trimmed.indexOf('+');
            if (plus <= 0) {
                continue;
            }
            String lemma = trimmed.substring(0, plus).trim();
            if (lemma.isEmpty()) {
                continue;
            }
            String normalised = Normalizer.normalize(lemma, Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT);
            lemmas.add(normalised);
        }
        return new ArrayList<>(lemmas);
    }

    private List<String> lookupTranslations(String lemma,
                                             PreparedStatement lookup,
                                             Map<String, List<String>> cache) throws SQLException {
        if (lemma == null || lemma.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> cached = cache.get(lemma);
        if (cached != null) {
            return cached;
        }
        List<String> results = new ArrayList<>();
        lookup.setString(1, lemma);
        try (ResultSet rs = lookup.executeQuery()) {
            while (rs.next()) {
                String value = rs.getString(1);
                if (value != null) {
                    results.add(value);
                }
            }
        }
        List<String> unmodifiable = Collections.unmodifiableList(results);
        cache.put(lemma, unmodifiable);
        return unmodifiable;
    }

    private boolean matches(JsonArray existing, List<String> translations) {
        if (existing == null) {
            return false;
        }
        if (existing.size() != translations.size()) {
            return false;
        }
        for (int i = 0; i < translations.size(); i++) {
            JsonElement element = existing.get(i);
            if (!element.isJsonPrimitive()) {
                return false;
            }
            String value = element.getAsString();
            if (!Objects.equals(value, translations.get(i))) {
                return false;
            }
        }
        return true;
    }

    private String toJsonLine(JsonObject object) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(GSON.toJson(entry.getKey()));
            builder.append(": ");
            JsonElement value = entry.getValue();
            if (value.isJsonArray() && "translations".equals(entry.getKey())) {
                builder.append(formatArray(value.getAsJsonArray()));
            } else {
                builder.append(GSON.toJson(value));
            }
        }
        builder.append('}');
        return builder.toString();
    }

    private String formatArray(JsonArray array) {
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        for (int i = 0; i < array.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(GSON.toJson(array.get(i)));
        }
        builder.append(']');
        return builder.toString();
    }

    private static final class FileReport {
        final int tokensProcessed;
        final int tokensWithTranslations;
        final int translationsWritten;

        FileReport(int tokensProcessed, int tokensWithTranslations, int translationsWritten) {
            this.tokensProcessed = tokensProcessed;
            this.tokensWithTranslations = tokensWithTranslations;
            this.translationsWritten = translationsWritten;
        }
    }
}
