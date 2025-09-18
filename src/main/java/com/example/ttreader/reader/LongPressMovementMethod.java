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

    @Override public boolean onTouchEvent(View widget, Spannable buffer, MotionEvent event) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            schedule(widget, buffer, event);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            handler.removeCallbacksAndMessages(null);
        }
        return true;
    }

    private void schedule(View widget, Spannable buffer, MotionEvent event) {
        final int x = (int)event.getX();
        final int y = (int)event.getY();
        handler.postDelayed(() -> {
            TokenSpan hit = findSpanAt(widget, buffer, x, y);
            if (hit != null && listener != null) listener.onLongPress(hit);
        }, 350);
    }

    private TokenSpan findSpanAt(View widget, Spannable buffer, int x, int y) {
        TextView tv = (TextView) widget;
        Layout layout = tv.getLayout();
        if (layout == null) return null;
        int line = layout.getLineForVertical(y - tv.getTotalPaddingTop());
        int off = layout.getOffsetForHorizontal(line, x - tv.getTotalPaddingLeft());
        TokenSpan[] spans = buffer.getSpans(off, off, TokenSpan.class);
        return spans != null && spans.length>0 ? spans[0] : null;
    }
}
