package com.example.ttreader.ui;

import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.viewpager2.widget.ViewPager2;

/**
 * Centralizes paging controls state to keep menu and button states in sync even after rebind.
 * Additionally synchronizes ViewPager2 gesture availability with the current paging state.
 */
public final class PagingController {

    private final Activity activity;

    private boolean controlsEnabled = true;
    private boolean prevEnabled = true;
    private boolean nextEnabled = true;

    private MenuItem prevItem;
    private MenuItem nextItem;

    private View prevView;
    private View nextView;

    @Nullable
    private ViewPager2 pager;

    public PagingController(Activity activity) {
        this.activity = activity;
    }

    /**
     * Attach menu items. Safe to call repeatedly (e.g. after invalidateOptionsMenu).
     */
    public void bindMenu(Menu menu, @IdRes int prevId, @IdRes int nextId) {
        if (menu == null) {
            prevItem = null;
            nextItem = null;
        } else {
            prevItem = resolveMenuItem(menu, prevId);
            nextItem = resolveMenuItem(menu, nextId);
        }
        apply();
    }

    /** Attach paging buttons/views (call once after setContentView). */
    public void bindViews(View prev, View next) {
        prevView = prev;
        nextView = next;
        apply();
    }

    /** Locate and bind the first ViewPager2 found within the Activity content view hierarchy. */
    public void bindFirstViewPagerInContent() {
        View root = activity.findViewById(android.R.id.content);
        if (root instanceof ViewGroup) {
            pager = findFirstViewPager2((ViewGroup) root);
        } else {
            pager = null;
        }
        apply();
    }

    /** Explicitly set the ViewPager2 instance to keep in sync. */
    public void setPager(@Nullable ViewPager2 viewPager2) {
        pager = viewPager2;
        apply();
    }

    /** Update current availability and enabled state for paging controls. */
    public void updateState(boolean controlsEnabled, boolean prevEnabled, boolean nextEnabled) {
        boolean changed = this.controlsEnabled != controlsEnabled
                || this.prevEnabled != prevEnabled
                || this.nextEnabled != nextEnabled;
        this.controlsEnabled = controlsEnabled;
        this.prevEnabled = prevEnabled;
        this.nextEnabled = nextEnabled;
        if (changed) {
            apply();
        }
    }

    private void apply() {
        boolean prevActive = controlsEnabled && prevEnabled;
        boolean nextActive = controlsEnabled && nextEnabled;

        if (prevItem != null) {
            prevItem.setEnabled(prevActive);
            if (prevItem.getIcon() != null) {
                prevItem.getIcon().mutate().setAlpha(prevActive ? 255 : 100);
            }
        }
        if (nextItem != null) {
            nextItem.setEnabled(nextActive);
            if (nextItem.getIcon() != null) {
                nextItem.getIcon().mutate().setAlpha(nextActive ? 255 : 100);
            }
        }
        if (prevView != null) {
            prevView.setEnabled(prevActive);
            prevView.setAlpha(prevActive ? 1f : 0.3f);
        }
        if (nextView != null) {
            nextView.setEnabled(nextActive);
            nextView.setAlpha(nextActive ? 1f : 0.3f);
        }
        if (pager != null) {
            pager.setUserInputEnabled(controlsEnabled);
        }
    }

    private MenuItem resolveMenuItem(Menu menu, @IdRes int id) {
        if (menu == null) return null;
        if (id == View.NO_ID || id == 0) return null;
        return menu.findItem(id);
    }

    @Nullable
    private static ViewPager2 findFirstViewPager2(ViewGroup group) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewPager2) {
                return (ViewPager2) child;
            }
            if (child instanceof ViewGroup) {
                ViewPager2 nested = findFirstViewPager2((ViewGroup) child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}

