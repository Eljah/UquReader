package com.example.uqureader.webapp.cli;

import com.example.uqureader.webapp.MorphologyException;
import com.example.uqureader.webapp.morphology.MorphologyAnalyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Simple console application that exposes the {@link MorphologyAnalyzer} via STDIN/STDOUT.
 * The program accepts arbitrary text input, performs a lookup against the HFST transducer
 * bundled with the web module and prints the resulting token markup to the console.
 */
public final class MorphologyConsoleApplication {

    private MorphologyConsoleApplication() {
        // Prevent instantiation
    }

    public static void main(String[] args) {
        MorphologyAnalyzer analyzer = MorphologyAnalyzer.loadDefault();
        String input = readInput(args);
        try {
            MorphologyAnalyzer.TextAnalysis analysis = analyzer.analyze(input);
            System.out.println(analysis.markup());
        } catch (MorphologyException ex) {
            System.err.println("Morphology analysis failed: " + ex.getMessage());
            if (ex.getCause() != null) {
                ex.getCause().printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    private static String readInput(String[] args) {
        if (args != null && args.length > 0) {
            return Arrays.stream(args).collect(Collectors.joining(" "));
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    builder.append(System.lineSeparator());
                }
                builder.append(line);
                first = false;
            }
            return builder.toString();
        } catch (IOException ex) {
            throw new MorphologyException("Failed to read input", ex);
        }
    }
}
