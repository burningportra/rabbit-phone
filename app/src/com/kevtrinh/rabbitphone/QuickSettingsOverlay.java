package com.kevtrinh.rabbitphone;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.IdentityHashMap;
import java.util.Map;

/** Same-window quick controls. Reads never change Android preferences; only user input writes. */
public final class QuickSettingsOverlay {
    public interface Host {
        void onCamera();
        void onKeyboard();
        void onLock();
        void onSettings();
        void onMessage(String message);
        default void onDismiss() { }
    }

    private static final int WHITE = 0xfff5efe1, ORANGE = 0xffff3400, REMAINDER = 0xff4c1100;
    private static final String[] SHORTCUTS = {"Camera", "Keyboard", "Lock", "Settings"};
    private final Activity activity;
    private final Host host;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Panel panel;
    private View previousFocus;
    private int selected;
    private boolean showSelection;
    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            if (panel == null || !panel.isAttachedToWindow() || !panel.isShown()
                    || panel.getWindowVisibility() != View.VISIBLE) return;
            refreshValues();
            main.postDelayed(this, 500);
        }
    };

    public QuickSettingsOverlay(Activity activity, Host host) {
        if (activity == null || host == null) throw new IllegalArgumentException("Activity and host are required");
        this.activity = activity;
        this.host = host;
        audio = activity.getSystemService(AudioManager.class);
    }

    public void show(ViewGroup container) {
        if (container == null) throw new IllegalArgumentException("Overlay container is required");
        if (panel != null && panel.getParent() == container) { refreshValues(); restartRefresh(); return; }
        dismiss();
        previousFocus = activity.getCurrentFocus();
        selected = 0;
        showSelection = false;
        panel = new Panel();
        container.addView(panel, new ViewGroup.LayoutParams(-1, -1));
        panel.hideSiblingAccessibility(container);
        panel.bringToFront();
        panel.requestFocus();
        refreshValues();
        restartRefresh();
    }

    public boolean isVisible() { return panel != null; }

    public void dismiss() {
        main.removeCallbacks(refreshTick);
        Panel old = panel;
        if (old == null) return;
        panel = null;
        old.brightness.cancelTouch();
        old.volume.cancelTouch();
        if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        old.restoreSiblingAccessibility();
        View focus = previousFocus;
        previousFocus = null;
        if (focus != null && focus.isAttachedToWindow() && focus.isShown()) focus.requestFocus();
        host.onDismiss();
    }

    public void release() { dismiss(); main.removeCallbacks(refreshTick); }

    public boolean handleWheel(boolean up) {
        if (panel == null) return false;
        int next = Math.max(0, Math.min(SHORTCUTS.length - 1, selected + (up ? -1 : 1)));
        showSelection = true;
        if (next != selected) {
            selected = next;
            panel.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        for (Shortcut shortcut : panel.shortcuts) shortcut.invalidate();
        return true;
    }

    public boolean handleSingle() {
        if (panel == null) return false;
        activate(selected);
        return true;
    }

    private void activate(int index) {
        if (panel == null || !panel.isAttachedToWindow() || !panel.isShown()
                || panel.getWindowVisibility() != View.VISIBLE) return;
        panel.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        dismiss();
        switch (index) {
            case 0: host.onCamera(); break;
            case 1: host.onKeyboard(); break;
            case 2: host.onLock(); break;
            case 3: host.onSettings(); break;
            default: break;
        }
    }

    private void restartRefresh() {
        main.removeCallbacks(refreshTick);
        if (panel != null && panel.isAttachedToWindow() && panel.isShown()
                && panel.getWindowVisibility() == View.VISIBLE) main.post(refreshTick);
    }

    private void refreshValues() {
        if (panel == null) return;
        if (panel.getParent() instanceof ViewGroup) panel.hideSiblingAccessibility((ViewGroup) panel.getParent());
        panel.brightness.readValue();
        panel.volume.readValue();
        panel.status.readStatus();
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    /** Normalized 480x640 placement, with real View children for touch and accessibility. */
    private final class Panel extends ViewGroup {
        final Status status = new Status();
        final Slider brightness = new Slider(true);
        final Slider volume = new Slider(false);
        final Shortcut[] shortcuts = new Shortcut[4];
        final Paint grabPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final IdentityHashMap<View, Integer> siblingAccessibility = new IdentityHashMap<View, Integer>();
        float scale, originX, originY;
        float grabX, grabY;
        int grabPointer = -1;

        Panel() {
            super(activity);
            setBackgroundColor(Color.BLACK);
            setWillNotDraw(false);
            setClickable(true);
            setFocusableInTouchMode(true);
            setSoundEffectsEnabled(false);
            setContentDescription("Quick settings");
            addView(status); addView(brightness); addView(volume);
            for (int i = 0; i < shortcuts.length; i++) { shortcuts[i] = new Shortcut(i); addView(shortcuts[i]); }
            grabPaint.setColor(0xff9b3b26);
            grabPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void hideSiblingAccessibility(ViewGroup container) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View sibling = container.getChildAt(i);
                if (sibling == this || siblingAccessibility.containsKey(sibling)) continue;
                siblingAccessibility.put(sibling, sibling.getImportantForAccessibility());
                sibling.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
        }

        void restoreSiblingAccessibility() {
            for (Map.Entry<View, Integer> saved : siblingAccessibility.entrySet()) {
                View sibling = saved.getKey();
                // Do not overwrite an accessibility change made by another owner.
                if (sibling.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                    sibling.setImportantForAccessibility(saved.getValue());
            }
            siblingAccessibility.clear();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = resolveSize(480, widthSpec), height = resolveSize(640, heightSpec);
            setMeasuredDimension(width, height);
            scale = Math.max(0f, Math.min(width / 480f, height / 640f));
            originX = (width - 480 * scale) / 2f;
            originY = (height - 640 * scale) / 2f;
            measureChildExact(status, Math.round(432 * scale), Math.round(80 * scale));
            int sliderHeight = Math.max(dp(44), Math.round(112 * scale));
            measureChildExact(brightness, Math.round(432 * scale), sliderHeight);
            measureChildExact(volume, Math.round(432 * scale), sliderHeight);
            for (Shortcut shortcut : shortcuts)
                measureChildExact(shortcut, Math.max(dp(56), Math.round(108 * scale)), Math.max(dp(56), Math.round(112 * scale)));
        }

        private void measureChildExact(View child, int width, int height) {
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, height), MeasureSpec.EXACTLY));
        }

        private void place(View view, float x, float y) {
            int left = Math.round(originX + x * scale), top = Math.round(originY + y * scale);
            view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            place(status, 24, 0); place(brightness, 24, 96); place(volume, 24, 224);
            for (int i = 0; i < shortcuts.length; i++) place(shortcuts[i], 24 + 108 * i, 384);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            grabPaint.setStrokeWidth(Math.max(1f, 2 * scale));
            canvas.drawLine(originX + 192 * scale, originY + 580 * scale,
                    originX + 288 * scale, originY + 580 * scale, grabPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                grabPointer = -1;
                if (event.getPointerCount() == 1 && event.getY() >= originY + 544 * scale) {
                    grabPointer = event.getPointerId(0);
                    grabX = event.getX(); grabY = event.getY();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
            } else if (action == MotionEvent.ACTION_UP) {
                int index = event.findPointerIndex(grabPointer);
                boolean close = index >= 0 && event.getY(index) - grabY <= -56 * Math.max(.01f, scale)
                        && grabY - event.getY(index) > Math.abs(event.getX(index) - grabX) * 1.15f;
                cancelGrab();
                if (close && panel == this) dismiss();
            } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
                cancelGrab();
            }
            return true;
        }

        private void cancelGrab() {
            grabPointer = -1;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); restartRefresh(); }
        @Override protected void onDetachedFromWindow() {
            cancelGrab();
            restoreSiblingAccessibility();
            brightness.cancelTouch(); volume.cancelTouch();
            if (panel == this) {
                main.removeCallbacks(refreshTick);
                panel = null; previousFocus = null; host.onDismiss();
            }
            super.onDetachedFromWindow();
        }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) restartRefresh(); else stopVisibleWork();
        }
        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (visibility == VISIBLE) restartRefresh(); else stopVisibleWork();
        }
        private void stopVisibleWork() {
            if (panel == this) main.removeCallbacks(refreshTick);
            cancelGrab();
            // Visibility callbacks can occur during View's superclass construction.
            if (brightness != null) brightness.cancelTouch();
            if (volume != null) volume.cancelTouch();
        }
    }

    private final class Slider extends View {
        final boolean brightness;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path clip = new Path(), icon = new Path();
        final RectF bounds = new RectF();
        int value, maximum;
        boolean known, writable, muted, manual, tracking, warned;
        int pointer = -1;

        Slider(boolean brightness) {
            super(activity);
            this.brightness = brightness;
            maximum = brightness ? 255 : 15;
            setFocusable(true);
            setClickable(true);
            setSoundEffectsEnabled(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void readValue() {
            known = writable = false;
            try {
                if (brightness) {
                    value = Math.max(0, Math.min(255, Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS)));
                    manual = Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
                    writable = Settings.System.canWrite(activity);
                } else if (audio != null) {
                    maximum = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                    value = Math.max(0, Math.min(maximum, audio.getStreamVolume(AudioManager.STREAM_MUSIC)));
                    muted = value == 0 || audio.isStreamMute(AudioManager.STREAM_MUSIC);
                    writable = !audio.isVolumeFixed();
                } else throw new IllegalStateException("Audio service unavailable");
                known = true;
            } catch (RuntimeException | Settings.SettingNotFoundException unavailable) { writable = false; }
            String name = brightness ? "Brightness" : "Media volume";
            String description = name + (known ? ", " + Math.round(value * 100f / maximum) + "%" : ", unavailable")
                    + (writable ? "" : ", control unavailable");
            if (!description.contentEquals(getContentDescription() == null ? "" : getContentDescription()))
                setContentDescription(description);
            invalidate();
        }

        private void changeTo(int requested) {
            if (panel == null || (this != panel.brightness && this != panel.volume)
                    || !panel.isAttachedToWindow() || !panel.isShown()
                    || panel.getWindowVisibility() != View.VISIBLE) return;
            int next = Math.max(0, Math.min(maximum, requested));
            try {
                if (brightness) {
                    if (!Settings.System.canWrite(activity)) throw new SecurityException();
                    if (known && manual && value == next) return;
                    if (!Settings.System.putInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                            || !Settings.System.putInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, next))
                        throw new IllegalStateException("Brightness setting was rejected");
                } else {
                    if (audio == null || audio.isVolumeFixed()) throw new IllegalStateException("Volume is fixed");
                    if (known && value == next && (!muted || next == 0)) return;
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
                }
            } catch (RuntimeException unavailable) {
                if (!warned) host.onMessage(brightness ? "Allow system brightness control in Android settings"
                        : "Media volume control is unavailable for this output");
                warned = true;
            }
            readValue();
        }

        private void changeAt(float x) { if (getWidth() > 0) changeTo(Math.round(maximum * x / getWidth())); }

        void cancelTouch() {
            tracking = false;
            pointer = -1;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    warned = false; tracking = true; pointer = event.getPointerId(0);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    changeAt(event.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int point = event.findPointerIndex(pointer);
                    if (tracking && point >= 0) changeAt(event.getX(point));
                    return true;
                case MotionEvent.ACTION_UP:
                    int released = event.findPointerIndex(pointer);
                    if (tracking && released >= 0) { changeAt(event.getX(released)); performClick(); }
                    cancelTouch();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelTouch();
                    return true;
                default: return true;
            }
        }

        @Override public boolean performClick() { super.performClick(); return true; }
        @Override protected void onDetachedFromWindow() { cancelTouch(); super.onDetachedFromWindow(); }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.SeekBar");
            info.setEnabled(writable);
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, maximum, value));
            if (writable) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            int step = brightness ? 13 : 1;
            warned = false;
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                changeTo(value + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? step : -step));
                return true;
            }
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && arguments != null
                    && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                float target = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                if (!Float.isNaN(target) && !Float.isInfinite(target)) changeTo(Math.round(target));
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth(), height = getHeight();
            if (width <= 0 || height <= 0) return;
            float radius = Math.min(18f * width / 432f, height / 2f);
            bounds.set(0, 0, width, height);
            clip.reset(); clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clip);
            paint.setStyle(Paint.Style.FILL); paint.setColor(REMAINDER);
            canvas.drawRect(0, 0, width, height, paint);
            paint.setColor(ORANGE);
            if (known) canvas.drawRect(0, 0, width * value / maximum, height, paint);
            canvas.restoreToCount(save);
            if (known) {
                paint.setStyle(Paint.Style.FILL); paint.setColor(WHITE);
                paint.setTypeface(RabbitTypography.regular(activity));
                paint.setTextSize(Math.min(dp(22), height * .24f));
                paint.setTextAlign(Paint.Align.LEFT);
                paint.setColor(value * 1f / maximum > .45f ? Color.BLACK : WHITE);
                canvas.drawText(brightness ? "Brightness" : "Volume", width * .16f,
                        height / 2f - (paint.ascent() + paint.descent()) / 2f, paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                paint.setColor(value * 1f / maximum > .82f ? Color.BLACK : WHITE);
                canvas.drawText(Math.round(value * 100f / maximum) + "%", width - width * .045f,
                        height / 2f - (paint.ascent() + paint.descent()) / 2f, paint);
            }
            save = canvas.save();
            float size = Math.min(height * .42f, width * .1f);
            canvas.translate(width * .057f, (height - size) / 2f);
            canvas.scale(size / 32f, size / 32f);
            paint.setColor(Color.BLACK); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(2.5f);
            if (brightness) {
                canvas.drawCircle(16, 16, 6, paint);
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    canvas.drawLine(16 + 10 * (float) Math.cos(angle), 16 + 10 * (float) Math.sin(angle),
                            16 + 13 * (float) Math.cos(angle), 16 + 13 * (float) Math.sin(angle), paint);
                }
            } else {
                icon.reset(); icon.moveTo(3, 12); icon.lineTo(9, 12); icon.lineTo(16, 6);
                icon.lineTo(16, 26); icon.lineTo(9, 20); icon.lineTo(3, 20); icon.close();
                canvas.drawPath(icon, paint);
                paint.setStyle(Paint.Style.STROKE);
                if (muted) { canvas.drawLine(22, 12, 29, 20, paint); canvas.drawLine(29, 12, 22, 20, paint); }
                else { bounds.set(13, 6, 30, 26); canvas.drawArc(bounds, -65, 130, false, paint);
                    bounds.set(15, 11, 24, 21); canvas.drawArc(bounds, -65, 130, false, paint); }
            }
            canvas.restoreToCount(save);
        }
    }

    private final class Shortcut extends View {
        final int index;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        Shortcut(final int index) {
            super(activity);
            this.index = index;
            setContentDescription(SHORTCUTS[index]);
            setFocusable(true); setClickable(true); setSoundEffectsEnabled(false);
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View view) {
                    if (panel == null || panel.shortcuts[index] != Shortcut.this) return;
                    selected = index;
                    activate(index);
                }
            });
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            info.setSelected(showSelection && selected == index);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(dp(32), Math.min(getWidth(), getHeight()) * .62f);
            if (size <= 0) return;
            int save = canvas.save();
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
            canvas.scale(size / 32f, size / 32f);
            paint.setColor(WHITE); paint.setStyle(Paint.Style.FILL);
            path.reset();
            if (index == 0) {
                canvas.drawRoundRect(2, 5, 30, 28, 6, 6, paint);
                paint.setColor(Color.BLACK); canvas.drawRoundRect(6, 11, 26, 24, 3, 3, paint);
                paint.setColor(WHITE); canvas.drawCircle(16, 17, 6, paint);
                paint.setColor(Color.BLACK); canvas.drawCircle(16, 17, 3, paint);
            } else if (index == 1) {
                canvas.drawRoundRect(4, 3, 28, 29, 3, 3, paint);
                paint.setColor(Color.BLACK); canvas.drawRoundRect(8, 7, 24, 24, 1, 1, paint);
                paint.setColor(WHITE); canvas.drawRect(11, 10, 21, 13, paint); canvas.drawRect(14.5f, 12, 17.5f, 21, paint);
            } else if (index == 2) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4);
                canvas.drawRoundRect(9, 3, 23, 23, 7, 7, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(4, 14, 28, 30, 3, 3, paint);
                paint.setColor(Color.BLACK); canvas.drawCircle(16, 20, 2.5f, paint); canvas.drawRect(14.5f, 20, 17.5f, 26, paint);
            } else {
                for (int i = 0; i < 32; i++) {
                    double angle = i * Math.PI / 16;
                    float radius = i % 4 == 0 || i % 4 == 3 ? 10.5f : 15f;
                    float x = 16 + radius * (float) Math.cos(angle), y = 16 + radius * (float) Math.sin(angle);
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close(); canvas.drawPath(path, paint);
                paint.setColor(Color.BLACK); canvas.drawRect(12, 12, 20, 20, paint);
            }
            canvas.restoreToCount(save);
            if (showSelection && selected == index) {
                paint.setColor(ORANGE); paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(getWidth() / 2f, getHeight() - dp(10), dp(2.5f), paint);
            }
        }
    }

    private final class Status extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint.FontMetrics metrics = new Paint.FontMetrics();
        final RectF arc = new RectF();
        String clock = "";
        int battery = -1;
        boolean wifi, cellular;

        Status() { super(activity); paint.setTypeface(RabbitTypography.regular(activity)); }

        void readStatus() {
            clock = DateFormat.format(DateFormat.is24HourFormat(activity) ? "H:mm" : "h:mm", System.currentTimeMillis()).toString();
            BatteryManager manager = activity.getSystemService(BatteryManager.class);
            battery = manager == null ? -1 : manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (battery < 0 || battery > 100) battery = -1;
            wifi = cellular = false;
            try {
                ConnectivityManager connectivity = activity.getSystemService(ConnectivityManager.class);
                if (connectivity != null) for (Network network : connectivity.getAllNetworks()) {
                    NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                    if (caps != null) {
                        wifi |= caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                        cellular |= caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                    }
                }
            } catch (RuntimeException unavailable) { wifi = cellular = false; }
            setContentDescription(clock + (wifi ? ", Wi-Fi available" : "") + (cellular ? ", mobile network available" : "")
                    + (battery >= 0 ? ", battery " + battery + "%" : ", battery unavailable"));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;
            int save = canvas.save();
            canvas.scale(getWidth() / 432f, getHeight() / 80f);
            paint.setStyle(Paint.Style.FILL); paint.setColor(WHITE); paint.setTextSize(29);
            paint.setTextAlign(Paint.Align.CENTER); paint.getFontMetrics(metrics);
            canvas.drawText(clock, 216, 40 - (metrics.ascent + metrics.descent) / 2f, paint);
            paint.setColor(wifi ? WHITE : 0xff444444); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.5f);
            arc.set(0, 24, 32, 56); canvas.drawArc(arc, 225, 90, false, paint);
            arc.set(6, 30, 26, 50); canvas.drawArc(arc, 225, 90, false, paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(16, 41, 2, paint);
            paint.setColor(cellular ? WHITE : 0xff444444);
            for (int i = 0; i < 4; i++) canvas.drawRect(38 + i * 6, 44 - (i + 1) * 5, 41 + i * 6, 44, paint);
            paint.setColor(WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
            canvas.drawRoundRect(398, 33, 428, 47, 3, 3, paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawRect(429, 37, 432, 43, paint);
            if (battery >= 0) canvas.drawRoundRect(401, 36, 401 + 24 * battery / 100f, 44, 1, 1, paint);
            canvas.restoreToCount(save);
        }
    }
}
