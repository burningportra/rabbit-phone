package com.kevtrinh.rabbitphone.beats;

/**
 * Reads the side button for Beats: it acts the moment the button goes down, then
 * decides between a click and a catch. There are no multi-click gestures, so
 * tapping along to a beat can never refresh or power off the R1.
 */
public final class BeatButton {
    public interface Scheduler {
        long now();
        void postDelayed(Runnable action, long delay);
        void remove(Runnable action);
    }

    public interface Actions {
        /** The button went down. Recording starts here so the first syllable survives. */
        void onPress();
        void onHoldStart();
        /** Released before {@link #HOLD_MS}. */
        void onClick();
        void onHoldEnd();
    }

    public static final long HOLD_MS = 450;
    private final Scheduler scheduler;
    private final Actions actions;
    private boolean pressed;
    private boolean held;
    private long downAt;
    private final Runnable hold = new Runnable() {
        @Override public void run() {
            if (!pressed || held) return;
            held = true;
            actions.onHoldStart();
        }
    };

    public BeatButton(Scheduler scheduler, Actions actions) {
        this.scheduler = scheduler;
        this.actions = actions;
    }

    public void down(long eventTime) {
        if (pressed) return;
        pressed = true;
        held = false;
        downAt = eventTime;
        actions.onPress();
        scheduler.postDelayed(hold, Math.max(0L, HOLD_MS - (scheduler.now() - eventTime)));
    }

    public void up(long eventTime) {
        if (!pressed) return;
        pressed = false;
        scheduler.remove(hold);
        // A busy UI thread may have delayed the hold timer; the event times still decide.
        if (!held && eventTime - downAt >= HOLD_MS) {
            held = true;
            actions.onHoldStart();
        }
        if (held) {
            held = false;
            actions.onHoldEnd();
        } else {
            actions.onClick();
        }
    }

    public void cancel() {
        scheduler.remove(hold);
        pressed = false;
        held = false;
    }

    public boolean isPressed() { return pressed; }
}
