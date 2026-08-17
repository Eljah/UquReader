package com.example.uqureader.webapp.reader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ReaderWorkCatalog {
    private static final int DEFAULT_PAGE_SIZE = 450;
    private static final Map<String, String> KNOWN_TITLES = Map.of(
            "elnet_puncheryshte", "Элнет пӱнчерыште"
    );

    private final Map<String, ReaderWork> works;

    private ReaderWorkCatalog(Map<String, ReaderWork> works) {
        this.works = Map.copyOf(works);
    }

    public static ReaderWorkCatalog loadDefault() throws IOException {
        Path assetsDir = resolveAssetsDir();
        Map<String, ReaderWork> loaded = new LinkedHashMap<>();
        if (Files.isDirectory(assetsDir)) {
            try (var stream = Files.list(assetsDir)) {
                List<Path> files = stream
                        .filter(path -> path.getFileName().toString().endsWith(".ttmorph.jsonl"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
                for (Path file : files) {
                    ReaderWork work = readJsonl(file);
                    loaded.put(work.id, work);
                }
            }
        }
        return new ReaderWorkCatalog(loaded);
    }

    public List<ReaderWork> listWorks() {
        return works.values().stream()
                .sorted(Comparator.comparing(work -> work.title.toLowerCase(Locale.ROOT)))
                .toList();
    }

    public Optional<ReaderWork> find(String id) {
        return Optional.ofNullable(works.get(id));
    }

    public List<ReaderToken> page(String workId, int pageIndex, int pageSize) {
        ReaderWork work = works.get(workId);
        if (work == null) {
            return List.of();
        }
        int safeSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(2_000, pageSize);
        int safePage = Math.max(0, pageIndex);
        int start = safePage * safeSize;
        if (start >= work.tokens.size()) {
            return List.of();
        }
        int end = Math.min(work.tokens.size(), start + safeSize);
        return work.tokens.subList(start, end);
    }

    private static Path resolveAssetsDir() {
        String override = System.getenv("UQUREADER_ASSETS_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path androidAssets = Path.of("android-app", "src", "main", "assets");
        if (Files.isDirectory(androidAssets)) {
            return androidAssets;
        }
        Path siblingAndroidAssets = Path.of("..", "android-app", "src", "main", "assets");
        if (Files.isDirectory(siblingAndroidAssets)) {
            return siblingAndroidAssets;
        }
        return Path.of("src", "main", "resources", "assets");
    }

    private static ReaderWork readJsonl(Path path) throws IOException {
        Gson gson = new Gson();
        List<ReaderToken> tokens = new ArrayList<>();
        int cursor = 0;
        try (InputStream input = Files.newInputStream(path);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
             BufferedReader buffered = new BufferedReader(reader)) {
            String line;
            while ((line = buffered.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject object = gson.fromJson(line, JsonObject.class);
                String prefix = getString(object, "prefix");
                String surface = getString(object, "surface");
                String analysis = getString(object, "analysis");
                List<ReaderAnalysisVariant> analyses = getAnalyses(object, surface);
                List<String> translations = getStringList(object, "translations");
                cursor += prefix.length();
                int start = cursor;
                cursor += surface.length();
                MorphologyData morphology = primaryMorphology(surface, analysis, analyses);
                tokens.add(new ReaderToken(tokens.size(), start, cursor, prefix, surface, analysis,
                        morphology, analyses, translations));
            }
        }
        String fileName = path.getFileName().toString();
        String id = normalizeId(fileName.replace(".ttmorph.jsonl", ""));
        return new ReaderWork(id, prettifyTitle(id), fileName, tokens, cursor);
    }

    private static String getString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static List<String> getStringList(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        JsonArray array = value.getAsJsonArray();
        for (JsonElement element : array) {
            if (element != null && !element.isJsonNull()) {
                String text = element.getAsString().trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private static List<ReaderAnalysisVariant> getAnalyses(JsonObject object, String surface) {
        JsonElement value = object.get("analyses");
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<ReaderAnalysisVariant> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject variant = element.getAsJsonObject();
            String analysis = getString(variant, "analysis");
            String lemma = getString(variant, "lemma");
            List<String> segments = getStringList(variant, "segments");
            List<String> gloss = getStringList(variant, "gloss");
            List<String> pos = getStringList(variant, "pos");
            List<String> translations = getStringList(variant, "translations");
            List<String> posDescriptions = pos.stream()
                    .map(ViennaGrammarCatalog::describePos)
                    .filter(description -> !description.isBlank())
                    .toList();
            List<String> glossDescriptions = gloss.stream()
                    .skip(1)
                    .map(ViennaGrammarCatalog::describeFeatureGloss)
                    .filter(description -> !description.isBlank())
                    .toList();
            List<ReaderAnalysisFeatureRow> featureRows = buildFeatureRows(segments, gloss, pos);
            MorphologyData morphology = buildViennaMorphology(lemma, analysis, segments, gloss, pos);
            result.add(new ReaderAnalysisVariant(analysis, lemma, segments, gloss, pos, translations,
                    posDescriptions, glossDescriptions, featureRows, morphology));
        }
        return result;
    }

    private static MorphologyData primaryMorphology(String surface, String analysis,
                                                    List<ReaderAnalysisVariant> analyses) {
        if (analyses != null && !analyses.isEmpty() && analyses.get(0).morphology != null) {
            return analyses.get(0).morphology;
        }
        return WebMorphologyParser.parse(surface, analysis);
    }

    private static MorphologyData buildViennaMorphology(String lemma, String analysis, List<String> segments,
                                                        List<String> gloss, List<String> pos) {
        String primaryPos = firstNonEmpty(pos);
        List<MorphFeatureData> features = new ArrayList<>();
        int max = Math.max(segments.size(), Math.max(gloss.size(), pos.size()));
        for (int index = 1; index < max; index++) {
            String code = firstNonEmpty(getAt(pos, index), getAt(gloss, index));
            String canonical = getAt(gloss, index);
            String actual = getAt(segments, index);
            if (!code.isBlank() || !canonical.isBlank() || !actual.isBlank()) {
                features.add(new MorphFeatureData(code, canonical, actual));
            }
        }
        String featureKey = buildViennaFeatureKey(primaryPos, features);
        return new MorphologyData(lemma, primaryPos, features, segments, featureKey, analysis);
    }

    private static String buildViennaFeatureKey(String pos, List<MorphFeatureData> features) {
        StringBuilder builder = new StringBuilder(pos == null ? "" : pos);
        for (MorphFeatureData feature : features) {
            if (feature.code == null || feature.code.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('+');
            }
            builder.append(feature.code);
        }
        return builder.toString();
    }

    private static String firstNonEmpty(List<String> values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static List<ReaderAnalysisFeatureRow> buildFeatureRows(List<String> segments, List<String> gloss,
                                                                   List<String> pos) {
        int max = Math.max(segments.size(), Math.max(gloss.size(), pos.size()));
        if (max == 0) {
            return List.of();
        }
        List<ReaderAnalysisFeatureRow> rows = new ArrayList<>();
        for (int index = 0; index < max; index++) {
            String segment = getAt(segments, index);
            String posCode = getAt(pos, index);
            String glossCode = getAt(gloss, index);
            List<String> descriptions = new ArrayList<>();
            String posDescription = ViennaGrammarCatalog.describePos(posCode);
            if (!posDescription.isBlank()) {
                descriptions.add(posDescription);
            }
            String glossDescription = ViennaGrammarCatalog.describeFeatureGloss(glossCode);
            if (!glossDescription.isBlank()) {
                descriptions.add(glossDescription);
            }
            rows.add(new ReaderAnalysisFeatureRow(segment, posCode, glossCode, descriptions));
        }
        return rows;
    }

    private static String getAt(List<String> values, int index) {
        return index >= 0 && index < values.size() ? values.get(index) : "";
    }

    private static String normalizeId(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String prettifyTitle(String id) {
        String known = KNOWN_TITLES.get(id);
        if (known != null) {
            return known;
        }
        if (id.endsWith("_vienna")) {
            String base = id.substring(0, id.length() - "_vienna".length());
            String baseTitle = KNOWN_TITLES.get(base);
            if (baseTitle != null) {
                return baseTitle + " · Vienna";
            }
        }
        String[] parts = id.replace('_', ' ').split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toTitleCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.length() == 0 ? id : builder.toString();
    }
}
