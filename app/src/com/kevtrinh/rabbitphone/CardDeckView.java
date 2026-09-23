package com.kevtrinh.rabbitphone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Rabbit-style card hand in normalized physical-screen coordinates (not density-scaled dp).
 * The host owns destinations, haptics, edge gestures, and removal of active tasks. */
public final class CardDeckView extends View {
    public interface Listener {
        void onSelectionChanged(int index);
        void onActivate(int index);
        void onDismiss(int index);
    }

    // Reference geometry: tune this group without changing touch/selection behavior.
    private static final float SCREEN_WIDTH = 480f, SCREEN_HEIGHT = 640f;
    private static final float CARD_LEFT = 68f, CARD_WIDTH = 326f, CARD_HEIGHT = 440f;
    private static final float SELECTED_TOP = 190f, SELECTED_REVEAL = 112f, HEADER_STEP = 44f;
    private static final float ACTIVE_TOP = 96f, ACTIVE_REVEAL = 470f;
    private static final float CLIP_TOP = 88f, CLIP_BOTTOM = 584f, CORNER_RADIUS = 16f;
    private static final float EDGE_GUARD = 32f, DRAG_STEP = 118f;
    private static final long SETTLE_MS = 180L;
    private static final int UNDECIDED = 0, VERTICAL = 1, DISMISS = 2, BLOCKED = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyphPath = new Path();
    private final RectF scratch = new RectF();
    private final int touchSlop;
    private final int maxVelocity;
    private final DecelerateInterpolator settling = new DecelerateInterpolator(1.5f);
    private List<NavigationCard> cards = Collections.emptyList();
    private Listener listener;
    private int selected = -1;
    private float position;
    private float dismissOffset;
    private int dismissIndex = -1;
    private ValueAnimator animator;
    private VelocityTracker velocity;
    private boolean attached;
    private boolean consuming;
    private boolean tracking;
    private boolean moved;
    private int pointer = -1, pressedIndex = -1, dragAxis;
    private String pressedId;
    private float downX, downY, startPosition;
    private int accessibilityFocus = -1;
    private int hoveredVirtual = -1;
    private final int[] screenLocation = new int[2];
    private final AccessibilityNodeProvider accessibility = new AccessibilityNodeProvider() {
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualId) {
            if (virtualId == HOST_VIEW_ID) {
                AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(CardDeckView.this);
                onInitializeAccessibilityNodeInfo(info);
                for (int i = 0; i < cards.size(); i++)
                    if (!getCardBounds(i).isEmpty()) info.addChild(CardDeckView.this, i + 1);
                return info;
            }
            int index = virtualId - 1;
            RectF visible = getCardBounds(index);
            if (visible.isEmpty()) return null;
            NavigationCard card = cards.get(index);
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
            info.setSource(CardDeckView.this, virtualId);
            info.setParent(CardDeckView.this);
            info.setPackageName(getContext().getPackageName());
            info.setClassName("android.widget.Button");
            info.setText(card.title);
            if (card.active) info.setContentDescription(card.title + ", active");
            info.setEnabled(isEnabled());
            info.setVisibleToUser(isShown() && getWindowVisibility() == VISIBLE);
            info.setClickable(true);
            info.setFocusable(true);
            info.setSelected(index == selected);
            info.setAccessibilityFocused(accessibilityFocus == virtualId);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            info.addAction(accessibilityFocus == virtualId
                    ? AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    : AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
            Rect bounds = new Rect();
            visible.roundOut(bounds);
            info.setBoundsInParent(bounds);
            getLocationOnScreen(screenLocation);
            bounds.offset(screenLocation[0], screenLocation[1]);
            info.setBoundsInScreen(bounds);
            return info;
        }

        @Override public boolean performAction(int virtualId, int action, Bundle arguments) {
            if (virtualId == HOST_VIEW_ID) return performAccessibilityAction(action, arguments);
            int index = virtualId - 1;
            if (!isEnabled() || !isShown() || getCardBounds(index).isEmpty()) return false;
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                String id = cards.get(index).id;
                finishTouch();
                selectFromUser(index);
                settle(true);
                if (selected == index && sameCard(index, id)) {
                    sendVirtualEvent(virtualId, AccessibilityEvent.TYPE_VIEW_CLICKED);
                    return performClick();
                }
                return false;
            }
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS && accessibilityFocus != virtualId) {
                clearVirtualFocus();
                accessibilityFocus = virtualId;
                sendVirtualEvent(virtualId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS && accessibilityFocus == virtualId) {
                clearVirtualFocus();
                return true;
            }
            return false;
        }

        @Override public AccessibilityNodeInfo findFocus(int focus) {
            return focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && accessibilityFocus > 0
                    ? createAccessibilityNodeInfo(accessibilityFocus) : null;
        }
    };

    public CardDeckView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        labelPaint.setTypeface(RabbitTypography.regular(context));
        labelPaint.setColor(Color.BLACK);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        maxVelocity = configuration.getScaledMaximumFlingVelocity();
        updateDescription();
    }

    public void setListener(Listener value) { listener = value; }

    /** Copies the supplied order; neither this setter nor setSelection invokes the listener. */
    public void setCards(List<NavigationCard> values, int selection) {
        if (values == null) throw new IllegalArgumentException("Cards are required");
        ArrayList<NavigationCard> copy = new ArrayList<NavigationCard>(values.size());
        HashSet<String> ids = new HashSet<String>();
        for (NavigationCard card : values) {
            if (card == null || !ids.add(card.id)) throw new IllegalArgumentException("Card IDs must be unique");
            copy.add(card);
        }
        stopInteraction();
        cards = Collections.unmodifiableList(copy);
        selected = clampSelection(selection);
        position = Math.max(0, selected);
        updateDescription();
        invalidate();
    }

    public void setSelection(int index, boolean animate) {
        finishTouch();
        selected = clampSelection(index);
        updateDescription();
        settle(animate);
    }

    public int getSelection() { return selected; }

    /** Visible exposed portion in view pixels, suitable for hit targets/diagnostics.
     * Returns an empty rectangle for absent, fully covered, or clipped cards. */
    public RectF getCardBounds(int index) {
        RectF result = new RectF();
        if (index < 0 || index >= cards.size() || scale() <= 0f) return result;
        visibleBounds(index, result);
        if (!result.isEmpty()) {
            float scale = scale();
            result.set(originX() + result.left * scale, originY() + result.top * scale,
                    originX() + result.right * scale, originY() + result.bottom * scale);
        }
        return result;
    }

    private int clampSelection(int index) {
        return cards.isEmpty() ? -1 : Math.max(0, Math.min(cards.size() - 1, index));
    }

    private float scale() {
        return Math.max(0f, Math.min((getWidth() - getPaddingLeft() - getPaddingRight()) / SCREEN_WIDTH,
                (getHeight() - getPaddingTop() - getPaddingBottom()) / SCREEN_HEIGHT));
    }

    private float originX() {
        return getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - SCREEN_WIDTH * scale()) / 2f;
    }

    private float originY() {
        return getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - SCREEN_HEIGHT * scale()) / 2f;
    }

    private float cardTop(int index) {
        // An opened card is a full face, with the next catalog card peeking
        // below. Interpolate this expansion from the actual scroll position,
        // so a wheel interruption never changes the layout discontinuously.
        float bounded = Math.max(0f, Math.min(Math.max(0, cards.size() - 1), position));
        int before = (int) bounded, after = Math.min(cards.size() - 1, before + 1);
        float openedBefore = !cards.isEmpty() && cards.get(before).active ? 1f : 0f;
        float openedAfter = after >= 0 && cards.get(after).active ? 1f : 0f;
        float expansion = openedBefore + (openedAfter - openedBefore) * (bounded - before);
        float selectedTop = SELECTED_TOP + (ACTIVE_TOP - SELECTED_TOP) * expansion;
        float reveal = SELECTED_REVEAL + (ACTIVE_REVEAL - SELECTED_REVEAL) * expansion;
        float relative = index - position;
        if (relative <= 0f) return selectedTop + relative * HEADER_STEP;
        if (relative < 1f) return selectedTop + relative * reveal;
        return selectedTop + reveal + (relative - 1f) * HEADER_STEP;
    }

    private void visibleBounds(int index, RectF bounds) {
        float top = cardTop(index);
        float bottom = Math.min(CLIP_BOTTOM, top + CARD_HEIGHT);
        if (index + 1 < cards.size()) bottom = Math.min(bottom, cardTop(index + 1));
        float left = CARD_LEFT + (index == dismissIndex ? dismissOffset : 0f);
        bounds.set(Math.max(0f, left), Math.max(CLIP_TOP, top),
                Math.min(SCREEN_WIDTH, left + CARD_WIDTH), bottom);
        if (bounds.isEmpty()) bounds.setEmpty();
    }

    private int hitCard(float x, float y) {
        float scale = scale();
        if (scale <= 0f) return -1;
        x = (x - originX()) / scale;
        y = (y - originY()) / scale;
        for (int i = cards.size() - 1; i >= 0; i--) {
            visibleBounds(i, scratch);
            if (scratch.contains(x, y)) return i;
        }
        return -1;
    }

    private void selectFromUser(int index) {
        int next = clampSelection(index);
        if (next == selected) return;
        selected = next;
        updateDescription();
        if (listener != null) listener.onSelectionChanged(next);
    }

    private void updateDescription() {
        setContentDescription(selected < 0 ? "App cards, empty" : cards.get(selected).title
                + ", card " + (selected + 1) + " of " + cards.size()
                + (cards.get(selected).active ? ", active" : ""));
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return accessibility; }

    private void sendVirtualEvent(int id, int type) {
        if (getParent() == null) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setSource(this, id);
        event.setPackageName(getContext().getPackageName());
        event.setClassName("android.widget.Button");
        getParent().requestSendAccessibilityEvent(this, event);
    }

    private void clearVirtualFocus() {
        if (accessibilityFocus < 0) return;
        int previous = accessibilityFocus;
        accessibilityFocus = -1;
        sendVirtualEvent(previous, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
    }

    private void updateVirtualHover(int id) {
        if (id == hoveredVirtual) return;
        int previous = hoveredVirtual;
        hoveredVirtual = id;
        if (id > 0) sendVirtualEvent(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        if (previous > 0) sendVirtualEvent(previous, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        AccessibilityManager manager = getContext().getSystemService(AccessibilityManager.class);
        if (manager != null && manager.isEnabled() && manager.isTouchExplorationEnabled()) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
                int hit = hitCard(event.getX(), event.getY());
                updateVirtualHover(hit < 0 ? -1 : hit + 1);
                if (hit >= 0) return true;
            } else if (action == MotionEvent.ACTION_HOVER_EXIT && hoveredVirtual > 0) {
                updateVirtualHover(-1);
                return true;
            }
        }
        return super.dispatchHoverEvent(event);
    }

    private boolean canAnimate() {
        return attached && isShown() && getWindowVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled();
    }

    private void cancelAnimation() {
        if (animator == null) return;
        ValueAnimator previous = animator;
        animator = null;
        previous.removeAllUpdateListeners();
        previous.removeAllListeners();
        previous.cancel();
    }

    private void announceSettledBounds() {
        if (accessibilityFocus > 0 && getCardBounds(accessibilityFocus - 1).isEmpty()) clearVirtualFocus();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    private void settle(boolean animate) {
        cancelAnimation();
        final float target = Math.max(0, selected);
        if (!animate || !canAnimate() || (Math.abs(position - target) < .001f && Math.abs(dismissOffset) < .1f)) {
            position = target;
            dismissOffset = 0f;
            dismissIndex = -1;
            invalidate();
            announceSettledBounds();
            return;
        }
        final float from = position, offset = dismissOffset;
        final ValueAnimator motion = ValueAnimator.ofFloat(0f, 1f);
        animator = motion;
        motion.setDuration(SETTLE_MS);
        motion.setInterpolator(settling);
        motion.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                if (!canAnimate()) { settle(false); return; }
                float fraction = (Float) animation.getAnimatedValue();
                position = from + (target - from) * fraction;
                dismissOffset = offset * (1f - fraction);
                invalidate();
            }
        });
        motion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animator != motion) return;
                animator = null;
                position = target;
                dismissOffset = 0f;
                dismissIndex = -1;
                invalidate();
                announceSettledBounds();
            }
        });
        motion.start();
    }

    private float rubberBand(float requested) {
        float last = Math.max(0, cards.size() - 1);
        if (requested < 0f) return -.28f * (1f - 1f / (1f - requested));
        if (requested > last) return last + .28f * (1f - 1f / (1f + requested - last));
        return requested;
    }

    private void dismissCard(final int index, final String id) {
        cancelAnimation();
        if (!canAnimate()) {
            if (listener != null && sameCard(index, id) && cards.get(index).active) listener.onDismiss(index);
            settle(false);
            return;
        }
        dismissIndex = index;
        final float from = dismissOffset;
        final ValueAnimator exit = ValueAnimator.ofFloat(0f, 1f);
        animator = exit;
        exit.setDuration(160L);
        exit.setInterpolator(settling);
        exit.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                if (!canAnimate()) { settle(false); return; }
                float fraction = (Float) animation.getAnimatedValue();
                dismissOffset = from + (-CARD_LEFT - CARD_WIDTH - 16f - from) * fraction;
                invalidate();
            }
        });
        exit.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animator != exit) return;
                animator = null;
                // The host may replace/reorder cards in this callback. Never
                // apply an old index to a newly published card list.
                if (listener != null && sameCard(index, id) && cards.get(index).active) listener.onDismiss(index);
                settle(true);
            }
        });
        exit.start();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) { finishTouch(); return false; }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (cards.isEmpty() || event.getY() < EDGE_GUARD || event.getY() > getHeight() - EDGE_GUARD) return false;
            int hit = hitCard(event.getX(), event.getY());
            if (hit < 0) return false;
            cancelAnimation();
            finishTouch();
            consuming = tracking = true;
            pointer = event.getPointerId(0);
            pressedIndex = hit;
            pressedId = cards.get(hit).id;
            downX = event.getX(); downY = event.getY(); startPosition = position;
            velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (!consuming) return false;
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            boolean keepConsuming = action == MotionEvent.ACTION_POINTER_DOWN;
            finishTouch();
            consuming = keepConsuming;
            settle(true);
            return true;
        }
        if (!tracking) {
            if (action == MotionEvent.ACTION_UP) finishTouch();
            return true;
        }
        int point = event.findPointerIndex(pointer);
        if (point < 0) { finishTouch(); settle(true); return true; }
        velocity.addMovement(event);
        float dx = event.getX(point) - downX, dy = event.getY(point) - downY;
        float scale = Math.max(.001f, scale());
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                moved = true;
                if (dragAxis == UNDECIDED) {
                    if (Math.abs(dx) > Math.abs(dy) * 1.15f)
                        dragAxis = dx < 0f && cards.get(pressedIndex).active ? DISMISS : BLOCKED;
                    else dragAxis = VERTICAL;
                }
            }
            if (dragAxis == VERTICAL) {
                position = rubberBand(startPosition - dy / (DRAG_STEP * scale));
                selectFromUser(Math.round(position));
            } else if (dragAxis == DISMISS) {
                dismissIndex = pressedIndex;
                dismissOffset = Math.max(-CARD_WIDTH * 1.15f, Math.min(0f, dx / scale));
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            velocity.computeCurrentVelocity(1000, maxVelocity);
            float vx = velocity.getXVelocity(pointer), vy = velocity.getYVelocity(pointer);
            int hit = hitCard(event.getX(point), event.getY(point));
            int index = pressedIndex, axis = dragAxis;
            String id = pressedId;
            boolean tap = !moved && Math.abs(dx) <= touchSlop && Math.abs(dy) <= touchSlop && hit == index;
            boolean dismiss = axis == DISMISS && (dx < -CARD_WIDTH * scale * .28f
                    || (dx < -48f * scale && vx < -650f * scale));
            float projected = position + Math.max(-2f, Math.min(2f, -vy * .12f / (DRAG_STEP * scale)));
            finishTouch();
            if (tap) {
                selectFromUser(index);
                settle(true);
                if (selected == index && sameCard(index, id)) performClick();
            } else if (dismiss && sameCard(index, id) && cards.get(index).active) {
                dismissCard(index, id);
            } else {
                if (axis == VERTICAL) selectFromUser(Math.round(projected));
                settle(true);
            }
            return true;
        }
        return true;
    }

    private boolean sameCard(int index, String id) {
        return index >= 0 && index < cards.size() && cards.get(index).id.equals(id);
    }

    private void finishTouch() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
        if (consuming && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        consuming = tracking = moved = false;
        pointer = pressedIndex = -1;
        pressedId = null;
        dragAxis = UNDECIDED;
    }

    private void stopInteraction() {
        cancelAnimation();
        finishTouch();
        position = Math.max(0, selected);
        dismissOffset = 0f;
        dismissIndex = -1;
        clearVirtualFocus();
        updateVirtualHover(-1);
    }

    @Override public boolean performClick() {
        super.performClick();
        if (listener != null && selected >= 0 && selected < cards.size()) listener.onActivate(selected);
        return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
        info.setScrollable(cards.size() > 1);
        if (selected > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (selected >= 0 && selected + 1 < cards.size())
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            finishTouch();
            selectFromUser(selected + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1));
            settle(true);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; }
    @Override protected void onDetachedFromWindow() {
        attached = false;
        stopInteraction();
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) stopInteraction();
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) stopInteraction();
    }

    @Override protected void onMeasure(int width, int height) {
        setMeasuredDimension(resolveSize((int) SCREEN_WIDTH, width), resolveSize((int) SCREEN_HEIGHT, height));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = scale();
        if (scale <= 0f) return;
        int save = canvas.save();
        canvas.translate(originX(), originY());
        canvas.scale(scale, scale);
        canvas.clipRect(0f, CLIP_TOP, SCREEN_WIDTH, CLIP_BOTTOM);
        for (int i = 0; i < cards.size(); i++) {
            float top = cardTop(i);
            if (top >= CLIP_BOTTOM || top + CARD_HEIGHT <= CLIP_TOP) continue;
            drawCard(canvas, cards.get(i), CARD_LEFT + (i == dismissIndex ? dismissOffset : 0f), top);
        }
        canvas.restoreToCount(save);
    }

    private void drawCard(Canvas canvas, NavigationCard card, float left, float top) {
        if (card.active) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(0xff51515f);
            canvas.drawRoundRect(left - 6f, top - 6f, left + CARD_WIDTH + 6f, top + CARD_HEIGHT + 6f,
                    CORNER_RADIUS + 6f, CORNER_RADIUS + 6f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(card.color);
        canvas.drawRoundRect(left, top, left + CARD_WIDTH, top + CARD_HEIGHT, CORNER_RADIUS, CORNER_RADIUS, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(Color.BLACK);
        canvas.drawRoundRect(left, top, left + CARD_WIDTH, top + CARD_HEIGHT, CORNER_RADIUS, CORNER_RADIUS, paint);
        drawGlyph(canvas, card.glyph, left + 12f, top + 6f, 29f, card.color);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        labelPaint.setTextSize(28f);
        float width = labelPaint.measureText(card.title);
        if (width > CARD_WIDTH - 70f) labelPaint.setTextSize(28f * (CARD_WIDTH - 70f) / width);
        canvas.drawText(card.title, left + CARD_WIDTH - 13f, top + 29f, labelPaint);
        drawGlyph(canvas, card.glyph, left + CARD_WIDTH / 2f - 52f, top + CARD_HEIGHT / 2f - 52f, 104f, card.color);
        if (top + CARD_HEIGHT <= CLIP_BOTTOM) {
            labelPaint.setTextAlign(Paint.Align.LEFT);
            labelPaint.setTextSize(28f);
            canvas.drawText(card.title, left + 18f, top + CARD_HEIGHT - 20f, labelPaint);
            drawGlyph(canvas, card.glyph, left + CARD_WIDTH - 42f, top + CARD_HEIGHT - 45f, 27f, card.color);
        }
    }

    private void fill(int color) { paint.setStyle(Paint.Style.FILL); paint.setColor(color); }
    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(width);
    }

    /** Small black category marks are drawn from the same paths at all three sizes. */
    private void drawGlyph(Canvas canvas, NavigationCard.Glyph glyph, float x, float y, float size, int paper) {
        int save = canvas.save();
        canvas.translate(x, y);
        canvas.scale(size / 32f, size / 32f);
        fill(Color.BLACK);
        glyphPath.reset();
        switch (glyph) {
            case CAMERA:
                canvas.drawRoundRect(3, 5, 29, 28, 6, 6, paint);
                fill(paper); canvas.drawRoundRect(6, 10, 26, 24, 3, 3, paint);
                fill(Color.BLACK); canvas.drawCircle(16, 17, 6, paint);
                fill(paper); canvas.drawCircle(16, 17, 3, paint);
                break;
            case GALLERY:
                canvas.drawRect(8, 2, 24, 4, paint); canvas.drawRect(5, 6, 27, 8, paint);
                canvas.drawRect(3, 10, 29, 30, paint);
                fill(paper); canvas.drawRect(7, 14, 25, 26, paint);
                fill(Color.BLACK); star(canvas, 16, 20, 6);
                break;
            case TIMER:
                canvas.drawCircle(16, 17, 13, paint);
                fill(paper); canvas.drawCircle(16, 17, 9, paint);
                stroke(Color.BLACK, 4); canvas.drawLine(12, 21, 22, 11, paint);
                break;
            case TRANSLATE:
                canvas.drawRect(2, 10, 14, 29, paint); canvas.drawRect(17, 3, 30, 25, paint);
                stroke(paper, 1.8f);
                canvas.drawLine(5, 24, 8, 15, paint); canvas.drawLine(8, 15, 11, 24, paint);
                canvas.drawLine(6, 21, 10, 21, paint);
                canvas.drawLine(20, 10, 27, 10, paint); canvas.drawLine(23.5f, 7, 23.5f, 10, paint);
                canvas.drawLine(26, 10, 20, 19, paint); canvas.drawLine(21, 12, 27, 19, paint);
                break;
            case RECORDER:
                canvas.drawCircle(16, 16, 14, paint);
                stroke(paper, 3.8f);
                canvas.translate(16, 16);
                for (int i = 0; i < 3; i++) { canvas.drawLine(0, -5, 0, -10, paint); canvas.rotate(120); }
                break;
            case RCADE:
                canvas.drawCircle(16, 6, 5, paint); canvas.drawRect(14, 11, 18, 22, paint);
                canvas.drawRect(3, 20, 29, 29, paint);
                fill(paper); canvas.drawCircle(23, 24, 2.6f, paint);
                break;
            case REMINDERS:
                canvas.drawRoundRect(8, 18, 29, 29, 2, 2, paint);
                stroke(Color.BLACK, 4);
                canvas.drawLine(10, 12, 10, 25, paint); canvas.drawLine(16, 18, 16, 25, paint);
                canvas.drawLine(22, 17, 22, 25, paint); canvas.drawLine(27, 20, 27, 25, paint);
                stroke(Color.BLACK, 2); canvas.drawLine(10, 2, 10, 6, paint);
                canvas.drawLine(2, 6, 5, 9, paint); canvas.drawLine(18, 5, 15, 8, paint);
                break;
            case ALARM:
                canvas.drawCircle(16, 18, 11, paint);
                canvas.drawCircle(7, 5, 4, paint); canvas.drawCircle(25, 5, 4, paint);
                stroke(paper, 3); canvas.drawLine(16, 11, 16, 18, paint); canvas.drawLine(16, 18, 11, 20, paint);
                break;
            case INTERN:
                canvas.drawRect(3, 4, 29, 24, paint); canvas.drawRect(1, 27, 31, 30, paint);
                fill(paper); canvas.drawRect(7, 8, 25, 20, paint);
                break;
            case MUSIC:
                canvas.drawRect(13, 4, 18, 26, paint); canvas.drawRect(13, 3, 29, 11, paint);
                canvas.drawOval(3, 20, 18, 31, paint);
                break;
            case CREATIONS:
                canvas.drawRect(3, 3, 13, 13, paint); star(canvas, 24, 8, 6);
                canvas.drawCircle(24, 24, 6, paint);
                glyphPath.reset();
                glyphPath.moveTo(8, 18); glyphPath.lineTo(2, 29); glyphPath.lineTo(14, 29); glyphPath.close();
                canvas.drawPath(glyphPath, paint);
                break;
            case SETTINGS:
                for (int i = 0; i < 32; i++) {
                    double angle = i * Math.PI / 16;
                    float radius = i % 4 == 0 || i % 4 == 3 ? 11.5f : 15f;
                    float px = 16 + radius * (float) Math.cos(angle), py = 16 + radius * (float) Math.sin(angle);
                    if (i == 0) glyphPath.moveTo(px, py); else glyphPath.lineTo(px, py);
                }
                glyphPath.close(); canvas.drawPath(glyphPath, paint);
                fill(paper); canvas.drawRect(11, 11, 21, 21, paint);
                break;
            case APPS:
                for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++)
                    canvas.drawRoundRect(3 + column * 10, 3 + row * 10, 9 + column * 10, 9 + row * 10, 1, 1, paint);
                break;
            case PHONE:
                glyphPath.moveTo(5, 3); glyphPath.lineTo(11, 3); glyphPath.lineTo(14, 10);
                glyphPath.lineTo(10, 14); glyphPath.cubicTo(12, 18, 15, 21, 19, 23);
                glyphPath.lineTo(23, 19); glyphPath.lineTo(30, 22); glyphPath.lineTo(30, 28);
                glyphPath.cubicTo(29, 34, 17, 29, 10, 22); glyphPath.cubicTo(3, 15, -1, 5, 5, 3);
                glyphPath.close(); canvas.drawPath(glyphPath, paint);
                break;
            case MESSAGES:
                canvas.drawRoundRect(2, 4, 30, 25, 5, 5, paint);
                glyphPath.moveTo(5, 23); glyphPath.lineTo(5, 31); glyphPath.lineTo(14, 23); glyphPath.close();
                canvas.drawPath(glyphPath, paint);
                fill(paper);
                for (int i = 0; i < 3; i++) canvas.drawCircle(9 + i * 7, 15, 2, paint);
                break;
        }
        canvas.restoreToCount(save);
    }

    private void star(Canvas canvas, float x, float y, float radius) {
        glyphPath.reset();
        glyphPath.moveTo(x, y - radius); glyphPath.lineTo(x + radius * .3f, y - radius * .3f);
        glyphPath.lineTo(x + radius, y); glyphPath.lineTo(x + radius * .3f, y + radius * .3f);
        glyphPath.lineTo(x, y + radius); glyphPath.lineTo(x - radius * .3f, y + radius * .3f);
        glyphPath.lineTo(x - radius, y); glyphPath.lineTo(x - radius * .3f, y - radius * .3f);
        glyphPath.close(); canvas.drawPath(glyphPath, paint);
    }
}
