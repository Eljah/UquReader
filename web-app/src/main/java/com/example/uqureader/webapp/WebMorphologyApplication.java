package com.example.uqureader.webapp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Minimal HTTP facade that exposes the morphology service via REST endpoints compatible with the
 * original Flask API.
 */
public class WebMorphologyApplication {

    private static final String CALLBACK_PARAM = "callback";

    private final MorphologyService service;
    private final Gson gson = new Gson();

    public WebMorphologyApplication(MorphologyService service) {
        this.service = service;
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
        server.setExecutor(null); // use the default executor
        server.start();
        return server;
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
}
