package com.example.uqureader.webapp.cli;

import com.example.uqureader.webapp.MorphologyException;
import com.example.uqureader.webapp.morphology.MorphologyAnalyzer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Console utility that compares two HFST transducers against the same input texts and writes
 * a detailed coverage report to a log file. The application is meant to automate manual
 * comparisons performed earlier during development.
 */
public final class TransducerComparisonApplication {

    private static final List<BundledTransducer> BUNDLED_TRANSDUCERS = List.of(
            new BundledTransducer("analyser-gt-desc", "/analyser-gt-desc.hfstol"),
            new BundledTransducer("tat-automorf", "/tat.automorf.hfstol"),
            new BundledTransducer("tatar-last", "/tatar_last.hfstol")
    );

    private static final Map<String, BundledTransducer> BUNDLED_TRANSDUCER_LOOKUP =
            createBundledTransducerLookup();

    private static final Set<String> STRUCTURAL_TAGS = Set.of(
            "NL", "Type1", "Type2", "Type3", "Type4", "Latin", "Sign"
    );

    private TransducerComparisonApplication() {
    }

    public static void main(String[] args) {
        if (args == null || args.length < 5) {
            printUsage();
            System.exit(1);
        }

        List<ResolvedTransducer> transducers = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            transducers.add(resolveTransducer(args[i]));
        }

        Path output = Path.of(args[3]);
        List<Path> texts = new ArrayList<>();
        for (int i = 4; i < args.length; i++) {
            texts.add(Path.of(args[i]));
        }
        if (texts.isEmpty()) {
            printUsage();
            System.exit(1);
        }

        try {
            List<LoadedTransducer> loadedTransducers = new ArrayList<>(transducers.size());
            for (ResolvedTransducer transducer : transducers) {
                MorphologyAnalyzer analyzer = MorphologyAnalyzer.load(transducer.path());
                loadedTransducers.add(new LoadedTransducer(transducer, analyzer));
            }
            writeComparisonLog(loadedTransducers, output, texts);
            System.out.println("Comparison results written to " + output.toAbsolutePath());
        } catch (MorphologyException | IOException ex) {
            System.err.println("Failed to run transducer comparison: " + ex.getMessage());
            Throwable cause = ex.getCause();
            if (cause != null) {
                cause.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    private static void writeComparisonLog(List<LoadedTransducer> transducers,
                                           Path output,
                                           List<Path> texts) throws IOException {
        Objects.requireNonNull(output, "output");
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        for (Path text : texts) {
            if (!Files.isRegularFile(text)) {
                throw new MorphologyException("Text file not found: " + text.toAbsolutePath());
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Transducer comparison log").append(System.lineSeparator());
        builder.append("Generated: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .append(System.lineSeparator());
        builder.append("Transducers:").append(System.lineSeparator());
        for (LoadedTransducer transducer : transducers) {
            builder.append("  - ")
                    .append(transducer.resolved().label())
                    .append(": ")
                    .append(transducer.resolved().path().toAbsolutePath())
                    .append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());

        for (int i = 0; i < transducers.size(); i++) {
            for (int j = i + 1; j < transducers.size(); j++) {
                appendPairComparison(builder, transducers.get(i), transducers.get(j), texts);
                builder.append(System.lineSeparator());
            }
        }

        Files.writeString(output, builder.toString(), StandardCharsets.UTF_8);
    }

    private static void appendPairComparison(StringBuilder builder,
                                             LoadedTransducer first,
                                             LoadedTransducer second,
                                             List<Path> texts) throws IOException {
        String labelA = first.resolved().label();
        String labelB = second.resolved().label();

        builder.append(String.format(Locale.ROOT, "=== Comparison: %s vs %s%n", labelA, labelB));
        builder.append("First transducer: ")
                .append(first.resolved().path().toAbsolutePath())
                .append(System.lineSeparator());
        builder.append("Second transducer: ")
                .append(second.resolved().path().toAbsolutePath())
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        for (Path text : texts) {
            builder.append("--- Text: ")
                    .append(text.toAbsolutePath())
                    .append(System.lineSeparator());

            String content = Files.readString(text, StandardCharsets.UTF_8);
            AnalysisSummary firstSummary = summarise(first.analyzer(), content);
            AnalysisSummary secondSummary = summarise(second.analyzer(), content);

            appendSummary(builder, labelA, firstSummary);
            appendSummary(builder, labelB, secondSummary);

            appendDifferences(builder, labelA, firstSummary, labelB, secondSummary);
            builder.append(System.lineSeparator());
        }
    }

    private static ResolvedTransducer resolveTransducer(String argument) {
        if (argument == null || argument.isBlank()) {
            throw new MorphologyException("Transducer argument must not be blank");
        }

        BundledTransducer bundled = BUNDLED_TRANSDUCER_LOOKUP.get(argument.toLowerCase(Locale.ROOT));
        if (bundled != null) {
            Path path = resolveBundledTransducer(bundled);
            return new ResolvedTransducer(bundled.alias(), path);
        }

        Path path = Path.of(argument);
        if (!Files.isRegularFile(path)) {
            throw new MorphologyException("Morphology transducer not found: " + path.toAbsolutePath());
        }
        String label = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        return new ResolvedTransducer(label, path);
    }

    private static Path resolveBundledTransducer(BundledTransducer transducer) {
        URL resource = TransducerComparisonApplication.class.getResource(transducer.resourcePath());
        if (resource == null) {
            throw new MorphologyException("Bundled transducer not found: " + transducer.fileName());
        }
        try {
            if ("file".equals(resource.getProtocol())) {
                Path path = Path.of(resource.toURI());
                if (!Files.isRegularFile(path)) {
                    throw new MorphologyException("Bundled transducer not found: " + transducer.fileName());
                }
                return path;
            }
            try (InputStream stream = TransducerComparisonApplication.class
                    .getResourceAsStream(transducer.resourcePath())) {
                if (stream == null) {
                    throw new MorphologyException("Bundled transducer not found: " + transducer.fileName());
                }
                Path tempFile = Files.createTempFile("uqureader-transducer-", ".hfstol");
                Files.copy(stream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                tempFile.toFile().deleteOnExit();
                return tempFile;
            }
        } catch (IOException | URISyntaxException ex) {
            throw new MorphologyException("Failed to access bundled transducer " + transducer.fileName(), ex);
        }
    }

    private static Map<String, BundledTransducer> createBundledTransducerLookup() {
        Map<String, BundledTransducer> lookup = new LinkedHashMap<>();
        for (BundledTransducer transducer : BUNDLED_TRANSDUCERS) {
            lookup.put(transducer.alias().toLowerCase(Locale.ROOT), transducer);
            lookup.put(transducer.fileName().toLowerCase(Locale.ROOT), transducer);
        }
        return Map.copyOf(lookup);
    }

    private static void appendSummary(StringBuilder builder, String label, AnalysisSummary summary) {
        builder.append(String.format(Locale.ROOT,
                "  %s: %d tokens (%d unique).%n",
                label,
                summary.totalTokens(),
                summary.uniqueTokens()));
        builder.append(String.format(Locale.ROOT,
                "    Relevant tokens: %d occurrences (%d recognised, %d not recognised).%n",
                summary.relevantOccurrences(),
                summary.recognisedOccurrences(),
                summary.notRecognisedOccurrences()));
        builder.append(String.format(Locale.ROOT,
                "    Unique relevant tokens: %d (%d recognised, %d not recognised, %d mixed).%n",
                summary.uniqueRelevantTokens(),
                summary.uniqueRecognisedCount(),
                summary.uniqueNotRecognisedCount(),
                summary.uniqueMixedCount()));
    }

    private static void appendDifferences(StringBuilder builder,
                                          String labelA,
                                          AnalysisSummary firstSummary,
                                          String labelB,
                                          AnalysisSummary secondSummary) {
        LinkedHashSet<String> orderedTokens = new LinkedHashSet<>();
        orderedTokens.addAll(firstSummary.tokens().keySet());
        orderedTokens.addAll(secondSummary.tokens().keySet());

        List<String> recognisedOnlyFirst = new ArrayList<>();
        List<String> recognisedOnlySecond = new ArrayList<>();
        List<String> differingAnalyses = new ArrayList<>();

        for (String token : orderedTokens) {
            TokenStats firstStats = firstSummary.tokens().get(token);
            TokenStats secondStats = secondSummary.tokens().get(token);

            boolean firstRecognised = firstStats != null && firstStats.isRecognised();
            boolean secondRecognised = secondStats != null && secondStats.isRecognised();

            if (firstRecognised && !secondRecognised) {
                recognisedOnlyFirst.add(formatRecognisedOnly(token, firstStats, secondStats, labelB));
                continue;
            }
            if (secondRecognised && !firstRecognised) {
                recognisedOnlySecond.add(formatRecognisedOnly(token, secondStats, firstStats, labelA));
                continue;
            }
            if (firstRecognised && secondRecognised && !firstStats.sameAnalyses(secondStats)) {
                differingAnalyses.add(formatAnalysisDifference(token, firstStats, secondStats, labelA, labelB));
            }
        }

        builder.append("  Tokens recognised only by ").append(labelA).append(':')
                .append(System.lineSeparator());
        appendList(builder, recognisedOnlyFirst);

        builder.append("  Tokens recognised only by ").append(labelB).append(':')
                .append(System.lineSeparator());
        appendList(builder, recognisedOnlySecond);

        builder.append("  Tokens with differing analyses:")
                .append(System.lineSeparator());
        appendList(builder, differingAnalyses);
    }

    private static void appendList(StringBuilder builder, List<String> items) {
        if (items.isEmpty()) {
            builder.append("    (none)").append(System.lineSeparator());
            return;
        }
        for (String item : items) {
            builder.append("    ").append(item).append(System.lineSeparator());
        }
    }

    private static String formatRecognisedOnly(String token,
                                               TokenStats recognisedStats,
                                               TokenStats otherStats,
                                               String otherLabel) {
        int recognisedOccurrences = recognisedStats != null ? recognisedStats.recognisedOccurrences() : 0;
        String analyses = recognisedStats != null ? recognisedStats.describeAnalyses() : "";
        String otherInfo;
        if (otherStats == null) {
            otherInfo = otherLabel + ": not present";
        } else if (otherStats.notRecognisedOccurrences() > 0) {
            otherInfo = otherLabel + ": NR x" + otherStats.notRecognisedOccurrences();
        } else {
            otherInfo = otherLabel + ": -";
        }
        return String.format(Locale.ROOT,
                "%s (recognised %d×) -> %s [%s]",
                token,
                recognisedOccurrences,
                analyses,
                otherInfo);
    }

    private static String formatAnalysisDifference(String token,
                                                   TokenStats firstStats,
                                                   TokenStats secondStats,
                                                   String labelA,
                                                   String labelB) {
        return String.format(Locale.ROOT,
                "%s -> %s: %s | %s: %s",
                token,
                labelA,
                firstStats.describeAnalyses(),
                labelB,
                secondStats.describeAnalyses());
    }

    private static AnalysisSummary summarise(MorphologyAnalyzer analyzer, String text) {
        MorphologyAnalyzer.TextAnalysis analysis = analyzer.analyze(text);
        AnalysisSummary summary = new AnalysisSummary(analysis.tokensCount(), analysis.uniqueTokensCount());
        for (List<MorphologyAnalyzer.TokenEntry> sentence : analysis.sentences()) {
            for (MorphologyAnalyzer.TokenEntry entry : sentence) {
                String tag = entry.analysis();
                if (!isRelevant(tag)) {
                    continue;
                }
                summary.incrementRelevant();
                TokenStats stats = summary.tokens()
                        .computeIfAbsent(entry.token(), key -> new TokenStats());
                stats.record(tag);
                if (isLexical(tag)) {
                    summary.incrementRecognised();
                } else {
                    summary.incrementNotRecognised();
                }
            }
        }
        return summary;
    }

    private static boolean isRelevant(String analysis) {
        return isLexical(analysis) || isNotRecognised(analysis);
    }

    private static boolean isLexical(String analysis) {
        if (analysis == null || analysis.isBlank()) {
            return false;
        }
        if (STRUCTURAL_TAGS.contains(analysis)) {
            return false;
        }
        return !"NR".equals(analysis) && !"Error".equals(analysis);
    }

    private static boolean isNotRecognised(String analysis) {
        return "NR".equals(analysis) || "Error".equals(analysis);
    }

    private static void printUsage() {
        System.err.println("Usage: java "
                + TransducerComparisonApplication.class.getName()
                + " <transducer-a> <transducer-b> <transducer-c> <output-log> <text> [<text> ...]");
        System.err.println("You can provide either a file path or one of the bundled aliases listed below:");
        for (BundledTransducer transducer : BUNDLED_TRANSDUCERS) {
            System.err.println(String.format(Locale.ROOT,
                    "  %s -> %s",
                    transducer.alias(),
                    transducer.fileName()));
        }
    }

    private record ResolvedTransducer(String label, Path path) {
    }

    private record LoadedTransducer(ResolvedTransducer resolved, MorphologyAnalyzer analyzer) {
    }

    private record BundledTransducer(String alias, String resourcePath) {
        String fileName() {
            int separator = resourcePath.lastIndexOf('/');
            return separator >= 0 ? resourcePath.substring(separator + 1) : resourcePath;
        }
    }

    private static final class AnalysisSummary {
        private final int totalTokens;
        private final int uniqueTokens;
        private final Map<String, TokenStats> tokens;
        private int relevantOccurrences;
        private int recognisedOccurrences;
        private int notRecognisedOccurrences;

        AnalysisSummary(int totalTokens, int uniqueTokens) {
            this.totalTokens = totalTokens;
            this.uniqueTokens = uniqueTokens;
            this.tokens = new LinkedHashMap<>();
        }

        int totalTokens() {
            return totalTokens;
        }

        int uniqueTokens() {
            return uniqueTokens;
        }

        int relevantOccurrences() {
            return relevantOccurrences;
        }

        int recognisedOccurrences() {
            return recognisedOccurrences;
        }

        int notRecognisedOccurrences() {
            return notRecognisedOccurrences;
        }

        int uniqueRelevantTokens() {
            return tokens.size();
        }

        long uniqueRecognisedCount() {
            return tokens.values().stream().filter(TokenStats::isRecognised).count();
        }

        long uniqueNotRecognisedCount() {
            return tokens.values().stream().filter(TokenStats::isNotRecognised).count();
        }

        long uniqueMixedCount() {
            return tokens.values().stream().filter(TokenStats::isMixed).count();
        }

        Map<String, TokenStats> tokens() {
            return tokens;
        }

        void incrementRelevant() {
            relevantOccurrences++;
        }

        void incrementRecognised() {
            recognisedOccurrences++;
        }

        void incrementNotRecognised() {
            notRecognisedOccurrences++;
        }
    }

    private static final class TokenStats {
        private final LinkedHashSet<String> analyses;
        private int recognisedOccurrences;
        private int notRecognisedOccurrences;

        TokenStats() {
            this.analyses = new LinkedHashSet<>();
        }

        void record(String analysis) {
            if (TransducerComparisonApplication.isLexical(analysis)) {
                recognisedOccurrences++;
                analyses.add(analysis);
            } else if (TransducerComparisonApplication.isNotRecognised(analysis)) {
                notRecognisedOccurrences++;
            }
        }

        boolean isRecognised() {
            return recognisedOccurrences > 0;
        }

        boolean isNotRecognised() {
            return recognisedOccurrences == 0 && notRecognisedOccurrences > 0;
        }

        boolean isMixed() {
            return recognisedOccurrences > 0 && notRecognisedOccurrences > 0;
        }

        int recognisedOccurrences() {
            return recognisedOccurrences;
        }

        int notRecognisedOccurrences() {
            return notRecognisedOccurrences;
        }

        boolean sameAnalyses(TokenStats other) {
            if (other == null) {
                return false;
            }
            return analyses.equals(other.analyses);
        }

        String describeAnalyses() {
            if (analyses.isEmpty()) {
                return "";
            }
            return analyses.stream().collect(Collectors.joining(", "));
        }
    }
}

