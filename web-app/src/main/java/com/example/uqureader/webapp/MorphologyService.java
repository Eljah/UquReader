package com.example.uqureader.webapp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Provides morphological markup responses for incoming texts.
 * <p>
 *     For now this service returns a precomputed placeholder produced
 *     by the original Python analyser for "Berenche teatr".
 * </p>
 */
public class MorphologyService {

    private final String markup;

    public MorphologyService() {
        this.markup = loadMarkup();
    }

    private String loadMarkup() {
        final String resourcePath = "/markup/berenche_teatr_markup.txt";
        try (InputStream stream = MorphologyService.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing placeholder markup resource: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read placeholder markup", ex);
        }
    }

    /**
     * Returns the placeholder markup regardless of the provided text.
     *
     * @param text ignored for now; kept for future integration with the real analyser
     * @return precomputed markup of the reference play
     */
    public String analyze(String text) {
        return markup;
    }

    /**
     * Exposes the cached markup string for tests and the CLI entry point.
     */
    public String getMarkup() {
        return markup;
    }
}
