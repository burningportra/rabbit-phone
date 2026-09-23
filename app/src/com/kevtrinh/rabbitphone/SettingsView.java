package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native, same-window settings for Rabbit Phone and links to Android settings owned by Home. */
public final class SettingsView extends ViewGroup implements HardwarePage {
    public interface Host {
        void onBluetooth();
        void onWifi();
        void onCellular();
        void onTheme();
        void onDeviceSettings();
        void onTimeSettings();
        void onLanguageSettings();
        void onMessage(String message);
    }

    private static final int ORANGE = 0xffff3400;
    private static final int WHITE = 0xfff5efe1;
    private static final int MUTED = 0xff8e8984;
    private static final int ROOT = 0, DISPLAY = 1, SOUND = 2, NETWORK = 3, MAGIC = 4,
            DEVICE = 5, SLEEP = 6, BRIGHTNESS_EDITOR = 7, VOLUME_EDITOR = 8, DEVICE_INFO = 9;
    private static final long[] SLEEP_VALUES = {15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L};
    private static final String[] SLEEP_LABELS = {"15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes"};

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final TextView title, infoText;
    private final ScrollView scroll;
    private final LinearLayout rows;
    private final TextView editorValue, editorHint;
    private TextView decrease, increase, done;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private Row[] currentRows = new Row[0];
    private int page = ROOT, selected, generation;
    private int displayOrigin, soundOrigin;
    private boolean wheelFocus, hostActive, attached, released, refreshRunning;
    private boolean editorKnown, editorWritable, editorWarned;
    private int editorValueNumber, editorMaximum;
    private float scale = 1f, originX, originY;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshRunning = false;
            if (!interactive()) return;
            refreshVisibleValues();
            refreshRunning = true;
            main.postDelayed(this, 1000);
        }
    };

    public SettingsView(Context context, Host host) {
        super(context);
        if (host == null) throw new IllegalArgumentException("Settings host is required");
        this.host = host;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        setBackgroundColor(Color.BLACK);
        setWillNotDraw(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("Rabbit settings");

        title = label("settings", 46, ORANGE);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));

        infoText = label("", 34, WHITE);
        infoText.setGravity(Gravity.TOP | Gravity.START);
        infoText.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        editorValue = label("Unavailable", 72, WHITE);
        editorValue.setGravity(Gravity.CENTER);
        editorValue.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        decrease = editorControl("−", "Decrease brightness", -1, generation, page);
        increase = editorControl("+", "Increase brightness", 1, generation, page);
        done = doneControl("Done adjusting brightness", generation, page);
        editorHint = label("wheel to adjust", 21, MUTED);
        editorHint.setGravity(Gravity.CENTER);

        addView(title); addView(scroll); addView(infoText); addView(editorValue); addView(decrease);
        addView(increase); addView(done); addView(editorHint);
        showPage(ROOT, 0, false);
    }

    @Override public View getView() { return this; }

    @Override public void setHostActive(boolean active) {
        hostActive = active;
        reconcileLifecycle();
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (scroll != null) reconcileLifecycle();
    }

    @Override public void release() {
        if (released) return;
        released = true;
        hostActive = false;
        generation++;
        stopRefresh();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        reconcileLifecycle();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        reconcileLifecycle();
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        reconcileLifecycle();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (scroll != null) reconcileLifecycle();
    }

    private boolean interactive() {
        return !released && hostActive && attached && isEnabled() && isShown() && hasWindowFocus();
    }

    private void reconcileLifecycle() {
        if (interactive()) {
            refreshVisibleValues();
            if (!refreshRunning) {
                refreshRunning = true;
                main.postDelayed(refresh, 1000);
            }
        } else stopRefresh();
    }

    private void stopRefresh() {
        refreshRunning = false;
        main.removeCallbacks(refresh);
    }

    @Override public boolean handleWheel(boolean up) {
        if (!interactive()) return false;
        if (isEditor()) {
            changeEditor(up ? 1 : -1, generation, page);
            return true;
        }
        if (currentRows.length == 0) return true;
        wheelFocus = true;
        int next = Math.max(0, Math.min(currentRows.length - 1, selected + (up ? -1 : 1)));
        if (next != selected) {
            selected = next;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            scrollToSelection();
        }
        refreshRows();
        return true;
    }

    @Override public boolean handleSingle() {
        if (!interactive()) return false;
        if (isEditor()) {
            finishEditor(generation, page);
            return true;
        }
        if (currentRows.length == 0) return true;
        currentRows[selected].activate();
        return true;
    }

    @Override public boolean handleBack() {
        if (!interactive()) return false;
        if (page == ROOT) return false;
        if (page == BRIGHTNESS_EDITOR) showPage(DISPLAY, displayOrigin, true);
        else if (page == VOLUME_EDITOR) showPage(SOUND, soundOrigin, true);
        else if (page == SLEEP) showPage(DISPLAY, displayOrigin, true);
        else if (page == DEVICE_INFO) showPage(DEVICE, 0, true);
        else showPage(ROOT, rootOrigin(page), true);
        return true;
    }

    private boolean isEditor() { return page == BRIGHTNESS_EDITOR || page == VOLUME_EDITOR; }

    private void showPage(int nextPage, int nextSelection, boolean showFocus) {
        if (released) return;
        generation++;
        page = nextPage;
        selected = Math.max(0, nextSelection);
        wheelFocus = showFocus;
        rows.removeAllViews();
        currentRows = buildRows(nextPage);
        if (currentRows.length > 0) selected = Math.min(selected, currentRows.length - 1);
        for (Row row : currentRows) rows.addView(row, new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(72 * scale))));
        boolean editor = isEditor();
        if (editor) rebuildEditorControls();
        scroll.setVisibility(editor ? GONE : VISIBLE);
        infoText.setVisibility(nextPage == DEVICE_INFO ? VISIBLE : GONE);
        editorValue.setVisibility(editor ? VISIBLE : GONE);
        decrease.setVisibility(editor ? VISIBLE : GONE);
        increase.setVisibility(editor ? VISIBLE : GONE);
        done.setVisibility(editor ? VISIBLE : GONE);
        editorHint.setVisibility(editor ? VISIBLE : GONE);
        title.setText(titleFor(nextPage));
        setContentDescription(descriptionFor(nextPage));
        if (nextPage == DEVICE_INFO) {
            infoText.setText(deviceInfo().replace(" · ", "\n"));
            infoText.setContentDescription(deviceInfo());
        }
        if (editor) {
            editorWarned = false;
            boolean brightness = nextPage == BRIGHTNESS_EDITOR;
            editorValue.setContentDescription(brightness ? "Adjust brightness" : "Adjust media volume");
            decrease.setContentDescription(brightness ? "Decrease brightness" : "Decrease media volume");
            increase.setContentDescription(brightness ? "Increase brightness" : "Increase media volume");
            done.setContentDescription(brightness ? "Done adjusting brightness" : "Done adjusting media volume");
            readEditor();
        }
        refreshRows();
        requestLayout();
        if (showFocus && !editor) scrollToSelection();
    }

    private Row[] buildRows(int forPage) {
        switch (forPage) {
            case ROOT:
                return new Row[]{
                        row("Display", "", DISPLAY), row("Sound", "", SOUND), action("Bluetooth", "", new Runnable() {
                            @Override public void run() { host.onBluetooth(); }
                        }), row("Network", "", NETWORK), row("Magic", "", MAGIC), row("Device", "", DEVICE)};
            case DISPLAY:
                return new Row[]{action("Brightness", brightnessText(), new Runnable() {
                    @Override public void run() { displayOrigin = 0; showPage(BRIGHTNESS_EDITOR, 0, false); }
                }), action("Auto-sleep", sleepText(), new Runnable() {
                    @Override public void run() { displayOrigin = 1; showPage(SLEEP, sleepSelection(), true); }
                })};
            case SOUND:
                return new Row[]{action("Media volume", volumeText(), new Runnable() {
                    @Override public void run() { soundOrigin = 0; showPage(VOLUME_EDITOR, 0, false); }
                }), action("System sound effects", soundEffectsText(), new Runnable() {
                    @Override public void run() { toggleSoundEffects(); }
                })};
            case NETWORK:
                return new Row[]{action("Wi-Fi", "", new Runnable() { @Override public void run() { host.onWifi(); } }),
                        action("Cellular", "", new Runnable() { @Override public void run() { host.onCellular(); } })};
            case MAGIC:
                return new Row[]{action("Rabbit theme", "home and lock screen", new Runnable() {
                    @Override public void run() { host.onTheme(); }
                })};
            case DEVICE:
                return new Row[]{action("Device info", deviceInfo(), new Runnable() {
                    @Override public void run() { showPage(DEVICE_INFO, 0, false); }
                }), action("Time", "", new Runnable() { @Override public void run() { host.onTimeSettings(); } }),
                        action("Language", "", new Runnable() { @Override public void run() { host.onLanguageSettings(); } }),
                        action("Android settings", "", new Runnable() { @Override public void run() { host.onDeviceSettings(); } })};
            case DEVICE_INFO: return new Row[0];
            case SLEEP:
                Row[] choices = new Row[SLEEP_VALUES.length];
                for (int i = 0; i < choices.length; i++) {
                    final int choice = i;
                    choices[i] = new Row(SLEEP_LABELS[i], "", new Runnable() {
                        @Override public void run() { setSleep(choice); }
                    }, generation, "Set auto-sleep to " + SLEEP_LABELS[i]);
                }
                return choices;
            default: return new Row[0];
        }
    }

    private Row row(String name, String value, final int targetPage) {
        return action(name, value, new Runnable() {
            @Override public void run() { showPage(targetPage, 0, false); }
        });
    }

    private Row action(String name, String value, Runnable action) {
        return new Row(name, value, action, generation);
    }

    private String titleFor(int value) {
        switch (value) {
            case DISPLAY: return "display";
            case SOUND: return "sound";
            case NETWORK: return "network";
            case MAGIC: return "magic";
            case DEVICE: return "device";
            case SLEEP: return "auto-sleep";
            case BRIGHTNESS_EDITOR: return "brightness";
            case VOLUME_EDITOR: return "media volume";
            case DEVICE_INFO: return "device info";
            default: return "settings";
        }
    }

    private String descriptionFor(int value) {
        switch (value) {
            case DISPLAY: return "Display settings";
            case SOUND: return "Sound settings";
            case NETWORK: return "Network settings";
            case MAGIC: return "Magic settings";
            case DEVICE: return "Device settings";
            case SLEEP: return "Auto-sleep settings";
            case BRIGHTNESS_EDITOR: return "Adjust brightness";
            case VOLUME_EDITOR: return "Adjust media volume";
            case DEVICE_INFO: return "Device info";
            default: return "Rabbit settings";
        }
    }

    private int rootOrigin(int value) {
        if (value == DISPLAY) return 0;
        if (value == SOUND) return 1;
        if (value == NETWORK) return 3;
        if (value == MAGIC) return 4;
        return 5;
    }

    private void refreshVisibleValues() {
        if (isEditor()) {
            readEditor();
            return;
        }
        if (page == DISPLAY || page == SOUND || page == DEVICE || page == DEVICE_INFO) {
            if (page == DEVICE_INFO) {
                infoText.setText(deviceInfo().replace(" · ", "\n"));
                infoText.setContentDescription(deviceInfo());
                return;
            }
            String[] values = page == DISPLAY ? new String[]{brightnessText(), sleepText()}
                    : page == SOUND ? new String[]{volumeText(), soundEffectsText()}
                    : new String[]{deviceInfo(), "", "", ""};
            for (int i = 0; i < currentRows.length && i < values.length; i++) currentRows[i].setValue(values[i]);
        }
    }

    private void refreshRows() {
        for (int i = 0; i < currentRows.length; i++) currentRows[i].setWheelSelected(wheelFocus && i == selected);
    }

    private void scrollToSelection() {
        if (selected < 0 || selected >= currentRows.length) return;
        final int expectedPage = page, expectedGeneration = generation;
        final Row expectedRow = currentRows[selected];
        scroll.post(new Runnable() {
            @Override public void run() {
                if (!interactive() || page != expectedPage || generation != expectedGeneration
                        || expectedRow.getParent() != rows || !expectedRow.isAttachedToWindow()) return;
                int top = expectedRow.getTop();
                int bottom = expectedRow.getBottom();
                int visibleTop = scroll.getScrollY();
                int visibleBottom = visibleTop + scroll.getHeight();
                if (top < visibleTop) scroll.smoothScrollTo(0, top);
                else if (bottom > visibleBottom) scroll.smoothScrollTo(0, bottom - scroll.getHeight());
            }
        });
    }

    private String brightnessText() {
        try {
            int value = clamp(Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS), 0, 255);
            return Math.round(value * 100f / 255f) + "%";
        } catch (RuntimeException | Settings.SettingNotFoundException error) { return "unavailable"; }
    }

    private String volumeText() {
        try {
            if (audio == null) return "unavailable";
            int maximum = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int value = clamp(audio.getStreamVolume(AudioManager.STREAM_MUSIC), 0, maximum);
            return Math.round(value * 100f / maximum) + "%";
        } catch (RuntimeException error) { return "unavailable"; }
    }

    private String sleepText() {
        try {
            long value = Settings.System.getLong(getContext().getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT);
            for (int i = 0; i < SLEEP_VALUES.length; i++) if (value == SLEEP_VALUES[i]) return SLEEP_LABELS[i];
            if (value < 60_000) return Math.max(1, value / 1000) + " seconds";
            return Math.max(1, value / 60_000) + " minutes";
        } catch (RuntimeException | Settings.SettingNotFoundException error) { return "unavailable"; }
    }

    private String soundEffectsText() {
        try {
            return Settings.System.getInt(getContext().getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED) != 0 ? "on" : "off";
        } catch (RuntimeException | Settings.SettingNotFoundException error) { return "unavailable"; }
    }

    private int sleepSelection() {
        try {
            long current = Settings.System.getLong(getContext().getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT);
            int nearest = 0;
            for (int i = 1; i < SLEEP_VALUES.length; i++)
                if (Math.abs(SLEEP_VALUES[i] - current) < Math.abs(SLEEP_VALUES[nearest] - current)) nearest = i;
            return nearest;
        } catch (RuntimeException | Settings.SettingNotFoundException error) { return 0; }
    }

    private void setSleep(int choice) {
        int expectedGeneration = generation;
        if (!validAction(expectedGeneration, SLEEP) || choice < 0 || choice >= SLEEP_VALUES.length) return;
        if (!Settings.System.canWrite(getContext())) { unavailable("Auto-sleep control unavailable"); return; }
        boolean wrote;
        try { wrote = Settings.System.putLong(getContext().getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, SLEEP_VALUES[choice]); }
        catch (RuntimeException error) { wrote = false; }
        if (!wrote || !sleepMatches(SLEEP_VALUES[choice])) { unavailable("Couldn't change auto-sleep"); return; }
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        showPage(DISPLAY, displayOrigin, true);
    }

    private boolean sleepMatches(long expected) {
        try { return Settings.System.getLong(getContext().getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT) == expected; }
        catch (RuntimeException | Settings.SettingNotFoundException error) { return false; }
    }

    private void toggleSoundEffects() {
        int expectedGeneration = generation;
        if (!validAction(expectedGeneration, SOUND)) return;
        int current;
        try { current = Settings.System.getInt(getContext().getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED); }
        catch (RuntimeException | Settings.SettingNotFoundException error) { unavailable("System sound effects unavailable"); return; }
        if (!Settings.System.canWrite(getContext())) { unavailable("System sound effects control unavailable"); return; }
        int next = current == 0 ? 1 : 0;
        boolean wrote;
        try { wrote = Settings.System.putInt(getContext().getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, next); }
        catch (RuntimeException error) { wrote = false; }
        try { wrote = wrote && Settings.System.getInt(getContext().getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED) == next; }
        catch (RuntimeException | Settings.SettingNotFoundException error) { wrote = false; }
        if (!wrote) { unavailable("Couldn't change system sound effects"); return; }
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        refreshVisibleValues();
    }

    private TextView editorControl(String text, String description, final int delta,
                                   final int expectedGeneration, final int expectedPage) {
        TextView view = label(text, 52, WHITE);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View view) {
                TextView owner = delta < 0 ? decrease : increase;
                if (view != owner || view.getParent() != SettingsView.this) return;
                changeEditor(delta, expectedGeneration, expectedPage);
            }
        });
        return view;
    }

    private TextView doneControl(String description, final int expectedGeneration, final int expectedPage) {
        TextView view = label("done", 34, WHITE);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View view) {
                if (view != done || view.getParent() != SettingsView.this) return;
                finishEditor(expectedGeneration, expectedPage);
            }
        });
        return view;
    }

    private void rebuildEditorControls() {
        removeView(decrease); removeView(increase); removeView(done);
        boolean brightness = page == BRIGHTNESS_EDITOR;
        decrease = editorControl("−", brightness ? "Decrease brightness" : "Decrease media volume", -1, generation, page);
        increase = editorControl("+", brightness ? "Increase brightness" : "Increase media volume", 1, generation, page);
        done = doneControl(brightness ? "Done adjusting brightness" : "Done adjusting media volume", generation, page);
        addView(decrease); addView(increase); addView(done);
    }

    private void readEditor() {
        editorKnown = editorWritable = false;
        try {
            if (page == BRIGHTNESS_EDITOR) {
                editorMaximum = 255;
                editorValueNumber = clamp(Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS), 0, 255);
                editorWritable = Settings.System.canWrite(getContext());
            } else if (page == VOLUME_EDITOR && audio != null) {
                editorMaximum = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                editorValueNumber = clamp(audio.getStreamVolume(AudioManager.STREAM_MUSIC), 0, editorMaximum);
                editorWritable = !audio.isVolumeFixed();
            } else return;
            editorKnown = true;
        } catch (RuntimeException | Settings.SettingNotFoundException ignored) { }
        refreshEditor();
        if (interactive() && (!editorKnown || !editorWritable) && !editorWarned) {
            editorWarned = true;
            host.onMessage(titleFor(page) + " control unavailable");
        }
    }

    private void refreshEditor() {
        String value = editorKnown ? Math.round(editorValueNumber * 100f / Math.max(1, editorMaximum)) + "%" : "Unavailable";
        editorValue.setText(value);
        if (Build.VERSION.SDK_INT >= 30) editorValue.setStateDescription(value);
        boolean enabled = editorKnown && editorWritable;
        decrease.setEnabled(enabled && editorValueNumber > 0);
        increase.setEnabled(enabled && editorValueNumber < editorMaximum);
        decrease.setAlpha(decrease.isEnabled() ? 1f : .32f);
        increase.setAlpha(increase.isEnabled() ? 1f : .32f);
        editorHint.setText(!editorKnown ? "value unavailable" : editorWritable ? "wheel or buttons to adjust" : "control unavailable");
    }

    private void changeEditor(int delta, int expectedGeneration, int expectedPage) {
        if (!validAction(expectedGeneration, expectedPage) || !isEditor()) return;
        readEditor();
        if (!editorKnown || !editorWritable) { unavailable(titleFor(page) + " control unavailable"); return; }
        int step = page == BRIGHTNESS_EDITOR ? 13 : 1;
        int next = clamp(editorValueNumber + delta * step, 0, editorMaximum);
        if (next == editorValueNumber) return;
        boolean wrote = false;
        try {
            if (page == BRIGHTNESS_EDITOR) {
                boolean manual = Settings.System.putInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                boolean brightness = Settings.System.putInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, next);
                wrote = manual && brightness && Settings.System.getInt(getContext().getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS) == next;
            } else if (audio != null) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
                wrote = audio.getStreamVolume(AudioManager.STREAM_MUSIC) == next;
            }
        } catch (RuntimeException | Settings.SettingNotFoundException ignored) { }
        if (!wrote) { unavailable("Couldn't change " + titleFor(page)); readEditor(); return; }
        editorValueNumber = next;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        refreshEditor();
    }

    private void finishEditor(int expectedGeneration, int expectedPage) {
        if (!validAction(expectedGeneration, expectedPage) || !isEditor()) return;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (page == BRIGHTNESS_EDITOR) showPage(DISPLAY, displayOrigin, true);
        else showPage(SOUND, soundOrigin, true);
    }

    private boolean validAction(int expectedGeneration, int expectedPage) {
        return interactive() && generation == expectedGeneration && page == expectedPage;
    }

    private void unavailable(String message) {
        if (!interactive()) return;
        host.onMessage(message);
    }

    private String deviceInfo() {
        String app = "unknown";
        try {
            PackageInfo info = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            if (info.versionName != null && info.versionName.length() > 0) app = info.versionName;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        String model = Build.MODEL == null || Build.MODEL.trim().length() == 0 ? "R1" : Build.MODEL.trim();
        String android = Build.VERSION.RELEASE == null ? "unknown" : Build.VERSION.RELEASE;
        return model + " · Android " + android + " · Rabbit Phone " + app;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        view.setTypeface(RabbitTypography.regular(getContext()));
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setIncludeFontPadding(false);
        view.setSoundEffectsEnabled(false);
        return view;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void measureExact(View view, int width, int height) {
        view.measure(MeasureSpec.makeMeasureSpec(Math.max(0, Math.round(width * scale)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, Math.round(height * scale)), MeasureSpec.EXACTLY));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        scale = Math.max(0f, Math.min(width / 480f, height / 640f));
        originX = (width - 480 * scale) / 2f;
        originY = (height - 640 * scale) / 2f;
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 46 * scale);
        measureExact(title, 376, 64);
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * scale);
        infoText.setLineSpacing(18 * scale, 1f);
        measureExact(infoText, 432, 300);
        for (Row row : currentRows) {
            ViewGroup.LayoutParams params = row.getLayoutParams();
            int rowHeight = Math.max(1, Math.round(72 * scale));
            if (params != null && params.height != rowHeight) params.height = rowHeight;
        }
        measureExact(scroll, 432, 442);
        editorValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, 72 * scale);
        decrease.setTextSize(TypedValue.COMPLEX_UNIT_PX, 52 * scale);
        increase.setTextSize(TypedValue.COMPLEX_UNIT_PX, 52 * scale);
        done.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * scale);
        editorHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21 * scale);
        measureExact(editorValue, 432, 132);
        measureExact(decrease, 126, 82);
        measureExact(increase, 126, 82);
        measureExact(done, 160, 68);
        measureExact(editorHint, 432, 54);
    }

    private void place(View view, int x, int y) {
        int left = Math.round(originX + x * scale), top = Math.round(originY + y * scale);
        view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        place(title, 80, 96);
        place(scroll, 24, 174);
        place(infoText, 24, 190);
        place(editorValue, 24, 188);
        place(decrease, 24, 344);
        place(increase, 330, 344);
        place(done, 160, 462);
        place(editorHint, 24, 544);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        canvas.translate(originX, originY);
        canvas.scale(scale, scale);
        paint.setColor(ORANGE);
        paint.setStyle(Paint.Style.FILL);
        iconPath.reset();
        for (int i = 0; i < 32; i++) {
            double angle = i * Math.PI / 16;
            float radius = i % 4 == 0 || i % 4 == 3 ? 10.5f : 15f;
            float x = 45 + radius * (float) Math.cos(angle);
            float y = 128 + radius * (float) Math.sin(angle);
            if (i == 0) iconPath.moveTo(x, y); else iconPath.lineTo(x, y);
        }
        iconPath.close();
        canvas.drawPath(iconPath, paint);
        paint.setColor(Color.BLACK);
        canvas.drawRect(41, 124, 49, 132, paint);
        canvas.restoreToCount(save);
    }

    private final class Row extends View {
        private final String name;
        private String value;
        private final Runnable action;
        private final int ownerGeneration;
        private final Paint type = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean wheelSelected;

        Row(String name, String value, Runnable action, int ownerGeneration) {
            this(name, value, action, ownerGeneration, name);
        }

        Row(String name, String value, Runnable action, int ownerGeneration, String description) {
            super(SettingsView.this.getContext());
            this.name = name;
            this.value = value == null ? "" : value;
            this.action = action;
            this.ownerGeneration = ownerGeneration;
            type.setTypeface(RabbitTypography.regular(getContext()));
            setContentDescription(description);
            setFocusable(true);
            setClickable(true);
            setMinimumHeight(dp(44));
            setSoundEffectsEnabled(false);
            updateStateDescription();
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View view) { activate(); }
            });
        }

        void setValue(String next) {
            next = next == null ? "" : next;
            if (value.equals(next)) return;
            value = next;
            updateStateDescription();
            invalidate();
        }

        void updateStateDescription() {
            if (Build.VERSION.SDK_INT >= 30) setStateDescription(value.length() == 0 ? null : value);
        }

        void setWheelSelected(boolean selected) {
            wheelSelected = selected;
            setSelected(selected);
            invalidate();
        }

        void activate() {
            if (!interactive() || page == BRIGHTNESS_EDITOR || page == VOLUME_EDITOR
                    || ownerGeneration != generation || getParent() != rows) return;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = getWidth() / Math.max(1f, 432f * scale);
            float unit = Math.max(.01f, scale * s);
            type.setTextAlign(Paint.Align.LEFT);
            type.setStyle(Paint.Style.FILL);
            type.setColor(wheelSelected ? ORANGE : WHITE);
            type.setTextSize((value.length() == 0 ? 34 : 30) * unit);
            canvas.drawText(name, 0, (value.length() == 0 ? 44 : 32) * unit, type);
            if (value.length() > 0) {
                type.setColor(wheelSelected ? ORANGE : MUTED);
                type.setTextSize(18 * unit);
                canvas.drawText(value, 0, 58 * unit, type);
            }
            type.setColor(wheelSelected ? ORANGE : 0xff615c58);
            type.setStyle(Paint.Style.STROKE);
            type.setStrokeWidth(2.2f * unit);
            float x = getWidth() - 8 * unit, y = 34 * unit;
            canvas.drawLine(x - 7 * unit, y - 8 * unit, x, y, type);
            canvas.drawLine(x, y, x - 7 * unit, y + 8 * unit, type);
            if (wheelSelected) canvas.drawLine(0, 66 * unit, getWidth(), 66 * unit, type);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
        }

        @Override public boolean performAccessibilityAction(int actionId, Bundle arguments) {
            if (!interactive() || ownerGeneration != generation || getParent() != rows) return false;
            return super.performAccessibilityAction(actionId, arguments);
        }
    }
}
