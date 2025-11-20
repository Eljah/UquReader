package com.example.ttreader.util;

import android.content.Context;
import android.util.Xml;

import com.example.ttreader.model.Token;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parser for FictionBook 2.0 documents produced by the Morph3Fb2Exporter.
 *
 * <p>The exporter encodes each token inside a {@code <style name="morph">}
 * element and stores morphology and translations in custom namespace attributes.
 * This parser extracts those tokens and converts them into {@link Token}
 * instances that match the structure used by JSONL based sources.</p>
 */
public final class Fb2MorphParser {

    private static final String NS_MORPH = "urn:uqureader:morph";
    private static final String TAG_BODY = "body";
    private static final String TAG_PARAGRAPH = "p";
    private static final String TAG_STYLE = "style";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_ANALYSIS = "analysis";
    private static final String ATTR_TRANSLATION = "translation";
    private static final String ATTR_SURFACE = "surface";
    private static final String MORPH_STYLE_NAME = "morph";
    private static final Set<Character> CLOSING_PUNCT_CHARS = new HashSet<>(Arrays.asList(
            ',', '.', '!', '?', ';', ':', '…', '»', '”', ')', ']', '}'
    ));
    private static final Set<Character> BREAKABLE_WS_CHARS = new HashSet<>(Arrays.asList(
            ' ', '\u00A0', '\u202F', '\u2009', '\u200A', '\u200B', '\u2060'
    ));
    private static final char NARROW_NBSP = '\u202F';
    private static final char WORD_JOINER = '\u2060';
    private static final char ZERO_WIDTH_NBSP = '\uFEFF';

    private Fb2MorphParser() {
    }

    /**
     * Parses the specified asset and returns a list of tokens.
     *
     * @param context   Android context used to open assets
     * @param assetName asset path inside {@code assets/}
     * @return list of tokens parsed from the asset
     * @throws IOException when the asset cannot be opened or parsed
     */
    public static List<Token> parseAsset(Context context, String assetName) throws IOException {
        try (InputStream input = context.getAssets().open(assetName)) {
            return parse(input);
        }
    }

    /**
     * Parses a FB2 document provided through an input stream.
     *
     * <p>The caller retains ownership of the stream and is responsible for
     * closing it.</p>
     *
     * @param input input stream with FB2 content encoded as UTF-8
     * @return tokens extracted from the stream
     * @throws IOException when parsing fails
     */
    public static List<Token> parse(InputStream input) throws IOException {
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setInput(new InputStreamReader(input, StandardCharsets.UTF_8));
            return parse(parser);
        } catch (XmlPullParserException ex) {
            throw new IOException("Failed to parse FB2 document", ex);
        }
    }

    private static List<Token> parse(XmlPullParser parser) throws IOException, XmlPullParserException {
        List<Token> tokens = new ArrayList<>();
        StringBuilder prefix = new StringBuilder();
        MorphStyle currentStyle = null;
        boolean insideBody = false;
        boolean insideParagraph = false;

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            switch (event) {
                case XmlPullParser.START_TAG:
                    if (TAG_BODY.equals(parser.getName())) {
                        insideBody = true;
                    } else if (insideBody && TAG_PARAGRAPH.equals(parser.getName())) {
                        insideParagraph = true;
                    }
                    if (insideBody && insideParagraph && TAG_STYLE.equals(parser.getName())) {
                        String styleName = parser.getAttributeValue(null, ATTR_NAME);
                        if (MORPH_STYLE_NAME.equals(styleName)) {
                            currentStyle = startMorphStyle(prefix, parser);
                        }
                    }
                    if (currentStyle != null && currentStyle != MorphStyle.EMPTY) {
                        currentStyle.depth++;
                    }
                    break;
                case XmlPullParser.TEXT:
                    if (currentStyle != null && currentStyle.depth > 0) {
                        currentStyle.text.append(parser.getText());
                    } else if (insideBody && insideParagraph) {
                        prefix.append(parser.getText());
                    }
                    break;
                case XmlPullParser.END_TAG:
                    String endName = parser.getName();
                    if (currentStyle != null && currentStyle.depth > 0) {
                        currentStyle.depth--;
                        if (currentStyle.depth == 0 && TAG_STYLE.equals(endName)) {
                            Token token = currentStyle.buildToken();
                            if (token != null) {
                                addTokenRespectingPunctuationBinding(tokens, token);
                            }
                            currentStyle = null;
                        }
                    }
                    if (TAG_STYLE.equals(endName) && currentStyle != null && currentStyle.depth == 0) {
                        currentStyle = null;
                    }
                    if (insideBody && TAG_PARAGRAPH.equals(endName)) {
                        insideParagraph = false;
                        if (prefix.length() > 0) {
                            addSyntheticToken(tokens, prefix.toString(), "");
                            prefix.setLength(0);
                        }
                        addSyntheticToken(tokens, "", "\n");
                    } else if (TAG_BODY.equals(endName)) {
                        if (prefix.length() > 0) {
                            addSyntheticToken(tokens, prefix.toString(), "");
                            prefix.setLength(0);
                        }
                        insideParagraph = false;
                        insideBody = false;
                    }
                    break;
                default:
                    break;
            }
            event = parser.next();
        }
        return tokens;
    }

    private static void addTokenRespectingPunctuationBinding(List<Token> tokens, Token token) {
        if (tokens == null || token == null) {
            return;
        }
        Token target = findAttachmentTarget(tokens, token);
        if (target != null) {
            gluePunctuationIntoTarget(target, token);
            return;
        }
        tokens.add(token);
    }

    private static Token findAttachmentTarget(List<Token> tokens, Token punctuationToken) {
        if (tokens == null || tokens.isEmpty() || punctuationToken == null) {
            return null;
        }
        if (punctuationToken.hasMorphology()) {
            return null;
        }
        String surfacePreview = safeTrim(punctuationToken.surface);
        if (!isClosingPunctuationOrDash(surfacePreview)) {
            return null;
        }
        if (hasVisiblePrefixContent(punctuationToken)) {
            return null;
        }
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token candidate = tokens.get(i);
            if (candidate != null && candidate.hasMorphology()) {
                return candidate;
            }
        }
        return null;
    }

    private static void gluePunctuationIntoTarget(Token target, Token punctuationToken) {
        StringBuilder surface = new StringBuilder(safeValue(target.surface));
        bindClosingPunctuationToSurface(surface);
        String glue = normalizeGluePrefix(punctuationToken.prefix);
        if (!glue.isEmpty()) {
            surface.append(glue);
        }
        surface.append(safeValue(punctuationToken.surface));
        target.surface = surface.toString();
    }

    private static boolean hasVisiblePrefixContent(Token token) {
        if (token == null || token.prefix == null) {
            return false;
        }
        for (int i = 0; i < token.prefix.length(); i++) {
            char c = token.prefix.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    private static void bindClosingPunctuationToSurface(StringBuilder surface) {
        if (surface == null || surface.length() == 0) {
            return;
        }
        int last = surface.length() - 1;
        char c = surface.charAt(last);
        if (c == WORD_JOINER || c == ZERO_WIDTH_NBSP) {
            return;
        }
        if (BREAKABLE_WS_CHARS.contains(c)) {
            if (c != NARROW_NBSP) {
                surface.setCharAt(last, NARROW_NBSP);
            }
        } else {
            surface.append(WORD_JOINER);
            surface.append(ZERO_WIDTH_NBSP);
        }
    }

    private static String normalizeGluePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        String collapsed = prefix.replaceAll("[\\r\\n]+", " ");
        collapsed = collapsed.replaceAll("[ \t\\u00A0\\u202F\\u2009\\u200A\\u200B]+", String.valueOf(NARROW_NBSP));
        return collapsed;
    }

    private static boolean isClosingPunctuationOrDash(String s) {
        if (s == null || s.isEmpty()) return false;
        char first = s.charAt(0);
        if (CLOSING_PUNCT_CHARS.contains(first)) {
            return true;
        }
        return s.length() == 1 && (first == '—' || first == '–' || first == '-');
    }

    private static String safeValue(String value) {
        return value == null ? "" : value;
    }

    private static MorphStyle startMorphStyle(StringBuilder prefix, XmlPullParser parser) {
        String styleName = parser.getAttributeValue(null, ATTR_NAME);
        if (!MORPH_STYLE_NAME.equals(styleName)) {
            return MorphStyle.EMPTY;
        }
        MorphStyle style = new MorphStyle();
        style.prefix = prefix.toString();
        prefix.setLength(0);
        style.analysis = safeTrim(parser.getAttributeValue(NS_MORPH, ATTR_ANALYSIS));
        style.translation = safeTrim(parser.getAttributeValue(NS_MORPH, ATTR_TRANSLATION));
        style.surfaceAttr = parser.getAttributeValue(NS_MORPH, ATTR_SURFACE);
        style.depth = 0;
        return style;
    }

    private static String safeTrim(String value) {
        return value == null ? null : value.trim();
    }

    private static void addSyntheticToken(List<Token> tokens, String prefixText, String surfaceText) {
        if (tokens == null) {
            return;
        }
        Token token = new Token();
        token.prefix = prefixText == null ? "" : prefixText;
        token.surface = surfaceText == null ? "" : surfaceText;
        tokens.add(token);
    }

    private static final class MorphStyle {
        static final MorphStyle EMPTY = new MorphStyle();

        String prefix = "";
        String analysis;
        String translation;
        String surfaceAttr;
        StringBuilder text = new StringBuilder();
        int depth;

        Token buildToken() {
            Token token = new Token();
            token.prefix = prefix;

            String surface = surfaceAttr != null ? surfaceAttr : text.toString();
            if (surface == null) {
                surface = "";
            }
            token.surface = surface;

            String analysisValue = analysis != null ? analysis : "";
            token.analysis = analysisValue;
            if (!analysisValue.isEmpty()) {
                token.morphology = MorphologyParser.parse(token.surface, analysisValue);
            }

            List<String> translations = parseTranslations(translation);
            if (!translations.isEmpty()) {
                token.translations = translations;
            }
            return token;
        }
    }

    private static List<String> parseTranslations(String raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = trimmed.split(";");
        List<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            String item = part.trim();
            if (!item.isEmpty()) {
                values.add(item);
            }
        }
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        return values;
    }
}
