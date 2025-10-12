package com.example.ttreader.util;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Wraps a drawable and logs draw / invalidation events for debugging UI rendering behaviour.
 */
public class LoggingDrawableWrapper extends Drawable implements Drawable.Callback {
    private static final String TAG = "SpeechIconDraw";

    private final Drawable delegate;
    private final String name;

    private LoggingDrawableWrapper(@NonNull Drawable delegate, @NonNull String name) {
        this.delegate = delegate;
        this.name = name;
        this.delegate.setCallback(this);
        copyBounds(delegate);
    }

    /**
     * Wraps the provided drawable with a logging wrapper. If the drawable is already wrapped the
     * same instance is returned.
     */
    @Nullable
    public static Drawable wrap(@Nullable Drawable drawable, @NonNull String name) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof LoggingDrawableWrapper) {
            return drawable;
        }
        return new LoggingDrawableWrapper(drawable, name);
    }

    /**
     * Exposes the identifier used for logging so tests can assert the expected drawable is active.
     */
    public String getDebugName() {
        return name;
    }

    private void log(String message) {
        Log.d(TAG, name + ": " + message);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        log("draw");
        delegate.draw(canvas);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        delegate.setBounds(bounds);
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        delegate.setBounds(left, top, right, bottom);
    }

    @Override
    public void setBounds(@NonNull Rect bounds) {
        super.setBounds(bounds);
        delegate.setBounds(bounds);
    }

    @Override
    public void setAlpha(int alpha) {
        delegate.setAlpha(alpha);
    }

    @Override
    public int getAlpha() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return delegate.getAlpha();
        }
        return super.getAlpha();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        delegate.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        int opacity = delegate.getOpacity();
        return opacity == PixelFormat.UNKNOWN ? PixelFormat.TRANSLUCENT : opacity;
    }

    @Override
    public int getIntrinsicWidth() {
        return delegate.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return delegate.getIntrinsicHeight();
    }

    @Override
    public int getMinimumWidth() {
        return delegate.getMinimumWidth();
    }

    @Override
    public int getMinimumHeight() {
        return delegate.getMinimumHeight();
    }

    @Override
    public boolean isStateful() {
        return delegate.isStateful();
    }

    @Override
    public boolean setState(@NonNull int[] stateSet) {
        boolean changed = delegate.setState(stateSet);
        if (changed) {
            log("setState -> " + delegate);
        }
        return changed;
    }

    @Override
    public int[] getState() {
        return delegate.getState();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = delegate.setVisible(visible, restart);
        if (changed) {
            log("setVisible -> " + visible + ", restart=" + restart);
        }
        return changed;
    }

    @Override
    public int getChangingConfigurations() {
        return delegate.getChangingConfigurations();
    }

    @Override
    public void setChangingConfigurations(int configs) {
        delegate.setChangingConfigurations(configs);
    }

    @Override
    public void invalidateSelf() {
        super.invalidateSelf();
        log("invalidateSelf");
    }

    @Override
    public void scheduleSelf(@NonNull Runnable what, long when) {
        super.scheduleSelf(what, when);
        log("scheduleSelf at " + when);
    }

    @Override
    public void unscheduleSelf(@NonNull Runnable what) {
        super.unscheduleSelf(what);
        log("unscheduleSelf");
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        log("invalidateDrawable from delegate");
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        log("scheduleDrawable from delegate at " + when);
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        log("unscheduleDrawable from delegate");
        unscheduleSelf(what);
    }

    @Override
    public void applyTheme(@NonNull Resources.Theme t) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            delegate.applyTheme(t);
        }
    }

    @Override
    public boolean canApplyTheme() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && delegate.canApplyTheme();
    }

    @Nullable
    @Override
    public ConstantState getConstantState() {
        final ConstantState state = delegate.getConstantState();
        if (state == null) {
            return null;
        }
        return new ConstantState() {
            @Override
            public @NonNull Drawable newDrawable() {
                Drawable newDelegate = state.newDrawable();
                return new LoggingDrawableWrapper(newDelegate, name);
            }

            @Override
            public @NonNull Drawable newDrawable(Resources res) {
                Drawable newDelegate = state.newDrawable(res);
                return new LoggingDrawableWrapper(newDelegate, name);
            }

            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public @NonNull Drawable newDrawable(Resources res, Resources.Theme theme) {
                Drawable newDelegate = state.newDrawable(res, theme);
                return new LoggingDrawableWrapper(newDelegate, name);
            }

            @Override
            public int getChangingConfigurations() {
                return state.getChangingConfigurations();
            }
        };
    }

    private void copyBounds(@NonNull Drawable target) {
        Rect bounds = target.getBounds();
        if (!bounds.isEmpty()) {
            setBounds(bounds);
        }
    }
}
