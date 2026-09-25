package com.kevtrinh.rabbitphone.beats;

import java.util.Arrays;

/** Turns a raw catch into a tight, loud, click-free sound, or says why it can't. */
public final class Cleaner {
    /** The side button's own click lives in these edges of every catch. */
    public static final int HEAD_GUARD_MS = 40;
    public static final int TAIL_GUARD_MS = 80;
    public static final String TOO_QUIET = "Didn't hear much. Try closer.";
    static final double START_DB = -30, END_DB = -40, FLOOR_DBFS = -40;
    static final int MIN_SOUND_MS = 60;
    static final float TARGET_PEAK = 0.891f;
    static final float CLIP_LEVEL = 0.999f, CLIP_SHARE = 0.005f;
    private static final int WINDOW_MS = 5, PRE_ROLL_MS = 5, POST_ROLL_MS = 20;
    private static final int FADE_IN_MS = 2, FADE_OUT_MS = 15;

    public static final class Result {
        /** The cleaned sound, or null when the catch was rejected. */
        public final float[] samples;
        /** Why the catch was rejected, or null. */
        public final String problem;
        public final boolean clipped;

        Result(float[] samples, String problem, boolean clipped) {
            this.samples = samples;
            this.problem = problem;
            this.clipped = clipped;
        }
    }

    private Cleaner() { }

    public static Result clean(float[] raw) {
        int clippedCount = 0;
        for (float value : raw) if (Math.abs(value) >= CLIP_LEVEL) clippedCount++;
        boolean clipped = raw.length > 0 && clippedCount > raw.length * CLIP_SHARE;
        int head = frames(HEAD_GUARD_MS), tail = frames(TAIL_GUARD_MS);
        if (raw.length <= head + tail) return new Result(null, TOO_QUIET, clipped);

        // Remove DC with a 20 Hz one-pole high-pass, starting at rest so it adds no step.
        float[] x = new float[raw.length - head - tail];
        double pole = Math.exp(-2 * Math.PI * 20 / Engine.RATE);
        double previousIn = raw[head], previousOut = 0;
        float peak = 0;
        for (int i = 0; i < x.length; i++) {
            double in = raw[head + i];
            double out = in - previousIn + pole * previousOut;
            previousIn = in;
            previousOut = out;
            x[i] = (float) out;
            peak = Math.max(peak, Math.abs(x[i]));
        }
        if (peak < gain(FLOOR_DBFS)) return new Result(null, TOO_QUIET, clipped);

        int window = frames(WINDOW_MS);
        int windows = (x.length + window - 1) / window;
        double[] rms = new double[windows];
        double peakRms = 0;
        for (int w = 0; w < windows; w++) {
            int from = w * window, to = Math.min(x.length, from + window);
            double sum = 0;
            for (int i = from; i < to; i++) sum += x[i] * x[i];
            rms[w] = Math.sqrt(sum / (to - from));
            peakRms = Math.max(peakRms, rms[w]);
        }
        int first = 0, last = windows - 1;
        while (rms[first] < peakRms * gain(START_DB)) first++;
        while (rms[last] < peakRms * gain(END_DB)) last--;
        if ((last - first + 1) * window < frames(MIN_SOUND_MS)) return new Result(null, TOO_QUIET, clipped);

        int start = Math.max(0, first * window - frames(PRE_ROLL_MS));
        int end = Math.min(x.length, (last + 1) * window + frames(POST_ROLL_MS));
        float[] out = Arrays.copyOfRange(x, start, end);
        int fadeIn = Math.min(out.length, frames(FADE_IN_MS));
        for (int i = 0; i < fadeIn; i++) out[i] *= i / (float) fadeIn;
        int fadeOut = Math.min(out.length, frames(FADE_OUT_MS));
        for (int i = 0; i < fadeOut; i++) out[out.length - 1 - i] *= i / (float) fadeOut;
        float cleanedPeak = 0;
        for (float value : out) cleanedPeak = Math.max(cleanedPeak, Math.abs(value));
        if (cleanedPeak > 0) {
            float scale = TARGET_PEAK / cleanedPeak;
            for (int i = 0; i < out.length; i++) out[i] *= scale;
        }
        return new Result(out, null, clipped);
    }

    static int frames(int milliseconds) {
        return Engine.RATE * milliseconds / 1000;
    }

    private static double gain(double decibels) {
        return Math.pow(10, decibels / 20);
    }
}
