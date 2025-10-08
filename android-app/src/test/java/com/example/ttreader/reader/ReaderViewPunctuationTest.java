package com.example.ttreader.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ReaderViewPunctuationTest {

    @Test
    public void extendIndexDoesNotChangeWithoutPunctuation() {
        String text = "Сүз юк";
        int base = text.indexOf(' ');
        int extended = ReaderView.extendIndexThroughPunctuation(text, base, text.length());
        assertEquals(base, extended);
    }

    @Test
    public void extendIndexCapturesImmediatePunctuation() {
        String text = "сүз, текст";
        int base = text.indexOf(',');
        int extended = ReaderView.extendIndexThroughPunctuation(text, base, text.length());
        assertEquals(text.indexOf(' ') + 1, extended);
    }

    @Test
    public void extendIndexCapturesWhitespaceBeforeDash() {
        String text = "сүз — дәвам";
        int base = text.indexOf(' ');
        int extended = ReaderView.extendIndexThroughPunctuation(text, base, text.length());
        assertEquals(text.indexOf('д'), extended);
    }

    @Test
    public void extendIndexIncludesNewlinePrefixedDash() {
        String text = "сүз\n— дәвам";
        int base = text.indexOf('\n');
        int extended = ReaderView.extendIndexThroughPunctuation(text, base, text.length());
        assertEquals(text.indexOf('д'), extended);
    }
}
