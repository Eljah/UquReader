package com.example.ttreader;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.KeyEvent;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.Toast;

import com.example.ttreader.data.DbHelper;
import com.example.ttreader.data.MemoryDao;
import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.reader.ReaderView;
import com.example.ttreader.reader.TokenSpan;
import com.example.ttreader.reader.TtsReaderController;
import com.example.ttreader.ui.TokenInfoBottomSheet;
import com.example.ttreader.util.GrammarResources;
import com.example.ttreader.tts.RhvoiceAvailability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements ReaderView.TokenInfoProvider {
    private static final String LANGUAGE_PAIR_TT_RU = "tt-ru";
    private static final String SAMPLE_ASSET = "sample_book.ttmorph.jsonl";
    private static final String SAMPLE_WORK_ID = "sample_book.ttmorph";
    private static final String RHVOICE_PACKAGE = "com.github.olga_yakovleva.rhvoice.android";
    private static final String TALGAT_NAME_KEYWORD = "talgat";
    private static final float BASE_CHARS_PER_SECOND = 14f;
    private static final float DEFAULT_SPEECH_RATE = 1f;

    public static final String EXTRA_TARGET_CHAR_INDEX = "com.example.ttreader.TARGET_CHAR_INDEX";

    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private UsageStatsDao usageStatsDao;
    private ScrollView readerScrollView;
    private ReaderView readerView;
    private ImageButton toggleSpeechButton;
    private ImageButton stopSpeechButton;
    private Button installTalgatButton;
    private TtsReaderController ttsController;
    private AlertDialog rhvoiceDialog;

    private final Handler speechProgressHandler = new Handler(Looper.getMainLooper());
    private final List<ReaderView.SentenceRange> sentenceRanges = new ArrayList<>();
    private final UtteranceProgressListener utteranceListener = new UtteranceProgressListener() {
        @Override public void onStart(String utteranceId) {
            utteranceStartElapsed = SystemClock.elapsedRealtime();
            isSpeaking = true;
            speechProgressHandler.post(() -> {
                if (readerView != null) {
                    readerView.highlightSentenceRange(currentSentenceStart, currentSentenceEnd);
                    readerView.highlightLetter(currentSentenceStart);
                }
                updateSpeechButtons();
                updatePlaybackState(PlaybackState.STATE_PLAYING);
            });
            speechProgressHandler.post(MainActivity.this::startProgressUpdates);
        }

        @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
            speechProgressHandler.post(() -> handleUtteranceRange(start));
        }

        @Override public void onDone(String utteranceId) {
            speechProgressHandler.post(MainActivity.this::handleUtteranceDone);
        }

        @Override public void onError(String utteranceId) {
            speechProgressHandler.post(MainActivity.this::handleUtteranceError);
        }
    };
    private final Runnable speechProgressRunnable = new Runnable() {
        @Override public void run() {
            if (!isSpeaking) return;
            if (estimatedUtteranceDurationMs <= 0) return;
            int length = Math.max(0, currentSentenceEnd - currentSentenceStart);
            if (length == 0) return;
            long elapsed = SystemClock.elapsedRealtime() - utteranceStartElapsed;
            if (elapsed < 0) elapsed = 0;
            float progress = Math.min(1f, (float) elapsed / (float) estimatedUtteranceDurationMs);
            int offset = Math.min(length - 1, (int) (progress * length));
            int highlightIndex = currentSentenceStart + offset;
            currentCharIndex = highlightIndex;
            if (readerView != null) {
                readerView.highlightLetter(highlightIndex);
            }
            if (progress < 1f && shouldContinueSpeech) {
                speechProgressHandler.postDelayed(this, 50);
            }
        }
    };

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
    private long utteranceStartElapsed = 0L;
    private MediaSession mediaSession;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GrammarResources.initialize(this);
        setContentView(R.layout.activity_main);

        dbHelper = new DbHelper(this);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        memoryDao = new MemoryDao(db);
        usageStatsDao = new UsageStatsDao(db);

        readerScrollView = findViewById(R.id.readerScrollView);
        readerView = findViewById(R.id.readerView);
        readerView.setup(dbHelper, memoryDao, usageStatsDao, this);
        readerView.setUsageContext(LANGUAGE_PAIR_TT_RU, SAMPLE_WORK_ID);
        readerView.loadFromJsonlAsset(SAMPLE_ASSET);
        readerView.post(this::updateSentenceRanges);

        ttsController = new TtsReaderController(this, readerView::getTranslations);
        ttsController.setTokenSequence(readerView.getTokenSpans());

        if (readerScrollView != null) {
            ViewTreeObserver observer = readerScrollView.getViewTreeObserver();
            observer.addOnScrollChangedListener(() ->
                    readerView.onViewportChanged(readerScrollView.getScrollY(), readerScrollView.getHeight()));
            readerScrollView.post(() ->
                    readerView.onViewportChanged(readerScrollView.getScrollY(), readerScrollView.getHeight()));
        }

        Button languageStatsButton = findViewById(R.id.btnLanguageStats);
        languageStatsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, StatsActivity.class);
            intent.putExtra(StatsActivity.EXTRA_MODE, StatsActivity.MODE_LANGUAGE);
            intent.putExtra(StatsActivity.EXTRA_LANGUAGE_PAIR, LANGUAGE_PAIR_TT_RU);
            startActivity(intent);
        });

        Button workStatsButton = findViewById(R.id.btnWorkStats);
        workStatsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, StatsActivity.class);
            intent.putExtra(StatsActivity.EXTRA_MODE, StatsActivity.MODE_WORK);
            intent.putExtra(StatsActivity.EXTRA_LANGUAGE_PAIR, LANGUAGE_PAIR_TT_RU);
            intent.putExtra(StatsActivity.EXTRA_WORK_ID, SAMPLE_WORK_ID);
            startActivity(intent);
        });

        toggleSpeechButton = findViewById(R.id.btnToggleSpeech);
        stopSpeechButton = findViewById(R.id.btnStopSpeech);
        installTalgatButton = findViewById(R.id.btnInstallTalgat);

        if (toggleSpeechButton != null) {
            toggleSpeechButton.setOnClickListener(v -> toggleSpeech());
        }
        if (stopSpeechButton != null) {
            stopSpeechButton.setOnClickListener(v -> stopSpeech());
        }
        if (installTalgatButton != null) {
            installTalgatButton.setOnClickListener(v -> openTalgatInstall());
        }

        rhVoiceInstalled = isRhVoiceInstalled();
        initMediaSession();
        initTextToSpeech();
        updateInstallButtonVisibility();
        updateSpeechButtons();

        handleNavigationIntent(getIntent());
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

    @Override public void onTokenLongPress(TokenSpan span, List<String> ruLemmas) {
        if (ttsController != null) {
            ttsController.speakTokenDetails(span, ruLemmas, true);
        }
        showTokenSheet(span, ruLemmas);
    }

    private void handleNavigationIntent(Intent intent) {
        if (intent == null) return;
        int targetIndex = intent.getIntExtra(EXTRA_TARGET_CHAR_INDEX, -1);
        if (targetIndex < 0) return;
        intent.removeExtra(EXTRA_TARGET_CHAR_INDEX);
        if (readerView == null) return;
        readerView.post(() -> navigateToCharIndex(targetIndex, 0));
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

    private void showTokenSheet(TokenSpan span, List<String> ruLemmas) {
        if (span == null || span.token == null || span.token.analysis == null) return;
        List<String> safeRu = ruLemmas == null ? new java.util.ArrayList<>() : ruLemmas;
        String ruCsv = safeRu.isEmpty()? "—" : String.join(", ", safeRu);
        TokenInfoBottomSheet sheet = TokenInfoBottomSheet.newInstance(span.token.surface, span.token.analysis, ruCsv);
        sheet.setUsageStatsDao(usageStatsDao);
        sheet.setUsageContext(LANGUAGE_PAIR_TT_RU, SAMPLE_WORK_ID, span.getStartIndex());
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
        if (!ttsReady || talgatVoice == null) return;
        updateSentenceRanges();
        if (sentenceRanges.isEmpty()) return;
        if (currentSentenceIndex < 0 || currentSentenceIndex >= sentenceRanges.size()) {
            currentSentenceIndex = 0;
        }
        shouldContinueSpeech = true;
        isSpeaking = true;
        if (mediaSession != null) {
            mediaSession.setActive(true);
        }
        updatePlaybackState(PlaybackState.STATE_PLAYING);
        updateSpeechButtons();
        speakCurrentSentence();
    }

    private void pauseSpeech() {
        shouldContinueSpeech = false;
        isSpeaking = false;
        stopProgressUpdates();
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        updatePlaybackState(PlaybackState.STATE_PAUSED);
        updateSpeechButtons();
    }

    private void stopSpeech() {
        shouldContinueSpeech = false;
        isSpeaking = false;
        stopProgressUpdates();
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        currentSentenceIndex = 0;
        currentSentenceStart = -1;
        currentSentenceEnd = -1;
        currentCharIndex = -1;
        estimatedUtteranceDurationMs = 0L;
        if (readerView != null) {
            readerView.clearSpeechHighlights();
        }
        updatePlaybackState(PlaybackState.STATE_STOPPED);
        updateSpeechButtons();
    }

    private void speakCurrentSentence() {
        if (!shouldContinueSpeech || textToSpeech == null) return;
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
        estimatedUtteranceDurationMs = estimateSentenceDurationMs(sentence);
        if (readerView != null) {
            readerView.highlightSentenceRange(sentence.start, sentence.end);
            readerView.highlightLetter(sentence.start);
        }
        scrollSentenceIntoView(sentence);
        String utteranceId = "talgat_sentence_" + currentSentenceIndex;
        textToSpeech.setVoice(talgatVoice);
        textToSpeech.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, new Bundle(), utteranceId);
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

    private void handleUtteranceDone() {
        stopProgressUpdates();
        if (shouldContinueSpeech) {
            if (readerView != null) {
                readerView.highlightLetter(-1);
            }
            currentSentenceIndex++;
            if (currentSentenceIndex >= sentenceRanges.size()) {
                stopSpeech();
            } else {
                speakCurrentSentence();
            }
        } else {
            isSpeaking = false;
            updatePlaybackState(PlaybackState.STATE_PAUSED);
            updateSpeechButtons();
        }
    }

    private void handleUtteranceError() {
        stopProgressUpdates();
        isSpeaking = false;
        shouldContinueSpeech = false;
        updatePlaybackState(PlaybackState.STATE_PAUSED);
        updateSpeechButtons();
    }

    private void handleUtteranceRange(int rangeStart) {
        if (!isSpeaking) return;
        int sentenceStart = currentSentenceStart;
        int sentenceEnd = currentSentenceEnd;
        if (sentenceStart < 0 || sentenceEnd <= sentenceStart) return;
        int sentenceLength = sentenceEnd - sentenceStart;
        if (sentenceLength <= 0) return;
        int safeStart = Math.max(0, Math.min(rangeStart, sentenceLength - 1));
        int highlightIndex = sentenceStart + safeStart;
        currentCharIndex = highlightIndex;
        if (readerView != null) {
            readerView.highlightLetter(highlightIndex);
        }
        if (estimatedUtteranceDurationMs > 0) {
            long now = SystemClock.elapsedRealtime();
            long elapsed = Math.max(0L, now - utteranceStartElapsed);
            long targetElapsed = (long) ((safeStart / (float) sentenceLength) * estimatedUtteranceDurationMs);
            if (targetElapsed < elapsed) {
                utteranceStartElapsed = now - targetElapsed;
            }
        }
    }

    private long estimateSentenceDurationMs(ReaderView.SentenceRange sentence) {
        if (sentence == null) return 0L;
        int length = Math.max(1, sentence.length());
        float speechRate = DEFAULT_SPEECH_RATE;
        float effectiveRate = Math.max(0.1f, speechRate);
        float durationSeconds = length / (BASE_CHARS_PER_SECOND * effectiveRate);
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
        String engine = rhVoiceInstalled ? RHVOICE_PACKAGE : null;
        textToSpeech = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                textToSpeech.setOnUtteranceProgressListener(utteranceListener);
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

    private void initMediaSession() {
        mediaSession = new MediaSession(this, "TalgatReaderSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                if (mediaButtonIntent == null) return super.onMediaButtonEvent(mediaButtonIntent);
                KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    speechProgressHandler.post(MainActivity.this::performMediaButtonAction);
                    return true;
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }

            @Override public void onSkipToNext() {
                speechProgressHandler.post(MainActivity.this::performMediaButtonAction);
            }

            @Override public void onSkipToPrevious() {
                speechProgressHandler.post(MainActivity.this::performMediaButtonAction);
            }
        });
        updatePlaybackState(false);
        mediaSession.setActive(false);
    }

    private void performMediaButtonAction() {
        if (isSpeaking) {
            pauseSpeechFromHeadset();
        } else {
            startSpeech();
        }
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
        int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        builder.setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f);
        mediaSession.setPlaybackState(builder.build());
    }

    private void updateSpeechButtons() {
        boolean voiceAvailable = ttsReady && talgatVoice != null;
        if (toggleSpeechButton != null) {
            toggleSpeechButton.setEnabled(voiceAvailable);
            toggleSpeechButton.setImageResource(isSpeaking ? R.drawable.ic_pause : R.drawable.ic_voice);
            toggleSpeechButton.setContentDescription(getString(isSpeaking ?
                    R.string.speech_toggle_content_pause : R.string.speech_toggle_content_start));
            toggleSpeechButton.setAlpha(voiceAvailable ? 1f : 0.4f);
        }
        if (stopSpeechButton != null) {
            stopSpeechButton.setEnabled(voiceAvailable);
            stopSpeechButton.setAlpha(voiceAvailable ? 1f : 0.4f);
        }
    }

    private void updateInstallButtonVisibility() {
        if (installTalgatButton == null) return;
        boolean show = rhVoiceInstalled && (talgatVoice == null);
        installTalgatButton.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
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
