package com.kevtrinh.rabbitphone.beats;

/** Second-order Butterworth filter coefficients {b0, b1, b2, a1, a2} at the engine's rate. */
final class Biquad {
    private Biquad() { }

    static double[] lowPass(double hz) { return design(hz, false); }

    static double[] highPass(double hz) { return design(hz, true); }

    private static double[] design(double hz, boolean high) {
        double w = 2 * Math.PI * Math.min(hz, Engine.RATE * 0.45) / Engine.RATE;
        double cos = Math.cos(w), alpha = Math.sin(w) / (2 * Math.sqrt(0.5)), a0 = 1 + alpha;
        double b0 = (high ? (1 + cos) / 2 : (1 - cos) / 2) / a0;
        double b1 = (high ? -(1 + cos) : 1 - cos) / a0;
        return new double[] {b0, b1, b0, -2 * cos / a0, (1 - alpha) / a0};
    }
}
