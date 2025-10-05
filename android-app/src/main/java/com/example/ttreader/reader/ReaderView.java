package com.example.ttreader.reader;

import android.content.Context;
import android.graphics.Typeface;
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
import com.example.ttreader.data.PaginationDao;
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

    private static final int PAGE_CHUNK_SIZE = 4000;
    private static final int MIN_PAGE_ADVANCE_CHARS = 64;
    private static final float FLOAT_TOLERANCE = 0.01f;

    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private UsageStatsDao usageDao;
    private DictionaryDao dictDao;
    private PaginationDao paginationDao;
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
    private final List<Page> pages = new ArrayList<>();
    private int viewportHeight = 0;
    private boolean paginationDirty = true;
    private boolean paginationCacheLoaded = false;
    private boolean paginationLocked = false;
    private boolean hasPendingTarget = false;
    private boolean pendingNotifyWindowChange = false;
    private int pendingTargetCharIndex = 0;
    private int currentPageIndex = 0;
    private PaginationSpec activePaginationSpec;
    private Runnable pendingInitialCompletion;
    private boolean initialContentDelivered = false;
    private int currentDocumentSignature = 0;
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

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            markPaginationDirty();
            post(this::showPendingTargetIfPossible);
        }
    }

    private void init() {
        setTextIsSelectable(false);
        setLineSpacing(1.2f, 1.2f);
        setHorizontalFadingEdgeEnabled(false);
        setVerticalFadingEdgeEnabled(false);
        setFadingEdgeLength(0);
        setOverScrollMode(OVER_SCROLL_NEVER);
        sentenceOutlineColor = resolveColorResource(com.example.ttreader.R.color.reader_sentence_outline);
        letterHighlightColor = resolveColorResource(com.example.ttreader.R.color.reader_letter_highlight);
        float density = getResources().getDisplayMetrics().density;
        sentenceOutlineStrokeWidth = 2f * density;
        sentenceOutlineCornerRadius = 6f * density;
        setMovementMethod(movementMethod);
        setTextColor(resolveColorResource(com.example.ttreader.R.color.reader_text_primary));
        setTypeface(Typeface.SERIF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setLetterSpacing(0.01f);
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
                      PaginationDao paginationDao, TokenInfoProvider provider) {
        this.dbHelper = helper;
        this.memoryDao = memoryDao;
        this.usageDao = usageDao;
        this.provider = provider;
        this.paginationDao = paginationDao;
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

    public void setViewportHeight(int height) {
        int clamped = Math.max(0, height);
        if (clamped != viewportHeight) {
            viewportHeight = clamped;
            if (!shouldIgnoreViewportChange(clamped)) {
                markPaginationDirty();
            }
        }
        showPendingTargetIfPossible();
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
            requestDisplayForChar(charIndex, false);
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

    public void clearContent() {
        visibleStart = 0;
        visibleEnd = 0;
        currentDocument = null;
        tokenSpans.clear();
        sentenceRanges.clear();
        loggedExposures.clear();
        pages.clear();
        currentPageIndex = 0;
        paginationDirty = true;
        paginationCacheLoaded = false;
        paginationLocked = false;
        hasPendingTarget = false;
        pendingNotifyWindowChange = false;
        pendingTargetCharIndex = 0;
        activePaginationSpec = null;
        pendingInitialCompletion = null;
        initialContentDelivered = false;
        currentDocumentSignature = 0;
        setText("");
        activeSentenceSpan = null;
        activeLetterSpan = null;
        activeSentenceStart = -1;
        activeSentenceEnd = -1;
        activeLetterIndex = -1;
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
                    applyLoadResult(result, completion);
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

    private void applyLoadResult(LoadResult result, Runnable completion) {
        if (result == null) {
            return;
        }
        currentDocument = result;
        tokenSpans.clear();
        tokenSpans.addAll(result.tokenSpans);
        loggedExposures.clear();
        sentenceRanges.clear();
        sentenceRanges.addAll(result.sentenceRanges);
        pages.clear();
        currentPageIndex = 0;
        paginationDirty = true;
        paginationCacheLoaded = false;
        paginationLocked = false;
        activePaginationSpec = null;
        initialContentDelivered = false;
        pendingInitialCompletion = completion;
        currentDocumentSignature = computeDocumentSignature(result.text);
        int target = hasPendingInitialChar ? pendingInitialCharIndex : 0;
        hasPendingInitialChar = false;
        requestDisplayForChar(target, true);
    }

    public void displayWindowAround(int targetCharIndex) {
        requestDisplayForChar(targetCharIndex, true);
    }

    public void ensureWindowContains(int globalCharIndex) {
        if (globalCharIndex < visibleStart || globalCharIndex >= visibleEnd) {
            requestDisplayForChar(globalCharIndex, true);
        }
    }

    public void scrollToGlobalChar(int globalCharIndex) {
        requestDisplayForChar(globalCharIndex, true);
    }

    private void requestDisplayForChar(int targetCharIndex, boolean notifyWindowChange) {
        int docLength = getDocumentLength();
        if (docLength == 0) {
            clearContent();
            return;
        }
        pendingTargetCharIndex = clamp(targetCharIndex, 0, docLength);
        hasPendingTarget = true;
        pendingNotifyWindowChange = pendingNotifyWindowChange || notifyWindowChange;
        showPendingTargetIfPossible();
    }

    private void showPendingTargetIfPossible() {
        if (!hasPendingTarget) {
            return;
        }
        if (!ensurePagination()) {
            return;
        }
        int target = pendingTargetCharIndex;
        boolean notify = pendingNotifyWindowChange;
        hasPendingTarget = false;
        pendingNotifyWindowChange = false;
        showPageForChar(target, notify);
    }

    private boolean ensurePagination() {
        if (currentDocument == null || currentDocument.text == null) {
            return false;
        }
        if (viewportHeight <= 0 || getWidth() <= 0) {
            return false;
        }
        PaginationSpec spec = captureCurrentSpec();
        if (spec == null) {
            return false;
        }
        if (!paginationCacheLoaded) {
            paginationCacheLoaded = true;
            if (applyCachedPagination(spec)) {
                paginationDirty = false;
                paginationLocked = true;
                activePaginationSpec = spec;
            }
        }
        if (paginationDirty) {
            recomputePagination(spec);
            paginationDirty = false;
            paginationLocked = true;
            activePaginationSpec = spec;
            persistPagination(spec);
        }
        return !pages.isEmpty();
    }

    private void markPaginationDirty() {
        if (currentDocument == null || currentDocument.text == null) {
            paginationDirty = true;
            return;
        }
        PaginationSpec currentSpec = captureCurrentSpec();
        if (paginationLocked && activePaginationSpec != null && currentSpec != null
                && activePaginationSpec.matchesDimensions(currentSpec)) {
            return;
        }
        paginationDirty = true;
        paginationLocked = false;
        paginationCacheLoaded = false;
        activePaginationSpec = null;
        if (!pages.isEmpty()) {
            pages.clear();
        }
        if (!currentDocument.text.isEmpty()) {
            int clampedVisibleStart = clamp(visibleStart, 0, currentDocument.text.length());
            if (!hasPendingTarget) {
                pendingTargetCharIndex = clampedVisibleStart;
                hasPendingTarget = true;
            } else {
                pendingTargetCharIndex = clamp(pendingTargetCharIndex, 0, currentDocument.text.length());
            }
            pendingNotifyWindowChange = true;
        }
    }

    private boolean shouldIgnoreViewportChange(int newViewportHeight) {
        if (!paginationLocked || activePaginationSpec == null) {
            return false;
        }
        int contentHeight = Math.max(0, newViewportHeight - getTotalPaddingTop() - getTotalPaddingBottom());
        return activePaginationSpec.contentHeight == contentHeight;
    }

    private PaginationSpec captureCurrentSpec() {
        if (viewportHeight <= 0 || getWidth() <= 0) {
            return null;
        }
        int contentWidth = getWidth() - getTotalPaddingLeft() - getTotalPaddingRight();
        int contentHeight = viewportHeight - getTotalPaddingTop() - getTotalPaddingBottom();
        if (contentWidth <= 0 || contentHeight <= 0) {
            return null;
        }
        return new PaginationSpec(contentWidth, contentHeight, getTextSize(),
                getLineSpacingExtra(), getLineSpacingMultiplier(), resolveLetterSpacing(),
                currentDocumentSignature);
    }

    private float resolveLetterSpacing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return getLetterSpacing();
        }
        return 0f;
    }

    private boolean applyCachedPagination(PaginationSpec spec) {
        if (paginationDao == null || spec == null) {
            return false;
        }
        PaginationDao.Snapshot snapshot = paginationDao.getSnapshot(languagePair, workId);
        if (snapshot == null) {
            return false;
        }
        if (!spec.matchesSnapshot(snapshot)) {
            paginationDao.deleteSnapshot(languagePair, workId);
            return false;
        }
        List<PaginationDao.PageBreak> cachedPages = snapshot.pageBreaks;
        if (cachedPages == null || cachedPages.isEmpty()) {
            paginationDao.deleteSnapshot(languagePair, workId);
            return false;
        }
        pages.clear();
        int docLength = getDocumentLength();
        int previousEnd = 0;
        for (PaginationDao.PageBreak page : cachedPages) {
            if (page == null) {
                paginationDao.deleteSnapshot(languagePair, workId);
                return false;
            }
            if (page.start < previousEnd) {
                paginationDao.deleteSnapshot(languagePair, workId);
                return false;
            }
            if (page.end < page.start) {
                paginationDao.deleteSnapshot(languagePair, workId);
                return false;
            }
            if (page.end == page.start && docLength > 0) {
                paginationDao.deleteSnapshot(languagePair, workId);
                return false;
            }
            pages.add(new Page(page.start, page.end));
            previousEnd = page.end;
        }
        if (pages.isEmpty()) {
            paginationDao.deleteSnapshot(languagePair, workId);
            return false;
        }
        if (docLength > 0 && pages.get(pages.size() - 1).end < docLength) {
            paginationDao.deleteSnapshot(languagePair, workId);
            return false;
        }
        return true;
    }

    private void persistPagination(PaginationSpec spec) {
        if (paginationDao == null || spec == null || pages.isEmpty()) {
            return;
        }
        List<PaginationDao.PageBreak> pageBreaks = new ArrayList<>(pages.size());
        for (Page page : pages) {
            if (page == null) {
                continue;
            }
            pageBreaks.add(new PaginationDao.PageBreak(page.start, page.end));
        }
        if (pageBreaks.isEmpty()) {
            return;
        }
        PaginationDao.Snapshot snapshot = new PaginationDao.Snapshot(
                languagePair, workId, spec.contentWidth, spec.contentHeight,
                spec.textSize, spec.lineSpacingExtra, spec.lineSpacingMultiplier,
                spec.letterSpacing, spec.documentSignature, pageBreaks,
                System.currentTimeMillis());
        paginationDao.saveSnapshot(snapshot);
    }

    private static boolean floatsEqual(float a, float b) {
        return Math.abs(a - b) <= FLOAT_TOLERANCE;
    }

    private int computeDocumentSignature(String text) {
        if (text == null) {
            return 0;
        }
        int hash = text.hashCode();
        return 31 * hash + text.length();
    }

    private void recomputePagination(PaginationSpec spec) {
        pages.clear();
        if (currentDocument == null || currentDocument.text == null || spec == null) {
            return;
        }
        String text = currentDocument.text;
        int docLength = text.length();
        if (docLength == 0) {
            pages.add(new Page(0, 0));
            return;
        }
        int availableWidth = Math.max(1, spec.contentWidth);
        int availableHeight = Math.max(1, spec.contentHeight);
        int start = 0;
        while (start < docLength) {
            int end = computePageEnd(text, start, availableWidth, availableHeight);
            if (end <= start) {
                end = Math.min(docLength, start + Math.max(MIN_PAGE_ADVANCE_CHARS, availableWidth));
            }
            pages.add(new Page(start, end));
            start = end;
        }
        if (pages.isEmpty()) {
            pages.add(new Page(0, docLength));
        }
    }

    private int computePageEnd(String text, int start, int availableWidth, int availableHeight) {
        int docLength = text.length();
        int candidateEnd = Math.min(docLength, start + PAGE_CHUNK_SIZE);
        if (candidateEnd <= start) {
            return docLength;
        }
        CharSequence chunk = text.subSequence(start, candidateEnd);
        StaticLayout layout = buildStaticLayout(chunk, availableWidth);
        if (layout == null) {
            return candidateEnd;
        }
        int lineCount = layout.getLineCount();
        if (lineCount == 0) {
            return Math.min(docLength, start + MIN_PAGE_ADVANCE_CHARS);
        }
        int lastVisibleLine = 0;
        for (int i = 0; i < lineCount; i++) {
            if (layout.getLineBottom(i) <= availableHeight) {
                lastVisibleLine = i;
            } else {
                break;
            }
        }
        int localEnd = layout.getLineEnd(lastVisibleLine);
        if (localEnd <= 0) {
            localEnd = Math.min(chunk.length(), 1);
        }
        int trimmedLocalEnd = trimTrailingWhitespace(chunk.toString(), 0, localEnd);
        if (trimmedLocalEnd <= 0) {
            trimmedLocalEnd = Math.min(chunk.length(), localEnd);
            if (trimmedLocalEnd <= 0) {
                trimmedLocalEnd = Math.min(chunk.length(), 1);
            }
        }
        int pageEnd = start + trimmedLocalEnd;
        pageEnd = adjustPageEndToTokenBoundary(start, pageEnd);
        if (pageEnd <= start) {
            pageEnd = Math.min(docLength, start + trimmedLocalEnd);
            if (pageEnd <= start) {
                pageEnd = Math.min(docLength, start + MIN_PAGE_ADVANCE_CHARS);
            }
        }
        return pageEnd;
    }

    private StaticLayout buildStaticLayout(CharSequence text, int width) {
        if (text == null) return null;
        TextPaint paint = getPaint();
        if (paint == null) return null;
        int effectiveWidth = Math.max(1, width);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, effectiveWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
                    .build();
        } else {
            //noinspection deprecation
            return new StaticLayout(text, paint, effectiveWidth, Layout.Alignment.ALIGN_NORMAL,
                    getLineSpacingMultiplier(), getLineSpacingExtra(), false);
        }
    }

    private int adjustPageEndToTokenBoundary(int start, int candidate) {
        int docLength = getDocumentLength();
        int clamped = clamp(candidate, start + 1, docLength);
        int best = clamped;
        for (TokenSpan span : tokenSpans) {
            if (span == null) continue;
            int spanEnd = span.getEndIndex();
            if (spanEnd <= start) continue;
            if (spanEnd <= clamped) {
                best = spanEnd;
            } else {
                break;
            }
        }
        if (best <= start && currentDocument != null && currentDocument.text != null) {
            for (int i = clamped; i > start + 1; i--) {
                char c = currentDocument.text.charAt(i - 1);
                if (Character.isWhitespace(c) || c == ',' || c == '.' || c == ';'
                        || c == ':' || c == '!' || c == '?' || c == '-') {
                    best = i;
                    break;
                }
            }
        }
        if (best <= start) {
            best = clamped;
        }
        return best;
    }

    private void showPageForChar(int charIndex, boolean notifyWindowChange) {
        if (pages.isEmpty()) {
            return;
        }
        int index = findPageIndexForChar(charIndex);
        showPageAtIndex(index, notifyWindowChange);
    }

    private int findPageIndexForChar(int charIndex) {
        if (pages.isEmpty()) {
            return 0;
        }
        int low = 0;
        int high = pages.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Page page = pages.get(mid);
            if (charIndex < page.start) {
                high = mid - 1;
            } else if (charIndex >= page.end) {
                low = mid + 1;
            } else {
                return mid;
            }
        }
        return Math.max(0, Math.min(pages.size() - 1, low));
    }

    private void showPageAtIndex(int index, boolean notifyWindowChange) {
        if (pages.isEmpty()) {
            return;
        }
        int clampedIndex = Math.max(0, Math.min(index, pages.size() - 1));
        currentPageIndex = clampedIndex;
        Page page = pages.get(clampedIndex);
        applyPage(page.start, page.end, notifyWindowChange);
    }

    private void applyPage(int pageStart, int pageEnd, boolean notifyWindowChange) {
        if (currentDocument == null || currentDocument.text == null) {
            clearContent();
            return;
        }
        int docLength = currentDocument.text.length();
        int clampedStart = clamp(pageStart, 0, docLength);
        int clampedEnd = clamp(pageEnd, clampedStart, docLength);
        if (clampedEnd <= clampedStart) {
            clampedEnd = Math.min(docLength, clampedStart + MIN_PAGE_ADVANCE_CHARS);
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(
                currentDocument.text.substring(clampedStart, clampedEnd));
        for (TokenSpan span : tokenSpans) {
            if (span == null) continue;
            int spanStart = span.getStartIndex();
            int spanEnd = span.getEndIndex();
            if (spanEnd <= clampedStart || spanStart >= clampedEnd) {
                continue;
            }
            int localStart = Math.max(0, spanStart - clampedStart);
            int localEnd = Math.min(clampedEnd - clampedStart, spanEnd - clampedStart);
            if (localEnd <= localStart) {
                continue;
            }
            builder.setSpan(span, localStart, localEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        visibleStart = clampedStart;
        visibleEnd = clampedEnd;
        setText(builder);
        deliverInitialContentIfNeeded();
        if (getMovementMethod() != movementMethod) {
            setMovementMethod(movementMethod);
        }
        reapplySpeechHighlights();
        logVisibleExposures();
        if (notifyWindowChange && windowChangeListener != null) {
            windowChangeListener.onWindowChanged(visibleStart, visibleEnd);
        }
    }

    private void deliverInitialContentIfNeeded() {
        if (initialContentDelivered) {
            return;
        }
        initialContentDelivered = true;
        if (pendingInitialCompletion != null) {
            pendingInitialCompletion.run();
            pendingInitialCompletion = null;
        }
    }

    public boolean hasPreviousPage() {
        return currentPageIndex > 0;
    }

    public boolean hasNextPage() {
        return currentPageIndex + 1 < pages.size();
    }

    public int getCurrentPageIndex() {
        return Math.max(0, Math.min(currentPageIndex, pages.size() - 1));
    }

    public int getTotalPageCount() {
        return pages.size();
    }

    public int getPageIndexForChar(int charIndex) {
        return findPageIndexForChar(charIndex);
    }

    public int getViewportStartChar() {
        return visibleStart;
    }

    public int getViewportEndChar() {
        return visibleEnd;
    }

    public int findNextPageStart() {
        if (!hasNextPage()) {
            return -1;
        }
        return pages.get(currentPageIndex + 1).start;
    }

    public int findPreviousPageStart() {
        if (!hasPreviousPage()) {
            return -1;
        }
        return pages.get(currentPageIndex - 1).start;
    }

    private void reapplySpeechHighlights() {
        if (activeSentenceStart >= 0 && activeSentenceEnd > activeSentenceStart) {
            highlightSentenceRange(activeSentenceStart, activeSentenceEnd);
        }
        if (activeLetterIndex >= 0) {
            highlightLetter(activeLetterIndex);
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
        if (visibleEnd <= visibleStart) return;
        long now = System.currentTimeMillis();
        for (TokenSpan span : tokenSpans) {
            if (span == null || loggedExposures.contains(span)) continue;
            int globalStart = span.getStartIndex();
            int globalEnd = span.getEndIndex();
            if (globalEnd <= visibleStart || globalStart >= visibleEnd) {
                continue;
            }
            recordExposure(span, now);
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

    private static final class PaginationSpec {
        final int contentWidth;
        final int contentHeight;
        final float textSize;
        final float lineSpacingExtra;
        final float lineSpacingMultiplier;
        final float letterSpacing;
        final int documentSignature;

        PaginationSpec(int contentWidth, int contentHeight, float textSize,
                        float lineSpacingExtra, float lineSpacingMultiplier,
                        float letterSpacing, int documentSignature) {
            this.contentWidth = Math.max(1, contentWidth);
            this.contentHeight = Math.max(1, contentHeight);
            this.textSize = textSize;
            this.lineSpacingExtra = lineSpacingExtra;
            this.lineSpacingMultiplier = lineSpacingMultiplier;
            this.letterSpacing = letterSpacing;
            this.documentSignature = documentSignature;
        }

        boolean matchesSnapshot(PaginationDao.Snapshot snapshot) {
            if (snapshot == null) {
                return false;
            }
            return contentWidth == snapshot.contentWidth
                    && contentHeight == snapshot.contentHeight
                    && documentSignature == snapshot.documentSignature
                    && floatsEqual(textSize, snapshot.textSize)
                    && floatsEqual(lineSpacingExtra, snapshot.lineSpacingExtra)
                    && floatsEqual(lineSpacingMultiplier, snapshot.lineSpacingMultiplier)
                    && floatsEqual(letterSpacing, snapshot.letterSpacing);
        }

        boolean matchesDimensions(PaginationSpec other) {
            if (other == null) {
                return false;
            }
            return contentWidth == other.contentWidth
                    && contentHeight == other.contentHeight
                    && documentSignature == other.documentSignature
                    && floatsEqual(textSize, other.textSize)
                    && floatsEqual(lineSpacingExtra, other.lineSpacingExtra)
                    && floatsEqual(lineSpacingMultiplier, other.lineSpacingMultiplier)
                    && floatsEqual(letterSpacing, other.letterSpacing);
        }
    }

    private static final class Page {
        final int start;
        final int end;

        Page(int start, int end) {
            this.start = Math.max(0, start);
            this.end = Math.max(this.start, end);
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
