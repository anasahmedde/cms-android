package com.example.videoplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * Bridges the gap between BootReceiver and FullScreenPlayerActivity.
 *
 * Strategy (handles Android 5 through 16 / targetSdk 36):
 *
 *  1. Immediately promote to foreground with a FULL-SCREEN INTENT notification.
 *     → On the lock screen / screen-off the system launches the activity through
 *       the full-screen-intent path (same mechanism as alarms & incoming calls).
 *     → This is the ONLY officially supported way to start an activity from the
 *       background on Android 10+ that is NOT blocked.
 *
 *  2. Acquire FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP to force the screen on.
 *
 *  3. After a delay (for display/audio init) call startActivity() as belt-and-
 *     suspenders.  Retry up to 3 times on failure.
 *
 *  4. Keep the service alive for a few more seconds so the process isn't killed
 *     before the activity is fully in the foreground.
 */
public class BootLaunchService extends Service {

    private static final String TAG = "BootLaunchService";
    private static final String CHANNEL_ID = "dgx_boot_channel";
    private static final int NOTIF_ID = 1;

    private static final long LAUNCH_DELAY_MS = 5_000;   // wait for display stack
    private static final long STOP_DELAY_MS   = 10_000;  // keep alive after launch
    private static final long RETRY_DELAY_MS  = 3_000;   // between retries
    private static final int  MAX_RETRIES     = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private int retryCount = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand — launching activity in " + LAUNCH_DELAY_MS + " ms");

        // 1. Acquire a wake lock to force the screen on
        acquireWakeLock();

        // 2. Promote to foreground with full-screen intent
        createNotificationChannel();
        try {
            startForeground(NOTIF_ID, buildFullScreenNotification());
        } catch (Exception e) {
            // On some devices startForeground can fail with a RemoteException
            // if the notification channel isn't ready yet.  Fall through —
            // the alarm fallback in BootReceiver will cover us.
            Log.e(TAG, "startForeground failed: " + e.getMessage());
        }

        // 3. Delayed explicit startActivity as secondary mechanism
        handler.postDelayed(this::attemptLaunch, LAUNCH_DELAY_MS);

        return START_NOT_STICKY;
    }

    // ── Activity launch with retry ───────────────────────────────────────────

    private void attemptLaunch() {
        retryCount++;
        Log.d(TAG, "attemptLaunch — try " + retryCount + "/" + MAX_RETRIES);
        try {
            Intent launch = new Intent(this, FullScreenPlayerActivity.class);
            launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(launch);
            Log.d(TAG, "startActivity() succeeded");

            // Schedule self-stop after the activity has time to reach foreground
            handler.postDelayed(this::cleanupAndStop, STOP_DELAY_MS);

        } catch (Exception e) {
            Log.e(TAG, "startActivity failed (try " + retryCount + "): " + e.getMessage());
            if (retryCount < MAX_RETRIES) {
                handler.postDelayed(this::attemptLaunch, RETRY_DELAY_MS);
            } else {
                Log.e(TAG, "All retries exhausted");
                cleanupAndStop();
            }
        }
    }

    // ── Wake lock ────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;

            // FULL_WAKE_LOCK is deprecated but still works and is the only way
            // to guarantee the screen turns on from a service context.
            // SCREEN_BRIGHT_WAKE_LOCK is an alternative but doesn't force the
            // screen on from a fully-off state on all OEMs.
            wakeLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "DGXPlayer:BootServiceWake");
            wakeLock.acquire(90_000); // auto-released after 90 s
            Log.d(TAG, "Full wake lock acquired — screen should turn on");
        } catch (Exception e) {
            Log.w(TAG, "Wake lock failed: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "Wake lock released");
            }
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    private void cleanupAndStop() {
        Log.d(TAG, "cleanupAndStop");
        releaseWakeLock();
        handler.removeCallbacksAndMessages(null);
        try {
            stopForeground(true);
        } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        releaseWakeLock();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Notification ─────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Boot Launch", NotificationManager.IMPORTANCE_HIGH);
            // IMPORTANCE_HIGH is required for full-screen intents to fire
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.enableLights(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    /**
     * Build a notification with a full-screen intent.
     *
     * When the screen is off or the keyguard is showing, Android honours the
     * full-screen intent and launches the target activity immediately — no user
     * interaction required.  This is the same mechanism alarm and phone apps use.
     *
     * USE_FULL_SCREEN_INTENT is auto-granted on install for apps targeting
     * Android 14+.  The user can revoke it in Settings → Apps → Special access
     * → Full-screen intent, but that's very rare for kiosk-style apps.
     */
    private Notification buildFullScreenNotification() {
        Intent launch = new Intent(this, FullScreenPlayerActivity.class);
        launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent fullScreenPi = PendingIntent.getActivity(this, 0, launch, piFlags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("DGX Player")
                    .setContentText("Starting…")
                    .setFullScreenIntent(fullScreenPi, true)
                    .setContentIntent(fullScreenPi)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build();
        } else {
            //noinspection deprecation
            return new Notification.Builder(this)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("DGX Player")
                    .setContentText("Starting…")
                    .setFullScreenIntent(fullScreenPi, true)
                    .setContentIntent(fullScreenPi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_MAX)
                    .build();
        }
    }
}