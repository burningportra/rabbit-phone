package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.content.Intent;

/** Stable quick-navigation contract consumed by every in-app navigation surface. */
public final class NavigationIntents {
    public static final String EXTRA_DESTINATION = "com.kevtrinh.rabbitphone.DESTINATION";
    public static final String ACTION_OPEN_CAMERA = "com.kevtrinh.rabbitphone.OPEN_CAMERA";
    public static final String ACTION_OPEN_KEYBOARD = "com.kevtrinh.rabbitphone.OPEN_KEYBOARD";
    public static final String ACTION_OPEN_SETTINGS = "com.kevtrinh.rabbitphone.OPEN_SETTINGS";

    private NavigationIntents() { }

    public static Intent camera(Context context) { return homeAction(context, ACTION_OPEN_CAMERA); }

    public static Intent keyboard(Context context) { return homeAction(context, ACTION_OPEN_KEYBOARD); }

    public static Intent settings(Context context) { return homeAction(context, ACTION_OPEN_SETTINGS); }

    public static Intent home() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    private static Intent homeAction(Context context, String action) {
        return new Intent(context, HomeActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).putExtra(EXTRA_DESTINATION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
