package com.kevtrinh.rabbitphone;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.kevtrinh.rabbitphone.beats.Analysis;
import com.kevtrinh.rabbitphone.beats.Arranger;
import com.kevtrinh.rabbitphone.beats.Beat;
import com.kevtrinh.rabbitphone.beats.BeatButton;
import com.kevtrinh.rabbitphone.beats.BeatState;
import com.kevtrinh.rabbitphone.beats.Cleaner;
import com.kevtrinh.rabbitphone.beats.Engine;
import com.kevtrinh.rabbitphone.beats.Jar;
import com.kevtrinh.rabbitphone.beats.Job;
import com.kevtrinh.rabbitphone.beats.Style;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Catch sounds with the side button and hear them in a beat. Pressing the button silences
 * the beat and starts the microphone; a click plays or stops, a hold keeps the sound. Each
 * catch is sorted into a job, voices are chopped and snapped to the grid, and missing drums
 * are cut from the newest voice. Sounds stay in this app's storage on the R1.
 */
public final class BeatsPage extends ViewGroup implements HardwarePage {
    public interface Host { void requestMicPermission(); }

    static final int ACCENT = 0xffb6ff3b;
    private static final int WHITE = 0xfff5efe1, MUTED = 0xff99969e, DIM = 0xff3a3a36;
    private static final int FAINT = 0xff242421, WARNING = 0xffff5a1f;
    private static final long IDLE_STOP_MS = 10 * 60_000L, SETTLE_MS = 250;
    private static final String TAG = "RabbitBeats";
    private static final int[] CHIP_X = {24, 134, 244, 354};
    private static final int CHIP_Y = 144, CHIP_W = 102, CHIP_H = 54, ROWS_Y = 212, ROW_H = 44;
    private enum Focus { BEAT, BPM, VOLUME }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Engine engine = new Engine();
    private final BeatSpeaker speaker;
    private final CatchMic mic;
    private final Jar jar;
    private final File stateFile;
    private final BeatState state;
    private final Map<Job, Arranger.Sound> sounds = new EnumMap<>(Job.class);
    private final BeatButton sideButton, padButton;
    private final AudioManager audio;
    private final AudioFocusRequest focusRequest;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Typeface font;
    private final TextView heading, ready, beatChip, bpmChip, volumeChip, playChip, status;
    private final CatchPad pad;
    private Focus focus = Focus.BEAT;
    private Beat beat;
    private boolean hostActive, released, buttonReady, loading, stateChanged;
    private boolean catching, recordingFromPress, wasPlaying, limitReached, framesPosted;
    private long pressAt, lastInputAt;
    private float level;
    private boolean tooLoud;
    private float scale = 1, originX, originY;

    private final Runnable frames = new Runnable() {
        @Override public void run() {
            framesPosted = false;
            if (released) return;
            if (engine.isPlaying() && SystemClock.uptimeMillis() - lastInputAt > IDLE_STOP_MS) {
                stopPlayback();
                showStatus("Stopped after 10 quiet minutes.");
            }
            invalidate();
            pad.invalidate();
            if (engine.isPlaying() || catching) {
                framesPosted = true;
                main.postDelayed(this, 33);
            }
        }
    };

    private final Runnable settledBeat = new Runnable() {
        @Override public void run() { rebuild(); }
    };

    public BeatsPage(Activity activity, Host host) {
        super(activity);
        this.host = host;
        setBackgroundColor(Color.BLACK);
        setWillNotDraw(false);
        font = RabbitTypography.regular(activity);
        paint.setTypeface(font);
        File base = activity.getExternalFilesDir(null);
        File root = new File(base != null ? base : activity.getFilesDir(), "beats");
        jar = new Jar(root);
        stateFile = new File(root, "state.properties");
        state = BeatState.load(stateFile);
        engine.setVolume(state.volume / 100f);
        audio = activity.getSystemService(AudioManager.class);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                    @Override public void onAudioFocusChange(int change) {
                        if ((change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                                && engine.isPlaying()) stopPlayback();
                    }
                }, main)
                .build();
        speaker = new BeatSpeaker(activity, engine, new BeatSpeaker.Listener() {
            @Override public void onOutputFailed() {
                if (engine.isPlaying()) stopPlayback();
                showStatus("Sound output isn't available.");
            }
        });
        mic = new CatchMic(activity, new CatchMic.Listener() {
            @Override public void onLevel(float peak) {
                level = peak;
                if (peak >= 0.999f) tooLoud = true;
                pad.invalidate();
            }
            @Override public void onLimit() {
                if (!catching) return;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                limitReached = true;
                finishCatch();
            }
        });
        BeatButton.Scheduler scheduler = new BeatButton.Scheduler() {
            @Override public long now() { return SystemClock.uptimeMillis(); }
            @Override public void postDelayed(Runnable action, long delay) { main.postDelayed(action, delay); }
            @Override public void remove(Runnable action) { main.removeCallbacks(action); }
        };
        sideButton = new BeatButton(scheduler, new BeatButton.Actions() {
            @Override public void onPress() { pressStart(); }
            @Override public void onHoldStart() { holdStart(); }
            @Override public void onClick() {
                mic.cancel();
                recordingFromPress = false;
                if (!wasPlaying) startPlayback();
            }
            @Override public void onHoldEnd() { finishCatch(); }
        });
        padButton = new BeatButton(scheduler, new BeatButton.Actions() {
            @Override public void onPress() { pressStart(); }
            @Override public void onHoldStart() { holdStart(); }
            @Override public void onClick() {
                mic.cancel();
                recordingFromPress = false;
                if (wasPlaying) startPlayback();
                else showStatus("Hold to catch a sound.");
            }
            @Override public void onHoldEnd() { finishCatch(); }
        });

        heading = label("beats", ACCENT);
        ready = label("", MUTED);
        ready.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        beatChip = chip("Beat number. Tap, then turn the wheel for another beat", new Runnable() {
            @Override public void run() { focus = Focus.BEAT; updateChips(); }
        });
        bpmChip = chip("Tempo. Tap, then turn the wheel", new Runnable() {
            @Override public void run() { focus = Focus.BPM; updateChips(); }
        });
        volumeChip = chip("Volume. Tap, then turn the wheel", new Runnable() {
            @Override public void run() { focus = Focus.VOLUME; updateChips(); }
        });
        playChip = chip("Play or stop", new Runnable() {
            @Override public void run() { togglePlayback(); }
        });
        status = label("", WHITE);
        status.setMaxLines(2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pad = new CatchPad(activity);
        addView(heading); addView(ready);
        addView(beatChip); addView(bpmChip); addView(volumeChip); addView(playChip);
        addView(status); addView(pad);
        updateReady();
        rebuild();
        updateChips();
        loadSounds();
    }

    public void setButtonReady(boolean value) {
        buttonReady = value;
        updateReady();
        pad.invalidate();
    }

    @Override public View getView() { return this; }

    @Override public boolean handleWheel(boolean up) {
        if (!isEnabled()) return false;
        noteInput();
        int delta = up ? -1 : 1;
        if (focus == Focus.VOLUME) {
            int next = clamp(state.volume + delta * 5, 0, 100);
            if (next == state.volume) return true;
            state.volume = next;
            engine.setVolume(next / 100f);
        } else if (focus == Focus.BPM) {
            int next = clamp(bpm() + delta, Beat.MIN_BPM, Beat.MAX_BPM);
            if (next == bpm()) return true;
            state.tempos.put(state.beat, next);
            rebuild();
        } else {
            int next = clamp(state.beat + delta, Arranger.FIRST_BEAT, Arranger.LAST_BEAT);
            if (next == state.beat) return true;
            state.beat = next;
            // Browsing quickly shouldn't rearrange the beat at every notch.
            main.removeCallbacks(settledBeat);
            main.postDelayed(settledBeat, SETTLE_MS);
        }
        stateChanged = true;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        updateChips();
        return true;
    }

    @Override public boolean handleSingle() {
        if (!isEnabled()) return false;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        togglePlayback();
        return true;
    }

    @Override public boolean handleBack() { return false; }

    @Override public boolean ownsButton() { return true; }

    @Override public void buttonDown(long time) {
        if (!isEnabled() || !hostActive || padButton.isPressed()) return;
        sideButton.down(time);
    }

    @Override public void buttonUp(long time) { sideButton.up(time); }

    @Override public void buttonCancel() {
        sideButton.cancel();
        cancelCatch();
    }

    @Override public void setHostActive(boolean active) {
        if (released || active == hostActive) return;
        hostActive = active;
        if (active) {
            speaker.start();
            mic.prepare();
            updateChips();
            return;
        }
        sideButton.cancel();
        padButton.cancel();
        cancelCatch();
        if (engine.isPlaying()) stopPlayback();
        speaker.stop();
        mic.release();
        // Only real changes are written, so an unreadable state file is never replaced with defaults.
        if (stateChanged) saveState();
    }

    @Override public void release() {
        setHostActive(false);
        released = true;
        main.removeCallbacks(frames);
        main.removeCallbacks(settledBeat);
        worker.shutdown();
    }

    private void loadSounds() {
        final Map<Job, String> ids = new EnumMap<>(state.sounds);
        if (ids.isEmpty()) {
            showStatus("Hold the side button and say something silly.");
            warmUp();
            return;
        }
        loading = true;
        worker.execute(new Runnable() {
            @Override public void run() {
                final Map<Job, Arranger.Sound> loaded = new EnumMap<>(Job.class);
                for (Map.Entry<Job, String> entry : ids.entrySet()) {
                    try {
                        loaded.put(entry.getKey(), jar.load(entry.getValue()));
                    } catch (IOException | RuntimeException unreadable) {
                        Log.w(TAG, "Couldn't load catch " + entry.getValue(), unreadable);
                    }
                }
                main.post(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        if (released) return;
                        for (Map.Entry<Job, Arranger.Sound> entry : loaded.entrySet())
                            if (!sounds.containsKey(entry.getKey())) sounds.put(entry.getKey(), entry.getValue());
                        if (sounds.isEmpty()) showStatus("Hold the side button and say something silly.");
                        rebuild();
                    }
                });
            }
        });
        warmUp();
    }

    /**
     * Runs the analysis and a silent-to-nobody render in the background, so the JIT has
     * compiled them before the first real catch or the first bar of playback.
     */
    private void warmUp() {
        worker.execute(new Runnable() {
            @Override public void run() {
                float[] noise = new float[Engine.RATE / 2];
                java.util.Random random = new java.util.Random(1);
                for (int i = 0; i < noise.length; i++) noise[i] = (random.nextFloat() - 0.5f) * 0.2f;
                Map<Job, Arranger.Sound> practice = new EnumMap<>(Job.class);
                practice.put(Job.VOICE, new Arranger.Sound("warm-up", noise, Analysis.of(noise)));
                Engine rehearsal = new Engine();
                rehearsal.setBeat(Arranger.build(Arranger.FIRST_BEAT, null, practice));
                rehearsal.play();
                float[] block = new float[256];
                for (int rendered = 0; rendered < Engine.RATE * 2 && !released; rendered += block.length)
                    rehearsal.render(block, block.length);
            }
        });
    }

    private void pressStart() {
        noteInput();
        pressAt = SystemClock.uptimeMillis();
        limitReached = false;
        level = 0;
        tooLoud = false;
        // A press is either a stop or a catch, so the beat goes quiet right away and
        // the microphone starts before the hold is confirmed. Clicks discard the audio.
        wasPlaying = engine.isPlaying();
        if (wasPlaying) stopPlayback();
        recordingFromPress = hostActive && mic.start();
    }

    private void holdStart() {
        if (!recordingFromPress) {
            if (!mic.hasPermission()) {
                host.requestMicPermission();
                showStatus("Allow the mic to catch sounds, then hold again.");
            } else {
                showStatus("The mic is busy. Try again in a moment.");
            }
            return;
        }
        catching = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showStatus("");
        startFrames();
        pad.invalidate();
    }

    private void finishCatch() {
        boolean caught = catching;
        catching = false;
        final float[] raw = mic.stop();
        recordingFromPress = false;
        pad.invalidate();
        if (!caught || raw == null) {
            // After the ten-second limit, the release that follows has nothing left to do.
            if (limitReached) limitReached = false;
            else if (wasPlaying) startPlayback();
            return;
        }
        showStatus("Listening…");
        final String source = mic.source();
        worker.execute(new Runnable() {
            @Override public void run() { keep(raw, source); }
        });
    }

    private void cancelCatch() {
        if (mic.isRecording()) mic.cancel();
        catching = false;
        recordingFromPress = false;
        pad.invalidate();
    }

    /** Runs on the worker: clean, analyse, then save. Raw audio is kept even when rejected. */
    private void keep(float[] raw, String source) {
        final Cleaner.Result result = Cleaner.clean(raw);
        final String id = jar.nextId(System.currentTimeMillis());
        Analysis analysis = null;
        if (result.samples != null) {
            long started = SystemClock.uptimeMillis();
            analysis = Analysis.of(result.samples);
            Log.i(TAG, String.format(Locale.US, "Analysed a %.1fs catch in %d ms: %s, %d pieces",
                    seconds(result.samples), SystemClock.uptimeMillis() - started, analysis.job, analysis.pieces.length));
        }
        boolean saved = true;
        try {
            jar.saveRaw(id, raw);
            if (result.samples != null) jar.saveSound(id, result.samples, about(result, analysis, raw.length, source));
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Couldn't save a catch", error);
            saved = false;
        }
        final Arranger.Sound sound = result.samples == null ? null : new Arranger.Sound(id, result.samples, analysis);
        final boolean stored = saved;
        main.post(new Runnable() {
            @Override public void run() { caught(result, sound, stored); }
        });
    }

    private void caught(Cleaner.Result result, Arranger.Sound sound, boolean stored) {
        if (released) return;
        if (sound == null) {
            showStatus(result.problem);
            if (wasPlaying) startPlayback();
            return;
        }
        Job job = sound.analysis.job;
        sounds.put(job, sound);
        if (stored) {
            state.sounds.put(job, sound.id);
            saveState();
        }
        rebuild();
        startPlayback();
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (!stored) showStatus("Couldn't save that sound. Storage may be full.");
        else if (result.clipped) showStatus("Too loud. Back off a bit.");
        else showStatus(String.format(Locale.US, "Got it: %s · %.1fs", job.label(), seconds(sound.samples)));
    }

    private Properties about(Cleaner.Result result, Analysis analysis, int rawFrames, String source) {
        Properties values = new Properties();
        values.setProperty("created", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
        values.setProperty("frames", Integer.toString(result.samples.length));
        values.setProperty("raw_frames", Integer.toString(rawFrames));
        values.setProperty("clipped", Boolean.toString(result.clipped));
        values.setProperty("source", source);
        analysis.write(values);
        return values;
    }

    private int bpm() {
        Integer set = state.tempos.get(state.beat);
        return set != null ? set : Arranger.tempo(Style.BOUNCE, state.beat);
    }

    private void rebuild() {
        main.removeCallbacks(settledBeat);
        beat = Arranger.build(state.beat, state.tempos.get(state.beat), sounds);
        engine.setBeat(beat);
        updateChips();
    }

    private void togglePlayback() {
        noteInput();
        if (engine.isPlaying()) stopPlayback();
        else startPlayback();
    }

    private void startPlayback() {
        if (!hostActive || released) return;
        if (sounds.isEmpty()) {
            showStatus(loading ? "" : "Catch a sound first.");
            return;
        }
        if (audio != null && audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            showStatus("Another app is playing sound.");
            return;
        }
        engine.play();
        setKeepScreenOn(true);
        updateChips();
        startFrames();
    }

    private void stopPlayback() {
        engine.stop();
        setKeepScreenOn(false);
        if (audio != null) audio.abandonAudioFocusRequest(focusRequest);
        updateChips();
    }

    private void startFrames() {
        if (framesPosted) return;
        framesPosted = true;
        main.post(frames);
    }

    private void noteInput() { lastInputAt = SystemClock.uptimeMillis(); }

    private void saveState() {
        stateChanged = false;
        final BeatState copy = state.copy();
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    copy.save(stateFile);
                } catch (IOException error) {
                    Log.w(TAG, "Couldn't save Beats settings", error);
                }
            }
        });
    }

    private void showStatus(String message) { status.setText(message); }

    private void updateReady() {
        ready.setText(buttonReady ? "side button ready" : "use the pad");
    }

    private void updateChips() {
        beatChip.setText("beat " + state.beat);
        bpmChip.setText(bpm() + " bpm");
        volumeChip.setText("vol " + state.volume);
        boolean playing = engine.isPlaying();
        playChip.setText(playing ? "stop" : "play");
        beatChip.setTextColor(focus == Focus.BEAT ? ACCENT : WHITE);
        bpmChip.setTextColor(focus == Focus.BPM ? ACCENT : WHITE);
        volumeChip.setTextColor(focus == Focus.VOLUME ? ACCENT : WHITE);
        playChip.setTextColor(playing ? Color.BLACK : WHITE);
        beatChip.setSelected(focus == Focus.BEAT);
        bpmChip.setSelected(focus == Focus.BPM);
        volumeChip.setSelected(focus == Focus.VOLUME);
        invalidate();
    }

    private TextView label(String text, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(color);
        view.setTypeface(font);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView chip(String description, final Runnable action) {
        TextView view = label("", WHITE);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setFocusable(true);
        view.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View clicked) {
                if (!BeatsPage.this.isEnabled()) return;
                noteInput();
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                action.run();
            }
        });
        return view;
    }

    private void measure(View view, int width, int height) {
        view.measure(MeasureSpec.makeMeasureSpec(Math.round(width * scale), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(height * scale), MeasureSpec.EXACTLY));
    }

    private void place(View view, int x, int y) {
        int left = Math.round(originX + x * scale), top = Math.round(originY + y * scale);
        view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        scale = Math.min(width / 480f, height / 640f);
        originX = (width - 480 * scale) / 2f;
        originY = (height - 640 * scale) / 2f;
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, 40 * scale);
        ready.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * scale);
        for (TextView chip : new TextView[] {beatChip, bpmChip, volumeChip, playChip}) {
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * scale);
            measure(chip, CHIP_W, CHIP_H);
        }
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17 * scale);
        measure(heading, 230, 52);
        measure(ready, 202, 32);
        measure(status, 432, 42);
        measure(pad, 432, 144);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        place(heading, 24, 86);
        place(ready, 254, 96);
        place(beatChip, CHIP_X[0], CHIP_Y);
        place(bpmChip, CHIP_X[1], CHIP_Y);
        place(volumeChip, CHIP_X[2], CHIP_Y);
        place(playChip, CHIP_X[3], CHIP_Y);
        place(status, 24, 436);
        place(pad, 24, 484);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        canvas.translate(originX, originY);
        canvas.scale(scale, scale);
        chipOutline(canvas, CHIP_X[0], focus == Focus.BEAT ? ACCENT : DIM, false);
        chipOutline(canvas, CHIP_X[1], focus == Focus.BPM ? ACCENT : DIM, false);
        chipOutline(canvas, CHIP_X[2], focus == Focus.VOLUME ? ACCENT : DIM, false);
        chipOutline(canvas, CHIP_X[3], engine.isPlaying() ? ACCENT : DIM, engine.isPlaying());
        long step = engine.isPlaying() ? engine.lastStep() : -1;
        Beat current = beat;
        if (current != null) {
            for (int i = 0; i < Math.min(5, current.trackCount()); i++) {
                Beat.Track track = current.track(i);
                drawRow(canvas, ROWS_Y + i * ROW_H, track.job.label(), detail(track), track, step);
            }
        }
        canvas.restoreToCount(save);
    }

    private String detail(Beat.Track track) {
        Arranger.Sound own = sounds.get(track.job);
        if (!"catch".equals(track.label) || own == null) return track.label;
        return String.format(Locale.US, "catch · %.1fs", seconds(own.samples));
    }

    private void chipOutline(Canvas canvas, float x, int color, boolean filled) {
        box.set(x + 1.5f, CHIP_Y + 1.5f, x + CHIP_W - 1.5f, CHIP_Y + CHIP_H - 1.5f);
        paint.setColor(color);
        paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        canvas.drawRoundRect(box, 14, 14, paint);
    }

    private void drawRow(Canvas canvas, float top, String name, String detail, Beat.Track track, long step) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(WHITE);
        paint.setTextSize(20);
        canvas.drawText(name, 24, top + 18, paint);
        paint.setColor(MUTED);
        paint.setTextSize(13);
        canvas.drawText(detail, 24, top + 36, paint);
        float left = 136, cell = 320 / 16f, middle = top + 17;
        long position = step < 0 ? -1 : step % track.length();
        long barStart = position < 0 ? 0 : position / Beat.STEPS_PER_BAR * Beat.STEPS_PER_BAR;
        for (int i = 0; i < Beat.STEPS_PER_BAR; i++) {
            float x = left + cell * (i + .5f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(track.hits(barStart + i) ? WHITE : FAINT);
            canvas.drawCircle(x, middle, 5.5f, paint);
            if (position >= 0 && position % Beat.STEPS_PER_BAR == i) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.5f);
                paint.setColor(ACCENT);
                canvas.drawCircle(x, middle, 9, paint);
            }
        }
    }

    private static float seconds(float[] samples) { return samples.length / (float) Engine.RATE; }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    /** The touch fallback for catching, and the live catch display for both buttons. */
    private final class CatchPad extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        CatchPad(Context context) {
            super(context);
            ink.setTypeface(font);
            ink.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
            setContentDescription("Hold to catch a sound");
            setOnTouchListener(new OnTouchListener() {
                @Override public boolean onTouch(View view, MotionEvent event) {
                    if (!BeatsPage.this.isEnabled()) return false;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            if (hostActive && !sideButton.isPressed()) padButton.down(event.getEventTime());
                            return true;
                        case MotionEvent.ACTION_UP:
                            padButton.up(event.getEventTime());
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            if (padButton.isPressed()) {
                                padButton.cancel();
                                cancelCatch();
                                if (wasPlaying) startPlayback();
                            }
                            return true;
                        default:
                            return true;
                    }
                }
            });
        }

        @Override protected void onDraw(Canvas canvas) {
            float s = getWidth() / 432f, middle = getWidth() / 2f;
            bounds.set(2 * s, 2 * s, getWidth() - 2 * s, getHeight() - 2 * s);
            if (catching) {
                // Pulse on every beat at the current tempo, strongest on the bar line, so
                // words can be chanted in time while the beat itself is quiet.
                long elapsed = SystemClock.uptimeMillis() - pressAt, beatMs = 60_000L / bpm();
                float phase = (elapsed % beatMs) / (float) beatMs, strength = (1 - phase) * (1 - phase) * (1 - phase);
                float accent = (elapsed / beatMs) % 4 == 0 ? 1f : 0.55f;
                int alpha = 0x22 + Math.round(0x66 * strength * accent);
                ink.setStyle(Paint.Style.FILL);
                ink.setColor((alpha << 24) | (ACCENT & 0xffffff));
                canvas.drawRoundRect(bounds, 22 * s, 22 * s, ink);
            }
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(3 * s);
            ink.setColor(catching ? ACCENT : DIM);
            canvas.drawRoundRect(bounds, 22 * s, 22 * s, ink);
            ink.setStyle(Paint.Style.FILL);
            if (!catching) {
                ink.setColor(WHITE);
                ink.setTextSize(28 * s);
                canvas.drawText("hold here to catch", middle, 70 * s, ink);
                ink.setColor(MUTED);
                ink.setTextSize(17 * s);
                canvas.drawText(buttonReady ? "or hold the side button" : "and say something silly", middle, 102 * s, ink);
                return;
            }
            ink.setColor(WHITE);
            ink.setTextSize(52 * s);
            float elapsed = (SystemClock.uptimeMillis() - pressAt) / 1000f;
            canvas.drawText(String.format(Locale.US, "%.1fs", Math.min(10f, elapsed)), middle, 64 * s, ink);
            ink.setColor(tooLoud ? WARNING : MUTED);
            ink.setTextSize(17 * s);
            canvas.drawText(tooLoud ? "too loud · back off a bit" : "let go to keep it", middle, 94 * s, ink);
            float left = 32 * s, right = getWidth() - 32 * s, top = 112 * s, height = 10 * s;
            ink.setColor(FAINT);
            canvas.drawRoundRect(left, top, right, top + height, height / 2, height / 2, ink);
            float amount = (float) Math.sqrt(Math.min(1f, level));
            ink.setColor(tooLoud ? WARNING : ACCENT);
            canvas.drawRoundRect(left, top, left + (right - left) * amount, top + height, height / 2, height / 2, ink);
        }
    }
}
