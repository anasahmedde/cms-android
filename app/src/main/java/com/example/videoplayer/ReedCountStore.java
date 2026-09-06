package com.example.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Local persistent buffer for reed-switch open counts.
 *
 * Every REED:OPEN event increments the pending counters here BEFORE any
 * network call is attempted.  The sync worker (ReedSyncWorker) reads these
 * counters and decrements each one only after a successful HTTP POST —
 * so a crash, reboot, or lost connection can never lose a count.
 *
 * Thread-safety: all mutating methods are synchronized on the prefs file.
 */
public class ReedCountStore {

    private static final String PREFS           = "reed_count_prefs";
    private static final String KEY_DAILY       = "pending_daily";
    private static final String KEY_MONTHLY     = "pending_monthly";

    private final SharedPreferences prefs;

    public ReedCountStore(Context context) {
        prefs = context.getApplicationContext()
                       .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Call this on every REED:OPEN — always persists, never throws. */
    public synchronized void recordOpen() {
        prefs.edit()
             .putInt(KEY_DAILY,   prefs.getInt(KEY_DAILY,   0) + 1)
             .putInt(KEY_MONTHLY, prefs.getInt(KEY_MONTHLY, 0) + 1)
             .commit();          // commit() (not apply()) so data survives an immediate kill
    }

    public int getPendingDaily()   { return prefs.getInt(KEY_DAILY,   0); }
    public int getPendingMonthly() { return prefs.getInt(KEY_MONTHLY, 0); }

    /** Decrement by 1 after a confirmed daily POST. */
    public synchronized void decrementDaily() {
        int v = prefs.getInt(KEY_DAILY, 0);
        if (v > 0) prefs.edit().putInt(KEY_DAILY, v - 1).commit();
    }

    /** Decrement by 1 after a confirmed monthly POST. */
    public synchronized void decrementMonthly() {
        int v = prefs.getInt(KEY_MONTHLY, 0);
        if (v > 0) prefs.edit().putInt(KEY_MONTHLY, v - 1).commit();
    }
}
