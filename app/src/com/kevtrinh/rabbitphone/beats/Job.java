package com.kevtrinh.rabbitphone.beats;

/** What a sound does in a beat. */
public enum Job {
    BOOM, SNAP, TICK, TUNE, VOICE;

    public boolean isDrum() { return this == BOOM || this == SNAP || this == TICK; }

    public String label() { return name().toLowerCase(java.util.Locale.ROOT); }

    public static Job parse(String text, Job fallback) {
        if (text == null) return fallback;
        try {
            return valueOf(text.trim());
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
