package com.example.ttreader.ui;

import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.IdRes;

/**
 * Centralizes paging controls state to keep menu and button states in sync even after rebind.
 */
public final class PagingController {

    private boolean controlsEnabled = true;
    private boolean prevEnabled = true;
    private boolean nextEnabled = true;

    private MenuItem prevItem;
    private MenuItem nextItem;

    private View prevView;
    private View nextView;

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
    }

    private MenuItem resolveMenuItem(Menu menu, @IdRes int id) {
        if (menu == null) return null;
        if (id == View.NO_ID || id == 0) return null;
        return menu.findItem(id);
    }
}

