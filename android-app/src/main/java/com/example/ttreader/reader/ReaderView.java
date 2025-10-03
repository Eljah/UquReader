package com.example.ttreader.reader;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.method.MovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.widget.TextView;

import com.example.ttreader.data.DbHelper;
import com.example.ttreader.data.DictionaryDao;
import com.example.ttreader.data.MemoryDao;
import com.example.ttreader.data.PageLayoutDao;
import com.example.ttreader.data.PageLayoutDao.PageLayoutEntry;
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
    private final AtomicInteger layoutSequence = new AtomicInteger();
    private final Object loadTaskLock = new Object();
    private final Object layoutTaskLock = new Object();
    private Future<?> pendingLoadTask;
    private Future<?> pendingLayoutTask;
    private LoadResult currentDocument;
    private int visibleStart = 0;
    private int visibleEnd = 0;
    private int pendingInitialCharIndex = 0;
    private boolean hasPendingInitialChar = false;
    private boolean windowChangeInProgress = false;
    private final List<PageRange> pageRanges = new ArrayList<>();
    private boolean pageLayoutReady = false;
    private PageLayoutSpec currentLayoutSpec;
    private int currentPageIndex = 0;
    private int pendingDisplayCharIndex = 0;
    private boolean hasPendingDisplayChar = false;
    private int viewportStartChar = 0;
    private int viewportEndChar = 0;
    private int totalPages = 0;
    private SentenceOutlineSpan activeSentenceSpan;
    private ForegroundColorSpan activeLetterSpan;
    private int activeSentenceStart = -1;
    private int activeSentenceEnd = -1;
    private int activeLetterIndex = -1;
    private int sentenceOutlineColor;
    private int letterHighlightColor;
    private float sentenceOutlineStrokeWidth;
    private float sentenceOutlineCornerRadius;
    private float configuredLineSpacingExtra = 0f;
    private float configuredLineSpacingMultiplier = 1f;
    private final MovementMethod movementMethod;
    private PageLayoutDao pageLayoutDao;

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

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (currentDocument == null || currentDocument.text == null) {
            return;
        }
        if (w != oldw || h != oldh) {
            int target = hasPendingDisplayChar ? pendingDisplayCharIndex : visibleStart;
            schedulePageLayoutRebuild(false, target);
        }
    }

    private void init() {
        setTextIsSelectable(false);
        setLineSpacing(1.2f, 1.2f);
        configuredLineSpacingExtra = 1.2f;
        configuredLineSpacingMultiplier = 1.2f;
        sentenceOutlineColor = resolveColorResource(com.example.ttreader.R.color.reader_sentence_outline);
        letterHighlightColor = resolveColorResource(com.example.ttreader.R.color.reader_letter_highlight);
        float density = getResources().getDisplayMetrics().density;
        sentenceOutlineStrokeWidth = 2f * density;
        sentenceOutlineCornerRadius = 6f * density;
        setMovementMethod(movementMethod);
    }

    @Override public void setLineSpacing(float add, float mult) {
        super.setLineSpacing(add, mult);
        configuredLineSpacingExtra = add;
        configuredLineSpacingMultiplier = mult;
        if (currentDocument != null && currentDocument.text != null) {
            schedulePageLayoutRebuild(true, hasPendingDisplayChar ? pendingDisplayCharIndex : visibleStart);
        }
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

    public void setup(DbHelper helper, MemoryDao memoryDao, UsageStatsDao usageDao,
            PageLayoutDao pageLayoutDao, TokenInfoProvider provider) {
        this.dbHelper = helper;
        this.memoryDao = memoryDao;
        this.usageDao = usageDao;
        this.provider = provider;
        this.pageLayoutDao = pageLayoutDao;
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
        displayWindowAround(globalCharIndex, globalCharIndex, true);
    }

    public void clearContent() {
        visibleStart = 0;
        visibleEnd = 0;
        currentDocument = null;
        tokenSpans.clear();
        sentenceRanges.clear();
        loggedExposures.clear();
        cancelPendingLayoutTask();
        pageRanges.clear();
        pageLayoutReady = false;
        currentLayoutSpec = null;
        currentPageIndex = 0;
        totalPages = 0;
        hasPendingDisplayChar = false;
        pendingDisplayCharIndex = 0;
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
        cancelPendingLayoutTask();
    }

    private void clearPendingTask(int sequence) {
        synchronized (loadTaskLock) {
            if (contentSequence.get() == sequence) {
                pendingLoadTask = null;
            }
        }
    }

    private void cancelPendingLayoutTask() {
        Future<?> task;
        synchronized (layoutTaskLock) {
            task = pendingLayoutTask;
            pendingLayoutTask = null;
        }
        if (task != null) {
            task.cancel(true);
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
        pageRanges.clear();
        pageLayoutReady = false;
        currentLayoutSpec = null;
        currentPageIndex = 0;
        totalPages = 0;
        int target = hasPendingInitialChar ? pendingInitialCharIndex : 0;
        pendingDisplayCharIndex = target;
        hasPendingDisplayChar = true;
        hasPendingInitialChar = false;
        setText("");
        schedulePageLayoutRebuild(false, target);
    }

    private void schedulePageLayoutRebuild(boolean force, int targetCharIndex) {
        if (currentDocument == null || currentDocument.text == null) {
            return;
        }
        int availableWidth = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        int availableHeight = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        int desiredChar = hasPendingDisplayChar ? pendingDisplayCharIndex : targetCharIndex;
        pendingDisplayCharIndex = Math.max(0, desiredChar);
        hasPendingDisplayChar = true;
        if (availableWidth <= 0 || availableHeight <= 0) {
            pageLayoutReady = false;
            return;
        }
        PageLayoutSpec spec = new PageLayoutSpec(availableWidth, availableHeight,
                Math.round(getTextSize()));
        if (!force && pageLayoutReady && currentLayoutSpec != null && currentLayoutSpec.equals(spec)) {
            showCharIndexWithExistingLayout(pendingDisplayCharIndex, true);
            return;
        }
        computePageLayoutAsync(spec, pendingDisplayCharIndex, !force);
    }

    private void computePageLayoutAsync(PageLayoutSpec spec, int targetCharIndex, boolean allowCache) {
        if (currentDocument == null || currentDocument.text == null) {
            return;
        }
        cancelPendingLayoutTask();
        final int sequence = layoutSequence.incrementAndGet();
        final String text = currentDocument.text;
        final List<WordBoundary> wordBoundaries = snapshotWordBoundaries();
        final TextPaint paint = new TextPaint(getPaint());
        final float lineSpacingExtra = configuredLineSpacingExtra;
        final float lineSpacingMultiplier = configuredLineSpacingMultiplier;
        final boolean includePad = getIncludeFontPadding();
        final String lang = languagePair;
        final String work = workId;
        final PageLayoutDao dao = pageLayoutDao;
        Future<?> task = contentExecutor.submit(() -> {
            try {
                List<PageRange> ranges = null;
                boolean fromCache = false;
                if (allowCache && dao != null && lang != null && !lang.isEmpty()
                        && work != null && !work.isEmpty()) {
                    List<PageLayoutEntry> cached = dao.loadLayout(lang, work,
                            spec.viewportWidth, spec.viewportHeight, spec.textSizePx);
                    if (!cached.isEmpty()) {
                        ranges = new ArrayList<>(cached.size());
                        for (PageLayoutEntry entry : cached) {
                            if (entry == null) continue;
                            int start = Math.max(0, entry.startCharInclusive);
                            int end = Math.max(start + 1, entry.endCharInclusive + 1);
                            ranges.add(new PageRange(start, end,
                                    entry.startWordIndex, entry.endWordIndex));
                        }
                        fromCache = true;
                    }
                }
                if (ranges == null) {
                    ranges = buildPageRanges(text, wordBoundaries, paint,
                            spec.viewportWidth, spec.viewportHeight,
                            lineSpacingExtra, lineSpacingMultiplier, includePad);
                }
                if (!fromCache && dao != null && ranges != null && !ranges.isEmpty()
                        && lang != null && !lang.isEmpty()
                        && work != null && !work.isEmpty()) {
                    List<PageLayoutEntry> entries = new ArrayList<>(ranges.size());
                    int total = ranges.size();
                    for (int i = 0; i < ranges.size(); i++) {
                        PageRange page = ranges.get(i);
                        if (page == null) continue;
                        int endInclusive = Math.max(page.startChar, page.endChar - 1);
                        entries.add(new PageLayoutEntry(i, total, page.startChar,
                                endInclusive, page.startWordIndex, page.endWordIndex));
                    }
                    dao.replaceLayout(lang, work, spec.viewportWidth, spec.viewportHeight,
                            spec.textSizePx, entries, System.currentTimeMillis());
                }
                final List<PageRange> finalRanges = ranges == null
                        ? Collections.emptyList()
                        : ranges;
                mainHandler.post(() -> applyPageLayout(sequence, spec, finalRanges,
                        targetCharIndex));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (sequence != layoutSequence.get()) return;
                    throw new RuntimeException(e);
                });
            }
        });
        synchronized (layoutTaskLock) {
            pendingLayoutTask = task;
        }
    }

    private void applyPageLayout(int sequence, PageLayoutSpec spec, List<PageRange> ranges,
            int targetCharIndex) {
        if (sequence != layoutSequence.get()) {
            return;
        }
        synchronized (layoutTaskLock) {
            if (pendingLayoutTask != null && pendingLayoutTask.isDone()) {
                pendingLayoutTask = null;
            }
        }
        pageRanges.clear();
        if (ranges != null && !ranges.isEmpty()) {
            pageRanges.addAll(ranges);
        }
        if ((pageRanges.isEmpty() || spec == null)
                && currentDocument != null && currentDocument.text != null
                && currentDocument.text.length() > 0) {
            int length = currentDocument.text.length();
            pageRanges.clear();
            pageRanges.add(new PageRange(0, length,
                    resolveWordIndexAt(0, true), resolveWordIndexAt(length - 1, false)));
        }
        currentLayoutSpec = spec;
        totalPages = pageRanges.size();
        pageLayoutReady = !pageRanges.isEmpty();
        if (!pageLayoutReady) {
            hasPendingDisplayChar = false;
            visibleStart = 0;
            visibleEnd = 0;
            setText("");
            if (windowChangeListener != null) {
                windowChangeListener.onWindowChanged(0, 0);
            }
            return;
        }
        int desiredChar = hasPendingDisplayChar ? pendingDisplayCharIndex : targetCharIndex;
        if (desiredChar < 0) {
            desiredChar = 0;
        }
        showCharIndexWithExistingLayout(desiredChar, true);
    }

    public int getCurrentPageIndex() {
        return currentPageIndex;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public boolean isPageLayoutReady() {
        return pageLayoutReady;
    }

    public int findPageIndexForChar(int charIndex) {
        if (pageRanges.isEmpty()) {
            return -1;
        }
        int docLength = getDocumentLength();
        int clamped = clamp(charIndex, 0, docLength);
        for (int i = 0; i < pageRanges.size(); i++) {
            PageRange page = pageRanges.get(i);
            if (page == null) continue;
            if (clamped >= page.startChar && clamped < page.endChar) {
                return i;
            }
        }
        return pageRanges.size() - 1;
    }

    private void showCharIndexWithExistingLayout(int charIndex, boolean notifyWindowChange) {
        if (!pageLayoutReady || pageRanges.isEmpty()) {
            return;
        }
        int pageIndex = findPageIndexForChar(charIndex);
        if (pageIndex < 0) {
            pageIndex = 0;
        }
        showPage(pageIndex, notifyWindowChange);
    }

    private void showPage(int pageIndex, boolean notifyWindowChange) {
        if (!pageLayoutReady || pageRanges.isEmpty()) {
            return;
        }
        int clampedIndex = clamp(pageIndex, 0, pageRanges.size() - 1);
        PageRange page = pageRanges.get(clampedIndex);
        if (page == null) {
            return;
        }
        currentPageIndex = clampedIndex;
        viewportStartChar = page.startChar;
        viewportEndChar = page.endChar;
        applyWindow(page.startChar, page.endChar, notifyWindowChange);
    }

    private List<WordBoundary> snapshotWordBoundaries() {
        if (tokenSpans.isEmpty()) {
            return Collections.emptyList();
        }
        List<WordBoundary> boundaries = new ArrayList<>(tokenSpans.size());
        int order = 1;
        for (TokenSpan span : tokenSpans) {
            if (span == null) continue;
            int start = span.getStartIndex();
            int end = span.getEndIndex();
            if (start < 0 || end <= start) continue;
            boundaries.add(new WordBoundary(order++, start, end));
        }
        return boundaries;
    }

    private List<PageRange> buildPageRanges(CharSequence text, List<WordBoundary> words,
            TextPaint paint, int viewportWidth, int viewportHeight, float lineSpacingExtra,
            float lineSpacingMultiplier, boolean includePad) {
        List<PageRange> ranges = new ArrayList<>();
        if (text == null) {
            return ranges;
        }
        int length = text.length();
        if (length == 0) {
            return ranges;
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            int startWord = words.isEmpty() ? -1 : words.get(0).order;
            int endWord = words.isEmpty() ? -1 : words.get(words.size() - 1).order;
            ranges.add(new PageRange(0, length, startWord, endWord));
            return ranges;
        }
        StaticLayout layout = buildStaticLayout(text, paint, viewportWidth,
                lineSpacingExtra, lineSpacingMultiplier, includePad);
        if (layout == null) {
            int startWord = words.isEmpty() ? -1 : words.get(0).order;
            int endWord = words.isEmpty() ? -1 : words.get(words.size() - 1).order;
            ranges.add(new PageRange(0, length, startWord, endWord));
            return ranges;
        }
        int lineCount = layout.getLineCount();
        if (lineCount <= 0) {
            int startWord = words.isEmpty() ? -1 : words.get(0).order;
            int endWord = words.isEmpty() ? -1 : words.get(words.size() - 1).order;
            ranges.add(new PageRange(0, length, startWord, endWord));
            return ranges;
        }
        int currentLine = 0;
        int wordPointer = 0;
        int wordCount = words.size();
        while (currentLine < lineCount) {
            int startChar = layout.getLineStart(currentLine);
            int pageTop = layout.getLineTop(currentLine);
            int lastLine = currentLine;
            int nextLine = currentLine + 1;
            while (nextLine < lineCount) {
                int lineBottom = layout.getLineBottom(nextLine);
                int height = lineBottom - pageTop;
                if (height <= viewportHeight) {
                    lastLine = nextLine;
                    nextLine++;
                } else {
                    break;
                }
            }
            if (lastLine == currentLine) {
                nextLine = currentLine + 1;
            } else {
                nextLine = lastLine + 1;
            }
            int endChar = nextLine < lineCount ? layout.getLineStart(nextLine) : length;
            if (endChar <= startChar) {
                endChar = Math.min(length, startChar + 1);
            }
            while (wordPointer < wordCount && words.get(wordPointer).end <= startChar) {
                wordPointer++;
            }
            int startWord = -1;
            int endWord = -1;
            int scan = wordPointer;
            while (scan < wordCount) {
                WordBoundary word = words.get(scan);
                if (word.start >= endChar) {
                    break;
                }
                if (startWord < 0) {
                    startWord = word.order;
                }
                endWord = word.order;
                scan++;
            }
            wordPointer = scan;
            ranges.add(new PageRange(startChar, Math.max(startChar + 1, endChar),
                    startWord, endWord));
            currentLine = nextLine;
        }
        return ranges;
    }

    private StaticLayout buildStaticLayout(CharSequence text, TextPaint paint, int viewportWidth,
            float lineSpacingExtra, float lineSpacingMultiplier, boolean includePad) {
        if (text == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(),
                    paint, viewportWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(includePad)
                    .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
            return builder.build();
        }
        return new StaticLayout(text, paint, viewportWidth, Layout.Alignment.ALIGN_NORMAL,
                lineSpacingMultiplier, lineSpacingExtra, includePad);
    }

    private int resolveWordIndexAt(int charIndex, boolean searchForward) {
        if (tokenSpans.isEmpty()) {
            return -1;
        }
        if (searchForward) {
            for (int i = 0; i < tokenSpans.size(); i++) {
                TokenSpan span = tokenSpans.get(i);
                if (span == null) continue;
                if (span.getEndIndex() > charIndex) {
                    return i + 1;
                }
            }
            return tokenSpans.size();
        } else {
            for (int i = tokenSpans.size() - 1; i >= 0; i--) {
                TokenSpan span = tokenSpans.get(i);
                if (span == null) continue;
                if (span.getStartIndex() <= charIndex) {
                    return i + 1;
                }
            }
            return tokenSpans.isEmpty() ? -1 : 1;
        }
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
        pendingDisplayCharIndex = target;
        hasPendingDisplayChar = true;
        if (!pageLayoutReady) {
            schedulePageLayoutRebuild(false, target);
            return;
        }
        showCharIndexWithExistingLayout(target, notifyWindowChange);
    }

    private void applyWindow(int windowStart, int windowEnd, boolean notifyWindowChange) {
        if (currentDocument == null || currentDocument.text == null) {
            return;
        }
        int docLength = currentDocument.text.length();
        int clampedStart = clamp(windowStart, 0, docLength);
        int clampedEnd = clamp(windowEnd, clampedStart, docLength);
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
        viewportStartChar = clampedStart;
        viewportEndChar = clampedEnd;
        hasPendingDisplayChar = false;
        post(this::logVisibleExposures);
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

    public void onViewportChanged(int scrollY, int viewportHeight) {
        if (!windowChangeInProgress) {
            viewportStartChar = visibleStart;
            viewportEndChar = visibleEnd;
        }
        logVisibleExposures();
    }

    public boolean hasPreviousPage() {
        return pageLayoutReady && currentPageIndex > 0;
    }

    public boolean hasNextPage() {
        return pageLayoutReady && currentPageIndex + 1 < pageRanges.size();
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
        return pageRanges.get(currentPageIndex + 1).startChar;
    }

    public int findPreviousPageStart() {
        if (!hasPreviousPage()) {
            return -1;
        }
        return pageRanges.get(currentPageIndex - 1).startChar;
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
        Layout layout = getLayout();
        CharSequence text = getText();
        if (layout == null || text == null) return;
        int contentLength = text.length();
        if (contentLength <= 0) return;
        for (TokenSpan span : tokenSpans) {
            if (span == null || loggedExposures.contains(span)) continue;
            int globalStart = span.getStartIndex();
            int globalEnd = span.getEndIndex();
            if (globalEnd <= visibleStart || globalStart >= visibleEnd) {
                continue;
            }
            recordExposure(span, System.currentTimeMillis());
        }
    }

    private void recordExposure(TokenSpan span, long timestamp) {
        if (span == null || span.token == null || span.token.morphology == null) return;
        if (usageDao == null) return;
        if (!loggedExposures.add(span)) return;
        Morphology morph = span.token.morphology;
        usageDao.recordEvent(languagePair, workId, morph.lemma, morph.pos, null,
                UsageStatsDao.EVENT_EXPOSURE, timestamp, span.getStartIndex());
    }

    private static final class PageRange {
        final int startChar;
        final int endChar;
        final int startWordIndex;
        final int endWordIndex;

        PageRange(int startChar, int endChar, int startWordIndex, int endWordIndex) {
            this.startChar = startChar;
            this.endChar = endChar;
            this.startWordIndex = startWordIndex;
            this.endWordIndex = endWordIndex;
        }
    }

    private static final class WordBoundary {
        final int order;
        final int start;
        final int end;

        WordBoundary(int order, int start, int end) {
            this.order = order;
            this.start = start;
            this.end = end;
        }
    }

    private static final class PageLayoutSpec {
        final int viewportWidth;
        final int viewportHeight;
        final int textSizePx;

        PageLayoutSpec(int viewportWidth, int viewportHeight, int textSizePx) {
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.textSizePx = textSizePx;
        }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof PageLayoutSpec)) return false;
            PageLayoutSpec other = (PageLayoutSpec) obj;
            return viewportWidth == other.viewportWidth
                    && viewportHeight == other.viewportHeight
                    && textSizePx == other.textSizePx;
        }

        @Override public int hashCode() {
            int result = viewportWidth;
            result = 31 * result + viewportHeight;
            result = 31 * result + textSizePx;
            return result;
        }
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
