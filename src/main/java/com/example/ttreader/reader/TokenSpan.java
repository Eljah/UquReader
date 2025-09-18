package com.example.UquReader.reader;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.ReplacementSpan;

public class TokenSpan extends ReplacementSpan {
    public final String surface;
    public final String lemma;
    public final String pos;
    public final String featureKey;

    public float lastAlpha = 0f;

    public TokenSpan(String surface, String lemma, String pos, String featureKey) {
        this.surface = surface; this.lemma = lemma; this.pos = pos; this.featureKey = featureKey;
    }

    @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return (int) paint.measureText(text, start, end);
    }

    @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        if (lastAlpha > 0.03f) {
            int old = paint.getColor();
            int bg = (int)((lastAlpha * 0.18f) * 255) & 0xFF;
            int color = (bg << 24) | (old & 0x00FFFFFF);
            Paint p = new Paint(paint);
            p.setColor(color);
            float w = paint.measureText(text, start, end);
            canvas.drawRect(x, top, x + w, bottom, p);
        }
        canvas.drawText(text, start, end, x, y, paint);
    }
}
