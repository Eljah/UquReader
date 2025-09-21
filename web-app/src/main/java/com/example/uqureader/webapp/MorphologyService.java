package com.example.uqureader.webapp;

import com.google.gson.JsonObject;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Provides access to the Tatar morphological analyser via a persistent Python bridge.
 */
public class MorphologyService implements Closeable {

    private final PythonBridge bridge;
    private final String version;
    private final ConcurrentMap<String, JsonObject> tokenCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, JsonObject> textCache = new ConcurrentHashMap<>();

    public MorphologyService() {
        this(new PythonBridge());
    }

    MorphologyService(PythonBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.version = extractVersion(bridge.requestVersion());
    }

    private String extractVersion(JsonObject response) {
        if (response == null || !response.has("version")) {
            throw new MorphologyException("Python bridge did not report a version");
        }
        return response.get("version").getAsString();
    }

    public String getVersion() {
        return version;
    }

    public JsonObject analyzeToken(String token) {
        String key = token == null ? "" : token;
        JsonObject response = tokenCache.computeIfAbsent(key, bridge::analyzeToken);
        return response.deepCopy();
    }

    public JsonObject analyzeText(String text) {
        String key = text == null ? "" : text;
        JsonObject response = textCache.computeIfAbsent(key, bridge::analyzeText);
        return response.deepCopy();
    }

    @Override
    public void close() {
        bridge.close();
    }
}
