package com.example.ttreader.ui;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import com.example.ttreader.R;

/**
 * UI-only контроллер кнопок озвучки.
 * Меняет иконки/видимость ТОЛЬКО по пользовательским нажатиям.
 * Любые фоновые события TTS/MediaPlayer сюда не прокидываются.
 */
public final class SpeechButtonsController {

    public enum UiPlaybackMode { IDLE, PLAYING, PAUSED }

    private final Activity activity;

    private MenuItem toggleItem;
    private MenuItem stopItem;
    private MenuItem skipBackItem;
    private MenuItem skipFwdItem;

    private UiPlaybackMode mode = UiPlaybackMode.IDLE;

    // Кэш иконок, чтобы избежать лишних инвалидаций и "мигания"
    private Drawable iconStart, iconPause, iconPlay, iconStop;

    public SpeechButtonsController(Activity activity) {
        this.activity = activity;
    }

    /** Привязать пункты меню (вызывать из onCreateOptionsMenu). */
    public void bind(Menu menu, int idToggle, int idStop, int idSkipBack, int idSkipFwd) {
        toggleItem   = menu.findItem(idToggle);
        stopItem     = menu.findItem(idStop);
        skipBackItem = menu.findItem(idSkipBack);
        skipFwdItem  = menu.findItem(idSkipFwd);
        ensureIcons();
        if (stopItem != null && iconStop != null) {
            stopItem.setIcon(iconStop);
        }
        render();
    }

    /** Пользователь нажал toggle. */
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

    /** Пользователь нажал stop. */
    public void onUserPressedStop() {
        mode = UiPlaybackMode.IDLE;
        render();
    }

    public UiPlaybackMode getMode() { return mode; }

    public void setMode(UiPlaybackMode newMode) {
        if (newMode != null) {
            mode = newMode;
            render();
        }
    }

    // ====== private ======

    private void ensureIcons() {
        if (iconStart == null) { iconStart = get(R.drawable.ic_radio_point); }
        if (iconPause == null) { iconPause = get(R.drawable.ic_pause); }
        if (iconPlay  == null) { iconPlay  = get(R.drawable.ic_play); }
        if (iconStop  == null) { iconStop  = get(R.drawable.ic_stop); }
    }

    private Drawable get(@DrawableRes int res) {
        Drawable d = activity.getDrawable(res);
        return d == null ? null : d.mutate();
    }

    private void setToggle(@DrawableRes int resIcon, @StringRes int contentDesc, boolean enabled) {
        Drawable d =
                (resIcon == R.drawable.ic_radio_point) ? iconStart :
                (resIcon == R.drawable.ic_pause)       ? iconPause :
                                                         iconPlay;
        if (d != null) {
            d.setAlpha(enabled ? 255 : 100);
            toggleItem.setIcon(d);
        }
        toggleItem.setTitle(activity.getString(contentDesc));
        toggleItem.setEnabled(enabled);
    }

    private void render() {
        if (toggleItem == null || stopItem == null) return;
        switch (mode) {
            case IDLE:
                setToggle(R.drawable.ic_radio_point, R.string.speech_toggle_content_start, true);
                stopItem.setVisible(false);
                stopItem.setEnabled(false);
                setSkipsVisible(false);
                break;
            case PLAYING:
                setToggle(R.drawable.ic_pause, R.string.speech_toggle_content_pause, true);
                stopItem.setVisible(true);
                stopItem.setEnabled(true);
                setSkipsVisible(true);
                break;
            case PAUSED:
                setToggle(R.drawable.ic_play, R.string.speech_toggle_content_resume, true);
                stopItem.setVisible(true);
                stopItem.setEnabled(true);
                setSkipsVisible(true);
                break;
        }
    }

    private void setSkipsVisible(boolean visible) {
        if (skipBackItem != null) { skipBackItem.setVisible(visible); skipBackItem.setEnabled(visible); }
        if (skipFwdItem  != null) { skipFwdItem.setVisible(visible);  skipFwdItem.setEnabled(visible); }
    }
}
