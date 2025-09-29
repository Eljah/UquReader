package com.example.uqureader.webapp.morphology;

import com.example.uqureader.webapp.MorphologyException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight HTTP client for querying the Tugantel online morphology service.
 * <p>
 * The client exposes helpers for analysing isolated tokens as well as longer
 * passages of text. Longer passages are split into batches of complete
 * sentences so that each HTTP request stays below the 500 character limit
 * imposed by the remote service.
 */
public class RemoteMorphologyClient {

    private static final int DEFAULT_BATCH_LIMIT = 500;

    private final HttpClient httpClient;
    private final URI endpoint;
    private final int batchLimit;

    /**
     * Creates a client that targets the production Tugantel endpoint.
     */
    public RemoteMorphologyClient() {
        this(HttpClient.newHttpClient(), URI.create("https://tugantel.tatar/new2022/morph/ajax.php"),
                DEFAULT_BATCH_LIMIT);
    }

    /**
     * Creates a client configured with a custom HTTP client and endpoint.
     *
     * @param httpClient HTTP client to use
     * @param endpoint   absolute URI of the Tugantel AJAX handler
     * @param batchLimit maximum number of characters per batch
     */
    public RemoteMorphologyClient(HttpClient httpClient, URI endpoint, int batchLimit) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute()) {
            throw new IllegalArgumentException("Endpoint URI must be absolute");
        }
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("Batch limit must be positive");
        }
        this.batchLimit = batchLimit;
    }

    /**
     * Performs morphology lookup for a single token.
     *
     * @param word surface form to analyse
     * @return {@link WordMarkup} with analyses returned by the remote service
     */
    public WordMarkup analyzeWord(String word) {
        Objects.requireNonNull(word, "word");
        String payload = "word=" + urlEncode(word);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .header("Accept", "application/json, text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        String body = execute(request);
        return parseWordResponse(word, body);
    }

    /**
     * Performs morphology lookup for an arbitrary text. The text is split into
     * batches of complete sentences, each containing at most 500 characters,
     * and the batches are sent sequentially to the remote service.
     *
     * @param text input text to analyse
     * @return list of {@link WordMarkup} entries for all analysed tokens
     */
    public List<WordMarkup> analyzeText(String text) {
        Objects.requireNonNull(text, "text");
        List<String> batches = splitIntoBatches(text);
        List<WordMarkup> result = new ArrayList<>();
        for (String batch : batches) {
            String payload = "text=" + urlEncode(batch);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    .header("Accept", "application/json, text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            String body = execute(request);
            result.addAll(parseBatchResponse(body));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Splits the provided text into batches of sentences respecting the
     * configured 500 character limit. Visible for unit tests.
     */
    List<String> splitIntoBatches(String text) {
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> batches = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String trimmed = sentence.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > batchLimit) {
                if (current.length() > 0) {
                    batches.add(current.toString());
                    current.setLength(0);
                }
                batches.add(trimmed);
                continue;
            }

            int prospectiveLength = current.length() == 0
                    ? trimmed.length()
                    : current.length() + 1 + trimmed.length();
            if (current.length() == 0) {
                current.append(trimmed);
            } else if (prospectiveLength <= batchLimit) {
                current.append('\n').append(trimmed);
            } else {
                batches.add(current.toString());
                current.setLength(0);
                current.append(trimmed);
            }
        }
        if (current.length() > 0) {
            batches.add(current.toString());
        }
        return Collections.unmodifiableList(batches);
    }

    private List<String> splitSentences(String text) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(new Locale("tt"));
        iterator.setText(text);
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end);
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private String execute(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new MorphologyException("Remote morphology service returned status " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MorphologyException("Failed to query remote morphology service", ex);
        } catch (IOException ex) {
            throw new MorphologyException("Failed to query remote morphology service", ex);
        }
    }

    private WordMarkup parseWordResponse(String fallbackWord, String body) {
        JsonElement element = parseJson(body);
        if (!element.isJsonObject()) {
            throw new MorphologyException("Unexpected response format: " + body);
        }
        return parseWordObject(element.getAsJsonObject(), fallbackWord);
    }

    private List<WordMarkup> parseBatchResponse(String body) {
        JsonElement element = parseJson(body);
        if (element.isJsonArray()) {
            return parseWordArray(element.getAsJsonArray());
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("tokens") && object.get("tokens").isJsonArray()) {
                return parseWordArray(object.getAsJsonArray("tokens"));
            }
            if (object.has("results") && object.get("results").isJsonArray()) {
                return parseWordArray(object.getAsJsonArray("results"));
            }
            return Collections.singletonList(parseWordObject(object,
                    object.has("word") ? object.get("word").getAsString() : ""));
        }
        throw new MorphologyException("Unexpected response format: " + body);
    }

    private List<WordMarkup> parseWordArray(JsonArray array) {
        List<WordMarkup> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                result.add(parseWordObject(element.getAsJsonObject(), ""));
            } else if (element.isJsonPrimitive()) {
                String value = element.getAsString();
                result.add(new WordMarkup(value, Collections.singletonList(value)));
            }
        }
        return result;
    }

    private WordMarkup parseWordObject(JsonObject object, String fallbackWord) {
        String surface = firstNonBlank(
                asString(object, "word"),
                asString(object, "token"),
                asString(object, "surface"),
                fallbackWord);
        if (surface == null) {
            throw new MorphologyException("Missing word field in response: " + object);
        }

        List<String> analyses = extractAnalyses(object);
        if (analyses.isEmpty()) {
            throw new MorphologyException("No analyses returned for word: " + surface);
        }
        return new WordMarkup(surface, analyses);
    }

    private List<String> extractAnalyses(JsonObject object) {
        Set<String> analyses = new LinkedHashSet<>();
        if (object.has("analyses")) {
            JsonElement element = object.get("analyses");
            if (element.isJsonArray()) {
                for (JsonElement entry : element.getAsJsonArray()) {
                    if (entry.isJsonPrimitive()) {
                        analyses.add(entry.getAsString());
                    } else if (entry.isJsonObject()) {
                        JsonObject obj = entry.getAsJsonObject();
                        String value = firstNonBlank(asString(obj, "analysis"), asString(obj, "tag"));
                        if (value != null && !value.isBlank()) {
                            analyses.add(value);
                        }
                    }
                }
            } else if (element.isJsonPrimitive()) {
                analyses.add(element.getAsString());
            }
        }
        if (analyses.isEmpty()) {
            String single = firstNonBlank(asString(object, "analysis"), asString(object, "tag"), asString(object, "markup"));
            if (single != null && !single.isBlank()) {
                analyses.add(single);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(analyses));
    }

    private JsonElement parseJson(String body) {
        try {
            return JsonParser.parseString(body);
        } catch (RuntimeException ex) {
            throw new MorphologyException("Unable to parse response as JSON", ex);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String asString(JsonObject object, String property) {
        JsonElement element = object.get(property);
        if (element == null) {
            return null;
        }
        if (element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return null;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Value object describing a remote morphology result.
     */
    public static final class WordMarkup {
        private final String word;
        private final List<String> analyses;

        public WordMarkup(String word, List<String> analyses) {
            this.word = Objects.requireNonNull(word, "word");
            Objects.requireNonNull(analyses, "analyses");
            this.analyses = Collections.unmodifiableList(new ArrayList<>(analyses));
        }

        public String word() {
            return word;
        }

        public List<String> analyses() {
            return analyses;
        }
    }
}

