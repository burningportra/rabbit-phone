package com.kevtrinh.rabbitphone;

import java.util.Locale;

/** Immutable presentation data; navigation and active-task ownership stay with the host. */
public final class NavigationCard {
    public static final class Preview {
        public enum Kind { TIMER, TEXT }

        public final Kind kind;
        public final String value;
        public final String detail;
        public final boolean running;
        public final boolean finished;

        public Preview(Kind kind, String value, String detail, boolean running, boolean finished) {
            if (kind == null) throw new IllegalArgumentException("Preview kind is required");
            this.kind = kind;
            this.value = value == null ? "" : value;
            this.detail = detail == null ? "" : detail;
            this.running = running;
            this.finished = finished;
        }
    }
    public enum Glyph {
        CAMERA, GALLERY, TIMER, TRANSLATE, RECORDER, RCADE, REMINDERS, ALARM,
        INTERN, MUSIC, CREATIONS, SETTINGS, APPS, PHONE, MESSAGES
    }

    public final String id;
    public final String title;
    public final int color;
    public final Glyph glyph;
    public final boolean active;
    public final Preview preview;

    public NavigationCard(String id, String title, int color, Glyph glyph, boolean active) {
        this(id, title, color, glyph, active, null);
    }

    public NavigationCard(String id, String title, int color, Glyph glyph, boolean active,
            Preview preview) {
        if (id == null || id.isEmpty() || title == null || title.trim().isEmpty() || glyph == null)
            throw new IllegalArgumentException("Card identity, title, and glyph are required");
        this.id = id;
        this.title = title.trim().toLowerCase(Locale.ROOT);
        this.color = color;
        this.glyph = glyph;
        this.active = active;
        this.preview = preview;
    }
}
