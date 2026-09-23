package com.kevtrinh.rabbitphone;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** An explicit-capture camera controller hosted in Home's existing window.
 * The host must stop its own hardware client before resuming this screen. */
@SuppressWarnings("deprecation")
public final class CameraScreen implements SurfaceHolder.Callback {
    public interface Host {
        void onBack();
        void onDoubleBack();
        void onHome();
        void onKeyboard();
        void onSettings();
        default void onInteraction() { }
    }
    private final Activity owner;
    private final Host host;
    private boolean released;
    private static final int BG = Color.rgb(10, 10, 9);
    private static final int WHITE = Color.rgb(245, 239, 225);
    private static final int ORANGE = Color.rgb(255, 90, 31);
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout previewFrame;
    private SurfaceView preview;
    private TextView status;
    private Button shutter;
    private Button flip;
    private HardwareButtonClient hardware;
    private ButtonGestures gestures;
    private VoiceRecorderOverlay recorderOverlay;
    private NavigationSurface navigationSurface;
    private QuickSettingsOverlay quickSettings;
    private Camera camera;
    private int rearId = -1;
    private int frontId = -1;
    private int selectedId = -1;
    private int previewWidth;
    private int previewHeight;
    private int previewRotation;
    private int cameraGeneration;
    private boolean resumed;
    private boolean foreground;
    private boolean surfaceReady;
    private boolean capturing;
    private boolean recording;

    public CameraScreen(Activity owner, Host host) {
        if (owner == null || host == null) throw new IllegalArgumentException("Camera owner and host are required");
        this.owner = owner;
        this.host = host;
        buildUi();
        discoverCameras();
        selectedId = rearId >= 0 ? rearId : frontId;
        recorderOverlay = new VoiceRecorderOverlay(owner, new VoiceRecorderOverlay.Host() {
            @Override public void onRecorderActiveChanged(boolean active) {
                recording = active;
                updateButtons();
                if (!active && foreground) status.setText(facingLabel());
            }
            @Override public void onRecorderMessage(String message) { status.setText(message); }
        });
        quickSettings = new QuickSettingsOverlay(owner, new QuickSettingsOverlay.Host() {
            @Override public void onCamera() {
                // Already in Camera. This must never be treated as a capture action.
            }
            @Override public void onKeyboard() {
                host.onKeyboard();
            }
            @Override public void onLock() {
                recorderOverlay.abortAndDismiss();
                if (gestures != null) gestures.cancel();
                if (hardware == null || !hardware.send(HardwareButtonClient.Command.SLEEP)) {
                    status.setText("Lock is unavailable; use Android's power button");
                }
            }
            @Override public void onSettings() {
                host.onSettings();
            }
            @Override public void onMessage(String message) { status.setText(message); }
        });
        navigationSurface.setListener(new NavigationSurface.Listener() {
            @Override public void onQuickSettings() {
                if (quickSettings != null) quickSettings.show(navigationSurface);
            }
            @Override public void onQuickHome() { host.onHome(); }
            @Override public void onOpenStack() { }
        });
        gestures = new ButtonGestures(new ButtonGestures.Scheduler() {
            @Override public long now() { return android.os.SystemClock.uptimeMillis(); }
            @Override public void postDelayed(Runnable action, long delay) { main.postDelayed(action, delay); }
            @Override public void remove(Runnable action) { main.removeCallbacks(action); }
        }, new ButtonGestures.Actions() {
            @Override public void onSingle() {
                if (quickSettings != null && quickSettings.handleSingle()) return;
                if (!recorderOverlay.handleSingle()) takePhoto();
            }
            @Override public void onDouble() {
                if (quickSettings != null) quickSettings.dismiss();
                recorderOverlay.dismissForDouble();
                host.onDoubleBack();
            }
            @Override public void onHoldStart() {
                if (quickSettings != null) quickSettings.dismiss();
                if (foreground) recorderOverlay.beginHold();
            }
            @Override public void onHoldEnd() { recorderOverlay.finishHold(); }
            @Override public void onRefresh() {
                if (quickSettings != null) quickSettings.dismiss();
                recorderOverlay.abortAndDismiss();
                refreshPreview();
            }
            @Override public void onShutdown() {
                if (quickSettings != null) quickSettings.dismiss();
                recorderOverlay.abortAndDismiss();
                if (!hardware.send(HardwareButtonClient.Command.SHUTDOWN))
                    status.setText("Shutdown is unavailable; use Android's power menu");
            }
        });
        hardware = new HardwareButtonClient(owner, new HardwareButtonClient.Listener() {
            @Override public void onReady() {
                if (foreground && camera != null) {
                    moveMotor();
                    if (!recording) status.setText(facingLabel());
                }
            }
            @Override public void onDown(long time) {
                if (!foreground) return;
                host.onInteraction();
                if (foreground && !released) gestures.down(time);
            }
            @Override public void onUp(long time) { if (foreground) gestures.up(time); }
            @Override public void onDisconnected() {
                gestures.cancel();
                recorderOverlay.abortAndDismiss();
                if (foreground) status.setText("Use the on-screen camera controls");
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        KeyguardManager keyguard = (KeyguardManager)owner.getSystemService(Context.KEYGUARD_SERVICE);
                        PowerManager power = (PowerManager)owner.getSystemService(Context.POWER_SERVICE);
                        if (!released && foreground && resumed && owner.hasWindowFocus()
                                && (keyguard == null || !keyguard.isKeyguardLocked())
                                && (power == null || power.isInteractive())) hardware.start();
                    }
                }, 750);
            }
        });
        updateButtons();
        configureWindow();
    }

    private void configureWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            owner.getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = owner.getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(owner);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout header = new LinearLayout(owner);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("Back", false);
        back.setContentDescription("Back to home");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { if (!released) host.onBack(); }
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(80), dp(48)));
        TextView title = new TextView(owner);
        title.setText("camera ⌄"); title.setTextSize(25); title.setTextColor(WHITE);
        title.setContentDescription("Open quick settings");
        title.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (!released && foreground && quickSettings != null) quickSettings.show(navigationSurface);
            }
        });
        title.setTypeface(RabbitTypography.regular(owner));
        title.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(header);
        status = new TextView(owner);
        status.setTextColor(WHITE); status.setTextSize(13);
        status.setTypeface(RabbitTypography.regular(owner));
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setMaxLines(2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status, new LinearLayout.LayoutParams(-1, dp(44)));
        previewFrame = new FrameLayout(owner) {
            @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
                super.onSizeChanged(w, h, oldW, oldH);
                fitPreview();
            }
        };
        previewFrame.setBackgroundColor(Color.BLACK);
        preview = new SurfaceView(owner);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        preview.getHolder().addCallback(this);
        previewFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        page.addView(previewFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(owner);
        controls.setPadding(0, dp(10), 0, 0);
        flip = button("Flip", false);
        flip.setContentDescription("Switch front and rear camera");
        flip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { selectCamera(selectedId == frontId ? rearId : frontId); }
        });
        LinearLayout.LayoutParams flipLayout = new LinearLayout.LayoutParams(0, dp(56), 1);
        flipLayout.setMargins(0, 0, dp(10), 0);
        controls.addView(flip, flipLayout);
        shutter = button("Take photo", true);
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { takePhoto(); }
        });
        controls.addView(shutter, new LinearLayout.LayoutParams(0, dp(56), 2));
        page.addView(controls);
        navigationSurface = new NavigationSurface(owner);
        navigationSurface.setHome(false);
        navigationSurface.addView(page, new FrameLayout.LayoutParams(-1, -1));

    }

    private Button button(String label, boolean primary) {
        Button view = new Button(owner);
        view.setText(label); view.setAllCaps(false); view.setTextSize(17);
        view.setTypeface(RabbitTypography.regular(owner));
        view.setTextColor(primary ? BG : WHITE);
        view.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? ORANGE : Color.rgb(27, 27, 24));
        shape.setCornerRadius(dp(14)); view.setBackground(shape);
        return view;
    }

    private void discoverCameras() {
        try {
            for (int id = 0; id < Camera.getNumberOfCameras(); ++id) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(id, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && frontId < 0) frontId = id;
                else if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK && rearId < 0) rearId = id;
            }
        } catch (RuntimeException exception) {
            status.setText("Camera hardware isn't available");
        }
    }

    public View getView() { return navigationSurface; }

    public void onResume() {
        if (released) return;
        resumed = true; configureWindow(); syncForeground();
    }

    public void onWindowFocusChanged(boolean focused) {
        if (released) return;
        if (!focused) {
            foreground = false;
            stopForeground();
            return;
        }
        configureWindow();
        syncForeground();
    }

    private void syncForeground() {
        if (hardware == null) return;
        KeyguardManager keyguard = (KeyguardManager)owner.getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager power = (PowerManager)owner.getSystemService(Context.POWER_SERVICE);
        boolean active = !released && resumed && owner.hasWindowFocus()
                && (keyguard == null || !keyguard.isKeyguardLocked())
                && (power == null || power.isInteractive());
        if (foreground == active) return;
        foreground = active;
        if (active) { hardware.start(); openCamera(); }
        else stopForeground();
    }

    private void stopForeground() {
        if (quickSettings != null) quickSettings.dismiss();
        if (gestures != null) gestures.cancel();
        if (recorderOverlay != null) recorderOverlay.abortAndDismiss();
        if (hardware != null) {
            hardware.send(HardwareButtonClient.Command.MOTOR_PRIVACY);
            hardware.stop();
        }
        releaseCamera();
    }

    public void onPause() {
        resumed = false; foreground = false; stopForeground();
    }

    public void release() {
        if (released) return;
        released = true;
        onPause();
        main.removeCallbacksAndMessages(null);
        if (quickSettings != null) quickSettings.release();
        if (recorderOverlay != null) recorderOverlay.release();
        preview.getHolder().removeCallback(this);
        navigationSurface.setListener(null);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true; openCamera();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null && foreground && !capturing) startPreview();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false; releaseCamera();
    }

    private void openCamera() {
        if (!foreground || !surfaceReady || camera != null) return;
        if (owner.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Allow Camera permission in Android Settings to use the camera");
            updateButtons(); return;
        }
        if (selectedId < 0) { status.setText("No camera is available"); return; }
        try {
            camera = Camera.open(selectedId);
            final Camera opened = camera;
            camera.setErrorCallback(new Camera.ErrorCallback() {
                @Override public void onError(int error, Camera source) {
                    if (camera == opened) {
                        releaseCamera(); status.setText("Camera stopped · reopen Camera to try again");
                    }
                }
            });
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(selectedId, info);
            int rotation = owner.getWindowManager().getDefaultDisplay().getRotation();
            int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180
                    : rotation == Surface.ROTATION_270 ? 270 : 0;
            int photoRotation;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                previewRotation = (360 - (info.orientation + degrees) % 360) % 360;
                photoRotation = (info.orientation - degrees + 360) % 360;
            } else {
                previewRotation = (info.orientation - degrees + 360) % 360;
                photoRotation = (info.orientation + degrees) % 360;
            }
            camera.setDisplayOrientation(previewRotation);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = choosePreview(parameters.getSupportedPreviewSizes());
            if (size != null) parameters.setPreviewSize(size.width, size.height);
            parameters.setRotation(photoRotation);
            List<String> focus = parameters.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(parameters);
            Camera.Size actual = camera.getParameters().getPreviewSize();
            previewWidth = actual.width; previewHeight = actual.height;
            fitPreview();
            if (startPreview()) { moveMotor(); status.setText(facingLabel()); }
        } catch (RuntimeException exception) {
            releaseCamera(); status.setText("Couldn't open the camera · close other camera apps and try again");
        }
        updateButtons();
    }

    private Camera.Size choosePreview(List<Camera.Size> choices) {
        Camera.Size best = null;
        double bestScore = Double.MAX_VALUE;
        if (choices != null) for (Camera.Size size : choices) {
            double score = Math.abs((double)size.width / size.height - 4.0 / 3.0) * 10000
                    + Math.abs((long)size.width * size.height - 640L * 480) / 1000.0;
            if (score < bestScore) { best = size; bestScore = score; }
        }
        return best;
    }

    private void fitPreview() {
        if (preview == null || previewWidth <= 0 || previewHeight <= 0) return;
        int availableWidth = previewFrame.getWidth(), availableHeight = previewFrame.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) return;
        double aspect = (double)previewWidth / previewHeight;
        if (previewRotation == 90 || previewRotation == 270) aspect = 1 / aspect;
        int width = availableWidth;
        int height = (int)Math.round(width / aspect);
        if (height > availableHeight) { height = availableHeight; width = (int)Math.round(height * aspect); }
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams)preview.getLayoutParams();
        if (layout.width != width || layout.height != height) {
            layout.width = width; layout.height = height; layout.gravity = Gravity.CENTER;
            preview.setLayoutParams(layout);
        }
    }

    private boolean startPreview() {
        if (camera == null || !foreground || !surfaceReady) return false;
        try {
            camera.stopPreview(); camera.setPreviewDisplay(preview.getHolder()); camera.startPreview();
            return true;
        } catch (IOException exception) {
            releaseCamera(); status.setText("Camera preview isn't available");
        } catch (RuntimeException exception) {
            releaseCamera(); status.setText("Camera preview stopped · reopen Camera to try again");
        }
        return false;
    }

    private void releaseCamera() {
        Camera old = camera; camera = null; capturing = false; cameraGeneration++;
        if (old != null) {
            try { old.setErrorCallback(null); old.stopPreview(); } catch (RuntimeException ignored) { }
            try { old.release(); } catch (RuntimeException ignored) { }
        }
        updateButtons();
    }

    private void selectCamera(int id) {
        if (!foreground || capturing || recording || id < 0 || id == selectedId) return;
        selectedId = id; releaseCamera(); openCamera();
    }

    private void refreshPreview() {
        if (!foreground || recording) return;
        releaseCamera(); openCamera();
    }

    private void moveMotor() {
        hardware.send(selectedId == frontId ? HardwareButtonClient.Command.MOTOR_FRONT
                : HardwareButtonClient.Command.MOTOR_REAR);
    }

    private String facingLabel() { return (selectedId == frontId ? "Front" : "Rear") + " · wheel to flip"; }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (released || !resumed) return false;
        InputDevice device = event.getDevice();
        int key = event.getKeyCode();
        boolean wheel = device != null && "och1970_holl_key".equals(device.getName());
        boolean up = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_VOLUME_UP;
        boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_VOLUME_DOWN;
        boolean overlayDirection = key == KeyEvent.KEYCODE_DPAD_UP
                || key == KeyEvent.KEYCODE_DPAD_DOWN
                || (wheel && (key == KeyEvent.KEYCODE_VOLUME_UP
                        || key == KeyEvent.KEYCODE_VOLUME_DOWN));
        if (quickSettings != null && quickSettings.isVisible()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (overlayDirection) quickSettings.handleWheel(up);
                else if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                        || key == KeyEvent.KEYCODE_NUMPAD_ENTER) quickSettings.handleSingle();
            }
            if (overlayDirection || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_NUMPAD_ENTER) return true;
        }
        if (recorderOverlay != null && recorderOverlay.isVisible() && overlayDirection) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                recorderOverlay.handleWheel(up);
            }
            return true;
        }
        if (recorderOverlay != null && recorderOverlay.isVisible()
                && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                        || key == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                recorderOverlay.handleSingle();
            }
            return true;
        }
        if (wheel && (up || down)) {
            if (foreground && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (recorderOverlay != null && recorderOverlay.handleWheel(up)) return true;
                preview.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                selectCamera(up ? frontId : rearId);
            }
            return true;
        }
        return false;
    }

    public void onBackPressed() {
        if (released) return;
        if (quickSettings != null && quickSettings.isVisible()) {
            quickSettings.dismiss();
            return;
        }
        if (recorderOverlay != null && recorderOverlay.isVisible()) {
            recorderOverlay.abortAndDismiss();
            return;
        }
        host.onBack();
    }

    private void takePhoto() {
        if (!foreground || camera == null || capturing || recording) return;
        final Camera source = camera;
        final int generation = cameraGeneration;
        capturing = true; updateButtons();
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        status.setText("Taking photo…");
        try {
            source.takePicture(null, null, new Camera.PictureCallback() {
                @Override public void onPictureTaken(byte[] jpeg, Camera completed) {
                    if (jpeg != null && jpeg.length > 0) savePhoto(jpeg, generation);
                    else if (camera == source) status.setText("Photo wasn't captured");
                    if (camera == source) {
                        capturing = false;
                        if (foreground) startPreview();
                        updateButtons();
                    }
                }
            });
        } catch (RuntimeException exception) {
            capturing = false; updateButtons(); status.setText("Couldn't take that photo");
            if (camera == source) startPreview();
        }
    }

    private void savePhoto(final byte[] jpeg, final int generation) {
        final ContentResolver resolver = owner.getApplicationContext().getContentResolver();
        final String name = "Rabbit-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(new Date()) + ".jpg";
        if (foreground && generation == cameraGeneration) status.setText("Saving photo…");
        new Thread(new Runnable() {
            @Override public void run() {
                Uri entry = null;
                boolean saved = false;
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Rabbit Phone");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    entry = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (entry == null) throw new IOException("No photo destination");
                    try (OutputStream output = resolver.openOutputStream(entry, "w")) {
                        if (output == null) throw new IOException("Photo destination unavailable");
                        output.write(jpeg);
                    }
                    ContentValues complete = new ContentValues();
                    complete.put(MediaStore.Images.Media.IS_PENDING, 0);
                    if (resolver.update(entry, complete, null, null) != 1) throw new IOException("Photo not published");
                    saved = true;
                } catch (IOException exception) {
                    saved = false;
                } catch (RuntimeException exception) {
                    saved = false;
                } finally {
                    if (!saved && entry != null) {
                        try { resolver.delete(entry, null, null); } catch (RuntimeException ignored) { }
                    }
                }
                final boolean completed = saved;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!released && foreground && generation == cameraGeneration
                                && !owner.isFinishing() && !owner.isDestroyed() && !recording)
                            status.setText(completed ? "Saved to Pictures / Rabbit Phone" : "Couldn't save the photo");
                    }
                });
            }
        }, "Rabbit photo save").start();
    }

    private void updateButtons() {
        if (shutter != null) shutter.setEnabled(foreground && camera != null && !capturing && !recording);
        if (flip != null) flip.setEnabled(foreground && frontId >= 0 && rearId >= 0 && !capturing && !recording);
    }

    private int dp(int value) { return Math.round(value * owner.getResources().getDisplayMetrics().density); }
}
