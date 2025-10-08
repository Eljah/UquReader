package com.example.ttreader.reader;

import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;

import com.example.ttreader.model.Token;

public class TokenSpan extends CharacterStyle implements UpdateAppearance {
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

    @Override
    public void updateDrawState(TextPaint tp) {
        if (tp == null) {
            return;
        }
        if (lastAlpha > 0.03f) {
            int alpha = Math.round(Math.min(1f, Math.max(0f, (float) (lastAlpha * 0.18f))) * 255f);
            if (alpha > 0) {
                int baseColor = tp.getColor();
                tp.bgColor = (alpha << 24) | (baseColor & 0x00FFFFFF);
                return;
            }
        }
        tp.bgColor = 0;
    }
}
