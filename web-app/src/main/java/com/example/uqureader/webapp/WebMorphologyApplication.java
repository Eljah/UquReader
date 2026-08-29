package com.example.uqureader.webapp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.example.uqureader.webapp.reader.GrammarCatalog;
import com.example.uqureader.webapp.reader.InMemoryReaderRepository;
import com.example.uqureader.webapp.reader.LemmaStat;
import com.example.uqureader.webapp.reader.ReaderRepository;
import com.example.uqureader.webapp.reader.ReaderRepositoryFactory;
import com.example.uqureader.webapp.reader.ReaderToken;
import com.example.uqureader.webapp.reader.ReaderWork;
import com.example.uqureader.webapp.reader.ReaderWorkCatalog;
import com.example.uqureader.webapp.reader.ReadingEventRecord;
import com.example.uqureader.webapp.reader.ReadingEvent;
import com.example.uqureader.webapp.reader.ReadingState;
import com.example.uqureader.webapp.reader.RhvoiceTtsService;
import com.example.uqureader.webapp.reader.UserSession;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Minimal HTTP facade that exposes the morphology service via REST endpoints compatible with the
 * original Flask API.
 */
public class WebMorphologyApplication {

    private static final String CALLBACK_PARAM = "callback";
    private static final String SESSION_COOKIE = "uqu_session";

    private final MorphologyService service;
    private final ReaderWorkCatalog catalog;
    private final ReaderRepository repository;
    private final RhvoiceTtsService ttsService = new RhvoiceTtsService();
    private final Gson gson = new Gson();
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(
            Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors() * 2)),
            runnable -> {
                Thread thread = new Thread(runnable, "uqureader-http");
                thread.setDaemon(true);
                return thread;
            });
    private volatile long catalogSentenceCount = -1;
    private volatile long uniqueCatalogSentenceCount = -1;

    public WebMorphologyApplication(MorphologyService service) {
        this(service, loadCatalogOrEmpty(), new InMemoryReaderRepository());
    }

    public WebMorphologyApplication(MorphologyService service, ReaderWorkCatalog catalog, ReaderRepository repository) {
        this.service = service;
        this.catalog = catalog;
        this.repository = repository;
    }

    /**
     * Starts the HTTP server and returns it so callers may manage its lifecycle.
     *
     * @param port port to bind to. If {@code 0} a random free port will be used.
     * @return started {@link HttpServer}
     * @throws IOException when server creation fails
     */
    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handleRoot);
        server.createContext("/api/token", this::handleToken);
        server.createContext("/api/token/", this::handleToken);
        server.createContext("/api/text", this::handleText);
        server.createContext("/api/text/", this::handleText);
        server.createContext("/api/auth/register", this::handleRegister);
        server.createContext("/api/auth/login", this::handleLogin);
        server.createContext("/api/auth/logout", this::handleLogout);
        server.createContext("/api/auth/me", this::handleMe);
        server.createContext("/api/works", this::handleWorks);
        server.createContext("/api/works/", this::handleWork);
        server.createContext("/api/grammar", this::handleGrammar);
        server.createContext("/api/reading/events", this::handleReadingEvents);
        server.createContext("/api/reading/state", this::handleReadingState);
        server.createContext("/api/reading/stats", this::handleReadingStats);
        server.createContext("/api/tts/status", this::handleTtsStatus);
        server.createContext("/api/tts/cache/status", this::handleTtsCacheStatus);
        server.createContext("/api/tts/page", this::handleTtsPage);
        server.createContext("/api/tts/speech", this::handleTtsPage);
        server.createContext("/reader", this::handleReaderApp);
        server.createContext("/reader/", this::handleReaderApp);
        server.setExecutor(httpExecutor);
        server.start();
        warmupCatalogTts();
        return server;
    }

    public void close() {
        ttsService.close();
        httpExecutor.shutdownNow();
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET, POST");
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("morphan_version", service.getVersion());
            sendJson(exchange, 200, payload);
        } catch (MorphologyException ex) {
            sendServerError(exchange, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if ("GET".equals(method)) {
                handleTokenGet(exchange);
            } else if ("POST".equals(method)) {
                handleTokenPost(exchange);
            } else {
                sendMethodNotAllowed(exchange, "GET, POST");
            }
        } catch (MorphologyException ex) {
            sendServerError(exchange, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleTokenGet(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String base = "/api/token/";
        if (path.length() <= base.length()) {
            sendNotFound(exchange, path);
            return;
        }
        String encoded = path.substring(base.length());
        String token = urlDecode(encoded);
        JsonObject response = service.analyzeToken(token);
        sendJson(exchange, 200, response);
    }

    private void handleTokenPost(HttpExchange exchange) throws IOException {
        JsonObject request = readJsonBody(exchange);
        if (request == null || !request.has("token")) {
            sendForbidden(exchange, "request data doesn`t have `token` field");
            return;
        }
        String token = request.get("token").getAsString();
        JsonObject response = service.analyzeToken(token);
        sendJson(exchange, 200, response);
    }

    private void handleText(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "POST");
                return;
            }
            JsonObject request = readJsonBody(exchange);
            if (request == null || !request.has("text")) {
                sendForbidden(exchange, "request data doesn`t have `text` field");
                return;
            }
            String text = request.get("text").getAsString();
            JsonObject response = service.analyzeText(text);
            sendJson(exchange, 200, response);
        } catch (MorphologyException ex) {
            sendServerError(exchange, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "POST");
                return;
            }
            JsonObject request = readJsonBody(exchange);
            UserSession session = repository.register(getString(request, "username"), getString(request, "password"));
            sendSession(exchange, session);
        } catch (SQLException ex) {
            sendError(exchange, 400, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "POST");
                return;
            }
            JsonObject request = readJsonBody(exchange);
            UserSession session = repository.login(getString(request, "username"), getString(request, "password"));
            sendSession(exchange, session);
        } catch (SQLException ex) {
            sendError(exchange, 401, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "POST");
                return;
            }
            repository.logout(readSessionCookie(exchange));
            exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=/; Max-Age=0; SameSite=Lax");
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", true);
            sendJson(exchange, 200, payload);
        } catch (SQLException ex) {
            sendServerError(exchange, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleMe(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            Optional<UserSession> session = currentSession(exchange);
            JsonObject payload = new JsonObject();
            payload.addProperty("authenticated", session.isPresent());
            session.ifPresent(value -> {
                payload.addProperty("userId", value.userId);
                payload.addProperty("username", value.username);
            });
            sendJson(exchange, 200, payload);
        } catch (SQLException ex) {
            sendServerError(exchange, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleWorks(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            JsonArray works = new JsonArray();
            for (ReaderWork work : catalog.listWorks()) {
                JsonObject item = new JsonObject();
                item.addProperty("id", work.id);
                item.addProperty("title", work.title);
                item.addProperty("assetName", work.assetName);
                item.addProperty("tokenCount", work.tokenCount);
                item.addProperty("charCount", work.charCount);
                works.add(item);
            }
            JsonObject payload = new JsonObject();
            payload.add("works", works);
            sendJson(exchange, 200, payload);
        } finally {
            exchange.close();
        }
    }

    private void handleWork(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            String[] parts = exchange.getRequestURI().getPath().split("/");
            if (parts.length < 5 || !"tokens".equals(parts[4])) {
                sendNotFound(exchange, exchange.getRequestURI().getPath());
                return;
            }
            String workId = urlDecode(parts[3]);
            Optional<ReaderWork> work = catalog.find(workId);
            if (work.isEmpty()) {
                sendNotFound(exchange, workId);
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            int pageIndex = parseInt(query.get("page"), 0);
            int pageSize = parseInt(query.get("pageSize"), 450);
            List<ReaderToken> tokens = catalog.page(workId, pageIndex, pageSize);
            JsonObject payload = new JsonObject();
            payload.addProperty("workId", workId);
            payload.addProperty("title", work.get().title);
            payload.addProperty("pageIndex", pageIndex);
            payload.addProperty("pageSize", pageSize);
            payload.addProperty("tokenCount", work.get().tokenCount);
            payload.addProperty("hasNext", (pageIndex + 1) * pageSize < work.get().tokenCount);
            JsonArray array = new JsonArray();
            for (ReaderToken token : tokens) {
                array.add(gson.toJsonTree(token));
            }
            payload.add("tokens", array);
            sendJson(exchange, 200, payload);
        } finally {
            exchange.close();
        }
    }

    private void handleGrammar(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            JsonObject payload = new JsonObject();
            payload.add("pos", gson.toJsonTree(GrammarCatalog.pos()));
            payload.add("features", gson.toJsonTree(GrammarCatalog.features()));
            sendJson(exchange, 200, payload);
        } finally {
            exchange.close();
        }
    }

    private void handleReadingEvents(HttpExchange exchange) throws IOException {
        try {
            Optional<UserSession> session = currentSession(exchange);
            if (session.isEmpty()) {
                sendError(exchange, 401, "Authentication required");
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                List<ReadingEventRecord> events = repository.listLemmaEvents(
                        session.get().userId,
                        query.getOrDefault("lemma", ""),
                        query.getOrDefault("pos", ""),
                        query.getOrDefault("eventType", ""),
                        parseInt(query.get("limit"), 500));
                JsonObject payload = new JsonObject();
                payload.add("events", gson.toJsonTree(events));
                sendJson(exchange, 200, payload);
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonObject request = readJsonBody(exchange);
                List<ReadingEvent> events = parseEvents(request);
                int accepted = repository.recordEvents(session.get().userId, session.get().sessionToken, events);
                JsonObject payload = new JsonObject();
                payload.addProperty("received", events.size());
                payload.addProperty("accepted", accepted);
                payload.addProperty("duplicates", events.size() - accepted);
                sendJson(exchange, 200, payload);
            } else {
                sendMethodNotAllowed(exchange, "GET, POST");
            }
        } catch (SQLException ex) {
            sendServerError(exchange, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleReadingState(HttpExchange exchange) throws IOException {
        try {
            Optional<UserSession> session = currentSession(exchange);
            if (session.isEmpty()) {
                sendError(exchange, 401, "Authentication required");
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                Optional<ReadingState> state = repository.findReadingState(session.get().userId, query.getOrDefault("workId", ""));
                JsonObject payload = new JsonObject();
                if (state.isPresent()) {
                    payload.add("state", gson.toJsonTree(state.get()));
                } else {
                    payload.add("state", null);
                }
                sendJson(exchange, 200, payload);
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonObject request = readJsonBody(exchange);
                ReadingState state = new ReadingState(getString(request, "workId"),
                        getInt(request, "pageIndex", 0),
                        getInt(request, "charIndex", 0),
                        System.currentTimeMillis());
                repository.saveReadingState(session.get().userId, state);
                JsonObject payload = new JsonObject();
                payload.addProperty("ok", true);
                sendJson(exchange, 200, payload);
            } else {
                sendMethodNotAllowed(exchange, "GET, POST");
            }
        } catch (SQLException ex) {
            sendServerError(exchange, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleReadingStats(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            Optional<UserSession> session = currentSession(exchange);
            if (session.isEmpty()) {
                sendError(exchange, 401, "Authentication required");
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            List<LemmaStat> stats = repository.listLemmaStats(session.get().userId, parseInt(query.get("limit"), 100));
            JsonObject payload = new JsonObject();
            payload.add("lemmas", gson.toJsonTree(stats));
            payload.add("features", gson.toJsonTree(repository.listFeatureStats(session.get().userId, parseInt(query.get("limit"), 100))));
            sendJson(exchange, 200, payload);
        } catch (SQLException ex) {
            sendServerError(exchange, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleTtsStatus(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("configured", ttsService.isConfigured());
            payload.addProperty("voice", RhvoiceTtsService.TALGAT_VOICE);
            payload.addProperty("hardcodedVoice", true);
            sendJson(exchange, 200, payload);
        } finally {
            exchange.close();
        }
    }

    private void handleTtsCacheStatus(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            RhvoiceTtsService.CacheStatus status = ttsService.cacheStatus();
            JsonObject payload = new JsonObject();
            payload.addProperty("configured", ttsService.isConfigured());
            payload.addProperty("voice", RhvoiceTtsService.TALGAT_VOICE);
            payload.addProperty("cacheRoot", status.root().toString());
            payload.addProperty("wavFiles", status.wavFiles());
            payload.addProperty("warmupQueued", status.warmupQueued());
            payload.addProperty("warmupCompleted", status.warmupCompleted());
            payload.addProperty("warmupFailed", status.warmupFailed());
            payload.addProperty("foregroundRequests", status.foregroundRequests());
            payload.addProperty("expectedSentences", cachedCatalogSentenceCount());
            payload.addProperty("expectedUniqueSentences", cachedUniqueCatalogSentenceCount());
            payload.addProperty("warming", status.warmupQueued() > status.warmupCompleted() + status.warmupFailed());
            sendJson(exchange, 200, payload);
        } finally {
            exchange.close();
        }
    }

    private void handleTtsPage(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "POST");
                return;
            }
            if (!ttsService.isConfigured()) {
                sendError(exchange, 503, "RHVoice server command is not configured");
                return;
            }
            JsonObject request = readJsonBody(exchange);
            String text = getString(request, "text");
            String scope = getString(request, "scope");
            if (text.isBlank()) {
                String workId = getString(request, "workId");
                int pageIndex = getInt(request, "pageIndex", 0);
                int pageSize = getInt(request, "pageSize", 450);
                text = buildPageText(workId, pageIndex, pageSize);
                if (scope.isBlank()) {
                    scope = "page";
                }
            }
            boolean bypassCache = "token".equalsIgnoreCase(scope) || "word".equalsIgnoreCase(scope);
            byte[] audio;
            if (bypassCache) {
                audio = ttsService.synthesize(text);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Uqureader-Tts-Cache", "bypass");
            } else {
                RhvoiceTtsService.CachedAudio cached = ttsService.synthesizeCached(text);
                audio = cached.audio();
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
                exchange.getResponseHeaders().set("X-Uqureader-Tts-Cache", cached.cacheHit() ? "hit" : "miss");
            }
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, audio.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(audio);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            sendServerError(exchange, "RHVoice synthesis interrupted");
        } catch (IOException ex) {
            sendServerError(exchange, ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void warmupCatalogTts() {
        if (!ttsService.isConfigured()) {
            return;
        }
        Thread thread = new Thread(() -> {
            for (ReaderWork work : catalog.listWorks()) {
                int pageSize = 450;
                int pages = Math.max(1, (int) Math.ceil(work.tokenCount / (double) pageSize));
                for (int page = 0; page < pages; page++) {
                    warmupSentenceTts(catalog.page(work.id, page, pageSize));
                }
            }
        }, "uqureader-tts-catalog-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    private long countCatalogSentences() {
        long count = 0;
        for (ReaderWork work : catalog.listWorks()) {
            int pageSize = 450;
            int pages = Math.max(1, (int) Math.ceil(work.tokenCount / (double) pageSize));
            for (int page = 0; page < pages; page++) {
                count += buildSentenceTexts(catalog.page(work.id, page, pageSize)).size();
            }
        }
        return count;
    }

    private long cachedCatalogSentenceCount() {
        long count = catalogSentenceCount;
        if (count >= 0) {
            return count;
        }
        synchronized (this) {
            if (catalogSentenceCount < 0) {
                catalogSentenceCount = countCatalogSentences();
            }
            return catalogSentenceCount;
        }
    }

    private long cachedUniqueCatalogSentenceCount() {
        long count = uniqueCatalogSentenceCount;
        if (count >= 0) {
            return count;
        }
        synchronized (this) {
            if (uniqueCatalogSentenceCount < 0) {
                uniqueCatalogSentenceCount = countUniqueCatalogSentences();
            }
            return uniqueCatalogSentenceCount;
        }
    }

    private long countUniqueCatalogSentences() {
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (ReaderWork work : catalog.listWorks()) {
            int pageSize = 450;
            int pages = Math.max(1, (int) Math.ceil(work.tokenCount / (double) pageSize));
            for (int page = 0; page < pages; page++) {
                for (String sentence : buildSentenceTexts(catalog.page(work.id, page, pageSize))) {
                    String key = ttsService.cacheKeyForStatus(sentence);
                    if (!key.isEmpty()) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys.size();
    }

    private void warmupSentenceTts(List<ReaderToken> tokens) {
        if (!ttsService.isConfigured() || tokens == null || tokens.isEmpty()) {
            return;
        }
        for (String sentence : buildSentenceTexts(tokens)) {
            ttsService.warmupCached(sentence);
        }
    }

    private List<String> buildSentenceTexts(List<ReaderToken> tokens) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean pendingEnd = false;
        for (ReaderToken token : tokens) {
            if (pendingEnd && !isClosingPunctuation(token.surface)) {
                addSentence(sentences, current);
                pendingEnd = false;
            }
            current.append(token.prefix).append(token.surface);
            String surface = token.surface == null ? "" : token.surface;
            if (pendingEnd && isClosingPunctuation(surface)) {
                addSentence(sentences, current);
                pendingEnd = false;
            } else if (isSentenceEnding(surface)) {
                pendingEnd = true;
            } else if (current.length() >= 420) {
                addSentence(sentences, current);
                pendingEnd = false;
            }
        }
        addSentence(sentences, current);
        return sentences;
    }

    private boolean isSentenceEnding(String surface) {
        return surface != null && surface.matches(".*[.!?…]+$");
    }

    private boolean isClosingPunctuation(String surface) {
        return surface != null && surface.matches("[)\\]}»”’]+");
    }

    private void addSentence(List<String> sentences, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            sentences.add(value);
        }
        current.setLength(0);
    }

    private String buildPageText(String workId, int pageIndex, int pageSize) {
        StringBuilder builder = new StringBuilder();
        for (ReaderToken token : catalog.page(workId, pageIndex, pageSize)) {
            builder.append(token.prefix).append(token.surface);
        }
        return builder.toString();
    }

    private void handleReaderApp(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange, "GET");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/app.js")) {
                sendText(exchange, 200, "application/javascript; charset=utf-8", StaticReaderAssets.APP_JS);
            } else if (path.endsWith("/style.css")) {
                sendText(exchange, 200, "text/css; charset=utf-8", StaticReaderAssets.STYLE_CSS);
            } else {
                sendText(exchange, 200, "text/html; charset=utf-8", StaticReaderAssets.INDEX_HTML);
            }
        } finally {
            exchange.close();
        }
    }

    private JsonObject readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody();
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            String body = new java.io.BufferedReader(reader)
                    .lines()
                    .collect(Collectors.joining());
            if (body.isEmpty()) {
                return null;
            }
            return gson.fromJson(body, JsonObject.class);
        }
    }

    private void sendSession(HttpExchange exchange, UserSession session) throws IOException {
        long maxAge = Math.max(0, (session.expiresAtMs - System.currentTimeMillis()) / 1000);
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + session.sessionToken
                + "; Path=/; Max-Age=" + maxAge + "; HttpOnly; SameSite=Lax");
        JsonObject payload = new JsonObject();
        payload.addProperty("userId", session.userId);
        payload.addProperty("username", session.username);
        payload.addProperty("expiresAtMs", session.expiresAtMs);
        sendJson(exchange, 200, payload);
    }

    private Optional<UserSession> currentSession(HttpExchange exchange) throws SQLException {
        return repository.findSession(readSessionCookie(exchange));
    }

    private String readSessionCookie(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return "";
        }
        for (String cookieHeader : cookies) {
            String[] parts = cookieHeader.split(";");
            for (String part : parts) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && SESSION_COOKIE.equals(pair[0])) {
                    return pair[1];
                }
            }
        }
        return "";
    }

    private List<ReadingEvent> parseEvents(JsonObject request) {
        if (request == null || !request.has("events") || !request.get("events").isJsonArray()) {
            return List.of();
        }
        List<ReadingEvent> events = new ArrayList<>();
        JsonArray array = request.getAsJsonArray("events");
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String clientEventId = ensureUuid(getString(object, "clientEventId"));
            events.add(new ReadingEvent(
                    clientEventId,
                    getString(object, "eventType"),
                    getString(object, "workId"),
                    getInt(object, "pageIndex", -1),
                    getInt(object, "tokenIndex", -1),
                    getString(object, "lemma"),
                    getString(object, "pos"),
                    getString(object, "featureKey"),
                    getInt(object, "charIndex", -1),
                    getInt(object, "visibleMs", 0),
                    getLong(object, "occurredAtMs", System.currentTimeMillis())));
        }
        return events;
    }

    private String ensureUuid(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return UUID.fromString(value).toString();
            } catch (IllegalArgumentException ignored) {
                // Replace malformed client IDs so one bad event does not poison the whole batch.
            }
        }
        return UUID.randomUUID().toString();
    }

    private Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(arr -> arr.length == 2)
                .collect(Collectors.toMap(arr -> urlDecode(arr[0]), arr -> urlDecode(arr[1]), (a, b) -> b));
    }

    private String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private int getInt(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private long getLong(JsonObject object, String key, long fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void sendJson(HttpExchange exchange, int status, JsonElement payload) throws IOException {
        String callback = extractCallback(exchange.getRequestURI().getRawQuery());
        String body;
        Headers headers = exchange.getResponseHeaders();
        if (callback != null) {
            body = callback + "(" + gson.toJson(payload) + ")";
            headers.set("Content-Type", "application/javascript; charset=utf-8");
        } else {
            body = gson.toJson(payload);
            headers.set("Content-Type", "application/json; charset=utf-8");
        }
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private void sendText(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private void sendMethodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendError(exchange, 405, "Method Not Allowed");
    }

    private void sendForbidden(HttpExchange exchange, String message) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", 403);
        payload.addProperty("message", message);
        sendJson(exchange, 403, payload);
    }

    private void sendNotFound(HttpExchange exchange, String path) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", 404);
        payload.addProperty("message", "Not Found: " + path);
        sendJson(exchange, 404, payload);
    }

    private void sendServerError(HttpExchange exchange, String message) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", 500);
        payload.addProperty("message", message);
        sendJson(exchange, 500, payload);
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", status);
        payload.addProperty("message", message);
        sendJson(exchange, status, payload);
    }

    private String extractCallback(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        Map<String, String> params = java.util.Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(arr -> arr.length == 2)
                .collect(Collectors.toMap(arr -> urlDecode(arr[0]), arr -> urlDecode(arr[1]), (a, b) -> b));
        return Optional.ofNullable(params.get(CALLBACK_PARAM))
                .filter(value -> !value.isEmpty())
                .orElse(null);
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static ReaderWorkCatalog loadCatalogOrEmpty() {
        try {
            return ReaderWorkCatalog.loadDefault();
        } catch (IOException ex) {
            try {
                return ReaderWorkCatalog.loadDefault();
            } catch (IOException ignored) {
                throw new IllegalStateException("Unable to load reader catalog", ex);
            }
        }
    }
}
