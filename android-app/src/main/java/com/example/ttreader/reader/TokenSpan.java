package com.example.ttreader.reader;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;

import com.example.ttreader.model.Token;

public class TokenSpan extends ReplacementSpan {
    public final Token token;
    public final String featureKey;

    public float lastAlpha = 0f;
    private int startIndex = -1;
    private int endIndex = -1;

    public TokenSpan(Token token) {
        this.token = token;
        this.featureKey = token != null && token.morphology != null ? token.morphology.featureKey : null;
    }

    public void setCharacterRange(int start, int end) {
        this.startIndex = start;
        this.endIndex = end;
    }

    public int getStartIndex() { return startIndex; }

    public int getEndIndex() { return endIndex; }

    @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return (int) paint.measureText(text, start, end);
    }

    @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        int baseColor = paint.getColor();
        float segmentWidth = paint.measureText(text, start, end);

        if (lastAlpha > 0.03f) {
            int alpha = Math.round(Math.min(1f, Math.max(0f, (float) (lastAlpha * 0.18f))) * 255f);
            if (alpha > 0) {
                Paint backgroundPaint = new Paint(paint);
                int color = (alpha << 24) | (baseColor & 0x00FFFFFF);
                backgroundPaint.setColor(color);
                canvas.drawRect(x, top, x + segmentWidth, bottom, backgroundPaint);
            }
        }

        if (text instanceof Spanned) {
            Spanned spanned = (Spanned) text;
            float currentX = x;
            int segmentStart = start;
            while (segmentStart < end) {
                int segmentEnd = spanned.nextSpanTransition(segmentStart, end, ForegroundColorSpan.class);
                if (segmentEnd <= segmentStart) {
                    segmentEnd = end;
                }
                ForegroundColorSpan[] spans = spanned.getSpans(segmentStart, segmentEnd, ForegroundColorSpan.class);
                int segmentColor = baseColor;
                int bestPriority = Integer.MIN_VALUE;
                if (spans != null) {
                    for (ForegroundColorSpan span : spans) {
                        if (span == null) continue;
                        int flags = spanned.getSpanFlags(span);
                        int priority = (flags & Spanned.SPAN_PRIORITY) >>> Spanned.SPAN_PRIORITY_SHIFT;
                        if (priority > bestPriority || (priority == bestPriority && spanned.getSpanStart(span) >= segmentStart)) {
                            bestPriority = priority;
                            segmentColor = span.getForegroundColor();
                        }
                    }
                }
                paint.setColor(segmentColor);
                canvas.drawText(spanned, segmentStart, segmentEnd, currentX, y, paint);
                currentX += paint.measureText(spanned, segmentStart, segmentEnd);
                segmentStart = segmentEnd;
            }
            paint.setColor(baseColor);
        } else {
            canvas.drawText(text, start, end, x, y, paint);
        }
    }
}
