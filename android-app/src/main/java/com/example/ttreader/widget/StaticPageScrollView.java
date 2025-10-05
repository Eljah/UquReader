package com.example.ttreader.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * A ScrollView that disables user-driven scrolling and smooth-scroll animations so that
 * the reader page behaves like discrete pages instead of a continuous scroller.
 */
public class StaticPageScrollView extends ScrollView {
    public StaticPageScrollView(Context context) {
        super(context);
        init();
    }

    public StaticPageScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public StaticPageScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSmoothScrollingEnabled(false);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    public void fling(int velocityY) {
        // Disable fling to avoid any inertial motion when pages change programmatically.
    }

}
