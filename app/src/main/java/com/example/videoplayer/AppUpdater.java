package com.example.videoplayer;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Handles self-update from S3.
 *
 * Flow:
 *   checkForUpdate() → fetch version.json → compare versionCode
 *     → if newer: notify listener
 *       → startDownload() → DownloadManager → on complete: verifyAndInstall()
 */
public class AppUpdater {

    public interface UpdateListener {
        /** Called on the main thread when a new version is available. */
        void onUpdateAvailable(int newVersionCode, String newVersionName,
                               String releaseNotes, boolean forceUpdate);

        /** Called on the main thread with download progress 0–100. */
        void onDownloadProgress(int percent);

        /** Called on the main thread when the APK is ready and install dialog is shown. */
        void onInstallReady();

        /** Called on the main thread if anything fails. */
        void onError(String message);
    }

    private static final String TAG = "AppUpdater";

    // ── Public config ────────────────────────────────────────────────────────
    /** URL to your version.json on S3 (must be publicly readable). */
    public static final String VERSION_JSON_URL =
            "https://dgx-release-bucket.s3.us-east-2.amazonaws.com/version.json";

    // ── Internals ─────────────────────────────────────────────────────────────
    private final Context ctx;
    private final int currentVersionCode;
    private UpdateListener listener;

    private long downloadId = -1;
    private BroadcastReceiver downloadReceiver;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public AppUpdater(Context context, int currentVersionCode) {
        this.ctx = context.getApplicationContext();
        this.currentVersionCode = currentVersionCode;
    }

    public void setListener(UpdateListener listener) {
        this.listener = listener;
    }

    // ── Check ─────────────────────────────────────────────────────────────────

    /** Fetch version.json from S3 in a background thread and compare with current build. */
    public void checkForUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(VERSION_JSON_URL).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "version.json returned HTTP " + code);
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                int remoteCode    = json.getInt("versionCode");
                String remoteName = json.optString("versionName", "");
                String apkUrl     = json.getString("apkUrl");
                String checksum   = json.optString("checksum", "");
                String notes      = json.optString("releaseNotes", "");
                boolean force     = json.optBoolean("forceUpdate", false);

                Log.d(TAG, "Remote versionCode=" + remoteCode +
                        "  current=" + currentVersionCode);

                if (remoteCode <= currentVersionCode) {
                    Log.d(TAG, "App is up to date.");
                    return;
                }

                // Keep APK URL and checksum for download phase
                pendingApkUrl  = apkUrl;
                pendingChecksum = checksum;

                ui.post(() -> {
                    if (listener != null)
                        listener.onUpdateAvailable(remoteCode, remoteName, notes, force);
                });

            } catch (Exception e) {
                Log.e(TAG, "checkForUpdate failed: " + e.getMessage());
                ui.post(() -> {
                    if (listener != null)
                        listener.onError("Update check failed: " + e.getMessage());
                });
            }
        }).start();
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private String pendingApkUrl;
    private String pendingChecksum;

    /**
     * Start downloading the APK via DownloadManager.
     * Call this after the user taps "Update" (or immediately for force updates).
     */
    public void startDownload() {
        if (pendingApkUrl == null) {
            if (listener != null) listener.onError("No update URL available.");
            return;
        }

        // Clean up any previous download
        File dest = getApkFile();
        if (dest.exists()) dest.delete();

        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(pendingApkUrl))
                .setTitle("DGX Player update")
                .setDescription("Downloading update…")
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(dest));

        downloadId = dm.enqueue(req);
        Log.d(TAG, "Download enqueued id=" + downloadId);

        // Register receiver for completion
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    ctx.unregisterReceiver(this);
                    onDownloadComplete(dm);
                }
            }
        };
        // Android 13+ requires RECEIVER_EXPORTED for broadcasts sent by system services
        // (DownloadManager is a system service, so its broadcast comes from outside this process)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(downloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(downloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        // Poll progress
        pollProgress(dm);
    }

    private void pollProgress(DownloadManager dm) {
        ui.postDelayed(() -> {
            if (downloadId < 0) return;
            DownloadManager.Query q = new DownloadManager.Query().setFilterById(downloadId);
            try (Cursor c = dm.query(q)) {
                if (c != null && c.moveToFirst()) {
                    int status = c.getInt(c.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_RUNNING ||
                            status == DownloadManager.STATUS_PAUSED) {
                        long total  = c.getLong(c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        long downloaded = c.getLong(c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        if (total > 0 && listener != null) {
                            int pct = (int) (downloaded * 100 / total);
                            listener.onDownloadProgress(pct);
                        }
                        pollProgress(dm); // keep polling
                    }
                }
            }
        }, 500);
    }

    private void onDownloadComplete(DownloadManager dm) {
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor c = dm.query(q)) {
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    verifyAndInstall();
                } else {
                    int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    ui.post(() -> {
                        if (listener != null) listener.onError("Download failed (reason=" + reason + ")");
                    });
                }
            }
        }
        downloadId = -1;
    }

    // ── Verify + Install ──────────────────────────────────────────────────────

    private void verifyAndInstall() {
        new Thread(() -> {
            File apk = getApkFile();
            if (!apk.exists()) {
                ui.post(() -> { if (listener != null) listener.onError("APK file not found."); });
                return;
            }

            // SHA-256 checksum verification (skip if server didn't provide one)
            if (pendingChecksum != null && !pendingChecksum.isEmpty()) {
                String actual = sha256(apk);
                if (!pendingChecksum.equalsIgnoreCase(actual)) {
                    apk.delete();
                    ui.post(() -> {
                        if (listener != null)
                            listener.onError("APK checksum mismatch — download may be corrupt.");
                    });
                    return;
                }
                Log.d(TAG, "Checksum OK");
            }

            ui.post(() -> {
                installApk(apk);
                if (listener != null) listener.onInstallReady();
            });
        }).start();
    }

    private void installApk(File apk) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".provider", apk);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(apk);
        }
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Destination file for the downloaded APK. */
    private File getApkFile() {
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, "dgx_update.apk");
    }

    private static String sha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
