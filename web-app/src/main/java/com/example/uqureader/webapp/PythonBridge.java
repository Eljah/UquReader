package com.example.uqureader.webapp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight bridge that keeps a Python interpreter process alive and proxies requests to it.
 */
public final class PythonBridge implements Closeable {

    private static final String RESOURCE_PATH = "/python/morphology_bridge.py";

    private final Gson gson = new Gson();
    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object lock = new Object();

    public PythonBridge() {
        this(resolvePythonExecutable());
    }

    PythonBridge(String pythonExecutable) {
        Objects.requireNonNull(pythonExecutable, "pythonExecutable");
        try {
            Path script = extractBridgeScript();
            ProcessBuilder builder = new ProcessBuilder(pythonExecutable, script.toAbsolutePath().toString());
            builder.redirectErrorStream(false);
            builder.environment().putIfAbsent("PYTHONIOENCODING", "UTF-8");
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            startErrorDrainer();
        } catch (IOException ex) {
            throw new MorphologyException("Failed to start Python bridge", ex);
        }
    }

    private void startErrorDrainer() {
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    System.err.println("[py-tat-morphan] " + line);
                }
            } catch (IOException ignored) {
                // Ignored on shutdown.
            }
        }, "python-morphology-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private Path extractBridgeScript() throws IOException {
        try (InputStream resource = PythonBridge.class.getResourceAsStream(RESOURCE_PATH)) {
            if (resource == null) {
                throw new IOException("Missing bridge resource: " + RESOURCE_PATH);
            }
            Path script = Files.createTempFile("morphology_bridge", ".py");
            script.toFile().deleteOnExit();
            Files.copy(resource, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return script;
        }
    }

    private static String resolvePythonExecutable() {
        String property = System.getProperty("py.tat.morphan.python");
        if (property != null && !property.isEmpty()) {
            return property;
        }
        String env = System.getenv("PY_TAT_MORPHAN_PYTHON");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return "python3";
    }

    public JsonObject requestVersion() {
        JsonObject payload = new JsonObject();
        payload.addProperty("cmd", "version");
        return sendRequest(payload);
    }

    public JsonObject analyzeToken(String token) {
        JsonObject payload = new JsonObject();
        payload.addProperty("cmd", "token");
        payload.addProperty("token", token == null ? "" : token);
        return sendRequest(payload);
    }

    public JsonObject analyzeText(String text) {
        JsonObject payload = new JsonObject();
        payload.addProperty("cmd", "text");
        payload.addProperty("text", text == null ? "" : text);
        return sendRequest(payload);
    }

    private JsonObject sendRequest(JsonObject payload) {
        Objects.requireNonNull(payload, "payload");
        synchronized (lock) {
            ensureOpen();
            try {
                writer.write(gson.toJson(payload));
                writer.write('\n');
                writer.flush();
                String responseLine = reader.readLine();
                if (responseLine == null) {
                    throw new MorphologyException("Python bridge terminated unexpectedly");
                }
                JsonElement element = gson.fromJson(responseLine, JsonElement.class);
                if (!element.isJsonObject()) {
                    throw new MorphologyException("Unexpected response: " + responseLine);
                }
                JsonObject response = element.getAsJsonObject();
                if (response.has("error")) {
                    String message = response.has("message") ? response.get("message").getAsString() : "Unknown error";
                    throw new MorphologyException(message);
                }
                return response;
            } catch (IOException ex) {
                throw new MorphologyException("Failed to communicate with Python bridge", ex);
            }
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new MorphologyException("Python bridge already closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("cmd", "shutdown");
            synchronized (lock) {
                try {
                    writer.write(gson.toJson(payload));
                    writer.write('\n');
                    writer.flush();
                } catch (IOException ignored) {
                }
            }
        } finally {
            process.destroy();
        }
    }
}
