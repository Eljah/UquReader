package com.example.uqureader.webapp;

import com.example.uqureader.webapp.reader.InMemoryReaderRepository;
import com.example.uqureader.webapp.reader.ReaderWorkCatalog;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

class ReaderWebApplicationTest {
    private final Gson gson = new Gson();

    @Test
    void recordsReadingEventsIdempotentlyAndExposesLemmaStats() throws Exception {
        InMemoryReaderRepository repository = new InMemoryReaderRepository();
        WebMorphologyApplication application = new WebMorphologyApplication(
                new MorphologyService(),
                ReaderWorkCatalog.loadDefault(),
                repository);
        HttpServer server = application.start(0);
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            Response auth = post(base + "/api/auth/register", json("""
                    {"username":"reader_api_test","password":"secret123"}
                    """), null);
            String cookie = auth.cookie;
            Assertions.assertEquals(200, auth.status);

            JsonObject works = get(base + "/api/works", cookie).json;
            String workId = works.getAsJsonArray("works").get(0).getAsJsonObject().get("id").getAsString();
            JsonObject page = get(base + "/api/works/" + workId + "/tokens?page=0&pageSize=8", cookie).json;
            JsonArray tokens = page.getAsJsonArray("tokens");
            Assertions.assertEquals(8, tokens.size());

            JsonObject firstMorphToken = null;
            for (int i = 0; i < tokens.size(); i++) {
                JsonObject token = tokens.get(i).getAsJsonObject();
                if (token.has("morphology") && !token.get("morphology").isJsonNull()) {
                    firstMorphToken = token;
                    break;
                }
            }
            Assertions.assertNotNull(firstMorphToken);

            JsonObject morphology = firstMorphToken.getAsJsonObject("morphology");
            String eventId = UUID.randomUUID().toString();
            JsonObject event = new JsonObject();
            event.addProperty("clientEventId", eventId);
            event.addProperty("eventType", "token_committed");
            event.addProperty("workId", workId);
            event.addProperty("pageIndex", 0);
            event.addProperty("tokenIndex", firstMorphToken.get("index").getAsInt());
            event.addProperty("lemma", morphology.get("lemma").getAsString());
            event.addProperty("pos", morphology.get("pos").getAsString());
            event.addProperty("featureKey", morphology.get("featureKey").getAsString());
            event.addProperty("charIndex", firstMorphToken.get("charStart").getAsInt());
            event.addProperty("visibleMs", 1200);
            event.addProperty("occurredAtMs", System.currentTimeMillis());
            JsonObject payload = new JsonObject();
            JsonArray events = new JsonArray();
            events.add(event);
            payload.add("events", events);

            JsonObject first = post(base + "/api/reading/events", payload, cookie).json;
            JsonObject second = post(base + "/api/reading/events", payload, cookie).json;
            Assertions.assertEquals(1, first.get("accepted").getAsInt());
            Assertions.assertEquals(0, second.get("accepted").getAsInt());
            Assertions.assertEquals(1, second.get("duplicates").getAsInt());

            JsonArray stats = get(base + "/api/reading/stats?limit=10", cookie).json.getAsJsonArray("lemmas");
            Assertions.assertFalse(stats.isEmpty());
            Assertions.assertEquals(morphology.get("lemma").getAsString(), stats.get(0).getAsJsonObject().get("lemma").getAsString());
            JsonArray featureStats = get(base + "/api/reading/stats?limit=10", cookie).json.getAsJsonArray("features");
            Assertions.assertFalse(featureStats.isEmpty());
            JsonObject timeline = get(base + "/api/reading/events?lemma="
                    + java.net.URLEncoder.encode(morphology.get("lemma").getAsString(), java.nio.charset.StandardCharsets.UTF_8)
                    + "&pos="
                    + java.net.URLEncoder.encode(morphology.get("pos").getAsString(), java.nio.charset.StandardCharsets.UTF_8)
                    + "&limit=50", cookie).json;
            Assertions.assertEquals(1, timeline.getAsJsonArray("events").size());

            JsonObject tts = get(base + "/api/tts/status", cookie).json;
            Assertions.assertTrue(tts.has("configured"));
            Assertions.assertEquals("Talgat", tts.get("voice").getAsString());
            Assertions.assertTrue(tts.get("hardcodedVoice").getAsBoolean());
        } finally {
            server.stop(0);
            repository.close();
        }
    }

    private JsonObject json(String source) {
        return gson.fromJson(source, JsonObject.class);
    }

    private Response get(String url, String cookie) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        return read(connection);
    }

    private Response post(String url, JsonObject payload, String cookie) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        try (OutputStream output = connection.getOutputStream()) {
            output.write(gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
        }
        return read(connection);
    }

    private Response read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        String cookie = connection.getHeaderField("Set-Cookie");
        return new Response(status, body.isEmpty() ? new JsonObject() : gson.fromJson(body, JsonObject.class), cookie);
    }

    private static final class Response {
        final int status;
        final JsonObject json;
        final String cookie;

        Response(int status, JsonObject json, String cookie) {
            this.status = status;
            this.json = json;
            this.cookie = cookie;
        }
    }
}
