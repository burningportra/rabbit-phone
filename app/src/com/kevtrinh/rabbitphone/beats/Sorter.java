package com.kevtrinh.rabbitphone.beats;

/**
 * Guesses a sound's job from its features. The thresholds are starting points to tune
 * against real catches, and a person can always change the job afterwards.
 */
public final class Sorter {
    static final float DRUM_MAX_SECONDS = 0.40f;
    static final float BOOM_MAX_CENTROID = 400f, BOOM_MIN_LOW_SHARE = 0.25f;
    /** Zero crossings aren't used: a quiet microphone's hiss crosses zero constantly. */
    static final float TICK_MIN_CENTROID = 3500f;
    static final float TUNE_MIN_VOICED = 0.7f, TUNE_MAX_SPREAD = 0.5f;

    private Sorter() { }

    public static Job sort(Features f) {
        if (f.seconds <= DRUM_MAX_SECONDS) {
            if (f.centroidHz < BOOM_MAX_CENTROID && f.lowShare > BOOM_MIN_LOW_SHARE) return Job.BOOM;
            if (f.centroidHz > TICK_MIN_CENTROID) return Job.TICK;
            return Job.SNAP;
        }
        // Long and unclear sounds are voices: a voice row always sounds fine.
        if (f.voicedFraction >= TUNE_MIN_VOICED && f.pitchSpreadSemitones <= TUNE_MAX_SPREAD) return Job.TUNE;
        return Job.VOICE;
    }
}
