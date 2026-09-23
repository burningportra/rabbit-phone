package com.kevtrinh.rabbitphone;

import android.app.Activity;
import android.os.Bundle;

/** Compatibility entry point; the camera itself belongs to Home's window. */
public final class CameraActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(NavigationIntents.camera(this));
        finish();
    }
}
