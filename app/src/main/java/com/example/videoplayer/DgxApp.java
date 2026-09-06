package com.example.videoplayer;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Process-level supervisor. Before this class existed, any uncaught exception
 * was permanent screen death: the process died, nothing relaunched it, and the
 * fleet operator learned nothing (no crash reporting of any kind).
 *
 * A crash now: (1) schedules a relaunch FIRST (so nothing later in the handler
 * can lose it), (2) records stats reported through the existing heartbeat.
 *
 * Relaunch pacing (adversarial-review hardened):
 *  - First crash: relaunch in {@link #RELAUNCH_DELAY_MS}.
 *  - A second crash within ~2 minutes: wait {@link #BAD_PROCESS_DELAY_MS} —
 *    AOSP marks a process "bad" after 2 rapid crashes and silently refuses
 *    background starts for ~60 s; pacing past that window keeps our alarms
 *    effective.
 *  - >= {@link #SAFE_MODE_THRESHOLD} crashes within {@link #LOOP_WINDOW_MS}:
 *    safe mode LATCHES for {@link #SAFE_MODE_HOLD_MS} (it does not oscillate
 *    back to fast pacing) — a poison build degrades to a blink every few
 *    minutes instead of a hot loop.
 *
 * Relaunch transport: alarm-started BootLaunchService (the proven boot-path
 * vehicle; survives our process death) plus a direct activity alarm. On
 * Android 10+ the direct start needs the overlay permission
 * (SYSTEM_ALERT_WINDOW, "Display over other apps") — granted once at
 * provisioning; measured on API 34: with it the relaunch lands
 * (BAL_ALLOW_SAW_PERMISSION), without it the OS blocks background activity
 * starts while the screen is on. The heartbeat reports overlay_ok so
 * un-provisioned boxes are visible in the dashboard. Android <= 9 boxes need
 * nothing.
 */
public class DgxApp extends Application {

    private static final String TAG = "DgxApp";
    static final String CRASH_PREFS = "dgx_crash_stats";
    static final String K_COUNT_TOTAL = "crash_count_total";     // monotonic gauge, reported as-is
    static final String K_LAST_AT = "last_crash_at";
    static final String K_LAST_MSG = "last_crash_msg";
    static final String K_RECENT_TIMES = "recent_crash_times";   // csv of epoch ms
    static final String K_SAFE_UNTIL = "safe_mode_until";

    private static final int SAFE_MODE_THRESHOLD = 5;
    private static final long LOOP_WINDOW_MS = 10 * 60_000L;
    private static final long RELAUNCH_DELAY_MS = 3_000L;
    private static final long BAD_PROCESS_DELAY_MS = 90_000L;   // > AOSP 60s bad-process window
    private static final long SAFE_MODE_DELAY_MS = 5 * 60_000L;
    private static final long SAFE_MODE_HOLD_MS = 60 * 60_000L; // latched, no oscillation
    private static final int REQ_SERVICE = 4242;
    private static final int REQ_ACTIVITY = 4243;

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            // Each step is independently guarded: a failure while computing the
            // delay or recording stats must never cost the relaunch (the review
            // found the original single try/catch dropped the relaunch whenever
            // recordCrash threw — e.g. under the very OOM it should survive).
            long delay = RELAUNCH_DELAY_MS;
            try { delay = planRelaunchDelay(); } catch (Throwable ignored) { }
            try { scheduleRelaunch(delay); } catch (Throwable ignored) { }
            try { recordCrashStats(e); } catch (Throwable ignored) { }
            Log.e(TAG, "FATAL crash — relaunching in " + delay + "ms", e);
            if (previous != null) {
                previous.uncaughtException(thread, e); // keep system logging/bookkeeping
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /**
     * Delay for THIS relaunch, from persisted history (reads only; the write
     * happens in recordCrashStats afterwards). Latches safe mode.
     */
    private long planRelaunchDelay() {
        SharedPreferences p = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now < p.getLong(K_SAFE_UNTIL, 0)) return SAFE_MODE_DELAY_MS;

        int inWindow = 1; // this crash
        long newest = 0;
        for (String s : p.getString(K_RECENT_TIMES, "").split(",")) {
            try {
                long t = Long.parseLong(s.trim());
                if (now - t <= LOOP_WINDOW_MS) inWindow++;
                if (t > newest) newest = t;
            } catch (NumberFormatException ignored) { }
        }
        if (inWindow >= SAFE_MODE_THRESHOLD) {
            p.edit().putLong(K_SAFE_UNTIL, now + SAFE_MODE_HOLD_MS).commit();
            return SAFE_MODE_DELAY_MS;
        }
        // Second rapid crash: outwait the OS bad-process suppression window.
        if (newest > 0 && now - newest < 2 * 60_000L) return BAD_PROCESS_DELAY_MS;
        return RELAUNCH_DELAY_MS;
    }

    private void recordCrashStats(Throwable e) {
        SharedPreferences p = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE);
        long now = System.currentTimeMillis();
        StringBuilder recent = new StringBuilder();
        for (String s : p.getString(K_RECENT_TIMES, "").split(",")) {
            try {
                long t = Long.parseLong(s.trim());
                if (now - t <= LOOP_WINDOW_MS) {
                    if (recent.length() > 0) recent.append(',');
                    recent.append(t);
                }
            } catch (NumberFormatException ignored) { }
        }
        if (recent.length() > 0) recent.append(',');
        recent.append(now);

        String msg = e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : "");
        if (msg.length() > 200) msg = msg.substring(0, 200);

        p.edit()
                .putInt(K_COUNT_TOTAL, p.getInt(K_COUNT_TOTAL, 0) + 1)
                .putLong(K_LAST_AT, now)
                .putString(K_LAST_MSG, msg)
                .putString(K_RECENT_TIMES, recent.toString())
                .commit(); // synchronous — the process dies right after this
    }

    private void scheduleRelaunch(long delayMs) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = System.currentTimeMillis() + delayMs;

        // Layer 1: alarm-start BootLaunchService — the proven boot-path vehicle
        // (foreground + full-screen intent). The alarm survives process death.
        Intent svc = new Intent(this, BootLaunchService.class);
        svc.putExtra("boot_action", "crash_relaunch");
        PendingIntent svcPi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(this, REQ_SERVICE, svc,
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getService(this, REQ_SERVICE, svc,
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        setAlarm(am, at, svcPi);

        // Layer 2: direct activity alarm a few seconds later — works on
        // Android <= 9 outright and on 10+ with the overlay permission.
        // Canceled by the activity once it is actually up (see
        // cancelRelaunchAlarms) so it can't CLEAR_TASK a healthy relaunch.
        setAlarm(am, at + 8_000L, activityPi());
    }

    private PendingIntent activityPi() {
        Intent act = new Intent(this, FullScreenPlayerActivity.class);
        act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getActivity(this, REQ_ACTIVITY, act,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Version/permission-guarded exact-ish alarm (minSdk 21; exact opt-out on 31+). */
    private static void setAlarm(AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            try { am.set(AlarmManager.RTC_WAKEUP, at, pi); } catch (Exception ignored) { }
        }
    }

    /**
     * Called by FullScreenPlayerActivity once it is up: a successful launch
     * must cancel the pending layer-2 alarm, or it fires ~8s later and
     * CLEAR_TASKs the healthy activity mid-initialization.
     */
    static void cancelRelaunchAlarms(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent act = new Intent(ctx, FullScreenPlayerActivity.class);
            act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent actPi = PendingIntent.getActivity(ctx, REQ_ACTIVITY, act,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (actPi != null) { am.cancel(actPi); actPi.cancel(); }
        } catch (Exception ignored) { }
    }
}
