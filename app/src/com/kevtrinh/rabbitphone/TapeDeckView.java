package com.kevtrinh.rabbitphone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** A small tape transport driven only by the host's real media position and mic samples.
 * All methods belong to the UI thread. This view never opens or polls a media device. */
public final class TapeDeckView extends View {
    public enum Mode { READY, RECORDING, SAVED, PLAYING }

    private static final int RED = 0xffff1645;
    private static final int WHITE = 0xfff5efe1;
    private static final int MUTED = 0xff777570;
    private static final int TRACK = 0xff30302e;
    private static final long FRAME_MS = 34L; // At most 30 requested animation frames per second.
    private static final long EXTRAPOLATE_MS = 100L;
    private static final float DESIGN_WIDTH = 350f;
    private static final float DESIGN_HEIGHT = 200f;
    private static final int TICKS = 45;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private Mode mode = Mode.READY;
    private long positionMs;
    private long durationMs;
    private long sampledAt;
    private long meterAt;
    private long lastFrameAt;
    private float targetLevel;
    private float meterLevel;
    private float phaseDegrees;
    private boolean attached;
    private boolean stopped;
    private boolean framePending;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            framePending = false;
            if (!canDrawActive()) return;
            long now = SystemClock.uptimeMillis();
            advanceMeter(now);
            updatePhase(now);
            lastFrameAt = now;
            invalidate();
            // Stop ticking once the last real sample is stale and its meter has
            // decayed. A fresh host sample restarts frames; time never free-runs.
            if (ValueAnimator.areAnimatorsEnabled()
                    && (now - sampledAt < EXTRAPOLATE_MS || meterLevel > 0f)) scheduleFrame();
        }
    };

    public TapeDeckView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Tape deck, ready to record");
        sampledAt = meterAt = SystemClock.uptimeMillis();
    }

    /** Position and duration are milliseconds from VoiceNotes/MediaPlayer; amplitude
     * is the unmodified 0..32767 MediaRecorder maximum, used only while RECORDING.
     * A zero duration means unknown. SAVED/READY do not schedule animation frames. */
    public void setState(Mode next, long position, long duration, int amplitude) {
        if (next == null) throw new IllegalArgumentException("Tape mode is required");
        long now = SystemClock.uptimeMillis();
        advanceMeter(now);
        boolean changed = mode != next;
        mode = next;
        positionMs = Math.max(0L, position);
        durationMs = Math.max(0L, duration);
        if (durationMs > 0L) positionMs = Math.min(positionMs, durationMs);
        sampledAt = meterAt = now;
        stopped = false;
        targetLevel = mode == Mode.RECORDING ? amplitudeLevel(amplitude) : 0f;
        // Instant attack retains an actual short peak; release takes 300 ms for
        // the full scale. No prerecorded/random waveform or playback VU exists.
        meterLevel = mode == Mode.RECORDING ? Math.max(meterLevel, targetLevel) : 0f;
        updatePhase(now);
        if (changed) updateDescription();
        if (!isActive()) {
            cancelFrame();
            invalidate();
        } else {
            scheduleFrame();
        }
    }

    /** Freeze immediately; a later setState is the only explicit restart. */
    public void stopAnimation() {
        long now = SystemClock.uptimeMillis();
        advanceMeter(now);
        updatePhase(now);
        stopped = true;
        cancelFrame();
    }

    /** The first reel's current displayed phase, useful for visual diagnostics. */
    public float getPhaseDegrees() { return phaseDegrees; }

    private boolean isActive() { return mode == Mode.RECORDING || mode == Mode.PLAYING; }

    private boolean canDrawActive() {
        return attached && !stopped && isActive() && getWindowVisibility() == VISIBLE && isShown();
    }

    private void scheduleFrame() {
        if (framePending || !canDrawActive()) return;
        framePending = true;
        long delay = Math.max(0L, FRAME_MS - (SystemClock.uptimeMillis() - lastFrameAt));
        postDelayed(frame, delay);
    }

    private void cancelFrame() {
        removeCallbacks(frame);
        framePending = false;
    }

    private void updatePhase(long now) {
        if (!ValueAnimator.areAnimatorsEnabled() || stopped) return;
        long extra = isActive() ? Math.min(EXTRAPOLATE_MS, Math.max(0L, now - sampledAt)) : 0L;
        double position = (double) positionMs + extra;
        if (durationMs > 0L) position = Math.min(position, (double) durationMs);
        phaseDegrees = (float) ((position * 0.042) % 360.0);
    }

    private static float amplitudeLevel(int amplitude) {
        if (amplitude <= 0) return 0f;
        double fraction = Math.min(32767, amplitude) / 32767.0;
        return (float) Math.max(0.0, Math.min(1.0, (20.0 * Math.log10(fraction) + 60.0) / 60.0));
    }

    private void advanceMeter(long now) {
        float target = mode == Mode.RECORDING && now - sampledAt <= EXTRAPOLATE_MS ? targetLevel : 0f;
        long elapsed = Math.max(0L, now - meterAt);
        meterLevel = Math.max(target, meterLevel - elapsed / 300f);
        meterAt = now;
    }

    private void updateDescription() {
        switch (mode) {
            case RECORDING: setContentDescription("Tape deck, recording. Live microphone level."); break;
            case PLAYING: setContentDescription("Tape deck, playing voice note."); break;
            case SAVED: setContentDescription("Tape deck, saved voice note."); break;
            default: setContentDescription("Tape deck, ready to record"); break;
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.round(DESIGN_WIDTH * density) + getPaddingLeft() + getPaddingRight();
        int height = Math.round(DESIGN_HEIGHT * density) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        scheduleFrame();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        cancelFrame();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) scheduleFrame();
        else cancelFrame();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) scheduleFrame();
        else cancelFrame();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        float height = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        if (width <= 0f || height <= 0f) return;
        float scale = Math.min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT);
        int save = canvas.save();
        canvas.translate(getPaddingLeft() + (width - DESIGN_WIDTH * scale) / 2f,
                getPaddingTop() + (height - DESIGN_HEIGHT * scale) / 2f);
        canvas.scale(scale, scale);
        int color = mode == Mode.RECORDING ? RED : mode == Mode.PLAYING ? WHITE : MUTED;
        drawReel(canvas, 86f, 85f, phaseDegrees, color);
        drawReel(canvas, 264f, 85f, phaseDegrees + 18f, color);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(1.6f);
        int lit = mode == Mode.RECORDING ? Math.min(TICKS, (int) Math.ceil(meterLevel * TICKS)) : 0;
        for (int i = 0; i < TICKS; i++) {
            float x = 5f + i * (340f / (TICKS - 1));
            paint.setColor(i < lit ? RED : TRACK);
            canvas.drawLine(x, 179f, x, 185f, paint);
        }

        paint.setStrokeWidth(1.25f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(TRACK);
        canvas.drawLine(5f, 198f, 345f, 198f, paint);
        if ((mode == Mode.PLAYING || mode == Mode.SAVED) && durationMs > 0L) {
            float end = 5f + 340f * Math.min(1f, (float) ((double) positionMs / durationMs));
            paint.setColor(color);
            canvas.drawLine(5f, 198f, end, 198f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(end, 198f, 2f, paint);
        }
        canvas.restoreToCount(save);
    }

    private void drawReel(Canvas canvas, float x, float y, float phase, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f);
        canvas.drawCircle(x, y, 81f, paint);
        int save = canvas.save();
        canvas.translate(x, y);
        canvas.rotate(phase - 40f);
        paint.setStrokeWidth(6.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            canvas.drawLine(24f, 0f, 63f, 0f, paint);
            canvas.rotate(120f);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0f, 0f, 4.8f, paint);
        canvas.restoreToCount(save);
    }
}
