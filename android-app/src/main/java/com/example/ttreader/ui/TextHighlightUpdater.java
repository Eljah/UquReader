package com.example.ttreader.ui;

import android.text.Spannable;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Updates a span range on the next animation frame, avoiding repeated setText()/relayout cycles.
 */
public final class TextHighlightUpdater {
    private final View hostView;
    private final Object span;
    private final int spanFlags;
    private final Runnable applyRunnable = this::applyNow;

    private Spannable text;
    private int pendingStart = -1;
    private int pendingEnd = -1;
    private int lastStart = -1;
    private int lastEnd = -1;
    private boolean posted;

    public TextHighlightUpdater(@NonNull View hostView, @NonNull Spannable text,
            @NonNull Object span, int spanFlags) {
        this.hostView = hostView;
        this.text = text;
        this.span = span;
        this.spanFlags = spanFlags;
    }

    /** Schedule span update to be applied on the next animation frame. */
    public void request(int start, int end) {
        pendingStart = start;
        pendingEnd = end;
        if (!posted) {
            posted = true;
            hostView.postOnAnimation(applyRunnable);
        }
    }

    /** Remove any active span immediately and reset state. */
    public void clear() {
        if (posted) {
            hostView.removeCallbacks(applyRunnable);
            posted = false;
        }
        if (text != null) {
            text.removeSpan(span);
        }
        lastStart = -1;
        lastEnd = -1;
        pendingStart = -1;
        pendingEnd = -1;
        hostView.invalidate();
    }

    /** Return the spannable managed by this updater. */
    public Spannable getText() {
        return text;
    }

    private void applyNow() {
        posted = false;
        Spannable target = text;
        if (target == null) {
            return;
        }
        int start = pendingStart;
        int end = pendingEnd;
        if (start == lastStart && end == lastEnd) {
            return;
        }
        target.removeSpan(span);
        if (start >= 0 && end > start) {
            target.setSpan(span, start, end, spanFlags);
        }
        lastStart = start;
        lastEnd = end;
        hostView.invalidate();
    }
}

