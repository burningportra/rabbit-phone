package com.kevtrinh.rabbitphone;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Non-exported receiver: token-bound expiry or Android restoration broadcasts only. */
public final class TimerReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean expiry = TimerStore.ACTION_EXPIRED.equals(action);
        boolean restore = Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action);
        if (!expiry && !restore) return;
        String token = intent.getStringExtra(TimerStore.EXTRA_TOKEN);
        if (expiry && (token == null || !token.matches("[a-f0-9]{32}"))) return;
        try {
            TimerStore store = new TimerStore(context);
            if (expiry) store.handleExpiry(token);
            else store.reconcile();
        } catch (RuntimeException unavailable) {
            Log.w("RabbitPhoneTimer", "Timer recovery unavailable: " + unavailable.getMessage());
        }
    }
}
