package com.kevtrinh.rabbitphone.beats;

/** In-place radix-2 complex FFT with precomputed twiddles and bit reversal. */
final class Fft {
    final int size;
    private final int[] reversed;
    private final double[] cos, sin;

    Fft(int size) {
        if (size < 2 || Integer.bitCount(size) != 1) throw new IllegalArgumentException("FFT size must be a power of two");
        this.size = size;
        int bits = Integer.numberOfTrailingZeros(size);
        reversed = new int[size];
        for (int i = 0; i < size; i++) reversed[i] = Integer.reverse(i) >>> (32 - bits);
        cos = new double[size / 2];
        sin = new double[size / 2];
        for (int i = 0; i < size / 2; i++) {
            cos[i] = Math.cos(2 * Math.PI * i / size);
            sin[i] = -Math.sin(2 * Math.PI * i / size);
        }
    }

    void transform(double[] re, double[] im) {
        for (int i = 0; i < size; i++) {
            int j = reversed[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int length = 2; length <= size; length <<= 1) {
            int half = length / 2, stride = size / length;
            for (int start = 0; start < size; start += length) {
                for (int k = 0; k < half; k++) {
                    double wr = cos[k * stride], wi = sin[k * stride];
                    int a = start + k, b = a + half;
                    double xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                }
            }
        }
    }
}
