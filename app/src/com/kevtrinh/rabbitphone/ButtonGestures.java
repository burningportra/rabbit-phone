package com.kevtrinh.rabbitphone;

/** Bounded R1 click/hold classifier. The scheduler is injectable for host tests. */
public final class ButtonGestures {
    public interface Scheduler {
        long now();
        void postDelayed(Runnable action, long delay);
        void remove(Runnable action);
    }
    public interface Actions {
        void onSingle();
        void onDouble();
        void onHoldStart();
        void onHoldEnd();
        void onRefresh();
        void onShutdown();
    }
    private static final long HOLD_MS = 450;
    private static final long MULTI_CLICK_MS = 320;
    private final Scheduler scheduler;
    private final Actions actions;
    private boolean pressed;
    private boolean held;
    private int clicks;
    private long downAt;
    private long lastUp = -1;
    private final Runnable hold = new Runnable() {
        @Override public void run() {
            if (!pressed || held) return;
            held = true;
            clicks = 0;
            scheduler.remove(settle);
            actions.onHoldStart();
        }
    };
    private final Runnable settle = new Runnable() {
        @Override public void run() {
            if (pressed) return;
            int count = clicks;
            clicks = 0;
            if (count == 1) actions.onSingle();
            else if (count == 2) actions.onDouble();
            else if (count == 5) actions.onRefresh();
        }
    };
    public ButtonGestures(Scheduler scheduler, Actions actions) {
        this.scheduler = scheduler;
        this.actions = actions;
    }
    public void down() {
        down(scheduler.now());
    }
    public void down(long eventTime) {
        if (pressed) return;
        if (lastUp >= 0 && eventTime - lastUp >= MULTI_CLICK_MS) clicks = 0;
        pressed = true;
        downAt = eventTime;
        scheduler.remove(settle);
        scheduler.postDelayed(hold, Math.max(0L, HOLD_MS - (scheduler.now() - eventTime)));
    }
    public void up() {
        up(scheduler.now());
    }
    public void up(long eventTime) {
        if (!pressed) return;
        pressed = false;
        scheduler.remove(hold);
        if (held) {
            held = false;
            clicks = 0;
            actions.onHoldEnd();
            return;
        }
        // The UI may have been busy opening the camera while an entire hold
        // occurred. Never reinterpret a delayed hold as a selection or photo.
        if (eventTime - downAt >= HOLD_MS) {
            clicks = 0;
            scheduler.remove(settle);
            return;
        }
        long delay = Math.max(0L, scheduler.now() - eventTime);
        if (delay > MULTI_CLICK_MS) {
            clicks = 0;
            lastUp = eventTime;
            scheduler.remove(settle);
            return;
        }
        lastUp = eventTime;
        clicks++;
        if (clicks == 8) {
            clicks = 0;
            scheduler.remove(settle);
            actions.onShutdown();
        } else {
            scheduler.remove(settle);
            scheduler.postDelayed(settle, Math.max(0L, MULTI_CLICK_MS - delay));
        }
    }
    public void cancel() {
        scheduler.remove(hold);
        scheduler.remove(settle);
        // Lifecycle owners cancel the recording themselves; never save a partial note here.
        pressed = false;
        held = false;
        clicks = 0;
        lastUp = -1;
    }
}
