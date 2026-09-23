package com.kevtrinh.rabbitphone;

import java.util.Locale;

/** Immutable presentation data; navigation and active-task ownership stay with the host. */
public final class NavigationCard {
    public enum Glyph {
        CAMERA, GALLERY, TIMER, TRANSLATE, RECORDER, RCADE, REMINDERS, ALARM,
        INTERN, MUSIC, CREATIONS, SETTINGS, APPS, PHONE, MESSAGES
    }

    public final String id;
    public final String title;
    public final int color;
    public final Glyph glyph;
    public final boolean active;

    public NavigationCard(String id, String title, int color, Glyph glyph, boolean active) {
        if (id == null || id.isEmpty() || title == null || title.trim().isEmpty() || glyph == null)
            throw new IllegalArgumentException("Card identity, title, and glyph are required");
        this.id = id;
        this.title = title.trim().toLowerCase(Locale.ROOT);
        this.color = color;
        this.glyph = glyph;
        this.active = active;
    }
}
