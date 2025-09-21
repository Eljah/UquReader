package com.example.uqureader.webapp;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Entry point for the web module.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--serve".equals(args[0])) {
            MorphologyService service = new MorphologyService();
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
            WebMorphologyApplication application = new WebMorphologyApplication(service);
            HttpServer server = application.start(port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop(0);
                service.close();
            }));
            System.out.printf("Server started on port %d%n", server.getAddress().getPort());
            System.out.flush();
        } else {
            try (MorphologyService service = new MorphologyService()) {
                String input = readAll(System.in);
                if (input.isEmpty()) {
                    System.err.println("Provide text via STDIN or run with --serve [port].");
                    return;
                }
                JsonObject result = service.analyzeText(input);
                System.out.print(result);
            }
        }
    }

    private static String readAll(java.io.InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }
}
