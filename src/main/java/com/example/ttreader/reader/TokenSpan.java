package com.example.ttreader.reader;

import android.graphics.Canvas;
import android.graphics.Paint;
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
