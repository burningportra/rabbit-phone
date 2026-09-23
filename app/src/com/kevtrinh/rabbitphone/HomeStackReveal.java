package com.kevtrinh.rabbitphone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** One-shot Home-to-stack reveal; the host retains input and destination ownership. */
public final class HomeStackReveal extends FrameLayout {
    private final View home;
    private final CardDeckView deck;
    private final View header;
    private ValueAnimator animator;
    private boolean requested, finished, laidOut;
    private int generation;
    private float progress;

    public HomeStackReveal(Context context, View homeBackdrop, CardDeckView deck, View header) {
        super(context);
        this.home = homeBackdrop;
        this.deck = deck;
        this.header = header;
        setBackgroundColor(Color.BLACK);
        home.setClickable(false);
        home.setLongClickable(false);
        home.setFocusable(false);
        home.setEnabled(false);
        home.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (home instanceof ViewGroup)
            ((ViewGroup) home).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        addView(home, new LayoutParams(-1, -1));
        deck.setAlpha(0f);
        addView(deck, new LayoutParams(-1, -1));
        header.setAlpha(0f);
        addView(header, new LayoutParams(-1,
                Math.round(80f * context.getResources().getDisplayMetrics().widthPixels / 480f)));
    }

    public boolean isRevealing() { return requested && !finished; }

    public void start() {
        if (requested || finished) return;
        requested = true;
        if (!ValueAnimator.areAnimatorsEnabled()) { finishReveal(); return; }
        tryStart();
    }

    private void tryStart() {
        if (!requested || finished || animator != null || !isAttachedToWindow()
                || !laidOut || getWidth() <= 0 || getHeight() <= 0
                || !isShown() || getWindowVisibility() != VISIBLE || !hasWindowFocus()) return;
        if (!ValueAnimator.areAnimatorsEnabled()) { finishReveal(); return; }
        final int run = ++generation;
        applyProgress(0f);
        deck.setAlpha(1f);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(333L);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator value) {
                if (!finished && generation == run) applyProgress((Float) value.getAnimatedValue());
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!finished && generation == run) finishReveal();
            }
        });
        animator.start();
    }

    private float scale() { return Math.min(getWidth() / 480f, getHeight() / 640f); }

    private void applyProgress(float value) {
        progress = value;
        float scale = scale();
        deck.setTranslationY((getHeight() - 132f * scale) * (1f - value));
        home.setTranslationY(-180f * scale * value);
        home.setAlpha(1f - value);
        header.setAlpha(value);
    }

    /** Terminal, including before start/layout: pending callbacks cannot restart the reveal. */
    public void finishReveal() {
        if (finished) return;
        finished = true;
        requested = false;
        generation++;
        ValueAnimator old = animator;
        animator = null;
        if (old != null) {
            old.removeAllUpdateListeners();
            old.removeAllListeners();
            old.cancel();
        }
        progress = 1f;
        deck.setTranslationY(0f);
        deck.setAlpha(1f);
        header.setAlpha(1f);
        if (home.getParent() == this) removeView(home);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        laidOut = true;
        if (!finished) applyProgress(progress);
        tryStart();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        LayoutParams params = (LayoutParams) header.getLayoutParams();
        int headerHeight = Math.round(80f * scale());
        if (params.height != headerHeight) {
            params.height = headerHeight;
            header.setLayoutParams(params);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        tryStart();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && requested) finishReveal();
        else if (hasFocus) tryStart();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (requested && !isShown()) finishReveal();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (requested && visibility != VISIBLE) finishReveal();
    }

    @Override protected void onDetachedFromWindow() {
        finishReveal();
        super.onDetachedFromWindow();
    }
}
