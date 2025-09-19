package com.example.ttreader.tts;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.TextUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Utility helpers for checking and launching installation flows for RHVoice and the Talgat voice.
 */
public final class RhvoiceAvailability {
    public static final String ENGINE_PACKAGE = "com.github.olga_yakovleva.rhvoice.android";
    private static final String TALGAT_KEYWORD = "talgat";
    private static final Uri PLAY_STORE_URI = Uri.parse("market://details?id=" + ENGINE_PACKAGE);
    private static final Uri PLAY_STORE_WEB_URI = Uri.parse("https://play.google.com/store/apps/details?id=" + ENGINE_PACKAGE);
    private static final Uri PROJECT_PAGE_URI = Uri.parse("https://github.com/RHVoice/RHVoice-android");
    private static final Uri VOICE_PAGE_URI = Uri.parse("https://github.com/RHVoice/RHVoice-android/releases");

    public enum Status { READY, ENGINE_MISSING, VOICE_MISSING }

    public interface StatusCallback {
        void onStatus(Status status);
    }

    private RhvoiceAvailability() {}

    public static boolean isEngineInstalled(Context context) {
        if (context == null) return false;
        PackageManager pm = context.getPackageManager();
        if (pm == null) return false;
        try {
            pm.getPackageInfo(ENGINE_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static void checkStatus(Context context, StatusCallback callback) {
        if (context == null || callback == null) return;
        Context appContext = context.getApplicationContext();
        if (!isEngineInstalled(appContext)) {
            postStatus(callback, Status.ENGINE_MISSING);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            postStatus(callback, Status.READY);
            return;
        }
        try {
            final TextToSpeech tts = new TextToSpeech(appContext, status -> {
                Status result = Status.VOICE_MISSING;
                if (status == TextToSpeech.SUCCESS) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                        result = Status.READY;
                    } else {
                        Set<Voice> voices = tts.getVoices();
                        if (voices != null) {
                            for (Voice voice : voices) {
                                if (voice == null) continue;
                                String name = voice.getName();
                                if (!TextUtils.isEmpty(name)
                                        && name.toLowerCase(Locale.ROOT).contains(TALGAT_KEYWORD)) {
                                    result = Status.READY;
                                    break;
                                }
                            }
                        }
                    }
                }
                tts.shutdown();
                postStatus(callback, result);
            }, ENGINE_PACKAGE);
        } catch (Exception e) {
            postStatus(callback, Status.VOICE_MISSING);
        }
    }

    public static Intent createPlayStoreIntent() {
        return new Intent(Intent.ACTION_VIEW, PLAY_STORE_URI);
    }

    public static Intent createPlayStoreWebIntent() {
        return new Intent(Intent.ACTION_VIEW, PLAY_STORE_WEB_URI);
    }

    public static Intent createProjectPageIntent() {
        return new Intent(Intent.ACTION_VIEW, PROJECT_PAGE_URI);
    }

    public static Intent createVoiceDownloadIntent() {
        return new Intent(Intent.ACTION_VIEW, VOICE_PAGE_URI);
    }

    public static Intent createLaunchIntent(Context context) {
        if (context == null) return null;
        PackageManager pm = context.getPackageManager();
        if (pm == null) return null;
        Intent launch = pm.getLaunchIntentForPackage(ENGINE_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        return launch;
    }

    private static void postStatus(StatusCallback callback, Status status) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> callback.onStatus(status));
    }
}
