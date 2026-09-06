package com.example.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Synchronous version-check helper used by UpdateCheckWorker (background thread).
 * Stores the latest remote versionCode in SharedPreferences so FullScreenPlayerActivity
 * can read it on next launch and show the update dialog without waiting for a network call.
 */
public class UpdateCheckHelper {

    private static final String TAG    = "UpdateCheckHelper";
    private static final String PREFS  = "dgx_update_prefs";
    private static final String KEY_REMOTE_VERSION_CODE = "remote_version_code";
    private static final String KEY_REMOTE_VERSION_NAME = "remote_version_name";
    private static final String KEY_APK_URL             = "apk_url";
    private static final String KEY_CHECKSUM            = "checksum";
    private static final String KEY_RELEASE_NOTES       = "release_notes";
    private static final String KEY_FORCE_UPDATE        = "force_update";

    /** Fetches version.json and caches the result. Throws on network error. */
    public static void checkVersionSync(Context ctx) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(AppUpdater.VERSION_JSON_URL).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setRequestMethod("GET");

        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();

        JSONObject json = new JSONObject(sb.toString());

        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putInt   (KEY_REMOTE_VERSION_CODE, json.getInt("versionCode"));
        ed.putString(KEY_REMOTE_VERSION_NAME, json.optString("versionName", ""));
        ed.putString(KEY_APK_URL,             json.getString("apkUrl"));
        ed.putString(KEY_CHECKSUM,            json.optString("checksum", ""));
        ed.putString(KEY_RELEASE_NOTES,       json.optString("releaseNotes", ""));
        ed.putBoolean(KEY_FORCE_UPDATE,       json.optBoolean("forceUpdate", false));
        ed.apply();

        Log.d(TAG, "Cached remote versionCode=" + json.getInt("versionCode"));
    }

    /** Read cached remote version info (set by background worker). */
    public static JSONObject getCachedVersionInfo(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject j = new JSONObject();
            j.put("versionCode",  prefs.getInt   (KEY_REMOTE_VERSION_CODE, 0));
            j.put("versionName",  prefs.getString (KEY_REMOTE_VERSION_NAME, ""));
            j.put("apkUrl",       prefs.getString (KEY_APK_URL,             ""));
            j.put("checksum",     prefs.getString (KEY_CHECKSUM,            ""));
            j.put("releaseNotes", prefs.getString (KEY_RELEASE_NOTES,       ""));
            j.put("forceUpdate",  prefs.getBoolean(KEY_FORCE_UPDATE,        false));
            return j;
        } catch (Exception e) {
            return null;
        }
    }
}
