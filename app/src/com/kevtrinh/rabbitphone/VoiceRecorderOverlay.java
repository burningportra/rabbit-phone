package com.kevtrinh.rabbitphone;

import android.Manifest;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Same-window recorder and voice-note library shared by Home and Camera. */
public final class VoiceRecorderOverlay implements VoiceNotes.Listener {
    public interface Host {
        void onRecorderActiveChanged(boolean active);
        void onRecorderMessage(String message);
        default void onRecorderClosed() { }
    }

    private static final int MICROPHONE_PERMISSION_REQUEST = 4071;
    private static final int BG = Color.rgb(10, 10, 9);
    private static final int WHITE = Color.rgb(245, 239, 225);
    private static final int MUTED = Color.rgb(161, 154, 140);
    private static final int ORANGE = Color.rgb(255, 90, 31);
    private static final int CARD = Color.rgb(27, 27, 24);
    private static final int DARK_INK = Color.rgb(22, 18, 14);

    private final Activity activity;
    private final Host host;
    private final VoiceNotes notes;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<View> actionViews = new ArrayList<View>();
    private final ArrayList<Runnable> actions = new ArrayList<Runnable>();
    private final Runnable meterTick = new Runnable() {
        @Override public void run() {
            if (!notes.isRecording() || root == null) return;
            long elapsed = notes.elapsedMillis();
            if (timer != null) timer.setText(formatDuration(elapsed));
            if (waveform != null) waveform.push(notes.maxAmplitude());
            main.postDelayed(this, 50L);
        }
    };
    private final Runnable volumeTick = new Runnable() {
        @Override public void run() {
            if (root == null || volumeLabel == null) return;
            updateVolumeLabel();
            main.postDelayed(this, 500L);
        }
    };

    private FrameLayout root;
    private LinearLayout page;
    private TextView timer;
    private TextView volumeLabel;
    private String volumeError;
    private WaveformView waveform;
    private ScrollView actionScroll;
    private File savedFile;
    private long savedDuration;
    private int selectedAction;
    private boolean ownsKeepScreenOn;
    private boolean showingSaved;

    public VoiceRecorderOverlay(Activity activity, Host host) {
        if (activity == null || host == null) {
            throw new IllegalArgumentException("Recorder activity and host are required");
        }
        this.activity = activity;
        this.host = host;
        notes = new VoiceNotes(activity, this);
        audio = activity.getSystemService(AudioManager.class);
    }

    public VoiceNotes voiceNotes() { return notes; }

    public boolean isVisible() { return root != null; }

    /** Opens without microphone access while an assistant hold is being handed off. */
    public void showReady() {
        if (notes.isRecording()) return;
        attach();
        beginPage();
        if (page == null) return;
        page.addView(text("Voice recorder", 32, WHITE, Typeface.NORMAL),
                new LinearLayout.LayoutParams(-1, dp(52)));
        TextView hint = text("Hold the side button to record", 29, WHITE, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(hint, new LinearLayout.LayoutParams(-1, 0, 1f));
        addAction("Notes", false, new Runnable() {
            @Override public void run() { showLibrary(); }
        });
        addAction("Done", false, new Runnable() {
            @Override public void run() { closeFromUser(); }
        });
        applySelection(false);
    }

    /** Begins from the deliberate 450 ms hardware hold. Permission never resumes recording. */
    public boolean beginHold() {
        if (notes.isRecording()) return false;
        if (!notes.hasMicrophonePermission()) {
            host.onRecorderMessage("Allow microphone access, then hold again to record");
            activity.requestPermissions(
                    new String[] { Manifest.permission.RECORD_AUDIO },
                    MICROPHONE_PERMISSION_REQUEST);
            return false;
        }
        // Playback callbacks are synchronous; finish them before constructing the
        // recording screen so they cannot replace its meter and timer.
        notes.stopPlayback();
        attach();
        renderRecording();
        if (!notes.start()) return false;
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        return true;
    }

    public void finishHold() {
        notes.stop();
    }

    public void showLibrary() {
        if (notes.isRecording()) return;
        notes.stopPlayback();
        attach();
        renderLibrary();
    }

    public boolean handleSingle() {
        if (!isVisible()) return false;
        if (selectedAction >= 0 && selectedAction < actions.size()) {
            root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            actions.get(selectedAction).run();
        }
        return true;
    }

    /** Returns true when an overlay was dismissed before the activity runs its normal double action. */
    public boolean dismissForDouble() {
        if (!isVisible()) return false;
        abortAndDismiss();
        return true;
    }

    public boolean handleWheel(boolean up) {
        if (!isVisible()) return false;
        if (actions.isEmpty()) return true;
        int next = Math.max(0, Math.min(actions.size() - 1,
                selectedAction + (up ? -1 : 1)));
        if (next != selectedAction) {
            selectedAction = next;
            applySelection(true);
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        return true;
    }

    /** Used for pause, focus loss, helper disconnect, lock, and destruction. */
    public void abortAndDismiss() {
        main.removeCallbacks(meterTick);
        main.removeCallbacks(volumeTick);
        notes.cancel();
        notes.stopPlayback();
        dismiss();
    }

    public void release() {
        abortAndDismiss();
        notes.release();
    }

    @Override public void onRecordingChanged(boolean recording) {
        host.onRecorderActiveChanged(recording);
        if (recording) {
            int flags = activity.getWindow().getAttributes().flags;
            if ((flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                ownsKeepScreenOn = true;
            }
            main.removeCallbacks(meterTick);
            main.post(meterTick);
        } else {
            main.removeCallbacks(meterTick);
            clearOwnedScreenFlag();
        }
    }

    @Override public void onResult(VoiceNotes.Result result) {
        if (result.isSaved()) {
            savedFile = result.file;
            savedDuration = result.durationMillis;
            attach();
            renderSaved(result.message);
        } else if (result.status == VoiceNotes.Status.CANCELED) {
            if (isVisible()) renderMessage(result.message);
        } else {
            attach();
            renderMessage(result.message);
        }
    }

    @Override public void onPlaybackChanged(File note, boolean playing) {
        if (!isVisible()) return;
        if (savedFile != null && savedFile.equals(note)) {
            renderSaved(playing ? "Playing voice note" : "Voice note saved");
        } else {
            renderLibrary();
        }
    }

    private void attach() {
        if (root != null) return;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            host.onRecorderMessage("Recorder screen isn't available");
            return;
        }
        root = new FrameLayout(activity);
        root.setBackgroundColor(BG);
        root.setClickable(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setContentDescription("Voice recorder");
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) { return true; }
        });
        ((ViewGroup) content).addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.bringToFront();
        root.requestFocus();
    }

    private void beginPage() {
        if (root == null) return;
        showingSaved = false;
        main.removeCallbacks(volumeTick);
        volumeLabel = null;
        volumeError = null;
        root.removeAllViews();
        actionViews.clear();
        actions.clear();
        selectedAction = 0;
        timer = null;
        waveform = null;
        actionScroll = null;
        page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(14));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void renderRecording() {
        beginPage();
        if (page == null) return;
        TextView title = text("Voice recorder", 24, WHITE, Typeface.NORMAL);
        page.addView(title, new LinearLayout.LayoutParams(-1, dp(36)));

        timer = text("00:00", 62, WHITE, Typeface.NORMAL);
        timer.setGravity(Gravity.CENTER);
        timer.setFontFeatureSettings("tnum");
        timer.setLetterSpacing(0.03f);
        page.addView(timer, new LinearLayout.LayoutParams(-1, dp(86)));

        waveform = new WaveformView(activity);
        page.addView(waveform, new LinearLayout.LayoutParams(-1, dp(86)));

        TextView hint = text("Release button to save", 17, ORANGE, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        page.addView(hint, new LinearLayout.LayoutParams(-1, dp(44)));

        View spacer = new View(activity);
        page.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        addAction("Cancel", false, new Runnable() {
            @Override public void run() {
                notes.cancel();
                closeFromUser();
            }
        });
        addAction("Stop & save", true, new Runnable() {
            @Override public void run() { notes.stop(); }
        });
        applySelection(false);
    }

    private void renderSaved(String message) {
        boolean preserveSelection = showingSaved;
        int previousSelection = selectedAction;
        beginPage();
        if (page == null) return;
        TextView eyebrow = text("SAVED", 13, ORANGE, Typeface.NORMAL);
        eyebrow.setLetterSpacing(0.12f);
        page.addView(eyebrow, new LinearLayout.LayoutParams(-1, dp(28)));
        TextView heading = text("Voice note saved", 34, WHITE, Typeface.NORMAL);
        page.addView(heading, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView duration = text(formatDuration(savedDuration), 56, WHITE, Typeface.NORMAL);
        duration.setFontFeatureSettings("tnum");
        duration.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(duration, new LinearLayout.LayoutParams(-1, dp(74)));
        TextView detail = text(message, 15, MUTED, Typeface.NORMAL);
        page.addView(detail, new LinearLayout.LayoutParams(-1, dp(44)));
        View spacer = new View(activity);
        page.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));

        boolean playing = notes.isPlaying(savedFile);
        addAction(playing ? "Stop" : "Play", true, new Runnable() {
            @Override public void run() {
                if (notes.isPlaying(savedFile)) notes.stopPlayback();
                else notes.play(savedFile);
            }
        });
        addAction("Notes", false, new Runnable() {
            @Override public void run() { showLibrary(); }
        });
        addAction("Done", false, new Runnable() {
            @Override public void run() { closeFromUser(); }
        });
        addVolumeControls();
        showingSaved = true;
        if (preserveSelection) selectedAction = Math.max(0, Math.min(actions.size() - 1, previousSelection));
        applySelection(false);
    }

    private void renderMessage(String message) {
        beginPage();
        if (page == null) return;
        TextView title = text("Voice recorder", 24, WHITE, Typeface.NORMAL);
        page.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView body = text(message, 29, WHITE, Typeface.NORMAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView detail = text("No recording was saved.", 15, MUTED, Typeface.NORMAL);
        page.addView(detail, new LinearLayout.LayoutParams(-1, dp(54)));
        addAction("Notes", false, new Runnable() {
            @Override public void run() { showLibrary(); }
        });
        addAction("Done", true, new Runnable() {
            @Override public void run() { closeFromUser(); }
        });
        selectedAction = Math.max(0, actions.size() - 1);
        applySelection(false);
    }

    private void renderLibrary() {
        boolean preserveSelection = actionScroll != null;
        int previousSelection = selectedAction;
        beginPage();
        if (page == null) return;
        savedFile = null;
        savedDuration = 0L;
        TextView title = text("Voice notes", 32, WHITE, Typeface.NORMAL);
        page.addView(title, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView subtitle = text("Saved privately on this Rabbit", 14, MUTED, Typeface.NORMAL);
        page.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(38)));

        actionScroll = new ScrollView(activity);
        actionScroll.setFillViewport(true);
        actionScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        List<File> files = notes.listNotes();
        if (files.isEmpty()) {
            TextView empty = text("Hold the side button to record your first voice note.",
                    20, WHITE, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            rows.addView(empty, new LinearLayout.LayoutParams(-1, dp(160)));
        } else {
            final SimpleDateFormat format = new SimpleDateFormat(
                    "EEE, MMM d  ·  h:mm a", Locale.getDefault());
            for (final File file : files) {
                addLibraryAction(rows,
                        format.format(new Date(file.lastModified())),
                        notes.isPlaying(file) ? "Playing · select to stop" : "Select to play",
                        new Runnable() {
                            @Override public void run() {
                                if (notes.isPlaying(file)) notes.stopPlayback();
                                else notes.play(file);
                            }
                        });
            }
        }
        actionScroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        page.addView(actionScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        addAction("Done", false, new Runnable() {
            @Override public void run() { closeFromUser(); }
        });
        addVolumeControls();
        if (preserveSelection) {
            selectedAction = Math.max(0, Math.min(actions.size() - 1, previousSelection));
        }
        applySelection(false);
    }

    /** In-window controls: opening Android's volume panel would interrupt playback. */
    private void addVolumeControls() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(8);
        page.addView(row, params);
        addVolumeButton(row, "−", "Lower media volume", AudioManager.ADJUST_LOWER);
        volumeLabel = text("", 15, MUTED, Typeface.NORMAL);
        volumeLabel.setGravity(Gravity.CENTER);
        volumeLabel.setFontFeatureSettings("tnum");
        row.addView(volumeLabel, new LinearLayout.LayoutParams(0, -1, 1f));
        addVolumeButton(row, "+", "Raise media volume", AudioManager.ADJUST_RAISE);
        main.removeCallbacks(volumeTick);
        main.post(volumeTick);
    }

    private void addVolumeButton(LinearLayout row, String label, String description,
            final int direction) {
        Button button = new Button(activity);
        button.setText(label);
        button.setContentDescription(description);
        button.setTextSize(24);
        button.setTypeface(RabbitTypography.regular(activity));
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        row.addView(button, new LinearLayout.LayoutParams(dp(54), -1));
        registerAction(button, new Runnable() {
            @Override public void run() {
                volumeError = null;
                try {
                    if (audio == null || audio.isVolumeFixed()) {
                        volumeError = "Use output volume controls";
                    } else {
                        // Explicit user action only. Play never raises or unmutes volume.
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
                    }
                } catch (SecurityException unavailable) {
                    volumeError = "Volume change unavailable";
                }
                updateVolumeLabel();
            }
        });
    }

    private void updateVolumeLabel() {
        if (volumeLabel == null) return;
        String label;
        boolean muted = false;
        if (volumeError != null) label = volumeError;
        else if (audio == null) label = "Media volume unavailable";
        else {
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            muted = current == 0 || audio.isStreamMute(AudioManager.STREAM_MUSIC);
            label = muted ? "Media volume off" : "Media volume "
                    + Math.round(current * 100f / Math.max(1, maximum)) + "%";
        }
        if (!label.contentEquals(volumeLabel.getText())) volumeLabel.setText(label);
        volumeLabel.setTextColor(muted || volumeError != null ? ORANGE : MUTED);
    }

    private void addLibraryAction(LinearLayout parent, String label, String detail,
            Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(7), dp(15), dp(7));
        TextView primary = text(label, 18, WHITE, Typeface.NORMAL);
        TextView secondary = text(detail, 13, MUTED, Typeface.NORMAL);
        row.addView(primary);
        row.addView(secondary);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(66));
        params.bottomMargin = dp(6);
        parent.addView(row, params);
        registerAction(row, action);
    }

    private void addAction(String label, boolean primary, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(17);
        button.setTypeface(RabbitTypography.regular(activity));
        button.setTextColor(primary ? DARK_INK : WHITE);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(shape(primary ? ORANGE : CARD, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.topMargin = dp(6);
        page.addView(button, params);
        registerAction(button, action);
    }

    private void registerAction(final View view, final Runnable action) {
        final int index = actions.size();
        actions.add(action);
        actionViews.add(view);
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View target, boolean focused) {
                if (focused) {
                    selectedAction = index;
                    applySelection(false);
                }
            }
        });
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View target, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    selectedAction = index;
                    applySelection(false);
                }
                return false;
            }
        });
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View target) { action.run(); }
        });
    }

    private void applySelection(boolean requestFocus) {
        for (int i = 0; i < actionViews.size(); i++) {
            View view = actionViews.get(i);
            boolean selected = i == selectedAction;
            if (view instanceof Button) {
                view.setBackground(shape(selected ? ORANGE : CARD, 14));
                ((Button) view).setTextColor(selected ? DARK_INK : WHITE);
            } else if (view instanceof LinearLayout) {
                view.setBackground(shape(selected ? ORANGE : CARD, 14));
                LinearLayout row = (LinearLayout) view;
                ((TextView) row.getChildAt(0)).setTextColor(selected ? DARK_INK : WHITE);
                ((TextView) row.getChildAt(1)).setTextColor(selected ? DARK_INK : MUTED);
            }
        }
        if (selectedAction < 0 || selectedAction >= actionViews.size()) return;
        final View selected = actionViews.get(selectedAction);
        if (requestFocus) selected.requestFocus();
        if (actionScroll != null) {
            final ScrollView scroll = actionScroll;
            scroll.post(new Runnable() {
                @Override public void run() {
                    if (actionScroll == scroll && selected.isAttachedToWindow()
                            && selected.getParent() == scroll.getChildAt(0)) {
                        scroll.smoothScrollTo(0, Math.max(0, selected.getTop() - dp(8)));
                    }
                }
            });
        }
    }

    private void closeFromUser() {
        dismiss();
        host.onRecorderClosed();
    }

    private void dismiss() {
        main.removeCallbacks(meterTick);
        main.removeCallbacks(volumeTick);
        notes.stopPlayback();
        clearOwnedScreenFlag();
        if (root != null) {
            ViewGroup parent = (ViewGroup) root.getParent();
            if (parent != null) parent.removeView(root);
        }
        root = null;
        showingSaved = false;
        page = null;
        actionViews.clear();
        actions.clear();
        timer = null;
        volumeLabel = null;
        volumeError = null;
        waveform = null;
        actionScroll = null;
        savedFile = null;
        savedDuration = 0L;
    }

    private void clearOwnedScreenFlag() {
        if (ownsKeepScreenOn) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            ownsKeepScreenOn = false;
        }
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(RabbitTypography.regular(activity));
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable shape(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class WaveformView extends View {
        private static final int BAR_COUNT = 29;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] levels = new float[BAR_COUNT];

        WaveformView(Activity activity) {
            super(activity);
            paint.setColor(ORANGE);
            setContentDescription("Live microphone level");
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void push(int amplitude) {
            System.arraycopy(levels, 1, levels, 0, BAR_COUNT - 1);
            float normalized = Math.min(1f, Math.max(0f, amplitude / 32767f));
            levels[BAR_COUNT - 1] = (float) Math.sqrt(normalized);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float gap = getResources().getDisplayMetrics().density * 3f;
            float width = Math.max(2f,
                    (getWidth() - gap * (BAR_COUNT - 1)) / BAR_COUNT);
            float center = getHeight() / 2f;
            float maxHeight = getHeight() * 0.82f;
            float minHeight = getResources().getDisplayMetrics().density * 2f;
            for (int i = 0; i < BAR_COUNT; i++) {
                float height = Math.max(minHeight, levels[i] * maxHeight);
                float left = i * (width + gap);
                canvas.drawRoundRect(left, center - height / 2f,
                        left + width, center + height / 2f,
                        width / 2f, width / 2f, paint);
            }
        }
    }
}
