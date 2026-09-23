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
    // Measured at 30 fps in the official rabbitOS 2 Translator sequence.
    private static final long OPEN_MS = 800L, RETURN_MS = 1167L;
    private static final float EXPAND_MS = 300f, REVEAL_AT_MS = 633f;
    private static final float RETURN_APPEAR_MS = 167f, RETURN_SETTLED_MS = 467f;
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
                    Math.round(80f * context.getResources().getDisplayMetrics().widthPixels / 480f)));
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
        final long duration = exiting ? RETURN_MS : OPEN_MS;
        animator = ValueAnimator.ofFloat(0f, duration);
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator value) {
                if (!active) return;
                elapsed = (Float) value.getAnimatedValue();
                if (!exiting && elapsed >= REVEAL_AT_MS) reveal();
                if (home != null) home.setAlpha(clamp(elapsed / 180f));
                if (header != null) header.setAlpha(clamp((elapsed - RETURN_APPEAR_MS) / 100f));
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
            RectF full = new RectF(ox + 24f * scale, oy + 96f * scale,
                    ox + 456f * scale, oy + 596f * scale);
            float alpha;
            if (!exiting) {
                float growth = ease(elapsed / EXPAND_MS);
                bounds.set(mix(source.left, full.left, growth), mix(source.top, full.top, growth),
                        mix(source.right, full.right, growth), mix(source.bottom, full.bottom, growth));
                float revealing = clamp((elapsed - REVEAL_AT_MS) / (OPEN_MS - REVEAL_AT_MS));
                float zoom = 1f + .26f * ease(revealing);
                float centerX = bounds.centerX(), centerY = bounds.centerY();
                float halfWidth = bounds.width() * zoom / 2f, halfHeight = bounds.height() * zoom / 2f;
                bounds.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight);
                alpha = 1f - revealing;
                paint.setColor(Color.BLACK); paint.setAlpha(Math.round(255f * growth * alpha));
                canvas.drawRect(0, oy + 84f * scale, getWidth(), getHeight(), paint);
            } else {
                float arriving = ease((elapsed - RETURN_APPEAR_MS) / (RETURN_SETTLED_MS - RETURN_APPEAR_MS));
                float size = mix(.9f, 1f, arriving);
                float rise = full.height() * .6f * (1f - arriving);
                float halfWidth = full.width() * size / 2f, halfHeight = full.height() * size / 2f;
                bounds.set(full.centerX() - halfWidth, full.centerY() - halfHeight + rise,
                        full.centerX() + halfWidth, full.centerY() + halfHeight + rise);
                alpha = clamp((elapsed - RETURN_APPEAR_MS) / 100f);
                paint.setColor(Color.BLACK); paint.setAlpha(Math.round(255f * alpha));
                // The return header is a separate child above this face; cover
                // Home's centered battery/clock once the card takes ownership.
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
            int layer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255f * alpha));
            // The fuller owner-requested card can expand beyond the viewport;
            // keep the status header clear while it gives way to the feature.
            canvas.clipRect(0, oy + 84f * scale, getWidth(), getHeight());
            int cardClip = canvas.save();
            if (exiting) canvas.clipRect(full.left, full.top, full.right, full.bottom);
            renderer.drawTransitionFace(canvas, card, bounds);
            canvas.restoreToCount(cardClip);
            if (exiting && elapsed >= RETURN_SETTLED_MS) {
                paint.setColor(cueColor); paint.setAlpha(255);
                canvas.drawRoundRect(bounds.left + 4f * scale, bounds.bottom + 20f * scale,
                        bounds.right - 4f * scale, bounds.bottom + 36f * scale, 4f * scale, 4f * scale, paint);
            }
            canvas.restoreToCount(layer);
        }
    }
}
