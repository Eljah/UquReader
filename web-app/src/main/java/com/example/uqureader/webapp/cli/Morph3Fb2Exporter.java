package com.example.uqureader.webapp.cli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Command line utility that converts {@code *.morph3.tsv} files and the matching
 * original text into a FictionBook 2.0 document with morphology metadata embedded
 * into additional markup for every token.
 */
public final class Morph3Fb2Exporter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Replacement[] MATCH_REPLACEMENTS = {
            new Replacement("…", "..."),
            new Replacement("...", "…"),
            new Replacement("–", "-"),
            new Replacement("—", "-"),
            new Replacement("‑", "-"),
            new Replacement("-", "—"),
            new Replacement("-", "–"),
            new Replacement("“", "\""),
            new Replacement("”", "\""),
            new Replacement("’", "'"),
            new Replacement("‘", "'"),
            new Replacement("\u00A0", " ")
    };

    private final PrintStream out;
    private final PrintStream err;

    public Morph3Fb2Exporter(PrintStream out, PrintStream err) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    public static void main(String[] args) {
        Morph3Fb2Exporter exporter = new Morph3Fb2Exporter(System.out, System.err);
        int exitCode = exporter.run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        if (args == null || args.length == 0) {
            printUsage();
            return 1;
        }

        if (args.length == 1 && !isOption(args[0])) {
            Path single = Path.of(args[0]);
            if (looksLikeOriginalFile(single)) {
                Path morphCandidate = Path.of(single.toString() + ".morph3.tsv");
                if (!Files.exists(morphCandidate)) {
                    err.printf("Связанный файл морфологии не найден: %s%n", morphCandidate);
                    return 2;
                }
                return runSinglePair(morphCandidate, single);
            }
        }

        if (args.length == 2 && !isOption(args[0]) && !isOption(args[1])) {
            Path morph = Path.of(args[0]);
            Path original = Path.of(args[1]);
            if (looksLikeMorphFile(morph) && looksLikeOriginalFile(original)) {
                return runSinglePair(morph, original);
            }
        }

        Path originalDir = null;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--original-dir".equals(arg) || "-o".equals(arg)) {
                if (i + 1 >= args.length) {
                    err.println("Опция --original-dir требует путь к каталогу с оригинальными текстами.");
                    return 1;
                }
                originalDir = Path.of(args[++i]);
                if (!Files.exists(originalDir)) {
                    err.printf("Каталог оригинальных текстов не найден: %s%n", originalDir);
                    return 2;
                }
                if (!Files.isDirectory(originalDir)) {
                    err.printf("Путь не является каталогом: %s%n", originalDir);
                    return 2;
                }
                continue;
            }
            Path file = Path.of(arg);
            if (!Files.exists(file)) {
                err.printf("Файл не найден: %s%n", file);
                return 2;
            }
            if (!Files.isRegularFile(file)) {
                err.printf("Не является файлом: %s%n", file);
                return 2;
            }
            files.add(file);
        }

        if (files.isEmpty()) {
            err.println("Не указаны входные *.morph3.tsv файлы.");
            printUsage();
            return 1;
        }

        int failures = 0;
        for (Path file : files) {
            try {
                processFile(file, originalDir, null);
            } catch (IOException ex) {
                failures++;
                err.printf("Не удалось обработать файл %s: %s%n", file, ex.getMessage());
            } catch (IllegalStateException ex) {
                failures++;
                err.printf("Ошибка совмещения текста и морфологии для %s: %s%n", file, ex.getMessage());
            }
        }

        if (failures > 0) {
            err.printf("Завершено с ошибками (%d файлов не обработано).%n", failures);
            return 3;
        }

        return 0;
    }

    private int runSinglePair(Path morphFile, Path originalFile) {
        if (!Files.exists(morphFile)) {
            err.printf("Файл морфологии не найден: %s%n", morphFile);
            return 2;
        }
        if (!Files.isRegularFile(morphFile)) {
            err.printf("Не является файлом морфологии: %s%n", morphFile);
            return 2;
        }
        if (!Files.exists(originalFile)) {
            err.printf("Оригинальный текст не найден: %s%n", originalFile);
            return 2;
        }
        if (!Files.isRegularFile(originalFile)) {
            err.printf("Путь не является файлом оригинального текста: %s%n", originalFile);
            return 2;
        }

        try {
            processFile(morphFile, null, originalFile);
        } catch (IOException ex) {
            err.printf("Не удалось обработать файл %s: %s%n", morphFile, ex.getMessage());
            return 3;
        } catch (IllegalStateException ex) {
            err.printf("Ошибка совмещения текста и морфологии для %s: %s%n", morphFile, ex.getMessage());
            return 3;
        }
        return 0;
    }

    private boolean isOption(String arg) {
        return arg != null && arg.startsWith("-");
    }

    private boolean looksLikeMorphFile(Path path) {
        if (path == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".morph3.tsv");
    }

    private boolean looksLikeOriginalFile(Path path) {
        if (path == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".txt");
    }

    private void printUsage() {
        err.println("Использование: java -cp web-app-<версия>.jar "
                + "com.example.uqureader.webapp.cli.Morph3Fb2Exporter [--original-dir <каталог>] "
                + "<файл.morph3.tsv> [<файл.morph3.tsv> ...]");
        err.println("Также можно указать пару файлов: <файл.morph3.tsv> <оригинал.txt>" +
                " или один путь к оригиналу <оригинал.txt>, если файл морфологии" +
                " находится рядом с расширением .morph3.tsv.");
        err.println("Инструмент создаёт fb2-файл рядом с исходной морфоразметкой," +
                " добавляя перевод и теги для каждого токена.");
        err.println("Если оригинальные тексты находятся в отдельном каталоге, задайте его через --original-dir.");
    }

    private void processFile(Path morphFile, Path originalDir, Path explicitOriginal) throws IOException {
        List<MorphToken> tokens = readMorphFile(morphFile);
        if (tokens.isEmpty()) {
            err.printf("Предупреждение: файл %s пуст — пропущен.%n", morphFile);
            return;
        }

        Path original = explicitOriginal != null ? explicitOriginal : locateOriginalFile(morphFile, originalDir);
        String originalText = Files.readString(original, StandardCharsets.UTF_8);
        List<Paragraph> paragraphs = alignTokensWithText(tokens, originalText);

        Path output = deriveOutputPath(morphFile, original);
        writeFb2(output, morphFile, original, paragraphs);

        out.printf("# %s → %s (%d абзацев, %d токенов)%n",
                morphFile,
                output,
                paragraphs.size(),
                tokens.size());
    }

    private List<MorphToken> readMorphFile(Path morphFile) throws IOException {
        List<MorphToken> tokens = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(morphFile, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                if (columns.length < 2) {
                    throw new IOException("Некорректная строка (ожидались минимум 2 столбца) в "
                            + morphFile + ": " + lineNumber);
                }
                String token = columns[0];
                String analysis = columns[1];
                String translation = columns.length >= 3 ? columns[2] : "";
                tokens.add(new MorphToken(token, analysis, translation));
            }
        }
        return tokens;
    }

    private Path locateOriginalFile(Path morphFile, Path explicitDir) {
        String fileName = morphFile.getFileName().toString();
        String baseName = stripMorphSuffix(fileName);
        if (baseName == null) {
            throw new IllegalStateException("Не удалось определить базовое имя для файла " + morphFile);
        }

        List<String> possibleNames = new ArrayList<>();
        possibleNames.add(baseName);
        String lowerBase = baseName.toLowerCase(Locale.ROOT);
        if (!lowerBase.endsWith(".txt")) {
            possibleNames.add(baseName + ".txt");
        }

        Set<Path> candidates = new LinkedHashSet<>();
        Path parent = morphFile.getParent();
        if (parent != null) {
            for (String name : possibleNames) {
                candidates.add(parent.resolve(name));
            }
            Path resources = parent.getParent();
            if (resources != null) {
                for (String name : possibleNames) {
                    candidates.add(resources.resolve(name));
                    candidates.add(resources.resolve("texts").resolve(name));
                }
            }
        }
        if (explicitDir != null) {
            for (String name : possibleNames) {
                candidates.add(explicitDir.resolve(name));
            }
        }
        for (Path candidate : candidates) {
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Оригинальный файл " + baseName + " для " + morphFile + " не найден.");
    }

    private String stripMorphSuffix(String fileName) {
        int idx = fileName.lastIndexOf(".morph3");
        if (idx < 0) {
            return null;
        }
        String base = fileName.substring(0, idx);
        String tail = fileName.substring(idx + ".morph3".length());
        if (tail.equals(".tsv")) {
            return base;
        }
        return base + tail;
    }

    private List<String> buildTokenCandidates(String token) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(token);
        boolean changed;
        do {
            changed = false;
            List<String> snapshot = new ArrayList<>(candidates);
            for (String value : snapshot) {
                for (Replacement replacement : MATCH_REPLACEMENTS) {
                    if (value.contains(replacement.from())) {
                        String replaced = value.replace(replacement.from(), replacement.to());
                        if (candidates.add(replaced)) {
                            changed = true;
                        }
                    }
                }
            }
        } while (changed);
        return new ArrayList<>(candidates);
    }

    private List<Paragraph> alignTokensWithText(List<MorphToken> tokens, String text) {
        List<Paragraph> paragraphs = new ArrayList<>();
        Paragraph current = new Paragraph();
        int morphIndex = 0;
        int length = text.length();
        int pos = 0;
        while (pos < length) {
            int whitespaceEnd = pos;
            while (whitespaceEnd < length && Character.isWhitespace(text.charAt(whitespaceEnd))) {
                whitespaceEnd++;
            }
            if (whitespaceEnd > pos) {
                String whitespace = text.substring(pos, whitespaceEnd);
                current = processWhitespace(whitespace, paragraphs, current);
                pos = whitespaceEnd;
                continue;
            }
            if (pos >= length) {
                break;
            }
            String surface = nextToken(text, pos);
            if (surface.isEmpty()) {
                pos++;
                continue;
            }
            MatchResult match = findMatchingToken(tokens, morphIndex, surface);
            if (match != null && match.token() != null) {
                current.addToken(match.token(), surface);
                morphIndex = match.nextIndex();
            } else {
                current.addToken(new MorphToken(surface, "", ""), surface);
            }
            pos += surface.length();
        }
        if (pos < length) {
            String trailing = text.substring(pos);
            current = processWhitespace(trailing, paragraphs, current);
        }
        if (!current.isEmpty() || paragraphs.isEmpty()) {
            paragraphs.add(current);
        }
        return paragraphs;
    }

    private String nextToken(String text, int pos) {
        int length = text.length();
        if (pos >= length) {
            return "";
        }
        int index = pos;
        char first = text.charAt(index);
        if (isWordStart(first)) {
            index++;
            while (index < length) {
                char ch = text.charAt(index);
                if (isWordContinuation(ch, text, index)) {
                    index++;
                    continue;
                }
                break;
            }
            return text.substring(pos, index);
        }
        int end = index + 1;
        while (end < length) {
            char ch = text.charAt(end);
            if (Character.isWhitespace(ch)) {
                break;
            }
            if (isWordStart(ch)) {
                break;
            }
            if (ch != first) {
                break;
            }
            end++;
        }
        return text.substring(pos, end);
    }

    private boolean isWordStart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '\'' || ch == '\u2019' || ch == '\u02BC';
    }

    private boolean isWordContinuation(char ch, String text, int index) {
        if (Character.isLetterOrDigit(ch) || ch == '\'' || ch == '\u2019' || ch == '\u02BC') {
            return true;
        }
        if (ch == '\u2026') {
            return true;
        }
        if (ch == '.' && hasNeighbouringEllipsisDot(text, index)) {
            return true;
        }
        if ((ch == '-' || ch == '\u2014' || ch == '\u2013') && index + 1 < text.length()) {
            char next = text.charAt(index + 1);
            if (Character.isLetterOrDigit(next)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNeighbouringEllipsisDot(String text, int index) {
        if (index > 0 && text.charAt(index - 1) == '.') {
            return true;
        }
        return index + 1 < text.length() && text.charAt(index + 1) == '.';
    }

    private MatchResult findMatchingToken(List<MorphToken> tokens, int startIndex, String surface) {
        int index = startIndex;
        while (index < tokens.size()) {
            MorphToken token = tokens.get(index);
            if (matchesSurface(token, surface)) {
                return new MatchResult(token, index + 1);
            }
            if (isSkippable(token)) {
                index++;
                continue;
            }
            break;
        }
        if (isPunctuationToken(surface)) {
            return new MatchResult(new MorphToken(surface, "", ""), startIndex);
        }
        if (index < tokens.size()) {
            MorphToken fallback = tokens.get(index);
            return new MatchResult(fallback, index + 1);
        }
        return new MatchResult(new MorphToken(surface, "", ""), startIndex);
    }

    private boolean matchesSurface(MorphToken token, String surface) {
        List<String> candidates = candidateTokenValues(token);
        if (candidates.isEmpty()) {
            return false;
        }
        String lowerSurface = surface.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            for (String variant : buildTokenCandidates(candidate)) {
                String trimmed = variant.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (surface.equals(trimmed)) {
                    return true;
                }
                if (lowerSurface.equals(trimmed.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> candidateTokenValues(MorphToken token) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (token.token() != null && !token.token().isBlank()) {
            values.add(token.token().trim());
        }
        String fromAnalysis = deriveCandidateFromField(token.analysis());
        if (fromAnalysis != null) {
            values.add(fromAnalysis);
        }
        String fromTranslation = deriveCandidateFromField(token.translation());
        if (fromTranslation != null) {
            values.add(fromTranslation);
        }
        return new ArrayList<>(values);
    }

    private String deriveCandidateFromField(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.equalsIgnoreCase("Error")
                || trimmed.equalsIgnoreCase("NR")
                || trimmed.startsWith("Type")) {
            return null;
        }
        if (trimmed.contains("+")) {
            return null;
        }
        return trimmed;
    }

    private boolean isSkippable(MorphToken token) {
        List<String> candidates = candidateTokenValues(token);
        if (candidates.isEmpty()) {
            return true;
        }
        for (String candidate : candidates) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.equalsIgnoreCase("Error")
                    && !trimmed.equalsIgnoreCase("Type1")
                    && !trimmed.equalsIgnoreCase("Type2")
                    && !trimmed.equalsIgnoreCase("Type3")
                    && !trimmed.equalsIgnoreCase("Type4")) {
                return false;
            }
        }
        return true;
    }

    private boolean isPunctuationToken(String surface) {
        if (surface == null || surface.isEmpty()) {
            return false;
        }
        for (int i = 0; i < surface.length(); i++) {
            if (Character.isLetterOrDigit(surface.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private Paragraph processWhitespace(String whitespace, List<Paragraph> paragraphs, Paragraph current) {
        if (whitespace == null || whitespace.isEmpty()) {
            return current;
        }
        StringBuilder buffer = new StringBuilder();
        int length = whitespace.length();
        for (int i = 0; i < length; i++) {
            char ch = whitespace.charAt(i);
            if (ch == '\r') {
                if (i + 1 < length && whitespace.charAt(i + 1) == '\n') {
                    i++;
                }
                flushBuffer(buffer, current);
                paragraphs.add(current);
                current = new Paragraph();
                continue;
            }
            if (ch == '\n') {
                flushBuffer(buffer, current);
                paragraphs.add(current);
                current = new Paragraph();
                continue;
            }
            buffer.append(ch);
        }
        flushBuffer(buffer, current);
        return current;
    }

    private void flushBuffer(StringBuilder buffer, Paragraph current) {
        if (buffer.length() > 0) {
            current.appendText(buffer.toString());
            buffer.setLength(0);
        }
    }

    private Path deriveOutputPath(Path morphFile, Path original) {
        String title = deriveBookTitle(original);
        Path parent = morphFile.getParent();
        if (parent == null) {
            return morphFile.resolveSibling(title + ".fb2");
        }
        return parent.resolve(title + ".fb2");
    }

    private void writeFb2(Path output,
                          Path morphFile,
                          Path original,
                          List<Paragraph> paragraphs) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String bookTitle = deriveBookTitle(original);
        String language = "tt";
        LocalDate today = LocalDate.now();

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
            writer.write("<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" ");
            writer.write("xmlns:l=\"http://www.w3.org/1999/xlink\" ");
            writer.write("xmlns:m=\"urn:uqureader:morph\">\n");
            writer.write("  <description>\n");
            writer.write("    <title-info>\n");
            writer.write("      <genre>foreign-education</genre>\n");
            writer.write("      <book-title>" + escapeText(bookTitle) + "</book-title>\n");
            writer.write("      <lang>" + language + "</lang>\n");
            writer.write("      <src-lang>" + language + "</src-lang>\n");
            writer.write("    </title-info>\n");
            writer.write("    <document-info>\n");
            writer.write("      <program-used>UquReader Morph3Fb2Exporter</program-used>\n");
            writer.write("      <date value=\"" + DATE_FORMAT.format(today) + "\">"
                    + DATE_FORMAT.format(today) + "</date>\n");
            writer.write("      <source-url>" + escapeText(original.toString()) + "</source-url>\n");
            writer.write("      <src-ocr>" + escapeText(morphFile.toString()) + "</src-ocr>\n");
            writer.write("    </document-info>\n");
            writer.write("  </description>\n");
            writer.write("  <body>\n");
            writer.write("    <section>\n");
            for (Paragraph paragraph : paragraphs) {
                writeParagraph(writer, paragraph);
            }
            writer.write("    </section>\n");
            writer.write("  </body>\n");
            writer.write("</FictionBook>\n");
        }
    }

    private void writeParagraph(BufferedWriter writer, Paragraph paragraph) throws IOException {
        if (paragraph.isEmpty()) {
            writer.write("      <p xml:space=\"preserve\"/>\n");
            return;
        }
        writer.write("      <p xml:space=\"preserve\">");
        for (ParagraphItem item : paragraph.items()) {
            if (item instanceof TextItem textItem) {
                writer.write(escapeText(textItem.text()));
            } else if (item instanceof WordItem wordItem) {
                MorphToken token = wordItem.token();
                writer.write("<m:w");
                String analysis = token.analysis().trim();
                if (!analysis.isEmpty()) {
                    writer.write(" analysis=\"" + escapeAttribute(analysis) + "\"");
                }
                String translation = token.translation().trim();
                if (!translation.isEmpty()) {
                    writer.write(" translation=\"" + escapeAttribute(translation) + "\"");
                }
                String surface = wordItem.surface();
                if (!surface.isEmpty()) {
                    writer.write(" surface=\"" + escapeAttribute(surface) + "\"");
                }
                writer.write("/>");
                writer.write(escapeText(surface));
            }
        }
        writer.write("</p>\n");
    }

    private String deriveBookTitle(Path original) {
        String fileName = original.getFileName().toString();
        int idx = fileName.lastIndexOf('.');
        String base = idx > 0 ? fileName.substring(0, idx) : fileName;
        base = base.replace('_', ' ').trim();
        if (base.isEmpty()) {
            base = fileName;
        }
        if (!base.isEmpty()) {
            base = base.substring(0, 1).toUpperCase(Locale.ROOT) + base.substring(1);
        }
        return base;
    }

    private String escapeText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String escaped = value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return escaped;
    }

    private String escapeAttribute(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String escaped = value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
        return escaped;
    }

    private interface ParagraphItem { }

    private static final class Paragraph {
        private final List<ParagraphItem> items = new ArrayList<>();

        void appendText(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            if (!items.isEmpty()) {
                ParagraphItem last = items.get(items.size() - 1);
                if (last instanceof TextItem textItem) {
                    textItem.append(text);
                    return;
                }
            }
            items.add(new TextItem(text));
        }

        void addToken(MorphToken token, String surface) {
            items.add(new WordItem(token, surface));
        }

        boolean isEmpty() {
            return items.isEmpty();
        }

        List<ParagraphItem> items() {
            return items;
        }
    }

    private static final class TextItem implements ParagraphItem {
        private final StringBuilder builder;

        TextItem(String value) {
            this.builder = new StringBuilder(value == null ? "" : value);
        }

        void append(String value) {
            if (value != null) {
                builder.append(value);
            }
        }

        String text() {
            return builder.toString();
        }
    }

    private static final class WordItem implements ParagraphItem {
        private final MorphToken token;
        private final String surface;

        WordItem(MorphToken token, String surface) {
            this.token = Objects.requireNonNull(token, "token");
            this.surface = Objects.requireNonNull(surface, "surface");
        }

        MorphToken token() {
            return token;
        }

        String surface() {
            return surface;
        }
    }

    private record MatchResult(MorphToken token, int nextIndex) { }

    private record MorphToken(String token, String analysis, String translation) { }

    private record Replacement(String from, String to) { }
}
