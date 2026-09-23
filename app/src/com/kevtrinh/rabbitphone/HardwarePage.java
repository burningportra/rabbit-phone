package com.kevtrinh.rabbitphone;

import android.view.View;

/** A native feature page that participates in the R1's wheel and side-button navigation. */
public interface HardwarePage {
    View getView();
    boolean handleWheel(boolean up);
    boolean handleSingle();
    boolean handleBack();
}
