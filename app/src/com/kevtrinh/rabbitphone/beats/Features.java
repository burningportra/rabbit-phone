package com.kevtrinh.rabbitphone.beats;

import java.util.Arrays;

/** Numbers that describe a stretch of sound. The sorter and the chopper decide from these. */
public final class Features {
    static final int FFT_SIZE = 1024, FFT_HOP = 256;
    /** Frames within 20 dB of the loudest one set the centroid; pauses full of hiss don't. */
    static final double LOUD_FRAME = 0.01;
    private static final double BIN_HZ = Engine.RATE / (double) FFT_SIZE;
    private static final int ENVELOPE = Engine.RATE / 200;

    public final float seconds;
    /** Median power-weighted spectral centroid of the louder frames. */
    public final float centroidHz;
    /** Shares of the total power below 150 Hz, from 150 to 800 Hz, and above 4 kHz. */
    public final float lowShare, bodyShare, highShare;
    public final float zeroCrossingsPerSecond;
    /** From the first loud 5 ms window to the loudest one. */
    public final float attackSeconds;
    /** From the loudest 5 ms window until it has fallen 20 dB. */
    public final float decaySeconds;
    /** Pitch summary; zero when pitch wasn't measured. */
    public final float voicedFraction, medianHz, pitchSpreadSemitones;

    private Features(float seconds, float centroidHz, float lowShare, float bodyShare, float highShare,
            float zeroCrossingsPerSecond, float attackSeconds, float decaySeconds,
            float voicedFraction, float medianHz, float pitchSpreadSemitones) {
        this.seconds = seconds;
        this.centroidHz = centroidHz;
        this.lowShare = lowShare;
        this.bodyShare = bodyShare;
        this.highShare = highShare;
        this.zeroCrossingsPerSecond = zeroCrossingsPerSecond;
        this.attackSeconds = attackSeconds;
        this.decaySeconds = decaySeconds;
        this.voicedFraction = voicedFraction;
        this.medianHz = medianHz;
        this.pitchSpreadSemitones = pitchSpreadSemitones;
    }

    public static Features of(float[] samples) {
        return measure(samples, 0, samples.length, true);
    }

    /** Features read back from storage. */
    static Features stored(float seconds, float centroidHz, float lowShare, float bodyShare, float highShare,
            float zeroCrossingsPerSecond, float attackSeconds, float decaySeconds,
            float voicedFraction, float medianHz, float pitchSpreadSemitones) {
        return new Features(seconds, centroidHz, lowShare, bodyShare, highShare, zeroCrossingsPerSecond,
                attackSeconds, decaySeconds, voicedFraction, medianHz, pitchSpreadSemitones);
    }

    /** Measures {@code samples[from, to)}; pitch is optional because it's the slow part. */
    public static Features measure(float[] samples, int from, int to, boolean withPitch) {
        from = Math.max(0, from);
        to = Math.min(samples.length, to);
        int length = Math.max(0, to - from);
        float seconds = length / (float) Engine.RATE;
        if (length == 0) return new Features(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        Fft fft = new Fft(FFT_SIZE);
        double[] re = new double[FFT_SIZE], im = new double[FFT_SIZE], window = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
        int frames = Math.max(1, (length - FFT_SIZE) / FFT_HOP + 1);
        double[] centroids = new double[frames], energies = new double[frames];
        double low = 0, body = 0, high = 0, total = 0, loudest = 0;
        for (int f = 0; f < frames; f++) {
            int start = from + f * FFT_HOP;
            for (int i = 0; i < FFT_SIZE; i++) {
                int j = start + i;
                re[i] = j < to ? samples[j] * window[i] : 0;
                im[i] = 0;
            }
            fft.transform(re, im);
            // Power weighting keeps a microphone's broadband hiss from dragging the centroid up.
            double weighted = 0, energy = 0;
            for (int k = 1; k <= FFT_SIZE / 2; k++) {
                double power = re[k] * re[k] + im[k] * im[k], hz = k * BIN_HZ;
                weighted += hz * power;
                energy += power;
                if (hz < 150) low += power;
                else if (hz < 800) body += power;
                else if (hz > 4000) high += power;
            }
            total += energy;
            energies[f] = energy;
            centroids[f] = energy == 0 ? 0 : weighted / energy;
            loudest = Math.max(loudest, energy);
        }
        double[] loud = new double[frames];
        int count = 0;
        for (int f = 0; f < frames; f++) if (energies[f] >= loudest * LOUD_FRAME && energies[f] > 0) loud[count++] = centroids[f];
        double centroid = 0;
        if (count > 0) {
            Arrays.sort(loud, 0, count);
            centroid = loud[count / 2];
        }

        int crossings = 0;
        for (int i = from + 1; i < to; i++) if ((samples[i - 1] < 0) != (samples[i] < 0)) crossings++;

        int windows = Math.max(1, (length + ENVELOPE - 1) / ENVELOPE);
        double[] rms = new double[windows];
        int peakWindow = 0;
        for (int w = 0; w < windows; w++) {
            int a = from + w * ENVELOPE, b = Math.min(to, a + ENVELOPE);
            double sum = 0;
            for (int i = a; i < b; i++) sum += samples[i] * samples[i];
            rms[w] = b > a ? Math.sqrt(sum / (b - a)) : 0;
            if (rms[w] > rms[peakWindow]) peakWindow = w;
        }
        int first = 0;
        while (first < peakWindow && rms[first] < rms[peakWindow] * 0.0316) first++;
        int fallen = peakWindow;
        while (fallen < windows && rms[fallen] >= rms[peakWindow] * 0.1) fallen++;

        float voiced = 0, median = 0, spread = 0;
        if (withPitch) {
            Pitch pitch = Pitch.track(from == 0 && to == samples.length ? samples : Arrays.copyOfRange(samples, from, to));
            voiced = pitch.voicedFraction();
            median = pitch.medianHz();
            spread = pitch.spreadSemitones();
        }
        float share = total > 0 ? 1f / (float) total : 0;
        return new Features(seconds, (float) centroid, (float) low * share, (float) body * share, (float) high * share,
                crossings / Math.max(seconds, 1e-3f), (peakWindow - first) * ENVELOPE / (float) Engine.RATE,
                (fallen - peakWindow) * ENVELOPE / (float) Engine.RATE, voiced, median, spread);
    }
}
