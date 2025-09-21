package com.example.uqureader.webapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

class MainTest {

    @Test
    void mainPrintsPlaceholderMarkup() throws Exception {
        String sampleText = "Комедия пәрдәдә";
        MorphologyService service = new MorphologyService();
        JsonObject expected = service.analyzeText(sampleText);

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
            service.close();
        }

        String actual = capture.toString(StandardCharsets.UTF_8.name());
        assertEquals(expected.toString(), actual);
    }

    @Test
    void httpEndpointReturnsMarkup() throws IOException {
        String sampleText = "Комедия пәрдәдә";
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
                String payload = new Gson().toJson(java.util.Collections.singletonMap("text", sampleText));
                output.write(payload.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }

            String response;
            try (InputStream stream = connection.getInputStream()) {
                response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals(200, connection.getResponseCode());
            JsonObject expected = service.analyzeText(sampleText);
            assertEquals(expected, new Gson().fromJson(response, JsonObject.class));
        } finally {
            server.stop(0);
            service.close();
        }
    }
}
