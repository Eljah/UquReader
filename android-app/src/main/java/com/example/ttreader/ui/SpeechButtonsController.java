package com.example.ttreader.ui;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import com.example.ttreader.R;

/**
 * Управляет отображением кнопок озвучки строго по "UI-событиям".
 * Внешний мир сообщает только: пользователь нажал Toggle/Stop.
 * Никаких обновлений из TTS/MediaPlayer здесь нет.
 */
public final class SpeechButtonsController {

    public enum UiPlaybackMode { IDLE, PLAYING, PAUSED }

    private final Activity activity;

    // menu items
    private MenuItem toggleItem;
    private MenuItem stopItem;
    private MenuItem skipBackItem;
    private MenuItem skipFwdItem;

    // кэш текущего режима — единственный источник правды для иконок
    private UiPlaybackMode mode = UiPlaybackMode.IDLE;

    public SpeechButtonsController(Activity activity) {
        this.activity = activity;
    }

    /** Привязать пункты меню (вызывать из onCreateOptionsMenu). */
    public void bind(Menu menu) {
        toggleItem   = menu.findItem(R.id.action_toggle_speech);
        stopItem     = menu.findItem(R.id.action_stop_speech);
        skipBackItem = menu.findItem(R.id.action_skip_back);
        skipFwdItem  = menu.findItem(R.id.action_skip_forward);
        render(); // первичная отрисовка
    }

    /** Зафиксировать, что пользователь НАЖАЛ toggle. */
    public void onUserPressedToggle() {
        switch (mode) {
            case IDLE:
            case PAUSED:
                mode = UiPlaybackMode.PLAYING;
                break;
            case PLAYING:
                mode = UiPlaybackMode.PAUSED;
                break;
        }
        render();
    }

    /** Зафиксировать, что пользователь НАЖАЛ stop. */
    public void onUserPressedStop() {
        mode = UiPlaybackMode.IDLE;
        render();
    }

    /** Дать текущий "UI-режим" внешнему миру (если нужно для логов/статистики). */
    public UiPlaybackMode getMode() {
        return mode;
    }

    /** Принудительно показать нужный режим (например, при создании меню). */
    public void setMode(UiPlaybackMode newMode) {
        if (newMode != null && newMode != mode) {
            mode = newMode;
            render();
        }
    }

    // ====== отрисовка ======

    private void render() {
        if (toggleItem == null || stopItem == null) return;

        switch (mode) {
            case IDLE:
                applyToggle(R.drawable.ic_radio_point, R.string.speech_toggle_content_start, true);
                applyStopVisible(false);
                applySkipVisible(false);
                break;
            case PLAYING:
                applyToggle(R.drawable.ic_pause, R.string.speech_toggle_content_pause, true);
                applyStopVisible(true);
                applySkipVisible(true);
                break;
            case PAUSED:
                applyToggle(R.drawable.ic_play, R.string.speech_toggle_content_resume, true);
                applyStopVisible(true);
                applySkipVisible(true);
                break;
        }
    }

    private void applyToggle(@DrawableRes int iconRes, @StringRes int desc, boolean enabled) {
        Drawable d = activity.getDrawable(iconRes);
        if (d != null) {
            d = d.mutate();
            d.setAlpha(enabled ? 255 : 100);
            toggleItem.setIcon(d);
        }
        toggleItem.setTitle(activity.getString(desc));
        toggleItem.setEnabled(enabled);
    }

    private void applyStopVisible(boolean visible) {
        stopItem.setVisible(visible);
        stopItem.setEnabled(visible);
    }

    private void applySkipVisible(boolean visible) {
        if (skipBackItem != null) {
            skipBackItem.setVisible(visible);
            skipBackItem.setEnabled(visible);
        }
        if (skipFwdItem != null) {
            skipFwdItem.setVisible(visible);
            skipFwdItem.setEnabled(visible);
        }
    }
}
