package com.example.uqureader.webapp.morphology;

import com.example.uqureader.webapp.MorphologyException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
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
import java.util.function.BiFunction;
import java.util.function.Function;

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

    private static final String ORIGIN = "https://tugantel.tatar";
    private static final String REFERER = ORIGIN + "/new2022/morph/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/125.0.0.0 Safari/537.36";
    private static final List<RequestVariant> WORD_VARIANTS = List.of(
            RequestVariant.post("word", (raw, encoded) -> "word=" + encoded),
            RequestVariant.post("word+ajax_action", (raw, encoded) -> "word=" + encoded + "&ajax_action=word"),
            RequestVariant.post("word+ajax", (raw, encoded) -> "word=" + encoded + "&ajax=1"),
            RequestVariant.post("word+mode=analyze", (raw, encoded) -> "word=" + encoded + "&mode=analyze"),
            RequestVariant.post("word+mode=analyse", (raw, encoded) -> "word=" + encoded + "&mode=analyse"),
            RequestVariant.post("word+lang=tt", (raw, encoded) -> "word=" + encoded + "&lang=tt"),
            RequestVariant.post("word+lang=tt+mode", (raw, encoded) -> "word=" + encoded + "&lang=tt&mode=analyze"),
            RequestVariant.post("text-parameter", (raw, encoded) -> "text=" + encoded),
            RequestVariant.post("request=word", (raw, encoded) -> "request=word&word=" + encoded),
            RequestVariant.get("word-get", (raw, encoded) -> "word=" + encoded)
    );
    private static final List<RequestVariant> TEXT_VARIANTS = List.of(
            RequestVariant.post("text", (raw, encoded) -> "text=" + encoded),
            RequestVariant.post("text+ajax_action", (raw, encoded) -> "text=" + encoded + "&ajax_action=text"),
            RequestVariant.post("text+ajax", (raw, encoded) -> "text=" + encoded + "&ajax=1"),
            RequestVariant.post("text+mode=analyze", (raw, encoded) -> "text=" + encoded + "&mode=analyze"),
            RequestVariant.post("text+mode=analyse", (raw, encoded) -> "text=" + encoded + "&mode=analyse"),
            RequestVariant.post("text+lang=tt", (raw, encoded) -> "text=" + encoded + "&lang=tt"),
            RequestVariant.post("text+lang=tt+mode", (raw, encoded) -> "text=" + encoded + "&lang=tt&mode=analyze"),
            RequestVariant.post("request=text", (raw, encoded) -> "request=text&text=" + encoded),
            RequestVariant.post("operation=analyse", (raw, encoded) -> "text=" + encoded + "&operation=analyse"),
            RequestVariant.post("json-data", (raw, encoded) -> "data=" + urlEncode(jsonPayload("text", raw))),
            RequestVariant.get("text-get", (raw, encoded) -> "text=" + encoded)
    );

    private final HttpClient httpClient;
    private final URI endpoint;
    private final int batchLimit;

    /**
     * Creates a client that targets the production Tugantel endpoint.
     */
    public RemoteMorphologyClient() {
        this(createDefaultHttpClient(), URI.create("https://tugantel.tatar/new2022/morph/ajax.php"),
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

    private static HttpClient createDefaultHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder();
        configureProxy(builder);
        return builder.build();
    }

    private static void configureProxy(HttpClient.Builder builder) {
        ProxyConfig proxy = ProxyConfig.detect();
        if (proxy == null) {
            return;
        }
        builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
    }

    private record ProxyConfig(String host, int port) {

        private static ProxyConfig detect() {
            if (isProxyDisabled()) {
                return null;
            }
            ProxyConfig fromSystemProperties = fromSystemProperties();
            if (fromSystemProperties != null) {
                return fromSystemProperties;
            }
            ProxyConfig fromEnvironment = fromEnvironment();
            if (fromEnvironment != null) {
                return fromEnvironment;
            }
            return new ProxyConfig("proxy", 8080);
        }

        private static boolean isProxyDisabled() {
            if (Boolean.getBoolean("morphology.proxy.disable")) {
                return true;
            }
            String disabled = System.getenv("MORPHOLOGY_PROXY_DISABLE");
            return disabled != null && disabled.equalsIgnoreCase("true");
        }

        private static ProxyConfig fromSystemProperties() {
            ProxyConfig https = normalise(System.getProperty("https.proxyHost"), System.getProperty("https.proxyPort"));
            if (https != null) {
                return https;
            }
            return normalise(System.getProperty("http.proxyHost"), System.getProperty("http.proxyPort"));
        }

        private static ProxyConfig fromEnvironment() {
            String[] uriKeys = {"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"};
            for (String key : uriKeys) {
                ProxyConfig config = parseProxyUri(System.getenv(key));
                if (config != null) {
                    return config;
                }
            }
            return normalise(System.getenv("PROXY_HOST"), System.getenv("PROXY_PORT"));
        }

        private static ProxyConfig parseProxyUri(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                String candidate = value.contains("://") ? value : "http://" + value;
                URI uri = URI.create(candidate);
                String host = uri.getHost();
                int port = uri.getPort();
                if (host == null || host.isBlank()) {
                    return null;
                }
                if (port <= 0) {
                    port = uri.getScheme() != null && uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
                }
                return new ProxyConfig(host, port);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        private static ProxyConfig normalise(String host, String portValue) {
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = 80;
            if (portValue != null && !portValue.isBlank()) {
                try {
                    port = Integer.parseInt(portValue.trim());
                } catch (NumberFormatException ignored) {
                    port = 80;
                }
            }
            if (port <= 0) {
                port = 80;
            }
            return new ProxyConfig(host.trim(), port);
        }
    }

    /**
     * Performs morphology lookup for a single token.
     *
     * @param word surface form to analyse
     * @return {@link WordMarkup} with analyses returned by the remote service
     */
    public WordMarkup analyzeWord(String word) {
        Objects.requireNonNull(word, "word");
        return attemptVariants(word, WORD_VARIANTS, body -> parseWordResponse(word, body),
                "слово \"" + word + "\"");
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
            List<WordMarkup> batchMarkup = attemptVariants(batch, TEXT_VARIANTS, this::parseBatchResponse,
                    describeBatch(batch));
            result.addAll(batchMarkup);
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

    private <T> T attemptVariants(String value,
                                  List<RequestVariant> variants,
                                  Function<String, T> parser,
                                  String contextDescription) {
        String encodedValue = urlEncode(value);
        List<String> failures = new ArrayList<>();
        for (RequestVariant variant : variants) {
            HttpRequest request = buildRequest(variant, value, encodedValue);
            try {
                String body = execute(request);
                return parser.apply(body);
            } catch (MorphologyException ex) {
                failures.add(variant.describeFailure(ex));
            } catch (RuntimeException ex) {
                String message = ex.getMessage();
                if (message == null || message.isBlank()) {
                    message = ex.getClass().getSimpleName();
                }
                failures.add(variant.describeFailure(new MorphologyException(message, ex)));
            }
        }
        String message = "Удалённый сервис не принял запрос (" + contextDescription + ")";
        if (!failures.isEmpty()) {
            message += ": " + String.join("; ", failures);
        }
        throw new MorphologyException(message);
    }

    private HttpRequest buildRequest(RequestVariant variant, String rawValue, String encodedValue) {
        String payload = variant.payloadFactory.apply(rawValue, encodedValue);
        HttpRequest.Builder builder;
        if (variant.method == HttpMethod.GET) {
            builder = HttpRequest.newBuilder(appendQuery(endpoint, payload)).GET();
        } else {
            builder = HttpRequest.newBuilder(endpoint)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        }
        applyCommonHeaders(builder);
        return builder.build();
    }

    private HttpRequest.Builder applyCommonHeaders(HttpRequest.Builder builder) {
        return builder
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", USER_AGENT)
                .header("Origin", ORIGIN)
                .header("Referer", REFERER)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept-Language", "ru,en;q=0.8,tt;q=0.7")
                .header("Cache-Control", "no-cache");
    }

    private URI appendQuery(URI base, String query) {
        if (query == null || query.isBlank()) {
            return base;
        }
        StringBuilder builder = new StringBuilder(base.toString());
        if (base.getQuery() == null || base.getQuery().isBlank()) {
            builder.append('?').append(query);
        } else {
            builder.append('&').append(query);
        }
        return URI.create(builder.toString());
    }

    private String describeBatch(String batch) {
        String trimmed = batch.strip();
        if (trimmed.length() > 40) {
            trimmed = trimmed.substring(0, 37) + "…";
        }
        return "фрагмент \"" + trimmed + "\"";
    }

    private static String jsonPayload(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object.toString();
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

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private enum HttpMethod {
        GET,
        POST
    }

    private static final class RequestVariant {
        private final HttpMethod method;
        private final String description;
        private final BiFunction<String, String, String> payloadFactory;

        private RequestVariant(HttpMethod method,
                               String description,
                               BiFunction<String, String, String> payloadFactory) {
            this.method = method;
            this.description = description;
            this.payloadFactory = payloadFactory;
        }

        static RequestVariant post(String description,
                                   BiFunction<String, String, String> payloadFactory) {
            return new RequestVariant(HttpMethod.POST, description, payloadFactory);
        }

        static RequestVariant get(String description,
                                  BiFunction<String, String, String> payloadFactory) {
            return new RequestVariant(HttpMethod.GET, description, payloadFactory);
        }

        String describeFailure(MorphologyException ex) {
            return description + " -> " + ex.getMessage();
        }
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

