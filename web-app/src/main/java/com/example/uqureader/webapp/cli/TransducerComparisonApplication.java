package com.example.uqureader.webapp.cli;

import com.example.uqureader.webapp.MorphologyException;
import com.example.uqureader.webapp.morphology.MorphologyAnalyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final Set<String> STRUCTURAL_TAGS = Set.of(
            "NL", "Type1", "Type2", "Type3", "Type4", "Latin", "Sign"
    );

    private TransducerComparisonApplication() {
    }

    public static void main(String[] args) {
        if (args == null || args.length < 4) {
            printUsage();
            System.exit(1);
        }

        Path firstTransducer = Path.of(args[0]);
        Path secondTransducer = Path.of(args[1]);
        Path output = Path.of(args[2]);
        List<Path> texts = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            texts.add(Path.of(args[i]));
        }
        if (texts.isEmpty()) {
            printUsage();
            System.exit(1);
        }

        try {
            MorphologyAnalyzer firstAnalyzer = MorphologyAnalyzer.load(firstTransducer);
            MorphologyAnalyzer secondAnalyzer = MorphologyAnalyzer.load(secondTransducer);
            writeComparisonLog(firstAnalyzer, secondAnalyzer, firstTransducer, secondTransducer, output, texts);
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

    private static void writeComparisonLog(MorphologyAnalyzer firstAnalyzer,
                                           MorphologyAnalyzer secondAnalyzer,
                                           Path firstTransducer,
                                           Path secondTransducer,
                                           Path output,
                                           List<Path> texts) throws IOException {
        Objects.requireNonNull(output, "output");
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        String labelA = firstTransducer.getFileName() != null
                ? firstTransducer.getFileName().toString()
                : firstTransducer.toString();
        String labelB = secondTransducer.getFileName() != null
                ? secondTransducer.getFileName().toString()
                : secondTransducer.toString();

        StringBuilder builder = new StringBuilder();
        builder.append("Transducer comparison log").append(System.lineSeparator());
        builder.append("Generated: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .append(System.lineSeparator());
        builder.append("First transducer: ")
                .append(firstTransducer.toAbsolutePath())
                .append(System.lineSeparator());
        builder.append("Second transducer: ")
                .append(secondTransducer.toAbsolutePath())
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        for (Path text : texts) {
            if (!Files.isRegularFile(text)) {
                throw new MorphologyException("Text file not found: " + text.toAbsolutePath());
            }
            builder.append("=== Text: ")
                    .append(text.toAbsolutePath())
                    .append(System.lineSeparator());

            String content = Files.readString(text, StandardCharsets.UTF_8);
            AnalysisSummary firstSummary = summarise(firstAnalyzer, content);
            AnalysisSummary secondSummary = summarise(secondAnalyzer, content);

            appendSummary(builder, labelA, firstSummary);
            appendSummary(builder, labelB, secondSummary);

            appendDifferences(builder, labelA, firstSummary, labelB, secondSummary);
            builder.append(System.lineSeparator());
        }

        Files.writeString(output, builder.toString(), StandardCharsets.UTF_8);
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
                + " <first-transducer> <second-transducer> <output-log> <text> [<text> ...]");
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

