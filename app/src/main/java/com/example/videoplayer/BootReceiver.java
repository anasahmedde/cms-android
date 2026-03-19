package com.example.videoplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.d(TAG, "Received: " + action);

        // LOCKED_BOOT_COMPLETED fires while the device is still in direct-boot mode.
        // FullScreenPlayerActivity is not directBootAware, so calling startActivity()
        // at that point throws ActivityNotFoundException and crashes the receiver.
        // Skip it here — BOOT_COMPLETED will fire once the user credential storage is
        // available (immediately on no-PIN devices, after unlock on PIN devices).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Log.d(TAG, "LOCKED_BOOT_COMPLETED — skipping activity start (direct boot mode)");
            return;
        }

        boolean isBoot =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (!isBoot) return;

        Intent i = new Intent(context, FullScreenPlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        Log.d(TAG, "Starting FullScreenPlayerActivity on: " + action);
        context.startActivity(i);
    }
}
