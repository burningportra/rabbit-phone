package com.kevtrinh.rabbitphone.beats;

/** An immutable beat: tempo, swing, and tracks that each loop over whole bars of 16th steps. */
public final class Beat {
    public static final int STEPS_PER_BAR = 16;
    public static final int MIN_BPM = 60, MAX_BPM = 160;

    /** How a track's hits are played: speed (and so pitch), filters, and a length limit. */
    public static final class Voicing {
        public static final Voicing NONE = new Voicing(1, 0, 0, 0);
        public final float rate, lowPassHz, highPassHz;
        public final int maxMs;

        public Voicing(float rate, float lowPassHz, float highPassHz, int maxMs) {
            if (rate <= 0) throw new IllegalArgumentException("Rate must be positive");
            this.rate = rate;
            this.lowPassHz = lowPassHz;
            this.highPassHz = highPassHz;
            this.maxMs = maxMs;
        }
    }

    public static final class Track {
        public final Job job;
        /** What the row says about where the sound came from, such as "catch" or "cut from voice". */
        public final String label;
        /** The sound to play, or null for a track that only plays the hidden thump. */
        public final float[] samples;
        public final float gain;
        /** Also play the hidden thump on every hit. */
        public final boolean thump;
        public final float rate;
        /** Output frames per hit; 0 plays to the end of the hit's stretch. */
        public final int maxFrames;
        final double[] lowPass, highPass;
        private final int[] starts, lengths;

        private Track(Job job, String label, float[] samples, int[] starts, int[] lengths, float gain,
                boolean thump, Voicing voicing) {
            if (job == null) throw new IllegalArgumentException("A track needs a job");
            if (starts.length == 0 || starts.length % STEPS_PER_BAR != 0 || lengths.length != starts.length)
                throw new IllegalArgumentException("Track steps must cover whole bars");
            if (samples == null && !thump) throw new IllegalArgumentException("A track needs a sound or the thump");
            for (int i = 0; i < starts.length; i++) {
                if (starts[i] < 0) continue;
                if (samples != null && (lengths[i] <= 0 || starts[i] + lengths[i] > samples.length))
                    throw new IllegalArgumentException("A hit reaches past its sound");
            }
            this.job = job;
            this.label = label == null ? "" : label;
            this.samples = samples;
            this.starts = starts;
            this.lengths = lengths;
            this.gain = gain;
            this.thump = thump;
            rate = voicing.rate;
            maxFrames = voicing.maxMs * Engine.RATE / 1000;
            lowPass = voicing.lowPassHz > 0 ? Biquad.lowPass(voicing.lowPassHz) : null;
            highPass = voicing.highPassHz > 0 ? Biquad.highPass(voicing.highPassHz) : null;
        }

        /** Every lit step plays the same stretch of the sound. */
        public static Track hits(Job job, String label, float[] samples, int start, int length, boolean[] steps,
                float gain, boolean thump, Voicing voicing) {
            int[] starts = new int[steps.length], lengths = new int[steps.length];
            for (int i = 0; i < steps.length; i++) {
                starts[i] = steps[i] ? (samples == null ? 0 : start) : -1;
                lengths[i] = steps[i] ? (samples == null ? 1 : length) : 0;
            }
            return new Track(job, label, samples, starts, lengths, gain, thump, voicing);
        }

        /** Each step may play its own stretch, like a phrase's syllables; a negative start is a rest. */
        public static Track pieces(Job job, String label, float[] samples, int[] starts, int[] lengths, float gain,
                Voicing voicing) {
            return new Track(job, label, samples, starts.clone(), lengths.clone(), gain, false, voicing);
        }

        public int length() { return starts.length; }

        public boolean hits(long step) { return starts[index(step)] >= 0; }

        int start(long step) { return starts[index(step)]; }

        int stretch(long step) { return lengths[index(step)]; }

        private int index(long step) { return (int) (step % starts.length); }
    }

    public final int bpm;
    /** The share of each pair of 16ths given to the first one: 0.5 is straight. */
    public final float swing;
    /** The song's root note; the engine picks the octave the speaker can play. */
    public final float rootHz;
    private final Track[] tracks;

    public Beat(int bpm, float swing, float rootHz, Track... tracks) {
        if (bpm < MIN_BPM || bpm > MAX_BPM) throw new IllegalArgumentException("BPM out of range: " + bpm);
        if (swing < 0.5f || swing > 0.75f) throw new IllegalArgumentException("Swing out of range: " + swing);
        if (rootHz <= 0) throw new IllegalArgumentException("Root must be positive");
        this.bpm = bpm;
        this.swing = swing;
        this.rootHz = rootHz;
        this.tracks = tracks.clone();
    }

    public int trackCount() { return tracks.length; }

    public Track track(int index) { return tracks[index]; }

    public double stepFrames() { return Engine.RATE * 60.0 / (bpm * 4.0); }
}
