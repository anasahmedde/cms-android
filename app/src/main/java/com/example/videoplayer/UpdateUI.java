package com.example.videoplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.util.Log;
import android.widget.Toast;

/**
 * Ready-to-use dialogs wired to AppUpdater.
 *
 * Usage (inside an Activity):
 *
 *   AppUpdater updater = new AppUpdater(this, BuildConfig.VERSION_CODE);
 *   UpdateUI ui = new UpdateUI(this);
 *   ui.attach(updater);
 *   updater.checkForUpdate();
 */
public class UpdateUI implements AppUpdater.UpdateListener {

    private static final String TAG = "UpdateUI";

    private final Activity activity;
    private AppUpdater updater;
    private ProgressDialog progressDialog;

    public UpdateUI(Activity activity) {
        this.activity = activity;
    }

    /** Wire this UI to an AppUpdater instance. */
    public void attach(AppUpdater appUpdater) {
        this.updater = appUpdater;
        appUpdater.setListener(this);
    }

    // ── AppUpdater.UpdateListener ─────────────────────────────────────────────

    @Override
    public void onUpdateAvailable(int newVersionCode, String newVersionName,
                                  String releaseNotes, boolean forceUpdate) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        String message = "Version " + newVersionName + " is available.\n\n" + releaseNotes;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Update Available")
                .setMessage(message)
                .setCancelable(!forceUpdate);

        builder.setPositiveButton(forceUpdate ? "Update Now" : "Update", (d, w) -> {
            d.dismiss();
            showDownloadProgress();
            updater.startDownload();
        });

        if (!forceUpdate) {
            builder.setNegativeButton("Later", (d, w) -> d.dismiss());
        }

        builder.show();
    }

    @Override
    public void onDownloadProgress(int percent) {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.setProgress(percent);
        }
    }

    @Override
    public void onInstallReady() {
        dismissProgress();
        // Android's package installer dialog takes over from here.
    }

    @Override
    public void onError(String message) {
        Log.e(TAG, "Update error: " + message);
        dismissProgress();
        // A failed CHECK is routine on an offline signage screen (no DNS/route)
        // and would toast over the public content on every retry — log only.
        // Real download/install failures still surface for the on-site tech.
        if (message != null && message.startsWith("Update check failed")) return;
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            Toast.makeText(activity, "Update error: " + message, Toast.LENGTH_LONG).show();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void showDownloadProgress() {
        progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("Downloading Update");
        progressDialog.setMessage("Please wait…");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.show();
    }

    private void dismissProgress() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        progressDialog = null;
    }
}
