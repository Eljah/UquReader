package com.example.ttreader.util;

import com.example.ttreader.model.MorphFeature;
import com.example.ttreader.model.Morphology;
import com.example.ttreader.model.FeatureMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MorphologyParser {
    public static Morphology parse(String surface, String analysis) {
        String selectedAnalysis = firstAnalysis(analysis);
        if (selectedAnalysis == null || !selectedAnalysis.contains("+")) return null;
        String[] parts = selectedAnalysis.split("\\+");
        if (parts.length < 2) return null;
        String lemma = parts[0];
        String pos = parts[1];
        List<MorphFeature> features = new ArrayList<>();
        for (int i = 2; i < parts.length; i++) {
            String part = parts[i];
            String code = part;
            String canonical = null;
            int paren = part.indexOf('(');
            if (paren >= 0 && part.endsWith(")")) {
                code = part.substring(0, paren);
                canonical = part.substring(paren + 1, part.length() - 1);
            }
            MorphFeature feature = new MorphFeature(code, canonical);
            features.add(feature);
        }
        String safeSurface = surface == null ? "" : surface;
        String[] actuals = resolveActualsRightToLeft(safeSurface, features);
        for (int i = 0; i < features.size(); i++) {
            features.get(i).actual = actuals[i];
        }
        List<String> segments = buildSegments(lemma, safeSurface, actuals);
        String featureKey = buildFeatureKey(pos, features);
        return new Morphology(lemma, pos, features, segments, featureKey, analysis);
    }

    private static String firstAnalysis(String analysis) {
        if (analysis == null) return null;
        for (String variant : analysis.split(";")) {
            String trimmed = variant.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return null;
    }

    private static String[] resolveActualsRightToLeft(String surface, List<MorphFeature> features) {
        String[] actuals = new String[features.size()];
        String remaining = surface == null ? "" : surface;
        for (int i = features.size() - 1; i >= 0; i--) {
            MorphFeature feature = features.get(i);
            String actual = matchSuffix(remaining, candidateForms(feature.code, feature.canonical));
            actuals[i] = actual;
            if (!actual.isEmpty()) {
                remaining = remaining.substring(0, remaining.length() - actual.length());
            }
        }
        return actuals;
    }

    private static List<String> buildSegments(String lemma, String surface, String[] actuals) {
        List<String> segments = new ArrayList<>();
        int endingLength = 0;
        for (String actual : actuals) {
            if (actual != null) endingLength += actual.length();
        }
        String surfaceBase = surface.substring(0, Math.max(0, surface.length() - endingLength));
        String base = lemma == null || lemma.isEmpty() ? surfaceBase : lemma;
        if (!base.isEmpty()) segments.add(base);
        for (String actual : actuals) {
            if (actual != null && !actual.isEmpty()) segments.add(actual);
        }
        if (segments.isEmpty() && !surface.isEmpty()) segments.add(surface);
        return segments;
    }

    private static String matchSuffix(String value, List<String> forms) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String form : forms) {
            String normalized = normalizeForm(form);
            if (normalized.isEmpty()) continue;
            if (lower.endsWith(normalized.toLowerCase(Locale.ROOT))) {
                return value.substring(value.length() - normalized.length());
            }
        }
        return "";
    }

    private static List<String> candidateForms(String code, String canonical) {
        List<String> forms = new ArrayList<>();
        FeatureMetadata metadata = GrammarResources.getFeatureMetadata(code);
        if (metadata != null) {
            forms.addAll(metadata.phoneticForms);
        }
        if (canonical != null && !canonical.isEmpty()) {
            Collections.addAll(forms, canonical.split("/"));
        }
        forms.removeIf(form -> normalizeForm(form).isEmpty());
        forms.sort(Comparator.comparingInt((String form) -> normalizeForm(form).length()).reversed());
        return forms;
    }

    private static String normalizeForm(String form) {
        if (form == null) return "";
        String normalized = form.trim();
        if (normalized.startsWith("-")) {
            normalized = normalized.substring(1);
        }
        if (normalized.equalsIgnoreCase("нулевое окончание")) {
            return "";
        }
        return normalized;
    }

    private static String buildFeatureKey(String pos, List<MorphFeature> features) {
        StringBuilder sb = new StringBuilder(pos);
        boolean hasFeature = false;
        for (MorphFeature f : features) {
            if (f.code == null || f.code.isEmpty()) continue;
            sb.append('+').append(f.code);
            hasFeature = true;
        }
        return hasFeature ? sb.toString() : pos;
    }
}
