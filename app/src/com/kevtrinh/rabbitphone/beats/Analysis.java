package com.kevtrinh.rabbitphone.beats;

import java.util.Locale;
import java.util.Properties;

/** Everything the arranger needs to know about a cleaned sound, and how it's stored. */
public final class Analysis {
    /** Bump when the analysis changes, so stored sounds are analysed again. */
    static final int VERSION = 1;

    public final Job job;
    public final Features features;
    public final Chopper.Piece[] pieces;
    public final Chopper.Cut boom, snap, tick;

    private Analysis(Job job, Features features, Chopper.Piece[] pieces, Chopper.Cut boom, Chopper.Cut snap,
            Chopper.Cut tick) {
        this.job = job;
        this.features = features;
        this.pieces = pieces;
        this.boom = boom;
        this.snap = snap;
        this.tick = tick;
    }

    public static Analysis of(float[] samples) {
        Features features = Features.of(samples);
        Chopper.Result chop = Chopper.chop(samples);
        return new Analysis(Sorter.sort(features), features, chop.pieces, chop.boom, chop.snap, chop.tick);
    }

    /** The drum-sized cut for a drum job, taken from this sound. */
    public Chopper.Cut cut(Job drum) {
        switch (drum) {
            case BOOM: return boom;
            case SNAP: return snap;
            case TICK: return tick;
            default: throw new IllegalArgumentException("Only drums are cut: " + drum);
        }
    }

    public void write(Properties out) {
        out.setProperty("analysis", Integer.toString(VERSION));
        out.setProperty("job", job.name());
        Features f = features;
        out.setProperty("seconds", number(f.seconds));
        out.setProperty("centroid_hz", number(f.centroidHz));
        out.setProperty("low_share", number(f.lowShare));
        out.setProperty("body_share", number(f.bodyShare));
        out.setProperty("high_share", number(f.highShare));
        out.setProperty("crossings_per_second", number(f.zeroCrossingsPerSecond));
        out.setProperty("attack_seconds", number(f.attackSeconds));
        out.setProperty("decay_seconds", number(f.decaySeconds));
        out.setProperty("voiced_fraction", number(f.voicedFraction));
        out.setProperty("median_hz", number(f.medianHz));
        out.setProperty("pitch_spread_semitones", number(f.pitchSpreadSemitones));
        StringBuilder text = new StringBuilder();
        for (Chopper.Piece piece : pieces) {
            if (text.length() > 0) text.append(',');
            text.append(piece.start).append(':').append(piece.onset).append(':').append(piece.end);
        }
        out.setProperty("pieces", text.toString());
        out.setProperty("cut_boom", boom.start + ":" + boom.length);
        out.setProperty("cut_snap", snap.start + ":" + snap.length);
        out.setProperty("cut_tick", tick.start + ":" + tick.length);
    }

    /** The stored analysis, or null when it's missing, stale or doesn't fit the sound. */
    public static Analysis read(Properties in, int frames) {
        try {
            if (!Integer.toString(VERSION).equals(in.getProperty("analysis"))) return null;
            Job job = Job.valueOf(in.getProperty("job"));
            Features features = Features.stored(
                    decimal(in, "seconds"), decimal(in, "centroid_hz"), decimal(in, "low_share"),
                    decimal(in, "body_share"), decimal(in, "high_share"), decimal(in, "crossings_per_second"),
                    decimal(in, "attack_seconds"), decimal(in, "decay_seconds"), decimal(in, "voiced_fraction"),
                    decimal(in, "median_hz"), decimal(in, "pitch_spread_semitones"));
            String[] parts = in.getProperty("pieces", "").split(",");
            Chopper.Piece[] pieces = new Chopper.Piece[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String[] bounds = parts[i].split(":");
                pieces[i] = new Chopper.Piece(Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]),
                        Integer.parseInt(bounds[2]));
                if (pieces[i].end > frames) return null;
            }
            Chopper.Cut boom = cut(in, "cut_boom", frames), snap = cut(in, "cut_snap", frames),
                    tick = cut(in, "cut_tick", frames);
            if (boom == null || snap == null || tick == null) return null;
            return new Analysis(job, features, pieces, boom, snap, tick);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static Chopper.Cut cut(Properties in, String key, int frames) {
        String[] bounds = in.getProperty(key, "").split(":");
        Chopper.Cut cut = new Chopper.Cut(Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]));
        return cut.start + cut.length <= frames ? cut : null;
    }

    private static float decimal(Properties in, String key) {
        return Float.parseFloat(in.getProperty(key));
    }

    private static String number(float value) {
        return String.format(Locale.US, "%.4f", value);
    }
}
