package com.example.uqureader.webapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class MainTest {

    @Test
    void mainPrintsPlaceholderMarkup() throws Exception {
        MorphologyService service = new MorphologyService();
        String expected = service.getMarkup();

        PrintStream originalOut = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        try (PrintStream replacement = new PrintStream(capture, true, StandardCharsets.UTF_8.name())) {
            System.setOut(replacement);
            Main.main(new String[0]);
        } finally {
            System.setOut(originalOut);
        }

        String actual = capture.toString(StandardCharsets.UTF_8.name());
        assertEquals(expected, actual);
    }

    @Test
    void httpEndpointReturnsMarkup() throws IOException {
        MorphologyService service = new MorphologyService();
        WebMorphologyApplication application = new WebMorphologyApplication(service);
        HttpServer server = application.start(0);
        try {
            int port = server.getAddress().getPort();
            HttpURLConnection connection = (HttpURLConnection) new URL("http://localhost:" + port + "/analyze").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write("ignored".getBytes(StandardCharsets.UTF_8));
                output.flush();
            }

            String response;
            try (InputStream stream = connection.getInputStream()) {
                response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals(200, connection.getResponseCode());
            assertEquals(service.getMarkup(), response);
        } finally {
            server.stop(0);
        }
    }
}
