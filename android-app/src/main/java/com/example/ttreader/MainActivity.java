package com.example.ttreader;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.core.content.ContextCompat;

import com.example.ttreader.data.DbHelper;
import com.example.ttreader.data.DeviceIdentity;
import com.example.ttreader.data.DeviceStatsDao;
import com.example.ttreader.data.MemoryDao;
import com.example.ttreader.data.PaginationDao;
import com.example.ttreader.data.ReadingStateDao;
import com.example.ttreader.data.UiLayoutDao;
import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.model.ReadingState;
import com.example.ttreader.reader.ReaderView;
import com.example.ttreader.reader.TokenSpan;
import com.example.ttreader.ui.TokenInfoBottomSheet;
import com.example.ttreader.util.DetailSpeechFormatter;
import com.example.ttreader.util.GrammarResources;
import com.example.ttreader.tts.RhvoiceAvailability;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements ReaderView.TokenInfoProvider {
    private static final String TAG = "MainActivity";
    private static final String LAYOUT_LOG_TAG = "ReaderLayoutTrace";
    private static final String LANGUAGE_PAIR_TT_RU = "tt-ru";
    private static final int MENU_WORK_ID_BASE = 100;
    private static final String PREFS_READER_STATE = "com.example.ttreader.reader_state";
    private static final String KEY_LAST_WORK = "reader.last.work";

    private static final int APPROX_CHARS_PER_PAGE = 1000;
    private static final float PAGE_CONTROLS_LOADING_ALPHA = 0.5f;
    private static final int READER_PAGE_HEIGHT_ADJUSTMENT_LINES = -1;
    private static final String PREF_KEY_PAGE_HEIGHT_ADJUSTMENT_APPLIED =
            "reader.page.height.adjustment.applied.v2";
    private static final String PREF_KEY_PAGE_HEIGHT_ADJUSTMENT_APPLIED_LEGACY =
            "reader.page.height.reduction.applied";
    private static final class WorkInfo {
        final String id;
        final String asset;
        final int fullNameRes;
        final int shortNameRes;

        WorkInfo(String id, String asset, int fullNameRes, int shortNameRes) {
            this.id = id;
            this.asset = asset;
            this.fullNameRes = fullNameRes;
            this.shortNameRes = shortNameRes;
        }

        String getFullName(Activity activity) {
            return activity.getString(fullNameRes);
        }

        String getShortName(Activity activity) {
            return activity.getString(shortNameRes);
        }
    }

    private static final class ViewBoundsSnapshot {
        private static final float FLOAT_TOLERANCE = 0.5f;
        int left = Integer.MIN_VALUE;
        int top = Integer.MIN_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        float translationX = Float.NaN;
        float translationY = Float.NaN;

        boolean isInitialized() {
            return left != Integer.MIN_VALUE;
        }

        boolean matches(int l, int t, int r, int b, float tx, float ty) {
            return left == l && top == t && right == r && bottom == b
                    && floatsEqual(translationX, tx) && floatsEqual(translationY, ty);
        }

        void update(int l, int t, int r, int b, float tx, float ty) {
            left = l;
            top = t;
            right = r;
            bottom = b;
            translationX = tx;
            translationY = ty;
        }

        void reset() {
            left = Integer.MIN_VALUE;
            top = Integer.MIN_VALUE;
            right = Integer.MIN_VALUE;
            bottom = Integer.MIN_VALUE;
            translationX = Float.NaN;
            translationY = Float.NaN;
        }

        private boolean floatsEqual(float a, float b) {
            if (Float.isNaN(a) && Float.isNaN(b)) {
                return true;
            }
            if (Float.isNaN(a) || Float.isNaN(b)) {
                return false;
            }
            return Math.abs(a - b) <= FLOAT_TOLERANCE;
        }
    }

    private final List<WorkInfo> availableWorks = Collections.singletonList(
            new WorkInfo("qubiz_qabiz", "qabiz_qubiz.fb2",
                    R.string.work_name_kubyzkabyz_full, R.string.work_name_kubyzkabyz_short)
    );
    private static final String RHVOICE_PACKAGE = "com.github.olga_yakovleva.rhvoice.android";
    private static final String TALGAT_NAME_KEYWORD = "talgat";
    private static final float BASE_CHARS_PER_SECOND = 14f;
    private static final float DEFAULT_SPEECH_RATE = 1f;
    private static final int MENU_LANGUAGE_PAIR_TT_RU = 1;
    private static final int REQUEST_TYPE_SENTENCE = 1;
    private static final int REQUEST_TYPE_DETAIL = 2;
    private static final int PREFETCH_SENTENCE_COUNT = 2;
    private static final long SKIP_BACK_RESET_PROGRESS_MS = 5000L;

    public static final String EXTRA_TARGET_CHAR_INDEX = "com.example.ttreader.TARGET_CHAR_INDEX";

    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private UsageStatsDao usageStatsDao;
    private DeviceStatsDao deviceStatsDao;
    private ReadingStateDao readingStateDao;
    private PaginationDao paginationDao;
    private UiLayoutDao uiLayoutDao;
    private ScrollView readerScrollView;
    private View readerPageContainer;
    private ReaderView readerView;
    private ProgressBar readerLoadingIndicator;
    private ImageButton pagePreviousButton;
    private ImageButton pageNextButton;
    private View pageControls;
    private TextView pageNumberText;
    private final ViewBoundsSnapshot pageControlsBounds = new ViewBoundsSnapshot();
    private final ViewBoundsSnapshot pageNumberBounds = new ViewBoundsSnapshot();
    private final ViewBoundsSnapshot readerViewBounds = new ViewBoundsSnapshot();
    private int lastLoggedPageIndex = -1;
    private int lastLoggedPageTotal = -1;
    private int readerBasePaddingLeft;
    private int readerBasePaddingTop;
    private int readerBasePaddingRight;
    private int readerBasePaddingBottom;
    private View readerOverlaySpacer;
    private int readerOverlaySpacerBaseHeight;
    private int readerViewportBottomInset;
    private int lastDispatchedViewportHeight = -1;
    private boolean readerViewportDispatchScheduled;
    private boolean readerPageHeightAdjustmentApplied;
    private boolean readerPageHeightFixed;
    private boolean readerPageHeightAdjustmentRetryScheduled;
    private final List<Runnable> pendingViewportReadyActions = new ArrayList<>();
    private boolean awaitingViewportMeasurement;
    private boolean readerViewportReady;
    private int lastOverlayClearance;
    private int lastKnownOverlayHeight = -1;
    private int persistedPageControlsHeight;
    private int persistedReaderPageHeight;
    private String activeLayoutWorkId = "";
    private ViewTreeObserver.OnPreDrawListener viewportReadyListener;
    private boolean flushingViewportActions;
    private boolean overlayInsetRetryScheduled;
    private boolean readerWindowInitialized;
    private boolean readerHasRenderableContent;
    private ReadingState currentReadingState;
    private SharedPreferences readerPrefs;
    private final Handler readingStateHandler = new Handler(Looper.getMainLooper());
    private final Runnable persistReadingRunnable = this::persistReadingStateNow;
    private int pendingVisualChar = 0;
    private int pendingVisualPage = 0;
    private int pendingSpeechChar = -1;
    private String pendingLastMode = ReadingState.MODE_VISUAL;
    private boolean readingStateDirty = false;
    private boolean restoringReadingState = false;
    private Toolbar toolbar;
    private MenuItem languagePairMenuItem;
    private MenuItem workMenuItem;
    private MenuItem toggleSpeechMenuItem;
    private MenuItem stopSpeechMenuItem;
    private MenuItem skipBackMenuItem;
    private MenuItem skipForwardMenuItem;
    private MenuItem installTalgatMenuItem;
    private AlertDialog rhvoiceDialog;
    private String currentLanguagePair = LANGUAGE_PAIR_TT_RU;
    private boolean languagePairInitialized = false;
    private WorkInfo currentWork;
    private int readingProgressColor;
    private int listeningProgressColor;

    private final Handler speechProgressHandler = new Handler(Looper.getMainLooper());
    private final Handler speechStopHandler = new Handler(Looper.getMainLooper());
    private final List<ReaderView.SentenceRange> sentenceRanges = new ArrayList<>();
    private final Map<String, SpeechRequest> pendingRequests = new HashMap<>();
    private final Map<Integer, SpeechRequest> preparedSentenceRequests = new HashMap<>();
    private final Set<Integer> pendingSentenceIndices = new HashSet<>();

    private SpeechRequest currentSentenceRequest;
    private SpeechRequest pendingDetailRequest;
    private SpeechRequest activeDetailRequest;
    private MediaPlayer sentencePlayer;
    private MediaPlayer detailPlayer;
    private int utteranceSequence = 0;
    private int pendingPlaybackSentenceIndex = -1;
    private boolean awaitingResumeAfterDetail = false;
    private boolean detailAutoResume = false;
    private TokenSpan lastDetailSpan;
    private boolean lastDetailResumeAfter = false;
    private long currentLetterIntervalMs = 0L;
    private boolean promptModeActive = false;
    private int promptTokenIndex = -1;
    private int promptBaseCharIndex = -1;
    private boolean skipBackGoPrevious = false;
    private boolean skipBackRestartArmed = false;

    private final UtteranceProgressListener synthesisListener = new UtteranceProgressListener() {
        @Override public void onStart(String utteranceId) {
        }

        @Override public void onDone(String utteranceId) {
            speechProgressHandler.post(() -> handleSynthesisFinished(utteranceId, false));
        }

        @Override public void onError(String utteranceId) {
            speechProgressHandler.post(() -> handleSynthesisFinished(utteranceId, true));
        }
    };

    private final Runnable speechProgressRunnable = new Runnable() {
        @Override public void run() {
            if (!isSpeaking) return;
            if (sentencePlayer == null) return;
            if (currentSentenceRequest == null || currentSentenceRequest.sentenceRange == null) return;
            long duration = estimatedUtteranceDurationMs > 0
                    ? estimatedUtteranceDurationMs
                    : sentencePlayer.getDuration();
            if (duration <= 0) return;
            long elapsed = Math.max(0, sentencePlayer.getCurrentPosition());
            updateLetterHighlightForElapsed(elapsed, duration);
            if ((skipBackGoPrevious || skipBackRestartArmed)
                    && elapsed >= SKIP_BACK_RESET_PROGRESS_MS) {
                clearSkipBackBehavior();
            }
            long interval = currentLetterIntervalMs > 0 ? currentLetterIntervalMs : 50L;
            speechProgressHandler.postDelayed(this, Math.max(25L, interval / 2L));
        }
    };

    private static final class SpeechRequest {
        final int type;
        final int sentenceIndex;
        final ReaderView.SentenceRange sentenceRange;
        final TokenSpan tokenSpan;
        final File file;
        final String utteranceId;
        final boolean resumeAfterPlayback;
        long durationMs;

        SpeechRequest(int type, int sentenceIndex, ReaderView.SentenceRange sentenceRange,
                TokenSpan tokenSpan, File file, String utteranceId, boolean resumeAfterPlayback) {
            this.type = type;
            this.sentenceIndex = sentenceIndex;
            this.sentenceRange = sentenceRange;
            this.tokenSpan = tokenSpan;
            this.file = file;
            this.utteranceId = utteranceId;
            this.resumeAfterPlayback = resumeAfterPlayback;
        }

        void deleteFile() {
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static final class PendingDevicePauseEvent {
        final DeviceIdentity device;
        final long pauseOffsetMs;
        final long letterIntervalMs;
        final int pauseCharIndex;
        final int sentenceIndex;
        final String languagePair;
        final String workId;
        final long recordedAtMs;

        PendingDevicePauseEvent(DeviceIdentity device, long pauseOffsetMs, long letterIntervalMs,
                int pauseCharIndex, int sentenceIndex, String languagePair, String workId,
                long recordedAtMs) {
            this.device = device;
            this.pauseOffsetMs = pauseOffsetMs;
            this.letterIntervalMs = letterIntervalMs;
            this.pauseCharIndex = pauseCharIndex;
            this.sentenceIndex = sentenceIndex;
            this.languagePair = languagePair == null ? "" : languagePair;
            this.workId = workId == null ? "" : workId;
            this.recordedAtMs = recordedAtMs;
        }
    }

    private TextToSpeech textToSpeech;
    private Voice talgatVoice;
    private boolean ttsReady = false;
    private boolean rhVoiceInstalled = false;
    private boolean shouldContinueSpeech = false;
    private boolean speechSessionActive = false;
    private boolean speechStoppingInProgress = false;
    private boolean isSpeaking = false;
    private int currentSentenceIndex = 0;
    private int currentSentenceStart = -1;
    private int currentSentenceEnd = -1;
    private int currentCharIndex = -1;
    private long estimatedUtteranceDurationMs = 0L;
    private MediaSession mediaSession;
    private PendingDevicePauseEvent pendingDevicePauseEvent;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GrammarResources.initialize(this);
        setContentView(R.layout.activity_main);

        readingProgressColor = ContextCompat.getColor(this, R.color.work_menu_progress_reading);
        listeningProgressColor = ContextCompat.getColor(this, R.color.work_menu_progress_listening);

        dbHelper = new DbHelper(this);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        memoryDao = new MemoryDao(db);
        usageStatsDao = new UsageStatsDao(db);
        deviceStatsDao = new DeviceStatsDao(db);
        readingStateDao = new ReadingStateDao(db);
        paginationDao = new PaginationDao(db);
        uiLayoutDao = new UiLayoutDao(db);
        Integer storedHeight = uiLayoutDao.getPageControlsHeight();
        persistedPageControlsHeight = storedHeight == null ? 0 : Math.max(0, storedHeight);
        Log.d(LAYOUT_LOG_TAG, "Loaded persisted page controls height: " + persistedPageControlsHeight);

        readerPrefs = getSharedPreferences(PREFS_READER_STATE, MODE_PRIVATE);
        if (readerPrefs != null) {
            String lastWorkId = readerPrefs.getString(KEY_LAST_WORK, null);
            WorkInfo saved = findWorkById(lastWorkId);
            if (saved != null) {
                currentWork = saved;
            }
        }
        if (readerPrefs != null) {
            readerPageHeightAdjustmentApplied = readerPrefs.getBoolean(
                    PREF_KEY_PAGE_HEIGHT_ADJUSTMENT_APPLIED, false);
            if (readerPrefs.contains(PREF_KEY_PAGE_HEIGHT_ADJUSTMENT_APPLIED_LEGACY)) {
                readerPrefs.edit().remove(PREF_KEY_PAGE_HEIGHT_ADJUSTMENT_APPLIED_LEGACY).apply();
            }
            readerPageHeightFixed = readerPageHeightAdjustmentApplied;
        }

        toolbar = findViewById(R.id.topToolbar);
        if (toolbar != null) {
            toolbar.setTitle(null);
            toolbar.setSubtitle(null);
            setActionBar(toolbar);
            ActionBar actionBar = getActionBar();
            if (actionBar != null) {
                actionBar.setDisplayShowTitleEnabled(false);
                actionBar.setDisplayUseLogoEnabled(true);
                actionBar.setDisplayShowHomeEnabled(true);
                actionBar.setLogo(R.mipmap.ic_launcher);
            }
        }

        readerScrollView = findViewById(R.id.readerScrollView);
        readerPageContainer = findViewById(R.id.readerPageContainer);
        if (readerPageContainer != null) {
            readerPageContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop,
                    oldRight, oldBottom) -> {
                int newHeight = Math.max(0, bottom - top);
                int previousHeight = Math.max(0, oldBottom - oldTop);
                logViewEvent("ReaderPageContainer", readerPageContainer,
                        "onLayout height=" + newHeight + " oldHeight=" + previousHeight);
                if (newHeight > 0) {
                    maybePersistReaderPageHeight(newHeight, "onLayout");
                }
            });
            enforceReaderPageHeight("onCreate");
        }
        readerOverlaySpacer = findViewById(R.id.readerOverlaySpacer);
        readerView = findViewById(R.id.readerView);
        if (readerView != null) {
            readerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop,
                    oldRight, oldBottom) -> {
                int newHeight = Math.max(0, bottom - top);
                int previousHeight = Math.max(0, oldBottom - oldTop);
                logViewEvent("ReaderView", readerView,
                        "onLayout height=" + newHeight + " oldHeight=" + previousHeight);
            });
        }
        readerLoadingIndicator = findViewById(R.id.readerLoadingIndicator);
        pagePreviousButton = findViewById(R.id.pagePreviousButton);
        pageNextButton = findViewById(R.id.pageNextButton);
        pageControls = findViewById(R.id.pageControls);
        pageNumberText = findViewById(R.id.pageNumberText);

        if (pageControls != null) {
            enforcePageControlsMinHeight("onCreate");
            logViewEvent("PageControls", pageControls,
                    "initialize persistedHeight=" + persistedPageControlsHeight);
        }
        if (pageNumberText != null) {
            pageNumberText.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop,
                    oldRight, oldBottom) -> logViewEvent("PageNumberText", pageNumberText,
                    "onLayout height=" + Math.max(0, bottom - top)
                            + " oldHeight=" + Math.max(0, oldBottom - oldTop)));
        }
        if (pagePreviousButton != null) {
            pagePreviousButton.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft,
                    oldTop, oldRight, oldBottom) -> logViewEvent("PagePreviousButton",
                    pagePreviousButton,
                    "onLayout height=" + Math.max(0, bottom - top)
                            + " oldHeight=" + Math.max(0, oldBottom - oldTop)));
        }
        if (pageNextButton != null) {
            pageNextButton.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft,
                    oldTop, oldRight, oldBottom) -> logViewEvent("PageNextButton", pageNextButton,
                    "onLayout height=" + Math.max(0, bottom - top)
                            + " oldHeight=" + Math.max(0, oldBottom - oldTop)));
        }

        if (readerView != null) {
            readerBasePaddingLeft = readerView.getPaddingLeft();
            readerBasePaddingTop = readerView.getPaddingTop();
            readerBasePaddingRight = readerView.getPaddingRight();
            readerBasePaddingBottom = readerView.getPaddingBottom();
            readerView.setup(dbHelper, memoryDao, usageStatsDao, paginationDao, this);
            readerView.setWindowChangeListener(this::handleReaderWindowChanged);
            readerView.setNavigationStateListener(ready -> {
                logViewEvent("ReaderView", readerView, "navigationState -> " + ready);
                updatePageControls();
            });
        }

        if (readerScrollView != null) {
            readerScrollView.setVerticalFadingEdgeEnabled(false);
            readerScrollView.setFadingEdgeLength(0);
            readerScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            readerScrollView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (bottom - top != oldBottom - oldTop) {
                    dispatchReaderViewportChanged();
                }
            });
            scheduleReaderViewportDispatch();
        }

        if (readerOverlaySpacer != null && readerOverlaySpacer.getLayoutParams() != null) {
            readerOverlaySpacerBaseHeight = readerOverlaySpacer.getLayoutParams().height;
        }

        if (pageControls != null) {
            pageControls.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int newHeight = Math.max(0, bottom - top);
                if (pageControls.getVisibility() != View.VISIBLE) {
                    newHeight = 0;
                }
                int previousHeight = Math.max(0, oldBottom - oldTop);
                logViewEvent("PageControls", pageControls,
                        "onLayout height=" + newHeight + " oldHeight=" + previousHeight);
                if (newHeight > 0) {
                    maybePersistPageControlsHeight(newHeight, "onLayout");
                }
                if (lastKnownOverlayHeight != newHeight) {
                    lastKnownOverlayHeight = newHeight;
                    logViewEvent("PageControls", pageControls,
                            "layoutHeightChanged -> " + newHeight);
                    updateReaderBottomInset();
                }
            });
        }

        if (pagePreviousButton != null) {
            pagePreviousButton.setOnClickListener(v -> {
                logViewEvent("PagePreviousButton", pagePreviousButton, "click");
                goToPreviousPage();
            });
        }
        if (pageNextButton != null) {
            pageNextButton.setOnClickListener(v -> {
                logViewEvent("PageNextButton", pageNextButton, "click");
                goToNextPage();
            });
        }

        if (pageControls != null) {
            pageControls.post(() -> {
                logViewEvent("PageControls", pageControls, "postUpdateReaderBottomInset");
                updateReaderBottomInset();
            });
        } else {
            updateReaderBottomInset();
        }

        updatePageControls();

        ensureDefaultWork();
        applyLanguagePair(LANGUAGE_PAIR_TT_RU);

        rhVoiceInstalled = isRhVoiceInstalled();
        initMediaSession();
        initTextToSpeech();
        updateInstallButtonVisibility();
        updateSpeechButtons();

        handleNavigationIntent(getIntent());
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_actions, menu);
        languagePairMenuItem = menu.findItem(R.id.action_language_pair);
        workMenuItem = menu.findItem(R.id.action_select_work);
        toggleSpeechMenuItem = menu.findItem(R.id.action_toggle_speech);
        stopSpeechMenuItem = menu.findItem(R.id.action_stop_speech);
        skipBackMenuItem = menu.findItem(R.id.action_skip_back);
        skipForwardMenuItem = menu.findItem(R.id.action_skip_forward);
        installTalgatMenuItem = menu.findItem(R.id.action_install_talgat);
        setupLanguagePairActionView();
        setupWorkMenuItem();
        updateLanguagePairDisplay();
        updateWorkMenuDisplay();
        updateSpeechButtons();
        updateInstallButtonVisibility();
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        setOverflowMenuIconsVisible(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item == null) return super.onOptionsItemSelected(item);
        int id = item.getItemId();
        if (id == R.id.action_language_pair) {
            showLanguagePairMenu(getLanguagePairAnchor());
            return true;
        } else if (id == R.id.action_select_work) {
            showWorkMenu(getWorkAnchor());
            return true;
        } else if (id == R.id.action_language_stats) {
            openLanguageStats();
            return true;
        } else if (id == R.id.action_work_stats) {
            openWorkStats();
            return true;
        } else if (id == R.id.action_device_stats) {
            openDeviceStats();
            return true;
        } else if (id == R.id.action_toggle_speech) {
            toggleSpeech();
            return true;
        } else if (id == R.id.action_stop_speech) {
            stopSpeech();
            return true;
        } else if (id == R.id.action_skip_forward) {
            handleMediaSkip(true);
            return true;
        } else if (id == R.id.action_skip_back) {
            handleMediaSkip(false);
            return true;
        } else if (id == R.id.action_install_talgat) {
            openTalgatInstall();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNavigationIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        updateSentenceRanges();
        updateRhVoiceState();
        ensureRhvoiceReady();
    }

    @Override protected void onPause() {
        persistReadingStateNow();
        super.onPause();
    }

    @Override protected void onDestroy() {
        readingStateHandler.removeCallbacks(persistReadingRunnable);
        stopSpeech();
        if (textToSpeech != null) {
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        dismissRhvoiceDialog();
        speechProgressHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (cancelDetailSpeech()) {
            return;
        }
        super.onBackPressed();
    }

    @Override public void onTokenSingleTap(TokenSpan span) {
        if (span == null) {
            return;
        }
        dismissTokenSheet(false);
        int start = Math.max(0, span.getStartIndex());
        currentCharIndex = start;
        updatePendingSpeechChar(currentCharIndex);
        if (!isSpeechModeActive()) {
            markLastModeVisual();
        }
        adjustSentenceIndexForChar(start);
        if (readerView != null) {
            ReaderView.SentenceRange sentence = readerView.findSentenceForCharIndex(start);
            if (sentence != null) {
                currentSentenceStart = sentence.start;
                currentSentenceEnd = sentence.end;
                readerView.highlightSentenceRange(sentence.start, sentence.end);
            }
            readerView.highlightLetter(start);
            readerView.scrollToGlobalChar(start);
        }
    }

    @Override public void onTokenLongPress(TokenSpan span, List<String> ruLemmas) {
        boolean resumeAfter = isSpeaking || shouldContinueSpeech;
        speakTokenDetails(span, ruLemmas, resumeAfter);
        showTokenSheet(span, ruLemmas);
    }

    private void speakTokenDetails(TokenSpan span, List<String> translations, boolean resumeAfter) {
        if (!ttsReady || textToSpeech == null || talgatVoice == null) return;
        if (span == null || span.token == null) return;
        List<String> combinedTranslations = new ArrayList<>();
        if (translations != null) {
            combinedTranslations.addAll(translations);
        }
        if (span.token != null && span.token.translations != null) {
            combinedTranslations.addAll(span.token.translations);
        }
        List<String> safeTranslations = DetailSpeechFormatter.sanitizeTranslations(combinedTranslations);
        String detailText = DetailSpeechFormatter.buildDetailSpeech(span, safeTranslations, true);
        if (TextUtils.isEmpty(detailText)) {
            return;
        }
        lastDetailSpan = span;
        lastDetailResumeAfter = resumeAfter;
        pauseSpeechForDetail();
        shouldContinueSpeech = resumeAfter;
        awaitingResumeAfterDetail = !resumeAfter;
        detailAutoResume = resumeAfter;
        stopDetailPlayback();
        cancelPendingDetailRequest();
        try {
            File file = File.createTempFile("detail_" + Math.max(0, span.getStartIndex()) + "_", ".wav", getCacheDir());
            String utteranceId = "detail_" + (utteranceSequence++);
            SpeechRequest request = new SpeechRequest(REQUEST_TYPE_DETAIL, -1, null, span, file, utteranceId, resumeAfter);
            pendingDetailRequest = request;
            pendingRequests.put(utteranceId, request);
            textToSpeech.setVoice(talgatVoice);
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
            int result = textToSpeech.synthesizeToFile(detailText, params, file, utteranceId);
            if (result != TextToSpeech.SUCCESS) {
                pendingRequests.remove(utteranceId);
                pendingDetailRequest = null;
                request.deleteFile();
                awaitingResumeAfterDetail = !resumeAfter;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to synthesize detail audio", e);
            awaitingResumeAfterDetail = !resumeAfter;
        }
    }

    private void cancelPendingDetailRequest() {
        if (pendingDetailRequest == null) {
            return;
        }
        removePendingRequest(pendingDetailRequest);
        pendingDetailRequest.deleteFile();
        pendingDetailRequest = null;
    }

    private void removePendingRequest(SpeechRequest target) {
        if (target == null) {
            return;
        }
        Iterator<Map.Entry<String, SpeechRequest>> iterator = pendingRequests.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SpeechRequest> entry = iterator.next();
            if (entry.getValue() == target) {
                iterator.remove();
                break;
            }
        }
    }

    private void handleNavigationIntent(Intent intent) {
        if (intent == null) return;
        int targetIndex = intent.getIntExtra(EXTRA_TARGET_CHAR_INDEX, -1);
        if (targetIndex < 0) return;
        intent.removeExtra(EXTRA_TARGET_CHAR_INDEX);
        if (readerView == null) return;
        readerView.post(() -> navigateToCharIndex(targetIndex, 0));
    }

    private void openLanguageStats() {
        Intent intent = new Intent(this, StatsActivity.class);
        intent.putExtra(StatsActivity.EXTRA_MODE, StatsActivity.MODE_LANGUAGE);
        intent.putExtra(StatsActivity.EXTRA_LANGUAGE_PAIR, currentLanguagePair);
        startActivity(intent);
    }

    private void openWorkStats() {
        WorkInfo work = getCurrentWork();
        if (work == null) {
            Toast.makeText(this, R.string.work_menu_button_unset, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, StatsActivity.class);
        intent.putExtra(StatsActivity.EXTRA_MODE, StatsActivity.MODE_WORK);
        intent.putExtra(StatsActivity.EXTRA_LANGUAGE_PAIR, currentLanguagePair);
        intent.putExtra(StatsActivity.EXTRA_WORK_ID, work.id);
        startActivity(intent);
    }

    private void openDeviceStats() {
        Intent intent = new Intent(this, DeviceStatsActivity.class);
        startActivity(intent);
    }

    private void showLanguagePairMenu(View anchor) {
        View safeAnchor = anchor != null ? anchor : getLanguagePairAnchor();
        if (safeAnchor == null) return;
        PopupMenu menu = new PopupMenu(this, safeAnchor);
        MenuItem ttRuItem = menu.getMenu().add(Menu.NONE, MENU_LANGUAGE_PAIR_TT_RU, Menu.NONE,
                getLanguagePairDisplayName(LANGUAGE_PAIR_TT_RU));
        ttRuItem.setIcon(R.drawable.ic_language_pair_tt_ru);
        ttRuItem.setCheckable(true);
        ttRuItem.setChecked(LANGUAGE_PAIR_TT_RU.equals(currentLanguagePair));
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_LANGUAGE_PAIR_TT_RU) {
                applyLanguagePair(LANGUAGE_PAIR_TT_RU);
                return true;
            }
            return false;
        });
        forceShowMenuIcons(menu);
        menu.show();
    }

    private View getLanguagePairAnchor() {
        if (languagePairMenuItem != null) {
            View actionView = languagePairMenuItem.getActionView();
            if (actionView != null) {
                return actionView;
            }
        }
        if (toolbar != null) {
            return toolbar;
        }
        if (readerView != null) {
            return readerView;
        }
        return getWindow() != null ? getWindow().getDecorView() : null;
    }

    private void setupLanguagePairActionView() {
        if (languagePairMenuItem == null) return;
        View actionView = languagePairMenuItem.getActionView();
        if (actionView != null) {
            actionView.setOnClickListener(v -> showLanguagePairMenu(v));
            actionView.setOnLongClickListener(v -> {
                showLanguagePairMenu(v);
                return true;
            });
            actionView.setFocusable(true);
            actionView.setContentDescription(languagePairMenuItem.getTitle());
        }
        languagePairMenuItem.setOnMenuItemClickListener(item -> {
            showLanguagePairMenu(getLanguagePairAnchor());
            return true;
        });
        updateLanguagePairIcon();
    }

    private void setupWorkMenuItem() {
        if (workMenuItem == null) return;
        workMenuItem.setOnMenuItemClickListener(item -> {
            showWorkMenu(getWorkAnchor());
            return true;
        });
        updateWorkMenuDisplay();
    }

    private void updateWorkMenuDisplay() {
        if (workMenuItem == null) return;
        WorkInfo work = getCurrentWork();
        if (work == null) {
            String title = getString(R.string.work_menu_button_unset);
            workMenuItem.setTitle(title);
            workMenuItem.setTitleCondensed(title);
            return;
        }
        String shortName = work.getShortName(this);
        String fullName = work.getFullName(this);
        String menuTitle = getString(R.string.work_menu_button_format, shortName);
        workMenuItem.setTitle(menuTitle);
        workMenuItem.setTitleCondensed(shortName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            workMenuItem.setContentDescription(fullName);
        }
    }

    private void showWorkMenu(View anchor) {
        View safeAnchor = anchor != null ? anchor : getWorkAnchor();
        if (safeAnchor == null) return;
        PopupMenu menu = new PopupMenu(this, safeAnchor);
        WorkInfo current = getCurrentWork();
        for (int i = 0; i < availableWorks.size(); i++) {
            WorkInfo work = availableWorks.get(i);
            CharSequence title = buildWorkMenuTitle(work);
            MenuItem item = menu.getMenu().add(Menu.NONE, MENU_WORK_ID_BASE + i, Menu.NONE, title);
            item.setCheckable(true);
            item.setChecked(work == current);
            item.setIcon(R.drawable.ic_menu_work);
        }
        menu.setOnMenuItemClickListener(item -> {
            WorkInfo selected = findWorkByMenuId(item.getItemId());
            if (selected != null) {
                applyWork(selected, true);
                return true;
            }
            return false;
        });
        forceShowMenuIcons(menu);
        menu.show();
    }

    private CharSequence buildWorkMenuTitle(WorkInfo work) {
        if (work == null) {
            return "";
        }
        String base = work.getFullName(this);
        if (readingStateDao == null) {
            return base;
        }
        ReadingState state = readingStateDao.getState(currentLanguagePair, work.id);
        if (state == null) {
            return base;
        }
        Integer readingPage = null;
        if (state.visualPage > 0) {
            readingPage = state.visualPage;
        } else if (state.visualCharIndex >= 0) {
            readingPage = approxPageForChar(state.visualCharIndex);
        }
        Integer listeningPage = null;
        if (state.voiceCharIndex >= 0) {
            listeningPage = approxPageForChar(state.voiceCharIndex);
        }
        if (readingPage == null && listeningPage == null) {
            return base;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(base);
        builder.append(' ');
        builder.append('(');
        if (readingPage != null) {
            appendColoredText(builder, String.valueOf(readingPage), readingProgressColor);
        } else {
            builder.append('—');
        }
        builder.append(' ');
        builder.append('/');
        builder.append(' ');
        if (listeningPage != null) {
            appendColoredText(builder, String.valueOf(listeningPage), listeningProgressColor);
        } else {
            builder.append('—');
        }
        builder.append(')');
        return builder;
    }

    private void appendColoredText(SpannableStringBuilder builder, String text, int color) {
        if (builder == null || TextUtils.isEmpty(text)) {
            return;
        }
        int start = builder.length();
        builder.append(text);
        if (color != 0) {
            builder.setSpan(new ForegroundColorSpan(color), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private int approxPageForChar(int charIndex) {
        if (readerView != null && readerView.getDocumentLength() > 0) {
            int index = readerView.getPageIndexForChar(Math.max(0, charIndex));
            return index + 1;
        }
        if (charIndex < 0) {
            return 1;
        }
        int pageSize = Math.max(1, APPROX_CHARS_PER_PAGE);
        return (charIndex / pageSize) + 1;
    }

    private void updateLanguagePairIcon() {
        if (languagePairMenuItem == null) return;
        int iconRes = R.drawable.ic_language_menu;
        languagePairMenuItem.setIcon(iconRes);
        View actionView = languagePairMenuItem.getActionView();
        if (actionView == null) return;
        ImageView iconView = actionView.findViewById(R.id.languagePairIcon);
        if (iconView != null) {
            iconView.setImageResource(iconRes);
        }
    }

    private View getWorkAnchor() {
        if (toolbar != null) {
            return toolbar;
        }
        if (readerView != null) {
            return readerView;
        }
        return getWindow() != null ? getWindow().getDecorView() : null;
    }

    private WorkInfo findWorkById(String workId) {
        if (workId == null) {
            return null;
        }
        for (WorkInfo info : availableWorks) {
            if (info != null && workId.equals(info.id)) {
                return info;
            }
        }
        return null;
    }

    private WorkInfo findWorkByMenuId(int menuId) {
        int index = menuId - MENU_WORK_ID_BASE;
        if (index >= 0 && index < availableWorks.size()) {
            return availableWorks.get(index);
        }
        return null;
    }

    private void ensureDefaultWork() {
        if (currentWork == null && !availableWorks.isEmpty()) {
            currentWork = availableWorks.get(0);
        }
    }

    private WorkInfo getCurrentWork() {
        ensureDefaultWork();
        return currentWork;
    }

    private void reloadReaderForCurrentSelection() {
        WorkInfo work = getCurrentWork();
        if (readerView == null || work == null) return;
        String workId = work.id == null ? "" : work.id;
        if (!TextUtils.equals(activeLayoutWorkId, workId)) {
            activeLayoutWorkId = workId;
            refreshPersistedReaderPageHeight("reloadReaderForCurrentSelection");
        } else {
            enforceReaderPageHeight("reloadReaderForCurrentSelection");
        }
        if (readingStateDao != null) {
            currentReadingState = readingStateDao.getState(currentLanguagePair, work.id);
        }
        readerView.setUsageContext(currentLanguagePair, work.id);
        shouldContinueSpeech = currentReadingState != null
                && currentReadingState.isVoiceMode()
                && currentReadingState.voiceCharIndex >= 0;
        int initialChar = resolveInitialCharIndex(work);
        pendingVisualChar = initialChar;
        pendingVisualPage = approxPageForChar(initialChar);
        pendingSpeechChar = -1;
        restoringReadingState = true;
        showReaderLoading(true);
        readerWindowInitialized = false;
        readerHasRenderableContent = false;
        lastDispatchedViewportHeight = -1;
        lastOverlayClearance = -1;
        readerView.clearContent();
        runWhenReaderViewportReady(() -> readerView.loadFromDocumentAsset(work.asset, initialChar, () -> {
            updateSentenceRanges();
            int speechChar = resolveSavedSpeechChar(work);
            int focusChar = resolveSavedFocusChar(work);
            if (currentReadingState != null) {
                pendingLastMode = currentReadingState.isVoiceMode()
                        ? ReadingState.MODE_VOICE
                        : ReadingState.MODE_VISUAL;
            }
            restoringReadingState = false;
            int anchor;
            if (speechChar >= 0) {
                anchor = speechChar;
            } else if (focusChar >= 0) {
                anchor = focusChar;
            } else {
                anchor = initialChar;
            }
            readerView.scrollToGlobalChar(anchor);
            if (speechChar >= 0) {
                currentCharIndex = speechChar;
                updatePendingSpeechChar(currentCharIndex);
                adjustSentenceIndexForChar(speechChar);
                if (readerView != null) {
                    ReaderView.SentenceRange sentence = readerView.findSentenceForCharIndex(speechChar);
                    if (sentence != null) {
                        currentSentenceStart = sentence.start;
                        currentSentenceEnd = sentence.end;
                        readerView.highlightSentenceRange(sentence.start, sentence.end);
                    }
                    readerView.highlightLetter(speechChar);
                }
                if (currentReadingState != null && currentReadingState.isVoiceMode()) {
                    shouldContinueSpeech = true;
                }
            } else if (focusChar >= 0) {
                adjustSentenceIndexForChar(focusChar);
                shouldContinueSpeech = false;
            } else {
                shouldContinueSpeech = false;
            }
            showReaderLoading(false);
        }));
        updateWorkMenuDisplay();
    }

    private int resolveInitialCharIndex(WorkInfo work) {
        if (currentReadingState != null && currentReadingState.visualCharIndex >= 0) {
            return currentReadingState.visualCharIndex;
        }
        return 0;
    }

    private int resolveSavedSpeechChar(WorkInfo work) {
        if (currentReadingState != null && currentReadingState.voiceCharIndex >= 0) {
            return currentReadingState.voiceCharIndex;
        }
        return -1;
    }

    private int resolveSavedFocusChar(WorkInfo work) {
        if (currentReadingState != null && currentReadingState.visualCharIndex >= 0) {
            return currentReadingState.visualCharIndex;
        }
        return -1;
    }

    private void handleReaderWindowChanged(int start, int end) {
        pendingVisualChar = Math.max(0, start);
        if (readerView != null) {
            int viewportStart = readerView.getViewportStartChar();
            if (viewportStart >= 0) {
                pendingVisualChar = viewportStart;
            }
            pendingVisualPage = readerView.getCurrentPageIndex() + 1;
        } else {
            pendingVisualPage = approxPageForChar(pendingVisualChar);
        }
        markLastModeVisual();
        if (readerView != null) {
            readerView.post(() -> {
                Log.d(LAYOUT_LOG_TAG, "handleReaderWindowChanged: window=" + start + "-" + end
                        + " viewportStart=" + readerView.getViewportStartChar()
                        + " page=" + (readerView.getCurrentPageIndex() + 1)
                        + "/" + readerView.getTotalPageCount());
                if (!readerWindowInitialized) {
                    readerWindowInitialized = true;
                    resetReaderScrollOffset();
                }
                boolean hasContent = readerView != null && readerView.hasRenderedContent();
                if (hasContent && !readerHasRenderableContent) {
                    readerHasRenderableContent = true;
                    int containerHeight = readerPageContainer != null
                            ? readerPageContainer.getHeight() : 0;
                    if (containerHeight > 0) {
                        maybePersistReaderPageHeight(containerHeight,
                                "windowInitialized");
                    }
                }
                int viewportStart = readerView.getViewportStartChar();
                if (viewportStart >= 0) {
                    pendingVisualChar = viewportStart;
                }
                if (readerView != null) {
                    pendingVisualPage = readerView.getCurrentPageIndex() + 1;
                } else {
                    pendingVisualPage = approxPageForChar(pendingVisualChar);
                }
                updatePageControls();
                int currentPageIndex = readerView != null ? readerView.getCurrentPageIndex() + 1 : -1;
                int totalPages = readerView != null ? readerView.getTotalPageCount() : 0;
                int cardHeight = readerPageContainer != null ? readerPageContainer.getHeight() : 0;
                logViewEvent("ReaderPageContainer", readerPageContainer,
                        "afterWindowChanged page=" + currentPageIndex + "/" + Math.max(1, totalPages)
                                + " height=" + cardHeight);
                int readerHeight = readerView != null ? readerView.getHeight() : 0;
                logViewEvent("ReaderView", readerView,
                        "afterWindowChanged page=" + currentPageIndex + "/" + Math.max(1, totalPages)
                                + " height=" + readerHeight);
                logReaderViewBounds("afterWindowChanged");
                boolean adjustedHeight = reconcileReaderPageHeightWithContent("afterWindowChanged");
                if (!adjustedHeight) {
                    enforceReaderPageHeight("afterWindowChanged");
                }
                logPageControlsBounds("afterWindowChanged");
            });
        } else {
            updatePageControls();
            int cardHeight = readerPageContainer != null ? readerPageContainer.getHeight() : 0;
            logViewEvent("ReaderPageContainer", readerPageContainer,
                    "afterWindowChanged (noReaderView) height=" + cardHeight);
            logViewEvent("ReaderView", null,
                    "afterWindowChanged (noReaderView) height=0");
            logReaderViewBounds("afterWindowChanged:noReader");
            enforceReaderPageHeight("afterWindowChanged:noReader");
            logPageControlsBounds("afterWindowChanged:noReader");
        }
        if (!restoringReadingState) {
            schedulePersistReadingState();
        }
    }

    private boolean reconcileReaderPageHeightWithContent(String reason) {
        if (readerView == null) {
            return false;
        }
        if (readerPageHeightFixed) {
            logViewEvent("ReaderPageContainer", readerPageContainer,
                    "reconcileHeight skipped fixedHeight reason=" + reason);
            return false;
        }
        int contentHeight = readerView.getContentHeight();
        if (contentHeight <= 0) {
            return false;
        }
        int containerPadding = readerPageContainer == null
                ? 0
                : readerPageContainer.getPaddingTop() + readerPageContainer.getPaddingBottom();
        int desiredHeight = contentHeight + containerPadding;
        if (desiredHeight <= 0) {
            return false;
        }
        if (persistedReaderPageHeight > 0) {
            int tolerance = Math.round(getResources().getDisplayMetrics().density * 2f);
            if (persistedReaderPageHeight > desiredHeight + tolerance) {
                boolean updated = overrideReaderPageHeight(desiredHeight,
                        "content:" + reason);
                if (updated) {
                    enforceReaderPageHeight("override:" + reason);
                }
                return updated;
            }
        }
        return false;
    }

    private boolean overrideReaderPageHeight(int newHeight, String reason) {
        int safeHeight = Math.max(0, newHeight);
        if (safeHeight <= 0 || safeHeight == persistedReaderPageHeight) {
            return false;
        }
        persistedReaderPageHeight = safeHeight;
        logViewEvent("ReaderPageContainer", readerPageContainer,
                "overridePersistedHeight -> " + safeHeight + " reason=" + reason);
        if (uiLayoutDao != null) {
            uiLayoutDao.saveReaderPageHeight(null, safeHeight);
            if (!TextUtils.isEmpty(activeLayoutWorkId)) {
                uiLayoutDao.saveReaderPageHeight(activeLayoutWorkId, safeHeight);
            }
        }
        return true;
    }

    private void updatePageControls() {
        boolean navigationReady = readerView != null && readerView.isNavigationReady();
        boolean speechModeActive = isSpeechModeActive();
        boolean loadingVisible = readerLoadingIndicator != null
                && readerLoadingIndicator.getVisibility() == View.VISIBLE;

        boolean prevEnabled = navigationReady && readerView != null && readerView.hasPreviousPage();
        boolean nextEnabled = navigationReady && readerView != null && readerView.hasNextPage();

        boolean controlsEnabled = !speechModeActive && !loadingVisible;
        if (!controlsEnabled) {
            prevEnabled = false;
            nextEnabled = false;
        }

        setEnabledWithLogging(pageControls, "PageControls", controlsEnabled);
        if (speechModeActive) {
            setAlphaWithLogging(pageControls, "PageControls", PAGE_CONTROLS_LOADING_ALPHA);
        } else if (!loadingVisible) {
            setAlphaWithLogging(pageControls, "PageControls", 1f);
        }

        setEnabledWithLogging(pagePreviousButton, "PagePreviousButton", prevEnabled);
        float prevAlpha = prevEnabled ? 1f : 0.3f;
        setAlphaWithLogging(pagePreviousButton, "PagePreviousButton", prevAlpha);

        setEnabledWithLogging(pageNextButton, "PageNextButton", nextEnabled);
        float nextAlpha = nextEnabled ? 1f : 0.3f;
        setAlphaWithLogging(pageNextButton, "PageNextButton", nextAlpha);

        boolean hasDocument = readerView != null && readerView.getDocumentLength() > 0;
        if (hasDocument) {
            int current = readerView.getCurrentPageIndex() + 1;
            int total = Math.max(1, readerView.getTotalPageCount());
            String formatted = String.format(Locale.getDefault(), "%d / %d", current, total);
            CharSequence existing = pageNumberText == null ? null : pageNumberText.getText();
            if (!TextUtils.equals(existing, formatted)) {
                setTextWithLogging(pageNumberText, "PageNumberText", formatted);
            } else {
                logViewEvent("PageNumberText", pageNumberText,
                        "setText no-op -> " + formatted);
            }
        } else {
            CharSequence dash = "—";
            CharSequence existing = pageNumberText == null ? null : pageNumberText.getText();
            if (!TextUtils.equals(existing, dash)) {
                setTextWithLogging(pageNumberText, "PageNumberText", dash);
            } else {
                logViewEvent("PageNumberText", pageNumberText,
                        "setText no-op -> " + dash);
            }
            lastLoggedPageIndex = -1;
            lastLoggedPageTotal = -1;
        }
        if (hasDocument) {
            int current = readerView.getCurrentPageIndex() + 1;
            int total = Math.max(1, readerView.getTotalPageCount());
            logPageProgress("updatePageControls", current, total);
        } else {
            logViewEvent("PageNumberText", pageNumberText,
                    "updatePageControls page=— (no document)");
        }
        logPageControlsBounds("updatePageControls");
        enforcePageControlsMinHeight("updatePageControls");
    }

    private void logPageControlsBounds(String stage) {
        logViewBoundsSnapshot("PageControls", pageControls, pageControlsBounds, stage);
        logViewBoundsSnapshot("PageNumberText", pageNumberText, pageNumberBounds, stage);
    }

    private void logPageProgress(String stage, int current, int total) {
        int safeTotal = Math.max(1, total);
        StringBuilder action = new StringBuilder(stage)
                .append(" page=").append(current).append('/')
                .append(safeTotal);
        if (lastLoggedPageIndex >= 0) {
            if (current == lastLoggedPageIndex && lastLoggedPageTotal == safeTotal) {
                action.append(" delta=0 steady");
                logViewEvent("PageNumberText", pageNumberText, action.toString());
                return;
            }
            int delta = current - lastLoggedPageIndex;
            if (delta == 1) {
                action.append(" delta=+").append(delta).append(" monotonic=true");
            } else {
                if (delta > 0) {
                    action.append(" delta=+").append(delta);
                } else {
                    action.append(" delta=").append(delta);
                }
                action.append(" monotonicViolation");
                Log.w(LAYOUT_LOG_TAG,
                        "PageNumberText: " + stage
                                + " nonIncremental page advance delta=" + delta
                                + " current=" + current + '/' + safeTotal
                                + " previous=" + lastLoggedPageIndex + '/' + lastLoggedPageTotal);
            }
            if (lastLoggedPageTotal >= 0 && lastLoggedPageTotal != safeTotal) {
                int totalDelta = safeTotal - lastLoggedPageTotal;
                action.append(" totalDelta=").append(totalDelta > 0 ? "+" + totalDelta : totalDelta);
            }
        } else {
            action.append(" initial");
        }
        logViewEvent("PageNumberText", pageNumberText, action.toString());
        lastLoggedPageIndex = current;
        lastLoggedPageTotal = safeTotal;
    }

    private void logViewBoundsSnapshot(String component, View view,
                                       ViewBoundsSnapshot snapshot, String stage) {
        if (view == null) {
            snapshot.reset();
            logViewEvent(component, null, stage + " bounds skipped (view null)");
            return;
        }
        int left = view.getLeft();
        int top = view.getTop();
        int right = view.getRight();
        int bottom = view.getBottom();
        float tx = view.getTranslationX();
        float ty = view.getTranslationY();
        StringBuilder action = new StringBuilder(stage)
                .append(" bounds=")
                .append('[').append(left).append(',').append(top)
                .append(" -> ").append(right).append(',').append(bottom).append(']')
                .append(" translation=")
                .append('[').append(tx).append(',').append(ty).append(']');
        if (snapshot.isInitialized()) {
            if (snapshot.matches(left, top, right, bottom, tx, ty)) {
                action.append(" status=unchanged");
            } else {
                action.append(" status=changed prevBounds=")
                        .append('[').append(snapshot.left).append(',').append(snapshot.top)
                        .append(" -> ").append(snapshot.right).append(',').append(snapshot.bottom).append(']')
                        .append(" prevTranslation=")
                        .append('[').append(snapshot.translationX).append(',').append(snapshot.translationY).append(']');
            }
        } else {
            action.append(" status=initial");
        }
        logViewEvent(component, view, action.toString());
        snapshot.update(left, top, right, bottom, tx, ty);
    }

    private void logReaderViewBounds(String stage) {
        logViewBoundsSnapshot("ReaderView", readerView, readerViewBounds, stage);
    }

    private void goToPreviousPage() {
        if (readerView == null) {
            return;
        }
        if (!readerView.isNavigationReady()) {
            logViewEvent("PagePreviousButton", pagePreviousButton, "navigationLocked");
            updatePageControls();
            return;
        }
        int target = readerView.findPreviousPageStart();
        if (target >= 0) {
            readerView.scrollToGlobalChar(target);
        }
        updatePageControls();
    }

    private void goToNextPage() {
        if (readerView == null) {
            return;
        }
        if (!readerView.isNavigationReady()) {
            logViewEvent("PageNextButton", pageNextButton, "navigationLocked");
            updatePageControls();
            return;
        }
        int target = readerView.findNextPageStart();
        if (target >= 0) {
            readerView.scrollToGlobalChar(target);
        }
        updatePageControls();
    }

    private void schedulePersistReadingState() {
        if (!readingStateDirty) {
            readingStateDirty = true;
        }
        readingStateHandler.removeCallbacks(persistReadingRunnable);
        readingStateHandler.postDelayed(persistReadingRunnable, 500);
    }

    private void persistReadingStateNow() {
        readingStateHandler.removeCallbacks(persistReadingRunnable);
        readingStateDirty = false;
        WorkInfo work = getCurrentWork();
        if (work == null) {
            return;
        }
        if (readerPrefs != null) {
            readerPrefs.edit().putString(KEY_LAST_WORK, work.id).apply();
        }
        if (readingStateDao == null) {
            return;
        }
        int safeVisualPage = Math.max(1, pendingVisualPage);
        int safeVisualChar = Math.max(0, pendingVisualChar);
        int speechChar = pendingSpeechChar >= 0 ? pendingSpeechChar : -1;
        int speechSentence = speechChar >= 0 ? Math.max(0, currentSentenceIndex) : -1;
        boolean voiceIsLast = ReadingState.MODE_VOICE.equals(pendingLastMode) && speechChar >= 0;
        boolean visualIsLast = !voiceIsLast;
        long now = System.currentTimeMillis();
        readingStateDao.updateVisualState(currentLanguagePair, work.id, safeVisualPage,
                safeVisualChar, now, visualIsLast);
        readingStateDao.updateVoiceState(currentLanguagePair, work.id, speechSentence,
                speechChar, now, voiceIsLast);
        String mode = voiceIsLast ? ReadingState.MODE_VOICE : ReadingState.MODE_VISUAL;
        currentReadingState = new ReadingState(currentLanguagePair, work.id, mode,
                safeVisualPage, safeVisualChar, speechSentence, speechChar, now);
        pendingLastMode = mode;
    }

    private void showReaderLoading(boolean show) {
        if (readerLoadingIndicator != null) {
            readerLoadingIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                readerLoadingIndicator.bringToFront();
            }
        }
        if (readerScrollView != null) {
            setVisibilityWithLogging(readerScrollView, "ReaderScrollView", View.VISIBLE);
        }
        setVisibilityWithLogging(pageControls, "PageControls", View.VISIBLE);
        setVisibilityWithLogging(pagePreviousButton, "PagePreviousButton", View.VISIBLE);
        setVisibilityWithLogging(pageNextButton, "PageNextButton", View.VISIBLE);

        if (show) {
            setAlphaWithLogging(pageControls, "PageControls", PAGE_CONTROLS_LOADING_ALPHA);
            setEnabledWithLogging(pageControls, "PageControls", false);
            setEnabledWithLogging(pagePreviousButton, "PagePreviousButton", false);
            setEnabledWithLogging(pageNextButton, "PageNextButton", false);
            setAlphaWithLogging(pagePreviousButton, "PagePreviousButton", 0.3f);
            setAlphaWithLogging(pageNextButton, "PageNextButton", 0.3f);
        } else {
            setEnabledWithLogging(pageControls, "PageControls", true);
            setAlphaWithLogging(pageControls, "PageControls", 1f);
            updatePageControls();
        }
        updateReaderBottomInset();
    }

    private void updateReaderBottomInset() {
        if (readerView == null) {
            return;
        }
        int left = readerBasePaddingLeft;
        int top = readerBasePaddingTop;
        int right = readerBasePaddingRight;

        int overlayHeight = 0;
        int overlayExtraClearance = 0;

        Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: begin overlayVisible="
                + (pageControls != null && pageControls.getVisibility() == View.VISIBLE));

        int overlayClearance = 0;
        int overlayGuardPadding = 0;
        boolean overlayActive = pageControls != null
                && pageControls.getVisibility() == View.VISIBLE;
        boolean awaitingMeasurement = false;
        if (overlayActive) {
            overlayHeight = resolvePageControlsOverlayHeight();
            if (overlayHeight <= 0) {
                if (!overlayInsetRetryScheduled) {
                    overlayInsetRetryScheduled = true;
                    pageControls.post(() -> {
                        overlayInsetRetryScheduled = false;
                        Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: retry after pending measurement");
                        updateReaderBottomInset();
                    });
                }
                awaitingMeasurement = true;
                Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: awaiting overlay measurement");
            } else {
                overlayInsetRetryScheduled = false;
                overlayExtraClearance = getResources()
                        .getDimensionPixelSize(R.dimen.reader_page_controls_clearance);
                overlayClearance = Math.max(0, overlayHeight + overlayExtraClearance);
                int desiredVisiblePadding = Math.max(overlayHeight, 0);
                if (desiredVisiblePadding > readerBasePaddingBottom) {
                    overlayGuardPadding = desiredVisiblePadding - readerBasePaddingBottom;
                }
                lastKnownOverlayHeight = overlayHeight;
                Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: overlayHeight=" + overlayHeight
                        + " extra=" + overlayExtraClearance + " clearance=" + overlayClearance);
            }
        } else {
            overlayInsetRetryScheduled = false;
            if (persistedPageControlsHeight > 0) {
                overlayExtraClearance = getResources().getDimensionPixelSize(
                        R.dimen.reader_page_controls_clearance);
                overlayHeight = persistedPageControlsHeight;
                overlayClearance = Math.max(0, overlayHeight + overlayExtraClearance);
                if (lastKnownOverlayHeight != persistedPageControlsHeight) {
                    lastKnownOverlayHeight = persistedPageControlsHeight;
                    Log.d(LAYOUT_LOG_TAG,
                            "updateReaderBottomInset: controls hidden using persisted overlay height="
                                    + persistedPageControlsHeight + " extra=" + overlayExtraClearance
                                    + " clearance=" + overlayClearance);
                } else {
                    Log.d(LAYOUT_LOG_TAG,
                            "updateReaderBottomInset: controls hidden retaining persisted overlay clearance="
                                    + overlayClearance);
                }
            } else {
                lastKnownOverlayHeight = 0;
                Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: controls hidden");
            }
        }

        if (awaitingMeasurement) {
            Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: exit awaiting measurement");
            return;
        }

        boolean overlayChanged = overlayClearance != lastOverlayClearance;
        if (overlayChanged) {
            lastOverlayClearance = overlayClearance;
            Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: overlay clearance changed -> " + overlayClearance);
        }

        int desiredBottomPadding = readerBasePaddingBottom
                + Math.max(overlayExtraClearance, overlayGuardPadding);
        boolean readerPaddingChanged = readerView.getPaddingLeft() != left
                || readerView.getPaddingTop() != top
                || readerView.getPaddingRight() != right
                || readerView.getPaddingBottom() != desiredBottomPadding;
        if (readerPaddingChanged) {
            readerView.setPadding(left, top, right, desiredBottomPadding);
            Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: reader padding -> L" + left
                    + " T" + top + " R" + right + " B" + desiredBottomPadding);
        }

        boolean spacerChanged = false;
        if (readerOverlaySpacer != null && readerOverlaySpacer.getLayoutParams() != null) {
            int targetHeight = Math.max(0, readerOverlaySpacerBaseHeight + overlayClearance);
            if (readerOverlaySpacer.getLayoutParams().height != targetHeight) {
                readerOverlaySpacer.getLayoutParams().height = targetHeight;
                readerOverlaySpacer.requestLayout();
                spacerChanged = true;
                Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: spacer height -> " + targetHeight);
            }
        }

        readerViewportBottomInset = overlayClearance;

        if (overlayChanged || readerPaddingChanged || spacerChanged) {
            Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: dispatch viewport change");
            dispatchReaderViewportChanged();
        }
        Log.d(LAYOUT_LOG_TAG, "updateReaderBottomInset: end");
    }

    private int resolvePageControlsOverlayHeight() {
        if (pageControls == null || pageControls.getVisibility() != View.VISIBLE) {
            return 0;
        }
        int overlayHeight = Math.max(pageControls.getHeight(), pageControls.getMeasuredHeight());
        if (overlayHeight <= 0 && persistedPageControlsHeight > 0) {
            overlayHeight = persistedPageControlsHeight;
            logViewEvent("PageControls", pageControls,
                    "usingPersistedHeightForInset -> " + overlayHeight);
        }
        if (overlayHeight > 0) {
            maybePersistPageControlsHeight(overlayHeight, "resolveOverlayHeight");
        }
        return overlayHeight;
    }

    private void maybePersistPageControlsHeight(int candidateHeight, String reason) {
        int safeHeight = Math.max(0, candidateHeight);
        if (safeHeight <= 0 || safeHeight <= persistedPageControlsHeight) {
            return;
        }
        persistedPageControlsHeight = safeHeight;
        if (uiLayoutDao != null) {
            uiLayoutDao.savePageControlsHeight(safeHeight);
        }
        enforcePageControlsMinHeight("persist:" + reason);
        logViewEvent("PageControls", pageControls,
                "persistHeight -> " + safeHeight + " reason=" + reason);
    }

    private void enforcePageControlsMinHeight(String reason) {
        if (pageControls == null || persistedPageControlsHeight <= 0) {
            return;
        }
        int minHeight = persistedPageControlsHeight;
        pageControls.setMinimumHeight(minHeight);
        ViewGroup.LayoutParams lp = pageControls.getLayoutParams();
        if (lp != null && lp.height > 0 && lp.height < minHeight) {
            lp.height = minHeight;
            pageControls.setLayoutParams(lp);
        }
        pageControls.requestLayout();
        logViewEvent("PageControls", pageControls,
                "enforceMinHeight -> " + minHeight + " reason=" + reason);
    }

    private void refreshPersistedReaderPageHeight(String reason) {
        int storedHeight = 0;
        if (uiLayoutDao != null) {
            Integer specific = uiLayoutDao.getReaderPageHeight(activeLayoutWorkId);
            if (specific == null && !TextUtils.isEmpty(activeLayoutWorkId)) {
                specific = uiLayoutDao.getReaderPageHeight(null);
            }
            if (specific != null) {
                storedHeight = Math.max(0, specific);
            }
        }
        int minimumHeight = computeMinimumPersistableReaderHeight();
        if (storedHeight > 0 && storedHeight < minimumHeight) {
            logViewEvent("ReaderPageContainer", readerPageContainer,
                    "loadPersistedHeight discarded height=" + storedHeight
                            + " min=" + minimumHeight + " reason=" + reason);
            clearPersistedReaderPageHeight("refreshBelowMinimum:" + reason);
            storedHeight = 0;
        }
        persistedReaderPageHeight = storedHeight;
        if (persistedReaderPageHeight > 0) {
            logViewEvent("ReaderPageContainer", readerPageContainer,
                    "loadPersistedHeight -> " + persistedReaderPageHeight
                            + " work=" + activeLayoutWorkId + " reason=" + reason);
            enforceReaderPageHeight(reason);
            maybeApplyReaderPageHeightAdjustment("refresh:" + reason);
        } else {
            logViewEvent("ReaderPageContainer", readerPageContainer,
                    "loadPersistedHeight -> none work=" + activeLayoutWorkId
                            + " reason=" + reason);
        }
    }

    private void maybePersistReaderPageHeight(int candidateHeight, String reason) {
        int safeHeight = Math.max(0, candidateHeight);
        if (!readerHasRenderableContent) {
            logViewEvent("ReaderPageContainer", readerPageContainer,
                    "persistReaderHeight skipped contentNotReady reason=" + reason);
            return;
        }
        int minimumHeight = computeMinimumPersistableReaderHeight();
        if (safeHeight > 0 && safeHeight < minimumHeight) {
            logViewEvent("ReaderPageContainer", readerPageContainer,
                    "persistReaderHeight skipped belowMinimum height=" + safeHeight
                            + " min=" + minimumHeight + " reason=" + reason);
            return;
        }
        if (safeHeight <= 0 || safeHeight <= persistedReaderPageHeight) {
            return;
        }
        if (readerPageHeightFixed && persistedReaderPageHeight > 0) {
            logViewEvent("ReaderPageContainer", readerPageContainer,
                    "persistReaderHeight skipped fixedHeight reason=" + reason);
            return;
        }
        persistedReaderPageHeight = safeHeight;
        logViewEvent("ReaderPageContainer", readerPageContainer,
                "persistReaderHeight -> " + safeHeight + " work=" + activeLayoutWorkId
                        + " reason=" + reason);
        enforceReaderPageHeight("persist:" + reason);
        if (uiLayoutDao != null) {
            uiLayoutDao.saveReaderPageHeight(null, safeHeight);
            if (!TextUtils.isEmpty(activeLayoutWorkId)) {
                uiLayoutDao.saveReaderPageHeight(activeLayoutWorkId, safeHeight);
            }
        }
        maybeApplyReaderPageHeightAdjustment("persist:" + reason);
    }

    private void markReaderPageHeightAdjustmentApplied() {
        if (!readerPageHeightAdjustmentApplied) {
            readerPageHeightAdjustmentApplied = true;
            if (readerPrefs != null) {
                readerPrefs.edit().putBoolean(PREF_KEY_PAGE_HEIGHT_ADJUSTMENT_APPLIED, true).apply();
            }
        }
        readerPageHeightFixed = true;
        readerPageHeightAdjustmentRetryScheduled = false;
    }

    private void maybeApplyReaderPageHeightAdjustment(String reason) {
        if (readerView == null) {
            return;
        }
        if (persistedReaderPageHeight <= 0) {
            return;
        }
        if (readerPageHeightAdjustmentApplied) {
            readerPageHeightFixed = true;
            enforceReaderPageHeight("adjustment:alreadyApplied:" + reason);
            return;
        }
        int lineHeight = Math.max(0, readerView.getLineHeight());
        if (lineHeight <= 0) {
            scheduleReaderPageHeightAdjustmentRetry(reason + ":lineHeightUnavailable");
            return;
        }
        int lineDelta = READER_PAGE_HEIGHT_ADJUSTMENT_LINES;
        if (lineDelta == 0) {
            markReaderPageHeightAdjustmentApplied();
            enforceReaderPageHeight("adjustment:none:" + reason);
            return;
        }
        int totalAdjustment = lineHeight * Math.abs(lineDelta);
        if (totalAdjustment <= 0) {
            markReaderPageHeightAdjustmentApplied();
            enforceReaderPageHeight("adjustment:zero:" + reason);
            return;
        }
        if (lineDelta > 0) {
            int newHeight = persistedReaderPageHeight - totalAdjustment;
            int minimumHeight = lineHeight;
            if (readerPageContainer != null) {
                int containerPadding = readerPageContainer.getPaddingTop()
                        + readerPageContainer.getPaddingBottom();
                minimumHeight = Math.max(minimumHeight, containerPadding + lineHeight);
            }
            int minimumPersistable = computeMinimumPersistableReaderHeight();
            if (newHeight < minimumHeight) {
                newHeight = minimumHeight;
            }
            if (newHeight < minimumPersistable) {
                newHeight = minimumPersistable;
            }
            if (newHeight == persistedReaderPageHeight) {
                markReaderPageHeightAdjustmentApplied();
                enforceReaderPageHeight("adjustment:reductionNoChange:" + reason);
                return;
            }
            boolean updated = overrideReaderPageHeight(newHeight, "adjustment:reduce:" + reason);
            markReaderPageHeightAdjustmentApplied();
            if (updated) {
                enforceReaderPageHeight("adjustment:reductionApplied:" + reason);
            } else {
                enforceReaderPageHeight("adjustment:reductionNoUpdate:" + reason);
            }
            return;
        }
        int newHeight = persistedReaderPageHeight + totalAdjustment;
        boolean updated = overrideReaderPageHeight(newHeight, "adjustment:increase:" + reason);
        markReaderPageHeightAdjustmentApplied();
        if (updated) {
            enforceReaderPageHeight("adjustment:increaseApplied:" + reason);
        } else {
            enforceReaderPageHeight("adjustment:increaseNoChange:" + reason);
        }
    }

    private void scheduleReaderPageHeightAdjustmentRetry(String reason) {
        if (readerView == null || readerPageHeightAdjustmentRetryScheduled
                || readerPageHeightAdjustmentApplied) {
            return;
        }
        readerPageHeightAdjustmentRetryScheduled = true;
        readerView.post(() -> {
            readerPageHeightAdjustmentRetryScheduled = false;
            maybeApplyReaderPageHeightAdjustment("retry:" + reason);
        });
        logViewEvent("ReaderPageContainer", readerPageContainer,
                "scheduleHeightAdjustmentRetry reason=" + reason);
    }

    private int computeMinimumPersistableReaderHeight() {
        int lineHeight = 0;
        if (readerView != null) {
            lineHeight = readerView.getLineHeight();
        }
        if (lineHeight <= 0) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            lineHeight = Math.round(metrics.scaledDensity * 24f);
        }
        int minContent = Math.max(0, lineHeight * 4);
        int padding = readerPageContainer == null
                ? 0
                : readerPageContainer.getPaddingTop() + readerPageContainer.getPaddingBottom();
        int minimum = minContent + padding;
        int absoluteMinimum = Math.round(getResources().getDisplayMetrics().density * 160f);
        if (minimum < absoluteMinimum) {
            minimum = absoluteMinimum;
        }
        return Math.max(0, minimum);
    }

    private void clearPersistedReaderPageHeight(String reason) {
        boolean hadPersistedHeight = persistedReaderPageHeight > 0;
        persistedReaderPageHeight = 0;
        readerPageHeightFixed = false;
        if (readerPageHeightAdjustmentApplied) {
            readerPageHeightAdjustmentApplied = false;
            if (readerPrefs != null) {
                readerPrefs.edit().putBoolean(PREF_KEY_PAGE_HEIGHT_ADJUSTMENT_APPLIED, false).apply();
            }
        }
        if (uiLayoutDao != null) {
            uiLayoutDao.saveReaderPageHeight(null, 0);
            if (!TextUtils.isEmpty(activeLayoutWorkId)) {
                uiLayoutDao.saveReaderPageHeight(activeLayoutWorkId, 0);
            }
        }
        logViewEvent("ReaderPageContainer", readerPageContainer,
                "clearPersistedHeight reason=" + reason + " hadValue=" + hadPersistedHeight);
        enforceReaderPageHeight("clear:" + reason);
    }

    private void enforceReaderPageHeight(String reason) {
        if (persistedReaderPageHeight <= 0) {
            return;
        }
        if (readerPageContainer != null) {
            ViewGroup.LayoutParams params = readerPageContainer.getLayoutParams();
            if (readerPageHeightFixed && params != null
                    && params.height != persistedReaderPageHeight) {
                params.height = persistedReaderPageHeight;
                readerPageContainer.setLayoutParams(params);
            } else if (!readerPageHeightFixed && params != null
                    && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                readerPageContainer.setLayoutParams(params);
            }
            readerPageContainer.setMinimumHeight(persistedReaderPageHeight);
            readerPageContainer.requestLayout();
            logViewEvent("ReaderPageContainer", readerPageContainer,
                    "enforceMinHeight -> " + persistedReaderPageHeight + " reason=" + reason);
        } else {
            logViewEvent("ReaderPageContainer", null,
                    "enforceMinHeight skipped reason=" + reason);
        }
        if (readerView != null) {
            int targetContentHeight = persistedReaderPageHeight;
            if (readerPageContainer != null) {
                int padding = readerPageContainer.getPaddingTop()
                        + readerPageContainer.getPaddingBottom();
                targetContentHeight = Math.max(0, persistedReaderPageHeight - padding);
            }
            if (targetContentHeight > 0) {
                readerView.setMinHeight(targetContentHeight);
                if (readerPageHeightFixed) {
                    readerView.setMaxHeight(targetContentHeight);
                    ViewGroup.LayoutParams readerParams = readerView.getLayoutParams();
                    if (readerParams != null
                            && readerParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                        readerParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                        readerView.setLayoutParams(readerParams);
                    }
                } else {
                    readerView.setMaxHeight(Integer.MAX_VALUE);
                    ViewGroup.LayoutParams readerParams = readerView.getLayoutParams();
                    if (readerParams != null
                            && readerParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        readerParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        readerView.setLayoutParams(readerParams);
                    }
                }
                readerView.requestLayout();
                logViewEvent("ReaderView", readerView,
                        "enforceMinHeight -> " + targetContentHeight + " reason=" + reason);
            } else {
                logViewEvent("ReaderView", readerView,
                        "enforceMinHeight skipped contentHeight<=0 reason=" + reason);
            }
        } else {
            logViewEvent("ReaderView", null,
                    "enforceMinHeight skipped reason=" + reason);
        }
    }

    private void setVisibilityWithLogging(View view, String component, int visibility) {
        if (view == null) {
            logViewEvent(component, null,
                    "setVisibility(" + visibilityToString(visibility) + ") skipped");
            return;
        }
        if (view.getVisibility() != visibility) {
            view.setVisibility(visibility);
            logViewEvent(component, view,
                    "setVisibility -> " + visibilityToString(visibility));
        } else {
            logViewEvent(component, view,
                    "setVisibility no-op -> " + visibilityToString(visibility));
        }
    }

    private void setEnabledWithLogging(View view, String component, boolean enabled) {
        if (view == null) {
            logViewEvent(component, null, "setEnabled(" + enabled + ") skipped");
            return;
        }
        if (view.isEnabled() != enabled) {
            view.setEnabled(enabled);
            logViewEvent(component, view, "setEnabled -> " + enabled);
        } else {
            logViewEvent(component, view, "setEnabled no-op -> " + enabled);
        }
    }

    private void setAlphaWithLogging(View view, String component, float alpha) {
        if (view == null) {
            logViewEvent(component, null, "setAlpha(" + alpha + ") skipped");
            return;
        }
        if (Math.abs(view.getAlpha() - alpha) > 0.001f) {
            view.setAlpha(alpha);
            logViewEvent(component, view, "setAlpha -> " + alpha);
        } else {
            logViewEvent(component, view, "setAlpha no-op -> " + alpha);
        }
    }

    private void setTextWithLogging(TextView view, String component, CharSequence text) {
        if (view == null) {
            logViewEvent(component, null, "setText(" + text + ") skipped");
            return;
        }
        view.setText(text);
        logViewEvent(component, view, "setText -> " + text);
    }

    private String visibilityToString(int visibility) {
        if (visibility == View.VISIBLE) {
            return "VISIBLE";
        } else if (visibility == View.INVISIBLE) {
            return "INVISIBLE";
        } else if (visibility == View.GONE) {
            return "GONE";
        }
        return String.valueOf(visibility);
    }

    private void logViewEvent(String component, View view, String action) {
        StringBuilder sb = new StringBuilder();
        sb.append(component).append(": ").append(action);
        if (view != null) {
            sb.append(" bounds=")
                    .append('[').append(view.getLeft()).append(',').append(view.getTop())
                    .append(" -> ").append(view.getRight()).append(',').append(view.getBottom())
                    .append(']');
            sb.append(" size=").append(view.getWidth()).append('x').append(view.getHeight());
            boolean attached = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                attached = view.isAttachedToWindow();
            }
            if (attached) {
                int[] location = new int[2];
                try {
                    view.getLocationOnScreen(location);
                    sb.append(" screen=")
                            .append('[').append(location[0]).append(',').append(location[1]).append(']');
                } catch (IllegalArgumentException e) {
                    sb.append(" screen=[unavailable]");
                }
            } else {
                sb.append(" attached=false");
            }
        } else {
            sb.append(" view=null");
        }
        sb.append('\n').append(Log.getStackTraceString(new Throwable("trace")));
        Log.d(LAYOUT_LOG_TAG, sb.toString());
    }

    private void runWhenReaderViewportReady(Runnable action) {
        if (action == null) {
            return;
        }
        if (readerScrollView == null || readerView == null) {
            action.run();
            return;
        }
        if (readerViewportReady && readerScrollView.getHeight() > 0 && readerView.getWidth() > 0) {
            updateReaderBottomInset();
            action.run();
            return;
        }
        pendingViewportReadyActions.add(action);
        if (awaitingViewportMeasurement) {
            return;
        }
        awaitingViewportMeasurement = true;
        viewportReadyListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (readerScrollView == null || readerView == null) {
                    return true;
                }
                if (readerScrollView.getHeight() <= 0 || readerView.getWidth() <= 0) {
                    return true;
                }
                removeViewportReadyListener();
                readerViewportReady = true;
                updateReaderBottomInset();
                dispatchReaderViewportChanged();
                flushPendingViewportReadyActions();
                return true;
            }
        };
        readerScrollView.getViewTreeObserver().addOnPreDrawListener(viewportReadyListener);
    }

    private void removeViewportReadyListener() {
        if (viewportReadyListener != null && readerScrollView != null) {
            ViewTreeObserver observer = readerScrollView.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(viewportReadyListener);
            }
        }
        viewportReadyListener = null;
        awaitingViewportMeasurement = false;
    }

    private void flushPendingViewportReadyActions() {
        if (pendingViewportReadyActions.isEmpty() || flushingViewportActions) {
            return;
        }
        flushingViewportActions = true;
        try {
            List<Runnable> callbacks = new ArrayList<>(pendingViewportReadyActions);
            pendingViewportReadyActions.clear();
            for (Runnable callback : callbacks) {
                if (callback != null) {
                    callback.run();
                }
            }
        } finally {
            flushingViewportActions = false;
        }
    }

    private void scheduleReaderViewportDispatch() {
        if (readerScrollView == null || readerViewportDispatchScheduled) {
            return;
        }
        readerViewportDispatchScheduled = true;
        readerScrollView.post(() -> {
            readerViewportDispatchScheduled = false;
            dispatchReaderViewportChanged();
        });
    }

    private void dispatchReaderViewportChanged() {
        if (readerView == null || readerScrollView == null) {
            return;
        }
        if (overlayInsetRetryScheduled) {
            scheduleReaderViewportDispatch();
            return;
        }
        int scrollPaddingTop = readerScrollView.getPaddingTop();
        int scrollPaddingBottom = readerScrollView.getPaddingBottom();
        int scrollHeight = readerScrollView.getHeight();
        if (scrollHeight <= 0) {
            scheduleReaderViewportDispatch();
            return;
        }
        int height = scrollHeight - scrollPaddingTop - scrollPaddingBottom - readerViewportBottomInset;
        if (height <= 0) {
            scheduleReaderViewportDispatch();
            return;
        }
        if (readerPageHeightFixed && persistedReaderPageHeight > 0
                && height > persistedReaderPageHeight) {
            height = persistedReaderPageHeight;
        }
        if (readerView != null) {
            int readerHeight = readerView.getHeight();
            if (readerHeight > 0 && height > readerHeight) {
                CharSequence currentText = readerView.getText();
                boolean hasRenderedContent = currentText != null && currentText.length() > 0;
                if (hasRenderedContent) {
                    height = readerHeight;
                }
            }
        }
        if (!readerViewportReady && awaitingViewportMeasurement) {
            readerViewportReady = true;
            removeViewportReadyListener();
            flushPendingViewportReadyActions();
        }
        if (height == lastDispatchedViewportHeight) {
            return;
        }
        lastDispatchedViewportHeight = height;
        Log.d(LAYOUT_LOG_TAG, "dispatchReaderViewportChanged: scrollHeight=" + scrollHeight
                + " paddingTop=" + scrollPaddingTop + " paddingBottom=" + scrollPaddingBottom
                + " bottomInset=" + readerViewportBottomInset + " -> viewportHeight=" + height);
        readerView.setViewportHeight(height);
    }

    private void resetReaderScrollOffset() {
        if (readerScrollView != null) {
            readerScrollView.scrollTo(0, 0);
        }
    }

    private void updatePendingSpeechChar(int charIndex) {
        pendingSpeechChar = charIndex;
        if (!restoringReadingState) {
            schedulePersistReadingState();
        }
    }

    private void markLastModeVoice() {
        if (!ReadingState.MODE_VOICE.equals(pendingLastMode)) {
            pendingLastMode = ReadingState.MODE_VOICE;
            if (!restoringReadingState) {
                schedulePersistReadingState();
            }
        }
    }

    private void markLastModeVisual() {
        if (!ReadingState.MODE_VISUAL.equals(pendingLastMode)) {
            pendingLastMode = ReadingState.MODE_VISUAL;
            if (!restoringReadingState) {
                schedulePersistReadingState();
            }
        }
    }

    private void applyWork(WorkInfo work, boolean fromMenu) {
        if (work == null) {
            updateWorkMenuDisplay();
            return;
        }
        ensureDefaultWork();
        if (!fromMenu && work == currentWork) {
            reloadReaderForCurrentSelection();
            return;
        }
        if (work == currentWork) {
            updateWorkMenuDisplay();
            return;
        }
        persistReadingStateNow();
        stopSpeech();
        if (readingStateDao != null) {
            currentReadingState = readingStateDao.getState(currentLanguagePair, work.id);
        } else {
            currentReadingState = null;
        }
        currentWork = work;
        reloadReaderForCurrentSelection();
    }

    private void forceShowMenuIcons(PopupMenu menu) {
        try {
            Field popupField = PopupMenu.class.getDeclaredField("mPopup");
            popupField.setAccessible(true);
            Object helper = popupField.get(menu);
            if (helper != null) {
                Method setForceShowIcon = helper.getClass()
                        .getDeclaredMethod("setForceShowIcon", boolean.class);
                setForceShowIcon.setAccessible(true);
                setForceShowIcon.invoke(helper, true);
            }
        } catch (Exception ignored) {
        }
    }

    private void setOverflowMenuIconsVisible(Menu menu) {
        if (menu == null) return;
        try {
            Method setOptionalIconsVisible = menu.getClass()
                    .getDeclaredMethod("setOptionalIconsVisible", boolean.class);
            setOptionalIconsVisible.setAccessible(true);
            setOptionalIconsVisible.invoke(menu, true);
        } catch (Exception ignored) {
        }
    }

    private void applyLanguagePair(String languagePair) {
        if (languagePair == null) {
            updateLanguagePairDisplay();
            return;
        }
        ensureDefaultWork();
        if (languagePairInitialized && languagePair.equals(currentLanguagePair)) {
            updateLanguagePairDisplay();
            reloadReaderForCurrentSelection();
            return;
        }
        languagePairInitialized = true;
        stopSpeech();
        currentLanguagePair = languagePair;
        if (readingStateDao != null && currentWork != null) {
            currentReadingState = readingStateDao.getState(currentLanguagePair, currentWork.id);
        } else {
            currentReadingState = null;
        }
        reloadReaderForCurrentSelection();
        updateLanguagePairDisplay();
    }

    private void updateLanguagePairDisplay() {
        String displayName = getLanguagePairDisplayName(currentLanguagePair);
        if (displayName == null || displayName.isEmpty()) {
            String unset = getString(R.string.language_pair_button_unset);
            setLanguagePairText(unset, unset);
        } else {
            setLanguagePairText(
                    getString(R.string.language_pair_button_format, displayName),
                    displayName);
        }
    }

    private void setLanguagePairText(String menuTitle, String subtitle) {
        if (languagePairMenuItem != null) {
            languagePairMenuItem.setTitle(menuTitle);
            languagePairMenuItem.setTitleCondensed(subtitle);
            View actionView = languagePairMenuItem.getActionView();
            if (actionView != null) {
                actionView.setContentDescription(menuTitle);
            }
        }
        updateLanguagePairIcon();
    }

    private String getLanguagePairDisplayName(String languagePair) {
        if (languagePair == null) return "";
        if (LANGUAGE_PAIR_TT_RU.equals(languagePair)) {
            return getString(R.string.language_pair_tt_ru);
        }
        return languagePair;
    }

    private void navigateToCharIndex(int charIndex, int attempt) {
        if (readerView == null) return;
        TokenSpan span = readerView.findSpanForCharIndex(charIndex);
        if (span == null) {
            if (attempt < 5) {
                readerView.postDelayed(() -> navigateToCharIndex(charIndex, attempt + 1), 50);
            }
            return;
        }
        readerView.ensureExposureLogged(span);
        scrollToSpan(span, attempt);
        readerView.postDelayed(() -> readerView.showTokenInfo(span), 150);
    }

    private void scrollToSpan(TokenSpan span, int attempt) {
        if (readerView == null || span == null) return;
        readerView.scrollToGlobalChar(span.getStartIndex());
    }

    private void dismissTokenSheet(boolean restoreFocus) {
        android.app.FragmentManager manager = getFragmentManager();
        android.app.Fragment existing = manager != null
                ? manager.findFragmentByTag("token-info")
                : null;
        if (existing instanceof TokenInfoBottomSheet) {
            ((TokenInfoBottomSheet) existing).dismissAllowingStateLoss();
        }
        if (restoreFocus && readerView != null) {
            readerView.post(() -> readerView.requestFocus());
        }
    }

    private void showTokenSheet(TokenSpan span, List<String> ruLemmas) {
        if (span == null || span.token == null || span.token.analysis == null) return;
        dismissTokenSheet(false);
        List<String> combined = ruLemmas == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(ruLemmas);
        if (span.token != null && span.token.translations != null) {
            for (String translation : span.token.translations) {
                if (!TextUtils.isEmpty(translation) && !combined.contains(translation)) {
                    combined.add(translation);
                }
            }
        }
        String ruCsv = combined.isEmpty()? "—" : String.join(", ", combined);
        TokenInfoBottomSheet sheet = TokenInfoBottomSheet.newInstance(span.token.surface, span.token.analysis, ruCsv);
        sheet.setUsageStatsDao(usageStatsDao);
        String languagePair = currentLanguagePair != null ? currentLanguagePair : LANGUAGE_PAIR_TT_RU;
        WorkInfo work = getCurrentWork();
        String workId = work != null ? work.id : null;
        sheet.setUsageContext(languagePair, workId, span.getStartIndex());
        sheet.show(getFragmentManager(), "token-info");
    }

    private void toggleSpeech() {
        if (speechStoppingInProgress) {
            return;
        }
        if (isSpeaking) {
            pauseSpeech();
        } else {
            startSpeech();
        }
    }

    private void startSpeech() {
        finalizePendingDevicePauseEvent();
        if (!ttsReady || talgatVoice == null) return;
        dismissTokenSheet(true);
        exitPromptMode();
        updateSentenceRanges();
        if (sentenceRanges.isEmpty()) return;
        if (currentSentenceIndex < 0 || currentSentenceIndex >= sentenceRanges.size()) {
            currentSentenceIndex = 0;
        }
        int focusChar = resolveFocusCharIndex();
        if (focusChar >= 0) {
            adjustSentenceIndexForChar(focusChar);
        }
        speechSessionActive = true;
        awaitingResumeAfterDetail = false;
        shouldContinueSpeech = true;
        if (mediaSession != null) {
            mediaSession.setActive(true);
        }
        if (sentencePlayer != null && currentSentenceRequest != null) {
            try {
                markLastModeVoice();
                sentencePlayer.start();
                isSpeaking = true;
                updatePlaybackState(PlaybackState.STATE_PLAYING);
                updateSpeechButtons();
                startProgressUpdates();
                prefetchUpcomingSentences(currentSentenceIndex + 1);
                return;
            } catch (IllegalStateException ignored) {
                releaseSentencePlayer();
            }
        }
        markLastModeVoice();
        updateSpeechButtons();
        speakCurrentSentence();
    }

    private void pauseSpeech() {
        if (speechSessionActive || isSpeaking || shouldContinueSpeech) {
            speechSessionActive = true;
        }
        shouldContinueSpeech = false;
        isSpeaking = false;
        stopProgressUpdates();
        preparePromptSelection();
        if (sentencePlayer != null) {
            try {
                if (sentencePlayer.isPlaying()) {
                    sentencePlayer.pause();
                }
            } catch (IllegalStateException ignored) {
                releaseSentencePlayer();
            }
        }
        updatePlaybackState(PlaybackState.STATE_PAUSED);
        updateSpeechButtons();
    }

    private void stopSpeech() {
        Runnable stopAction = this::requestSpeechStop;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(stopAction);
            return;
        }
        stopAction.run();
    }

    private void requestSpeechStop() {
        if (!beginSpeechStop()) {
            return;
        }
        speechStopHandler.post(this::stopSpeechInternal);
    }

    private boolean beginSpeechStop() {
        if (speechStoppingInProgress) {
            return false;
        }
        pauseSpeech();
        awaitingResumeAfterDetail = false;
        speechStoppingInProgress = true;
        return true;
    }

    private void stopSpeechInternal() {
        try {
            int resumeChar = resolveFocusCharIndex();
            shouldContinueSpeech = false;
            isSpeaking = false;
            speechSessionActive = false;
            awaitingResumeAfterDetail = false;
            stopProgressUpdates();
            exitPromptMode();
            clearSkipBackBehavior();
            clearPendingDevicePauseEvent();
            if (textToSpeech != null) {
                textToSpeech.stop();
            }
            stopDetailPlayback();
            cancelPendingDetailRequest();
            activeDetailRequest = null;
            detailAutoResume = false;
            releaseSentencePlayer();
            if (currentSentenceRequest != null) {
                currentSentenceRequest.deleteFile();
                currentSentenceRequest = null;
            }
            clearPreparedAudio();
            estimatedUtteranceDurationMs = 0L;
            if (readerView != null) {
                readerView.clearSpeechHighlights();
            }
            updateSentenceRanges();
            if (resumeChar >= 0) {
                currentCharIndex = resumeChar;
                updatePendingSpeechChar(resumeChar);
                adjustSentenceIndexForChar(resumeChar);
            } else {
                currentSentenceIndex = -1;
                currentSentenceStart = -1;
                currentSentenceEnd = -1;
                currentCharIndex = -1;
                updatePendingSpeechChar(-1);
            }
            markLastModeVisual();
            updatePlaybackState(PlaybackState.STATE_STOPPED);
        } finally {
            speechStoppingInProgress = false;
            updateSpeechButtons();
        }
    }

    private void speakCurrentSentence() {
        if (!shouldContinueSpeech || textToSpeech == null || talgatVoice == null) return;
        exitPromptMode();
        if (currentSentenceIndex < 0 || currentSentenceIndex >= sentenceRanges.size()) {
            stopSpeech();
            return;
        }
        ReaderView.SentenceRange sentence = sentenceRanges.get(currentSentenceIndex);
        if (sentence == null || sentence.length() == 0) {
            currentSentenceIndex++;
            speakCurrentSentence();
            return;
        }
        markLastModeVoice();
        currentSentenceStart = sentence.start;
        currentSentenceEnd = sentence.end;
        currentCharIndex = sentence.start;
        updatePendingSpeechChar(currentCharIndex);
        if (readerView != null) {
            readerView.highlightSentenceRange(sentence.start, sentence.end);
            readerView.highlightLetter(sentence.start);
        }
        scrollSentenceIntoView(sentence);

        if (currentSentenceRequest != null
                && currentSentenceRequest.sentenceIndex == currentSentenceIndex
                && sentencePlayer != null) {
            try {
                sentencePlayer.start();
                isSpeaking = true;
                updatePlaybackState(PlaybackState.STATE_PLAYING);
                updateSpeechButtons();
                startProgressUpdates();
                return;
            } catch (IllegalStateException ignored) {
                releaseSentencePlayer();
                currentSentenceRequest = null;
            }
        }

        SpeechRequest prepared = preparedSentenceRequests.remove(currentSentenceIndex);
        if (prepared != null) {
            playSentenceRequest(prepared);
            return;
        }

        pendingPlaybackSentenceIndex = currentSentenceIndex;
        prepareSentenceAudio(currentSentenceIndex, sentence);
        prefetchUpcomingSentences(currentSentenceIndex + 1);
    }

    private void scrollSentenceIntoView(ReaderView.SentenceRange sentence) {
        if (sentence == null || readerView == null) return;
        readerView.post(() -> readerView.scrollToGlobalChar(sentence.start));
    }

    private void startProgressUpdates() {
        speechProgressHandler.removeCallbacks(speechProgressRunnable);
        speechProgressHandler.post(speechProgressRunnable);
    }

    private void stopProgressUpdates() {
        speechProgressHandler.removeCallbacks(speechProgressRunnable);
    }

    private void clearSkipBackBehavior() {
        skipBackGoPrevious = false;
        skipBackRestartArmed = false;
    }

    private void handleSentencePlaybackComplete() {
        stopProgressUpdates();
        isSpeaking = false;
        clearSkipBackBehavior();
        if (readerView != null) {
            readerView.highlightLetter(-1);
        }
        SpeechRequest finished = currentSentenceRequest;
        if (finished != null) {
            finished.deleteFile();
        }
        currentSentenceRequest = null;
        releaseSentencePlayer();
        if (shouldContinueSpeech) {
            currentSentenceIndex++;
            if (currentSentenceIndex >= sentenceRanges.size()) {
                stopSpeech();
            } else {
                speakCurrentSentence();
            }
        } else {
            updatePlaybackState(PlaybackState.STATE_PAUSED);
            updateSpeechButtons();
        }
    }

    private void handleSentencePlaybackError() {
        stopProgressUpdates();
        isSpeaking = false;
        clearSkipBackBehavior();
        releaseSentencePlayer();
        SpeechRequest failed = currentSentenceRequest;
        if (failed != null) {
            failed.deleteFile();
            currentSentenceRequest = null;
        }
        updatePlaybackState(PlaybackState.STATE_PAUSED);
        updateSpeechButtons();
    }

    private long estimateSentenceDurationMs(ReaderView.SentenceRange sentence) {
        if (sentence == null) return 0L;
        int length = Math.max(1, sentence.length());
        float durationSeconds = length / (BASE_CHARS_PER_SECOND * Math.max(0.1f, DEFAULT_SPEECH_RATE));
        return (long) Math.max(500, durationSeconds * 1000f);
    }

    private void updateSentenceRanges() {
        if (readerView == null) return;
        sentenceRanges.clear();
        sentenceRanges.addAll(readerView.getSentenceRanges());
    }

    private void initTextToSpeech() {
        if (textToSpeech != null) {
            textToSpeech.shutdown();
        }
        stopDetailPlayback();
        clearPreparedAudio();
        releaseSentencePlayer();
        String engine = rhVoiceInstalled ? RHVOICE_PACKAGE : null;
        textToSpeech = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                textToSpeech.setOnUtteranceProgressListener(synthesisListener);
                locateTalgatVoice();
            } else {
                talgatVoice = null;
            }
            updateInstallButtonVisibility();
            updateSpeechButtons();
        }, engine);
    }

    private void locateTalgatVoice() {
        talgatVoice = null;
        if (textToSpeech == null) return;
        Set<Voice> voices = textToSpeech.getVoices();
        if (voices == null) return;
        for (Voice voice : voices) {
            if (voice == null || voice.getName() == null) continue;
            String name = voice.getName().toLowerCase(Locale.US);
            if (name.contains(TALGAT_NAME_KEYWORD)) {
                talgatVoice = voice;
                break;
            }
        }
        if (talgatVoice != null) {
            textToSpeech.setVoice(talgatVoice);
        }
    }

    private void handleSynthesisFinished(String utteranceId, boolean error) {
        if (utteranceId == null) return;
        SpeechRequest request = pendingRequests.remove(utteranceId);
        if (request == null) return;
        if (request.type == REQUEST_TYPE_SENTENCE) {
            pendingSentenceIndices.remove(request.sentenceIndex);
        } else {
            pendingDetailRequest = null;
        }
        if (error) {
            request.deleteFile();
            if (request.type == REQUEST_TYPE_SENTENCE && request.sentenceIndex == pendingPlaybackSentenceIndex) {
                pendingPlaybackSentenceIndex = -1;
            }
            if (request.type == REQUEST_TYPE_DETAIL) {
                awaitingResumeAfterDetail = true;
            }
            return;
        }
        request.durationMs = resolveAudioDuration(request.file);
        if (request.type == REQUEST_TYPE_SENTENCE) {
            if (pendingPlaybackSentenceIndex == request.sentenceIndex) {
                pendingPlaybackSentenceIndex = -1;
                if (shouldContinueSpeech) {
                    playSentenceRequest(request);
                } else {
                    preparedSentenceRequests.put(request.sentenceIndex, request);
                }
            } else {
                preparedSentenceRequests.put(request.sentenceIndex, request);
            }
            prefetchUpcomingSentences(request.sentenceIndex + 1);
        } else {
            playDetailRequest(request);
        }
    }

    private void prepareSentenceAudio(int index, ReaderView.SentenceRange sentence) {
        if (sentence == null) return;
        if (!ttsReady || textToSpeech == null || talgatVoice == null) return;
        if (pendingSentenceIndices.contains(index)) return;
        if (preparedSentenceRequests.containsKey(index)) return;
        try {
            File file = File.createTempFile("sentence_" + index + "_", ".wav", getCacheDir());
            String utteranceId = "sentence_" + (utteranceSequence++);
            SpeechRequest request = new SpeechRequest(REQUEST_TYPE_SENTENCE, index, sentence, null, file, utteranceId, false);
            pendingRequests.put(utteranceId, request);
            pendingSentenceIndices.add(index);
            textToSpeech.setVoice(talgatVoice);
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
            int result = textToSpeech.synthesizeToFile(sentence.text, params, file, utteranceId);
            if (result != TextToSpeech.SUCCESS) {
                pendingRequests.remove(utteranceId);
                pendingSentenceIndices.remove(index);
                request.deleteFile();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to create audio file for sentence " + index, e);
        }
    }

    private void prefetchUpcomingSentences(int fromIndex) {
        if (sentenceRanges.isEmpty()) return;
        for (int offset = 0; offset < PREFETCH_SENTENCE_COUNT; offset++) {
            int index = fromIndex + offset;
            if (index < 0 || index >= sentenceRanges.size()) {
                break;
            }
            if (currentSentenceRequest != null && currentSentenceRequest.sentenceIndex == index) {
                continue;
            }
            if (pendingSentenceIndices.contains(index)) {
                continue;
            }
            if (preparedSentenceRequests.containsKey(index)) {
                continue;
            }
            ReaderView.SentenceRange sentence = sentenceRanges.get(index);
            if (sentence != null && sentence.length() > 0) {
                prepareSentenceAudio(index, sentence);
            }
        }
    }

    private void playSentenceRequest(SpeechRequest request) {
        currentSentenceRequest = request;
        estimatedUtteranceDurationMs = request.durationMs > 0
                ? request.durationMs
                : estimateSentenceDurationMs(request.sentenceRange);
        currentLetterIntervalMs = computeLetterInterval(request.sentenceRange, estimatedUtteranceDurationMs);
        setupSentencePlayer(request);
        prefetchUpcomingSentences(request.sentenceIndex + 1);
    }

    private void setupSentencePlayer(SpeechRequest request) {
        releaseSentencePlayer();
        sentencePlayer = new MediaPlayer();
        try {
            sentencePlayer.setDataSource(request.file.getAbsolutePath());
            sentencePlayer.setOnPreparedListener(mp -> {
                if (sentencePlayer != mp || currentSentenceRequest != request || !shouldContinueSpeech) {
                    if (currentSentenceRequest == request) {
                        currentSentenceRequest = null;
                    }
                    request.deleteFile();
                    try {
                        mp.release();
                    } catch (IllegalStateException ignored) {
                    }
                    if (sentencePlayer == mp) {
                        sentencePlayer = null;
                    }
                    updateSpeechButtons();
                    return;
                }
                long duration = request.durationMs > 0 ? request.durationMs : mp.getDuration();
                if (duration <= 0) {
                    duration = estimateSentenceDurationMs(request.sentenceRange);
                }
                estimatedUtteranceDurationMs = duration;
                currentLetterIntervalMs = computeLetterInterval(request.sentenceRange, duration);
                mp.start();
                isSpeaking = true;
                updatePlaybackState(PlaybackState.STATE_PLAYING);
                updateSpeechButtons();
                startProgressUpdates();
            });
            sentencePlayer.setOnCompletionListener(mp -> handleSentencePlaybackComplete());
            sentencePlayer.setOnErrorListener((mp, what, extra) -> {
                handleSentencePlaybackError();
                return true;
            });
            sentencePlayer.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Failed to prepare sentence audio", e);
            handleSentencePlaybackError();
        }
    }

    private long resolveAudioDuration(File file) {
        if (file == null) return 0L;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                return Long.parseLong(duration);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to resolve audio duration", e);
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return 0L;
    }

    private void updateLetterHighlightForElapsed(long elapsed, long duration) {
        if (currentSentenceRequest == null || currentSentenceRequest.sentenceRange == null) return;
        ReaderView.SentenceRange sentence = currentSentenceRequest.sentenceRange;
        int length = Math.max(1, sentence.length());
        long clampedDuration = Math.max(1L, duration);
        int offset = (int) Math.min(length - 1, (elapsed * length) / clampedDuration);
        if (elapsed >= clampedDuration) {
            offset = length - 1;
        }
        int highlightIndex = sentence.start + offset;
        currentCharIndex = highlightIndex;
        updatePendingSpeechChar(currentCharIndex);
        if (readerView != null) {
            readerView.highlightLetter(highlightIndex);
        }
    }

    private long computeLetterInterval(ReaderView.SentenceRange sentence, long durationMs) {
        if (sentence == null || durationMs <= 0) {
            return 0L;
        }
        int length = Math.max(1, sentence.length());
        return Math.max(10L, durationMs / length);
    }

    private void releaseSentencePlayer() {
        if (sentencePlayer == null) return;
        try {
            sentencePlayer.stop();
        } catch (IllegalStateException ignored) {
        }
        sentencePlayer.release();
        sentencePlayer = null;
    }

    private void clearPreparedAudio() {
        for (SpeechRequest request : preparedSentenceRequests.values()) {
            if (request != null) {
                request.deleteFile();
            }
        }
        preparedSentenceRequests.clear();
        for (SpeechRequest request : pendingRequests.values()) {
            if (request != null) {
                request.deleteFile();
            }
        }
        pendingRequests.clear();
        pendingSentenceIndices.clear();
        pendingPlaybackSentenceIndex = -1;
        if (pendingDetailRequest != null) {
            pendingDetailRequest.deleteFile();
            pendingDetailRequest = null;
        }
        activeDetailRequest = null;
    }

    private void initMediaSession() {
        mediaSession = new MediaSession(this, "TalgatReaderSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                if (mediaButtonIntent == null) return super.onMediaButtonEvent(mediaButtonIntent);
                KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int keyCode = event.getKeyCode();
                    if (isPlayPauseKey(keyCode)) {
                        KeyEvent copy = new KeyEvent(event);
                        speechProgressHandler.post(() -> performMediaButtonAction(copy));
                        return true;
                    } else if (isSkipForwardKey(keyCode)) {
                        speechProgressHandler.post(() -> handleMediaSkip(true));
                        return true;
                    } else if (isSkipBackwardKey(keyCode)) {
                        speechProgressHandler.post(() -> handleMediaSkip(false));
                        return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }

            @Override public void onSkipToNext() {
                speechProgressHandler.post(() -> handleMediaSkip(true));
            }

            @Override public void onSkipToPrevious() {
                speechProgressHandler.post(() -> handleMediaSkip(false));
            }
        });
        updatePlaybackState(PlaybackState.STATE_STOPPED);
        mediaSession.setActive(false);
    }

    private void performMediaButtonAction() {
        performMediaButtonAction(null);
    }

    private void performMediaButtonAction(KeyEvent keyEvent) {
        DeviceIdentity deviceIdentity = DeviceIdentity.from(keyEvent);
        if (cancelDetailSpeech()) {
            return;
        }
        if (awaitingResumeAfterDetail) {
            resumeSentenceAfterDetail();
            return;
        }
        if (isSpeaking) {
            if (deviceIdentity.shouldTrack()) {
                capturePauseFromDevice(deviceIdentity);
            } else {
                clearPendingDevicePauseEvent();
            }
            pauseSpeechFromHeadset();
        } else {
            finalizePendingDevicePauseEvent();
            startSpeech();
        }
    }

    private void handleMediaSkip(boolean forward) {
        boolean detailActive = detailPlaybackActive()
                || activeDetailRequest != null
                || pendingDetailRequest != null
                || awaitingResumeAfterDetail;
        if (detailActive && !isSpeaking) {
            handleDetailSkip(forward);
            return;
        }
        if (isSpeaking) {
            handleSkipWhileSpeaking(forward);
        } else {
            handlePromptSkip(forward);
        }
    }

    private void handleDetailSkip(boolean forward) {
        if (readerView == null) {
            return;
        }
        List<TokenSpan> spans = readerView.getTokenSpans();
        if (spans.isEmpty()) {
            return;
        }
        TokenSpan current = resolveCurrentDetailSpan();
        if (current == null) {
            return;
        }
        int index = spans.indexOf(current);
        if (index < 0 && current.getStartIndex() >= 0) {
            index = findClosestTokenIndex(spans, current.getStartIndex());
        }
        if (index < 0) {
            return;
        }
        int targetIndex = forward ? findNextTokenIndex(spans, index) : findPreviousTokenIndex(spans, index);
        if (targetIndex < 0 || targetIndex >= spans.size()) {
            return;
        }
        TokenSpan target = spans.get(targetIndex);
        if (target == null || target.token == null) {
            return;
        }
        readerView.ensureExposureLogged(target);
        List<String> translations = readerView.getTranslations(target);
        boolean resumeAfter = resolveDetailResumePreference();
        speakTokenDetails(target, translations, resumeAfter);
        showTokenSheet(target, translations);
    }

    private TokenSpan resolveCurrentDetailSpan() {
        if (activeDetailRequest != null && activeDetailRequest.tokenSpan != null) {
            return activeDetailRequest.tokenSpan;
        }
        if (pendingDetailRequest != null && pendingDetailRequest.tokenSpan != null) {
            return pendingDetailRequest.tokenSpan;
        }
        return lastDetailSpan;
    }

    private boolean resolveDetailResumePreference() {
        if (activeDetailRequest != null) {
            return activeDetailRequest.resumeAfterPlayback;
        }
        if (pendingDetailRequest != null) {
            return pendingDetailRequest.resumeAfterPlayback;
        }
        return lastDetailResumeAfter;
    }

    private void handleSkipWhileSpeaking(boolean forward) {
        if (sentenceRanges.isEmpty()) {
            return;
        }
        if (forward) {
            skipBackRestartArmed = false;
            if (currentSentenceIndex < sentenceRanges.size() - 1) {
                skipToSentence(currentSentenceIndex + 1);
            }
            return;
        }
        if (skipBackGoPrevious) {
            if (currentSentenceIndex > 0) {
                skipBackRestartArmed = false;
                skipToSentence(currentSentenceIndex - 1);
            } else {
                restartCurrentSentence();
            }
            return;
        }
        if (skipBackRestartArmed) {
            if (currentSentenceIndex > 0) {
                skipBackGoPrevious = true;
                skipBackRestartArmed = false;
                skipToSentence(currentSentenceIndex - 1);
            } else {
                restartCurrentSentence();
            }
            return;
        }
        restartCurrentSentence();
        skipBackRestartArmed = true;
        skipBackGoPrevious = false;
    }

    private void restartCurrentSentence() {
        if (currentSentenceIndex < 0 || currentSentenceIndex >= sentenceRanges.size()) {
            return;
        }
        exitPromptMode();
        if (sentencePlayer != null) {
            try {
                sentencePlayer.seekTo(0);
                markLastModeVoice();
                sentencePlayer.start();
                isSpeaking = true;
                shouldContinueSpeech = true;
                long duration = estimatedUtteranceDurationMs > 0
                        ? estimatedUtteranceDurationMs
                        : sentencePlayer.getDuration();
                updateLetterHighlightForElapsed(0, Math.max(1L, duration));
                if (readerView != null && currentSentenceStart >= 0) {
                    readerView.highlightLetter(currentSentenceStart);
                }
                currentCharIndex = currentSentenceStart;
                updatePendingSpeechChar(currentCharIndex);
                startProgressUpdates();
                return;
            } catch (IllegalStateException e) {
                releaseSentencePlayer();
            }
        }
        skipToSentence(currentSentenceIndex);
    }

    private void skipToSentence(int index) {
        if (sentenceRanges.isEmpty()) {
            return;
        }
        int clampedIndex = Math.max(0, Math.min(index, sentenceRanges.size() - 1));
        exitPromptMode();
        int previousIndex = currentSentenceIndex;
        discardCurrentSentencePlayback();
        currentSentenceIndex = clampedIndex;
        if (clampedIndex != previousIndex) {
            skipBackRestartArmed = false;
        }
        currentSentenceStart = -1;
        currentSentenceEnd = -1;
        currentCharIndex = -1;
        updatePendingSpeechChar(-1);
        shouldContinueSpeech = true;
        speakCurrentSentence();
    }

    private void discardCurrentSentencePlayback() {
        stopProgressUpdates();
        if (sentencePlayer != null) {
            try {
                sentencePlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            sentencePlayer.release();
            sentencePlayer = null;
        }
        if (currentSentenceRequest != null) {
            currentSentenceRequest.deleteFile();
            currentSentenceRequest = null;
        }
        isSpeaking = false;
        estimatedUtteranceDurationMs = 0L;
        currentLetterIntervalMs = 0L;
        pendingPlaybackSentenceIndex = -1;
    }

    private long getCurrentSentencePosition() {
        if (sentencePlayer == null) {
            return 0L;
        }
        try {
            return sentencePlayer.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0L;
        }
    }

    private void handlePromptSkip(boolean forward) {
        if (readerView == null) {
            return;
        }
        List<TokenSpan> spans = readerView.getTokenSpans();
        if (spans.isEmpty()) {
            return;
        }
        if (!promptModeActive) {
            int focusIndex = promptBaseCharIndex >= 0 ? promptBaseCharIndex : resolveFocusCharIndex();
            if (focusIndex < 0) {
                return;
            }
            int index = findClosestTokenIndex(spans, focusIndex);
            if (index < 0) {
                return;
            }
            promptTokenIndex = index;
            promptModeActive = true;
        } else {
            int nextIndex = forward
                    ? findNextTokenIndex(spans, promptTokenIndex)
                    : findPreviousTokenIndex(spans, promptTokenIndex);
            if (nextIndex < 0) {
                nextIndex = promptTokenIndex;
            }
            promptTokenIndex = nextIndex;
        }
        if (promptTokenIndex < 0 || promptTokenIndex >= spans.size()) {
            return;
        }
        TokenSpan span = spans.get(promptTokenIndex);
        if (span == null || span.token == null) {
            return;
        }
        showPromptForSpan(span, spans);
    }

    private int findClosestTokenIndex(List<TokenSpan> spans, int charIndex) {
        if (spans == null || spans.isEmpty()) {
            return -1;
        }
        int previousValid = -1;
        for (int i = 0; i < spans.size(); i++) {
            TokenSpan span = spans.get(i);
            if (span == null) {
                continue;
            }
            int start = span.getStartIndex();
            int end = span.getEndIndex();
            if (start < 0 || end <= start) {
                continue;
            }
            if (charIndex >= start && charIndex < end) {
                return i;
            }
            if (end <= charIndex) {
                previousValid = i;
                continue;
            }
            return previousValid >= 0 ? previousValid : i;
        }
        return previousValid >= 0 ? previousValid : spans.size() - 1;
    }

    private int findNextTokenIndex(List<TokenSpan> spans, int fromIndex) {
        if (spans == null || spans.isEmpty()) {
            return -1;
        }
        for (int i = Math.max(0, fromIndex + 1); i < spans.size(); i++) {
            TokenSpan span = spans.get(i);
            if (span != null && span.token != null && !TextUtils.isEmpty(span.token.surface)) {
                return i;
            }
        }
        return -1;
    }

    private int findPreviousTokenIndex(List<TokenSpan> spans, int fromIndex) {
        if (spans == null || spans.isEmpty()) {
            return -1;
        }
        for (int i = Math.min(fromIndex - 1, spans.size() - 1); i >= 0; i--) {
            TokenSpan span = spans.get(i);
            if (span != null && span.token != null && !TextUtils.isEmpty(span.token.surface)) {
                return i;
            }
        }
        return -1;
    }

    private void showPromptForSpan(TokenSpan span, List<TokenSpan> spans) {
        if (readerView == null || span == null || span.token == null) {
            return;
        }
        int restoreIndex = promptTokenIndex;
        int restoreBaseIndex = span.getStartIndex();
        readerView.showTokenInfo(span);
        promptModeActive = true;
        if (spans != null && !spans.isEmpty()) {
            if (restoreIndex >= 0 && restoreIndex < spans.size()) {
                promptTokenIndex = restoreIndex;
            } else {
                int located = spans.indexOf(span);
                if (located >= 0) {
                    promptTokenIndex = located;
                }
            }
            if (promptTokenIndex < 0 && restoreIndex >= 0) {
                promptTokenIndex = Math.min(spans.size() - 1, restoreIndex);
            }
        } else {
            promptTokenIndex = restoreIndex;
        }
        if (restoreBaseIndex >= 0) {
            promptBaseCharIndex = restoreBaseIndex;
        }
    }

    private void preparePromptSelection() {
        promptModeActive = false;
        promptTokenIndex = -1;
        promptBaseCharIndex = resolveFocusCharIndex();
    }

    private void exitPromptMode() {
        promptModeActive = false;
        promptTokenIndex = -1;
        promptBaseCharIndex = -1;
    }

    private void pauseSpeechFromHeadset() {
        pauseSpeech();
    }

    private void capturePauseFromDevice(DeviceIdentity deviceIdentity) {
        if (deviceStatsDao == null || deviceIdentity == null || !deviceIdentity.shouldTrack()) {
            return;
        }
        long pauseOffset = resolveCurrentPlaybackElapsedMs();
        int pauseCharIndex = currentCharIndex >= 0 ? currentCharIndex : resolveFocusCharIndex();
        long letterInterval = resolveCurrentLetterIntervalMs();
        String language = currentLanguagePair == null ? "" : currentLanguagePair;
        String workId = currentWork != null ? currentWork.id : "";
        pendingDevicePauseEvent = new PendingDevicePauseEvent(
                deviceIdentity,
                pauseOffset,
                letterInterval,
                pauseCharIndex,
                currentSentenceIndex,
                language,
                workId,
                System.currentTimeMillis());
        if (pauseCharIndex >= 0) {
            applyAverageShiftForDevice(deviceIdentity, pauseCharIndex, letterInterval);
        }
    }

    private void finalizePendingDevicePauseEvent() {
        if (pendingDevicePauseEvent == null || deviceStatsDao == null) {
            return;
        }
        PendingDevicePauseEvent pending = pendingDevicePauseEvent;
        pendingDevicePauseEvent = null;
        if (pending.device == null || !pending.device.shouldTrack()) {
            return;
        }
        int targetCharIndex = resolveFocusCharIndex();
        if (targetCharIndex < 0) {
            targetCharIndex = currentCharIndex;
        }
        if (targetCharIndex < 0) {
            return;
        }
        int pauseCharIndex = pending.pauseCharIndex >= 0 ? pending.pauseCharIndex : targetCharIndex;
        int charDelta = Math.max(0, pauseCharIndex - targetCharIndex);
        long letterInterval = pending.letterIntervalMs > 0 ? pending.letterIntervalMs : defaultLetterIntervalMs();
        long deltaMs = charDelta <= 0 ? 0L : Math.max(0L, charDelta * Math.max(1L, letterInterval));
        long targetOffset = Math.max(0L, pending.pauseOffsetMs - deltaMs);
        deviceStatsDao.recordPauseReaction(
                pending.device,
                pending.pauseOffsetMs,
                targetOffset,
                deltaMs,
                charDelta,
                pending.languagePair,
                pending.workId,
                pending.recordedAtMs);
    }

    private void clearPendingDevicePauseEvent() {
        pendingDevicePauseEvent = null;
    }

    private long resolveCurrentPlaybackElapsedMs() {
        if (sentencePlayer != null) {
            try {
                return Math.max(0L, sentencePlayer.getCurrentPosition());
            } catch (IllegalStateException ignored) {
            }
        }
        ReaderView.SentenceRange sentence = null;
        if (currentSentenceIndex >= 0 && currentSentenceIndex < sentenceRanges.size()) {
            sentence = sentenceRanges.get(currentSentenceIndex);
        }
        if (sentence == null && currentSentenceRequest != null) {
            sentence = currentSentenceRequest.sentenceRange;
        }
        if (sentence == null) {
            return 0L;
        }
        int baseIndex = sentence.start;
        int highlightIndex = currentCharIndex >= 0 ? currentCharIndex : baseIndex;
        int offsetChars = Math.max(0, highlightIndex - baseIndex);
        long letterInterval = resolveCurrentLetterIntervalMs();
        return Math.max(0L, offsetChars * Math.max(1L, letterInterval));
    }

    private long resolveCurrentLetterIntervalMs() {
        if (currentLetterIntervalMs > 0) {
            return currentLetterIntervalMs;
        }
        ReaderView.SentenceRange sentence = null;
        if (currentSentenceRequest != null && currentSentenceRequest.sentenceRange != null) {
            sentence = currentSentenceRequest.sentenceRange;
        } else if (currentSentenceIndex >= 0 && currentSentenceIndex < sentenceRanges.size()) {
            sentence = sentenceRanges.get(currentSentenceIndex);
        }
        if (sentence != null) {
            long duration = estimatedUtteranceDurationMs > 0
                    ? estimatedUtteranceDurationMs
                    : estimateSentenceDurationMs(sentence);
            return computeLetterInterval(sentence, duration);
        }
        return defaultLetterIntervalMs();
    }

    private long defaultLetterIntervalMs() {
        double charsPerSecond = BASE_CHARS_PER_SECOND * Math.max(0.1f, DEFAULT_SPEECH_RATE);
        if (charsPerSecond <= 0.01d) {
            return 50L;
        }
        return Math.max(10L, Math.round(1000d / charsPerSecond));
    }

    private void applyAverageShiftForDevice(DeviceIdentity deviceIdentity, int pauseCharIndex, long letterIntervalMs) {
        if (deviceStatsDao == null || deviceIdentity == null) {
            return;
        }
        DeviceStatsDao.DeviceReactionStats stats = deviceStatsDao.getStats(deviceIdentity.stableKey());
        if (stats == null || stats.sampleCount < 10) {
            return;
        }
        double avgDelay = Math.max(0d, stats.averageDelayMs);
        if (avgDelay < 1d) {
            return;
        }
        long interval = letterIntervalMs > 0 ? letterIntervalMs : defaultLetterIntervalMs();
        int charShift = (int) Math.max(0, Math.round(avgDelay / Math.max(1d, (double) interval)));
        if (charShift <= 0) {
            return;
        }
        int targetIndex = Math.max(0, pauseCharIndex - charShift);
        if (readerView != null) {
            readerView.highlightLetter(targetIndex);
        }
        currentCharIndex = targetIndex;
        updatePendingSpeechChar(currentCharIndex);
        adjustSentenceIndexForChar(targetIndex);
    }

    private void adjustSentenceIndexForChar(int charIndex) {
        if (charIndex < 0 || sentenceRanges.isEmpty()) {
            return;
        }
        int index = findSentenceIndexForChar(charIndex);
        if (index < 0) {
            return;
        }
        currentSentenceIndex = index;
        ReaderView.SentenceRange range = sentenceRanges.get(index);
        if (range != null) {
            currentSentenceStart = range.start;
            currentSentenceEnd = range.end;
        }
    }

    private int findSentenceIndexForChar(int charIndex) {
        if (charIndex < 0 || sentenceRanges.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < sentenceRanges.size(); i++) {
            ReaderView.SentenceRange range = sentenceRanges.get(i);
            if (range == null) {
                continue;
            }
            int start = range.start;
            int end = range.end;
            if (start <= charIndex && charIndex < end) {
                return i;
            }
        }
        return -1;
    }

    private void pauseSpeechForDetail() {
        if (speechSessionActive || isSpeaking || shouldContinueSpeech) {
            speechSessionActive = true;
            markLastModeVoice();
        }
        stopProgressUpdates();
        exitPromptMode();
        if (sentencePlayer != null) {
            try {
                if (sentencePlayer.isPlaying()) {
                    sentencePlayer.pause();
                }
            } catch (IllegalStateException ignored) {
                releaseSentencePlayer();
            }
        }
        isSpeaking = false;
        updatePlaybackState(PlaybackState.STATE_PAUSED);
        updateSpeechButtons();
    }

    private void resumeSentenceAfterDetail() {
        detailAutoResume = false;
        awaitingResumeAfterDetail = false;
        dismissTokenSheet(true);
        speechSessionActive = true;
        if (sentencePlayer != null) {
            try {
                markLastModeVoice();
                sentencePlayer.start();
                isSpeaking = true;
                shouldContinueSpeech = true;
                updatePlaybackState(PlaybackState.STATE_PLAYING);
                updateSpeechButtons();
                startProgressUpdates();
                return;
            } catch (IllegalStateException ignored) {
                releaseSentencePlayer();
            }
        }
        markLastModeVoice();
        shouldContinueSpeech = true;
        speakCurrentSentence();
    }

    private void stopDetailPlayback() {
        if (detailPlayer != null) {
            try {
                detailPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            detailPlayer.release();
            detailPlayer = null;
        }
        if (activeDetailRequest != null) {
            activeDetailRequest.deleteFile();
            activeDetailRequest = null;
        }
    }

    private boolean cancelDetailSpeech() {
        boolean hasDetail = detailPlaybackActive()
                || activeDetailRequest != null
                || pendingDetailRequest != null;
        if (!hasDetail) {
            return false;
        }
        stopDetailPlayback();
        cancelPendingDetailRequest();
        detailAutoResume = false;
        awaitingResumeAfterDetail = true;
        if (speechSessionActive || isSpeaking || shouldContinueSpeech) {
            speechSessionActive = true;
            markLastModeVoice();
        }
        updatePlaybackState(PlaybackState.STATE_PAUSED);
        updateSpeechButtons();
        return true;
    }

    private boolean detailPlaybackActive() {
        if (detailPlayer == null) return false;
        try {
            return detailPlayer.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void playDetailRequest(SpeechRequest request) {
        if (request == null) return;
        stopDetailPlayback();
        activeDetailRequest = request;
        detailAutoResume = request.resumeAfterPlayback;
        awaitingResumeAfterDetail = !detailAutoResume;
        if (request.tokenSpan != null) {
            lastDetailSpan = request.tokenSpan;
        }
        lastDetailResumeAfter = request.resumeAfterPlayback;
        detailPlayer = new MediaPlayer();
        try {
            detailPlayer.setDataSource(request.file.getAbsolutePath());
            detailPlayer.setOnPreparedListener(mp -> {
                mp.start();
                updatePlaybackState(PlaybackState.STATE_PLAYING);
            });
            detailPlayer.setOnCompletionListener(mp -> handleDetailPlaybackComplete());
            detailPlayer.setOnErrorListener((mp, what, extra) -> {
                handleDetailPlaybackComplete();
                return true;
            });
            detailPlayer.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Failed to play detail audio", e);
            handleDetailPlaybackComplete();
        }
    }

    private void handleDetailPlaybackComplete() {
        if (detailPlayer != null) {
            try {
                detailPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            detailPlayer.release();
            detailPlayer = null;
        }
        if (activeDetailRequest != null) {
            activeDetailRequest.deleteFile();
            activeDetailRequest = null;
        }
        if (detailAutoResume) {
            resumeSentenceAfterDetail();
        } else {
            awaitingResumeAfterDetail = true;
            updatePlaybackState(PlaybackState.STATE_PAUSED);
            updateSpeechButtons();
        }
        detailAutoResume = false;
    }

    private int resolveFocusCharIndex() {
        if (currentCharIndex >= 0) {
            return currentCharIndex;
        }
        if (currentSentenceStart >= 0) {
            return currentSentenceStart;
        }
        if (currentSentenceIndex >= 0 && currentSentenceIndex < sentenceRanges.size()) {
            ReaderView.SentenceRange range = sentenceRanges.get(currentSentenceIndex);
            if (range != null) {
                return range.start;
            }
        }
        if (!sentenceRanges.isEmpty()) {
            ReaderView.SentenceRange first = sentenceRanges.get(0);
            if (first != null) {
                return first.start;
            }
        }
        return -1;
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS);
        int playbackState = state;
        if (playbackState != PlaybackState.STATE_PLAYING
                && playbackState != PlaybackState.STATE_PAUSED
                && playbackState != PlaybackState.STATE_STOPPED) {
            playbackState = isSpeaking ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        }
        float playbackSpeed = playbackState == PlaybackState.STATE_PLAYING ? 1f : 0f;
        builder.setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, playbackSpeed);
        mediaSession.setPlaybackState(builder.build());
    }

    private boolean isSpeechModeActive() {
        if (speechStoppingInProgress) {
            return false;
        }
        return speechSessionActive
                || shouldContinueSpeech
                || isSpeaking
                || awaitingResumeAfterDetail
                || detailPlaybackActive()
                || activeDetailRequest != null
                || pendingDetailRequest != null;
    }

    private void updateSpeechButtons() {
        boolean voiceAvailable = ttsReady && talgatVoice != null;
        boolean sessionActive = (speechSessionActive || isSpeaking || shouldContinueSpeech)
                && !speechStoppingInProgress;
        Log.d(TAG, "updateSpeechButtons: voiceAvailable=" + voiceAvailable
                + ", sessionActive=" + sessionActive
                + ", speechStoppingInProgress=" + speechStoppingInProgress
                + ", isSpeaking=" + isSpeaking
                + ", shouldContinueSpeech=" + shouldContinueSpeech
                + ", awaitingResumeAfterDetail=" + awaitingResumeAfterDetail
                + ", detailPlaybackActive=" + detailPlaybackActive());
        if (toggleSpeechMenuItem != null) {
            int iconRes;
            int descriptionRes;
            boolean toggleEnabled;
            if (!sessionActive) {
                iconRes = R.drawable.ic_radio_point;
                descriptionRes = R.string.speech_toggle_content_start;
                toggleEnabled = voiceAvailable;
            } else if (isSpeaking) {
                iconRes = R.drawable.ic_pause;
                descriptionRes = R.string.speech_toggle_content_pause;
                toggleEnabled = true;
            } else {
                iconRes = R.drawable.ic_play;
                descriptionRes = R.string.speech_toggle_content_resume;
                toggleEnabled = true;
            }
            if (speechStoppingInProgress) {
                toggleEnabled = false;
            }
            String toggleIconName = getResources().getResourceEntryName(iconRes);
            Log.d(TAG, "updateSpeechButtons: toggle icon=" + toggleIconName
                    + ", enabled=" + toggleEnabled
                    + ", descriptionRes=" + getResources().getResourceEntryName(descriptionRes));
            toggleSpeechMenuItem.setEnabled(toggleEnabled);
            Drawable toggleIcon = getDrawable(iconRes);
            if (toggleIcon != null) {
                toggleIcon = toggleIcon.mutate();
                toggleIcon.setAlpha(toggleEnabled ? 255 : 100);
                toggleSpeechMenuItem.setIcon(toggleIcon);
            }
            toggleSpeechMenuItem.setTitle(getString(descriptionRes));
        }
        if (stopSpeechMenuItem != null) {
            boolean stopVisible = sessionActive;
            stopSpeechMenuItem.setVisible(stopVisible);
            boolean stopEnabled = sessionActive;
            stopSpeechMenuItem.setEnabled(stopEnabled);
            Log.d(TAG, "updateSpeechButtons: stop icon visible=" + stopVisible
                    + ", enabled=" + stopEnabled);
            Drawable stopIcon = getDrawable(R.drawable.ic_stop);
            if (stopIcon != null) {
                stopIcon = stopIcon.mutate();
                stopIcon.setAlpha(stopEnabled ? 255 : 100);
                stopSpeechMenuItem.setIcon(stopIcon);
            }
        }
        if (skipBackMenuItem != null) {
            boolean skipVisible = sessionActive;
            skipBackMenuItem.setVisible(skipVisible);
            boolean skipEnabled = sessionActive;
            skipBackMenuItem.setEnabled(skipEnabled);
            Drawable backIcon = getDrawable(R.drawable.ic_skip_back);
            if (backIcon != null) {
                backIcon = backIcon.mutate();
                backIcon.setAlpha(skipEnabled ? 255 : 100);
                skipBackMenuItem.setIcon(backIcon);
            }
        }
        if (skipForwardMenuItem != null) {
            boolean skipVisible = sessionActive;
            skipForwardMenuItem.setVisible(skipVisible);
            boolean skipEnabled = sessionActive;
            skipForwardMenuItem.setEnabled(skipEnabled);
            Drawable forwardIcon = getDrawable(R.drawable.ic_skip_forward);
            if (forwardIcon != null) {
                forwardIcon = forwardIcon.mutate();
                forwardIcon.setAlpha(skipEnabled ? 255 : 100);
                skipForwardMenuItem.setIcon(forwardIcon);
            }
        }
        updatePageControls();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleExternalKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleExternalKeyEvent(KeyEvent event) {
        if (event == null) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        InputDevice device = event.getDevice();
        if (!isExternalInputDevice(device)) return false;
        int sources = device.getSources();
        if ((sources & InputDevice.SOURCE_KEYBOARD) == 0
                && (sources & InputDevice.SOURCE_CLASS_BUTTON) == 0) {
            return false;
        }
        int keyCode = event.getKeyCode();
        if (detailPlaybackActive() || activeDetailRequest != null || pendingDetailRequest != null) {
            if (isPlayPauseKey(keyCode) || keyCode == KeyEvent.KEYCODE_BACK) {
                cancelDetailSpeech();
            } else if (isSkipForwardKey(keyCode)) {
                handleDetailSkip(true);
            } else if (isSkipBackwardKey(keyCode)) {
                handleDetailSkip(false);
            }
            return true;
        }
        if (awaitingResumeAfterDetail) {
            if (isPlayPauseKey(keyCode)) {
                resumeSentenceAfterDetail();
            } else if (isSkipForwardKey(keyCode)) {
                handleDetailSkip(true);
            } else if (isSkipBackwardKey(keyCode)) {
                handleDetailSkip(false);
            } else {
                handleKeyboardDetailRequest();
            }
            return true;
        }
        if (isSkipForwardKey(keyCode)) {
            handleMediaSkip(true);
            return true;
        }
        if (isSkipBackwardKey(keyCode)) {
            handleMediaSkip(false);
            return true;
        }
        if (isSpeaking) {
            handleKeyboardDetailRequest();
            return true;
        }
        if (isPlayPauseKey(keyCode)) {
            startSpeech();
            return true;
        }
        return false;
    }

    private boolean isExternalInputDevice(InputDevice device) {
        if (device == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Method method = InputDevice.class.getMethod("isExternal");
                Object result = method.invoke(device);
                if (result instanceof Boolean && (Boolean) result) {
                    return true;
                }
            } catch (ReflectiveOperationException ignore) {
            }
        }
        if (device.isVirtual()) {
            return false;
        }
        int sources = device.getSources();
        if ((sources & InputDevice.SOURCE_CLASS_BUTTON) != 0) {
            return true;
        }
        if ((sources & InputDevice.SOURCE_KEYBOARD) != 0
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return true;
        }
        return device.getVendorId() != 0 || device.getProductId() != 0;
    }

    private void handleKeyboardDetailRequest() {
        if (readerView == null) return;
        int focusIndex = resolveFocusCharIndex();
        if (focusIndex < 0) return;
        TokenSpan span = readerView.findSpanForCharIndex(focusIndex);
        if (span == null) return;
        readerView.ensureExposureLogged(span);
        List<String> translations = readerView.getTranslations(span);
        speakTokenDetails(span, translations, false);
        showTokenSheet(span, translations);
    }

    private boolean isPlayPauseKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK;
    }

    private boolean isSkipForwardKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_STEP_FORWARD;
    }

    private boolean isSkipBackwardKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                || keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD;
    }

    private void updateInstallButtonVisibility() {
        if (installTalgatMenuItem == null) return;
        boolean show = rhVoiceInstalled && (talgatVoice == null);
        installTalgatMenuItem.setVisible(show);
        installTalgatMenuItem.setEnabled(show);
    }

    private void updateRhVoiceState() {
        boolean installed = isRhVoiceInstalled();
        if (installed != rhVoiceInstalled || textToSpeech == null) {
            rhVoiceInstalled = installed;
            initTextToSpeech();
        } else if (ttsReady) {
            locateTalgatVoice();
            updateSpeechButtons();
            updateInstallButtonVisibility();
        }
    }

    private void ensureRhvoiceReady() {
        RhvoiceAvailability.checkStatus(this, status -> {
            if (isFinishing()) return;
            switch (status) {
                case RhvoiceAvailability.Status.READY:
                    rhVoiceInstalled = true;
                    updateRhVoiceState();
                    break;
                case RhvoiceAvailability.Status.ENGINE_MISSING:
                    rhVoiceInstalled = false;
                    updateRhVoiceState();
                    showRhvoiceEngineDialog();
                    break;
                case RhvoiceAvailability.Status.VOICE_MISSING:
                    rhVoiceInstalled = true;
                    updateRhVoiceState();
                    showRhvoiceVoiceDialog();
                    break;
                default:
                    break;
            }
        });
    }

    private boolean isRhVoiceInstalled() {
        try {
            PackageManager pm = getPackageManager();
            pm.getPackageInfo(RHVOICE_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {

            return false;
        }
    }

    private void openTalgatInstall() {
        Intent installIntent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
        installIntent.setPackage(RHVOICE_PACKAGE);
        try {
            startActivity(installIntent);
        } catch (ActivityNotFoundException e) {
            Uri marketUri = Uri.parse("market://details?id=com.github.olga_yakovleva.rhvoice.android.language.tt");
            Intent marketIntent = new Intent(Intent.ACTION_VIEW, marketUri);
            try {
                startActivity(marketIntent);
            } catch (ActivityNotFoundException ex) {
                Uri webUri = Uri.parse("https://play.google.com/store/apps/details?id=com.github.olga_yakovleva.rhvoice.android.language.tt");
                startActivity(new Intent(Intent.ACTION_VIEW, webUri));
            }
        }

    }

    private void showRhvoiceEngineDialog() {
        if (isFinishing()) return;
        dismissRhvoiceDialog();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.rhvoice_install_title)
                .setMessage(R.string.rhvoice_install_message)
                .setPositiveButton(R.string.rhvoice_install_store, (dialog, which) -> openRhvoiceStore())
                .setNeutralButton(R.string.rhvoice_install_download, (dialog, which) ->
                        startActivitySafely(RhvoiceAvailability.createProjectPageIntent()))
                .setNegativeButton(android.R.string.cancel, null);
        rhvoiceDialog = builder.create();
        rhvoiceDialog.setOnDismissListener(dialog -> rhvoiceDialog = null);
        rhvoiceDialog.show();
    }

    private void showRhvoiceVoiceDialog() {
        if (isFinishing()) return;
        dismissRhvoiceDialog();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.rhvoice_voice_title)
                .setMessage(R.string.rhvoice_voice_message)
                .setPositiveButton(R.string.rhvoice_voice_open_app, (dialog, which) -> openRhvoiceApp())
                .setNeutralButton(R.string.rhvoice_voice_download, (dialog, which) ->
                        startActivitySafely(RhvoiceAvailability.createVoiceDownloadIntent()))
                .setNegativeButton(android.R.string.cancel, null);
        rhvoiceDialog = builder.create();
        rhvoiceDialog.setOnDismissListener(dialog -> rhvoiceDialog = null);
        rhvoiceDialog.show();
    }

    private void openRhvoiceStore() {
        if (startActivitySafely(RhvoiceAvailability.createPlayStoreIntent())) return;
        if (startActivitySafely(RhvoiceAvailability.createPlayStoreWebIntent())) return;
        if (startActivitySafely(RhvoiceAvailability.createProjectPageIntent())) return;
        Toast.makeText(this, R.string.rhvoice_no_handler, Toast.LENGTH_LONG).show();
    }

    private void openRhvoiceApp() {
        Intent launchIntent = RhvoiceAvailability.createLaunchIntent(this);
        if (startActivitySafely(launchIntent)) return;
        openRhvoiceStore();
    }

    private boolean startActivitySafely(Intent intent) {
        if (intent == null) return false;
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private void dismissRhvoiceDialog() {
        if (rhvoiceDialog != null) {
            rhvoiceDialog.dismiss();
            rhvoiceDialog = null;
        }
    }
}
