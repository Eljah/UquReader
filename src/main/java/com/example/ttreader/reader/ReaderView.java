package com.example.ttreader.reader;

import android.content.Context;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.widget.TextView;

import com.example.ttreader.data.DbHelper;
import com.example.ttreader.data.DictionaryDao;
import com.example.ttreader.data.MemoryDao;
import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.model.Morphology;
import com.example.ttreader.model.Token;
import com.example.ttreader.util.JsonlParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ReaderView extends TextView {
    public interface TokenInfoProvider { void onTokenLongPress(TokenSpan span, List<String> ruLemmas); }

    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private UsageStatsDao usageDao;
    private DictionaryDao dictDao;
    private TokenInfoProvider provider;
    private String languagePair = "";
    private String workId = "";
    private final List<TokenSpan> tokenSpans = new ArrayList<>();
    private final Set<TokenSpan> loggedExposures = new HashSet<>();
    private int lastViewportScroll = 0;
    private int lastViewportHeight = 0;

    public ReaderView(Context context) { super(context); init(); }
    public ReaderView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ReaderView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        setTextIsSelectable(false);
        setLineSpacing(1.2f, 1.2f);
    }

    public void setup(DbHelper helper, MemoryDao memoryDao, UsageStatsDao usageDao, TokenInfoProvider provider) {
        this.dbHelper = helper;
        this.memoryDao = memoryDao;
        this.usageDao = usageDao;
        this.provider = provider;
        try {
            File dict = helper.ensureDictionaryDb();
            this.dictDao = new DictionaryDao(dict);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void setUsageContext(String languagePair, String workId) {
        this.languagePair = languagePair == null ? "" : languagePair;
        this.workId = workId == null ? "" : workId;
    }

    public void loadFromJsonlAsset(String assetName) {
        try {
            List<Token> tokens = JsonlParser.readTokensFromAssets(getContext(), assetName);
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            double halflife = 7.0; // days
            long now = System.currentTimeMillis();

            tokenSpans.clear();
            loggedExposures.clear();
            for (Token t : tokens) {
                if (t.prefix != null && !t.prefix.isEmpty()) ssb.append(t.prefix);
                int start = ssb.length();
                if (t.surface != null) ssb.append(t.surface);
                int end = ssb.length();

                if (t.hasMorphology() && t.surface != null && !t.surface.isEmpty()) {
                    Morphology morph = t.morphology;
                    TokenSpan span = new TokenSpan(t);
                    span.setCharacterRange(start, end);
                    double s = memoryDao.getCurrentStrength(morph.lemma, span.featureKey, now, halflife);
                    double alpha = Math.max(0, 1.0 - Math.min(1.0, s/5.0));
                    span.lastAlpha = (float)alpha;
                    ssb.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    tokenSpans.add(span);
                }
            }

            setText(ssb);
            setMovementMethod(new LongPressMovementMethod(this::handleTokenSelection));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public void onViewportChanged(int scrollY, int viewportHeight) {
        lastViewportScroll = Math.max(0, scrollY);
        lastViewportHeight = Math.max(0, viewportHeight);
        logVisibleExposures();
    }

    public TokenSpan findSpanForCharIndex(int charIndex) {
        if (charIndex < 0) return null;
        for (TokenSpan span : tokenSpans) {
            if (span == null) continue;
            int start = span.getStartIndex();
            int end = span.getEndIndex();
            if (start < 0 || end <= start) continue;
            if (charIndex >= start && charIndex < end) {
                return span;
            }
        }
        return null;
    }

    public void showTokenInfo(TokenSpan span) {
        handleTokenSelection(span);
    }

    public List<String> getTranslations(TokenSpan span) {
        List<String> ru = new ArrayList<>();
        if (span == null || span.token == null || span.token.morphology == null) return ru;
        if (dictDao != null) {
            dictDao.translateLemmaToRu(span.token.morphology.lemma).forEach(p -> ru.add(p.first));
        }
        return ru;
    }

    public void ensureExposureLogged(TokenSpan span) {
        recordExposure(span, System.currentTimeMillis());
    }

    public List<TokenSpan> getTokenSpans() {
        return Collections.unmodifiableList(tokenSpans);
    }

    private void handleTokenSelection(TokenSpan span) {
        if (span == null || span.token == null || span.token.morphology == null) return;
        Morphology morph = span.token.morphology;
        recordExposure(span, System.currentTimeMillis());
        List<String> ru = getTranslations(span);
        if (usageDao != null) {
            usageDao.recordEvent(languagePair, workId, morph.lemma, morph.pos, null,
                    UsageStatsDao.EVENT_LOOKUP, System.currentTimeMillis(), span.getStartIndex());
        }
        if (provider != null) provider.onTokenLongPress(span, ru);
        if (memoryDao != null) {
            memoryDao.updateOnLookup(morph.lemma, span.featureKey, System.currentTimeMillis(), 1.0);
        }
    }

    private void logVisibleExposures() {
        if (usageDao == null || tokenSpans.isEmpty()) return;
        if (lastViewportHeight <= 0) return;
        Layout layout = getLayout();
        CharSequence text = getText();
        if (layout == null || text == null) return;
        int contentLength = text.length();
        int visibleTop = lastViewportScroll;
        int visibleBottom = visibleTop + lastViewportHeight;
        int firstLine = layout.getLineForVertical(visibleTop);
        int lastLine = layout.getLineForVertical(Math.max(visibleBottom, 0));
        for (TokenSpan span : tokenSpans) {
            if (span == null || loggedExposures.contains(span)) continue;
            int start = clampIndex(span.getStartIndex(), contentLength);
            int end = clampIndex(span.getEndIndex(), contentLength);
            if (start >= end) continue;
            int startLine = layout.getLineForOffset(start);
            int endLine = layout.getLineForOffset(end - 1);
            if (startLine <= lastLine && endLine >= firstLine) {
                recordExposure(span, System.currentTimeMillis());
            }
        }
    }

    private int clampIndex(int value, int max) {
        if (value < 0) return 0;
        if (value > max) return max;
        return value;
    }

    private void recordExposure(TokenSpan span, long timestamp) {
        if (span == null || span.token == null || span.token.morphology == null) return;
        if (usageDao == null) return;
        if (!loggedExposures.add(span)) return;
        Morphology morph = span.token.morphology;
        usageDao.recordEvent(languagePair, workId, morph.lemma, morph.pos, null,
                UsageStatsDao.EVENT_EXPOSURE, timestamp, span.getStartIndex());
    }
}
