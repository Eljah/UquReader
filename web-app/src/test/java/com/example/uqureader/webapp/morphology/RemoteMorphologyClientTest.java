package com.example.uqureader.webapp.morphology;

import com.example.uqureader.webapp.morphology.RemoteMorphologyClient.WordMarkup;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

class RemoteMorphologyClientTest {

//    private static final Gson GSON = new Gson();
//
//    private HttpServer server;
//    private URI endpoint;
//
//    @BeforeEach
//    void setUp() throws IOException {
//        server = HttpServer.create(new InetSocketAddress(0), 0);
//        endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/new2022/morph/ajax.php");
//    }
//
//    @AfterEach
//    void tearDown() {
//        if (server != null) {
//            server.stop(0);
//        }
//    }
//
//    @Test
//    void analyzeWordReturnsRemoteMarkup() {
//        server.createContext("/new2022/morph/ajax.php", exchange -> {
//            Map<String, String> params = readForm(exchange);
//            Assertions.assertEquals("Комедия", params.get("word"));
//            WordMarkup response = new WordMarkup("Комедия", Collections.singletonList("комедия+N+Sg+Nom"));
//            writeJson(exchange, response);
//        });
//        server.start();
//
//        RemoteMorphologyClient client = new RemoteMorphologyClient(HttpClient.newHttpClient(), endpoint, 500);
//        WordMarkup markup = client.analyzeWord("Комедия");
//
//        Assertions.assertEquals("Комедия", markup.word());
//        Assertions.assertEquals(Collections.singletonList("комедия+N+Sg+Nom"), markup.analyses());
//    }
//
//    @Test
//    void analyzeTextSplitsRequestsIntoBatches() {
//        ConcurrentLinkedQueue<String> receivedBatches = new ConcurrentLinkedQueue<>();
//        AtomicInteger counter = new AtomicInteger();
//        server.createContext("/new2022/morph/ajax.php", exchange -> {
//            Map<String, String> params = readForm(exchange);
//            String text = params.get("text");
//            receivedBatches.add(text);
//            int index = counter.getAndIncrement();
//            WordMarkup response = new WordMarkup("batch" + index, Collections.singletonList("analysis" + index));
//            writeJson(exchange, Collections.singletonList(response));
//        });
//        server.start();
//
//        RemoteMorphologyClient client = new RemoteMorphologyClient(HttpClient.newHttpClient(), endpoint, 30);
//        String text = "Бер җөмлә тәмам. Икенче җөмлә тәмам! Өченче җөмлә дә тәмам?";
//
//        List<WordMarkup> tokens = client.analyzeText(text);
//
//        List<String> expectedBatches = client.splitIntoBatches(text);
//        Assertions.assertEquals(expectedBatches, new ArrayList<>(receivedBatches));
//        Assertions.assertEquals(counter.get(), tokens.size());
//        Assertions.assertEquals("batch0", tokens.get(0).word());
//        Assertions.assertEquals("analysis0", tokens.get(0).analyses().get(0));
//    }
//
//    @Test
//    void splitIntoBatchesSplitsOversizedSentence() {
//        String fragment = "озын җөмлә";
//        String longSentence = String.join(" ", Collections.nCopies(80, fragment));
//        RemoteMorphologyClient client = new RemoteMorphologyClient(HttpClient.newHttpClient(),
//                URI.create("http://localhost:1/new2022/morph/ajax.php"), 120);
//
//        List<String> batches = client.splitIntoBatches(longSentence);
//
//        Assertions.assertTrue(batches.size() > 1);
//        Assertions.assertTrue(batches.stream().allMatch(batch -> batch.length() <= 120));
//
//        List<String> originalTokens = Arrays.stream(longSentence.trim().split("\\s+")).collect(Collectors.toList());
//        List<String> resultingTokens = batches.stream()
//                .flatMap(part -> Arrays.stream(part.split("\\s+")))
//                .filter(token -> !token.isBlank())
//                .collect(Collectors.toList());
//
//        Assertions.assertEquals(originalTokens, resultingTokens);
//    }
//
//    private Map<String, String> readForm(HttpExchange exchange) throws IOException {
//        Assertions.assertEquals("POST", exchange.getRequestMethod().toUpperCase(Locale.ROOT));
//        byte[] body = exchange.getRequestBody().readAllBytes();
//        String decoded = URLDecoder.decode(new String(body, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
//        String[] pairs = decoded.split("&");
//        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
//        for (String pair : pairs) {
//            if (pair.isEmpty()) {
//                continue;
//            }
//            String[] kv = pair.split("=", 2);
//            String key = kv.length > 0 ? kv[0] : "";
//            String value = kv.length > 1 ? kv[1] : "";
//            map.put(key, value);
//        }
//        return map;
//    }
//
//    private void writeJson(HttpExchange exchange, Object payload) throws IOException {
//        byte[] data = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
//        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
//        exchange.sendResponseHeaders(200, data.length);
//        try (OutputStream output = exchange.getResponseBody()) {
//            output.write(data);
//        }
//    }
}

