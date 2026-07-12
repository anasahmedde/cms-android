package com.example.videoplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.DataOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Flushes locally buffered reed-switch counts to the backend.
 *
 * Enqueue via ReedSyncWorker.enqueue(context) — WorkManager will run it as
 * soon as the device has a network connection, even if the app is in the
 * background or the device rebooted since the counts were saved.
 *
 * The server treats each POST as "+1", so we send one request per pending
 * count and decrement the local store only after the request succeeds.
 * This means partial syncs are safe: whatever wasn't sent stays buffered.
 */
public class ReedSyncWorker extends Worker {

    private static final String TAG      = "ReedSyncWorker";
    private static final String WORK_TAG = "reed_count_sync";

    private static final String API_BASE   = "https://api-staging-cms.wizioners.com";
    private static final int    TIMEOUT_MS = 10_000;

    public ReedSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx   = getApplicationContext();
        String  devId = getDeviceId(ctx);
        if (devId == null) {
            Log.w(TAG, "No device ID — retrying later");
            return Result.retry();
        }

        ReedCountStore store = new ReedCountStore(ctx);

        boolean allOk = true;

        // ── Daily counts ─────────────────────────────────────────────────
        String dailyUrl = API_BASE + "/device/" + devId + "/daily_update";
        int pendingDaily = store.getPendingDaily();
        for (int i = 0; i < pendingDaily; i++) {
            if (post(dailyUrl, "daily_count")) {
                store.decrementDaily();
                Log.d(TAG, "daily synced (" + (i + 1) + "/" + pendingDaily + ")");
            } else {
                Log.w(TAG, "daily POST failed at " + (i + 1) + "/" + pendingDaily + " — will retry");
                allOk = false;
                break;  // network is gone; remaining counts stay buffered
            }
        }

        // ── Monthly counts ───────────────────────────────────────────────
        String monthlyUrl = API_BASE + "/device/" + devId + "/monthly_update";
        int pendingMonthly = store.getPendingMonthly();
        for (int i = 0; i < pendingMonthly; i++) {
            if (post(monthlyUrl, "monthly_count")) {
                store.decrementMonthly();
                Log.d(TAG, "monthly synced (" + (i + 1) + "/" + pendingMonthly + ")");
            } else {
                Log.w(TAG, "monthly POST failed at " + (i + 1) + "/" + pendingMonthly + " — will retry");
                allOk = false;
                break;
            }
        }

        // Result.retry() makes WorkManager re-run with exponential back-off
        // the next time the network constraint is satisfied.
        return allOk ? Result.success() : Result.retry();
    }

    // ── HTTP helper ──────────────────────────────────────────────────────

    private boolean post(String url, String key) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
                out.write(("{\"" + key + "\": 0}").getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressLint("HardwareIds")
    private String getDeviceId(Context ctx) {
        try {
            return Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Enqueue helper ───────────────────────────────────────────────────

    /**
     * Schedule a one-time sync that runs as soon as the network is available.
     * Safe to call repeatedly — KEEP policy avoids duplicate workers.
     */
    public static void enqueue(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ReedSyncWorker.class)
                .setConstraints(constraints)
                .addTag(WORK_TAG)
                .build();

        WorkManager.getInstance(context)
                   .enqueueUniqueWork(WORK_TAG, ExistingWorkPolicy.KEEP, work);

        Log.d(TAG, "Sync enqueued (runs when connected)");
    }
}
