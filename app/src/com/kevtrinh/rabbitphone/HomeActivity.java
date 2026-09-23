package com.kevtrinh.rabbitphone;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class HomeActivity extends Activity {
    private static final int BG = Color.rgb(10, 10, 9);
    private static final int WARM_WHITE = Color.rgb(245, 239, 225);
    private static final int MUTED = Color.rgb(161, 154, 140);
    private static final int ORANGE = Color.rgb(255, 90, 31);
    private static final int DARK_INK = Color.rgb(22, 18, 14);
    private static final int CARD = Color.rgb(27, 27, 24);

    private enum Page { HOME, APPS, UTILITIES, IDLE }

    private static final class Entry {
        final String label;
        final String detail;
        final Runnable action;

        Entry(String label, String detail, Runnable action) {
            this.label = label;
            this.detail = detail;
            this.action = action;
        }
    }

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<View> selectableViews = new ArrayList<>();
    private final ArrayList<Entry> visibleEntries = new ArrayList<>();
    private final int[] savedSelection = {0, 0, 0, 0};
    private final Handler controlsHandler = new Handler(Looper.getMainLooper());
    private HardwareButtonClient hardware;
    private ButtonGestures gestures;
    private VoiceNotes voiceNotes;
    private boolean resumed;
    private boolean recording;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            long delay = 60_000L - (System.currentTimeMillis() % 60_000L);
            clockHandler.postDelayed(this, delay);
        }
    };

    private Page page = Page.HOME;
    private int selection;
    private TextView clockView;
    private TextView dateView;
    private TextView statusView;
    private ScrollView scrollView;
    private boolean batteryReceiverRegistered;
    private boolean networkCallbackRegistered;
    private int batteryPercent = -1;
    private String networkLabel = "Offline";

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            batteryPercent = level < 0 ? -1 : Math.round(level * 100f / Math.max(1, scale));
            updateStatus();
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { refreshNetwork(); }
                @Override public void onLost(Network network) { refreshNetwork(); }
                @Override public void onCapabilitiesChanged(Network network,
                        NetworkCapabilities capabilities) { refreshNetwork(); }
            };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prepareControls();
        showHome();
        configureWindow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME)) {
            showHome();
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        configureWindow();
        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
        registerBatteryStatus();
        registerNetworkStatus();
        updateControls();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (hardware != null) hardware.stop();
        if (gestures != null) gestures.cancel();
        if (voiceNotes != null) voiceNotes.release();
        super.onPause();
        clockHandler.removeCallbacks(clockTick);
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver);
            batteryReceiverRegistered = false;
        }
        if (networkCallbackRegistered) {
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            if (manager != null) manager.unregisterNetworkCallback(networkCallback);
            networkCallbackRegistered = false;
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) updateControls();
        else {
            if (hardware != null) hardware.stop();
            if (gestures != null) gestures.cancel();
            if (recording && voiceNotes != null) voiceNotes.cancel();
        }
    }

    private void updateControls() {
        if (hardware == null || !resumed || !hasWindowFocus()) return;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        PowerManager power = getSystemService(PowerManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return;
        if (power != null && !power.isInteractive()) return;
        hardware.start();
    }

    private void prepareControls() {
        voiceNotes = new VoiceNotes(this, new VoiceNotes.Listener() {
            @Override public void onRecordingChanged(boolean active) {
                recording = active;
                if (active) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                updateStatus();
            }
            @Override public void onMessage(String message) { showError(message); }
        });
        gestures = new ButtonGestures(new ButtonGestures.Scheduler() {
            @Override public long now() { return android.os.SystemClock.uptimeMillis(); }
            @Override public void postDelayed(Runnable action, long delay) { controlsHandler.postDelayed(action, delay); }
            @Override public void remove(Runnable action) { controlsHandler.removeCallbacks(action); }
        }, new ButtonGestures.Actions() {
            @Override public void onSingle() {
                if (page == Page.IDLE) hardware.send(HardwareButtonClient.Command.SLEEP);
                else {
                    getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    activateSelection();
                }
            }
            @Override public void onDouble() { openCamera(); }
            @Override public void onHoldStart() {
                if (voiceNotes.start()) getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            @Override public void onHoldEnd() { voiceNotes.stop(); }
            @Override public void onRefresh() {
                showHome(); refreshNetwork(); updateClock();
                showError("Phone interface refreshed");
            }
            @Override public void onShutdown() {
                if (hardware.send(HardwareButtonClient.Command.SHUTDOWN)) showError("Powering off");
            }
        });
        hardware = new HardwareButtonClient(this, new HardwareButtonClient.Listener() {
            @Override public void onReady() {
                hardware.send(HardwareButtonClient.Command.MOTOR_PRIVACY);
                android.util.Log.i("RabbitPhoneHardware", "Home controls ready");
            }
            @Override public void onDown(long time) { gestures.down(time); }
            @Override public void onUp(long time) { gestures.up(time); }
            @Override public void onDisconnected() {
                gestures.cancel(); voiceNotes.cancel();
                android.util.Log.i("RabbitPhoneHardware", "Home controls released");
                controlsHandler.postDelayed(new Runnable() {
                    @Override public void run() { updateControls(); }
                }, 750);
            }
        });
    }

    private void showIdle() {
        page = Page.IDLE;
        visibleEntries.clear(); selectableViews.clear(); scrollView = null;
        LinearLayout root = rootColumn(); root.setGravity(Gravity.CENTER);
        root.addView(new RabbitMarkView(this), new LinearLayout.LayoutParams(dp(96), dp(96)));
        clockView = text("", 64, WARM_WHITE, Typeface.BOLD);
        clockView.setFontFeatureSettings("tnum");
        dateView = text("", 18, MUTED, Typeface.NORMAL);
        statusView = text("", 14, MUTED, Typeface.NORMAL);
        root.addView(clockView); root.addView(dateView); root.addView(statusView);
        TextView hint = text("Wheel or tap to browse", 14, MUTED, Typeface.NORMAL);
        hint.setPadding(0, dp(36), 0, 0); root.addView(hint);
        root.setContentDescription("Standby clock. Tap to browse. Side button sleeps.");
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHome(); }
        });
        setContentView(root); applyInsets(root); updateClock(); updateStatus();
    }

    private void registerBatteryStatus() {
        if (batteryReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky;
        if (Build.VERSION.SDK_INT >= 33) {
            sticky = registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            sticky = registerReceiver(batteryReceiver, filter);
        }
        batteryReceiverRegistered = true;
        if (sticky != null) batteryReceiver.onReceive(this, sticky);
    }

    private void registerNetworkStatus() {
        refreshNetwork();
        if (networkCallbackRegistered) return;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return;
        try {
            manager.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        } catch (RuntimeException ignored) {
            networkLabel = "Offline";
            updateStatus();
        }
    }

    private void refreshNetwork() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                ConnectivityManager manager = getSystemService(ConnectivityManager.class);
                Network network = manager == null ? null : manager.getActiveNetwork();
                NetworkCapabilities caps = network == null ? null
                        : manager.getNetworkCapabilities(network);
                if (caps == null) networkLabel = "Offline";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) networkLabel = "Wi-Fi";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) networkLabel = "Mobile";
                else if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) networkLabel = "Online";
                else networkLabel = "Offline";
                updateStatus();
            }
        });
    }

    private void showHome() {
        page = Page.HOME;
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(new Entry("Phone", "Calls and favorites", new Runnable() {
            @Override public void run() { openDialer(); }
        }));
        entries.add(new Entry("Messages", "Texts and conversations", new Runnable() {
            @Override public void run() { openMessages(); }
        }));
        entries.add(new Entry("Camera", "Take a photo", new Runnable() {
            @Override public void run() { openCamera(); }
        }));
        entries.add(new Entry("Music", "Listen offline", new Runnable() {
            @Override public void run() { openMusic(); }
        }));
        entries.add(new Entry("All apps", "Everything installed", new Runnable() {
            @Override public void run() { showApps(); }
        }));
        entries.add(new Entry("Settings", "Device controls", new Runnable() {
            @Override public void run() {
                launchIntent(new Intent(Settings.ACTION_SETTINGS), "Settings isn't available");
            }
        }));
        renderHome(entries);
    }

    private void renderHome(List<Entry> entries) {
        selectableViews.clear();
        visibleEntries.clear();
        visibleEntries.addAll(entries);
        LinearLayout root = rootColumn();

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

        LinearLayout timeColumn = new LinearLayout(this);
        timeColumn.setOrientation(LinearLayout.VERTICAL);
        clockView = text("--:--", 46, WARM_WHITE, Typeface.BOLD);
        clockView.setFontFeatureSettings("tnum");
        dateView = text("", 14, MUTED, Typeface.NORMAL);
        timeColumn.addView(clockView);
        timeColumn.addView(dateView);
        hero.addView(timeColumn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        RabbitMarkView mark = new RabbitMarkView(this);
        mark.setContentDescription("Standby clock");
        mark.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showIdle(); }
        });
        hero.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));

        statusView = text("", 13, MUTED, Typeface.NORMAL);
        statusView.setGravity(Gravity.START);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
        statusParams.bottomMargin = dp(5);
        root.addView(statusView, statusParams);

        for (int i = 0; i < entries.size(); i++) root.addView(createRow(entries.get(i), i));

        setContentView(root);
        applyInsets(root);
        selection = clamp(savedSelection[Page.HOME.ordinal()], 0, entries.size() - 1);
        applySelection(false);
        updateClock();
        updateStatus();
    }

    private void showApps() {
        savedSelection[page.ordinal()] = selection;
        page = Page.APPS;
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(new Entry("Utilities", "Contacts, clock, recorder, compass", new Runnable() {
            @Override public void run() { showUtilities(); }
        }));

        Intent launcherQuery = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcherQuery, 0);
        Collator collator = Collator.getInstance();
        Collections.sort(resolved, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo left, ResolveInfo right) {
                return collator.compare(
                        left.loadLabel(getPackageManager()).toString(),
                        right.loadLabel(getPackageManager()).toString());
            }
        });
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) continue;
            String label = info.loadLabel(getPackageManager()).toString();
            ComponentName component = new ComponentName(
                    info.activityInfo.packageName, info.activityInfo.name);
            entries.add(new Entry(label, "", new Runnable() {
                @Override public void run() {
                    launchIntent(new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(component), label + " couldn't open");
                }
            }));
        }
        renderListPage("All apps", "Wheel to browse", entries);
    }

    private void showUtilities() {
        savedSelection[page.ordinal()] = selection;
        page = Page.UTILITIES;
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(new Entry("Voice notes", "Your local recordings", new Runnable() {
            @Override public void run() { voiceNotes.showLibrary(); }
        }));
        entries.add(new Entry("Rabbit theme", "Wallpaper and lock screen", new Runnable() {
            @Override public void run() {
                launchIntent(new Intent(HomeActivity.this, ThemeActivity.class),
                        "Rabbit theme isn't available");
            }
        }));
        entries.add(packageEntry("Contacts", "People and numbers", "com.android.contacts",
                new Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people"))));
        entries.add(packageEntry("Clock", "Alarms and timers", "com.bnyro.clock",
                new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        entries.add(packageEntry("Recorder", "Voice notes", "com.bnyro.recorder", null));
        entries.add(packageEntry("Compass", "Direction and heading", "com.techyminati.compass", null));
        renderListPage("Utilities", "Simple tools", entries);
    }

    private Entry packageEntry(String label, String detail, String packageName, Intent fallback) {
        return new Entry(label, detail, new Runnable() {
            @Override public void run() {
                launchPackage(packageName, fallback, label + " isn't installed");
            }
        });
    }

    private void renderListPage(String title, String subtitle, List<Entry> entries) {
        selectableViews.clear();
        visibleEntries.clear();
        visibleEntries.addAll(entries);

        LinearLayout root = rootColumn();
        TextView heading = text(title, 34, WARM_WHITE, Typeface.BOLD);
        root.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView subheading = text(subtitle, 14, MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        subtitleParams.bottomMargin = dp(8);
        root.addView(subheading, subtitleParams);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < entries.size(); i++) rows.addView(createRow(entries.get(i), i));
        scrollView.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        applyInsets(root);
        selection = clamp(savedSelection[page.ordinal()], 0, Math.max(0, entries.size() - 1));
        applySelection(false);
    }

    private LinearLayout rootColumn() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(12), dp(18), dp(14));
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        return root;
    }

    private View createRow(Entry entry, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(7), dp(14), dp(7));
        row.setFocusable(true);
        row.setClickable(true);
        row.setContentDescription(entry.detail.isEmpty() ? entry.label
                : entry.label + ", " + entry.detail);

        TextView label = text(entry.label, 20, WARM_WHITE, Typeface.BOLD);
        TextView detail = text(entry.detail, 12, MUTED, Typeface.NORMAL);
        if (entry.detail.isEmpty()) detail.setVisibility(View.GONE);
        row.addView(label);
        row.addView(detail);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        params.bottomMargin = dp(5);
        row.setLayoutParams(params);
        row.setTag(index);
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                if (focused) {
                    selection = (Integer) view.getTag();
                    applySelection(false);
                }
            }
        });
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    selection = (Integer) view.getTag();
                    applySelection(false);
                }
                return false;
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { activateSelection(); }
        });
        selectableViews.add(row);
        return row;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(RabbitTypography.regular(this));
        view.setIncludeFontPadding(false);
        return view;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        InputDevice device = event.getDevice();
        boolean wheelVolume = device != null
                && "och1970_holl_key".equals(device.getName())
                && (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN);
        boolean up = key == KeyEvent.KEYCODE_DPAD_UP
                || (wheelVolume && key == KeyEvent.KEYCODE_VOLUME_UP);
        boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN
                || (wheelVolume && key == KeyEvent.KEYCODE_VOLUME_DOWN);
        if (up || down) {
            // Consume both edges so the ROM never forwards this wheel tick to volume.
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (page == Page.IDLE) {
                showHome();
                getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return true;
            }
            if (selectableViews.isEmpty()) return true;
            int delta = up ? -1 : 1;
            selection = clamp(selection + delta, 0, selectableViews.size() - 1);
            savedSelection[page.ordinal()] = selection;
            applySelection(true);
            getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                activateSelection();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void applySelection(boolean requestFocus) {
        for (int i = 0; i < selectableViews.size(); i++) {
            LinearLayout row = (LinearLayout) selectableViews.get(i);
            boolean selected = i == selection;
            row.setBackground(roundedBackground(selected ? ORANGE : CARD));
            ((TextView) row.getChildAt(0)).setTextColor(selected ? DARK_INK : WARM_WHITE);
            ((TextView) row.getChildAt(1)).setTextColor(selected ? DARK_INK : MUTED);
            row.setSelected(selected);
        }
        if (selection < 0 || selection >= selectableViews.size()) return;
        View selected = selectableViews.get(selection);
        if (requestFocus) selected.requestFocus();
        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override public void run() {
                    scrollView.scrollTo(0, Math.max(0, selected.getTop() - dp(8)));
                }
            });
        }
    }

    private void activateSelection() {
        if (selection < 0 || selection >= visibleEntries.size()) return;
        savedSelection[page.ordinal()] = selection;
        visibleEntries.get(selection).action.run();
    }

    @Override
    public void onBackPressed() {
        if (page == Page.UTILITIES) {
            savedSelection[page.ordinal()] = selection;
            showApps();
        } else if (page == Page.APPS) {
            savedSelection[page.ordinal()] = selection;
            showHome();
        } else {
            showHome();
        }
    }

    private void openDialer() {
        launchIntent(new Intent(Intent.ACTION_DIAL), "No phone app is available");
    }

    private void openMessages() {
        Intent appCategory = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.APP_MESSAGING");
        launchPackage("com.cipheros.messaging", appCategory,
                new Intent(Intent.ACTION_SENDTO, Uri.parse("sms:")),
                "No messaging app is available");
    }

    private void openCamera() {
        launchIntent(new Intent(this, CameraActivity.class), "Camera isn't available");
    }

    private void openMusic() {
        Intent musicCategory = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.APP_MUSIC");
        if (launchPackageQuietly("org.akanework.gramophone")) return;
        launchPackage("com.android.music", musicCategory, "No music app is available");
    }

    private void launchPackage(String packageName, Intent fallback, String error) {
        launchPackage(packageName, fallback, null, error);
    }

    private void launchPackage(String packageName, Intent fallbackOne, Intent fallbackTwo,
            String error) {
        if (launchPackageQuietly(packageName)) return;
        if (launchIntentQuietly(fallbackOne)) return;
        if (launchIntentQuietly(fallbackTwo)) return;
        showError(error);
    }

    private boolean launchPackageQuietly(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        return launchIntentQuietly(launch);
    }

    private void launchIntent(Intent intent, String error) {
        if (!launchIntentQuietly(intent)) showError(error);
    }

    private boolean launchIntentQuietly(Intent intent) {
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private GradientDrawable roundedBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(15));
        return background;
    }

    private void updateClock() {
        Date now = new Date();
        if (clockView != null) clockView.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        if (dateView != null) dateView.setText(new SimpleDateFormat("EEEE · MMM d", Locale.getDefault()).format(now));
    }

    private void updateStatus() {
        if (statusView == null) return;
        if (recording) {
            statusView.setText("Recording · release button to save");
            statusView.setTextColor(ORANGE);
            return;
        }
        statusView.setTextColor(MUTED);
        String battery = batteryPercent < 0 ? "Battery --" : "Battery " + batteryPercent + "%";
        statusView.setText(networkLabel + "   ·   " + battery);
    }

    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT < 21) return;
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    view.setPadding(left + bars.left, top + bars.top,
                            right + bars.right, bottom + bars.bottom);
                } else {
                    view.setPadding(left + insets.getSystemWindowInsetLeft(),
                            top + insets.getSystemWindowInsetTop(),
                            right + insets.getSystemWindowInsetRight(),
                            bottom + insets.getSystemWindowInsetBottom());
                }
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
