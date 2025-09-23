package com.example.ttreader.reader;

import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.TextView;

public class TokenGestureMovementMethod extends LinkMovementMethod {
    public interface Listener {
        void onTokenSingleTap(TokenSpan span);
        void onTokenLongPress(TokenSpan span);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Runnable longPressRunnable = this::triggerLongPress;
    private TokenSpan pendingSpan;
    private boolean longPressTriggered;
    private float downX;
    private float downY;
    private int touchSlop;

    public TokenGestureMovementMethod(Listener listener) {
        this.listener = listener;
    }

    @Override public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        int action = event.getActionMasked();
        if (touchSlop == 0) {
            touchSlop = ViewConfiguration.get(widget.getContext()).getScaledTouchSlop();
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                pendingSpan = findSpanAt(widget, buffer, (int) downX, (int) downY);
                longPressTriggered = false;
                if (pendingSpan != null) {
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pendingSpan != null && !longPressTriggered) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        cancelPendingCallbacks();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                TokenSpan span = pendingSpan;
                boolean wasLongPress = longPressTriggered;
                cancelPendingCallbacks();
                if (!wasLongPress && span != null) {
                    TokenSpan hit = findSpanAt(widget, buffer, (int) event.getX(), (int) event.getY());
                    if (hit == span && listener != null) {
                        listener.onTokenSingleTap(span);
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelPendingCallbacks();
                return true;
            default:
                return super.onTouchEvent(widget, buffer, event);
        }
    }

    private void triggerLongPress() {
        TokenSpan span = pendingSpan;
        pendingSpan = null;
        if (span != null && listener != null) {
            longPressTriggered = true;
            listener.onTokenLongPress(span);
        }
    }

    private void cancelPendingCallbacks() {
        handler.removeCallbacks(longPressRunnable);
        pendingSpan = null;
    }

    private TokenSpan findSpanAt(TextView widget, Spannable buffer, int x, int y) {
        Layout layout = widget.getLayout();
        if (layout == null) return null;
        int line = layout.getLineForVertical(y - widget.getTotalPaddingTop());
        int off = layout.getOffsetForHorizontal(line, x - widget.getTotalPaddingLeft());
        TokenSpan[] spans = buffer.getSpans(off, off, TokenSpan.class);
        return spans != null && spans.length > 0 ? spans[0] : null;
    }
}
