package com.example.ttreader.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class UsageTimelineView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Float> positions = new ArrayList<>();

    private float strokeWidth;
    private float dotRadius;
    private float horizontalPadding;
    private OnEventClickListener eventClickListener;

    public UsageTimelineView(Context context) {
        this(context, null);
    }

    public UsageTimelineView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UsageTimelineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        strokeWidth = 1.5f * density;
        dotRadius = 4f * density;
        horizontalPadding = 12f * density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeWidth(strokeWidth);
        setColor(0xFF388E3C); // default green shade
    }

    public void setColor(int color) {
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(color);
        int lineColor = (color & 0x00FFFFFF) | 0x66000000;
        linePaint.setColor(lineColor);
        invalidate();
    }

    public void setEvents(List<Float> normalizedPositions) {
        positions.clear();
        if (normalizedPositions != null) {
            for (Float value : normalizedPositions) {
                if (value == null) continue;
                float clamped = Math.max(0f, Math.min(1f, value));
                positions.add(clamped);
            }
        }
        invalidate();
    }

    public void setOnEventClickListener(OnEventClickListener listener) {
        this.eventClickListener = listener;
        setClickable(listener != null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (eventClickListener == null || positions.isEmpty()) {
            return super.onTouchEvent(event);
        }
        boolean handled = super.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_UP) {
            int index = findEventIndex(event.getX());
            if (index >= 0) {
                performClick();
                eventClickListener.onEventClick(index);
                return true;
            }
        }
        return handled;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float startX = horizontalPadding;
        float endX = width - horizontalPadding;
        if (endX <= startX) {
            return;
        }
        float centerY = height / 2f;
        canvas.drawLine(startX, centerY, endX, centerY, linePaint);
        if (positions.isEmpty()) {
            return;
        }
        float usableWidth = endX - startX;
        for (Float position : positions) {
            if (position == null) continue;
            float x = startX + usableWidth * position;
            canvas.drawCircle(x, centerY, dotRadius, dotPaint);
        }
    }

    private int findEventIndex(float touchX) {
        float width = getWidth();
        float startX = horizontalPadding;
        float endX = width - horizontalPadding;
        if (endX <= startX) {
            return -1;
        }
        float usableWidth = endX - startX;
        float maxDistance = dotRadius * 2f;
        int closestIndex = -1;
        float closestDistance = maxDistance;
        for (int i = 0; i < positions.size(); i++) {
            Float position = positions.get(i);
            if (position == null) continue;
            float eventX = startX + usableWidth * position;
            float distance = Math.abs(eventX - touchX);
            if (distance <= closestDistance) {
                closestIndex = i;
                closestDistance = distance;
            }
        }
        return closestIndex;
    }

    public interface OnEventClickListener {
        void onEventClick(int index);
    }
}
