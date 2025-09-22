package com.example.ttreader.reader;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final List<SentenceRange> sentenceRanges = new ArrayList<>();
    private final ExecutorService contentExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger contentSequence = new AtomicInteger();
    private final Object loadTaskLock = new Object();
    private Future<?> pendingLoadTask;
    private int lastViewportScroll = 0;
    private int lastViewportHeight = 0;
    private SentenceOutlineSpan activeSentenceSpan;
    private ForegroundColorSpan activeLetterSpan;
    private int activeSentenceStart = -1;
    private int sentenceOutlineColor;
    private int letterHighlightColor;
    private float sentenceOutlineStrokeWidth;
    private float sentenceOutlineCornerRadius;

    public ReaderView(Context context) { super(context); init(); }
    public ReaderView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ReaderView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        setTextIsSelectable(false);
        setLineSpacing(1.2f, 1.2f);
        sentenceOutlineColor = resolveColorResource(com.example.ttreader.R.color.reader_sentence_outline);
        letterHighlightColor = resolveColorResource(com.example.ttreader.R.color.reader_letter_highlight);
        float density = getResources().getDisplayMetrics().density;
        sentenceOutlineStrokeWidth = 2f * density;
        sentenceOutlineCornerRadius = 6f * density;
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
        loadFromJsonlAsset(assetName, null);
    }

    public void loadFromJsonlAsset(String assetName, Runnable onLoaded) {
        final String requestedAsset = assetName;
        final Runnable completion = onLoaded;
        final int sequence = contentSequence.incrementAndGet();

        Future<?> previousTask;
        synchronized (loadTaskLock) {
            previousTask = pendingLoadTask;
        }
        if (previousTask != null) {
            previousTask.cancel(true);
        }

        Future<?> newTask = contentExecutor.submit(() -> {
            try {
                LoadResult result = buildContent(requestedAsset);
                if (result == null) return;
                mainHandler.post(() -> {
                    if (sequence != contentSequence.get()) return;
                    applyLoadResult(result);
                    if (completion != null) {
                        completion.run();
                    }
                    clearPendingTask(sequence);
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (sequence != contentSequence.get()) return;
                    clearPendingTask(sequence);
                    throw new RuntimeException(e);
                });
            }
        });

        synchronized (loadTaskLock) {
            pendingLoadTask = newTask;
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Future<?> task;
        synchronized (loadTaskLock) {
            task = pendingLoadTask;
            pendingLoadTask = null;
        }
        if (task != null) {
            task.cancel(true);
        }
    }

    private void clearPendingTask(int sequence) {
        synchronized (loadTaskLock) {
            if (contentSequence.get() == sequence) {
                pendingLoadTask = null;
            }
        }
    }

    private LoadResult buildContent(String assetName) throws Exception {
        if (assetName == null || assetName.isEmpty()) {
            return new LoadResult(new SpannableStringBuilder(), Collections.emptyList(), Collections.emptyList());
        }
        List<Token> tokens = JsonlParser.readTokensFromAssets(getContext(), assetName);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        List<TokenSpan> spans = new ArrayList<>();
        double halflife = 7.0; // days
        long now = System.currentTimeMillis();

        for (Token t : tokens) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (t.prefix != null && !t.prefix.isEmpty()) ssb.append(t.prefix);
            int start = ssb.length();
            if (t.surface != null) ssb.append(t.surface);
            int end = ssb.length();

            if (t.hasMorphology() && t.surface != null && !t.surface.isEmpty()) {
                Morphology morph = t.morphology;
                TokenSpan span = new TokenSpan(t);
                span.setCharacterRange(start, end);
                double s = memoryDao.getCurrentStrength(morph.lemma, span.featureKey, now, halflife);
                double alpha = Math.max(0, 1.0 - Math.min(1.0, s / 5.0));
                span.lastAlpha = (float) alpha;
                ssb.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spans.add(span);
            }
        }

        List<SentenceRange> ranges = buildSentenceRanges(ssb);
        return new LoadResult(ssb, spans, ranges);
    }

    private void applyLoadResult(LoadResult result) {
        if (result == null) return;
        setText(result.text);
        setMovementMethod(new LongPressMovementMethod(this::handleTokenSelection));

        tokenSpans.clear();
        tokenSpans.addAll(result.tokenSpans);
        loggedExposures.clear();

        sentenceRanges.clear();
        sentenceRanges.addAll(result.sentenceRanges);
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

    public List<SentenceRange> getSentenceRanges() {
        return Collections.unmodifiableList(sentenceRanges);
    }

    public SentenceRange findSentenceForCharIndex(int charIndex) {
        if (charIndex < 0) return null;
        for (SentenceRange range : sentenceRanges) {
            if (range == null) continue;
            if (charIndex >= range.start && charIndex < range.end) {
                return range;
            }
        }
        return null;
    }

    public void highlightSentenceRange(int start, int end) {
        Spannable text = getSpannableText();
        if (text == null) return;
        if (activeSentenceSpan != null) {
            text.removeSpan(activeSentenceSpan);
            activeSentenceSpan = null;
        }
        if (start < 0 || end <= start) {
            activeSentenceStart = -1;
            invalidate();
            return;
        }
        activeSentenceSpan = new SentenceOutlineSpan(sentenceOutlineColor, sentenceOutlineStrokeWidth, sentenceOutlineCornerRadius);
        text.setSpan(activeSentenceSpan, start, Math.min(end, text.length()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        activeSentenceStart = start;
        invalidate();
    }

    public void highlightLetter(int charIndex) {
        Spannable text = getSpannableText();
        if (text == null) return;
        if (activeLetterSpan != null) {
            text.removeSpan(activeLetterSpan);
            activeLetterSpan = null;
        }
        if (charIndex < 0 || text.length() == 0) {
            invalidate();
            return;
        }
        int clampedIndex = Math.max(0, Math.min(charIndex, text.length() - 1));
        int start = clampedIndex;
        if (activeSentenceStart >= 0 && activeSentenceStart <= clampedIndex) {
            start = activeSentenceStart;
        }
        int end = Math.min(clampedIndex + 1, text.length());
        if (end <= start) {
            invalidate();
            return;
        }
        activeLetterSpan = new ForegroundColorSpan(letterHighlightColor);
        int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | (1 << Spanned.SPAN_PRIORITY_SHIFT);
        text.setSpan(activeLetterSpan, start, end, flags);
        invalidate();
    }

    public void clearSpeechHighlights() {
        highlightSentenceRange(-1, -1);
        highlightLetter(-1);
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

    private List<SentenceRange> buildSentenceRanges(CharSequence text) {
        List<SentenceRange> ranges = new ArrayList<>();
        if (text == null) return ranges;
        String content = text.toString();
        if (content.isEmpty()) return ranges;
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.getDefault());
        iterator.setText(content);
        int start = iterator.first();
        int end = iterator.next();
        while (end != BreakIterator.DONE) {
            if (Thread.currentThread().isInterrupted()) {
                return ranges;
            }
            int trimmedStart = trimLeadingWhitespace(content, start, end);
            int trimmedEnd = trimTrailingWhitespace(content, trimmedStart, end);
            if (trimmedStart < trimmedEnd) {
                ranges.add(new SentenceRange(trimmedStart, trimmedEnd, content.substring(trimmedStart, trimmedEnd)));
            }
            start = end;
            end = iterator.next();
        }
        return ranges;
    }

    private int trimLeadingWhitespace(String content, int start, int end) {
        int result = Math.max(0, start);
        int limit = Math.min(content.length(), end);
        while (result < limit && Character.isWhitespace(content.charAt(result))) {
            result++;
        }
        return result;
    }

    private int trimTrailingWhitespace(String content, int start, int end) {
        int result = Math.min(content.length(), end);
        int limit = Math.max(start, 0);
        while (result > limit && Character.isWhitespace(content.charAt(result - 1))) {
            result--;
        }
        return Math.max(result, limit);
    }

    private Spannable getSpannableText() {
        CharSequence text = getText();
        if (text instanceof Spannable) {
            return (Spannable) text;
        }
        return null;
    }

    private int resolveColorResource(int resId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getResources().getColor(resId, getContext().getTheme());
        }
        //noinspection deprecation
        return getResources().getColor(resId);
    }

    public static class SentenceRange {
        public final int start;
        public final int end;
        public final String text;

        SentenceRange(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text == null ? "" : text;
        }

        public int length() {
            return Math.max(0, end - start);
        }
    }

    private static final class LoadResult {
        final SpannableStringBuilder text;
        final List<TokenSpan> tokenSpans;
        final List<SentenceRange> sentenceRanges;

        LoadResult(SpannableStringBuilder text, List<TokenSpan> tokenSpans, List<SentenceRange> sentenceRanges) {
            this.text = text;
            this.tokenSpans = tokenSpans == null ? Collections.emptyList() : tokenSpans;
            this.sentenceRanges = sentenceRanges == null ? Collections.emptyList() : sentenceRanges;
        }
    }
}
