package com.kevtrinh.rabbitphone;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import java.io.File;

/** Loads the separately installed Rabbit typeface without bundling proprietary font data. */
final class RabbitTypography {
    private static final String FONT_PATH = "fonts/PowerGrotesk-Regular.otf";
    private static Typeface cachedTypeface;
    private static String cachedFileState;

    private RabbitTypography() {}

    static synchronized Typeface regular(Context context) {
        File font = new File(context.getApplicationContext().getFilesDir(), FONT_PATH);
        String fileState = font.isFile() && font.canRead() && font.length() > 0
                ? font.getAbsolutePath() + ":" + font.length() + ":" + font.lastModified()
                : "missing";
        if (cachedTypeface != null && fileState.equals(cachedFileState)) {
            return cachedTypeface;
        }

        Typeface resolved = Typeface.create("sans", Typeface.NORMAL);
        if (!"missing".equals(fileState)) {
            try {
                Typeface loaded = Typeface.createFromFile(font);
                if (loaded != null) resolved = Typeface.create(loaded, Typeface.NORMAL);
            } catch (RuntimeException ignored) {
                // Invalid or unreadable private font files fall back without breaking the UI.
            }
        }
        cachedTypeface = resolved;
        cachedFileState = fileState;
        return resolved;
    }

    static void applyToDialog(Context context, AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) applyToTree(window.getDecorView(), regular(context));
    }

    private static void applyToTree(View view, Typeface typeface) {
        if (view instanceof TextView) ((TextView) view).setTypeface(typeface);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            applyToTree(group.getChildAt(index), typeface);
        }
    }
}
