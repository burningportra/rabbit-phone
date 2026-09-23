package com.kevtrinh.rabbitphone;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Gesture parent shared by the page and its in-window cards/overlays. */
public final class NavigationSurface extends FrameLayout {
    public interface Listener {
        void onQuickSettings();
        void onQuickHome();
        void onOpenStack();
    }
    private Listener listener;
    private boolean home;
    private float downX, downY;
    private int pointer = -1;
    private boolean top, bottom, committed, blocked;
    private final float slop;

    public NavigationSurface(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setListener(Listener listener) { this.listener = listener; }
    public void setHome(boolean home) { this.home = home; }

    public static ViewGroup content(Activity activity) {
        ViewGroup host = activity.findViewById(android.R.id.content);
        if (host.getChildCount() == 1 && host.getChildAt(0) instanceof NavigationSurface) {
            return (NavigationSurface) host.getChildAt(0);
        }
        return host;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0);
                downX = event.getX(); downY = event.getY();
                top = downY <= getHeight() * .075f;
                bottom = downY >= getHeight() * .91f;
                committed = blocked = false;
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                blocked = true;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (committed) return true;
                if (blocked || pointer < 0 || listener == null) return false;
                int index = event.findPointerIndex(pointer);
                if (index < 0) { blocked = true; return false; }
                float dx = event.getX(index) - downX, dy = event.getY(index) - downY;
                if (Math.abs(dy) < Math.max(slop * 2f, getHeight() * .055f)
                        || Math.abs(dy) < Math.abs(dx) * 1.3f) return false;
                if (top && dy > 0) {
                    committed = true; listener.onQuickSettings(); return true;
                }
                if (bottom && dy < 0) {
                    committed = true; listener.onQuickHome(); return true;
                }
                if (home && dy < 0) {
                    committed = true; listener.onOpenStack(); return true;
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                pointer = -1;
                return committed;
            default: return committed;
        }
    }

    @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
        // A nested ScrollView may claim a drag before our directional threshold.
        // Reserve only gestures that began at the two system-style edge strips;
        // ordinary card drags, controls and sliders still keep their touch stream.
        if (disallow && pointer >= 0 && !blocked && (top || bottom)) {
            super.requestDisallowInterceptTouchEvent(false);
        } else {
            super.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (committed) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) pointer = -1;
            return true;
        }
        // Blank edge strips have no child touch target. Retain DOWN there and
        // recognize subsequent moves ourselves, otherwise ViewGroup never sees
        // a complete gesture on the exposed black canvas.
        if (!blocked && listener != null && (top || bottom || home)) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) onInterceptTouchEvent(event);
            return true;
        }
        return super.onTouchEvent(event);
    }
}
