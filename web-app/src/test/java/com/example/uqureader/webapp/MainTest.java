package com.example.uqureader.webapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

class MainTest {

    private final Gson gson = new Gson();

    @Test
    void mainPrintsMarkupForFullText() throws Exception {
        String sampleText = readResource("/texts/berenche_teatr.txt");
        String expectedMarkup = readResource("/markup/berenche_teatr.txt.morph.tsv");

        PrintStream originalOut = System.out;
        InputStream originalIn = System.in;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        try (ByteArrayInputStream input = new ByteArrayInputStream(sampleText.getBytes(StandardCharsets.UTF_8));
             PrintStream replacement = new PrintStream(capture, true, StandardCharsets.UTF_8.name())) {
            System.setIn(input);
            System.setOut(replacement);
            Main.main(new String[0]);
        } finally {
            System.setOut(originalOut);
            System.setIn(originalIn);
        }

        String actual = capture.toString(StandardCharsets.UTF_8.name());
        assertEquals(expectedMarkup, actual);
    }

    @Test
    void httpEndpointReturnsMarkupForFullText() throws IOException {
        String sampleText = readResource("/texts/berenche_teatr.txt");
        MorphologyService service = new MorphologyService();
        WebMorphologyApplication application = new WebMorphologyApplication(service);
        HttpServer server = application.start(0);
        try {
            int port = server.getAddress().getPort();
            HttpURLConnection connection = (HttpURLConnection) new URL("http://localhost:" + port + "/api/text/").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                String payload = gson.toJson(Map.of("text", sampleText));
                output.write(payload.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }

            String response;
            try (InputStream stream = connection.getInputStream()) {
                response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals(200, connection.getResponseCode());
            JsonObject expected = service.analyzeText(sampleText);
            assertEquals(expected, gson.fromJson(response, JsonObject.class));
        } finally {
            server.stop(0);
            service.close();
        }
    }

    private String readResource(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing resource: " + path);
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new BufferedReader(reader).lines().collect(Collectors.joining("\n"));
            }
        }
    }

    private static class BufferedReader extends java.io.BufferedReader {
        BufferedReader(InputStreamReader reader) {
            super(reader);
        }
    }
}
