package com.example.videoplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Stable per-device identifier for enrollment + heartbeat.
 *
 * <p>The player used to identify itself with {@link Settings.Secure#ANDROID_ID}
 * directly. That value is NOT stable: on Android 8+ it is scoped per
 * app-signing-key (so a debug build and the release build report different ids),
 * it is regenerated on factory reset, and on some devices it does not survive an
 * uninstall/reinstall. The result was that a re-installed or re-signed screen
 * enrolled as a brand-new device — orphaned duplicate rows and lost content
 * assignments.
 *
 * <p>Instead we persist the id to a file on shared external storage, which is
 * NOT cleared when the app is uninstalled. The file is <b>seeded from the
 * current ANDROID_ID on first run</b>, so devices already enrolled under their
 * ANDROID_ID keep exactly the same id after updating (no fleet-wide re-id).
 * Thereafter the persisted value wins, regardless of signing key or reinstall.
 * (A factory reset that wipes the storage volume is still a genuinely fresh
 * device and will re-seed.)
 */
public final class DeviceIdentity {
    private static final String TAG = "DeviceIdentity";
    // Kept outside the "video" cache dir so cache-pruning never touches it.
    private static final String ID_DIR = ".dgx";
    private static final String ID_FILE = "device_id";
    // Known-bad ANDROID_ID from the old Android 2.2 emulator bug; treat as unusable.
    private static final String BAD_ANDROID_ID = "9774d56d682e549c";

    private static volatile String cached;

    private DeviceIdentity() {}

    /** Returns the stable device id, resolving and persisting it on first use. */
    public static synchronized String get(Context ctx) {
        File file = idFile();
        if (cached == null) {
            String existing = read(file);
            if (!TextUtils.isEmpty(existing)) {
                cached = existing;
            } else {
                String seed = usableAndroidId(ctx);
                cached = (seed != null) ? seed : ("dgx-" + UUID.randomUUID());
            }
        }
        // Best-effort persist so the id survives the next uninstall/reinstall.
        // (May fail if storage isn't ready/permitted yet; retried on later calls.)
        if (!file.exists()) write(file, cached);
        return cached;
    }

    private static File idFile() {
        return new File(new File(Environment.getExternalStorageDirectory(), ID_DIR), ID_FILE);
    }

    @SuppressLint("HardwareIds")
    private static String usableAndroidId(Context ctx) {
        try {
            String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id == null) return null;
            id = id.trim();
            if (id.isEmpty() || id.equalsIgnoreCase(BAD_ANDROID_ID) || id.matches("0+")) return null;
            return id;
        } catch (Exception e) {
            Log.w(TAG, "ANDROID_ID unavailable", e);
            return null;
        }
    }

    private static String read(File file) {
        try {
            if (!file.exists()) return null;
            byte[] buf = new byte[64];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int n = in.read(buf);
                if (n <= 0) return null;
                return new String(buf, 0, n, "UTF-8").trim();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read persisted device id", e);
            return null;
        }
    }

    private static void write(File file, String id) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Could not create id dir " + dir);
                return;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(id.getBytes("UTF-8"));
                out.flush();
            }
            Log.d(TAG, "Persisted device id: " + id);
        } catch (IOException e) {
            Log.w(TAG, "Could not persist device id (will retry): " + e.getMessage());
        }
    }
}
