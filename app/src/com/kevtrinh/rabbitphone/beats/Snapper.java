package com.kevtrinh.rabbitphone.beats;

/** Puts each piece of a phrase on the nearest (swung) 16th step, keeping their order. */
public final class Snapper {
    public static final int MAX_BARS = 8;

    private Snapper() { }

    /**
     * Steps for onsets measured in frames from the phrase's first onset. Pieces that would
     * land past {@link #MAX_BARS} bars get -1.
     */
    public static int[] steps(int[] onsets, int bpm, float swing) {
        double stepFrames = Engine.RATE * 60.0 / (bpm * 4.0);
        int limit = MAX_BARS * Beat.STEPS_PER_BAR;
        int[] steps = new int[onsets.length];
        int previous = -1;
        for (int i = 0; i < onsets.length; i++) {
            int step = nearest(onsets[i], stepFrames, swing);
            if (step <= previous) step = previous + 1;
            if (step >= limit) {
                for (int j = i; j < steps.length; j++) steps[j] = -1;
                break;
            }
            steps[i] = step;
            previous = step;
        }
        return steps;
    }

    /** The phrase repeats every 1, 2, 4 or 8 bars: the first length that holds its last step. */
    public static int bars(int lastStep) {
        int bars = 1;
        while (bars < MAX_BARS && lastStep >= bars * Beat.STEPS_PER_BAR) bars *= 2;
        return bars;
    }

    static int nearest(int onset, double stepFrames, float swing) {
        int pair = (int) Math.floor(onset / (2 * stepFrames));
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int n = Math.max(0, 2 * pair - 1); n <= 2 * pair + 2; n++) {
            double distance = Math.abs(Engine.stepOffset(n, stepFrames, swing) - onset);
            if (distance < bestDistance) { bestDistance = distance; best = n; }
        }
        return best;
    }
}
