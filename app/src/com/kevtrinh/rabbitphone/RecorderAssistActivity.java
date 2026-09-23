package com.kevtrinh.rabbitphone;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.Toast;

/** Android's permission-protected assistant entry for an original, still-held power press. */
public final class RecorderAssistActivity extends Activity {
    // AOSP AssistUtils: INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS. This is only
    // a routing hint; manifest permission and live physical state are the gates.
    private static final int POWER_LONG_PRESS = 6;
    private final Handler main = new Handler(Looper.getMainLooper());
    private VoiceRecorderOverlay overlay;
    private NavigationSurface navigationSurface;
    private QuickSettingsOverlay quickSettings;
    private HardwareButtonClient hardware;
    private ButtonGestures gestures;
    private boolean resumed;
    private boolean foreground;
    private boolean assistantPending;
    private boolean observing;
    private boolean awaitingUnlock;
    private final Runnable assistantStartupTimeout = new Runnable() {
        @Override public void run() {
            if (!observing || hardware.isReady()) return;
            hardware.stop();
            observing = false;
            if (canUseMicrophone()) {
                overlay.showReady();
                hardware.start();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTurnScreenOn(true);
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Color.rgb(10, 10, 9));
        navigationSurface = new NavigationSurface(this);
        navigationSurface.setHome(false);
        navigationSurface.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(navigationSurface);
        configureWindow();
        overlay = new VoiceRecorderOverlay(this, new VoiceRecorderOverlay.Host() {
            @Override public void onRecorderActiveChanged(boolean active) { }
            @Override public void onRecorderMessage(String message) {
                Toast.makeText(RecorderAssistActivity.this, message, Toast.LENGTH_SHORT).show();
            }
            @Override public void onRecorderClosed() { finish(); }
        });
        quickSettings = new QuickSettingsOverlay(this, new QuickSettingsOverlay.Host() {
            @Override public void onCamera() {
                launchQuickIntent(NavigationIntents.camera(RecorderAssistActivity.this),
                        "Camera isn't available");
            }
            @Override public void onKeyboard() {
                launchQuickIntent(NavigationIntents.keyboard(RecorderAssistActivity.this),
                        "Keyboard settings aren't available");
            }
            @Override public void onLock() {
                overlay.abortAndDismiss();
                if (gestures != null) gestures.cancel();
                if (hardware == null || !hardware.send(HardwareButtonClient.Command.SLEEP)) {
                    Toast.makeText(RecorderAssistActivity.this,
                            "Lock is unavailable; use Android's power button", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onSettings() {
                launchQuickIntent(NavigationIntents.settings(RecorderAssistActivity.this), "Settings isn't available");
            }
            @Override public void onMessage(String message) {
                Toast.makeText(RecorderAssistActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
        navigationSurface.setListener(new NavigationSurface.Listener() {
            @Override public void onQuickSettings() {
                if (quickSettings != null) quickSettings.show(navigationSurface);
            }
            @Override public void onQuickHome() { openHome(); }
            @Override public void onOpenStack() { }
        });
        gestures = new ButtonGestures(new ButtonGestures.Scheduler() {
            @Override public long now() { return SystemClock.uptimeMillis(); }
            @Override public void postDelayed(Runnable action, long delay) {
                main.postDelayed(action, delay);
            }
            @Override public void remove(Runnable action) { main.removeCallbacks(action); }
        }, new ButtonGestures.Actions() {
            @Override public void onSingle() {
                if (quickSettings != null && quickSettings.handleSingle()) return;
                overlay.handleSingle();
            }
            @Override public void onDouble() {
                if (quickSettings != null) quickSettings.dismiss();
                finish();
            }
            @Override public void onHoldStart() {
                if (quickSettings != null) quickSettings.dismiss();
                if (canUseMicrophone()) overlay.beginHold();
            }
            @Override public void onHoldEnd() {
                if (canUseMicrophone()) overlay.finishHold();
                else overlay.abortAndDismiss();
            }
            @Override public void onRefresh() {
                if (quickSettings != null) quickSettings.dismiss();
                overlay.showLibrary();
            }
            @Override public void onShutdown() {
                if (quickSettings != null) quickSettings.dismiss();
                overlay.abortAndDismiss();
                hardware.send(HardwareButtonClient.Command.SHUTDOWN);
            }
        });
        hardware = new HardwareButtonClient(this, new HardwareButtonClient.Listener() {
            @Override public void onReady() {
                main.removeCallbacks(assistantStartupTimeout);
                android.util.Log.i("RabbitPhoneHardware", "Recorder controls ready");
            }
            @Override public void onDown(long time) { if (foreground) gestures.down(time); }
            @Override public void onUp(long time) { if (foreground) gestures.up(time); }
            @Override public void onAssistantHeld(long time) {
                long age = SystemClock.uptimeMillis() - time;
                if (observing && age >= 0 && age <= 1000 && canUseMicrophone()) {
                    android.util.Log.i("RabbitPhoneHardware", "Assistant physical hold adopted");
                    overlay.beginHold();
                }
            }
            @Override public void onAssistantReleased(long time) {
                if (!observing) return;
                observing = false;
                if (canUseMicrophone()) overlay.finishHold();
                else overlay.abortAndDismiss();
                hardware.stop();
                // The observed release has also reached Android. A fresh normal
                // session may now grab only after both physical keys are up.
                if (canUseMicrophone()) hardware.start();
            }
            @Override public void onDisconnected() {
                observing = false;
                assistantPending = false;
                gestures.cancel();
                overlay.abortAndDismiss();
                if (canUseMicrophone()) {
                    overlay.showReady();
                    main.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (canUseMicrophone()) hardware.start();
                        }
                    }, 750);
                }
            }
        });
        // Recreating an Activity is never fresh authority to record a hold.
        assistantPending = state == null && isPowerAssist(getIntent());
        overlay.showReady();
        requestUnlock();
    }

    private boolean isPowerAssist(Intent intent) {
        return intent != null && Intent.ACTION_ASSIST.equals(intent.getAction())
                && intent.getIntExtra("invocation_type", 0) == POWER_LONG_PRESS;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        stopControls();
        assistantPending = isPowerAssist(intent);
        overlay.showReady();
        requestUnlock();
        syncForeground();
    }

    private void requestUnlock() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isKeyguardLocked() || awaitingUnlock) return;
        awaitingUnlock = true;
        // Standard Android dismissal: a configured credential must still be
        // entered. Nothing captures audio or observes input while keyguard holds.
        keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() {
                awaitingUnlock = false;
                syncForeground();
            }
            @Override public void onDismissCancelled() {
                awaitingUnlock = false;
                assistantPending = false;
                finish();
            }
            @Override public void onDismissError() {
                awaitingUnlock = false;
                assistantPending = false;
            }
        });
    }

    private void configureWindow() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private boolean canUseMicrophone() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        PowerManager power = getSystemService(PowerManager.class);
        return resumed && hasWindowFocus() && !isFinishing()
                && (keyguard == null || !keyguard.isKeyguardLocked())
                && power != null && power.isInteractive();
    }

    private void syncForeground() {
        boolean active = canUseMicrophone();
        if (!active) {
            if (foreground) stopControls();
            foreground = false;
            return;
        }
        if (foreground) return;
        foreground = true;
        if (!overlay.isVisible()) overlay.showReady();
        if (assistantPending) {
            assistantPending = false;
            observing = true;
            hardware.startForAssistantHold();
            main.postDelayed(assistantStartupTimeout, 3000);
        } else {
            hardware.start();
        }
    }

    private void stopControls() {
        main.removeCallbacks(assistantStartupTimeout);
        if (quickSettings != null) quickSettings.dismiss();
        if (hardware != null) hardware.stop();
        if (gestures != null) gestures.cancel();
        if (overlay != null) overlay.abortAndDismiss();
        observing = false;
        foreground = false;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        configureWindow();
        requestUnlock();
        syncForeground();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (hardware != null) syncForeground();
    }

    @Override protected void onPause() {
        resumed = false;
        if (foreground || observing) assistantPending = false;
        stopControls();
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        stopControls();
        if (quickSettings != null) quickSettings.release();
        if (overlay != null) overlay.release();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (quickSettings != null && quickSettings.isVisible()) {
            quickSettings.dismiss();
            return;
        }
        finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        InputDevice device = event.getDevice();
        boolean wheel = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || ((key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN)
                    && device != null && "och1970_holl_key".equals(device.getName()));
        if (quickSettings != null && quickSettings.isVisible()) {
            if (foreground && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                if (wheel) quickSettings.handleWheel(key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_VOLUME_UP);
                else if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                    quickSettings.handleSingle();
                }
            }
            if (wheel || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                return true;
            }
        }
        if (wheel) {
            if (foreground && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                overlay.handleWheel(key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_VOLUME_UP);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (foreground && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) overlay.handleSingle();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void launchQuickIntent(Intent intent, String failureMessage) {
        try {
            startActivity(intent);
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show();
        }
    }

    private void openHome() {
        launchQuickIntent(NavigationIntents.home(), "Home isn't available");
    }
}
