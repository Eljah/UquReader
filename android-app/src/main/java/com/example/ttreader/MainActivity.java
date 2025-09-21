package com.example.ttreader;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.text.TextUtils;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.Toolbar;

import com.example.ttreader.data.DbHelper;
import com.example.ttreader.data.DeviceIdentity;
import com.example.ttreader.data.DeviceStatsDao;
import com.example.ttreader.data.MemoryDao;
import com.example.ttreader.data.UsageStatsDao;
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
import java.util.Arrays;
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
    private static final String LANGUAGE_PAIR_TT_RU = "tt-ru";
    private static final int MENU_WORK_ID_BASE = 100;
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

    private final List<WorkInfo> availableWorks = Arrays.asList(
            new WorkInfo("sample_book.ttmorph", "sample_book.ttmorph.jsonl",
                    R.string.work_name_kubyzkabyz_full, R.string.work_name_kubyzkabyz_short),
            new WorkInfo("berenche_teatr.ttmorph", "berenche_teatr.ttmorph.jsonl",
                    R.string.work_name_berenche_teatr_full, R.string.work_name_berenche_teatr_short),
            new WorkInfo("yazgy_jillar.ttmorph", "yazgy_jillar.ttmorph.jsonl",
                    R.string.work_name_yazgy_jillar_full, R.string.work_name_yazgy_jillar_short)
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
    private ScrollView readerScrollView;
    private ReaderView readerView;
    private Toolbar toolbar;
    private MenuItem languagePairMenuItem;
    private MenuItem workMenuItem;
    private MenuItem toggleSpeechMenuItem;
    private MenuItem stopSpeechMenuItem;
    private MenuItem installTalgatMenuItem;
    private AlertDialog rhvoiceDialog;
    private String currentLanguagePair = LANGUAGE_PAIR_TT_RU;
    private boolean languagePairInitialized = false;
    private WorkInfo currentWork;

    private final Handler speechProgressHandler = new Handler(Looper.getMainLooper());
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

        dbHelper = new DbHelper(this);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        memoryDao = new MemoryDao(db);
        usageStatsDao = new UsageStatsDao(db);
        deviceStatsDao = new DeviceStatsDao(db);

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
        readerView = findViewById(R.id.readerView);
        readerView.setup(dbHelper, memoryDao, usageStatsDao, this);

        if (readerScrollView != null) {
            ViewTreeObserver observer = readerScrollView.getViewTreeObserver();
            observer.addOnScrollChangedListener(() ->
                    readerView.onViewportChanged(readerScrollView.getScrollY(), readerScrollView.getHeight()));
            readerScrollView.post(() ->
                    readerView.onViewportChanged(readerScrollView.getScrollY(), readerScrollView.getHeight()));
        }

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
        } else if (id == R.id.action_toggle_speech) {
            toggleSpeech();
            return true;
        } else if (id == R.id.action_stop_speech) {
            stopSpeech();
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
        super.onPause();
    }

    @Override protected void onDestroy() {
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
            MenuItem item = menu.getMenu().add(Menu.NONE, MENU_WORK_ID_BASE + i, Menu.NONE,
                    work.getFullName(this));
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
        readerView.setUsageContext(currentLanguagePair, work.id);
        readerView.loadFromJsonlAsset(work.asset);
        readerView.post(this::updateSentenceRanges);
        updateWorkMenuDisplay();
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
        stopSpeech();
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
        if (readerView == null || readerScrollView == null || span == null) return;
        android.text.Layout layout = readerView.getLayout();
        CharSequence text = readerView.getText();
        if ((layout == null || text == null) && attempt < 5) {
            readerView.postDelayed(() -> scrollToSpan(span, attempt + 1), 50);
            return;
        } else if (layout == null || text == null) {
            return;
        }
        int textLength = text.length();
        int start = Math.max(0, Math.min(span.getStartIndex(), textLength));
        int line = layout.getLineForOffset(start);
        int y = readerView.getTotalPaddingTop() + layout.getLineTop(line);
        readerScrollView.smoothScrollTo(0, y);
        readerScrollView.post(() ->
                readerView.onViewportChanged(readerScrollView.getScrollY(), readerScrollView.getHeight()));
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
        awaitingResumeAfterDetail = false;
        shouldContinueSpeech = true;
        if (mediaSession != null) {
            mediaSession.setActive(true);
        }
        if (sentencePlayer != null && currentSentenceRequest != null) {
            try {
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
        updateSpeechButtons();
        speakCurrentSentence();
    }

    private void pauseSpeech() {
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
        shouldContinueSpeech = false;
        isSpeaking = false;
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
        currentSentenceIndex = 0;
        currentSentenceStart = -1;
        currentSentenceEnd = -1;
        currentCharIndex = -1;
        estimatedUtteranceDurationMs = 0L;
        pendingPlaybackSentenceIndex = -1;
        if (readerView != null) {
            readerView.clearSpeechHighlights();
        }
        updatePlaybackState(PlaybackState.STATE_STOPPED);
        updateSpeechButtons();
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
        currentSentenceStart = sentence.start;
        currentSentenceEnd = sentence.end;
        currentCharIndex = sentence.start;
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
        if (sentence == null || readerView == null || readerScrollView == null) return;
        readerView.post(() -> {
            TokenSpan span = readerView.findSpanForCharIndex(sentence.start);
            if (span != null) {
                scrollToSpan(span, 0);
            } else {
                android.text.Layout layout = readerView.getLayout();
                CharSequence text = readerView.getText();
                if (layout == null || text == null) return;
                int index = Math.max(0, Math.min(sentence.start, text.length()));
                int line = layout.getLineForOffset(index);
                int y = readerView.getTotalPaddingTop() + layout.getLineTop(line);
                readerScrollView.smoothScrollTo(0, y);
            }
        });
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
                long duration = request.durationMs > 0 ? request.durationMs : mp.getDuration();
                if (duration <= 0) {
                    duration = estimateSentenceDurationMs(request.sentenceRange);
                }
                estimatedUtteranceDurationMs = duration;
                currentLetterIntervalMs = computeLetterInterval(request.sentenceRange, duration);
                mp.start();
                isSpeaking = true;
                shouldContinueSpeech = true;
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
            } catch (RuntimeException ignored) {
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
        if (sentencePlayer != null) {
            try {
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

    private void updateSpeechButtons() {
        boolean voiceAvailable = ttsReady && talgatVoice != null;
        if (toggleSpeechMenuItem != null) {
            toggleSpeechMenuItem.setEnabled(voiceAvailable);
            int descriptionRes = isSpeaking
                    ? R.string.speech_toggle_content_pause
                    : R.string.speech_toggle_content_start;
            Drawable toggleIcon = getDrawable(isSpeaking ? R.drawable.ic_pause : R.drawable.ic_voice);
            if (toggleIcon != null) {
                toggleIcon = toggleIcon.mutate();
                toggleIcon.setAlpha(voiceAvailable ? 255 : 100);
                toggleSpeechMenuItem.setIcon(toggleIcon);
            }
            toggleSpeechMenuItem.setTitle(getString(descriptionRes));
        }
        if (stopSpeechMenuItem != null) {
            stopSpeechMenuItem.setEnabled(voiceAvailable);
            Drawable stopIcon = getDrawable(R.drawable.ic_stop);
            if (stopIcon != null) {
                stopIcon = stopIcon.mutate();
                stopIcon.setAlpha(voiceAvailable ? 255 : 100);
                stopSpeechMenuItem.setIcon(stopIcon);
            }
        }
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
