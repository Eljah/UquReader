package com.example.uqureader.webapp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assertions;

/**
 * Utilities for asserting equality of large payloads without flooding the console output.
 */
final class LargeTextAssertions {

    private static final int MAX_PREVIEW_LENGTH = 256;

    private LargeTextAssertions() {
        // Utility class
    }

    static void assertLargeTextEquals(String expected, String actual) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected != null && expected.equals(actual)) {
            return;
        }
        String expectedText = expected == null ? "<null>" : expected;
        String actualText = actual == null ? "<null>" : actual;
        int diffIndex = firstDifferenceIndex(expectedText, actualText);
        String message = "Text values differ at index " + diffIndex
                + System.lineSeparator()
                + "expected: " + preview(expectedText, diffIndex)
                + System.lineSeparator()
                + "actual  : " + preview(actualText, diffIndex);
        Assertions.fail(message);
    }

    static void assertJsonEquals(JsonObject expected, JsonObject actual) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected != null && expected.equals(actual)) {
            return;
        }
        String expectedJson = expected == null ? "<null>" : expected.toString();
        String actualJson = actual == null ? "<null>" : actual.toString();
        String message = "JSON values differ" + System.lineSeparator()
                + "expected: " + preview(expectedJson, 0)
                + System.lineSeparator()
                + "actual  : " + preview(actualJson, 0);
        Assertions.fail(message);
    }

    private static int firstDifferenceIndex(String a, String b) {
        int min = Math.min(a.length(), b.length());
        for (int i = 0; i < min; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return min;
    }

    private static String preview(String value, int centerIndex) {
        if (value.length() <= MAX_PREVIEW_LENGTH) {
            return value;
        }
        int safeIndex = Math.max(0, Math.min(centerIndex, value.length()));
        int start = Math.max(0, safeIndex - MAX_PREVIEW_LENGTH / 2);
        int end = Math.min(value.length(), start + MAX_PREVIEW_LENGTH);
        if (end - start < MAX_PREVIEW_LENGTH) {
            start = Math.max(0, end - MAX_PREVIEW_LENGTH);
        }
        String core = value.substring(start, end);
        StringBuilder builder = new StringBuilder(MAX_PREVIEW_LENGTH);
        if (start > 0) {
            builder.append('…');
        }
        builder.append(core);
        if (end < value.length()) {
            builder.append('…');
        }
        if (builder.length() > MAX_PREVIEW_LENGTH) {
            if (start > 0 && end < value.length()) {
                int trim = builder.length() - MAX_PREVIEW_LENGTH;
                int leftTrim = trim / 2 + trim % 2;
                builder.delete(1, 1 + leftTrim);
                trim = builder.length() - MAX_PREVIEW_LENGTH;
                if (trim > 0) {
                    builder.delete(builder.length() - 1 - trim, builder.length() - 1);
                }
            } else if (start > 0) {
                builder.delete(1, builder.length() - MAX_PREVIEW_LENGTH + 1);
            } else {
                builder.setLength(MAX_PREVIEW_LENGTH - 1);
                builder.append('…');
            }
        }
        if (builder.length() > MAX_PREVIEW_LENGTH) {
            if (end < value.length()) {
                builder.setLength(MAX_PREVIEW_LENGTH);
                builder.setCharAt(MAX_PREVIEW_LENGTH - 1, '…');
            } else {
                int excess = builder.length() - MAX_PREVIEW_LENGTH;
                builder.delete(0, excess);
                builder.setCharAt(0, '…');
            }
        }
        return builder.toString();
    }
}
