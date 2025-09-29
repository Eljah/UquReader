package com.example.uqureader.webapp;

import com.example.uqureader.webapp.morphology.MorphologyAnalyzer;
import com.example.uqureader.webapp.morphology.MorphologyAnalyzer.TextAnalysis;
import com.example.uqureader.webapp.morphology.MorphologyAnalyzer.TokenEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.Closeable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Pure Java implementation of the morphology service that mirrors the structure of the
 * original Python library but operates entirely on pre-calculated lexical data bundled with the
 * application resources.
 */
public class MorphologyService implements Closeable {

    private static final String VERSION = "1.2.10-java";

    private final MorphologyAnalyzer analyzer;
    private final ConcurrentMap<String, JsonObject> tokenCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, JsonObject> textCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> markupCache = new ConcurrentHashMap<>();

    public MorphologyService() {
        this(MorphologyAnalyzer.loadDefault());
    }

    MorphologyService(MorphologyAnalyzer analyzer) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
    }

    public String getVersion() {
        return VERSION;
    }

    public JsonObject analyzeToken(String token) {
        String key = token == null ? "" : token;
        return tokenCache.computeIfAbsent(key, this::computeTokenAnalysis).deepCopy();
    }

    public JsonObject analyzeText(String text) {
        String key = text == null ? "" : text;
        return textCache.computeIfAbsent(key, this::computeTextAnalysis).deepCopy();
    }

    public String markup(String text) {
        String key = text == null ? "" : text;
        return markupCache.computeIfAbsent(key, this::computeMarkup);
    }

    private JsonObject computeTokenAnalysis(String token) {
        String analysis = analyzer.analyseToken(token);
        JsonObject payload = new JsonObject();
        payload.addProperty("token", token);
        payload.addProperty("tag", analysis);
        payload.addProperty("morphan_version", VERSION);
        payload.addProperty("format", 1);
        return payload;
    }

    private JsonObject computeTextAnalysis(String text) {
        TextAnalysis analysis = analyzer.analyze(text);
        JsonObject payload = new JsonObject();
        payload.addProperty("tokens_count", analysis.tokensCount());
        payload.addProperty("unique_tokens_count", analysis.uniqueTokensCount());
        payload.addProperty("sentenes_count", analysis.sentencesCount());
        payload.addProperty("morphan_version", VERSION);
        payload.addProperty("format", 1);

        JsonArray sentences = new JsonArray();
        for (List<TokenEntry> sentence : analysis.sentences()) {
            JsonArray sentenceArray = new JsonArray();
            for (TokenEntry entry : sentence) {
                JsonArray pair = new JsonArray();
                pair.add(entry.token());
                pair.add(entry.analysis());
                sentenceArray.add(pair);
            }
            sentences.add(sentenceArray);
        }
        payload.add("sentences", sentences);
        return payload;
    }

    private String computeMarkup(String text) {
        TextAnalysis analysis = analyzer.analyze(text);
        return analysis.markup();
    }

    @Override
    public void close() {
        // No external resources to close; method retained for API compatibility.
    }
}
