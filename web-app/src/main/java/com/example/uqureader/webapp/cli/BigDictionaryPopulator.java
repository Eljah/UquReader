package com.example.uqureader.webapp.cli;

import com.example.uqureader.webapp.morphology.MorphologyAnalyzer;

import org.apache.poi.hwpf.extractor.WordExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Command line utility that populates the SQLite dictionary with lemmas extracted from the
 * Word documents located in the {@code bigdic} directory.
 */
public final class BigDictionaryPopulator {

    private static final Map<String, String> POS_MAPPING = createPosMapping();
    private static final Map<String, Integer> POS_PRIORITY = createPosPriority();

    private BigDictionaryPopulator() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            printUsage();
            System.exit(1);
        } catch (Exception ex) {
            System.err.println("Failed to populate dictionary: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException, SQLException {
        Options options = parseArguments(args);
        if (options.help()) {
            printUsage();
            return;
        }

        Path sourceDirectory = options.sourceDir();
        if (sourceDirectory == null) {
            sourceDirectory = resolveDefaultSourceDirectory();
        }
        if (!Files.isDirectory(sourceDirectory)) {
            throw new IllegalArgumentException(
                    "Source directory not found: " + sourceDirectory.toAbsolutePath());
        }

        Path databasePath = options.dictionaryPath();
        if (databasePath == null) {
            databasePath = resolveDefaultDictionaryPath();
        }

        List<Path> documents = listDocuments(sourceDirectory);
        if (documents.isEmpty()) {
            System.out.println("No Word documents found in " + sourceDirectory.toAbsolutePath());
            return;
        }

        MorphologyAnalyzer analyzer = MorphologyAnalyzer.loadDefault();
        Map<String, LemmaData> lemmas = new LinkedHashMap<>();
        Set<String> conflicts = new LinkedHashSet<>();
        int entriesParsed = 0;
        int entriesWithLemma = 0;

        for (Path document : documents) {
            List<DictionaryEntry> rows = extractEntries(document);
            entriesParsed += rows.size();
            for (DictionaryEntry row : rows) {
                String token = row.tatar();
                String translation = row.translation();
                if (token.isEmpty() || translation.isEmpty()) {
                    continue;
                }
                String analysis = analyzer.analyseToken(token);
                Optional<LemmaSelection> selection = selectLemma(analysis);
                String lemma;
                String pos;
                if (selection.isPresent()) {
                    LemmaSelection candidate = selection.get();
                    lemma = candidate.lemma();
                    pos = candidate.pos();
                    entriesWithLemma++;
                } else {
                    lemma = normaliseLemma(token);
                    pos = "UNKNOWN";
                }
                if (lemma.isEmpty()) {
                    continue;
                }
                mergeLemma(lemmas, conflicts, lemma, pos, translation);
            }
        }

        Path absoluteDatabase = databasePath.toAbsolutePath();
        Path parent = absoluteDatabase.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        int inserted;
        int updated;
        int translationsInserted;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + absoluteDatabase)) {
            connection.setAutoCommit(false);
            ensureLemmasTable(connection);
            ensureTranslationsTable(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO lemmas_tt (lemma, pos) VALUES (?, ?)"
            );
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE lemmas_tt SET pos = ? WHERE lemma = ? AND (pos IS NULL OR TRIM(pos) = '')"
                 );
                 PreparedStatement insertTranslation = connection.prepareStatement(
                         "INSERT OR IGNORE INTO tt_ru (lemma_tt, lemma_ru, score) VALUES (?, ?, ?)"
                 )) {
                inserted = 0;
                updated = 0;
                translationsInserted = 0;
                for (Map.Entry<String, LemmaData> entry : lemmas.entrySet()) {
                    String lemma = entry.getKey();
                    LemmaData data = entry.getValue();
                    insert.setString(1, lemma);
                    insert.setString(2, data.pos);
                    inserted += insert.executeUpdate();

                    update.setString(1, data.pos);
                    update.setString(2, lemma);
                    updated += update.executeUpdate();

                    for (String translation : data.translations) {
                        insertTranslation.setString(1, lemma);
                        insertTranslation.setString(2, translation);
                        insertTranslation.setDouble(3, 1.0d);
                        translationsInserted += insertTranslation.executeUpdate();
                    }
                }
            }
            connection.commit();
        }

        System.out.printf(Locale.ROOT,
                "Processed %d documents, parsed %d entries, resolved lemmas for %d rows, collected %d unique lemmas.%n",
                documents.size(), entriesParsed, entriesWithLemma, lemmas.size());
        System.out.printf(Locale.ROOT,
                "Inserted %d new lemmas, updated %d existing entries, added %d translations in %s.%n",
                inserted, updated, translationsInserted, absoluteDatabase);
        if (!conflicts.isEmpty()) {
            System.err.println("Encountered lemmas with conflicting parts of speech:");
            int shown = 0;
            for (String conflict : conflicts) {
                System.err.println(" - " + conflict);
                if (++shown >= 20) {
                    break;
                }
            }
            if (conflicts.size() > 20) {
                System.err.printf(Locale.ROOT, "... and %d more conflicts.%n", conflicts.size() - 20);
            }
        }
    }

    private static Options parseArguments(String[] args) {
        if (args == null || args.length == 0) {
            return new Options(null, null, false);
        }
        Path source = null;
        Path dictionary = null;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (Objects.equals(arg, "--source") || Objects.equals(arg, "-s")) {
                source = readPathArgument(args, ++i, arg);
            } else if (Objects.equals(arg, "--dictionary") || Objects.equals(arg, "-d")) {
                dictionary = readPathArgument(args, ++i, arg);
            } else if (Objects.equals(arg, "--help") || Objects.equals(arg, "-h")) {
                help = true;
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return new Options(source, dictionary, help);
    }

    private static Path readPathArgument(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return Path.of(args[index]);
    }

    private static void printUsage() {
        System.out.println("Usage: BigDictionaryPopulator [--source <path>] [--dictionary <file>]");
        System.out.println("Options:");
        System.out.println("  --source, -s      Directory that contains *.doc files (default: web-app/src/main/resources/bigdic)");
        System.out.println("  --dictionary, -d  Path to tat_rus_dictionary.db SQLite file (default: data/tat_rus_dictionary.db)");
        System.out.println("  --help, -h        Show this help text");
    }

    private static Path resolveDefaultSourceDirectory() throws IOException {
        List<Path> candidates = List.of(
                Path.of("web-app", "src", "main", "resources", "bigdic"),
                Path.of("src", "main", "resources", "bigdic")
        );
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        URL resource = BigDictionaryPopulator.class.getResource("/bigdic");
        if (resource != null && Objects.equals(resource.getProtocol(), "file")) {
            try {
                Path path = Path.of(resource.toURI());
                if (Files.isDirectory(path)) {
                    return path;
                }
            } catch (URISyntaxException ignored) {
                // Ignored, fallback handled below
            }
        }
        throw new IOException("Unable to locate bigdic directory. Provide path via --source option.");
    }

    private static Path resolveDefaultDictionaryPath() {
        Path candidate = Path.of("data", "tat_rus_dictionary.db");
        if (Files.exists(candidate)) {
            return candidate;
        }
        return Path.of("tat_rus_dictionary.db");
    }

    private static List<Path> listDocuments(Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(BigDictionaryPopulator::isWordDocument)
                    .sorted()
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private static boolean isWordDocument(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".doc") && !fileName.startsWith("~$");
    }

    private static List<DictionaryEntry> extractEntries(Path document) throws IOException {
        List<DictionaryEntry> entries = new ArrayList<>();
        try (InputStream stream = Files.newInputStream(document);
             WordExtractor extractor = new WordExtractor(stream)) {
            String[] paragraphs = extractor.getParagraphText();
            if (paragraphs == null || paragraphs.length == 0) {
                return entries;
            }
            for (String paragraph : paragraphs) {
                if (paragraph == null) {
                    continue;
                }
                String cleaned = paragraph.replace('\r', '\n')
                        .replace('\u0007', ' ')
                        .strip();
                if (cleaned.isEmpty()) {
                    continue;
                }
                String[] lines = cleaned.split("\\R");
                for (String line : lines) {
                    String fixed = fixDocumentEncoding(line).strip();
                    if (fixed.isEmpty()) {
                        continue;
                    }
                    DictionaryEntry entry = parseDictionaryEntry(fixed);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
            return entries;
        }
    }

    private static String normaliseLemma(String lemma) {
        String normalised = Normalizer.normalize(lemma == null ? "" : lemma, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .strip();
        return normalised.replace('\u00a0', ' ');
    }

    private static Optional<LemmaSelection> selectLemma(String analysis) {
        if (analysis == null || analysis.isBlank()) {
            return Optional.empty();
        }
        String[] entries = analysis.split(";");
        LemmaSelection best = null;
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || !trimmed.contains("+")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon >= 0) {
                trimmed = trimmed.substring(0, colon);
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\+");
            if (parts.length == 0) {
                continue;
            }
            String lemma = normaliseLemma(parts[0]);
            if (lemma.isEmpty()) {
                continue;
            }
            String pos = null;
            for (int i = 1; i < parts.length; i++) {
                pos = mapPartOfSpeech(parts[i]);
                if (pos != null) {
                    break;
                }
            }
            if (pos == null) {
                continue;
            }
            int candidatePriority = priority(pos);
            if (best == null || candidatePriority > best.priority()) {
                best = new LemmaSelection(lemma, pos, candidatePriority);
            }
        }
        return Optional.ofNullable(best);
    }

    private static String mapPartOfSpeech(String tag) {
        String simplified = simplifyTag(tag);
        if (simplified.isEmpty()) {
            return null;
        }
        String mapped = POS_MAPPING.get(simplified);
        if (mapped != null) {
            return mapped;
        }
        if (simplified.endsWith("PL") && POS_MAPPING.containsKey(simplified.substring(0, simplified.length() - 2))) {
            return POS_MAPPING.get(simplified.substring(0, simplified.length() - 2));
        }
        return null;
    }

    private static String simplifyTag(String tag) {
        if (tag == null) {
            return "";
        }
        String value = tag;
        int paren = value.indexOf('(');
        if (paren >= 0) {
            value = value.substring(0, paren);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int comma = value.indexOf(',');
        if (comma >= 0) {
            value = value.substring(0, comma);
        }
        value = value.strip();
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetter(ch)) {
                builder.append(Character.toUpperCase(ch));
            }
        }
        return builder.toString();
    }

    private static void mergeLemma(Map<String, LemmaData> lemmas,
                                   Set<String> conflicts,
                                   String lemma,
                                   String pos,
                                   String translation) {
        LemmaData existing = lemmas.get(lemma);
        if (existing == null) {
            LemmaData created = new LemmaData();
            created.pos = pos;
            if (translation != null && !translation.isBlank()) {
                created.translations.add(translation);
            }
            lemmas.put(lemma, created);
            return;
        }
        if (pos != null) {
            if (existing.pos == null) {
                existing.pos = pos;
            } else if (!existing.pos.equals(pos)) {
                int currentPriority = priority(existing.pos);
                int candidatePriority = priority(pos);
                if (candidatePriority > currentPriority) {
                    existing.pos = pos;
                } else if (candidatePriority == currentPriority) {
                    conflicts.add(lemma + " (" + existing.pos + " vs " + pos + ")");
                }
            }
        }
        if (translation != null && !translation.isBlank()) {
            existing.translations.add(translation);
        }
    }

    private static int priority(String pos) {
        return POS_PRIORITY.getOrDefault(pos, 1);
    }

    private static void ensureLemmasTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS lemmas_tt("
                            + "lemma TEXT PRIMARY KEY,"
                            + "pos TEXT"
                            + ")");
        }
    }

    private static void ensureTranslationsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS tt_ru("
                            + "lemma_tt TEXT NOT NULL,"
                            + "lemma_ru TEXT NOT NULL,"
                            + "score REAL,"
                            + "PRIMARY KEY(lemma_tt, lemma_ru)"
                            + ")");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS tt_ru_tt_idx ON tt_ru(lemma_tt)");
        }
    }

    private static DictionaryEntry parseDictionaryEntry(String line) {
        int splitIndex = findFirstWhitespace(line);
        if (splitIndex <= 0 || splitIndex >= line.length() - 1) {
            return null;
        }
        String tatar = line.substring(0, splitIndex).strip();
        String translation = line.substring(splitIndex + 1).strip();
        if (tatar.isEmpty() || translation.isEmpty()) {
            return null;
        }
        String normalisedToken = normaliseToken(tatar);
        String normalisedTranslation = normaliseTranslation(translation);
        if (normalisedToken.isEmpty() || normalisedTranslation.isEmpty()) {
            return null;
        }
        return new DictionaryEntry(normalisedToken, normalisedTranslation);
    }

    private static int findFirstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String normaliseToken(String token) {
        if (token == null) {
            return "";
        }
        String replaced = token.replace('\u00a0', ' ');
        String normalised = Normalizer.normalize(replaced, Normalizer.Form.NFC).strip();
        return collapseWhitespace(normalised);
    }

    private static String normaliseTranslation(String translation) {
        if (translation == null) {
            return "";
        }
        String fixed = fixDocumentEncoding(translation).replace('\r', ' ').replace('\n', ' ');
        String normalised = Normalizer.normalize(fixed.replace('\u00a0', ' '), Normalizer.Form.NFC).strip();
        return collapseWhitespace(normalised);
    }

    private static String collapseWhitespace(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean previousWhitespace = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch)) {
                if (!previousWhitespace) {
                    builder.append(' ');
                }
                previousWhitespace = true;
            } else {
                builder.append(ch);
                previousWhitespace = false;
            }
        }
        return builder.toString().strip();
    }

    private static String fixDocumentEncoding(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            builder.append(switch (ch) {
                case '\u00B4' -> 'ә';
                case '\u00BA' -> 'ү';
                case '\u00BF' -> 'ө';
                case '\u00BC' -> 'ң';
                case '\u00B3' -> 'һ';
                case '\u00A2' -> 'җ';
                default -> ch;
            });
        }
        return builder.toString();
    }

    private static final class LemmaData {
        private String pos;
        private final LinkedHashSet<String> translations = new LinkedHashSet<>();
    }

    private record DictionaryEntry(String tatar, String translation) {
    }

    private record LemmaSelection(String lemma, String pos, int priority) {
    }

    private static Map<String, String> createPosMapping() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("N", "NOUN");
        mapping.put("NOUN", "NOUN");
        mapping.put("PROP", "PROPN");
        mapping.put("PROPN", "PROPN");
        mapping.put("PN", "PRON");
        mapping.put("PRN", "PRON");
        mapping.put("PRON", "PRON");
        mapping.put("V", "VERB");
        mapping.put("VERB", "VERB");
        mapping.put("AUX", "AUX");
        mapping.put("ADJ", "ADJ");
        mapping.put("ADJECTIVE", "ADJ");
        mapping.put("ADV", "ADV");
        mapping.put("ADVERB", "ADV");
        mapping.put("NUM", "NUM");
        mapping.put("NUMCARD", "NUM");
        mapping.put("NUMORD", "NUM");
        mapping.put("DET", "DET");
        mapping.put("PART", "PART");
        mapping.put("PCL", "PART");
        mapping.put("POST", "POSTP");
        mapping.put("POSTP", "POSTP");
        mapping.put("ADP", "ADP");
        mapping.put("PREP", "ADP");
        mapping.put("CNJ", "CONJ");
        mapping.put("CONJ", "CONJ");
        mapping.put("CCONJ", "CONJ");
        mapping.put("SCONJ", "CONJ");
        mapping.put("MOD", "MOD");
        mapping.put("INTJ", "INTJ");
        mapping.put("IJ", "INTJ");
        mapping.put("INTERJ", "INTJ");
        mapping.put("ABBR", "ABBR");
        mapping.put("SYM", "SYM");
        mapping.put("X", "X");
        return Collections.unmodifiableMap(mapping);
    }

    private static Map<String, Integer> createPosPriority() {
        Map<String, Integer> priorities = new HashMap<>();
        priorities.put("PROPN", 120);
        priorities.put("NOUN", 110);
        priorities.put("VERB", 100);
        priorities.put("ADJ", 90);
        priorities.put("ADV", 80);
        priorities.put("PRON", 80);
        priorities.put("NUM", 70);
        priorities.put("DET", 65);
        priorities.put("PART", 60);
        priorities.put("AUX", 60);
        priorities.put("POSTP", 55);
        priorities.put("ADP", 55);
        priorities.put("CONJ", 50);
        priorities.put("MOD", 40);
        priorities.put("INTJ", 30);
        priorities.put("ABBR", 20);
        priorities.put("SYM", 10);
        priorities.put("X", 5);
        priorities.put("UNKNOWN", 1);
        return Collections.unmodifiableMap(priorities);
    }

    private record Options(Path sourceDir, Path dictionaryPath, boolean help) {
    }
}
