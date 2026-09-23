package com.kevtrinh.rabbitphone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import java.util.IdentityHashMap;
import java.util.Map;

/** Transient card face in the existing window. It never owns hardware or feature state. */
public final class CardTransition extends FrameLayout {
    public interface Listener {
        void onReveal(CardTransition transition);
        void onFinished(CardTransition transition);
        void onCanceled(CardTransition transition);
    }
    private final boolean exiting;
    private final NavigationCard card;
    private final RectF source;
    private final int cueColor;
    private final Listener listener;
    private final CardDeckView renderer;
    private final View home;
    private final View header;
    private final Face face;
    private ValueAnimator animator;
    private boolean active, revealed, revealing, moving;
    private float elapsed;
    private final IdentityHashMap<View, Integer> siblingAccessibility = new IdentityHashMap<>();

    public CardTransition(Context context, NavigationCard card, RectF source, int cueColor,
            View homeBackdrop, View cardHeader, Listener listener) {
        super(context);
        this.card = card; this.source = new RectF(source); this.cueColor = cueColor;
        this.listener = listener; this.home = homeBackdrop; this.header = cardHeader; exiting = homeBackdrop != null;
        renderer = new CardDeckView(context);
        setClickable(true); setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (home != null) {
            home.setAlpha(0f);
            addView(home, new FrameLayout.LayoutParams(-1, -1));
        }
        face = new Face(context);
        addView(face, new FrameLayout.LayoutParams(-1, -1));
        if (header != null) {
            header.setAlpha(0f);
            addView(header, new FrameLayout.LayoutParams(-1,
                    Math.round(96f * context.getResources().getDisplayMetrics().widthPixels / 480f)));
        }
    }

    public boolean isExiting() { return exiting; }
    public boolean isRevealing() { return revealing; }
    public boolean hasRevealed() { return revealed; }

    public void attachTo(ViewGroup parent) {
        if (getParent() != parent) {
            restoreSiblingAccessibility();
            moving = true;
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            parent.addView(this, new ViewGroup.LayoutParams(-1, -1));
            moving = false;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View sibling = parent.getChildAt(i);
            if (sibling == this) continue;
            if (!siblingAccessibility.containsKey(sibling))
                siblingAccessibility.put(sibling, sibling.getImportantForAccessibility());
            sibling.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        bringToFront();
    }

    public void start() {
        if (active) return;
        active = true;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            if (!exiting) reveal();
            finish();
            return;
        }
        final long duration = exiting ? 1200L : 720L;
        animator = ValueAnimator.ofFloat(0f, duration);
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator value) {
                if (!active) return;
                elapsed = (Float) value.getAnimatedValue();
                if (!exiting && elapsed >= 600f) reveal();
                if (home != null) home.setAlpha(clamp(elapsed / 180f));
                if (header != null) header.setAlpha(clamp((elapsed - 80f) / 120f)
                        * (1f - ease((elapsed - 1050f) / 150f)));
                face.invalidate();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ignored) {
                if (!active) return;
                if (!exiting) reveal();
                finish();
            }
        });
        animator.start();
    }

    private void reveal() {
        if (!active || revealed) return;
        revealed = true; revealing = true;
        // A same-window feature may acquire its own modal layer during reveal.
        // Release this layer first so nested overlays save the real prior flags.
        restoreSiblingAccessibility();
        listener.onReveal(this);
        revealing = false;
    }

    private void finish() {
        if (!active) return;
        active = false; animator = null;
        remove();
        listener.onFinished(this);
    }

    public void cancel() {
        if (!active) { remove(); return; }
        active = false;
        ValueAnimator old = animator; animator = null;
        if (old != null) old.cancel();
        remove();
        listener.onCanceled(this);
    }

    private void remove() {
        restoreSiblingAccessibility();
        moving = true;
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        moving = false;
    }

    private void restoreSiblingAccessibility() {
        for (Map.Entry<View, Integer> saved : siblingAccessibility.entrySet()) {
            if (saved.getKey().getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                saved.getKey().setImportantForAccessibility(saved.getValue());
        }
        siblingAccessibility.clear();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
    @Override public boolean onTouchEvent(MotionEvent event) { return true; }
    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!moving && active && !revealing) cancel();
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static float ease(float value) { float p = 1f - clamp(value); return 1f - p * p * p; }
    private static float mix(float a, float b, float fraction) { return a + (b - a) * fraction; }

    private final class Face extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        Face(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float scale = Math.min(getWidth() / 480f, getHeight() / 640f);
            if (scale <= 0f) return;
            float ox = (getWidth() - 480f * scale) / 2f, oy = (getHeight() - 640f * scale) / 2f;
            // Feature entry uses a centered face, distinct from a live card in the hand.
            RectF full = new RectF(ox + 88f * scale, oy + 118f * scale,
                    ox + 388f * scale, oy + 508f * scale);
            float alpha;
            if (!exiting) {
                float growth = ease(elapsed / 120f);
                bounds.set(mix(source.left, full.left, growth), mix(source.top, full.top, growth),
                        mix(source.right, full.right, growth), mix(source.bottom, full.bottom, growth));
                alpha = elapsed < 600f ? 1f : 1f - clamp((elapsed - 600f) / 120f);
                paint.setColor(Color.BLACK); paint.setAlpha(Math.round(255f * growth * alpha));
                canvas.drawRect(0, oy + 112f * scale, getWidth(), getHeight(), paint);
            } else {
                float arriving = ease((elapsed - 80f) / 180f);
                float departing = ease((elapsed - 1050f) / 150f);
                float size = mix(.82f, 1f, arriving) * mix(1f, .88f, departing);
                float halfWidth = full.width() * size / 2f, halfHeight = full.height() * size / 2f;
                bounds.set(full.centerX() - halfWidth, full.centerY() - halfHeight,
                        full.centerX() + halfWidth, full.centerY() + halfHeight);
                alpha = clamp((elapsed - 80f) / 120f) * (1f - departing);
                paint.setColor(Color.BLACK); paint.setAlpha(Math.round(255f * alpha));
                canvas.drawRect(0, oy + 112f * scale, getWidth(), getHeight(), paint);
            }
            int layer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255f * alpha));
            renderer.drawTransitionFace(canvas, card, bounds);
            if (exiting && bounds.height() >= 380f * scale) {
                paint.setColor(cueColor); paint.setAlpha(255);
                canvas.drawRoundRect(bounds.left + 4f * scale, bounds.bottom + 30f * scale,
                        bounds.right - 4f * scale, bounds.bottom + 42f * scale, 4f * scale, 4f * scale, paint);
            }
            canvas.restoreToCount(layer);
        }
    }
}
