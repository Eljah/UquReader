package com.example.ttreader.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.model.UsageEvent;

import java.util.ArrayList;
import java.util.List;

public class TimelineView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint exposurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lookupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<UsageEvent> events = new ArrayList<>();
    private long rangeStart = 0L;
    private long rangeEnd = 0L;
    private float lineWidth;
    private float dotRadius;

    public TimelineView(Context context) {
        super(context);
        init();
    }

    public TimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TimelineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        lineWidth = density;
        dotRadius = 4f * density;

        linePaint.setColor(0xFFBDBDBD);
        linePaint.setStrokeWidth(lineWidth);
        exposurePaint.setColor(0xFF2E7D32);
        exposurePaint.setStyle(Paint.Style.FILL);
        lookupPaint.setColor(0xFFC62828);
        lookupPaint.setStyle(Paint.Style.FILL);

        setWillNotDraw(false);
    }

    public void setEvents(List<UsageEvent> newEvents, long start, long end) {
        events.clear();
        if (newEvents != null) {
            events.addAll(newEvents);
        }
        rangeStart = start;
        rangeEnd = end;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float startX = getPaddingLeft();
        float centerY = getPaddingTop() + availableHeight / 2f;
        canvas.drawLine(startX, centerY, startX + availableWidth, centerY, linePaint);
        if (events.isEmpty() || availableWidth <= 0) {
            return;
        }
        long effectiveStart = rangeStart;
        long effectiveEnd = rangeEnd;
        if (effectiveEnd <= effectiveStart) {
            effectiveEnd = effectiveStart + 1;
        }
        for (UsageEvent event : events) {
            double fraction = (event.timestampMs - effectiveStart) / (double) (effectiveEnd - effectiveStart);
            float clamped = (float) Math.max(0.0, Math.min(1.0, fraction));
            float cx = startX + clamped * availableWidth;
            boolean isLookup = UsageStatsDao.EVENT_LOOKUP.equals(event.eventType)
                    || UsageStatsDao.EVENT_FEATURE.equals(event.eventType);
            Paint paint = isLookup ? lookupPaint : exposurePaint;
            canvas.drawCircle(cx, centerY, dotRadius, paint);
        }
    }
}
