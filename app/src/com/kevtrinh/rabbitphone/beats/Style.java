package com.kevtrinh.rabbitphone.beats;

/** A beat recipe: tempo range, swing, and a bank of 16-step patterns per job. */
public enum Style {
    /** After Timbaland: swung, syncopated kicks, ghost snares and hat rolls. */
    BOUNCE(92, 104, 0.62f,
            new String[] {"x..x..x...x.x...", "x.....x..x..x...", "x..x......x..x..", "x......x..x.....",
                    "x.x...x...x..x..", "x..x...x..x....x"},
            new String[] {"....x.......x...", "....x.......x..x", "....x..x....x...", "....x.......x.x.",
                    "..x.x.......x...", "....x...x...x..."},
            new String[] {"x.x.x.x.x.x.x.x.", "xxx.xxx.xxx.xxx.", "x.xxx.x.x.xxx.x.", "..x...x...x...x.",
                    "x.x.x.xxx.x.x.xx", "xx.xx.x.xx.xx.x."},
            new String[] {"..x.......x.....", "......x.......x.", "..x...x......x..", "x.......x......."});

    public final int minBpm, maxBpm;
    public final float swing;
    private final String[] boom, snap, tick, tune;

    Style(int minBpm, int maxBpm, float swing, String[] boom, String[] snap, String[] tick, String[] tune) {
        this.minBpm = minBpm;
        this.maxBpm = maxBpm;
        this.swing = swing;
        this.boom = boom;
        this.snap = snap;
        this.tick = tick;
        this.tune = tune;
    }

    /** The pattern bank for a job; voices follow their own rhythm and have none. */
    String[] patterns(Job job) {
        switch (job) {
            case BOOM: return boom;
            case SNAP: return snap;
            case TICK: return tick;
            case TUNE: return tune;
            default: throw new IllegalArgumentException("Voices follow their own rhythm");
        }
    }
}
