package com.kevtrinh.rabbitphone;

import android.Manifest;
import android.animation.ValueAnimator;
import android.animation.TimeInterpolator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.IdentityHashMap;
import java.util.Map;

/** Same-window recorder and voice-note library shared by Home and Camera. */
public final class VoiceRecorderOverlay implements VoiceNotes.Listener {
    public interface Host {
        void onRecorderActiveChanged(boolean active);
        void onRecorderMessage(String message);
        default void onRecorderClosed() { }
    }

    private static final int MICROPHONE_PERMISSION_REQUEST = 4071;
    private static final int BG = Color.BLACK;
    private static final int WHITE = Color.rgb(245, 239, 225);
    private static final int MUTED = Color.rgb(161, 154, 140);
    private static final int ORANGE = Color.rgb(255, 90, 31);
    private static final int CARD = Color.rgb(27, 27, 24);
    private static final int DARK_INK = Color.rgb(22, 18, 14);
    private static final int RECORD_RED = Color.rgb(255, 22, 69);
    private static final TimeInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);
    private enum PageMode { OTHER, LIBRARY, DETAIL, READY_FROM_LIBRARY }

    private final Activity activity;
    private final Host host;
    private final VoiceNotes notes;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<View> actionViews = new ArrayList<View>();
    private final ArrayList<Runnable> actions = new ArrayList<Runnable>();
    private final IdentityHashMap<View, Integer> siblingAccessibility = new IdentityHashMap<View, Integer>();
    private final Runnable meterTick = new Runnable() {
        @Override public void run() {
            if (!notes.isRecording() || root == null) return;
            long elapsed = notes.elapsedMillis();
            setTimer(elapsed);
            if (deck != null) deck.setState(TapeDeckView.Mode.RECORDING, elapsed,
                    VoiceNotes.MAX_DURATION_MS, notes.maxAmplitude());
            main.postDelayed(this, 50L);
        }
    };
    private final Runnable playbackTick = new Runnable() {
        @Override public void run() {
            if (root == null || (!showingSaved && pageMode != PageMode.DETAIL) || !notes.isPlaying(savedFile)) return;
            long position = notes.playbackPositionMillis();
            long duration = notes.playbackDurationMillis();
            setTimer(position);
            if (deck != null) deck.setState(TapeDeckView.Mode.PLAYING, position, duration, 0);
            main.postDelayed(this, 100L);
        }
    };
    private final Runnable volumeTick = new Runnable() {
        @Override public void run() {
            if (root == null || volumeLabel == null) return;
            updateVolumeLabel();
            updateLibraryHeader();
            main.postDelayed(this, 500L);
        }
    };

    private FrameLayout root;
    private LinearLayout page;
    private TextView timer;
    private TextView volumeLabel;
    private String volumeError;
    private TapeDeckView deck;
    private ScrollView actionScroll;
    private File savedFile;
    private long savedDuration;
    private int selectedAction;
    private boolean ownsKeepScreenOn;
    private boolean showingSaved;
    private PageMode pageMode = PageMode.OTHER;
    private boolean navigating;
    private int pageGeneration;
    private int librarySelection = 2;
    private int libraryScrollY;
    private TextView headerTime;
    private RecorderBattery headerBattery;

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
        boolean fromLibrary = pageMode == PageMode.LIBRARY || pageMode == PageMode.READY_FROM_LIBRARY;
        if (pageMode == PageMode.LIBRARY) rememberLibraryPosition();
        stopPlaybackForNavigation();
        attach();
        beginPage();
        if (page == null) return;
        pageMode = fromLibrary ? PageMode.READY_FROM_LIBRARY : PageMode.OTHER;
        addDeckHeader("ready", MUTED);
        addTimer(0, MUTED);
        addDeck(TapeDeckView.Mode.READY, 0, 0);
        addHint("Hold the side button to record", MUTED);
        addSpacer();
        LinearLayout transport = addTransportRow();
        addTransport(transport, "Notes", Glyph.NOTES, ORANGE, new Runnable() {
            @Override public void run() { showLibrary(); }
        });
        addTransport(transport, "Done", Glyph.DONE, ORANGE, new Runnable() {
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
        stopPlaybackForNavigation();
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
        stopPlaybackForNavigation();
        attach();
        renderLibrary();
    }

    /** Detail and the library's Plus destination return within this same overlay/window. */
    public boolean handleBack() {
        if (!isVisible() || (pageMode != PageMode.DETAIL && pageMode != PageMode.READY_FROM_LIBRARY)) return false;
        showLibrary();
        return true;
    }

    private void backFromUser() { if (!handleBack()) closeFromUser(); }

    private void stopPlaybackForNavigation() {
        navigating = true;
        try { notes.stopPlayback(); } finally { navigating = false; }
    }

    private void rememberLibraryPosition() {
        librarySelection = selectedAction;
        libraryScrollY = actionScroll == null ? 0 : actionScroll.getScrollY();
    }

    private void openDetail(File file) {
        if (pageMode != PageMode.LIBRARY || !isVisible()) return;
        rememberLibraryPosition();
        stopPlaybackForNavigation();
        savedFile = file;
        savedDuration = notes.durationMillis(file);
        renderDetail();
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
        main.removeCallbacks(playbackTick);
        main.removeCallbacks(volumeTick);
        notes.cancel();
        stopPlaybackForNavigation();
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
            stopDeck();
            clearOwnedScreenFlag();
        }
    }

    @Override public void onResult(VoiceNotes.Result result) {
        if (result.isSaved()) {
            librarySelection = 2; libraryScrollY = 0;
            savedFile = result.file;
            savedDuration = result.durationMillis;
            attach();
            renderSaved(result.message);
            root.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        } else if (result.status == VoiceNotes.Status.CANCELED) {
            if (isVisible()) renderMessage(result.message);
        } else {
            attach();
            renderMessage(result.message);
        }
    }

    @Override public void onPlaybackChanged(File note, boolean playing) {
        if (!isVisible() || navigating) return;
        if (pageMode == PageMode.DETAIL && savedFile != null && savedFile.equals(note)) {
            if (playing && notes.playbackDurationMillis() > 0) savedDuration = notes.playbackDurationMillis();
            renderDetail();
        } else if (savedFile != null && savedFile.equals(note)) {
            renderSaved(playing ? "Playing voice note" : "Voice note saved");
        } else {
            renderLibrary();
        }
    }

    private void attach() {
        if (root != null) return;
        ViewGroup content = NavigationSurface.content(activity);
        if (content == null) {
            host.onRecorderMessage("Recorder screen isn't available");
            return;
        }
        root = new FrameLayout(activity) {
            @Override protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                if (root == this) {
                    root = null; // Parent removal already owns detachment; do not remove it twice.
                    abortAndDismiss();
                }
            }
        };
        root.setBackgroundColor(BG);
        root.setClickable(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setContentDescription("Voice recorder");
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) { return true; }
        });
        content.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < content.getChildCount(); i++) {
            View sibling = content.getChildAt(i);
            if (sibling == root) continue;
            siblingAccessibility.put(sibling, sibling.getImportantForAccessibility());
            sibling.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        root.bringToFront();
        root.requestFocus();
    }

    private void beginPage() {
        if (root == null) return;
        pageGeneration++;
        main.removeCallbacks(playbackTick);
        stopDeck();
        for (View view : actionViews) view.animate().cancel();
        showingSaved = false;
        pageMode = PageMode.OTHER;
        headerTime = null; headerBattery = null;
        main.removeCallbacks(volumeTick);
        volumeLabel = null;
        volumeError = null;
        root.removeAllViews();
        actionViews.clear();
        actions.clear();
        selectedAction = 0;
        timer = null;
        deck = null;
        actionScroll = null;
        page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(26), dp(20), dp(14));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void renderRecording() {
        beginPage();
        if (page == null) return;
        addDeckHeader("recording", RECORD_RED);
        addTimer(0, RECORD_RED);
        addDeck(TapeDeckView.Mode.READY, 0, VoiceNotes.MAX_DURATION_MS);
        addHint("Release button to save", RECORD_RED);
        addSpacer();
        LinearLayout transport = addTransportRow();
        addTransport(transport, "Cancel", Glyph.CANCEL, RECORD_RED, new Runnable() {
            @Override public void run() {
                notes.cancel();
                closeFromUser();
            }
        });
        addTransport(transport, "Stop & save", Glyph.STOP, RECORD_RED, new Runnable() {
            @Override public void run() { notes.stop(); }
        });
        selectedAction = 1;
        applySelection(false);
    }

    private void renderSaved(String message) {
        boolean preserveSelection = showingSaved;
        int previousSelection = selectedAction;
        beginPage();
        if (page == null) return;
        boolean playing = notes.isPlaying(savedFile);
        addDeckHeader(playing ? "playing" : "saved", playing ? ORANGE : MUTED);
        addTimer(playing ? notes.playbackPositionMillis() : savedDuration, WHITE);
        addDeck(playing ? TapeDeckView.Mode.PLAYING : TapeDeckView.Mode.SAVED,
                playing ? notes.playbackPositionMillis() : savedDuration,
                playing ? notes.playbackDurationMillis() : savedDuration);
        addHint(playing ? "Playing · " + formatDuration(notes.playbackDurationMillis()) : message, MUTED);
        addSpacer();
        LinearLayout transport = addTransportRow();
        addTransport(transport, playing ? "Stop" : "Play", playing ? Glyph.STOP : Glyph.PLAY,
                ORANGE, new Runnable() {
            @Override public void run() {
                if (notes.isPlaying(savedFile)) notes.stopPlayback();
                else notes.play(savedFile);
            }
        });
        addTransport(transport, "Notes", Glyph.NOTES, ORANGE, new Runnable() {
            @Override public void run() { showLibrary(); }
        });
        addTransport(transport, "Done", Glyph.DONE, ORANGE, new Runnable() {
            @Override public void run() { closeFromUser(); }
        });
        addVolumeControls();
        showingSaved = true;
        if (preserveSelection) selectedAction = Math.max(0, Math.min(actions.size() - 1, previousSelection));
        applySelection(false);
        if (playing) main.post(playbackTick);
        else if (!preserveSelection && ValueAnimator.areAnimatorsEnabled()) {
            deck.setAlpha(.65f);
            deck.animate().alpha(1f).setDuration(180).setInterpolator(EASE_OUT).start();
        }
    }

    private void renderMessage(String message) {
        beginPage();
        if (page == null) return;
        TextView title = text("Voice recorder", 24, WHITE, Typeface.NORMAL);
        page.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView body = text(message, 29, WHITE, Typeface.NORMAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
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
        if (pageMode == PageMode.LIBRARY) rememberLibraryPosition();
        beginPage();
        if (page == null) return;
        pageMode = PageMode.LIBRARY;
        setLibraryPadding();
        savedFile = null;
        savedDuration = 0L;
        addLibraryHeader();
        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = referenceText("recorder", 44, RECORD_RED);
        GlyphDrawable mark = new GlyphDrawable(Glyph.RECORDER);
        mark.setColor(RECORD_RED); mark.setBounds(0, 0, px(30), px(30));
        title.setCompoundDrawables(mark, null, null, null); title.setCompoundDrawablePadding(px(10));
        heading.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        PlainControl add = new PlainControl("+", "New voice note", 42, WHITE);
        heading.addView(add, new LinearLayout.LayoutParams(dp(44), -1));
        registerAction(add, new Runnable() { @Override public void run() { showReady(); } });
        page.addView(heading, new LinearLayout.LayoutParams(-1, px(66)));

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
            for (final File file : files) {
                addLibraryAction(rows,
                        "Voice note", noteListDate(file), noteDate(file),
                        new Runnable() {
                            @Override public void run() { openDetail(file); }
                        });
            }
        }
        actionScroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        page.addView(actionScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        addVolumeControls();
        selectedAction = Math.max(0, Math.min(actions.size() - 1, files.isEmpty() ? 1 : librarySelection));
        applySelection(false);
        final ScrollView scroll = actionScroll;
        scroll.post(new Runnable() { @Override public void run() {
            if (actionScroll == scroll && pageMode == PageMode.LIBRARY) scroll.scrollTo(0, libraryScrollY);
        } });
    }

    private void renderDetail() {
        boolean preserveSelection = pageMode == PageMode.DETAIL;
        int previousSelection = selectedAction;
        beginPage();
        if (page == null || savedFile == null) return;
        pageMode = PageMode.DETAIL;
        page.setContentDescription("Voice note details");
        setLibraryPadding();
        addLibraryHeader();
        TextView title = referenceText("Voice note", 44, WHITE);
        page.addView(title, new LinearLayout.LayoutParams(-1, px(70)));
        TextView date = referenceText(noteDate(savedFile), 22, MUTED);
        page.addView(date, new LinearLayout.LayoutParams(-1, px(48)));
        TextView duration = referenceText(savedDuration < 0 ? "Duration unavailable" : "Duration " + formatDuration(savedDuration), 23, RECORD_RED);
        page.addView(duration, new LinearLayout.LayoutParams(-1, px(40)));
        addSpacer();
        final boolean playing = notes.isPlaying(savedFile);
        LinearLayout transport = new LinearLayout(activity);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        PlainControl play = new PlainControl(playing ? "■" : "▶", playing ? "Stop" : "Play", 36, WHITE);
        transport.addView(play, new LinearLayout.LayoutParams(dp(56), -1));
        registerAction(play, new Runnable() { @Override public void run() {
            if (pageMode != PageMode.DETAIL || savedFile == null) return;
            if (notes.isPlaying(savedFile)) notes.stopPlayback(); else notes.play(savedFile);
        } });
        timer = referenceText(formatDuration(playing ? notes.playbackPositionMillis() : 0), 32, WHITE);
        timer.setFontFeatureSettings("tnum");
        timer.setGravity(Gravity.CENTER_VERTICAL);
        transport.addView(timer, new LinearLayout.LayoutParams(0, -1, 1f));
        page.addView(transport, new LinearLayout.LayoutParams(-1, dp(64)));
        addVolumeControls();
        selectedAction = preserveSelection ? Math.max(0, Math.min(actions.size() - 1, previousSelection)) : 1;
        applySelection(false);
        if (playing) main.post(playbackTick);
    }

    private void setLibraryPadding() { page.setPadding(px(48), px(42), px(48), px(18)); }

    private String noteDate(File file) {
        return new SimpleDateFormat("MMM d, yyyy · h:mm:ss a", Locale.getDefault()).format(new Date(file.lastModified()));
    }

    private String noteListDate(File file) {
        Calendar note = Calendar.getInstance(); note.setTimeInMillis(file.lastModified());
        Calendar day = Calendar.getInstance();
        String prefix;
        if (note.get(Calendar.YEAR) == day.get(Calendar.YEAR)
                && note.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)) prefix = "Today";
        else {
            day.add(Calendar.DAY_OF_YEAR, -1);
            if (note.get(Calendar.YEAR) == day.get(Calendar.YEAR)
                    && note.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)) prefix = "Yesterday";
            else prefix = new SimpleDateFormat(note.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
                    ? "MMM d" : "MMM d, yyyy", Locale.getDefault()).format(note.getTime());
        }
        return prefix + " · " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(note.getTime());
    }

    private void addLibraryHeader() {
        FrameLayout header = new FrameLayout(activity);
        PlainControl back = new PlainControl("‹ back", pageMode == PageMode.DETAIL ? "Back to recordings" : "Back", 24, RECORD_RED);
        back.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(back, new FrameLayout.LayoutParams(px(118), -1, Gravity.LEFT));
        registerAction(back, new Runnable() { @Override public void run() { backFromUser(); } });
        headerTime = referenceText("", 24, RECORD_RED);
        headerTime.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams time = new FrameLayout.LayoutParams(px(112), -1, Gravity.CENTER);
        header.addView(headerTime, time);
        headerBattery = new RecorderBattery();
        header.addView(headerBattery, new FrameLayout.LayoutParams(px(32), px(18), Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));
        updateLibraryHeader();
    }

    private void updateLibraryHeader() {
        if (headerTime == null) return;
        String time = DateFormat.format(DateFormat.is24HourFormat(activity) ? "H:mm" : "h:mm", System.currentTimeMillis()).toString();
        if (!time.contentEquals(headerTime.getText())) headerTime.setText(time);
        BatteryManager battery = activity.getSystemService(BatteryManager.class);
        int percent = battery == null ? -1 : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (headerBattery != null) headerBattery.setPercent(percent);
    }

    private TextView referenceText(String value, int pixels, int color) {
        TextView view = text(value, 20, color, Typeface.NORMAL);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(pixels));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int px(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().widthPixels / 480f); }

    private void addDeckHeader(String state, int color) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹ recorder", 22, WHITE, Typeface.NORMAL);
        back.setContentDescription(pageMode == PageMode.READY_FROM_LIBRARY ? "Back to recordings" : "Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { backFromUser(); }
        });
        row.addView(back, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView status = text(state, 13, color, Typeface.NORMAL);
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.addView(status, new LinearLayout.LayoutParams(-2, -1));
        page.addView(row, new LinearLayout.LayoutParams(-1, dp(28)));
    }

    private void addTimer(long millis, int color) {
        timer = text(formatDuration(millis), 57, color, Typeface.NORMAL);
        timer.setGravity(Gravity.CENTER_VERTICAL);
        timer.setFontFeatureSettings("tnum");
        page.addView(timer, new LinearLayout.LayoutParams(-1, dp(72)));
    }

    private void setTimer(long millis) {
        if (timer == null) return;
        String value = formatDuration(millis);
        if (!value.contentEquals(timer.getText())) timer.setText(value);
    }

    private void addDeck(TapeDeckView.Mode mode, long position, long duration) {
        deck = new TapeDeckView(activity);
        deck.setState(mode, position, duration, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(200));
        params.topMargin = dp(6);
        params.bottomMargin = dp(8);
        page.addView(deck, params);
    }

    private void addHint(String message, int color) {
        TextView hint = text(message, 15, color, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        page.addView(hint, new LinearLayout.LayoutParams(-1, dp(28)));
    }

    private void addSpacer() {
        page.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private LinearLayout addTransportRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(64));
        params.topMargin = dp(10);
        page.addView(row, params);
        return row;
    }

    private void addTransport(LinearLayout row, String label, Glyph glyph, int accent, Runnable action) {
        TransportButton button = new TransportButton(label, glyph, accent);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        if (row.getChildCount() > 0) params.leftMargin = dp(8);
        row.addView(button, params);
        registerAction(button, action);
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
                        // AudioService persists this per output device, including zero.
                        // Never restore an app default or overwrite it on Play/entry.
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

    private void addLibraryAction(LinearLayout parent, String label, String detail, String fullDate,
            Runnable action) {
        LibraryRow row = new LibraryRow(label, detail, fullDate);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, px(96));
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
        final int generation = pageGeneration;
        final Runnable guardedAction = new Runnable() {
            @Override public void run() {
                if (!isVisible() || generation != pageGeneration || !view.isAttachedToWindow()) return;
                action.run();
            }
        };
        actions.add(guardedAction);
        actionViews.add(view);
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View target, boolean focused) {
                if (focused && generation == pageGeneration) {
                    selectedAction = index;
                    applySelection(false);
                }
            }
        });
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View target, MotionEvent event) {
                if (generation != pageGeneration || !isVisible()) return true;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    selectedAction = index;
                    applySelection(false);
                    if (view instanceof TransportButton && ValueAnimator.areAnimatorsEnabled()) {
                        view.animate().cancel();
                        view.setScaleX(.97f);
                        view.setScaleY(.97f);
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (view instanceof TransportButton) {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120)
                                .setInterpolator(EASE_OUT).start();
                    }
                }
                return false;
            }
        });
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View target) { guardedAction.run(); }
        });
    }

    private void applySelection(boolean requestFocus) {
        for (int i = 0; i < actionViews.size(); i++) {
            View view = actionViews.get(i);
            boolean selected = i == selectedAction;
            if (view instanceof LibraryRow) {
                ((LibraryRow) view).showSelection(selected);
            } else if (view instanceof PlainControl) {
                ((PlainControl) view).showSelection(selected);
            } else if (view instanceof TransportButton) {
                ((TransportButton) view).showSelection(selected);
            } else if (view instanceof Button) {
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
        // Back must end an unfinished take, just like leaving the Activity.
        // Otherwise release could save and reopen a recorder the user closed.
        abortAndDismiss();
        host.onRecorderClosed();
    }

    private void dismiss() {
        pageGeneration++;
        FrameLayout removed = root;
        root = null;
        main.removeCallbacks(meterTick);
        main.removeCallbacks(playbackTick);
        stopDeck();
        for (View view : actionViews) view.animate().cancel();
        main.removeCallbacks(volumeTick);
        stopPlaybackForNavigation();
        clearOwnedScreenFlag();
        if (removed != null) {
            ViewGroup parent = (ViewGroup) removed.getParent();
            if (parent != null) parent.removeView(removed);
        }
        for (Map.Entry<View, Integer> saved : siblingAccessibility.entrySet()) {
            if (saved.getKey().getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                saved.getKey().setImportantForAccessibility(saved.getValue());
        }
        siblingAccessibility.clear();
        showingSaved = false;
        pageMode = PageMode.OTHER;
        headerTime = null; headerBattery = null;
        librarySelection = 2; libraryScrollY = 0;
        page = null;
        actionViews.clear();
        actions.clear();
        timer = null;
        volumeLabel = null;
        volumeError = null;
        deck = null;
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

    private void stopDeck() {
        if (deck == null) return;
        deck.stopAnimation();
        deck.animate().cancel();
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
        return String.format(Locale.US, "%d:%02d:%02d", totalSeconds / 3600L,
                (totalSeconds / 60L) % 60L, totalSeconds % 60L);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private final class PlainControl extends TextView {
        private final int baseColor;
        PlainControl(String label, String description, int pixels, int color) {
            super(activity);
            baseColor = color;
            setText(label); setContentDescription(description);
            setTypeface(RabbitTypography.regular(activity));
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(pixels));
            setIncludeFontPadding(false); setGravity(Gravity.CENTER);
            setTextColor(color); setBackgroundColor(BG); setMinHeight(dp(44));
            setSoundEffectsEnabled(false);
        }
        void showSelection(boolean selected) {
            setSelected(selected);
            setTextColor(selected ? RECORD_RED : baseColor);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
        }
    }

    private final class LibraryRow extends LinearLayout {
        private final TextView chevron;
        LibraryRow(String title, String date, String fullDate) {
            super(activity);
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setBackgroundColor(BG);
            setContentDescription("Open voice note, " + fullDate);
            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(VERTICAL); labels.setGravity(Gravity.CENTER_VERTICAL);
            TextView primary = referenceText(title, 32, WHITE);
            TextView secondary = referenceText(date, 20, MUTED);
            primary.setSingleLine(true); secondary.setSingleLine(true);
            labels.addView(primary); labels.addView(secondary);
            addView(labels, new LinearLayout.LayoutParams(0, -1, 1f));
            chevron = referenceText("›", 32, MUTED);
            chevron.setGravity(Gravity.CENTER);
            addView(chevron, new LinearLayout.LayoutParams(px(30), -1));
        }
        void showSelection(boolean selected) {
            setSelected(selected);
            chevron.setTextColor(selected ? RECORD_RED : MUTED);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
        }
    }

    private final class RecorderBattery extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int percent = -2;
        RecorderBattery() { super(activity); }
        void setPercent(int value) {
            value = value < 0 || value > 100 ? -1 : value;
            if (percent == value) return;
            percent = value;
            setContentDescription(value < 0 ? "Battery unavailable" : "Battery " + value + " percent");
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;
            int saved = canvas.save();
            canvas.scale(getWidth() / 32f, getHeight() / 18f);
            paint.setColor(RECORD_RED); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.6f);
            canvas.drawRoundRect(1, 2, 28, 16, 3, 3, paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawRect(29, 6, 32, 12, paint);
            if (percent > 0) canvas.drawRect(4, 5, 4 + 21 * percent / 100f, 13, paint);
            canvas.restoreToCount(saved);
        }
    }

    private enum Glyph { PLAY, STOP, NOTES, DONE, CANCEL, RECORDER }

    private final class TransportButton extends Button {
        private final GlyphDrawable icon;
        private final int accent;

        TransportButton(String label, Glyph glyph, int accent) {
            super(activity);
            this.accent = accent;
            icon = new GlyphDrawable(glyph);
            icon.setBounds(0, 0, dp(22), dp(22));
            setText(label);
            setAllCaps(false);
            setTextSize(13);
            setTypeface(RabbitTypography.regular(activity));
            setIncludeFontPadding(false);
            setMinWidth(0);
            setMinimumWidth(0);
            setMinHeight(0);
            setMinimumHeight(0);
            setGravity(Gravity.CENTER);
            setPadding(dp(6), dp(7), dp(6), dp(7));
            setCompoundDrawables(null, icon, null, null);
            setCompoundDrawablePadding(dp(4));
            showSelection(false);
        }

        void showSelection(boolean selected) {
            int color = selected ? accent : WHITE;
            int fill = selected ? Color.argb(24, Color.red(accent), Color.green(accent), Color.blue(accent)) : BG;
            GradientDrawable background = shape(fill, 12);
            if (selected) background.setStroke(dp(1), accent);
            setBackground(background);
            setTextColor(selected ? accent : MUTED);
            icon.setColor(color);
        }
    }

    private static final class GlyphDrawable extends Drawable {
        private final Glyph glyph;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        GlyphDrawable(Glyph glyph) {
            this.glyph = glyph;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(2f);
        }

        void setColor(int color) { paint.setColor(color); invalidateSelf(); }

        @Override public void draw(Canvas canvas) {
            int checkpoint = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            switch (glyph) {
                case RECORDER:
                    int color = paint.getColor();
                    paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 11, paint);
                    paint.setColor(BG); paint.setStrokeWidth(3f); paint.setStyle(Paint.Style.STROKE);
                    canvas.translate(12, 12);
                    for (int i = 0; i < 3; i++) { canvas.drawLine(0, -4, 0, -8, paint); canvas.rotate(120); }
                    paint.setColor(color); paint.setStrokeWidth(2f);
                    break;
                case PLAY:
                    paint.setStyle(Paint.Style.FILL);
                    path.moveTo(7, 4); path.lineTo(20, 12); path.lineTo(7, 20); path.close();
                    canvas.drawPath(path, paint);
                    break;
                case STOP:
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(5, 5, 19, 19, 2, 2, paint);
                    break;
                case NOTES:
                    canvas.drawLine(5, 6, 19, 6, paint);
                    canvas.drawLine(5, 12, 19, 12, paint);
                    canvas.drawLine(5, 18, 15, 18, paint);
                    break;
                case DONE:
                    path.moveTo(4, 12); path.lineTo(10, 18); path.lineTo(20, 6);
                    canvas.drawPath(path, paint);
                    break;
                case CANCEL:
                    canvas.drawLine(6, 6, 18, 18, paint);
                    canvas.drawLine(18, 6, 6, 18, paint);
                    break;
            }
            canvas.restoreToCount(checkpoint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
