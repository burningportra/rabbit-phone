package com.kevtrinh.rabbitphone;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import java.util.LinkedHashMap;

public final class HomeActivity extends Activity {
    private static final int BG = Color.BLACK;
    private static final int WARM_WHITE = Color.rgb(245, 239, 225);
    private static final int MUTED = Color.rgb(161, 154, 140);
    private static final int ORANGE = Color.rgb(255, 90, 31);
    private static final int DARK_INK = Color.rgb(22, 18, 14);
    private static final int CARD = Color.rgb(27, 27, 24);

    private enum Page { HOME, DECK, APPS, UTILITIES, SETTINGS, KEYBOARD, FEATURE, CAMERA, TIMER_SETUP, TRANSLATOR, GALLERY, IDLE }

    private static final class Entry {
        final String id;
        final String label;
        final String detail;
        final Runnable action;
        final int color;
        final NavigationCard.Glyph glyph;

        Entry(String label, String detail, Runnable action) {
            this(label.toLowerCase(Locale.ROOT), label, detail, ORANGE, NavigationCard.Glyph.APPS, action);
        }
        Entry(String id, String label, String detail, int color, NavigationCard.Glyph glyph, Runnable action) {
            this.id = id; this.label = label; this.detail = detail; this.action = action;
            this.color = color; this.glyph = glyph;
        }
    }

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<View> selectableViews = new ArrayList<>();
    private final ArrayList<Entry> visibleEntries = new ArrayList<>();
    private final int[] savedSelection = new int[Page.values().length];
    private final LinkedHashMap<String, Entry> primaryCards = new LinkedHashMap<>();
    private CardNavigation navigation;
    private NavigationSurface navigationSurface;
    private RabbitHomeView homeVisual;
    private HomeStackReveal homeStackReveal;
    private CardDeckView cardDeck;
    private QuickSettingsOverlay quickSettings;
    private BatteryIcon batteryIcon;
    private boolean returningFromApp;
    private CameraScreen cameraScreen;
    private boolean cameraReturnHome;
    private TimerStore timerStore;
    private HardwarePage hardwarePage;
    private CardTransition cardTransition;
    private String activeFeatureId;
    private String pendingReturnCard;
    private boolean timerReadError;
    private final Runnable timerTick = new Runnable() {
        @Override public void run() { refreshTimerTick(); }
    };
    private final Handler controlsHandler = new Handler(Looper.getMainLooper());
    private HardwareButtonClient hardware;
    private ButtonGestures gestures;
    private VoiceRecorderOverlay recorderOverlay;
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
        timerStore = new TimerStore(this);
        prepareCards();
        syncTimerCard(timerSnapshot());
        prepareControls();
        showHome();
        handleNavigationIntent(getIntent());
        configureWindow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleNavigationIntent(intent)) return;
        if (intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (recorderOverlay != null) recorderOverlay.abortAndDismiss();
            if (quickSettings != null) quickSettings.dismiss();
            showHome();
        }
    }

    private boolean handleNavigationIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getStringExtra(NavigationIntents.EXTRA_DESTINATION);
        if (action == null) action = intent.getAction();
        if (!NavigationIntents.ACTION_OPEN_CAMERA.equals(action)
                && !NavigationIntents.ACTION_OPEN_KEYBOARD.equals(action)
                && !NavigationIntents.ACTION_OPEN_SETTINGS.equals(action)
                && !NavigationIntents.ACTION_OPEN_TIMER.equals(action)) return false;
        recorderOverlay.abortAndDismiss(); quickSettings.dismiss();
        if (NavigationIntents.ACTION_OPEN_CAMERA.equals(action)) openCamera();
        else if (NavigationIntents.ACTION_OPEN_KEYBOARD.equals(action)) showKeyboard();
        else if (NavigationIntents.ACTION_OPEN_TIMER.equals(action)) showTimerResult();
        else showSettings();
        return true;
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
        if (cameraScreen != null) cameraScreen.onResume();
        if (returningFromApp && page == Page.DECK) {
            String returningCard = activeFeatureId;
            showHome();
            pendingReturnCard = returningCard;
        }
        returningFromApp = false;
        configureWindow();
        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
        registerBatteryStatus();
        registerNetworkStatus();
        updateControls(); updateSurfaceMode();
        try {
            if (syncTimerCard(timerStore.reconcile()) && page == Page.DECK) showDeck(false);
        } catch (RuntimeException error) {
            boolean pausedForAccess = false;
            try {
                if (!timerStore.canSchedule()) {
                    TimerStore.Snapshot paused = timerStore.pause();
                    pausedForAccess = paused.phase == TimerState.Phase.PAUSED;
                    boolean orderChanged = syncTimerCard(paused);
                    if (orderChanged && page == Page.DECK) showDeck(false);
                }
            } catch (RuntimeException unavailable) { /* Retain unreadable state for recovery. */ }
            String message = error.getMessage() == null ? "Timer recovery is unavailable" : error.getMessage();
            showError(pausedForAccess ? "Timer paused. " + message : message);
        }
        refreshTimerTick();
    }

    @Override
    protected void onPause() {
        resumed = false;
        finishHomeReveal();
        if (hardwarePage != null) hardwarePage.setHostActive(false);
        cancelCardTransition(true);
        controlsHandler.removeCallbacks(timerTick);
        if (cameraScreen != null) cameraScreen.onPause();
        if (hardware != null) hardware.stop();
        if (gestures != null) gestures.cancel();
        if (recorderOverlay != null) recorderOverlay.abortAndDismiss();
        if (quickSettings != null) quickSettings.dismiss();
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
        if (!focused) {
            finishHomeReveal();
            if (hardwarePage != null) hardwarePage.setHostActive(false);
            cancelCardTransition(true);
        }
        if (focused && pendingReturnCard != null) {
            String id = pendingReturnCard; pendingReturnCard = null; returnFromFeature(id);
        }
        if (cameraScreen != null) { cameraScreen.onWindowFocusChanged(focused); return; }
        if (focused) { updateControls(); updateSurfaceMode(); refreshTimerTick(); }
        else {
            controlsHandler.removeCallbacks(timerTick);
            if (hardware != null) hardware.stop();
            if (gestures != null) gestures.cancel();
            if (recorderOverlay != null) recorderOverlay.abortAndDismiss();
            if (quickSettings != null) quickSettings.dismiss();
        }
    }

    @Override protected void onDestroy() {
        cancelCardTransition(false);
        controlsHandler.removeCallbacks(timerTick);
        releaseCameraScreen();
        setHardwarePage(null);
        if (recorderOverlay != null) recorderOverlay.release();
        if (quickSettings != null) quickSettings.release();
        super.onDestroy();
    }

    private void updateControls() {
        if (cameraScreen != null || hardware == null || !resumed || !hasWindowFocus()) return;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        PowerManager power = getSystemService(PowerManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return;
        if (power != null && !power.isInteractive()) return;
        hardware.start();
    }

    private void prepareControls() {
        recorderOverlay = new VoiceRecorderOverlay(this, new VoiceRecorderOverlay.Host() {
            @Override public void onRecorderActiveChanged(boolean active) {
                recording = active;
                updateStatus();
                updateSurfaceMode();
            }
            @Override public void onRecorderMessage(String message) { showError(message); }
            @Override public void onRecorderClosed() { returnFromFeature("recorder"); }
        });
        quickSettings = new QuickSettingsOverlay(this, new QuickSettingsOverlay.Host() {
            @Override public void onCamera() { recorderOverlay.abortAndDismiss(); openCamera(); }
            @Override public void onKeyboard() { recorderOverlay.abortAndDismiss(); showKeyboard(); }
            @Override public void onLock() {
                recorderOverlay.abortAndDismiss(); gestures.cancel();
                if (!hardware.send(HardwareButtonClient.Command.SLEEP)) showError("Use the side button to lock");
            }
            @Override public void onSettings() { recorderOverlay.abortAndDismiss(); showSettings(); }
            @Override public void onMessage(String message) { showError(message); }
            @Override public void onDismiss() { updateSurfaceMode(); }
        });
        gestures = new ButtonGestures(new ButtonGestures.Scheduler() {
            @Override public long now() { return android.os.SystemClock.uptimeMillis(); }
            @Override public void postDelayed(Runnable action, long delay) { controlsHandler.postDelayed(action, delay); }
            @Override public void remove(Runnable action) { controlsHandler.removeCallbacks(action); }
        }, new ButtonGestures.Actions() {
            @Override public void onSingle() {
                if (quickSettings.handleSingle()) return;
                if (recorderOverlay.handleSingle()) return;
                if (hardwarePage != null && hardwarePage.handleSingle()) return;
                if (page == Page.HOME || page == Page.IDLE) hardware.send(HardwareButtonClient.Command.SLEEP);
                else {
                    getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    activateSelection();
                }
            }
            @Override public void onDouble() {
                cancelCardTransition(true);
                quickSettings.dismiss();
                recorderOverlay.dismissForDouble();
                openCamera();
            }
            @Override public void onHoldStart() {
                cancelCardTransition(true);
                quickSettings.dismiss();
                visitFeature("recorder");
                recorderOverlay.beginHold();
                updateSurfaceMode();
            }
            @Override public void onHoldEnd() { recorderOverlay.finishHold(); }
            @Override public void onRefresh() {
                cancelCardTransition(false);
                recorderOverlay.abortAndDismiss();
                quickSettings.dismiss();
                showHome(); refreshNetwork(); updateClock();
                showError("Phone interface refreshed");
            }
            @Override public void onShutdown() {
                cancelCardTransition(false);
                recorderOverlay.abortAndDismiss();
                quickSettings.dismiss();
                if (hardware.send(HardwareButtonClient.Command.SHUTDOWN)) showError("Powering off");
            }
        });
        hardware = new HardwareButtonClient(this, new HardwareButtonClient.Listener() {
            @Override public void onReady() {
                hardware.send(HardwareButtonClient.Command.MOTOR_PRIVACY);
                android.util.Log.i("RabbitPhoneHardware", "Home controls ready");
            }
            @Override public void onDown(long time) { cancelCardTransition(true); gestures.down(time); }
            @Override public void onUp(long time) { gestures.up(time); }
            @Override public void onDisconnected() {
                cancelCardTransition(true);
                gestures.cancel(); recorderOverlay.abortAndDismiss();
                quickSettings.dismiss();
                android.util.Log.i("RabbitPhoneHardware", "Home controls released");
                controlsHandler.postDelayed(new Runnable() {
                    @Override public void run() { updateControls(); }
                }, 750);
            }
        });
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

    private void prepareCards() {
        addCard("camera", "camera", 0xffff009f, NavigationCard.Glyph.CAMERA, new Runnable() {
            @Override public void run() { openCamera(); }
        });
        addCard("gallery", "magic gallery", 0xff1af6ff, NavigationCard.Glyph.GALLERY,
                new Runnable() {
                    @Override public void run() {
                        visitFeature("gallery");
                        showGallery();
                    }
                });
        addCard("timer", "timer", 0xff6b63ff, NavigationCard.Glyph.TIMER,
                new Runnable() {
                    @Override public void run() {
                        showTimerSetup();
                    }
                });
        addCard("translator", "translator", 0xff02f719, NavigationCard.Glyph.TRANSLATE,
                new Runnable() {
                    @Override public void run() {
                        visitFeature("translator");
                        showTranslator();
                    }
                });
        addCard("recorder", "recorder", 0xffff163c, NavigationCard.Glyph.RECORDER,
                new Runnable() {
                    @Override public void run() { visitFeature("recorder"); recorderOverlay.showLibrary(); updateSurfaceMode(); }
                });
        addCard("r-cade", "r-cade", 0xffff8d00, NavigationCard.Glyph.RCADE, new Runnable() {
            @Override public void run() { visitFeature("r-cade"); showGames(); }
        });
        addCard("reminders", "reminder", 0xff0091ff, NavigationCard.Glyph.REMINDERS,
                new Runnable() {
                    @Override public void run() {
                        if (launchPackage("com.techyminati.pages", null, "No reminders app is installed")) visitFeature("reminders");
                    }
                });
        addCard("alarm", "alarms", 0xffe16bf5, NavigationCard.Glyph.ALARM,
                new Runnable() {
                    @Override public void run() {
                        if (launchIntent(new Intent(AlarmClock.ACTION_SHOW_ALARMS), "No alarm app is installed")) visitFeature("alarm");
                    }
                });
        addCard("settings", "settings", 0xffff3400, NavigationCard.Glyph.SETTINGS, new Runnable() {
            @Override public void run() { visitFeature("settings"); showSettings(); }
        });
        addCard("creations", "creations", 0xffff7700, NavigationCard.Glyph.CREATIONS,
                new Runnable() {
                    @Override public void run() {
                        visitFeature("creations");
                        showWebCard("creations", "Browse creations", "https://www.rabbit.tech/creations");
                    }
                });
        addCard("intern", "intern", 0xffffb300, NavigationCard.Glyph.INTERN,
                new Runnable() {
                    @Override public void run() { visitFeature("intern"); showWebCard("intern", "Open OS3", "https://os3.rabbit.tech/"); }
                });
        addCard("music", "music", 0xffe7f200, NavigationCard.Glyph.MUSIC, new Runnable() {
            @Override public void run() { openMusic(); }
        });
        addCard("apps", "apps", 0xfff5efe1, NavigationCard.Glyph.APPS, new Runnable() {
            @Override public void run() { visitFeature("apps"); showApps(); }
        });
        navigation = new CardNavigation(new ArrayList<>(primaryCards.keySet()));
        // Active task cards come from their owners, never from a recent-app cache.
        // Older builds promoted every visited feature; discard that derived state.
        getPreferences(MODE_PRIVATE).edit().remove("opened_cards").apply();
        navigation.select(0);
    }

    private void addCard(String id, String title, int color, NavigationCard.Glyph glyph, Runnable action) {
        primaryCards.put(id, new Entry(id, title, "", color, glyph, action));
    }

    private void visitFeature(String id) {
        navigation.visit(id);
    }

    @Override public void dump(String prefix, java.io.FileDescriptor descriptor,
            java.io.PrintWriter writer, String[] args) {
        if (args != null) for (String argument : args) {
            if ("rabbit-navigation".equals(argument)) {
                writer.println(prefix + "rabbit_navigation_page=" + page);
                writer.println(prefix + "rabbit_animators_enabled=" + ValueAnimator.areAnimatorsEnabled());
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    writer.println(prefix + "rabbit_animator_duration_scale=" + ValueAnimator.getDurationScale());
                writer.println(prefix + "rabbit_card_transition_active=" + (cardTransition != null));
                writer.println(prefix + "rabbit_home_reveal_active="
                        + (homeStackReveal != null && homeStackReveal.isRevealing()));
                return;
            }
        }
        super.dump(prefix, descriptor, writer, args);
    }

    private void showHome() {
        activeFeatureId = null; pendingReturnCard = null;
        page = Page.HOME;
        visibleEntries.clear(); selectableViews.clear(); scrollView = null; cardDeck = null;
        clockView = dateView = statusView = null; batteryIcon = null;
        homeVisual = new RabbitHomeView(this);
        homeVisual.setOpenStack(new Runnable() {
            @Override public void run() { showDeck(true); }
        });
        installPage(homeVisual, true);
        updateClock(); updateStatus();
    }

    private void showDeck(boolean reveal) {
        syncTimerCard(timerSnapshot());
        page = Page.DECK;
        ArrayList<Entry> entries = new ArrayList<>();
        for (String id : navigation.order()) entries.add(primaryCards.get(id));
        renderCardEntries(entries, navigation.selectedIndex(), false, reveal);
    }

    private void renderCardEntries(List<Entry> entries, int selected, boolean back, boolean reveal) {
        selectableViews.clear(); visibleEntries.clear(); visibleEntries.addAll(entries);
        homeVisual = null; scrollView = null; clockView = dateView = statusView = null;
        cardDeck = new CardDeckView(this);
        ArrayList<NavigationCard> cards = new ArrayList<>();
        TimerStore.Snapshot timer = timerSnapshot();
        for (Entry entry : entries) cards.add(new NavigationCard(entry.id, entry.label, entry.color,
                entry.glyph, page == Page.DECK && navigation.isOpened(entry.id),
                "timer".equals(entry.id) ? timerPreview(timer) : null));
        selection = clamp(selected, 0, Math.max(0, entries.size() - 1));
        cardDeck.setCards(cards, selection);
        cardDeck.setListener(new CardDeckView.Listener() {
            @Override public void onSelectionChanged(int index) {
                selection = index; savedSelection[page.ordinal()] = index;
                if (page == Page.DECK) navigation.select(index);
                getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            @Override public void onActivate(int index) { selection = index; activateSelection(); }
            @Override public void onCardAction(int index, String action) {
                if (page == Page.DECK && index >= 0 && index < visibleEntries.size()
                        && "timer".equals(visibleEntries.get(index).id)) handleTimerAction(action);
            }
            @Override public void onDismiss(int index) {
                if (page != Page.DECK || index < 0 || index >= visibleEntries.size()) return;
                if ("timer".equals(visibleEntries.get(index).id)) { handleTimerAction("cancel_timer"); return; }
                navigation.close(visibleEntries.get(index).id); showDeck(false);
                getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            }
        });
        View header = cardStatusHeader(back, WARM_WHITE);
        FrameLayout canvas;
        if (reveal) {
            RabbitHomeView backdrop = new RabbitHomeView(this);
            backdrop.setStatus(new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date()), batteryPercent);
            canvas = new HomeStackReveal(this, backdrop, cardDeck, header);
        } else {
            canvas = new FrameLayout(this); canvas.setBackgroundColor(Color.BLACK);
            canvas.addView(cardDeck, new FrameLayout.LayoutParams(-1, -1));
            canvas.addView(header, new FrameLayout.LayoutParams(-1, Math.round(80 * screenScale())));
        }
        installPage(canvas, false); updateClock(); updateStatus(); refreshTimerTick();
        if (canvas instanceof HomeStackReveal) ((HomeStackReveal) canvas).start();
    }

    private void finishHomeReveal() {
        if (homeStackReveal != null) homeStackReveal.finishReveal();
    }

    private void installPage(View content, boolean home) {
        HomeStackReveal nextReveal = content instanceof HomeStackReveal ? (HomeStackReveal) content : null;
        if (homeStackReveal != nextReveal) finishHomeReveal();
        homeStackReveal = nextReveal;
        if (cardTransition != null && !cardTransition.isRevealing()) cancelCardTransition(false);
        controlsHandler.removeCallbacks(timerTick);
        if (page != Page.TIMER_SETUP && page != Page.TRANSLATOR && page != Page.GALLERY
                && page != Page.SETTINGS) setHardwarePage(null);
        releaseCameraScreen();
        navigationSurface = new NavigationSurface(this); navigationSurface.setHome(home);
        navigationSurface.setListener(new NavigationSurface.Listener() {
            @Override public void onQuickSettings() { showQuickSettings(); }
            @Override public void onQuickHome() {
                if (quickSettings.isVisible()) { quickSettings.dismiss(); return; }
                recorderOverlay.abortAndDismiss(); hideKeyboard(); showHome();
            }
            @Override public void onOpenStack() {
                if (page == Page.HOME && !recorderOverlay.isVisible() && !quickSettings.isVisible()) showDeck(true);
            }
        });
        navigationSurface.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(navigationSurface); configureWindow(); updateSurfaceMode(); updateControls();
    }

    private void updateSurfaceMode() {
        boolean modal = (recorderOverlay != null && recorderOverlay.isVisible())
                || (quickSettings != null && quickSettings.isVisible()) || cardTransition != null;
        if (modal) finishHomeReveal();
        if (navigationSurface != null) navigationSurface.setHome(page == Page.HOME && !modal);
        if (cardDeck != null) cardDeck.setEnabled(!modal);
        if (hardwarePage != null) {
            hardwarePage.setHostActive(resumed && hasWindowFocus());
            hardwarePage.getView().setEnabled(!modal);
            hardwarePage.getView().setImportantForAccessibility(modal ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
    }

    private void showQuickSettings() {
        cancelCardTransition(true);
        if (!quickSettings.isVisible()) quickSettings.show(NavigationSurface.content(this));
        updateSurfaceMode();
    }

    private View statusHeader(boolean back) { return statusHeader(back, WARM_WHITE); }

    private View statusHeader(boolean back, int tint) {
        FrameLayout header = new FrameLayout(this);
        clockView = text("", 22, tint, Typeface.NORMAL); clockView.setGravity(Gravity.CENTER);
        clockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30f * screenScale());
        header.addView(clockView, new FrameLayout.LayoutParams(-1, -1));
        if (back) {
            View button = new BackControl(tint);
            header.addView(button, new FrameLayout.LayoutParams(Math.round(160 * screenScale()), -1, Gravity.LEFT));
        }
        batteryIcon = new BatteryIcon(this, tint);
        FrameLayout.LayoutParams battery = new FrameLayout.LayoutParams(Math.round(34 * screenScale()), Math.round(18 * screenScale()), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        battery.rightMargin = Math.round(24 * screenScale()); header.addView(batteryIcon, battery);
        return header;
    }

    private View cardStatusHeader(boolean back, int tint) {
        View header = statusHeader(back, tint);
        header.setTranslationY(0f);
        return header;
    }

    private float screenScale() { return getResources().getDisplayMetrics().widthPixels / 480f; }

    private int stackCueColor(String openingId) {
        for (String id : navigation.order()) {
            if (!id.equals(openingId) && primaryCards.containsKey(id)) return primaryCards.get(id).color;
        }
        return ORANGE;
    }

    private void beginCardOpen(NavigationCard card, RectF bounds, final Entry entry) {
        cardTransition = new CardTransition(this, card, bounds, stackCueColor(card.id), null, null,
                new CardTransition.Listener() {
                    @Override public void onReveal(CardTransition transition) {
                        if (cardTransition != transition || !resumed || !hasWindowFocus()) {
                            cancelCardTransition(false); return;
                        }
                        entry.action.run();
                        if (cardTransition == transition) {
                            transition.attachTo(NavigationSurface.content(HomeActivity.this));
                            updateSurfaceMode();
                        }
                    }
                    @Override public void onFinished(CardTransition transition) {
                        if (cardTransition == transition) { cardTransition = null; updateSurfaceMode(); }
                    }
                    @Override public void onCanceled(CardTransition transition) {
                        if (cardTransition == transition) { cardTransition = null; updateSurfaceMode(); }
                    }
                });
        cardTransition.attachTo(NavigationSurface.content(this));
        updateSurfaceMode();
        cardTransition.start();
    }

    private void returnFromFeature(String id) {
        cancelCardTransition(false);
        Entry entry = id == null ? null : primaryCards.get(id);
        if (entry == null || !resumed || !hasWindowFocus()) { showHome(); return; }
        if (cameraScreen != null || page == Page.DECK) {
            // Release the camera immediately. Closed overlays and external apps
            // must not expose the old stack underneath their return animation.
            showHome();
        }
        controlsHandler.removeCallbacks(timerTick);
        hideKeyboard();
        RabbitHomeView backdrop = new RabbitHomeView(this);
        backdrop.setStatus(new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date()), batteryPercent);
        NavigationCard card = new NavigationCard(entry.id, entry.label, entry.color, entry.glyph, true,
                "timer".equals(id) ? timerPreview(timerSnapshot()) : null);
        cardTransition = new CardTransition(this, card, new RectF(), stackCueColor(id), backdrop, cardStatusHeader(false, WARM_WHITE),
                new CardTransition.Listener() {
                    @Override public void onReveal(CardTransition transition) { }
                    @Override public void onFinished(CardTransition transition) {
                        if (cardTransition != transition) return;
                        cardTransition = null;
                        showHome();
                    }
                    @Override public void onCanceled(CardTransition transition) {
                        if (cardTransition == transition) { cardTransition = null; updateSurfaceMode(); }
                    }
                });
        cardTransition.attachTo(NavigationSurface.content(this));
        updateClock(); updateStatus(); updateSurfaceMode();
        cardTransition.start();
    }

    private void cancelCardTransition(boolean finishExit) {
        CardTransition old = cardTransition;
        if (old == null) return;
        cardTransition = null;
        old.cancel();
        if (finishExit && old.isExiting()) showHome();
        else updateSurfaceMode();
    }

    private void setHardwarePage(HardwarePage next) {
        HardwarePage previous = hardwarePage;
        hardwarePage = next;
        if (previous != null && previous != next) previous.release();
    }

    private void showGallery() {
        activeFeatureId = "gallery";
        page = Page.GALLERY;
        homeVisual = null; cardDeck = null; scrollView = null;
        visibleEntries.clear(); selectableViews.clear();
        clockView = dateView = statusView = null;
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        setHardwarePage(new GalleryView(this));
        root.addView(hardwarePage.getView(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(cardStatusHeader(true, 0xff25c8ed), new FrameLayout.LayoutParams(-1, Math.round(80 * screenScale())));
        installPage(root, false); updateClock(); updateStatus();
    }

    private void showTranslator() {
        activeFeatureId = "translator";
        page = Page.TRANSLATOR;
        homeVisual = null; cardDeck = null; scrollView = null;
        visibleEntries.clear(); selectableViews.clear();
        clockView = dateView = statusView = null;
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        setHardwarePage(new TranslatorSetupView(this, new TranslatorSetupView.Host() {
            @Override public void onContinue(String source, String target) {
                if (!resumed || !hasWindowFocus()) return;
                Uri destination = Uri.parse("https://translate.google.com/").buildUpon()
                        .appendQueryParameter("sl", source).appendQueryParameter("tl", target)
                        .appendQueryParameter("op", "translate").build();
                launchIntent(new Intent(Intent.ACTION_VIEW, destination), "No translation browser is available");
            }
        }));
        root.addView(hardwarePage.getView(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(cardStatusHeader(true, 0xff02f719), new FrameLayout.LayoutParams(-1, Math.round(80 * screenScale())));
        installPage(root, false); updateClock(); updateStatus();
    }

    private TimerStore.Snapshot timerSnapshot() {
        try {
            TimerStore.Snapshot snapshot = timerStore.snapshot();
            timerReadError = false;
            return snapshot;
        } catch (RuntimeException error) {
            if (!timerReadError) showError(error.getMessage() == null ? "Timer is unavailable" : error.getMessage());
            timerReadError = true;
            return null;
        }
    }

    private boolean syncTimerCard(TimerStore.Snapshot snapshot) {
        if (snapshot == null) return false;
        boolean exists = snapshot.phase != TimerState.Phase.NONE;
        if (exists == navigation.isOpened("timer")) return false;
        if (exists) navigation.open("timer"); else navigation.close("timer");

        return true;
    }

    private NavigationCard.Preview timerPreview(TimerStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.phase == TimerState.Phase.NONE) return null;
        long seconds = Math.max(0, (snapshot.remainingMs + 999) / 1000);
        String value = seconds >= 3600
                ? String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        long total = snapshot.durationMs / 1000;
        String detail;
        if (snapshot.phase == TimerState.Phase.FINISHED) detail = "time's up";
        else if (total % 60 == 0 && total < 3600) detail = total / 60 + (total == 60 ? " minute" : " minutes");
        else if (total < 60) detail = total + (total == 1 ? " second" : " seconds");
        else if (total % 3600 == 0) detail = total / 3600 + (total == 3600 ? " hour" : " hours");
        else detail = (total >= 3600 ? total / 3600 + "h " : "")
                    + (total / 60) % 60 + "m " + total % 60 + "s";
        return new NavigationCard.Preview(NavigationCard.Preview.Kind.TIMER, value, detail,
                snapshot.phase == TimerState.Phase.RUNNING, snapshot.phase == TimerState.Phase.FINISHED);
    }

    private void showTimerSetup() {
        activeFeatureId = "timer";
        page = Page.TIMER_SETUP;
        homeVisual = null; cardDeck = null; scrollView = null;
        visibleEntries.clear(); selectableViews.clear();
        clockView = dateView = statusView = null;
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        setHardwarePage(new TimerSetupView(this, new TimerSetupView.Host() {
            @Override public void onStart(long durationMillis) {
                if (!resumed || !hasWindowFocus()) return;
                try {
                    timerStore.start(durationMillis);
                    navigation.open("timer");
                    showDeck(true);
                } catch (RuntimeException error) {
                    showError(error.getMessage() == null ? "Timer couldn't start" : error.getMessage());
                }
            }
        }));
        root.addView(hardwarePage.getView(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(cardStatusHeader(true, 0xff6b63ff), new FrameLayout.LayoutParams(-1, Math.round(80 * screenScale())));
        installPage(root, false); updateClock(); updateStatus();
    }

    private void showTimerResult() {
        TimerStore.Snapshot snapshot = timerSnapshot();
        if (snapshot == null || snapshot.phase == TimerState.Phase.NONE) { showTimerSetup(); return; }
        navigation.open("timer"); showDeck(false);
    }

    private void handleTimerAction(String action) {
        if (!resumed || !hasWindowFocus()) return;
        try {
            TimerStore.Snapshot current = timerStore.snapshot();
            if ("cancel_timer".equals(action)) {
                timerStore.cancel(); navigation.close("timer"); showDeck(false);
                return;
            }
            if ("pause_timer".equals(action) && current.phase == TimerState.Phase.RUNNING) timerStore.pause();
            else if ("resume_timer".equals(action) && current.phase == TimerState.Phase.PAUSED) timerStore.resume();
            else if ("restart_timer".equals(action) && current.phase == TimerState.Phase.FINISHED) timerStore.start(current.durationMs);
            refreshTimerTick();
        } catch (RuntimeException error) {
            showError(error.getMessage() == null ? "Timer couldn't change" : error.getMessage());
        }
    }

    private void refreshTimerTick() {
        controlsHandler.removeCallbacks(timerTick);
        if (!resumed || !hasWindowFocus() || page != Page.DECK || cardDeck == null) return;
        TimerStore.Snapshot snapshot = timerSnapshot();
        if (snapshot == null) return;
        cardDeck.updatePreview("timer", timerPreview(snapshot));
        if (snapshot.phase == TimerState.Phase.RUNNING) {
            long delay = Math.max(25, Math.min(1000, snapshot.remainingMs % 1000 + 20));
            controlsHandler.postDelayed(timerTick, delay);
        }
    }

    private void showApps() { showApps(""); }

    private void showApps(String filter) {
        activeFeatureId = "apps";
        savedSelection[page.ordinal()] = selection;
        page = Page.APPS;
        ArrayList<Entry> entries = new ArrayList<>();
        if (filter.isEmpty()) entries.add(new Entry("Utilities", "Contacts, clock, recorder, compass", new Runnable() {
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
            if (!label.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) continue;
            ComponentName component = new ComponentName(
                    info.activityInfo.packageName, info.activityInfo.name);
            entries.add(new Entry(component.flattenToString(), label, "", 0xfff5efe1, NavigationCard.Glyph.APPS, new Runnable() {
                @Override public void run() {
                    launchIntent(new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(component), label + " couldn't open");
                }
            }));
        }
        if (entries.isEmpty()) renderListPage("apps", "No matching apps", entries);
        else renderCardEntries(entries, savedSelection[Page.APPS.ordinal()], true, false);
    }

    private void showUtilities() {
        savedSelection[page.ordinal()] = selection;
        page = Page.UTILITIES;
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(new Entry("Voice notes", "Your local recordings", new Runnable() {
            @Override public void run() { recorderOverlay.showLibrary(); }
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
        entries.add(new Entry("Recorder", "Local voice notes", new Runnable() {
            @Override public void run() { recorderOverlay.showLibrary(); }
        }));
        entries.add(packageEntry("Compass", "Direction and heading", "com.techyminati.compass", null));
        renderListPage("Utilities", "Simple tools", entries);
    }

    private void showSettings() {
        if (page == Page.SETTINGS && hardwarePage instanceof SettingsView) {
            updateSurfaceMode();
            return;
        }
        activeFeatureId = "settings";
        page = Page.SETTINGS;
        homeVisual = null; cardDeck = null; scrollView = null;
        visibleEntries.clear(); selectableViews.clear();
        clockView = dateView = statusView = null;
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        setHardwarePage(new SettingsView(this, new SettingsView.Host() {
            @Override public void onWifi() {
                launchSettingsChild(new Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi settings unavailable");
            }
            @Override public void onBluetooth() {
                launchSettingsChild(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth settings unavailable");
            }
            @Override public void onCellular() {
                launchSettingsChild(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS), "Cellular settings unavailable");
            }
            @Override public void onTheme() {
                launchSettingsChild(new Intent(HomeActivity.this, ThemeActivity.class), "Theme unavailable");
            }
            @Override public void onDeviceSettings() {
                launchSettingsChild(new Intent(Settings.ACTION_SETTINGS), "Settings unavailable");
            }
            @Override public void onTimeSettings() {
                launchSettingsChild(new Intent(Settings.ACTION_DATE_SETTINGS), "Time settings unavailable");
            }
            @Override public void onLanguageSettings() {
                launchSettingsChild(new Intent(Settings.ACTION_LOCALE_SETTINGS), "Language settings unavailable");
            }
            @Override public void onMessage(String message) { showError(message); }
        }));
        root.addView(hardwarePage.getView(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(cardStatusHeader(true, 0xffff3400), new FrameLayout.LayoutParams(-1, Math.round(80 * screenScale())));
        installPage(root, false); updateClock(); updateStatus();
    }

    /** Keep a settings child in this task so Android Back restores its native parent. */
    private void launchSettingsChild(Intent intent, String message) {
        try {
            startActivity(intent);
            returningFromApp = true;
        } catch (RuntimeException unavailable) {
            showError(message);
        }
    }

    private void showGames() {
        page = Page.FEATURE;
        ArrayList<Entry> games = new ArrayList<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : getPackageManager().queryIntentActivities(query, 0)) {
            if (info.activityInfo == null || info.activityInfo.applicationInfo.category != android.content.pm.ApplicationInfo.CATEGORY_GAME) continue;
            String label = info.loadLabel(getPackageManager()).toString();
            String pkg = info.activityInfo.packageName;
            games.add(new Entry(label, "", new Runnable() {
                @Override public void run() { launchPackage(pkg, null, "Game unavailable"); }
            }));
        }
        renderListPage("r-cade", games.isEmpty() ? "No games installed" : "Installed games", games);
    }

    private void showWebCard(String title, String button, String url) {
        page = Page.FEATURE;
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(new Entry(button, "Opens in your browser", new Runnable() {
            @Override public void run() {
                launchIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), "No browser is installed");
            }
        }));
        renderListPage(title, "", entries);
    }

    private void showKeyboard() {
        page = Page.KEYBOARD; cardDeck = null; homeVisual = null;
        visibleEntries.clear(); selectableViews.clear(); scrollView = null;
        LinearLayout root = rootColumn();
        root.addView(statusHeader(true), new LinearLayout.LayoutParams(-1, dp(60)));
        TextView label = text("find an app", 32, WARM_WHITE, Typeface.NORMAL);
        label.setPadding(0, dp(24), 0, dp(18)); root.addView(label);
        final EditText query = new EditText(this);
        query.setSingleLine(true); query.setTextColor(WARM_WHITE); query.setHintTextColor(MUTED);
        query.setTypeface(RabbitTypography.regular(this)); query.setHint("Type an app name");
        query.setTextSize(22); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        root.addView(query, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView search = text("search", 22, ORANGE, Typeface.NORMAL);
        search.setGravity(Gravity.CENTER_VERTICAL); root.addView(search, new LinearLayout.LayoutParams(-1, dp(56)));
        final Runnable runSearch = new Runnable() {
            @Override public void run() {
                String value = query.getText().toString().trim(); hideKeyboard(); showApps(value);
            }
        };
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { runSearch.run(); }
        });
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView view, int action, KeyEvent event) {
                if (action == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    runSearch.run(); return true;
                }
                return false;
            }
        });
        installPage(root, false); updateClock(); updateStatus(); query.requestFocus();
        query.post(new Runnable() {
            @Override public void run() {
                getSystemService(InputMethodManager.class).showSoftInput(
                        query, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }

    private final class BackControl extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int tint;
        BackControl(int tint) {
            super(HomeActivity.this); this.tint = tint;
            paint.setTypeface(RabbitTypography.regular(HomeActivity.this));
            setContentDescription("Back"); setFocusable(true);
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { onBackPressed(); }
            });
        }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = screenScale(), y = getHeight() / 2f;
            paint.setColor(tint); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * s); paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(24 * s, y, 44 * s, y, paint);
            canvas.drawLine(24 * s, y, 33 * s, y - 9 * s, paint);
            canvas.drawLine(24 * s, y, 33 * s, y + 9 * s, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(28 * s);
            canvas.drawText("back", 54 * s, y + 9 * s, paint);
        }
    }

    private final class BatteryIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int tint;
        BatteryIcon(Context context, int tint) { super(context); this.tint = tint; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            paint.setColor(tint); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1));
            canvas.drawRoundRect(1, 1, w - dp(3), h - 1, dp(2), dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(w - dp(2), h * .3f, w, h * .7f, paint);
            if (batteryPercent > 0) canvas.drawRect(dp(3), dp(3), dp(3) + (w - dp(9)) * Math.min(100, batteryPercent) / 100f, h - dp(3), paint);
        }
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

        cardDeck = null; homeVisual = null;
        LinearLayout root = rootColumn();
        root.addView(statusHeader(true), new LinearLayout.LayoutParams(-1, dp(60)));
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

        installPage(root, false); updateClock(); updateStatus();
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

        TextView label = text(entry.label, 24, WARM_WHITE, Typeface.BOLD);
        TextView detail = text(entry.detail, 14, MUTED, Typeface.NORMAL);
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
        int transitionKey = event.getKeyCode();
        InputDevice transitionDevice = event.getDevice();
        boolean transitionWheel = transitionKey == KeyEvent.KEYCODE_DPAD_UP || transitionKey == KeyEvent.KEYCODE_DPAD_DOWN
                || (transitionDevice != null && "och1970_holl_key".equals(transitionDevice.getName())
                    && (transitionKey == KeyEvent.KEYCODE_VOLUME_UP || transitionKey == KeyEvent.KEYCODE_VOLUME_DOWN));
        boolean transitionSelect = transitionKey == KeyEvent.KEYCODE_DPAD_CENTER || transitionKey == KeyEvent.KEYCODE_ENTER
                || transitionKey == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (event.getAction() == KeyEvent.ACTION_DOWN && (transitionWheel || transitionSelect)) finishHomeReveal();
        if (cardTransition != null && event.getAction() == KeyEvent.ACTION_DOWN && (transitionWheel || transitionSelect))
            cancelCardTransition(true);
        if (cameraScreen != null) return cameraScreen.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
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
            if (quickSettings != null && quickSettings.handleWheel(up)) return true;
            if (recorderOverlay != null && recorderOverlay.handleWheel(up)) return true;
            if (hardwarePage != null && hardwarePage.handleWheel(up)) return true;
            if (page == Page.IDLE || page == Page.HOME) {
                showDeck(true);
                getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return true;
            }
            if (visibleEntries.isEmpty()) return true;
            int delta = up ? -1 : 1;
            selection = clamp(selection + delta, 0, visibleEntries.size() - 1);
            savedSelection[page.ordinal()] = selection;
            if (page == Page.DECK) navigation.select(selection);
            applySelection(true);
            getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            return true;
        }
        if (page == Page.KEYBOARD && key != KeyEvent.KEYCODE_DPAD_CENTER) return super.dispatchKeyEvent(event);
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (quickSettings != null && quickSettings.handleSingle()) return true;
                if (recorderOverlay != null && recorderOverlay.handleSingle()) return true;
                if (hardwarePage != null && hardwarePage.handleSingle()) return true;
                if (page == Page.HOME) showDeck(true); else activateSelection();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void applySelection(boolean requestFocus) {
        if (cardDeck != null) { cardDeck.setSelection(selection, requestFocus); return; }
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
        finishHomeReveal();
        if (!resumed || !hasWindowFocus() || cardTransition != null || quickSettings.isVisible() || recorderOverlay.isVisible()) return;
        if (selection < 0 || selection >= visibleEntries.size()) return;
        savedSelection[page.ordinal()] = selection;
        final Entry entry = visibleEntries.get(selection);
        if (page == Page.DECK && cardDeck != null) {
            NavigationCard card = cardDeck.getCardSnapshot(selection);
            RectF bounds = cardDeck.getCardBounds(selection);
            if (card != null && !bounds.isEmpty()) {
                activeFeatureId = entry.id;
                beginCardOpen(card, bounds, entry);
                return;
            }
        }
        entry.action.run();
    }

    @Override
    public void onBackPressed() {
        if (cardTransition != null) { cancelCardTransition(true); return; }
        if (cameraScreen != null) { cameraScreen.onBackPressed(); return; }
        if (quickSettings.isVisible()) { quickSettings.dismiss(); return; }
        if (recorderOverlay.isVisible()) {
            if (recorderOverlay.handleBack()) return;
            recorderOverlay.abortAndDismiss(); returnFromFeature("recorder"); return;
        }
        if (hardwarePage != null && hardwarePage.handleBack()) return;
        hideKeyboard(); savedSelection[page.ordinal()] = selection;
        if (page == Page.UTILITIES) showApps();
        else if (page == Page.DECK || page == Page.HOME) showHome();
        else returnFromFeature(activeFeatureId);
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
        if (cardTransition != null && !cardTransition.isRevealing()) cancelCardTransition(true);
        if (cameraScreen != null) return;
        controlsHandler.removeCallbacks(timerTick);
        setHardwarePage(null);
        cameraReturnHome = page == Page.HOME || page == Page.IDLE;
        visitFeature("camera");
        recorderOverlay.abortAndDismiss(); quickSettings.dismiss();
        gestures.cancel(); hardware.stop();
        page = Page.CAMERA;
        homeVisual = null; cardDeck = null; navigationSurface = null;
        clockView = dateView = statusView = null; batteryIcon = null; scrollView = null;
        visibleEntries.clear(); selectableViews.clear();
        cameraScreen = new CameraScreen(this, new CameraScreen.Host() {
            @Override public void onBack() { returnFromFeature("camera"); }
            @Override public void onInteraction() { cancelCardTransition(true); }
            @Override public void onDoubleBack() {
                if (cameraReturnHome) showHome(); else showDeck(false);
            }
            @Override public void onHome() { showHome(); }
            @Override public void onKeyboard() { showKeyboard(); }
            @Override public void onSettings() { showSettings(); }
        });
        setContentView(cameraScreen.getView()); configureWindow();
        if (resumed) cameraScreen.onResume();
    }

    private void releaseCameraScreen() {
        CameraScreen old = cameraScreen;
        cameraScreen = null;
        if (old != null) old.release();
    }

    private void openMusic() {
        Intent musicCategory = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.APP_MUSIC");
        if (launchPackageQuietly("org.akanework.gramophone")
                || launchPackage("com.android.music", musicCategory, "No music app is available")) visitFeature("music");
    }

    private boolean launchPackage(String packageName, Intent fallback, String error) {
        return launchPackage(packageName, fallback, null, error);
    }

    private boolean launchPackage(String packageName, Intent fallbackOne, Intent fallbackTwo,
            String error) {
        if (launchPackageQuietly(packageName)) return true;
        if (launchIntentQuietly(fallbackOne)) return true;
        if (launchIntentQuietly(fallbackTwo)) return true;
        showError(error);
        return false;
    }

    private boolean launchPackageQuietly(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        return launchIntentQuietly(launch);
    }

    private boolean launchIntent(Intent intent, String error) {
        if (launchIntentQuietly(intent)) return true;
        showError(error);
        return false;
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
            returningFromApp = true;
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
        if (homeVisual != null) homeVisual.setStatus(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now), batteryPercent);
        if (clockView != null) clockView.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        if (dateView != null) dateView.setText(new SimpleDateFormat("EEEE · MMM d", Locale.getDefault()).format(now));
    }

    private void updateStatus() {
        if (homeVisual != null) updateClock();
        if (batteryIcon != null) { batteryIcon.setContentDescription("Battery " + batteryPercent + " percent"); batteryIcon.invalidate(); }
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
