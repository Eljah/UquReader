package com.example.uqureader.webapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal HTTP facade that exposes the morphology placeholder via REST.
 */
public class WebMorphologyApplication {

    private final MorphologyService service;

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
        server.createContext("/analyze", this::handleAnalyze);
        server.setExecutor(null); // use the default executor
        server.start();
        return server;
    }

    private void handleAnalyze(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            consumeRequestBody(exchange);

            byte[] response = service.getMarkup().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        } finally {
            exchange.close();
        }
    }

    private void consumeRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            if (input == null) {
                return;
            }
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) {
                // Consume the stream to allow clients to reuse connections.
            }
        }
    }
}
