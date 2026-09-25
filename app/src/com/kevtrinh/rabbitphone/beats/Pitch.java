package com.kevtrinh.rabbitphone.beats;

import java.util.Arrays;

/**
 * YIN pitch tracking on a 16 kHz copy of a sound, with 30 ms frames every 10 ms. Like a
 * guitar tuner, it only looks at the sound itself.
 */
public final class Pitch {
    public static final double MIN_HZ = 70, MAX_HZ = 1000;
    static final int RATE = 16_000, FRAME = 480, HOP = 160;
    static final double THRESHOLD = 0.15;
    /** With no dip under the threshold, the deepest dip still counts if it's under this. */
    static final double FALLBACK = 0.35;
    /** Frames quieter than this RMS are never called pitched. */
    static final double MIN_RMS = 0.01;
    /** Only frames within 20 dB of the loudest count toward how pitched a sound is. */
    static final double LOUD_SHARE = 0.1;
    private static final double[] LOW_PASS = lowPass();

    /** Pitch per frame in Hz, or 0 where the frame isn't pitched. */
    public final float[] hz;
    private final float[] rms;

    private Pitch(float[] hz, float[] rms) {
        this.hz = hz;
        this.rms = rms;
    }

    public static Pitch track(float[] samples) {
        float[] y = downsample(samples);
        int tauMin = (int) Math.floor(RATE / MAX_HZ), tauMax = (int) Math.ceil(RATE / MIN_HZ);
        int frames = Math.max(0, (y.length - FRAME - tauMax - 1) / HOP + 1);
        float[] hz = new float[frames], rms = new float[frames];
        double[] normalized = new double[tauMax + 2];
        for (int f = 0; f < frames; f++) {
            int s = f * HOP;
            double energy = 0;
            for (int j = 0; j < FRAME; j++) energy += y[s + j] * y[s + j];
            rms[f] = (float) Math.sqrt(energy / FRAME);
            if (rms[f] < MIN_RMS) continue;
            double running = 0;
            normalized[0] = 1;
            for (int tau = 1; tau <= tauMax + 1; tau++) {
                double sum = 0;
                for (int j = 0; j < FRAME; j++) {
                    double delta = y[s + j] - y[s + j + tau];
                    sum += delta * delta;
                }
                running += sum;
                normalized[tau] = running == 0 ? 1 : sum * tau / running;
            }
            int best = -1, deepest = tauMin;
            for (int tau = tauMin; tau <= tauMax; tau++) {
                if (normalized[tau] < normalized[deepest]) deepest = tau;
                if (normalized[tau] < THRESHOLD) {
                    while (tau + 1 <= tauMax && normalized[tau + 1] < normalized[tau]) tau++;
                    best = tau;
                    break;
                }
            }
            if (best < 0 && normalized[deepest] < FALLBACK) best = deepest;
            if (best < 0) continue;
            double shift = 0;
            if (best > tauMin && best < tauMax) {
                double a = normalized[best - 1], b = normalized[best], c = normalized[best + 1];
                double denominator = a - 2 * b + c;
                if (denominator != 0) shift = 0.5 * (a - c) / denominator;
            }
            hz[f] = (float) (RATE / (best + shift));
        }
        return new Pitch(hz, rms);
    }

    /** The share of the loud frames that are pitched; pauses between words don't count. */
    public float voicedFraction() {
        float loudest = 0;
        for (float value : rms) loudest = Math.max(loudest, value);
        int loud = 0, voiced = 0;
        for (int f = 0; f < hz.length; f++) {
            if (rms[f] < loudest * LOUD_SHARE || rms[f] < MIN_RMS) continue;
            loud++;
            if (hz[f] > 0) voiced++;
        }
        return loud == 0 ? 0 : voiced / (float) loud;
    }

    public float medianHz() {
        float[] voiced = voiced();
        if (voiced.length == 0) return 0;
        Arrays.sort(voiced);
        return voiced[voiced.length / 2];
    }

    /** How far the pitch wanders, as a standard deviation in semitones around the median. */
    public float spreadSemitones() {
        float[] voiced = voiced();
        if (voiced.length < 2) return 0;
        double median = medianHz(), sum = 0, squares = 0;
        for (float value : voiced) {
            double semitones = 12 * Math.log(value / median) / Math.log(2);
            sum += semitones;
            squares += semitones * semitones;
        }
        double mean = sum / voiced.length;
        return (float) Math.sqrt(Math.max(0, squares / voiced.length - mean * mean));
    }

    private float[] voiced() {
        int count = 0;
        for (float value : hz) if (value > 0) count++;
        float[] out = new float[count];
        int at = 0;
        for (float value : hz) if (value > 0) out[at++] = value;
        return out;
    }

    /** A 31-tap Hamming-windowed sinc at 7 kHz, then every third sample. */
    static float[] downsample(float[] x) {
        int half = LOW_PASS.length / 2;
        float[] y = new float[x.length / 3];
        for (int i = 0; i < y.length; i++) {
            int center = i * 3;
            double sum = 0;
            for (int n = 0; n < LOW_PASS.length; n++) {
                int j = center + n - half;
                if (j >= 0 && j < x.length) sum += LOW_PASS[n] * x[j];
            }
            y[i] = (float) sum;
        }
        return y;
    }

    private static double[] lowPass() {
        int taps = 31, half = taps / 2;
        double cutoff = 7000.0 / Engine.RATE, total = 0;
        double[] h = new double[taps];
        for (int n = 0; n < taps; n++) {
            int k = n - half;
            double sinc = k == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * k) / (Math.PI * k);
            h[n] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * n / (taps - 1)));
            total += h[n];
        }
        for (int n = 0; n < taps; n++) h[n] /= total;
        return h;
    }
}
