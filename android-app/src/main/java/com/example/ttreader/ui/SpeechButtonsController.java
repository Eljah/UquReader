package com.example.ttreader.ui;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import com.example.ttreader.R;

/**
 * Контроллер отображения кнопок озвучки.
 * Содержит только UI-логику (иконки, доступность, альфа-канал) для элементов меню.
 * Внешний код обязан вызывать {@link #setMode(UiPlaybackMode)} при изменении состояния.
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
        // Всегда видимы → никакого relayout при смене состояния
        if (stopItem != null) {
            stopItem.setVisible(true);
        }
        if (skipBackItem != null) {
            skipBackItem.setVisible(true);
        }
        if (skipFwdItem != null) {
            skipFwdItem.setVisible(true);
        }
        render();
    }

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
        if (toggleItem == null) return;
        switch (mode) {
            case IDLE: {
                setToggle(R.drawable.ic_radio_point, R.string.speech_toggle_content_start, true);
                setStopEnabled(false);
                setSkipsEnabled(false);
                break;
            }
            case PLAYING: {
                setToggle(R.drawable.ic_pause, R.string.speech_toggle_content_pause, true);
                setStopEnabled(true);
                setSkipsEnabled(true);
                break;
            }
            case PAUSED: {
                setToggle(R.drawable.ic_play, R.string.speech_toggle_content_resume, true);
                setStopEnabled(true);
                setSkipsEnabled(true);
                break;
            }
        }
    }

    private void setStopEnabled(boolean enabled) {
        if (stopItem == null) return;
        stopItem.setEnabled(enabled);
        final Drawable d = stopItem.getIcon();
        if (d != null) {
            d.setAlpha(enabled ? 255 : 100);
        }
    }

    private void setSkipsEnabled(boolean enabled) {
        if (skipBackItem != null) {
            skipBackItem.setEnabled(enabled);
            final Drawable d = skipBackItem.getIcon();
            if (d != null) d.setAlpha(enabled ? 255 : 100);
        }
        if (skipFwdItem  != null) {
            skipFwdItem.setEnabled(enabled);
            final Drawable d = skipFwdItem.getIcon();
            if (d != null) d.setAlpha(enabled ? 255 : 100);
        }
    }
}
