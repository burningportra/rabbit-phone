package com.kevtrinh.rabbitphone.beats;

import java.util.ArrayList;
import java.util.List;

/** Splits a sound at the dips between syllables and picks the bits that make good drums. */
public final class Chopper {
    static final double MIN_DEPTH_DB = 9, SILENCE_DB = 40;
    static final int MIN_PIECE_MS = 80, LOOKAROUND_MS = 300, PRE_ROLL_MS = 5;
    static final int BOOM_MS = 250, SNAP_MS = 180, TICK_MS = 90;
    private static final int WINDOW = Engine.RATE / 100, HOP = Engine.RATE / 200;

    /** A syllable-sized piece: play from {@code start}, snap on {@code onset}, stop at {@code end}. */
    public static final class Piece {
        public final int start, onset, end;

        public Piece(int start, int onset, int end) {
            if (start < 0 || onset < start || end <= onset) throw new IllegalArgumentException("Bad piece bounds");
            this.start = start;
            this.onset = onset;
            this.end = end;
        }
    }

    /** A stretch of the sound to use as a drum. */
    public static final class Cut {
        public final int start, length;

        public Cut(int start, int length) {
            if (start < 0 || length <= 0) throw new IllegalArgumentException("Bad cut bounds");
            this.start = start;
            this.length = length;
        }
    }

    public static final class Result {
        public final Piece[] pieces;
        public final Cut boom, snap, tick;

        Result(Piece[] pieces, Cut boom, Cut snap, Cut tick) {
            this.pieces = pieces;
            this.boom = boom;
            this.snap = snap;
            this.tick = tick;
        }
    }

    private Chopper() { }

    public static Result chop(float[] samples) {
        Piece[] pieces = pieces(samples);
        double bestBoom = -1, bestTick = -1, bestSnap = -1;
        Piece boom = pieces[0], tick = pieces[0], snap = pieces[0];
        for (Piece piece : pieces) {
            Features f = Features.measure(samples, piece.onset, Math.min(piece.end, piece.onset + frames(SNAP_MS)), false);
            double attack = 1 / (1 + f.attackSeconds * 100);
            double boomScore = (f.lowShare + f.bodyShare) * attack;
            double tickScore = f.highShare * Math.sqrt(attack);
            double snapScore = attack * Math.max(0, 1 - Math.abs(Math.log(Math.max(f.centroidHz, 1) / 1500) / Math.log(2)) / 3);
            if (boomScore > bestBoom) { bestBoom = boomScore; boom = piece; }
            if (tickScore > bestTick) { bestTick = tickScore; tick = piece; }
            if (snapScore > bestSnap) { bestSnap = snapScore; snap = piece; }
        }
        // With more than one piece, the snare and the kick shouldn't be the same syllable.
        if (pieces.length > 1 && snap == boom) {
            bestSnap = -1;
            for (Piece piece : pieces) {
                if (piece == boom) continue;
                Features f = Features.measure(samples, piece.onset, Math.min(piece.end, piece.onset + frames(SNAP_MS)), false);
                double score = 1 / (1 + f.attackSeconds * 100);
                if (score > bestSnap) { bestSnap = score; snap = piece; }
            }
        }
        return new Result(pieces, cut(boom, BOOM_MS), cut(snap, SNAP_MS), cut(tick, TICK_MS));
    }

    static Piece[] pieces(float[] samples) {
        int windows = Math.max(1, (samples.length - WINDOW) / HOP + 1);
        double[] level = new double[windows];
        for (int w = 0; w < windows; w++) {
            int a = w * HOP, b = Math.min(samples.length, a + WINDOW);
            double sum = 0;
            for (int i = a; i < b; i++) sum += samples[i] * samples[i];
            level[w] = 10 * Math.log10(sum / Math.max(1, b - a) + 1e-12);
        }
        double[] smooth = new double[windows];
        for (int w = 0; w < windows; w++) {
            double sum = 0;
            int count = 0;
            for (int k = Math.max(0, w - 1); k <= Math.min(windows - 1, w + 1); k++) { sum += level[k]; count++; }
            smooth[w] = sum / count;
        }
        double peak = -1e9;
        for (double value : smooth) peak = Math.max(peak, value);
        int minimum = frames(MIN_PIECE_MS) / HOP, around = frames(LOOKAROUND_MS) / HOP;

        List<Integer> cuts = new ArrayList<>();
        int last = 0;
        for (int w = 1; w < windows - 1; w++) {
            if (!(smooth[w] < smooth[w - 1] && smooth[w] <= smooth[w + 1])) continue;
            if (w - last < minimum || windows - w < minimum) continue;
            double left = -1e9, right = -1e9;
            for (int k = last; k <= w; k++) left = Math.max(left, smooth[k]);
            for (int k = w; k <= Math.min(windows - 1, w + around); k++) right = Math.max(right, smooth[k]);
            if (Math.min(left, right) - smooth[w] < MIN_DEPTH_DB) continue;
            cuts.add(w);
            last = w;
        }

        List<Piece> pieces = new ArrayList<>();
        int boundary = 0;
        for (int c = 0; c <= cuts.size(); c++) {
            int from = boundary, to = c < cuts.size() ? cuts.get(c) : windows;
            boundary = to;
            double piecePeak = -1e9;
            for (int w = from; w < to; w++) piecePeak = Math.max(piecePeak, smooth[w]);
            if (piecePeak < peak - SILENCE_DB) continue;
            int onsetWindow = from;
            while (onsetWindow < to && smooth[onsetWindow] < piecePeak - 20) onsetWindow++;
            int startSample = from * HOP, onset = Math.max(startSample, onsetWindow * HOP);
            int end = c < cuts.size() ? to * HOP + WINDOW / 2 : samples.length;
            end = Math.min(samples.length, Math.max(end, onset + 1));
            pieces.add(new Piece(Math.max(startSample, onset - frames(PRE_ROLL_MS)), onset, end));
        }
        if (pieces.isEmpty()) pieces.add(new Piece(0, 0, Math.max(1, samples.length)));
        return pieces.toArray(new Piece[0]);
    }

    private static Cut cut(Piece piece, int milliseconds) {
        return new Cut(piece.start, Math.max(1, Math.min(piece.end - piece.start, frames(milliseconds) + piece.onset - piece.start)));
    }

    static int frames(int milliseconds) { return Engine.RATE * milliseconds / 1000; }
}
