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
        if (args != null && args.length > 0) {
            processInput(analyzer, Arrays.stream(args).collect(Collectors.joining(" ")));
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processInput(analyzer, line);
            }
        } catch (IOException ex) {
            throw new MorphologyException("Failed to read input", ex);
        }
    }

    private static void processInput(MorphologyAnalyzer analyzer, String input) {
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
}
