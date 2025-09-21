package com.example.ttreader.util;

import android.text.TextUtils;

import com.example.ttreader.model.Morphology;
import com.example.ttreader.reader.TokenSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/**
 * Utility methods for building and sanitizing the speech strings used when reading token details.
 */
public final class DetailSpeechFormatter {

    private DetailSpeechFormatter() {}

    /**
     * Returns a cleaned list of translations that removes empty entries, trims whitespace (including
     * non-breaking spaces) and de-duplicates while preserving order.
     */
    public static List<String> sanitizeTranslations(List<String> translations) {
        if (translations == null || translations.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : translations) {
            if (value == null) continue;
            String trimmed = trimUnicodeWhitespace(value);
            if (trimmed.isEmpty()) continue;
            unique.add(trimmed);
        }
        if (unique.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(unique);
    }

    /**
     * Builds the string that should be spoken for token details.
     *
     * @param span         the token span to describe
     * @param translations the translations associated with the lemma; if they are already sanitized
     *                     pass {@code alreadySanitized = true} to avoid re-processing
     */
    public static String buildDetailSpeech(TokenSpan span, List<String> translations) {
        return buildDetailSpeech(span, translations, false);
    }

    public static String buildDetailSpeech(TokenSpan span, List<String> translations, boolean alreadySanitized) {
        if (span == null || span.token == null) {
            return "";
        }

        List<String> safeTranslations = alreadySanitized
                ? ensureList(translations)
                : sanitizeTranslations(translations);

        StringBuilder builder = new StringBuilder();
        String surface = span.token.surface;
        if (!TextUtils.isEmpty(surface)) {
            builder.append(surface);
        }

        Morphology morph = span.token.morphology;
        if (morph != null) {
            if (!TextUtils.isEmpty(morph.lemma)) {
                appendSentence(builder, "Лемма: " + morph.lemma);
            }
            if (!TextUtils.isEmpty(morph.pos)) {
                appendSentence(builder, "Часть речи: " + GrammarResources.formatPos(morph.pos));
            }
        }

        if (!safeTranslations.isEmpty()) {
            appendSentence(builder, "Перевод: " + TextUtils.join(", ", safeTranslations));
        } else {
            appendSentence(builder, "Перевод не найден");
        }

        return builder.toString();
    }

    private static List<String> ensureList(List<String> translations) {
        return translations == null ? Collections.emptyList() : translations;
    }

    private static void appendSentence(StringBuilder builder, String sentence) {
        if (TextUtils.isEmpty(sentence)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('.').append(' ');
        }
        builder.append(sentence);
    }

    private static String trimUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        if (start == 0 && end == value.length()) {
            return value;
        }
        return value.substring(start, end);
    }
}
