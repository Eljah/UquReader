package com.example.ttreader.reader;

import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.session.MediaSessionCompat;

import java.util.Collections;
import java.util.List;

/**
 * MediaBrowserService-based host for {@link TtsReaderController} that exposes
 * reader speech playback through a media session.
 */
public class ReaderTtsService extends MediaBrowserServiceCompat {
    private static final String MEDIA_ROOT_ID = "uqureader.media.ROOT";

    private final IBinder localBinder = new LocalBinder();
    private TtsReaderController controller;

    @Override
    public void onCreate() {
        super.onCreate();
        controller = new TtsReaderController(this, null);
        MediaSessionCompat session = controller.getMediaSession();
        if (session != null) {
            setSessionToken(session.getSessionToken());
        }
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(MEDIA_ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(Collections.emptyList());
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return super.onBind(intent);
        }
        return localBinder;
    }

    public void setTranslationProvider(TtsReaderController.TranslationProvider provider) {
        if (controller != null) {
            controller.setTranslationProvider(provider);
        }
    }

    public void setTokenSequence(List<TokenSpan> spans) {
        if (controller != null) {
            controller.setTokenSequence(spans);
        }
    }

    public void startReading() {
        if (controller != null) {
            controller.startReading();
        }
    }

    public void resumeReading() {
        if (controller != null) {
            controller.resumeReading();
        }
    }

    public void speakTokenDetails(TokenSpan span, List<String> translations, boolean resumeAfter) {
        if (controller != null) {
            controller.speakTokenDetails(span, translations, resumeAfter);
        }
    }

    public void releaseController() {
        if (controller != null) {
            controller.release();
        }
    }

    @Override
    public void onDestroy() {
        releaseController();
        controller = null;
        super.onDestroy();
    }

    public final class LocalBinder extends Binder {
        public ReaderTtsService getService() {
            return ReaderTtsService.this;
        }
    }
}
