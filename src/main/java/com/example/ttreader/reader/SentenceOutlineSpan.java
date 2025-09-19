package com.example.ttreader.reader;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.LineBackgroundSpan;

public class SentenceOutlineSpan implements LineBackgroundSpan {
    private final int color;
    private final float strokeWidth;
    private final float cornerRadius;
    private final RectF rect = new RectF();

    public SentenceOutlineSpan(int color, float strokeWidth, float cornerRadius) {
        this.color = color;
        this.strokeWidth = strokeWidth;
        this.cornerRadius = cornerRadius;
    }

    @Override
    public void drawBackground(Canvas canvas, Paint paint, int left, int right, int top, int baseline, int bottom,
                               CharSequence text, int start, int end, int lineNumber) {
        Paint.Style originalStyle = paint.getStyle();
        int originalColor = paint.getColor();
        float originalStrokeWidth = paint.getStrokeWidth();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(color);

        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);

        paint.setStyle(originalStyle);
        paint.setColor(originalColor);
        paint.setStrokeWidth(originalStrokeWidth);
    }
}
