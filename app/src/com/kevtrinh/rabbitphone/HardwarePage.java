package com.kevtrinh.rabbitphone;

import android.view.View;

/** A native feature page that participates in the R1's wheel and side-button navigation. */
public interface HardwarePage {
    View getView();
    boolean handleWheel(boolean up);
    boolean handleSingle();
    boolean handleBack();
    default void setHostActive(boolean active) { }
    default void release() { }

    /**
     * Pages that return true receive the side button's raw edges instead of clicks and
     * holds classified by {@link ButtonGestures}, so the global hold-to-record and
     * multi-press actions don't apply while they're open.
     */
    default boolean ownsButton() { return false; }
    default void buttonDown(long time) { }
    default void buttonUp(long time) { }
    default void buttonCancel() { }
}
