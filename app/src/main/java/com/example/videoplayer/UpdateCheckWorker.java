package com.example.videoplayer;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager worker that runs a silent update check every 6 hours.
 * WorkManager ensures it runs even when the app is in the background or
 * was killed by the system — as long as the device has network.
 *
 * The check itself is in AppUpdater. This worker just triggers it.
 * The UI for "update available" is shown the next time the app is foregrounded
 * (AppUpdater is re-initialized in FullScreenPlayerActivity.onCreate).
 */
public class UpdateCheckWorker extends Worker {

    private static final String TAG = "UpdateCheckWorker";
    private static final String WORK_NAME = "dgx_update_check";

    public UpdateCheckWorker(@NonNull Context context,
                             @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Background update check running…");
        // Run check synchronously via the shared helper (no UI listener here)
        try {
            // AppUpdater.checkForUpdate() is async internally; we call the
            // network part inline so WorkManager knows when we're done.
            UpdateCheckHelper.checkVersionSync(getApplicationContext());
            Log.d(TAG, "Background update check complete.");
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Background update check failed: " + e.getMessage());
            return Result.retry();
        }
    }

    /**
     * Schedule a periodic update check every 6 hours, only when network is available.
     * Safe to call multiple times — WorkManager deduplicates by WORK_NAME.
     */
    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                UpdateCheckWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request);

        Log.d(TAG, "Periodic update check scheduled (every 6 h).");
    }
}
