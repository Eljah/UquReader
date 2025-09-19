package com.example.UquReader.reader;

import android.os.Handler;
import android.text.Layout;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

public class LongPressMovementMethod extends LinkMovementMethod {
    public interface OnLongPressTokenListener { void onLongPress(TokenSpan span); }

    private final Handler handler = new Handler();
    private OnLongPressTokenListener listener;

    public LongPressMovementMethod(OnLongPressTokenListener l) { this.listener = l; }

    @Override public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            schedule(widget, buffer, event);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            handler.removeCallbacksAndMessages(null);
        }
        return true;
    }

    private void schedule(TextView widget, Spannable buffer, MotionEvent event) {
        final int x = (int)event.getX();
        final int y = (int)event.getY();
        handler.postDelayed(() -> {
            TokenSpan hit = findSpanAt(widget, buffer, x, y);
            if (hit != null && listener != null) listener.onLongPress(hit);
        }, 350);
    }

    private TokenSpan findSpanAt(TextView widget, Spannable buffer, int x, int y) {
        Layout layout = widget.getLayout();
        if (layout == null) return null;
        int line = layout.getLineForVertical(y - widget.getTotalPaddingTop());
        int off = layout.getOffsetForHorizontal(line, x - widget.getTotalPaddingLeft());
        TokenSpan[] spans = buffer.getSpans(off, off, TokenSpan.class);
        return spans != null && spans.length>0 ? spans[0] : null;
    }
}
