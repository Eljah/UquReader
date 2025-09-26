package com.example.uqureader.webapp.morphology;

import com.example.uqureader.webapp.MorphologyException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lightweight Java port of the tokenisation and tagging logic used by the Python
 * {@code py_tat_morphan} package. The implementation relies on pre-generated markup stored in the
 * application resources and reproduces the same token level annotations without spawning a Python
 * interpreter.
 */
public final class MorphologyAnalyzer {

    private static final Pattern SPLIT_PATTERN = Pattern.compile("([ .,!?\\n\\r\\t“”„‘«»≪≫\\{\\}\\(\\)\\[\\]:;\\'\\\"+=*\\—_^…\\|\\/\\\\ ]|[0-9]+)");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    private static final Pattern LATIN = Pattern.compile("^[a-zA-Z]+$");
    private static final Pattern SINGLE_CYRILLIC = Pattern.compile("^[а-эА-ЭөүһңҗҺҮӨҖҢӘЁё]$");
    private static final Pattern NON_CYRILLIC = Pattern.compile("^[^а-яА-ЯөүһңҗәҺҮӨҖҢӘЁё]+$");

    private final Map<String, String> dictionary;

    private MorphologyAnalyzer(Map<String, String> dictionary) {
        this.dictionary = dictionary;
    }

    public static MorphologyAnalyzer loadDefault() {
        Map<String, String> dictionary = new LinkedHashMap<>(loadDictionary("/markup/berenche_teatr_markup.txt"));
        loadDictionary("/markup/harri_potter_ham_lagnetle_bala_markup.txt").forEach(dictionary::putIfAbsent);
        return new MorphologyAnalyzer(dictionary);
    }

    public TextAnalysis analyze(String text) {
        String content = text == null ? "" : text;
        List<TokenEntry> tokens = tokenize(content);
        List<List<TokenEntry>> sentences = splitIntoSentences(tokens);
        String markup = tokens.stream()
                .map(entry -> entry.token() + "\t" + entry.analysis())
                .collect(Collectors.joining("\n"));
        int uniqueTokens = (int) tokens.stream()
                .map(entry -> entry.token().toLowerCase(Locale.ROOT))
                .distinct()
                .count();
        return new TextAnalysis(tokens, sentences, markup, uniqueTokens);
    }

    public String lookup(String token) {
        String key = token == null ? "" : token;
        return dictionary.getOrDefault(key, classifyToken(key));
    }

    private List<TokenEntry> tokenize(String text) {
        List<TokenEntry> result = new ArrayList<>();
        Matcher matcher = SPLIT_PATTERN.matcher(text);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                String token = text.substring(last, matcher.start());
                addToken(result, token);
            }
            String separator = matcher.group();
            addToken(result, separator);
            last = matcher.end();
        }
        if (last < text.length()) {
            addToken(result, text.substring(last));
        }
        return result;
    }

    private void addToken(List<TokenEntry> target, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        if (isWhitespace(token)) {
            return;
        }
        String analysis = dictionary.get(token);
        if (analysis == null) {
            analysis = classifyToken(token);
        }
        target.add(new TokenEntry(token, analysis));
    }

    private boolean isWhitespace(String token) {
        return token.chars().allMatch(Character::isWhitespace);
    }

    private static Map<String, String> loadDictionary(String resourcePath) {
        InputStream stream = MorphologyAnalyzer.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new MorphologyException("Missing morphology resource: " + resourcePath);
        }
        Map<String, String> map = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                if (parts.length != 2) {
                    throw new MorphologyException("Invalid markup line: " + line);
                }
                map.putIfAbsent(parts[0], parts[1]);
            }
            return map;
        } catch (IOException ex) {
            throw new MorphologyException("Failed to read morphology resource: " + resourcePath, ex);
        }
    }

    private static List<List<TokenEntry>> splitIntoSentences(List<TokenEntry> tokens) {
        List<List<TokenEntry>> sentences = new ArrayList<>();
        List<TokenEntry> current = new ArrayList<>();
        for (TokenEntry entry : tokens) {
            current.add(entry);
            if ("Type1".equals(entry.analysis())) {
                sentences.add(unmodifiableCopy(current));
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            sentences.add(unmodifiableCopy(current));
        }
        return Collections.unmodifiableList(sentences);
    }

    private static List<TokenEntry> unmodifiableCopy(List<TokenEntry> entries) {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    private String classifyToken(String token) {
        if (token.isEmpty()) {
            return "NR";
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
        if (SINGLE_CYRILLIC.matcher(token).matches()) {
            return "Letter";
        }
        if (LATIN.matcher(token).matches()) {
            return "Latin";
        }
        if (NON_CYRILLIC.matcher(token).matches()) {
            return "Sign";
        }
        return "NR";
    }

    private boolean isSentencePunctuation(String token) {
        return Set.of(".", "!", "?", "…").contains(token);
    }

    private boolean isCommaLike(String token) {
        return Set.of(",", ":", ";", "—", "–", "-", "_").contains(token);
    }

    private boolean isBracket(String token) {
        return Set.of("(", ")", "[", "]", "{", "}").contains(token);
    }

    private boolean isQuote(String token) {
        return Set.of("“", "”", "\"", "'", "»", "«", "≪", "≫", "„", "‘").contains(token);
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
        private final List<TokenEntry> tokens;
        private final List<List<TokenEntry>> sentences;
        private final String markup;
        private final int uniqueTokens;

        private TextAnalysis(List<TokenEntry> tokens, List<List<TokenEntry>> sentences, String markup, int uniqueTokens) {
            this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens));
            this.sentences = sentences;
            this.markup = markup;
            this.uniqueTokens = uniqueTokens;
        }

        public int tokensCount() {
            return tokens.size();
        }

        public int uniqueTokensCount() {
            return uniqueTokens;
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
