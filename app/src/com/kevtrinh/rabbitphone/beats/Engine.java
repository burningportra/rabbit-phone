package com.kevtrinh.rabbitphone.beats;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sample-accurate sequencer, sampler and master chain. Control methods may be called from
 * any thread. {@link #render} belongs to the audio thread and never allocates.
 */
public final class Engine {
    public static final int RATE = 48_000;
    /** -1 dBFS: nothing leaves the engine louder than this. */
    public static final float CEILING = 0.891f;
    static final int MAX_VOICES = 24;
    /** The thump sits about 6 dB under a sound it's layered with. */
    static final float THUMP_UNDER_SOUND = 0.5f;
    /** Thump octave ranges: the R1 speaker can't play much under 100 Hz. */
    static final double SPEAKER_THUMP_LOW = 100, HEADPHONE_THUMP_LOW = 50;
    static final double SPEAKER_HIGH_PASS = 100, HEADPHONE_HIGH_PASS = 25;
    private static final int STOP_FADE = RATE / 100;
    private static final int ATTACK = RATE / 1000;
    private static final int CHOKE = RATE / 200;
    private static final double COMP_THRESHOLD = 0.251, COMP_RATIO = 2, DRIVE = 1.2;
    private static final double TANH_DRIVE = Math.tanh(DRIVE);
    private static final double[] SPEAKER_FILTER = Biquad.highPass(SPEAKER_HIGH_PASS);
    private static final double[] HEADPHONE_FILTER = Biquad.highPass(HEADPHONE_HIGH_PASS);
    private static final int NONE = 0, PLAY = 1, STOP = 2;

    private final AtomicReference<Beat> pending = new AtomicReference<>();
    private final AtomicInteger request = new AtomicInteger(NONE);
    private volatile boolean playing;
    private volatile boolean active;
    private volatile boolean speakerMode = true;
    private volatile float volumeTarget = 0.8f;
    private volatile long lastStep = -1;
    /** Tests only: skip the master chain so timing can be read sample by sample. */
    boolean bypassMaster;

    private Beat beat;
    private boolean running;
    private boolean alive;
    private int fade;
    private long frame, anchorFrame, anchorStep, step, nextStepFrame;
    private float volume = 0.8f;
    private final Voice[] voices = new Voice[MAX_VOICES];
    private long voiceClock;
    private boolean filterSpeaker, filterReady;
    private double b0, b1, b2, a1, a2, x1, x2, y1, y2;
    private double compEnvelope, limitEnvelope;
    private final double compAttack = smoothing(0.010), compRelease = smoothing(0.120);
    private final double limitRelease = smoothing(0.050);

    public Engine() {
        for (int i = 0; i < voices.length; i++) voices[i] = new Voice();
    }

    /** Swapped in at the next bar while playing, or right away while stopped. */
    public void setBeat(Beat next) { pending.set(next); }

    public void play() {
        request.set(PLAY);
        playing = true;
        active = true;
    }

    public void stop() {
        request.set(STOP);
        playing = false;
    }

    public boolean isPlaying() { return playing; }

    /** True while the engine is producing sound, including the short fade after a stop. */
    public boolean isActive() { return active; }

    public void setVolume(float value) { volumeTarget = Math.max(0f, Math.min(1f, value)); }

    public void setSpeakerMode(boolean speaker) { speakerMode = speaker; }

    /** The step that most recently played, or -1 before the first one. */
    public long lastStep() { return lastStep; }

    public void render(float[] out, int frames) {
        int command = request.getAndSet(NONE);
        if (command == PLAY) start();
        else if (command == STOP) beginStop();
        if (!running && fade == 0) {
            Beat next = pending.getAndSet(null);
            if (next != null) beat = next;
        }
        updateFilter();
        for (int i = 0; i < frames; i++) {
            if (!alive) { out[i] = 0; continue; }
            if (running) while (frame >= nextStepFrame) tick();
            float mix = 0;
            for (Voice voice : voices) if (voice.active) mix += voice.next();
            float value = bypassMaster ? mix : master(mix);
            volume += (volumeTarget - volume) * 0.0005f;
            value *= volume;
            if (fade > 0) {
                value *= fade / (float) STOP_FADE;
                if (--fade == 0) finishStop();
            }
            out[i] = value;
            frame++;
        }
    }

    private void start() {
        Beat next = pending.getAndSet(null);
        if (next != null) beat = next;
        for (Voice voice : voices) voice.active = false;
        fade = 0;
        if (beat == null) {
            running = alive = false;
            playing = active = false;
            return;
        }
        running = alive = true;
        frame = anchorFrame = anchorStep = step = nextStepFrame = 0;
        lastStep = -1;
        // Start at the chosen volume; smoothing is only for changes while playing.
        volume = volumeTarget;
        x1 = x2 = y1 = y2 = compEnvelope = limitEnvelope = 0;
    }

    private void beginStop() {
        if (running) {
            running = false;
            fade = STOP_FADE;
        } else if (fade == 0) {
            finishStop();
        }
    }

    private void finishStop() {
        for (Voice voice : voices) voice.active = false;
        running = alive = false;
        fade = 0;
        active = false;
    }

    private void tick() {
        if (step % Beat.STEPS_PER_BAR == 0) {
            Beat next = pending.getAndSet(null);
            if (next != null) {
                beat = next;
                anchorFrame = nextStepFrame;
                anchorStep = step;
            }
        }
        for (int t = 0; t < beat.trackCount(); t++) {
            Beat.Track track = beat.track(t);
            if (track.hits(step)) trigger(t, track);
        }
        lastStep = step;
        step++;
        nextStepFrame = anchorFrame + Math.round(stepOffset(step - anchorStep, beat.stepFrames(), beat.swing));
    }

    /** Frames from a bar-aligned anchor to step {@code n}, with swing delaying every odd 16th. */
    static double stepOffset(long n, double stepFrames, float swing) {
        double pairStart = (n / 2) * 2 * stepFrames;
        return (n & 1) == 0 ? pairStart : pairStart + 2 * stepFrames * swing;
    }

    private void trigger(int trackIndex, Beat.Track track) {
        if (track.samples != null) {
            choke(trackIndex, false);
            allocate().startSample(trackIndex, track, track.start(step), track.stretch(step), voiceClock++);
        }
        if (track.thump) {
            choke(trackIndex, true);
            float gain = track.samples == null ? track.gain : track.gain * THUMP_UNDER_SOUND;
            double low = speakerMode ? SPEAKER_THUMP_LOW : HEADPHONE_THUMP_LOW;
            allocate().startThump(trackIndex, fold(beat.rootHz, low), gain, voiceClock++);
        }
    }

    /** Moves a pitch by octaves into [low, 2 * low). */
    static double fold(double hz, double low) {
        while (hz < low) hz *= 2;
        while (hz >= low * 2) hz /= 2;
        return hz;
    }

    private void choke(int trackIndex, boolean thump) {
        for (Voice voice : voices)
            if (voice.active && voice.track == trackIndex && voice.thump == thump && voice.release < 0)
                voice.release = CHOKE;
    }

    private Voice allocate() {
        Voice oldest = voices[0];
        for (Voice voice : voices) {
            if (!voice.active) return voice;
            if (voice.started < oldest.started) oldest = voice;
        }
        return oldest;
    }

    private void updateFilter() {
        boolean speaker = speakerMode;
        if (filterReady && speaker == filterSpeaker) return;
        filterSpeaker = speaker;
        filterReady = true;
        double[] c = speaker ? SPEAKER_FILTER : HEADPHONE_FILTER;
        b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4];
    }

    private float master(float x) {
        double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = x; y2 = y1; y1 = y;
        double level = Math.abs(y);
        double smoothing = level > compEnvelope ? compAttack : compRelease;
        compEnvelope = smoothing * compEnvelope + (1 - smoothing) * level;
        if (compEnvelope > COMP_THRESHOLD) y *= Math.pow(COMP_THRESHOLD / compEnvelope, 1 - 1 / COMP_RATIO);
        y = Math.tanh(DRIVE * y) / TANH_DRIVE;
        limitEnvelope = Math.max(Math.abs(y), limitEnvelope * limitRelease);
        if (limitEnvelope > CEILING) y *= CEILING / limitEnvelope;
        return (float) Math.max(-CEILING, Math.min(CEILING, y));
    }

    private static double smoothing(double seconds) {
        return Math.exp(-1.0 / (seconds * RATE));
    }

    private static final class Voice {
        boolean active;
        boolean thump;
        int track;
        float[] samples;
        double position;
        int end;
        double rate;
        int limit;
        double[] lowPass, highPass;
        double lx1, lx2, ly1, ly2, hx1, hx2, hy1, hy2;
        float gain;
        double baseHz;
        double phase;
        int age;
        int release = -1;
        long started;

        void startSample(int track, Beat.Track source, int start, int length, long started) {
            reset(track, false, source.gain, started);
            samples = source.samples;
            position = start;
            end = Math.min(samples.length, start + length);
            rate = source.rate;
            limit = source.maxFrames > 0 ? source.maxFrames : Integer.MAX_VALUE;
            lowPass = source.lowPass;
            highPass = source.highPass;
            lx1 = lx2 = ly1 = ly2 = hx1 = hx2 = hy1 = hy2 = 0;
        }

        void startThump(int track, double hz, float gain, long started) {
            reset(track, true, gain, started);
            samples = null;
            baseHz = hz;
            phase = 0;
        }

        private void reset(int track, boolean thump, float gain, long started) {
            active = true;
            this.thump = thump;
            this.track = track;
            this.gain = gain;
            this.started = started;
            age = 0;
            release = -1;
        }

        float next() {
            double value;
            if (thump) {
                double t = age / (double) RATE;
                if (t >= 0.25) { active = false; return 0; }
                // Falls from 2.5x its pitch in about 30 ms, then decays by 60 dB over 180 ms.
                phase += 2 * Math.PI * baseHz * (1 + 1.5 * Math.exp(-t / 0.010)) / RATE;
                value = Math.sin(phase) * Math.exp(-t / 0.026);
            } else {
                int index = (int) position;
                if (index + 1 >= end) { active = false; return 0; }
                // Fade out before a cut ends or the length limit, so stretches never click.
                if (release < 0) {
                    int left = Math.min(limit - age, (int) ((end - 1 - position) / rate));
                    if (left <= CHOKE) release = Math.max(0, left);
                }
                double fraction = position - index;
                value = samples[index] + (samples[index + 1] - samples[index]) * fraction;
                position += rate;
                if (lowPass != null) {
                    double y = lowPass[0] * value + lowPass[1] * lx1 + lowPass[2] * lx2 - lowPass[3] * ly1 - lowPass[4] * ly2;
                    lx2 = lx1; lx1 = value; ly2 = ly1; ly1 = y;
                    value = y;
                }
                if (highPass != null) {
                    double y = highPass[0] * value + highPass[1] * hx1 + highPass[2] * hx2 - highPass[3] * hy1 - highPass[4] * hy2;
                    hx2 = hx1; hx1 = value; hy2 = hy1; hy1 = y;
                    value = y;
                }
            }
            float envelope = age < ATTACK ? (age + 1) / (float) ATTACK : 1f;
            if (release >= 0) {
                if (release == 0) { active = false; return 0; }
                envelope *= release / (float) CHOKE;
                release--;
            }
            age++;
            return (float) value * envelope * gain;
        }
    }
}
