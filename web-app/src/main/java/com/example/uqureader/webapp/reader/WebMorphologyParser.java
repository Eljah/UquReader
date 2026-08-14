package com.example.uqureader.webapp.reader;

import java.util.ArrayList;
import java.util.List;

public final class WebMorphologyParser {
    private WebMorphologyParser() {
    }

    public static MorphologyData parse(String surface, String analysis) {
        if (analysis == null || !analysis.contains("+")) {
            return null;
        }
        String[] parts = analysis.split("\\+");
        if (parts.length < 2) {
            return null;
        }
        String lemma = parts[0];
        String pos = parts[1];
        List<MorphFeatureData> features = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        for (int i = 2; i < parts.length; i++) {
            String part = parts[i];
            String code = part;
            String canonical = "";
            int paren = part.indexOf('(');
            if (paren >= 0 && part.endsWith(")")) {
                code = part.substring(0, paren);
                canonical = part.substring(paren + 1, part.length() - 1);
            }
            lengths.add(estimateLength(canonical));
            features.add(new MorphFeatureData(code, canonical, ""));
        }

        String safeSurface = surface == null ? "" : surface;
        List<String> segments = new ArrayList<>();
        int totalEndings = 0;
        for (Integer len : lengths) {
            totalEndings += len;
        }
        int baseLen = Math.max(0, Math.min(safeSurface.length(), safeSurface.length() - totalEndings));
        String baseSegment = safeSubstring(safeSurface, 0, baseLen);
        segments.add(baseSegment);
        int idx = baseSegment.length();
        List<MorphFeatureData> resolved = new ArrayList<>();
        for (MorphFeatureData feature : features) {
            String actual = resolveActual(safeSurface, idx, feature.canonical);
            resolved.add(new MorphFeatureData(feature.code, feature.canonical, actual));
            if (!actual.isEmpty()) {
                segments.add(actual);
            }
            idx += actual.length();
        }
        if (idx < safeSurface.length()) {
            int last = Math.max(0, segments.size() - 1);
            segments.set(last, segments.get(last) + safeSurface.substring(idx));
        }
        return new MorphologyData(lemma, pos, resolved, segments, buildFeatureKey(pos, resolved), analysis);
    }

    private static String safeSubstring(String value, int start, int end) {
        if (start >= value.length()) {
            return "";
        }
        return value.substring(start, Math.max(start, Math.min(end, value.length())));
    }

    private static int estimateLength(String canonical) {
        if (canonical == null || canonical.isEmpty()) {
            return 0;
        }
        return canonical.split("/")[0].length();
    }

    private static String resolveActual(String surface, int idx, String canonical) {
        if (canonical == null || canonical.isEmpty() || idx >= surface.length()) {
            return "";
        }
        String[] options = canonical.split("/");
        int remaining = surface.length() - idx;
        for (String option : options) {
            if (option.isEmpty()) {
                continue;
            }
            int len = Math.min(option.length(), remaining);
            if (len <= 0) {
                continue;
            }
            String candidate = surface.substring(idx, idx + len);
            if (candidate.equalsIgnoreCase(option)) {
                return candidate;
            }
        }
        int len = Math.min(options[0].length(), remaining);
        return len <= 0 ? "" : surface.substring(idx, idx + len);
    }

    private static String buildFeatureKey(String pos, List<MorphFeatureData> features) {
        StringBuilder builder = new StringBuilder(pos == null ? "" : pos);
        boolean hasFeature = false;
        for (MorphFeatureData feature : features) {
            if (feature.code == null || feature.code.isEmpty()) {
                continue;
            }
            builder.append('+').append(feature.code);
            hasFeature = true;
        }
        return hasFeature ? builder.toString() : builder.toString();
    }
}
