package com.example.videoplayer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Receives BOOT_COMPLETED / MY_PACKAGE_REPLACED and chains into BootLaunchService,
 * which in turn opens FullScreenPlayerActivity.
 *
 * Three-layer strategy to guarantee auto-start on every Android version (5 → 16):
 *
 *   Layer 1 – Foreground service with full-screen-intent notification (BootLaunchService).
 *             Works on Android 8-13 reliably; on 14-16 the full-screen intent fires
 *             if the USE_FULL_SCREEN_INTENT permission is granted (auto-granted on
 *             first install but the user can revoke it).
 *
 *   Layer 2 – AlarmManager exact alarm set for 6 seconds from now.
 *             AlarmManager is one of the few mechanisms that CAN start an activity
 *             from the background because it qualifies as a "user-visible alarm".
 *             This acts as a fallback if the service path is killed.
 *
 *   Layer 3 – Direct startActivity() from the receiver (works on Android ≤9).
 *
 * A partial wake-lock is acquired for 60 s to keep the CPU alive through the chain.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.d(TAG, "Received: " + action);

        // ── Skip LOCKED_BOOT_COMPLETED ───────────────────────────────────────
        // Direct-boot mode — credential-encrypted storage is not available yet,
        // and FullScreenPlayerActivity is not directBootAware.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Log.d(TAG, "LOCKED_BOOT_COMPLETED — skipping (direct boot mode)");
            return;
        }

        boolean isBoot =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!isBoot) return;

        // ── Acquire a partial wake-lock ──────────────────────────────────────
        acquireWakeLock(context);

        // ── Layer 1: Start BootLaunchService (foreground + full-screen intent) ──
        try {
            Intent serviceIntent = new Intent(context, BootLaunchService.class);
            serviceIntent.putExtra("boot_action", action);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d(TAG, "Layer 1: BootLaunchService started");
        } catch (Exception e) {
            Log.e(TAG, "Layer 1 failed: " + e.getMessage());
        }

        // ── Layer 2: Schedule an exact alarm as a fallback ───────────────────
        scheduleAlarmFallback(context);

        // ── Layer 3: Direct startActivity (works on Android ≤ 9) ─────────────
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                Intent launch = new Intent(context, FullScreenPlayerActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(launch);
                Log.d(TAG, "Layer 3: direct startActivity succeeded");
            } catch (Exception e) {
                Log.w(TAG, "Layer 3 failed (expected on Android 10+): " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void acquireWakeLock(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "DGXPlayer:BootReceiverWake");
                wl.acquire(60_000); // auto-released after 60 s
                Log.d(TAG, "Wake lock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "Wake lock failed: " + e.getMessage());
        }
    }

    /**
     * Set an exact alarm for ~6 seconds from now that fires a PendingIntent
     * which starts FullScreenPlayerActivity.
     *
     * On Android 12+ exact alarms need SCHEDULE_EXACT_ALARM permission (which
     * is auto-granted for most clock/alarm apps).  If we don't have it, we
     * fall back to setAndAllowWhileIdle which may be delayed a few seconds by
     * Doze but still fires.
     */
    private void scheduleAlarmFallback(Context context) {
        try {
            Intent launch = new Intent(context, FullScreenPlayerActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(context, 1001, launch, piFlags);

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            long triggerAt = SystemClock.elapsedRealtime() + 6_000; // 6 seconds

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: check if we can schedule exact alarms
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                    Log.d(TAG, "Layer 2: exact alarm scheduled (6 s)");
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                    Log.d(TAG, "Layer 2: inexact alarm scheduled (6 s, no exact-alarm perm)");
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                Log.d(TAG, "Layer 2: exact alarm scheduled (6 s)");
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME, triggerAt, pi);
                Log.d(TAG, "Layer 2: exact alarm scheduled (6 s)");
            }
        } catch (Exception e) {
            Log.w(TAG, "Layer 2 alarm scheduling failed: " + e.getMessage());
        }
    }
}