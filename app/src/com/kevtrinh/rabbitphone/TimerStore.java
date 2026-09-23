package com.kevtrinh.rabbitphone;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Durable single-timer owner shared by the UI and the same-process receiver. */
public final class TimerStore {
    public static final String ACTION_EXPIRED = "com.kevtrinh.rabbitphone.TIMER_EXPIRED";
    public static final String EXTRA_TOKEN = "timer_generation";
    private static final String CHANNEL = "rabbit-timer";
    private static final int NOTIFICATION_ID = 9041;
    private static final Object LOCK = new Object();

    public static final class Snapshot {
        public final TimerState.Phase phase;
        public final long durationMs, remainingMs;
        public final String token;
        private Snapshot(TimerState state, long remaining) {
            phase = state.phase; durationMs = state.durationMs;
            remainingMs = remaining; token = state.token;
        }
    }

    private final Context context;
    private final AlarmManager alarms;
    private final NotificationManager notifications;
    private final AtomicFile file;

    public TimerStore(Context context) {
        this.context = context.getApplicationContext();
        alarms = this.context.getSystemService(AlarmManager.class);
        notifications = this.context.getSystemService(NotificationManager.class);
        file = new AtomicFile(new File(this.context.getFilesDir(), "timer-state.json"));
        synchronized (LOCK) { ensureChannel(); }
    }

    public boolean canSchedule() { return schedulingProblem() == null; }

    /** Null means ready. User-controlled channel sound/mute preferences remain authoritative. */
    public String schedulingProblem() {
        synchronized (LOCK) {
            if (alarms == null) return "Android's alarm service is unavailable";
            if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms())
                return "Allow Alarms & reminders for Rabbit Phone in Android settings";
            String notification = notificationProblem();
            if (notification != null) return notification;
            if (Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1) < 0)
                return "Android's boot counter is unavailable; timer recovery cannot be guaranteed";
            return null;
        }
    }

    public Snapshot snapshot() {
        synchronized (LOCK) { return reconcileLocked(false, false); }
    }

    public Snapshot start(long durationMs) {
        TimerState.validateDuration(durationMs);
        synchronized (LOCK) {
            requireScheduling();
            TimerState old = read();
            TimerState.Time now = now();
            TimerState next = TimerState.start(durationMs, token(), now);
            installRunning(old, next);
            return new Snapshot(next, next.remainingMs(now));
        }
    }

    public Snapshot pause() {
        synchronized (LOCK) {
            TimerState old = read();
            if (old.phase != TimerState.Phase.RUNNING)
                return new Snapshot(deliverIfNeeded(old), old.remainingAtAnchorMs);
            TimerState.Time now = now();
            TimerState next = old.pause(token(), now);
            if (next != old) { write(next); cancelAlarm(old.token); }
            return new Snapshot(deliverIfNeeded(next), next.remainingMs(now));
        }
    }

    public Snapshot resume() {
        synchronized (LOCK) {
            TimerState old = read();
            if (old.phase != TimerState.Phase.PAUSED) return reconcileLocked(false, false);
            requireScheduling();
            TimerState.Time now = now();
            TimerState next = old.resume(token(), now);
            installRunning(old, next);
            return new Snapshot(next, next.remainingMs(now));
        }
    }

    public Snapshot cancel() {
        synchronized (LOCK) {
            TimerState old = read();
            TimerState next = old.cancel(token());
            write(next); // A late delivery is stale even if cancellation races Android.
            cancelAlarm(old.token);
            if (notifications != null) notifications.cancel(NOTIFICATION_ID);
            return new Snapshot(next, 0);
        }
    }

    /** Restore after boot/package update/permission grant, or re-anchor a changed wall clock. */
    public Snapshot reconcile() {
        synchronized (LOCK) { return reconcileLocked(true, true); }
    }

    void handleExpiry(String alarmToken) {
        synchronized (LOCK) {
            TimerState old = read();
            if (!old.token.equals(alarmToken)) return;
            TimerState.Time now = now();
            TimerState next = old.expire(alarmToken, now);
            if (next != old) write(next);
            if (next.phase == TimerState.Phase.RUNNING) {
                requireScheduling();
                schedule(next); // An early callback must never complete a timer.
            } else {
                cancelAlarm(old.token);
                deliverIfNeeded(next);
            }
        }
    }

    private Snapshot reconcileLocked(boolean reschedule, boolean wallChanged) {
        TimerState old = read();
        if (old.phase != TimerState.Phase.RUNNING)
            return new Snapshot(deliverIfNeeded(old), old.remainingAtAnchorMs);
        TimerState.Time now = now();
        TimerState next = old.reconcile(now, wallChanged);
        if (next.phase == TimerState.Phase.RUNNING && (reschedule || next != old)) {
            requireScheduling();
            schedule(next);
        }
        if (next != old) write(next);
        if (old.phase == TimerState.Phase.RUNNING && next.phase != TimerState.Phase.RUNNING) cancelAlarm(old.token);
        return new Snapshot(deliverIfNeeded(next), next.remainingMs(now));
    }

    private void installRunning(TimerState old, TimerState next) {
        // Schedule the new token before replacing storage. A crash before commit
        // leaves the old timer intact; the uncommitted callback is safely stale.
        schedule(next);
        try { write(next); }
        catch (RuntimeException failure) { cancelAlarm(next.token); throw failure; }
        cancelAlarm(old.token);
        if (notifications != null) notifications.cancel(NOTIFICATION_ID);
    }

    private void requireScheduling() {
        String problem = schedulingProblem();
        if (problem != null) throw new IllegalStateException(problem);
    }

    private void schedule(TimerState state) {
        PendingIntent operation = alarmIntent(state.token, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    state.deadlineElapsedMs, operation);
        } catch (SecurityException denied) {
            operation.cancel();
            throw new IllegalStateException("Allow Alarms & reminders for Rabbit Phone in Android settings", denied);
        }
    }

    private PendingIntent alarmIntent(String token, int flags) {
        Intent intent = new Intent(context, TimerReceiver.class).setAction(ACTION_EXPIRED)
                .setData(new Uri.Builder().scheme("rabbit-timer").authority("expiry").appendPath(token).build())
                .putExtra(EXTRA_TOKEN, token);
        return PendingIntent.getBroadcast(context, 0, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private void cancelAlarm(String token) {
        if (token.isEmpty()) return;
        PendingIntent pending = alarmIntent(token, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            if (alarms != null) alarms.cancel(pending);
            pending.cancel();
        }
    }

    private String notificationProblem() {
        if (notifications == null) return "Android's notification service is unavailable";
        if ((Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) || !notifications.areNotificationsEnabled())
            return "Allow notifications for Rabbit Phone before starting a timer";
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
            return "Enable Rabbit Phone's Timers notification channel in Android settings";
        return null;
    }

    private void ensureChannel() {
        if (notifications == null || notifications.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Timers", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Completion alerts for timers you start on this Rabbit");
        channel.setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        channel.enableVibration(true);
        notifications.createNotificationChannel(channel);
    }

    private TimerState deliverIfNeeded(TimerState state) {
        if (!state.needsNotification() || notificationProblem() != null) return state;
        PendingIntent open = PendingIntent.getActivity(context, 0, NavigationIntents.timer(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long seconds = state.durationMs / 1000;
        String duration = String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        Notification note = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Time's up")
                .setContentText(duration + " timer finished").setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build();
        // A retry after a crash reposts the same ID without a second alert.
        // Record delivery after Android accepts it, so a pre-post crash can retry.
        notifications.notify(NOTIFICATION_ID, note);
        TimerState delivered = state.markNotified();
        write(delivered);
        return delivered;
    }

    private TimerState.Time now() {
        return new TimerState.Time(SystemClock.elapsedRealtime(), System.currentTimeMillis(),
                Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
    }

    private static String token() { return UUID.randomUUID().toString().replace("-", ""); }

    private TimerState read() {
        try (FileInputStream input = file.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 4096) throw new IOException("Oversized timer state");
                bytes.write(buffer, 0, count);
            }
            JSONObject state = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (state.getInt("version") != 1) throw new IOException("Unknown timer version");
            return TimerState.restore(TimerState.Phase.valueOf(state.getString("phase")), state.getLong("duration"),
                    state.getLong("remaining"), state.getLong("elapsed_deadline"), state.getLong("wall_deadline"),
                    state.getInt("boot"), state.getString("token"), state.getBoolean("notified"));
        } catch (FileNotFoundException absent) {
            if (!file.getBaseFile().exists()) return TimerState.empty();
            throw new IllegalStateException("Timer storage could not be read", absent);
        } catch (IOException | JSONException | IllegalArgumentException invalid) {
            throw new IllegalStateException("Timer storage could not be read", invalid);
        }
    }

    private void write(TimerState state) {
        FileOutputStream output = null;
        try {
            JSONObject value = new JSONObject().put("version", 1).put("phase", state.phase.name())
                    .put("duration", state.durationMs).put("remaining", state.remainingAtAnchorMs)
                    .put("elapsed_deadline", state.deadlineElapsedMs).put("wall_deadline", state.deadlineWallMs)
                    .put("boot", state.bootCount).put("token", state.token).put("notified", state.notified);
            output = file.startWrite();
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            file.finishWrite(output);
            output = null;
            // AtomicFile can log a failed final rename without throwing. Do not
            // report a running timer until the committed generation reads back.
            TimerState stored = read();
            if (stored.phase != state.phase || !stored.token.equals(state.token)
                    || stored.durationMs != state.durationMs || stored.remainingAtAnchorMs != state.remainingAtAnchorMs
                    || stored.deadlineElapsedMs != state.deadlineElapsedMs || stored.deadlineWallMs != state.deadlineWallMs
                    || stored.bootCount != state.bootCount || stored.notified != state.notified)
                throw new IllegalStateException("Timer storage could not be verified");
        } catch (IOException | JSONException failure) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("Timer storage could not be saved", failure);
        }
    }
}
