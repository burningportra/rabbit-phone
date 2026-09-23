package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/** Native language-pair setup. Translation itself remains the host's explicit action. */
public final class TranslatorSetupView extends ViewGroup implements HardwarePage {
    public interface Host { void onContinue(String sourceCode, String targetCode); }

    private static final int ACCENT = 0xff02f719;
    private static final int WHITE = 0xfff5efe1;
    private static final String PREFS = "translator_setup";
    private static final String SOURCE = "source", TARGET = "target";
    private static final int SETUP = 0, PICK_SOURCE = 1, PICK_TARGET = 2;
    private static final LinkedHashMap<String, String> LANGUAGES = languages();

    private final Host host;
    private final SharedPreferences preferences;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LanguageField sourceField;
    private final LanguageField targetField;
    private final ContinueControl continueControl;
    private final ScrollView pickerScroll;
    private final LinearLayout pickerRows;
    private LanguageOption[] options = new LanguageOption[0];
    private String sourceCode;
    private String targetCode;
    private int mode = SETUP;
    private int selected;
    private int pickerOrigin;
    private int pickerGeneration;
    private boolean showWheelFocus;
    private float scale = 1f, originX, originY;

    public TranslatorSetupView(Context context, Host host) {
        super(context);
        if (host == null) throw new IllegalArgumentException("Translator host is required");
        this.host = host;
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sourceCode = validCode(preferences.getString(SOURCE, "en")) ? preferences.getString(SOURCE, "en") : "en";
        targetCode = validCode(preferences.getString(TARGET, "es")) ? preferences.getString(TARGET, "es") : "es";
        setBackgroundColor(Color.BLACK);
        setWillNotDraw(false);
        setFocusable(true);
        setFocusableInTouchMode(true);

        sourceField = new LanguageField("language a", PICK_SOURCE);
        targetField = new LanguageField("language b", PICK_TARGET);
        continueControl = new ContinueControl();
        addView(sourceField);
        addView(targetField);
        addView(continueControl);

        pickerRows = new LinearLayout(context);
        pickerRows.setOrientation(LinearLayout.VERTICAL);
        pickerScroll = new ScrollView(context);
        pickerScroll.setFillViewport(true);
        pickerScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pickerScroll.addView(pickerRows, new ScrollView.LayoutParams(-1, -2));
        addView(pickerScroll);
        updateMode();
    }

    @Override public View getView() { return this; }

    @Override public boolean handleWheel(boolean up) {
        if (!interactive()) return false;
        showWheelFocus = true;
        if (mode == SETUP) {
            int next = Math.max(0, Math.min(2, selected + (up ? -1 : 1)));
            if (next != selected) {
                selected = next;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                refreshSelection();
            }
        } else if (options.length > 0) {
            int next = Math.max(0, Math.min(options.length - 1, selected + (up ? -1 : 1)));
            if (next != selected) {
                selected = next;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                refreshSelection();
                scrollToSelection();
            }
        }
        refreshSelection();
        return true;
    }

    @Override public boolean handleSingle() {
        if (!interactive()) return false;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (mode == SETUP) {
            if (selected == 0) showPicker(PICK_SOURCE);
            else if (selected == 1) showPicker(PICK_TARGET);
            else host.onContinue(sourceCode, targetCode);
        } else if (options.length > 0) {
            LanguageOption option = options[selected];
            choose(option.code, option.pickMode, option.generation);
        }
        return true;
    }

    @Override public boolean handleBack() {
        if (!interactive()) return false;
        if (mode == SETUP) return false;
        showSetup(pickerOrigin);
        return true;
    }

    private boolean interactive() { return isEnabled() && isShown() && hasWindowFocus(); }

    private void showPicker(int nextMode) {
        if (!interactive() || mode != SETUP) return;
        pickerOrigin = nextMode == PICK_SOURCE ? 0 : 1;
        mode = nextMode;
        selected = indexFor(nextMode == PICK_SOURCE ? sourceCode : targetCode);
        showWheelFocus = true;
        rebuildPicker(nextMode);
        updateMode();
        scrollToSelection();
    }

    private void choose(String code, int expectedMode, int expectedGeneration) {
        if (!interactive() || mode != expectedMode || pickerGeneration != expectedGeneration || !validCode(code)) return;
        if (expectedMode == PICK_SOURCE) sourceCode = code;
        else targetCode = code;
        preferences.edit().putString(SOURCE, sourceCode).putString(TARGET, targetCode).apply();
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        showSetup(pickerOrigin);
    }

    private void showSetup(int focus) {
        mode = SETUP;
        selected = Math.max(0, Math.min(2, focus));
        showWheelFocus = false;
        pickerGeneration++;
        updateMode();
    }

    private void rebuildPicker(int pickMode) {
        pickerGeneration++;
        pickerRows.removeAllViews();
        options = new LanguageOption[LANGUAGES.size()];
        int index = 0;
        for (Map.Entry<String, String> entry : LANGUAGES.entrySet()) {
            LanguageOption option = new LanguageOption(index, entry.getKey(), entry.getValue(), pickMode, pickerGeneration);
            options[index] = option;
            pickerRows.addView(option, new LinearLayout.LayoutParams(-1, dp(56)));
            index++;
        }
    }

    private void updateMode() {
        boolean setup = mode == SETUP;
        sourceField.setVisibility(setup ? VISIBLE : GONE);
        targetField.setVisibility(setup ? VISIBLE : GONE);
        continueControl.setVisibility(setup ? VISIBLE : GONE);
        pickerScroll.setVisibility(setup ? GONE : VISIBLE);
        refreshSelection();
        requestLayout();
    }

    private void refreshSelection() {
        sourceField.invalidate();
        targetField.invalidate();
        continueControl.updateDescription();
        continueControl.invalidate();
        for (LanguageOption option : options) option.refresh();
    }

    private void scrollToSelection() {
        final int expectedMode = mode;
        final int expectedGeneration = pickerGeneration;
        final LanguageOption option = options[selected];
        pickerScroll.post(new Runnable() {
            @Override public void run() {
                if (mode != expectedMode || pickerGeneration != expectedGeneration
                        || pickerScroll.getParent() != TranslatorSetupView.this
                        || !option.isAttachedToWindow()) return;
                pickerScroll.smoothScrollTo(0, Math.max(0, option.getTop() - dp(8)));
            }
        });
    }

    private String language(String code) { return LANGUAGES.get(code); }

    private int indexFor(String code) {
        int index = 0;
        for (String value : LANGUAGES.keySet()) {
            if (value.equals(code)) return index;
            index++;
        }
        return 0;
    }

    private static boolean validCode(String code) { return code != null && LANGUAGES.containsKey(code); }

    private static LinkedHashMap<String, String> languages() {
        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        values.put("en", "english"); values.put("es", "spanish"); values.put("fr", "french");
        values.put("de", "german"); values.put("it", "italian"); values.put("pt", "portuguese");
        values.put("ja", "japanese"); values.put("ko", "korean"); values.put("zh-CN", "chinese");
        values.put("ar", "arabic"); values.put("hi", "hindi"); values.put("nl", "dutch");
        values.put("ru", "russian"); values.put("uk", "ukrainian");
        return values;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void measure(View view, int width, int height) {
        view.measure(MeasureSpec.makeMeasureSpec(Math.round(width * scale), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(height * scale), MeasureSpec.EXACTLY));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        scale = Math.min(width / 480f, height / 640f);
        originX = (width - 480 * scale) / 2f;
        originY = (height - 640 * scale) / 2f;
        measure(sourceField, 384, 108);
        measure(targetField, 384, 108);
        measure(continueControl, 384, 66);
        pickerScroll.measure(MeasureSpec.makeMeasureSpec(Math.round(384 * scale), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(408 * scale), MeasureSpec.EXACTLY));
        for (LanguageOption option : options) option.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * scale);
    }

    private void place(View view, int x, int y) {
        int left = Math.round(originX + x * scale), top = Math.round(originY + y * scale);
        view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        place(sourceField, 48, 202);
        place(targetField, 48, 330);
        place(continueControl, 48, 504);
        place(pickerScroll, 48, 164);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        canvas.translate(originX, originY);
        canvas.scale(scale, scale);
        int glyph = canvas.save();
        canvas.translate(48, 128); canvas.scale(1.2f, 1.2f);
        paint.setColor(ACCENT); paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(2, 10, 14, 29, paint); canvas.drawRect(17, 3, 30, 25, paint);
        paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f);
        canvas.drawLine(5, 24, 8, 15, paint); canvas.drawLine(8, 15, 11, 24, paint);
        canvas.drawLine(6, 21, 10, 21, paint);
        canvas.drawLine(20, 10, 27, 10, paint); canvas.drawLine(23.5f, 7, 23.5f, 10, paint);
        canvas.drawLine(26, 10, 20, 19, paint); canvas.drawLine(21, 12, 27, 19, paint);
        canvas.restoreToCount(glyph);
        paint.setColor(ACCENT); paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(RabbitTypography.regular(getContext()));
        paint.setTextAlign(Paint.Align.LEFT); paint.setTextSize(44);
        canvas.drawText(mode == SETUP ? "translator" : "choose language", 104, 160, paint);
        canvas.restoreToCount(save);
    }

    private void chevron(Canvas canvas, Paint paint, float x, float y, float scale, boolean focused) {
        paint.setColor(focused ? ACCENT : 0xff514c55);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * scale);
        canvas.drawLine(x - 5 * scale, y - 6 * scale, x, y, paint);
        canvas.drawLine(x, y, x - 5 * scale, y + 6 * scale, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private final class LanguageField extends View {
        private final String label;
        private final int pickMode;
        private final Paint type = new Paint(Paint.ANTI_ALIAS_FLAG);

        LanguageField(String label, final int pickMode) {
            super(TranslatorSetupView.this.getContext());
            this.label = label; this.pickMode = pickMode;
            type.setTypeface(RabbitTypography.regular(getContext()));
            setFocusable(true); setClickable(true); setMinimumHeight(dp(44));
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View view) { showPicker(pickMode); }
            });
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = getWidth() / 384f;
            boolean focused = mode == SETUP && showWheelFocus
                    && selected == (pickMode == PICK_SOURCE ? 0 : 1);
            type.setColor(ACCENT); type.setTextSize(24 * s); type.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(label, 0, 28 * s, type);
            type.setColor(focused ? ACCENT : WHITE); type.setTextSize(36 * s);
            canvas.drawText(language(pickMode == PICK_SOURCE ? sourceCode : targetCode), 0, 88 * s, type);
            chevron(canvas, type, getWidth() - 6 * s, 77 * s, s, focused);
            if (focused) {
                type.setStrokeWidth(2 * s);
                canvas.drawLine(0, 94 * s, getWidth(), 94 * s, type);
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            info.setContentDescription(label + ", " + language(pickMode == PICK_SOURCE ? sourceCode : targetCode));
        }
    }

    private final class ContinueControl extends View {
        private final Paint type = new Paint(Paint.ANTI_ALIAS_FLAG);

        ContinueControl() {
            super(TranslatorSetupView.this.getContext());
            type.setTypeface(RabbitTypography.regular(getContext()));
            setFocusable(true); setClickable(true); setMinimumHeight(dp(44));
            updateDescription();
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View view) {
                    if (!interactive() || mode != SETUP) return;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    host.onContinue(sourceCode, targetCode);
                }
            });
        }

        void updateDescription() {
            setContentDescription("Continue with " + language(sourceCode) + " and " + language(targetCode));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = getWidth() / 384f;
            boolean focused = mode == SETUP && showWheelFocus && selected == 2;
            type.setColor(focused ? ACCENT : WHITE); type.setTextSize(36 * s); type.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("continue", 0, 42 * s, type);
            chevron(canvas, type, getWidth() - 6 * s, 31 * s, s, focused);
            if (focused) {
                type.setStrokeWidth(2 * s);
                canvas.drawLine(0, 58 * s, getWidth(), 58 * s, type);
            }
        }
    }

    private final class LanguageOption extends TextView {
        final int index;
        final String code;
        final String name;
        final int pickMode;
        final int generation;

        LanguageOption(final int index, final String code, final String name,
                final int pickMode, final int generation) {
            super(TranslatorSetupView.this.getContext());
            this.index = index; this.code = code; this.name = name;
            this.pickMode = pickMode; this.generation = generation;
            setText(name); setTextColor(WHITE); setTypeface(RabbitTypography.regular(getContext()));
            setGravity(Gravity.CENTER_VERTICAL); setPadding(dp(8), 0, dp(8), 0);
            setIncludeFontPadding(false); setFocusable(true); setClickable(true); setMinimumHeight(dp(44));
            setContentDescription(name);
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View view) {
                    if (!interactive() || mode != pickMode || pickerGeneration != generation
                            || !isShown() || !isAttachedToWindow() || getParent() != pickerRows) return;
                    choose(code, pickMode, generation);
                }
            });
        }

        void refresh() {
            boolean focused = mode == pickMode && pickerGeneration == generation && selected == index;
            setTextColor(focused ? ACCENT : WHITE);
            setSelected(focused);
        }
    }
}
