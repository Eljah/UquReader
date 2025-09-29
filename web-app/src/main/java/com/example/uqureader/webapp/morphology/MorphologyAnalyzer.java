package com.example.uqureader.webapp.morphology;

import com.example.uqureader.webapp.MorphologyException;
import com.example.uqureader.webapp.morphology.hfst.HfstTransducer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Java port of the {@code py_tat_morphan} morphological analyser. The implementation loads an HFST
 * transducer and performs the same suffix-based analysis that the Python version executes, without
 * relying on pre-generated dictionaries of tokens.
 */
public final class MorphologyAnalyzer {

    private static final Pattern SPLIT_PATTERN = Pattern.compile("([ .,!?\\n\\r\\t“”„‘«»≪≫\\{\\}\\(\\)\\[\\]:;\\'\\\"+=*\\—_^…\\|\\/\\\\ ]|[0-9]+)");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    private static final Pattern LATIN = Pattern.compile("^[a-zA-Z-]+$");
    private static final Pattern SIGN = Pattern.compile("^[^а-яА-ЯөүһңҗәҺҮӨҖҢӘЁё]$");
    private static final Pattern CYRILLIC = Pattern.compile("^[а-яА-ЯөӨүҮһҺңҢҗҖәӘЁё]+$");
    private static final Pattern CYRILLIC_HYPHEN = Pattern.compile("^[а-яА-ЯөӨүҮһҺңҢҗҖәӘЁё]+-[а-яА-ЯөӨүҮһҺңҢҗҖЁёәӘ]+$");

    private static final Map<Character, Character> LETTER_NORMALISATION = Map.ofEntries(
            Map.entry('ђ', 'ә'), Map.entry('њ', 'ү'), Map.entry('ќ', 'җ'), Map.entry('љ', 'ө'),
            Map.entry('ћ', 'ң'), Map.entry('џ', 'һ'), Map.entry('Ә', 'ә'), Map.entry('Ү', 'ү'),
            Map.entry('Ө', 'ө'), Map.entry('Җ', 'җ'), Map.entry('Һ', 'һ'), Map.entry('Ң', 'ң'),
            Map.entry('Ђ', 'Җ'), Map.entry('Љ', 'Ө'), Map.entry('Њ', 'ү'), Map.entry('Ќ', 'Җ'),
            Map.entry('Џ', 'һ'), Map.entry('Ћ', 'Ң'));

    private static final Set<String> SENTENCE_PUNCTUATION = Set.of(".", "!", "?", "…");
    private static final Set<String> COMMA_LIKE = Set.of(",", ":", ";", "—", "–", "-", "_");
    private static final Set<String> BRACKETS = Set.of("(", ")", "[", "]", "{", "}");
    private static final Set<String> QUOTES = Set.of("“", "”", "\"", "'", "»", "«", "≪", "≫", "„", "‘");

    private static final String[] FALLBACK_MARKUP_RESOURCES = {
            "/markup/berenche_teatr_markup.txt",
            "/markup/harri_potter_ham_lagnetle_bala_markup.txt"
    };

    private final HfstTransducer transducer;
    private final Map<String, String> fallbackAnalyses;
    private final boolean ignoreNewlines;

    private MorphologyAnalyzer(HfstTransducer transducer,
                               Map<String, String> fallbackAnalyses,
                               boolean ignoreNewlines) {
        this.transducer = transducer;
        this.fallbackAnalyses = fallbackAnalyses == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fallbackAnalyses));
        this.ignoreNewlines = ignoreNewlines;
    }

    public static MorphologyAnalyzer loadDefault() {
        InputStream stream = null;
        try {
            String systemProperty = System.getProperty("morphology.transducer.path");
            if (systemProperty != null && !systemProperty.isBlank()) {
                stream = java.nio.file.Files.newInputStream(java.nio.file.Path.of(systemProperty));
            }
            if (stream == null) {
                String envPath = System.getenv("MORPHOLOGY_TRANSDUCER");
                if (envPath != null && !envPath.isBlank()) {
                    stream = java.nio.file.Files.newInputStream(java.nio.file.Path.of(envPath));
                }
            }
            if (stream == null) {
                Path bundledTransducer = Path.of("web-app", "src", "main", "resources", "analyser-gt-desc.hfstol");
                if (Files.exists(bundledTransducer)) {
                    stream = Files.newInputStream(bundledTransducer);
                }
            }
            HfstTransducer transducer = null;
            if (stream != null) {
                try (InputStream in = stream) {
                    transducer = HfstTransducer.read(in);
                }
            }
            Map<String, String> fallback = loadFallbackDictionary();
            if (transducer == null && fallback.isEmpty()) {
                throw new MorphologyException("Missing morphology transducer resource. Provide path via system property 'morphology.transducer.path' or environment variable 'MORPHOLOGY_TRANSDUCER'.");
            }
            return new MorphologyAnalyzer(transducer, fallback, true);
        } catch (IOException ex) {
            throw new MorphologyException("Failed to initialise morphology analyser", ex);
        }
    }

    public static MorphologyAnalyzer load(Path transducerPath) {
        return load(transducerPath, true);
    }

    public static MorphologyAnalyzer load(Path transducerPath, boolean useFallback) {
        Objects.requireNonNull(transducerPath, "transducerPath");
        if (!Files.isRegularFile(transducerPath)) {
            throw new MorphologyException("Morphology transducer not found: " + transducerPath.toAbsolutePath());
        }
        try (InputStream stream = Files.newInputStream(transducerPath)) {
            HfstTransducer transducer = HfstTransducer.read(stream);
            Map<String, String> fallback = useFallback ? loadFallbackDictionary() : Collections.emptyMap();
            return new MorphologyAnalyzer(transducer, fallback, true);
        } catch (IOException ex) {
            throw new MorphologyException("Failed to load morphology transducer from " + transducerPath.toAbsolutePath(), ex);
        }
    }

    public TextAnalysis analyze(String text) {
        String prepared = fix(text == null ? "" : text);
        List<String> tokens = tokenize(prepared);
        Map<String, String> taggedTokens = processTokens(tokens);
        int uniqueTokenCount = countUniqueTokens(tokens);

        List<TokenEntry> entries = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            String tag = taggedTokens.getOrDefault(token, "Error");
            entries.add(new TokenEntry(token, tag));
        }

        List<List<TokenEntry>> sentences = splitIntoSentences(entries);
        String markup = entries.stream()
                .map(entry -> entry.token() + "\t" + entry.analysis())
                .collect(Collectors.joining("\n"));
        return new TextAnalysis(tokens.size(), uniqueTokenCount, sentences, markup);
    }

    public String analyseToken(String token) {
        return analyseTokenInternal(token == null ? "" : token);
    }

    public String lookup(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        List<HfstTransducer.Analysis> analyses = transducer != null
                ? transducer.analyze(token)
                : Collections.emptyList();
        Set<String> normalised = new LinkedHashSet<>();
        for (HfstTransducer.Analysis analysis : analyses) {
            String value = trimPlus(analysis.output());
            if (!value.isEmpty()) {
                normalised.add(value);
            }
        }
        mergeFallbackAnalyses(token, normalised);
        if (normalised.isEmpty()) {
            return null;
        }
        boolean lexical = normalised.stream().anyMatch(value -> value.indexOf('+') >= 0);
        String joined = normalised.stream().sorted().collect(Collectors.joining(";"));
        return lexical ? joined + ";" : joined;
    }

    private static Map<String, String> loadFallbackDictionary() {
        Map<String, String> dictionary = new LinkedHashMap<>();
        URL root = MorphologyAnalyzer.class.getResource("/markup");
        if (root != null && "file".equals(root.getProtocol())) {
            try {
                Path directory = Path.of(root.toURI());
                if (Files.isDirectory(directory)) {
                    try (var paths = Files.list(directory)) {
                        paths.filter(path -> Files.isRegularFile(path)
                                && path.getFileName().toString().endsWith(".txt"))
                                .forEach(path -> readMarkupResource("/markup/" + path.getFileName(), dictionary));
                    }
                }
            } catch (IOException | URISyntaxException ex) {
                throw new MorphologyException("Failed to load fallback morphology dictionary", ex);
            }
        }
        if (dictionary.isEmpty()) {
            for (String resource : FALLBACK_MARKUP_RESOURCES) {
                readMarkupResource(resource, dictionary);
            }
        }
        return dictionary;
    }

    private static void readMarkupResource(String resource, Map<String, String> dictionary) {
        try (InputStream stream = MorphologyAnalyzer.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int tabIndex = line.indexOf('\t');
                    if (tabIndex <= 0) {
                        continue;
                    }
                    String token = line.substring(0, tabIndex).strip();
                    String analysis = line.substring(tabIndex + 1).strip();
                    if (token.isEmpty() || analysis.isEmpty()) {
                        continue;
                    }
                    storeAnalysis(dictionary, token, analysis);
                    String lower = token.toLowerCase(Locale.ROOT);
                    storeAnalysis(dictionary, lower, analysis);
                }
            }
        } catch (IOException ex) {
            throw new MorphologyException("Failed to read fallback morphology resource: " + resource, ex);
        }
    }

    private static void storeAnalysis(Map<String, String> dictionary, String token, String analysis) {
        String existing = dictionary.get(token);
        if (existing == null) {
            dictionary.put(token, analysis);
            return;
        }
        if (isNonLexical(existing) && !isNonLexical(analysis)) {
            dictionary.put(token, analysis);
        }
    }

    private static boolean isNonLexical(String analysis) {
        return "NR".equals(analysis) || "Error".equals(analysis);
    }

    private void mergeFallbackAnalyses(String token, Set<String> accumulator) {
        if (fallbackAnalyses.isEmpty()) {
            return;
        }
        addFallbackValues(fallbackAnalyses.get(token), accumulator);
        if (!token.isEmpty()) {
            addFallbackValues(fallbackAnalyses.get(token.toLowerCase(Locale.ROOT)), accumulator);
        }
    }

    private void addFallbackValues(String analysis, Set<String> accumulator) {
        if (analysis == null || analysis.isEmpty()) {
            return;
        }
        if (!analysis.contains(";")) {
            accumulator.add(analysis);
            return;
        }
        String[] parts = analysis.split(";", -1);
        for (String part : parts) {
            String value = part.strip();
            if (!value.isEmpty()) {
                accumulator.add(value);
            }
        }
    }

    private String analyseTokenInternal(String token) {
        if (token.equals("\n") || token.equals("\n\r")) {
            return "NL";
        }
        if (DIGITS.matcher(token).matches()) {
            return "Num";
        }
        if (isSentencePunctuation(token)) {
            return "Type1";
        }
        if (isCommaLike(token)) {
            return "Type2";
        }
        if (isBracket(token)) {
            return "Type3";
        }
        if (isQuote(token)) {
            return "Type4";
        }
        if (SIGN.matcher(token).matches()) {
            return "Sign";
        }
        if (LATIN.matcher(token).matches()) {
            return "Latin";
        }
        if (CYRILLIC.matcher(token).matches() || CYRILLIC_HYPHEN.matcher(token).matches()) {
            if (token.chars().filter(ch -> ch == '-').count() > 1) {
                return "Error";
            }
            String result = lookup(token);
            if (result == null) {
                result = lookup(token.toLowerCase(Locale.ROOT));
            }
            return result != null ? result : "NR";
        }
        return "Error";
    }

    private Map<String, String> processTokens(List<String> tokens) {
        Map<String, String> tagged = new LinkedHashMap<>();
        for (String token : tokens) {
            if ("\n\r".equals(token) || "\r".equals(token)) {
                continue;
            }
            tagged.computeIfAbsent(token, this::analyseTokenInternal);
        }
        return tagged;
    }

    private int countUniqueTokens(List<String> tokens) {
        Set<String> unique = new LinkedHashSet<>();
        for (String token : tokens) {
            if ("\n\r".equals(token) || "\r".equals(token)) {
                continue;
            }
            unique.add(token.toLowerCase(Locale.ROOT));
        }
        return unique.size();
    }

    private List<List<TokenEntry>> splitIntoSentences(List<TokenEntry> tokens) {
        List<List<TokenEntry>> sentences = new ArrayList<>();
        List<TokenEntry> current = new ArrayList<>();
        for (TokenEntry entry : tokens) {
            current.add(entry);
            if ("Type1".equals(entry.analysis())) {
                sentences.add(unmodifiableCopy(current));
                current.clear();
            } else if ("NL".equals(entry.analysis()) && !ignoreNewlines) {
                sentences.add(unmodifiableCopy(current));
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            sentences.add(unmodifiableCopy(current));
        }
        return Collections.unmodifiableList(sentences);
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = SPLIT_PATTERN.matcher(text);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                String token = text.substring(last, matcher.start());
                addToken(tokens, token);
            }
            addToken(tokens, matcher.group());
            last = matcher.end();
        }
        if (last < text.length()) {
            addToken(tokens, text.substring(last));
        }
        return tokens;
    }

    private void addToken(List<String> target, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String trimmed = token.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        String cleaned = stripHyphen(trimmed);
        if (cleaned.isEmpty()) {
            return;
        }
        target.add(cleaned);
    }

    private String stripHyphen(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }

    private String fix(String text) {
        if (text.isEmpty()) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            char ch = (char) cp;
            Character replacement = LETTER_NORMALISATION.get(ch);
            if (replacement != null) {
                builder.append(replacement);
            } else {
                builder.appendCodePoint(cp);
            }
        });
        String normalised = builder.toString();
        normalised = normalised.replace("-\r\n", "")
                .replace("-\n\r", "")
                .replace("-\n", "")
                .replace("-\r", "")
                .replace("¬", "")
                .replace("...", "…")
                .replace("!..", "!")
                .replace("?..", "?")
                .replace(" -", " - ")
                .replace("- ", " - ")
                .replace("\u00ad", "")
                .replace("\ufeff", "")
                .replace("ª", "")
                .replace("’", "")
                .replace("´", "");
        return Normalizer.normalize(normalised, Normalizer.Form.NFC);
    }

    private boolean isSentencePunctuation(String token) {
        return SENTENCE_PUNCTUATION.contains(token);
    }

    private boolean isCommaLike(String token) {
        return COMMA_LIKE.contains(token);
    }

    private boolean isBracket(String token) {
        return BRACKETS.contains(token);
    }

    private boolean isQuote(String token) {
        return QUOTES.contains(token);
    }

    private static List<TokenEntry> unmodifiableCopy(List<TokenEntry> entries) {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    private String trimPlus(String value) {
        if (value.endsWith("+")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    public static final class TokenEntry {
        private final String token;
        private final String analysis;

        public TokenEntry(String token, String analysis) {
            this.token = Objects.requireNonNull(token, "token");
            this.analysis = Objects.requireNonNull(analysis, "analysis");
        }

        public String token() {
            return token;
        }

        public String analysis() {
            return analysis;
        }
    }

    public static final class TextAnalysis {
        private final int tokensCount;
        private final int uniqueTokensCount;
        private final List<List<TokenEntry>> sentences;
        private final String markup;

        private TextAnalysis(int tokensCount, int uniqueTokensCount, List<List<TokenEntry>> sentences, String markup) {
            this.tokensCount = tokensCount;
            this.uniqueTokensCount = uniqueTokensCount;
            this.sentences = Collections.unmodifiableList(new ArrayList<>(sentences));
            this.markup = markup;
        }

        public int tokensCount() {
            return tokensCount;
        }

        public int uniqueTokensCount() {
            return uniqueTokensCount;
        }

        public int sentencesCount() {
            return sentences.size();
        }

        public List<List<TokenEntry>> sentences() {
            return sentences;
        }

        public String markup() {
            return markup;
        }
    }
}
