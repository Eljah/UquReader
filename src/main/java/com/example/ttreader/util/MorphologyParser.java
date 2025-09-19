package com.example.ttreader.util;

import com.example.ttreader.model.MorphFeature;
import com.example.ttreader.model.Morphology;

import java.util.ArrayList;
import java.util.List;

public class MorphologyParser {
    public static Morphology parse(String surface, String analysis) {
        if (analysis == null || !analysis.contains("+")) return null;
        String[] parts = analysis.split("\\+");
        if (parts.length < 2) return null;
        String lemma = parts[0];
        String pos = parts[1];
        List<MorphFeature> features = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
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
            lengths.add(estimateLength(canonical));
        }
        List<String> segments = new ArrayList<>();
        int totalEndings = 0;
        for (Integer len : lengths) totalEndings += len;
        int baseLen = Math.max(0, Math.min(surface.length(), surface.length() - totalEndings));
        String baseSegment = safeSubstring(surface, 0, baseLen);
        segments.add(baseSegment);
        int idx = baseSegment.length();
        for (MorphFeature feature : features) {
            String actual = resolveActual(surface, idx, feature.canonical);
            feature.actual = actual;
            if (!actual.isEmpty()) segments.add(actual);
            idx += actual.length();
        }
        if (segments.isEmpty()) segments.add(surface);
        if (idx < surface.length()) {
            String tail = surface.substring(idx);
            if (!segments.isEmpty()) {
                int last = segments.size() - 1;
                segments.set(last, segments.get(last) + tail);
            } else {
                segments.add(tail);
            }
        }
        String featureKey = buildFeatureKey(pos, features);
        return new Morphology(lemma, pos, features, segments, featureKey, analysis);
    }

    private static String safeSubstring(String value, int start, int end) {
        if (start >= value.length()) return "";
        end = Math.min(end, value.length());
        if (end <= start) return "";
        return value.substring(start, end);
    }

    private static int estimateLength(String canonical) {
        if (canonical == null || canonical.isEmpty()) return 0;
        String option = canonical.split("/")[0];
        return option.length();
    }

    private static String resolveActual(String surface, int idx, String canonical) {
        if (canonical == null || canonical.isEmpty()) return "";
        String[] options = canonical.split("/");
        int remaining = surface.length() - idx;
        for (String opt : options) {
            if (opt.isEmpty()) continue;
            int len = Math.min(opt.length(), remaining);
            if (len <= 0) continue;
            String candidate = surface.substring(idx, idx + len);
            if (candidate.equalsIgnoreCase(opt)) return candidate;
        }
        String opt = options[0];
        int len = Math.min(opt.length(), remaining);
        if (len <= 0) return "";
        return surface.substring(idx, idx + len);
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
