package com.kevtrinh.rabbitphone.beats;

import java.util.Map;

/**
 * Builds a beat from the newest sound for each job. Missing drums are cut from the newest
 * voice (or tune), so a single catch already makes a whole beat. The beat number picks
 * the tempo and each row's pattern; the same inputs always give the same beat.
 */
public final class Arranger {
    public static final int FIRST_BEAT = 1, LAST_BEAT = 999;
    /** A root of A; the engine picks the thump's octave. */
    static final float ROOT_HZ = 55f;
    static final float BOOM_GAIN = 0.9f, SNAP_GAIN = 0.7f, TICK_GAIN = 0.45f, TUNE_GAIN = 0.6f, VOICE_GAIN = 0.8f;
    /** Cut kicks drop a fourth so a syllable reads as a drum. */
    static final float CUT_BOOM_RATE = (float) Math.pow(2, -5 / 12.0);
    private static final Job[] DRUMS = {Job.BOOM, Job.SNAP, Job.TICK};

    /** A sound in the jar with what's known about it. */
    public static final class Sound {
        public final String id;
        public final float[] samples;
        public final Analysis analysis;

        public Sound(String id, float[] samples, Analysis analysis) {
            this.id = id;
            this.samples = samples;
            this.analysis = analysis;
        }
    }

    private Arranger() { }

    public static Beat build(int number, Integer bpmOverride, Map<Job, Sound> sounds) {
        Style style = Style.BOUNCE;
        int bpm = bpmOverride != null ? bpmOverride : tempo(style, number);
        Sound voice = sounds.get(Job.VOICE), tune = sounds.get(Job.TUNE);
        Sound source = voice != null && voice.analysis != null ? voice : tune != null && tune.analysis != null ? tune : null;
        String cutLabel = source == null ? "" : "cut from " + source.analysis.job.label();
        java.util.List<Beat.Track> tracks = new java.util.ArrayList<>();
        for (Job drum : DRUMS) {
            boolean[] steps = pattern(style, drum, number);
            Sound own = sounds.get(drum);
            boolean thump = drum == Job.BOOM;
            if (own != null) {
                tracks.add(Beat.Track.hits(drum, "catch", own.samples, 0, own.samples.length, steps,
                        gain(drum), thump, voicing(drum, false)));
            } else if (source != null) {
                Chopper.Cut cut = source.analysis.cut(drum);
                tracks.add(Beat.Track.hits(drum, cutLabel, source.samples, cut.start, cut.length, steps,
                        gain(drum), thump, voicing(drum, true)));
            } else if (thump) {
                tracks.add(Beat.Track.hits(Job.BOOM, "thump", null, 0, 0, steps, 1f, true, Beat.Voicing.NONE));
            }
        }
        if (tune != null) {
            tracks.add(Beat.Track.hits(Job.TUNE, "catch", tune.samples, 0, tune.samples.length,
                    pattern(style, Job.TUNE, number), TUNE_GAIN, false, Beat.Voicing.NONE));
        }
        if (voice != null) tracks.add(phrase(voice, bpm, style.swing));
        return new Beat(bpm, style.swing, ROOT_HZ, tracks.toArray(new Beat.Track[0]));
    }

    /** The tempo a beat number picks inside its style's range. */
    public static int tempo(Style style, int number) {
        return style.minBpm + Math.floorMod(mix(number, -1), style.maxBpm - style.minBpm + 1);
    }

    static boolean[] pattern(Style style, Job job, int number) {
        String[] bank = style.patterns(job);
        return pattern(bank[Math.floorMod(mix(number, job.ordinal()), bank.length)]);
    }

    /** A voice's syllables on the swung 16th grid, looping every 1, 2, 4 or 8 bars. */
    static Beat.Track phrase(Sound voice, int bpm, float swing) {
        Chopper.Piece[] pieces = voice.analysis != null ? voice.analysis.pieces
                : new Chopper.Piece[] {new Chopper.Piece(0, 0, voice.samples.length)};
        int[] onsets = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) onsets[i] = pieces[i].onset - pieces[0].onset;
        int[] steps = Snapper.steps(onsets, bpm, swing);
        int last = 0;
        for (int step : steps) last = Math.max(last, step);
        int length = Snapper.bars(last) * Beat.STEPS_PER_BAR;
        int[] starts = new int[length], lengths = new int[length];
        java.util.Arrays.fill(starts, -1);
        for (int i = 0; i < pieces.length; i++) {
            if (steps[i] < 0) break;
            starts[steps[i]] = pieces[i].start;
            lengths[steps[i]] = pieces[i].end - pieces[i].start;
        }
        return Beat.Track.pieces(Job.VOICE, "catch", voice.samples, starts, lengths, VOICE_GAIN, Beat.Voicing.NONE);
    }

    static Beat.Voicing voicing(Job drum, boolean cut) {
        switch (drum) {
            case BOOM: return new Beat.Voicing(cut ? CUT_BOOM_RATE : 1f, cut ? 1200 : 1500, 0, Chopper.BOOM_MS);
            case SNAP: return new Beat.Voicing(1f, 0, cut ? 250 : 200, Chopper.SNAP_MS);
            case TICK: return new Beat.Voicing(1f, 0, cut ? 3500 : 3000, Chopper.TICK_MS);
            default: return Beat.Voicing.NONE;
        }
    }

    private static float gain(Job drum) {
        return drum == Job.BOOM ? BOOM_GAIN : drum == Job.SNAP ? SNAP_GAIN : TICK_GAIN;
    }

    static boolean[] pattern(String text) {
        boolean[] steps = new boolean[text.length()];
        for (int i = 0; i < steps.length; i++) steps[i] = text.charAt(i) == 'x';
        return steps;
    }

    /** A stable integer hash, so a beat number always means the same beat. */
    static int mix(int number, int salt) {
        int h = number * 0x9E3779B1 ^ (salt + 7) * 0x85EBCA6B;
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return h;
    }
}
