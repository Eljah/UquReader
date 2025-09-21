package com.example.ttreader.reader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.util.Log;

import com.example.ttreader.tts.RhvoiceAvailability;
import com.example.ttreader.util.DetailSpeechFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controls text-to-speech playback of reader content using RHVoice voice "Talgat".
 */
public class TtsReaderController {
    private static final String TAG = "TtsReaderController";
    private static final String RHVOICE_PACKAGE = RhvoiceAvailability.ENGINE_PACKAGE;
    private static final String TALGAT_KEYWORD = "talgat";
    private static final String UTTERANCE_PREFIX_WORD = "uqu-word-";
    private static final String UTTERANCE_PREFIX_DETAIL = "uqu-detail-";

    public interface TranslationProvider {
        List<String> getTranslations(TokenSpan span);
    }

    private static final class Mode {
        static final int IDLE = 0;
        static final int READING = 1;
        static final int DETAIL = 2;
        static final int WAITING_RESUME = 3;

        private Mode() {}
    }

    private final Context context;
    private final TranslationProvider translationProvider;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger utteranceCounter = new AtomicInteger();
    private final List<TokenSpan> sequence = new ArrayList<>();

    private TextToSpeech tts;
    private MediaSession mediaSession;
    private boolean initialized = false;
    private boolean pendingStart = false;
    private boolean isSpeaking = false;
    private int mode = Mode.IDLE;
    private int currentIndex = 0;
    private TokenSpan currentSpan;
    private boolean resumeAfterDetails = false;

    public TtsReaderController(Context context, TranslationProvider provider) {
        this.context = context == null ? null : context.getApplicationContext();
        this.translationProvider = provider;
        initializeTextToSpeech();
        initializeMediaSession();
    }

    private void initializeTextToSpeech() {
        if (context == null) return;
        tts = new TextToSpeech(context, this::handleTtsInit, RHVOICE_PACKAGE);
        tts.setOnUtteranceProgressListener(new RhvoiceUtteranceListener(this));
    }

    private void initializeMediaSession() {
        if (context == null) return;
        mediaSession = new MediaSession(context, TAG);
        mediaSession.setCallback(new ReaderMediaSessionCallback(this));
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);
        updatePlaybackState();
    }

    private void handleTtsInit(int status) {
        initialized = status == TextToSpeech.SUCCESS;
        if (!initialized) {
            Log.w(TAG, "Failed to initialize RHVoice TTS engine");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            selectRhvoiceVoice();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .build();
            tts.setAudioAttributes(attrs);
        }
        tts.setSpeechRate(0.95f);
        updatePlaybackState();
        if (pendingStart) {
            pendingStart = false;
            startReading();
        }
    }

    private void selectRhvoiceVoice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return;
            for (Voice voice : voices) {
                if (voice == null || voice.getName() == null) continue;
                String name = voice.getName().toLowerCase(Locale.ROOT);
                if (name.contains(TALGAT_KEYWORD)) {
                    tts.setVoice(voice);
                    if (voice.getLocale() != null) {
                        tts.setLanguage(voice.getLocale());
                    }
                    return;
                }
            }
            Log.w(TAG, "Voice Talgat not found, using default voice");
        } catch (Exception e) {
            Log.e(TAG, "Failed to select RHVoice voice", e);
        }
    }

    public void setTokenSequence(List<TokenSpan> spans) {
        sequence.clear();
        if (spans != null) {
            for (TokenSpan span : spans) {
                if (span == null || span.token == null) continue;
                String surface = span.token.surface;
                if (TextUtils.isEmpty(surface)) continue;
                sequence.add(span);
            }
        }
        currentIndex = 0;
        currentSpan = null;
    }

    public void startReading() {
        if (!initialized) {
            pendingStart = true;
            return;
        }
        if (sequence.isEmpty()) {
            mode = Mode.IDLE;
            updatePlaybackState();
            return;
        }
        mode = Mode.READING;
        updatePlaybackState();
        speakNextWord();
    }

    public void resumeReading() {
        if (!initialized || sequence.isEmpty()) return;
        mode = Mode.READING;
        updatePlaybackState();
        if (!isSpeaking) {
            speakNextWord();
        }
    }

    private void speakNextWord() {
        if (!initialized || mode != Mode.READING) return;
        if (isSpeaking) return;
        while (currentIndex < sequence.size()) {
            TokenSpan span = sequence.get(currentIndex++);
            if (span == null || span.token == null) continue;
            String surface = span.token.surface;
            if (TextUtils.isEmpty(surface)) continue;
            currentSpan = span;
            speakText(surface, UTTERANCE_PREFIX_WORD + utteranceCounter.incrementAndGet(), TextToSpeech.QUEUE_FLUSH);
            return;
        }
        currentSpan = null;
        mode = Mode.IDLE;
        updatePlaybackState();
    }

    public void speakTokenDetails(TokenSpan span, List<String> translations, boolean resumeAfter) {
        if (!initialized || span == null || span.token == null) return;
        List<String> safeTranslations = DetailSpeechFormatter.sanitizeTranslations(translations);
        resumeAfterDetails = resumeAfter && mode == Mode.READING;
        mode = Mode.DETAIL;
        updatePlaybackState();
        if (tts != null) {
            tts.stop();
        }
        isSpeaking = false;
        String detailText = DetailSpeechFormatter.buildDetailSpeech(span, safeTranslations, true);
        speakText(detailText, UTTERANCE_PREFIX_DETAIL + utteranceCounter.incrementAndGet(), TextToSpeech.QUEUE_FLUSH);
    }

    private void handlePauseRequest() {
        if (!initialized) return;
        if (mode == Mode.READING && currentSpan != null) {
            List<String> translations = translationProvider == null
                    ? Collections.emptyList()
                    : translationProvider.getTranslations(currentSpan);
            speakTokenDetails(currentSpan, translations, false);
        } else if (mode == Mode.WAITING_RESUME) {
            resumeReading();
        } else if (mode == Mode.DETAIL && tts != null) {
            tts.stop();
            isSpeaking = false;
            mode = Mode.WAITING_RESUME;
            updatePlaybackState();
        }
    }

    private void speakText(String text, String utteranceId, int queueMode) {
        if (!initialized || tts == null) return;
        if (TextUtils.isEmpty(text)) {
            handleUtteranceFinished(utteranceId);
            return;
        }
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        tts.speak(text, queueMode, params, utteranceId);
    }

    private void handleUtteranceFinished(String utteranceId) {
        isSpeaking = false;
        if (utteranceId == null) return;
        if (utteranceId.startsWith(UTTERANCE_PREFIX_WORD)) {
            if (mode == Mode.READING) {
                speakNextWord();
            }
        } else if (utteranceId.startsWith(UTTERANCE_PREFIX_DETAIL)) {
            if (resumeAfterDetails) {
                resumeAfterDetails = false;
                mode = Mode.READING;
                updatePlaybackState();
                speakNextWord();
            } else {
                mode = Mode.WAITING_RESUME;
                updatePlaybackState();
            }
        }
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        int playbackState;
        switch (mode) {
            case Mode.READING:
                playbackState = PlaybackState.STATE_PLAYING;
                break;
            case Mode.DETAIL:
            case Mode.WAITING_RESUME:
                playbackState = PlaybackState.STATE_PAUSED;
                break;
            case Mode.IDLE:
            default:
                playbackState = PlaybackState.STATE_STOPPED;
                break;
        }
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_STOP)
                .setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        mediaSession.setPlaybackState(builder.build());
    }

    public void release() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (tts != null) {
            try {
                tts.stop();
            } finally {
                tts.shutdown();
            }
            tts = null;
        }
        sequence.clear();
        initialized = false;
        pendingStart = false;
        isSpeaking = false;
        mode = Mode.IDLE;
    }

    private static final class RhvoiceUtteranceListener extends UtteranceProgressListener {
        private final TtsReaderController controller;

        RhvoiceUtteranceListener(TtsReaderController controller) {
            this.controller = controller;
        }

        @Override public void onStart(String utteranceId) {
            controller.mainHandler.post(() -> controller.isSpeaking = true);
        }

        @Override public void onDone(String utteranceId) {
            controller.mainHandler.post(() -> controller.handleUtteranceFinished(utteranceId));
        }

        @Override public void onError(String utteranceId) {
            controller.mainHandler.post(() -> controller.handleUtteranceFinished(utteranceId));
        }
    }

    private static final class ReaderMediaSessionCallback extends MediaSession.Callback {
        private final TtsReaderController controller;

        ReaderMediaSessionCallback(TtsReaderController controller) {
            this.controller = controller;
        }

        @Override public void onPlay() {
            controller.resumeReading();
        }

        @Override public void onPause() {
            controller.handlePauseRequest();
        }
    }
}
