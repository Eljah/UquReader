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
import java.util.Collections;
import java.util.List;

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
                                tokens.add(token);
                            }
                            currentStyle = null;
                        }
                    }
                    if (TAG_STYLE.equals(endName) && currentStyle != null && currentStyle.depth == 0) {
                        currentStyle = null;
                    }
                    if (TAG_PARAGRAPH.equals(endName)) {
                        insideParagraph = false;
                        if (insideBody) {
                            prefix.append('\n');
                            flushPrefix(tokens, prefix);
                        } else {
                            prefix.setLength(0);
                        }
                    } else if (TAG_BODY.equals(endName)) {
                        insideBody = false;
                    }
                    break;
                default:
                    break;
            }
            event = parser.next();
        }
        flushPrefix(tokens, prefix);
        return tokens;
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

    private static void flushPrefix(List<Token> tokens, StringBuilder prefix) {
        if (prefix.length() == 0) {
            return;
        }
        Token token = new Token();
        token.prefix = prefix.toString();
        token.surface = "";
        tokens.add(token);
        prefix.setLength(0);
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
