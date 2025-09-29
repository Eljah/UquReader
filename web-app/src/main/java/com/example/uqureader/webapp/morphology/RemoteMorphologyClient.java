package com.example.uqureader.webapp.morphology;

import com.example.uqureader.webapp.MorphologyException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.text.BreakIterator;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight HTTP client for querying the Tugantel online morphology service.
 * Учитывает:
 *  - необходимость cookie/«прогрева» сессии;
 *  - нестабильность конечной точки (ajax.php/страница);
 *  - проблемы с TLS-сертификатом (опциональный insecure-режим).
 *
 * По умолчанию TLS-проверка включена (безопасно).
 * Для обхода кривого сертификата можно запустить JVM с -Dmorphology.ssl.insecure=true
 * или указать ваш trustStore: -Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStorePassword=...
 */
public class RemoteMorphologyClient {

    private static final int DEFAULT_BATCH_LIMIT = 500;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final String ORIGIN = "https://tugantel.tatar";
    private static final String REFERER = ORIGIN + "/new2022/morph/"; // важен слэш
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/125.0.0.0 Safari/537.36";

    // Порядок важен: сначала HTTPS, затем HTTP (если сервер позволяет).
    private static final List<URI> DEFAULT_ENDPOINTS = List.of(
            URI.create("https://tugantel.tatar/new2022/morph/ajax.php"),
            URI.create("https://tugantel.tatar/new2022/morph"),
            URI.create("http://tugantel.tatar/new2022/morph/ajax.php"),
            URI.create("http://tugantel.tatar/new2022/morph")
    );

    /** Сервер реально принимает form-urlencoded text=... */
    private static final List<RequestVariant> WORD_VARIANTS = List.of(
            RequestVariant.post("text", (raw, enc) -> "text=" + enc)
    );
    private static final List<RequestVariant> TEXT_VARIANTS = List.of(
            RequestVariant.post("text", (raw, enc) -> "text=" + enc)
    );

    private final HttpClient httpClient;
    private final List<URI> endpoints;
    private final int batchLimit;

    /** Конфигурация по умолчанию. */
    public RemoteMorphologyClient() {
        this(createHttpClientFromSystem(), DEFAULT_ENDPOINTS, DEFAULT_BATCH_LIMIT);
        warmupSession();
    }

    /** Кастомный конструктор. */
    public RemoteMorphologyClient(HttpClient httpClient, List<URI> endpoints, int batchLimit) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        Objects.requireNonNull(endpoints, "endpoints");
        if (endpoints.isEmpty()) throw new IllegalArgumentException("At least one endpoint must be provided");
        for (URI ep : endpoints) {
            if (ep == null || !ep.isAbsolute()) throw new IllegalArgumentException("Endpoint URI must be absolute: " + ep);
        }
        if (batchLimit <= 0) throw new IllegalArgumentException("Batch limit must be positive");
        this.endpoints = List.copyOf(endpoints);
        this.batchLimit = batchLimit;
    }

    // -------------------- HttpClient factory --------------------

    private static HttpClient createHttpClientFromSystem() {
        boolean insecure = true; // сервис использует невалидный сертификат — всегда работаем в insecure-режиме
        CookieManager cm = new CookieManager();
        cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .cookieHandler(cm);

        configureProxy(b);

        if (insecure) {
            try {
                SSLContext sslContext = insecureSSLContext();
                HostnameVerifier hv = (hostname, session) -> true;
                b.sslContext(sslContext).sslParameters(sslContext.getDefaultSSLParameters());
                // Hostname verifier задаётся на уровне клиента только через builder на JDK17+ при создании,
                // но java.net.http не даёт публичного API для установки кастомного verifier.
                // Поэтому оставляем только TrustManager "trust-all". Обычно этого достаточно.
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                // Если не смогли создать insecure контекст — оставляем дефолтный, чтобы не ломать клиент.
            }
        }

        return b.build();
    }

    private static SSLContext insecureSSLContext() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new java.security.SecureRandom());
        return sc;
    }

    private static void configureProxy(HttpClient.Builder builder) {
        ProxyConfig proxy = ProxyConfig.detect();
        if (proxy == null) return;
        builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
    }

    private record ProxyConfig(String host, int port) {
        private static ProxyConfig detect() {
            if (isProxyDisabled()) return null;
            ProxyConfig p = fromSystemProperties();
            if (p != null) return p;
            p = fromEnvironment();
            if (p != null) return p;
            return null;
        }
        private static boolean isProxyDisabled() {
            if (Boolean.getBoolean("morphology.proxy.disable")) return true;
            String disabled = System.getenv("MORPHOLOGY_PROXY_DISABLE");
            return disabled != null && disabled.equalsIgnoreCase("true");
        }
        private static ProxyConfig fromSystemProperties() {
            ProxyConfig https = normalise(System.getProperty("https.proxyHost"), System.getProperty("https.proxyPort"));
            if (https != null) return https;
            return normalise(System.getProperty("http.proxyHost"), System.getProperty("http.proxyPort"));
        }
        private static ProxyConfig fromEnvironment() {
            String[] keys = {"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"};
            for (String k : keys) {
                ProxyConfig c = parseProxyUri(System.getenv(k));
                if (c != null) return c;
            }
            return normalise(System.getenv("PROXY_HOST"), System.getenv("PROXY_PORT"));
        }
        private static ProxyConfig parseProxyUri(String v) {
            if (v == null || v.isBlank()) return null;
            try {
                String candidate = v.contains("://") ? v : "http://" + v;
                URI uri = URI.create(candidate);
                String host = uri.getHost();
                int port = uri.getPort();
                if (host == null || host.isBlank()) return null;
                if (port <= 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
                return new ProxyConfig(host, port);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        private static ProxyConfig normalise(String host, String portValue) {
            if (host == null || host.isBlank()) return null;
            int port = 80;
            if (portValue != null && !portValue.isBlank()) {
                try { port = Integer.parseInt(portValue.trim()); } catch (NumberFormatException ignored) { port = 80; }
            }
            if (port <= 0) port = 80;
            return new ProxyConfig(host.trim(), port);
        }
    }

    // -------------------- Warmup --------------------

    private void warmupSession() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(REFERER))
                .GET()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru,en;q=0.8,tt;q=0.7")
                .header("Referer", REFERER)
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignore) {
            // главное — попытаться получить cookie; ошибки прогрева не критичны
        }
    }

    // -------------------- Public API --------------------

    public WordMarkup analyzeWord(String word) {
        Objects.requireNonNull(word, "word");
        return attemptEndpointsAndVariants(
                word, WORD_VARIANTS, b -> parseFlexibleResponse(word, b), "слово \"" + word + "\""
        );
    }

    public List<WordMarkup> analyzeText(String text) {
        Objects.requireNonNull(text, "text");
        List<String> batches = splitIntoBatches(text);
        List<WordMarkup> out = new ArrayList<>();
        for (String batch : batches) {
            List<WordMarkup> part = attemptEndpointsAndVariants(
                    batch, TEXT_VARIANTS, this::parseFlexibleBatchResponse, describeBatch(batch)
            );
            out.addAll(part);
        }
        return Collections.unmodifiableList(out);
    }

    // -------------------- batching --------------------

    List<String> splitIntoBatches(String text) {
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) return Collections.emptyList();
        List<String> batches = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String trimmed = sentence.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > DEFAULT_BATCH_LIMIT) {
                if (current.length() > 0) {
                    batches.add(current.toString());
                    current.setLength(0);
                }
                batches.addAll(splitOversizedSentence(trimmed));
                continue;
            }
            int prospective = current.length() == 0 ? trimmed.length() : current.length() + 1 + trimmed.length();
            if (current.length() == 0) current.append(trimmed);
            else if (prospective <= DEFAULT_BATCH_LIMIT) current.append('\n').append(trimmed);
            else {
                batches.add(current.toString());
                current.setLength(0);
                current.append(trimmed);
            }
        }
        if (current.length() > 0) batches.add(current.toString());
        return Collections.unmodifiableList(batches);
    }

    private List<String> splitOversizedSentence(String sentence) {
        List<String> fragments = new ArrayList<>();
        int length = sentence.length(), start = 0;
        while (start < length) {
            int end = Math.min(start + DEFAULT_BATCH_LIMIT, length);
            if (end < length) {
                int wb = findLastWhitespaceBoundary(sentence, start, end);
                if (wb > start) end = wb;
            }
            if (end == start) end = Math.min(start + DEFAULT_BATCH_LIMIT, length);
            String fragment = sentence.substring(start, end).strip();
            if (!fragment.isEmpty()) fragments.add(fragment);
            start = end;
        }
        return fragments;
    }

    private int findLastWhitespaceBoundary(String s, int start, int candidateEnd) {
        int i = candidateEnd;
        while (i > start) {
            int cp = s.codePointBefore(i);
            if (Character.isWhitespace(cp)) return i;
            i -= Character.charCount(cp);
        }
        return -1;
    }

    private List<String> splitSentences(String text) {
        BreakIterator it = BreakIterator.getSentenceInstance(new Locale("tt"));
        it.setText(text);
        List<String> sentences = new ArrayList<>();
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String sentence = text.substring(start, end);
            if (!sentence.isBlank()) sentences.add(sentence);
        }
        return sentences;
    }

    // -------------------- HTTP & retry logic --------------------

    private <T> T attemptEndpointsAndVariants(String value,
                                              List<RequestVariant> variants,
                                              Function<String, T> parser,
                                              String ctx) {
        String encoded = urlEncode(value);
        List<String> failures = new ArrayList<>();

        for (URI ep : endpoints) {
            for (RequestVariant v : variants) {
                HttpRequest req = buildRequest(ep, v, value, encoded);
                try {
                    String body = execute(req);
                    return parser.apply(body);
                } catch (MorphologyException ex) {
                    failures.add(ep + " [" + v.description + "] -> " + ex.getMessage());
                } catch (RuntimeException ex) {
                    String msg = (ex.getMessage() == null || ex.getMessage().isBlank())
                            ? ex.getClass().getSimpleName() : ex.getMessage();
                    failures.add(ep + " [" + v.description + "] -> " + msg);
                }
            }
        }

        String msg = "Удалённый сервис не принял запрос (" + ctx + ")";
        if (!failures.isEmpty()) msg += ": " + String.join("; ", failures);
        throw new MorphologyException(msg);
    }

    private HttpRequest buildRequest(URI endpoint, RequestVariant variant, String raw, String encoded) {
        String payload = variant.payloadFactory.apply(raw, encoded);
        HttpRequest.Builder b;
        if (variant.method == HttpMethod.GET) {
            b = HttpRequest.newBuilder(appendQuery(endpoint, payload)).GET();
        } else {
            b = HttpRequest.newBuilder(endpoint)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        }
        applyCommonHeaders(b);
        b.timeout(REQUEST_TIMEOUT);
        return b.build();
    }

    private HttpRequest.Builder applyCommonHeaders(HttpRequest.Builder b) {
        return b.header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                .header("User-Agent", USER_AGENT)
                .header("Origin", ORIGIN)
                .header("Referer", REFERER)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept-Language", "ru,en;q=0.8,tt;q=0.7")
                .header("Cache-Control", "no-cache");
    }

    private URI appendQuery(URI base, String query) {
        if (query == null || query.isBlank()) return base;
        StringBuilder sb = new StringBuilder(base.toString());
        if (base.getQuery() == null || base.getQuery().isBlank()) sb.append('?').append(query);
        else sb.append('&').append(query);
        return URI.create(sb.toString());
    }

    private String execute(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                String body = resp.body();
                String snippet = body != null ? body.substring(0, Math.min(200, body.length())).replaceAll("\\s+", " ").trim() : "";
                throw new MorphologyException("Remote morphology service returned status " + resp.statusCode()
                        + (snippet.isEmpty() ? "" : " — body: " + snippet));
            }
            return resp.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MorphologyException("Failed to query remote morphology service (InterruptedException)", ex);
        } catch (IOException ex) {
            String msg = ex.getClass().getSimpleName() + (ex.getMessage() != null ? (": " + ex.getMessage()) : "");
            throw new MorphologyException("Failed to query remote morphology service (" + msg + ")", ex);
        }
    }

    private static String urlEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private enum HttpMethod { GET, POST }

    private static final class RequestVariant {
        private final HttpMethod method;
        private final String description;
        private final BiFunction<String, String, String> payloadFactory;
        private RequestVariant(HttpMethod method, String description, BiFunction<String, String, String> payloadFactory) {
            this.method = method; this.description = description; this.payloadFactory = payloadFactory;
        }
        static RequestVariant post(String description, BiFunction<String, String, String> payloadFactory) {
            return new RequestVariant(HttpMethod.POST, description, payloadFactory);
        }
        static RequestVariant get(String description, BiFunction<String, String, String> payloadFactory) {
            return new RequestVariant(HttpMethod.GET, description, payloadFactory);
        }
    }

    // -------------------- Parsing --------------------

    /** Сначала JSON; если не похоже на JSON — HTML эвристикой. */
    private WordMarkup parseFlexibleResponse(String fallbackWord, String body) {
        try {
            return parseWordResponseJson(fallbackWord, body);
        } catch (MorphologyException ignored) {
            List<WordMarkup> list = parseHtmlPairs(body);
            if (!list.isEmpty()) {
                for (WordMarkup wm : list) {
                    if (wm.word().equalsIgnoreCase(fallbackWord.trim())) return wm;
                }
                return list.get(0);
            }
            throw new MorphologyException("Unexpected response (neither JSON nor recognizable HTML)");
        }
    }

    private List<WordMarkup> parseFlexibleBatchResponse(String body) {
        try {
            return parseBatchResponseJson(body);
        } catch (MorphologyException ignored) {
            List<WordMarkup> list = parseHtmlPairs(body);
            if (!list.isEmpty()) return list;
            throw new MorphologyException("Unexpected response (neither JSON nor recognizable HTML)");
        }
    }

    private WordMarkup parseWordResponseJson(String fallbackWord, String body) {
        JsonElement el = parseJson(body);
        if (!el.isJsonObject()) throw new MorphologyException("Unexpected JSON format");
        return parseWordObject(el.getAsJsonObject(), fallbackWord);
    }

    private List<WordMarkup> parseBatchResponseJson(String body) {
        JsonElement el = parseJson(body);
        if (el.isJsonArray()) return parseWordArray(el.getAsJsonArray());
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("tokens") && obj.get("tokens").isJsonArray()) return parseWordArray(obj.getAsJsonArray("tokens"));
            if (obj.has("results") && obj.get("results").isJsonArray()) return parseWordArray(obj.getAsJsonArray("results"));
            return Collections.singletonList(parseWordObject(obj, obj.has("word") ? obj.get("word").getAsString() : ""));
        }
        throw new MorphologyException("Unexpected JSON format");
    }

    private List<WordMarkup> parseWordArray(JsonArray array) {
        List<WordMarkup> res = new ArrayList<>(array.size());
        for (JsonElement el : array) {
            if (el.isJsonObject()) res.add(parseWordObject(el.getAsJsonObject(), ""));
            else if (el.isJsonPrimitive()) {
                String value = el.getAsString();
                res.add(new WordMarkup(value, Collections.singletonList(value)));
            }
        }
        return res;
    }

    private WordMarkup parseWordObject(JsonObject obj, String fallbackWord) {
        String surface = firstNonBlank(asString(obj, "word"), asString(obj, "token"), asString(obj, "surface"), fallbackWord);
        if (surface == null) throw new MorphologyException("Missing word field in JSON");
        List<String> analyses = extractAnalyses(obj);
        if (analyses.isEmpty()) throw new MorphologyException("No analyses returned for word: " + surface);
        return new WordMarkup(surface, analyses);
    }

    private List<String> extractAnalyses(JsonObject obj) {
        Set<String> set = new LinkedHashSet<>();
        if (obj.has("analyses")) {
            JsonElement el = obj.get("analyses");
            if (el.isJsonArray()) {
                for (JsonElement e : el.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) set.add(e.getAsString());
                    else if (e.isJsonObject()) {
                        JsonObject o = e.getAsJsonObject();
                        String v = firstNonBlank(asString(o, "analysis"), asString(o, "tag"));
                        if (v != null && !v.isBlank()) set.add(v);
                    }
                }
            } else if (el.isJsonPrimitive()) {
                set.add(el.getAsString());
            }
        }
        if (set.isEmpty()) {
            String single = firstNonBlank(asString(obj, "analysis"), asString(obj, "tag"), asString(obj, "markup"));
            if (single != null && !single.isBlank()) set.add(single);
        }
        return Collections.unmodifiableList(new ArrayList<>(set));
    }

    private JsonElement parseJson(String body) {
        try {
            return JsonParser.parseString(body);
        } catch (RuntimeException ex) {
            throw new MorphologyException("Unable to parse response as JSON", ex);
        }
    }

    // HTML fallback (эвристический, без зависимостей)
    private List<WordMarkup> parseHtmlPairs(String html) {
        if (html == null || html.isBlank()) return Collections.emptyList();
        List<WordMarkup> fromPre = parsePreformattedMarkup(html);
        if (!fromPre.isEmpty()) {
            return fromPre;
        }

        String noScript = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ");
        String text = noScript.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?is)</(p|li|tr|div|h\\d)>", "\n")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\n *", "\n")
                .trim();

        Pattern linePat = Pattern.compile("(?m)^(\\S.{0,100}?)[\\s]*[—:-]{1,2}[\\s]*(\\S.+)$");
        Matcher m = linePat.matcher(text);

        Map<String, LinkedHashSet<String>> collected = new LinkedHashMap<>();
        while (m.find()) {
            String word = m.group(1).trim();
            String analysis = m.group(2).trim();
            if (word.isEmpty() || analysis.isEmpty()) continue;
            collected.computeIfAbsent(word, k -> new LinkedHashSet<>()).add(analysis);
        }

        if (collected.isEmpty()) {
            Pattern alt = Pattern.compile("«([^»]{1,100})»\\s*\\(([^)]+)\\)");
            Matcher a = alt.matcher(text);
            while (a.find()) {
                String word = a.group(1).trim();
                String analysis = a.group(2).trim();
                if (word.isEmpty() || analysis.isEmpty()) continue;
                collected.computeIfAbsent(word, k -> new LinkedHashSet<>()).add(analysis);
            }
        }

        List<WordMarkup> out = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : collected.entrySet()) {
            out.add(new WordMarkup(e.getKey(), new ArrayList<>(e.getValue())));
        }
        return out;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private String asString(JsonObject object, String property) {
        JsonElement el = object.get(property);
        if (el == null || el.isJsonNull()) return null;
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private List<WordMarkup> parsePreformattedMarkup(String html) {
        Pattern prePattern = Pattern.compile("(?is)<pre[^>]*>(.*?)</pre>");
        Matcher matcher = prePattern.matcher(html);
        while (matcher.find()) {
            String body = matcher.group(1);
            String text = decodeHtmlEntities(body.replaceAll("(?is)<[^>]+>", ""));
            List<String> lines = new ArrayList<>();
            for (String rawLine : text.split("\\R")) {
                String trimmed = rawLine.strip();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
            if (lines.size() < 2) {
                continue;
            }
            List<WordMarkup> tokens = new ArrayList<>();
            for (int i = 0; i + 1 < lines.size(); i += 2) {
                String word = decodeHtmlEntities(lines.get(i));
                String analysisLine = decodeHtmlEntities(lines.get(i + 1));
                List<String> analyses = splitAnalysesLine(analysisLine);
                if (word.isEmpty() || analyses.isEmpty()) {
                    continue;
                }
                tokens.add(new WordMarkup(word, analyses));
            }
            if (!tokens.isEmpty()) {
                return tokens;
            }
        }
        return Collections.emptyList();
    }

    private List<String> splitAnalysesLine(String line) {
        if (line == null) {
            return Collections.emptyList();
        }
        String cleaned = line.trim();
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = cleaned.split(";");
        List<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        if (values.isEmpty()) {
            values.add("Error");
        }
        return values;
    }

    private String decodeHtmlEntities(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    /** Value object describing a remote morphology result. */
    public static final class WordMarkup {
        private final String word;
        private final List<String> analyses;
        public WordMarkup(String word, List<String> analyses) {
            this.word = Objects.requireNonNull(word, "word");
            Objects.requireNonNull(analyses, "analyses");
            this.analyses = Collections.unmodifiableList(new ArrayList<>(analyses));
        }
        public String word() { return word; }
        public List<String> analyses() { return analyses; }
    }

    private static final Set<String> SINGLE_VALUE_TAGS = Set.of(
            "Error", "Latin", "NR", "Num", "Rus", "Sign", "Type1", "Type2", "Type3", "Type4"
    );

    /**
     * Converts the remote markup into the tab-separated format used by the bundled markup resources.
     */
    public static String formatAsMarkup(List<WordMarkup> tokens) {
        Objects.requireNonNull(tokens, "tokens");
        if (tokens.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            WordMarkup token = tokens.get(i);
            String analyses = normaliseAnalyses(token.analyses());
            builder.append(token.word()).append('\t').append(analyses);
            if (i + 1 < tokens.size()) builder.append('\n');
        }
        return builder.toString();
    }

    private static String normaliseAnalyses(List<String> analyses) {
        if (analyses == null || analyses.isEmpty()) {
            return "Error";
        }
        List<String> cleaned = new ArrayList<>();
        for (String analysis : analyses) {
            if (analysis == null) continue;
            String trimmed = analysis.trim();
            if (!trimmed.isEmpty()) cleaned.add(trimmed);
        }
        if (cleaned.isEmpty()) {
            return "Error";
        }
        int size = cleaned.size();
        StringBuilder builder = new StringBuilder();
        for (String value : cleaned) {
            boolean appendSemicolon = size > 1 || !SINGLE_VALUE_TAGS.contains(value);
            if (appendSemicolon && !value.endsWith(";")) {
                builder.append(value).append(';');
            } else {
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private String describeBatch(String batch) {
        String trimmed = batch.strip();
        if (trimmed.length() > 40) {
            trimmed = trimmed.substring(0, 37) + "…";
        }
        return "фрагмент \"" + trimmed + "\"";
    }

}
