package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/** Local timer choices and an in-window duration editor; never opens a dialog. */
public final class TimerSetupView extends ViewGroup implements HardwarePage {
    public interface Host { void onStart(long durationMillis); }
    private static final int ACCENT = 0xff6b63ff, WHITE = 0xfff5efe1, MUTED = 0xff99969e;
    private static final int[] MINUTES = {1, 3, 5, 10, 15, 30};
    private final Host host;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextView heading, customButton, hint, start;
    private final TextView[] presets = new TextView[MINUTES.length];
    private final DurationField[] fields = new DurationField[3];
    private final TextView[] plus = new TextView[3], minus = new TextView[3];
    private final int[] duration = {0, 5, 0};
    private boolean custom, editing;
    private int selected;
    private float scale = 1, originX, originY;

    public TimerSetupView(Context context, Host host) {
        super(context);
        this.host = host;
        setBackgroundColor(Color.BLACK);
        setWillNotDraw(false);
        heading = label("timer", 44, ACCENT);
        customButton = control("+", "Custom timer", new Runnable() {
            @Override public void run() { showCustom(); }
        });
        hint = label("", 20, MUTED);
        hint.setGravity(Gravity.CENTER);
        addView(heading); addView(customButton); addView(hint);
        for (int i = 0; i < presets.length; i++) {
            final int index = i;
            presets[i] = control(MINUTES[i] + (MINUTES[i] == 1 ? " minute" : " minutes"),
                    "Start " + MINUTES[i] + " minute timer",
                    new Runnable() {
                        @Override public void run() { host.onStart(MINUTES[index] * 60_000L); }
                    });
            presets[i].setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            addView(presets[i]);
        }
        String[] units = {"hours", "minutes", "seconds"};
        for (int i = 0; i < fields.length; i++) {
            final int index = i;
            fields[i] = new DurationField(i, units[i]);
            plus[i] = control("+", "Increase " + units[i], new Runnable() {
                @Override public void run() { selectField(index); changeValue(index, 1); }
            });
            minus[i] = control("−", "Decrease " + units[i], new Runnable() {
                @Override public void run() { selectField(index); changeValue(index, -1); }
            });
            addView(fields[i]); addView(plus[i]); addView(minus[i]);
        }
        start = control("start", "Start custom timer", new Runnable() {
            @Override public void run() { startCustom(); }
        });
        addView(start);
        updateMode();
    }

    @Override public View getView() { return this; }

    public boolean handleBack() {
        if (!custom) return false;
        custom = editing = false; selected = 0; updateMode(); return true;
    }

    public boolean handleWheel(boolean up) {
        if (!isEnabled()) return false;
        if (custom && editing && selected < 3) changeValue(selected, up ? -1 : 1);
        else {
            int next = Math.max(0, Math.min(custom ? 3 : presets.length, selected + (up ? -1 : 1)));
            if (next != selected) {
                selected = next; refreshSelection();
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        return true;
    }

    public boolean handleSingle() {
        if (!isEnabled()) return false;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (!custom) {
            if (selected == presets.length) showCustom();
            else host.onStart(MINUTES[selected] * 60_000L);
        } else if (selected == 3) startCustom();
        else { editing = !editing; refreshSelection(); }
        return true;
    }

    private void showCustom() {
        custom = true; editing = false; selected = 1;
        updateMode();
    }

    private void selectField(int index) {
        selected = index; editing = true; refreshSelection();
    }

    private void changeValue(int index, int delta) {
        int next = Math.max(0, Math.min(index == 0 ? 99 : 59, duration[index] + delta));
        if (next != duration[index]) {
            duration[index] = next;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            refreshSelection();
        }
    }

    private long durationMillis() {
        return (duration[0] * 3600L + duration[1] * 60L + duration[2]) * 1000L;
    }

    private void startCustom() {
        long value = durationMillis();
        if (value > 0) host.onStart(value);
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        view.setTypeface(RabbitTypography.regular(getContext()));
        view.setGravity(Gravity.CENTER_VERTICAL); view.setIncludeFontPadding(false);
        return view;
    }

    private TextView control(String text, String description, final Runnable action) {
        TextView view = label(text, 32, WHITE);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setFocusable(true);
        view.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View view) {
                if (!TimerSetupView.this.isEnabled()) return;
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                action.run();
            }
        });
        return view;
    }

    private void updateMode() {
        for (TextView preset : presets) preset.setVisibility(custom ? GONE : VISIBLE);
        customButton.setVisibility(custom ? GONE : VISIBLE);
        for (int i = 0; i < fields.length; i++) {
            fields[i].setVisibility(custom ? VISIBLE : GONE);
            plus[i].setVisibility(custom ? VISIBLE : GONE);
            minus[i].setVisibility(custom ? VISIBLE : GONE);
        }
        start.setVisibility(custom ? VISIBLE : GONE);
        refreshSelection(); requestLayout();
    }

    private void refreshSelection() {
        for (int i = 0; i < presets.length; i++) {
            presets[i].setTextColor(!custom && selected == i ? ACCENT : WHITE);
            presets[i].setSelected(!custom && selected == i);
        }
        customButton.setTextColor(selected == presets.length ? ACCENT : WHITE);
        for (DurationField field : fields) { field.updateDescription(); field.invalidate(); }
        start.setTextColor(selected == 3 ? ACCENT : WHITE);
        start.setSelected(custom && selected == 3);
        start.setEnabled(durationMillis() > 0);
        start.setAlpha(durationMillis() > 0 ? 1f : .35f);
        hint.setText(custom ? (editing ? "wheel to adjust · press to confirm" : "select a field to adjust") : "");
        invalidate();
    }

    private void measure(View view, int width, int height) {
        view.measure(MeasureSpec.makeMeasureSpec(Math.round(width * scale), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(height * scale), MeasureSpec.EXACTLY));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        scale = Math.min(width / 480f, height / 640f);
        originX = (width - 480 * scale) / 2f; originY = (height - 640 * scale) / 2f;
        measure(heading, 280, 68); measure(customButton, 64, 64); measure(hint, 432, 50);
        for (TextView preset : presets) measure(preset, 384, 58);
        for (int i = 0; i < fields.length; i++) {
            measure(fields[i], 112, 122); measure(plus[i], 112, 58); measure(minus[i], 112, 58);
        }
        measure(start, 384, 66);
    }

    private void place(View view, int x, int y) {
        int left = Math.round(originX + x * scale), top = Math.round(originY + y * scale);
        view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        place(heading, 94, 105); place(customButton, 368, 108);
        for (int i = 0; i < presets.length; i++) place(presets[i], 48, 198 + i * 58);
        for (int i = 0; i < fields.length; i++) {
            place(plus[i], 48 + i * 136, 209); place(fields[i], 48 + i * 136, 269);
            place(minus[i], 48 + i * 136, 392);
        }
        place(start, 48, 472); place(hint, 24, 554);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save(); canvas.translate(originX, originY); canvas.scale(scale, scale);
        paint.setColor(ACCENT); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3.5f);
        canvas.drawCircle(64, 139, 14, paint); canvas.drawLine(64, 139, 70, 132, paint);
        canvas.drawLine(60, 119, 68, 119, paint); canvas.drawLine(64, 119, 64, 125, paint);
        canvas.drawLine(53, 122, 50, 126, paint); canvas.drawLine(75, 122, 78, 126, paint);
        canvas.restoreToCount(save);
    }

    private final class DurationField extends View {
        private final int index;
        private final String unit;
        private final Paint type = new Paint(Paint.ANTI_ALIAS_FLAG);
        DurationField(final int index, String unit) {
            super(TimerSetupView.this.getContext()); this.index = index; this.unit = unit;
            type.setTypeface(RabbitTypography.regular(getContext())); type.setTextAlign(Paint.Align.CENTER);
            type.setFontFeatureSettings("tnum");
            setFocusable(true); setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View view) { if (TimerSetupView.this.isEnabled()) selectField(index); }
            });
        }
        void updateDescription() { setContentDescription(unit + ", " + duration[index]); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = getWidth() / 112f;
            type.setColor(selected == index ? ACCENT : WHITE); type.setTextSize(62 * s);
            canvas.drawText((duration[index] < 10 ? "0" : "") + duration[index], getWidth() / 2f, 68 * s, type);
            type.setTextSize(21 * s); canvas.drawText(unit, getWidth() / 2f, 106 * s, type);
            if (editing && selected == index) {
                type.setStrokeWidth(3 * s); canvas.drawLine(15 * s, 81 * s, 97 * s, 81 * s, type);
            }
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.NumberPicker");
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                    0, index == 0 ? 99 : 59, duration[index]));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (!TimerSetupView.this.isEnabled() || !isShown()) return false;
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                selectField(index); changeValue(index, action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1); return true;
            }
            return super.performAccessibilityAction(action, args);
        }
    }
}
