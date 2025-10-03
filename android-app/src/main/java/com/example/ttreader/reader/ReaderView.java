package com.example.ttreader.reader;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.MovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.ttreader.data.DbHelper;
import com.example.ttreader.data.DictionaryDao;
import com.example.ttreader.data.MemoryDao;
import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.model.Morphology;
import com.example.ttreader.model.Token;
import com.example.ttreader.util.MorphDocumentParser;

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
    public interface TokenInfoProvider {
        void onTokenLongPress(TokenSpan span, List<String> ruLemmas);
        void onTokenSingleTap(TokenSpan span);
    }

    public interface WindowChangeListener {
        void onWindowChanged(int globalStart, int globalEnd);
    }

    private static final int WINDOW_PADDING_CHARS = 1000;
    private static final int WINDOW_THRESHOLD_CHARS = 200;

    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private UsageStatsDao usageDao;
    private DictionaryDao dictDao;
    private TokenInfoProvider provider;
    private WindowChangeListener windowChangeListener;
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
    private LoadResult currentDocument;
    private int visibleStart = 0;
    private int visibleEnd = 0;
    private int pendingInitialCharIndex = 0;
    private boolean hasPendingInitialChar = false;
    private boolean windowChangeInProgress = false;
    private int pendingAnchorCharIndex = -1;
    private ScrollView attachedScrollView;
    private int lastViewportScroll = 0;
    private int lastViewportHeight = 0;
    private int viewportStartChar = 0;
    private int viewportEndChar = 0;
    private SentenceOutlineSpan activeSentenceSpan;
    private ForegroundColorSpan activeLetterSpan;
    private int activeSentenceStart = -1;
    private int activeSentenceEnd = -1;
    private int activeLetterIndex = -1;
    private int sentenceOutlineColor;
    private int letterHighlightColor;
    private float sentenceOutlineStrokeWidth;
    private float sentenceOutlineCornerRadius;
    private final MovementMethod movementMethod;

    public ReaderView(Context context) {
        super(context);
        movementMethod = createMovementMethod();
        init();
    }

    public ReaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        movementMethod = createMovementMethod();
        init();
    }

    public ReaderView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        movementMethod = createMovementMethod();
        init();
    }

    private void init() {
        setTextIsSelectable(false);
        setLineSpacing(1.2f, 1.2f);
        sentenceOutlineColor = resolveColorResource(com.example.ttreader.R.color.reader_sentence_outline);
        letterHighlightColor = resolveColorResource(com.example.ttreader.R.color.reader_letter_highlight);
        float density = getResources().getDisplayMetrics().density;
        sentenceOutlineStrokeWidth = 2f * density;
        sentenceOutlineCornerRadius = 6f * density;
        setMovementMethod(movementMethod);
    }

    private MovementMethod createMovementMethod() {
        return new TokenGestureMovementMethod(new TokenGestureMovementMethod.Listener() {
            @Override public void onTokenSingleTap(TokenSpan span) {
                handleTokenTap(span);
            }

            @Override public void onTokenLongPress(TokenSpan span) {
                handleTokenSelection(span);
            }
        });
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

    public void attachScrollView(ScrollView scrollView) {
        this.attachedScrollView = scrollView;
    }

    public void setWindowChangeListener(WindowChangeListener listener) {
        this.windowChangeListener = listener;
    }

    public void setInitialCharIndex(int charIndex) {
        if (charIndex < 0) {
            charIndex = 0;
        }
        pendingInitialCharIndex = charIndex;
        hasPendingInitialChar = true;
        if (currentDocument != null) {
            displayWindowAround(charIndex, charIndex, false);
        }
    }

    public int getVisibleStart() {
        return visibleStart;
    }

    public int getVisibleEnd() {
        return visibleEnd;
    }

    public int getDocumentLength() {
        if (currentDocument == null || currentDocument.text == null) {
            return 0;
        }
        return currentDocument.text.length();
    }

    public int toLocalCharIndex(int globalIndex) {
        CharSequence text = getText();
        int length = text == null ? 0 : text.length();
        int local = globalIndex - visibleStart;
        if (local < 0) {
            return 0;
        }
        if (local > length) {
            return length;
        }
        return local;
    }

    public void ensureWindowContains(int globalCharIndex) {
        if (globalCharIndex < visibleStart || globalCharIndex >= visibleEnd) {
            displayWindowAround(globalCharIndex, globalCharIndex, true);
        }
    }

    public void scrollToGlobalChar(int globalCharIndex) {
        ensureWindowContains(globalCharIndex);
        pendingAnchorCharIndex = globalCharIndex;
        post(this::alignPendingAnchor);
    }

    public void clearContent() {
        visibleStart = 0;
        visibleEnd = 0;
        currentDocument = null;
        tokenSpans.clear();
        sentenceRanges.clear();
        loggedExposures.clear();
        setText("");
        activeSentenceSpan = null;
        activeLetterSpan = null;
        activeSentenceStart = -1;
        activeSentenceEnd = -1;
        activeLetterIndex = -1;
        viewportStartChar = 0;
        viewportEndChar = 0;
    }

    public void loadFromDocumentAsset(String assetName) {
        loadFromDocumentAsset(assetName, pendingInitialCharIndex, null);
    }

    public void loadFromDocumentAsset(String assetName, Runnable onLoaded) {
        loadFromDocumentAsset(assetName, pendingInitialCharIndex, onLoaded);
    }

    public void loadFromDocumentAsset(String assetName, int initialCharIndex, Runnable onLoaded) {
        final String requestedAsset = assetName;
        final Runnable completion = onLoaded;
        final int sequence = contentSequence.incrementAndGet();
        pendingInitialCharIndex = Math.max(0, initialCharIndex);
        hasPendingInitialChar = true;

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

    @Deprecated
    public void loadFromJsonlAsset(String assetName) {
        loadFromDocumentAsset(assetName);
    }

    @Deprecated
    public void loadFromJsonlAsset(String assetName, Runnable onLoaded) {
        loadFromDocumentAsset(assetName, onLoaded);
    }

    @Deprecated
    public void loadFromJsonlAsset(String assetName, int initialCharIndex, Runnable onLoaded) {
        loadFromDocumentAsset(assetName, initialCharIndex, onLoaded);
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

    private void applyLoadResult(LoadResult result) {
        if (result == null) {
            return;
        }
        currentDocument = result;
        tokenSpans.clear();
        tokenSpans.addAll(result.tokenSpans);
        loggedExposures.clear();
        sentenceRanges.clear();
        sentenceRanges.addAll(result.sentenceRanges);
        int target = hasPendingInitialChar ? pendingInitialCharIndex : 0;
        hasPendingInitialChar = false;
        displayWindowAround(target, target, false);
    }

    public void displayWindowAround(int targetCharIndex) {
        displayWindowAround(targetCharIndex, targetCharIndex, true);
    }

    private void displayWindowAround(int targetCharIndex, int anchorCharIndex, boolean notifyWindowChange) {
        if (currentDocument == null || currentDocument.text == null) {
            return;
        }
        int docLength = currentDocument.text.length();
        if (docLength == 0) {
            clearContent();
            return;
        }
        int target = clamp(targetCharIndex, 0, docLength);
        int start = Math.max(0, target - WINDOW_PADDING_CHARS);
        int end = Math.min(docLength, target + WINDOW_PADDING_CHARS);
        if (end - start < WINDOW_PADDING_CHARS * 2 && docLength > WINDOW_PADDING_CHARS * 2) {
            if (start == 0) {
                end = Math.min(docLength, WINDOW_PADDING_CHARS * 2);
            } else {
                start = Math.max(0, docLength - WINDOW_PADDING_CHARS * 2);
                end = docLength;
            }
        }
        start = adjustStartToTokenBoundary(start);
        end = adjustEndToTokenBoundary(Math.max(end, start + 1));
        if (end <= start) {
            end = Math.min(docLength, start + WINDOW_PADDING_CHARS * 2);
        }
        applyWindow(start, end, anchorCharIndex, notifyWindowChange);
    }

    private void applyWindow(int windowStart, int windowEnd, int anchorCharIndex, boolean notifyWindowChange) {
        if (currentDocument == null || currentDocument.text == null) {
            return;
        }
        int docLength = currentDocument.text.length();
        int clampedStart = clamp(windowStart, 0, docLength);
        int clampedEnd = clamp(windowEnd, clampedStart, docLength);
        if (clampedEnd <= clampedStart) {
            clampedEnd = Math.min(docLength, clampedStart + WINDOW_PADDING_CHARS * 2);
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(
                currentDocument.text.substring(clampedStart, clampedEnd));
        for (TokenSpan span : tokenSpans) {
            if (span == null) continue;
            int spanStart = span.getStartIndex();
            int spanEnd = span.getEndIndex();
            if (spanStart >= clampedEnd || spanEnd <= clampedStart) {
                continue;
            }
            int localStart = Math.max(0, spanStart - clampedStart);
            int localEnd = Math.min(clampedEnd - clampedStart, spanEnd - clampedStart);
            if (localEnd <= localStart) {
                continue;
            }
            builder.setSpan(span, localStart, localEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        windowChangeInProgress = true;
        visibleStart = clampedStart;
        visibleEnd = clampedEnd;
        setText(builder);
        if (getMovementMethod() != movementMethod) {
            setMovementMethod(movementMethod);
        }
        reapplySpeechHighlights();
        windowChangeInProgress = false;
        pendingAnchorCharIndex = anchorCharIndex;
        post(this::alignPendingAnchor);
        logVisibleExposures();
        if (notifyWindowChange && windowChangeListener != null) {
            windowChangeListener.onWindowChanged(visibleStart, visibleEnd);
        }
    }

    private void reapplySpeechHighlights() {
        if (activeSentenceStart >= 0 && activeSentenceEnd > activeSentenceStart) {
            highlightSentenceRange(activeSentenceStart, activeSentenceEnd);
        }
        if (activeLetterIndex >= 0) {
            highlightLetter(activeLetterIndex);
        }
    }

    private void alignPendingAnchor() {
        if (pendingAnchorCharIndex < 0) {
            return;
        }
        int anchor = pendingAnchorCharIndex;
        pendingAnchorCharIndex = -1;
        if (anchor < visibleStart) {
            anchor = visibleStart;
        } else if (anchor > visibleEnd) {
            anchor = visibleEnd;
        }
        if (attachedScrollView == null) {
            return;
        }
        Layout layout = getLayout();
        CharSequence text = getText();
        if (layout == null || text == null) {
            final int retryAnchor = anchor;
            postDelayed(() -> {
                pendingAnchorCharIndex = retryAnchor;
                alignPendingAnchor();
            }, 16);
            return;
        }
        int contentLength = text.length();
        int local = clamp(anchor - visibleStart, 0, contentLength);
        int line = layout.getLineForOffset(local);
        int y = getTotalPaddingTop() + layout.getLineTop(line);
        attachedScrollView.smoothScrollTo(0, y);
        post(this::logVisibleExposures);
    }

    public void onViewportChanged(int scrollY, int viewportHeight) {
        lastViewportScroll = Math.max(0, scrollY);
        lastViewportHeight = Math.max(0, viewportHeight);
        if (!windowChangeInProgress) {
            ensureWindowForViewport(scrollY, viewportHeight);
        }
        logVisibleExposures();
    }

    public boolean hasPreviousPage() {
        return viewportStartChar > 0;
    }

    public boolean hasNextPage() {
        if (currentDocument == null || currentDocument.text == null) {
            return false;
        }
        return viewportEndChar < currentDocument.text.length();
    }

    public int getViewportStartChar() {
        return viewportStartChar;
    }

    public int getViewportEndChar() {
        return viewportEndChar;
    }

    public int findNextPageStart() {
        if (!hasNextPage()) {
            return -1;
        }
        return Math.max(viewportStartChar + 1, viewportEndChar);
    }

    public int findPreviousPageStart() {
        if (!hasPreviousPage()) {
            return -1;
        }
        int span = Math.max(1, viewportEndChar - viewportStartChar);
        int candidate = viewportStartChar - span;
        if (candidate < 0) {
            candidate = 0;
        }
        return candidate;
    }

    private void ensureWindowForViewport(int scrollY, int viewportHeight) {
        if (currentDocument == null || currentDocument.text == null) {
            return;
        }
        Layout layout = getLayout();
        CharSequence text = getText();
        if (layout == null || text == null || text.length() == 0) {
            return;
        }
        int contentLength = text.length();
        int topLine = layout.getLineForVertical(scrollY);
        int bottomLine = layout.getLineForVertical(scrollY + viewportHeight);
        int localTop = clamp(layout.getLineStart(topLine), 0, contentLength);
        int localBottom = clamp(layout.getLineEnd(bottomLine), 0, contentLength);
        int globalTop = visibleStart + localTop;
        int globalBottom = visibleStart + localBottom;
        viewportStartChar = globalTop;
        viewportEndChar = Math.max(globalTop, globalBottom);
        if (globalTop < visibleStart + WINDOW_THRESHOLD_CHARS && visibleStart > 0) {
            displayWindowAround(Math.max(0, globalTop - WINDOW_PADDING_CHARS / 2), globalTop, true);
        } else if (globalBottom > visibleEnd - WINDOW_THRESHOLD_CHARS && visibleEnd < getDocumentLength()) {
            displayWindowAround(Math.min(getDocumentLength(), globalBottom + WINDOW_PADDING_CHARS / 2), globalBottom, true);
        }
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
        activeSentenceStart = start;
        activeSentenceEnd = end;
        Spannable text = getSpannableText();
        if (text == null) return;
        if (activeSentenceSpan != null) {
            text.removeSpan(activeSentenceSpan);
            activeSentenceSpan = null;
        }
        if (start < 0 || end <= start) {
            invalidate();
            return;
        }
        if (end <= visibleStart || start >= visibleEnd) {
            invalidate();
            return;
        }
        int localStart = clamp(start - visibleStart, 0, text.length());
        int localEnd = clamp(end - visibleStart, 0, text.length());
        if (localEnd <= localStart) {
            invalidate();
            return;
        }
        activeSentenceSpan = new SentenceOutlineSpan(sentenceOutlineColor, sentenceOutlineStrokeWidth, sentenceOutlineCornerRadius);
        text.setSpan(activeSentenceSpan, localStart, localEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        invalidate();
    }

    public void highlightLetter(int charIndex) {
        activeLetterIndex = charIndex;
        Spannable text = getSpannableText();
        if (text == null) return;
        if (activeLetterSpan != null) {
            text.removeSpan(activeLetterSpan);
            activeLetterSpan = null;
        }
        if (charIndex < visibleStart || charIndex >= visibleEnd || text.length() == 0) {
            invalidate();
            return;
        }
        int clampedIndex = clamp(charIndex - visibleStart, 0, text.length());
        int localSentenceStart = (activeSentenceStart >= visibleStart && activeSentenceStart < visibleEnd)
                ? Math.max(0, activeSentenceStart - visibleStart)
                : clampedIndex;
        int start = Math.max(0, Math.min(clampedIndex, localSentenceStart));
        int end = Math.min(text.length(), clampedIndex + 1);
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
        activeSentenceStart = -1;
        activeSentenceEnd = -1;
        activeLetterIndex = -1;
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

    private void handleTokenTap(TokenSpan span) {
        if (span == null || span.token == null || span.token.morphology == null) return;
        recordExposure(span, System.currentTimeMillis());
        if (provider != null) {
            provider.onTokenSingleTap(span);
        }
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
            int globalStart = span.getStartIndex();
            int globalEnd = span.getEndIndex();
            if (globalEnd <= visibleStart || globalStart >= visibleEnd) {
                continue;
            }
            int localStart = clampIndex(globalStart - visibleStart, contentLength);
            int localEnd = clampIndex(globalEnd - visibleStart, contentLength);
            if (localEnd <= localStart) continue;
            int startLine = layout.getLineForOffset(localStart);
            int endLine = layout.getLineForOffset(Math.max(0, localEnd - 1));
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
    private LoadResult buildContent(String assetName) throws Exception {
        if (assetName == null || assetName.isEmpty()) {
            return new LoadResult("", Collections.emptyList(), Collections.emptyList());
        }
        List<Token> tokens = MorphDocumentParser.loadFromAssets(getContext(), assetName);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        StringBuilder plain = new StringBuilder();
        List<TokenSpan> spans = new ArrayList<>();
        double halflife = 7.0; // days
        long now = System.currentTimeMillis();

        for (Token t : tokens) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (t.prefix != null && !t.prefix.isEmpty()) plain.append(t.prefix);
            int start = plain.length();
            if (t.surface != null) plain.append(t.surface);
            int end = plain.length();

            if (t.hasMorphology() && t.surface != null && !t.surface.isEmpty()) {
                Morphology morph = t.morphology;
                TokenSpan span = new TokenSpan(t);
                span.setCharacterRange(start, end);
                double s = memoryDao.getCurrentStrength(morph.lemma, span.featureKey, now, halflife);
                double alpha = Math.max(0, 1.0 - Math.min(1.0, s / 5.0));
                span.lastAlpha = (float) alpha;
                spans.add(span);
            }
        }

        List<SentenceRange> ranges = buildSentenceRanges(plain.toString());
        return new LoadResult(plain.toString(), spans, ranges);
    }

    private int adjustStartToTokenBoundary(int candidate) {
        int result = Math.max(0, candidate);
        for (int i = tokenSpans.size() - 1; i >= 0; i--) {
            TokenSpan span = tokenSpans.get(i);
            if (span == null) continue;
            if (span.getStartIndex() <= result) {
                return Math.max(0, span.getStartIndex());
            }
        }
        return result;
    }

    private int adjustEndToTokenBoundary(int candidate) {
        int docLength = getDocumentLength();
        int result = clamp(candidate, 0, docLength);
        for (TokenSpan span : tokenSpans) {
            if (span == null) continue;
            if (span.getEndIndex() >= result) {
                return Math.min(docLength, span.getEndIndex());
            }
        }
        return result;
    }

    private List<SentenceRange> buildSentenceRanges(String text) {
        List<SentenceRange> ranges = new ArrayList<>();
        if (text == null) return ranges;
        if (text.isEmpty()) return ranges;
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.getDefault());
        iterator.setText(text);
        int start = iterator.first();
        int end = iterator.next();
        while (end != BreakIterator.DONE) {
            if (Thread.currentThread().isInterrupted()) {
                return ranges;
            }
            int trimmedStart = trimLeadingWhitespace(text, start, end);
            int trimmedEnd = trimTrailingWhitespace(text, trimmedStart, end);
            if (trimmedStart < trimmedEnd) {
                ranges.add(new SentenceRange(trimmedStart, trimmedEnd, text.substring(trimmedStart, trimmedEnd)));
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
        return Math.min(result, limit);
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

    private int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
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
        final String text;
        final List<TokenSpan> tokenSpans;
        final List<SentenceRange> sentenceRanges;

        LoadResult(String text, List<TokenSpan> tokenSpans, List<SentenceRange> sentenceRanges) {
            this.text = text;
            this.tokenSpans = tokenSpans == null ? Collections.emptyList() : tokenSpans;
            this.sentenceRanges = sentenceRanges == null ? Collections.emptyList() : sentenceRanges;
        }
    }
}
