package com.example.videoplayer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.os.ParcelUuid;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.app.UiModeManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.content.pm.ActivityInfo;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

// Self-update
import com.example.videoplayer.AppUpdater;
import com.example.videoplayer.UpdateCheckWorker;
import com.example.videoplayer.UpdateUI;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Video player using TextureView for rotation support.
 * TextureView (unlike SurfaceView) properly supports rotation transforms.
 */
@OptIn(markerClass = UnstableApi.class)
@SuppressLint("MissingPermission")
public class FullScreenPlayerActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "FullScreenPlayer";

    // ==========================================
    // BACKEND URL CONFIGURATION - CHANGE THIS
    // ==========================================
    //private static final String API_BASE = "https://api-cms.wizioners.com";
    private static final String API_BASE = "https://api-staging-cms.wizioners.com";

    // ==========================================

    // Network timeouts (milliseconds)
    private static final int CONNECT_TIMEOUT_MS = 8_000;      // 8 seconds for connection (fast fallback to cache)
    private static final int READ_TIMEOUT_MS = 15_000;        // 15 seconds for reading API responses
    private static final int DOWNLOAD_TIMEOUT_MS = 1_800_000; // 30 minutes for large file downloads
    private static final long MIN_STORAGE_REQUIRED_MB = 100; // Minimum 100MB free space required
    private static final long MIN_RAM_REQUIRED_MB = 50;      // Minimum 50MB RAM required

    private static String listDownloadsUrl(String id) { return API_BASE + "/device/" + id + "/videos/downloads"; }
    private static String readStatusUrl(String id) { return API_BASE + "/device/" + id + "/download_status"; }
    private static String updateStatusUrl(String id) { return API_BASE + "/device/" + id + "/download_update"; }
    private static String updateOnlineUrl(String id) { return API_BASE + "/device/" + id + "/online_update"; }
    private static String updateTemperatureUrl(String id) { return API_BASE + "/device/" + id + "/temperature_update"; }
    private static String countsUrl(String id) { return API_BASE + "/device/" + id + "/counts"; }
    private static String dailyUpdateUrl(String id) { return API_BASE + "/device/" + id + "/daily_update"; }
    private static String monthlyUpdateUrl(String id) { return API_BASE + "/device/" + id + "/monthly_update"; }
    private String checkDeviceUrl(String id) {
        // Include the device's actual screen resolution so the backend can cache it
        // even before the device is enrolled (powers the auto-detect in the CMS UI).
        return API_BASE + "/device/" + id + "/online?resolution=" + screenWidth + "x" + screenHeight;
    }

    // Enrollment status
    private LinearLayout enrollmentOverlay;
    private TextView deviceIdText;
    private TextView retryText;
    private Button copyButton;
    // Optional device-specific header (text) / footer (image)
    private TextView headerBar;
    private ImageView footerBar;
    private volatile String lastFooterSig = null;
    private volatile boolean isEnrolled = false;
    private volatile boolean reconnectNeeded = false; // true when offline boot path ran; triggers re-sync on reconnect
    private ReedCountStore reedCountStore;
    private static final String PREFS_NAME = "digix_player_prefs";
    private static final String PREF_WAS_ENROLLED = "was_enrolled";
    private static final String PREF_LAYOUT_JSON = "cached_layout_json";
    private static final String PREF_IS_MUTED = "is_muted";
    private static final String PREF_BLE_DEVICE_ID = "ble_device_id";
    private static final long ENROLLMENT_CHECK_MS = 15_000L; // Check every 15 seconds
    private final Handler enrollmentCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable enrollmentCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkEnrollmentStatus();
            enrollmentCheckHandler.postDelayed(this, jittered(ENROLLMENT_CHECK_MS));
        }
    };

    // Video metadata
    private static class VideoMetadata {
        String filename = "";
        String videoName = "";
        int rotation = 0;
        String fitMode = "cover";
        int gridPosition = 0;
        String contentType = "video"; // video, image, gif, html
        int displayDuration = 10; // seconds for images/gifs
    }

    // Metadata storage
    private volatile Map<String, VideoMetadata> metadataByVideoName = new HashMap<>();
    private volatile Map<String, VideoMetadata> metadataByFilename = new HashMap<>();
    private int lastAppliedRotation = -9999;
    private String lastAppliedFitMode = "";
    private List<File> currentPlaylistFiles = new ArrayList<>();

    // Grid layout support
    private volatile String layoutMode = "single";
    private ExoPlayer[] gridPlayers = new ExoPlayer[4];
    private TextureView[] gridTextureViews = new TextureView[4];
    private Surface[] gridSurfaces = new Surface[4];
    private boolean isGridMode = false;
    // Prevents concurrent switchToGridMode() calls from stacking SurfaceTexture allocations
    private volatile boolean gridSwitchInProgress = false;

    // Image display support
    private ImageView imageView;
    private ImageView[] gridImageViews = new ImageView[4];
    private final Handler imageTimerHandler = new Handler(Looper.getMainLooper());
    private Runnable imageTimerRunnable;
    private int currentImageIndex = 0;
    private List<File> currentImageFiles = new ArrayList<>();
    private boolean isShowingImage = false;

    private volatile boolean isWorking = false;
    private final java.util.concurrent.atomic.AtomicBoolean downloadInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);

    // "Content updating..." overlay shown while downloads are in progress
    private TextView downloadStatusOverlay;

    // Playback generation counter: prevents duplicate players when multiple code paths
    // trigger playMixedPlaylist simultaneously (causing dual audio + frozen video frames)
    private int playbackGeneration = 0;

    // Transition watcher: class-level handler so it can be cancelled before each new
    // playback session. Without this, every playLocalPlaylistOrToast call leaked a new
    // Handler+Runnable that kept fading the TextureView to 0.01f, fighting the fade-in
    // from onVideoSizeChanged and causing a permanently frozen/invisible screen.
    private final Handler transitionWatcherHandler = new Handler(Looper.getMainLooper());
    private int transitionWatcherGeneration = 0;

    // Storage management - report storage to server for dashboard visibility
    private static String storageReportUrl(String id) { return API_BASE + "/device/" + id + "/storage/report"; }

    // Screen dimensions
    private int screenWidth = 0;
    private int screenHeight = 0;

    // TV detection - Android TV has limited hardware video decoders
    private boolean isTvDevice = false;

    // Video dimensions
    private int videoWidth = 0;
    private int videoHeight = 0;

    // Rotation polling every 10 seconds
    private static final long ROTATION_POLL_MS = 10_000L;
    private final Handler rotationPollHandler = new Handler(Looper.getMainLooper());
    private final Runnable rotationPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollRotationMetadata();
            rotationPollHandler.postDelayed(this, jittered(ROTATION_POLL_MS));
        }
    };

    private static final long POLL_MS = 30_000L; // 30 seconds heartbeat
    private final Handler pollHandler = new Handler(Looper.getMainLooper());

    // ── Poll pacing ─────────────────────────────────────────────────────────
    // Fixed intervals synchronize the fleet into request herds: after a shared
    // outage or a backend deploy every device retries in lockstep, and during
    // an outage each keeps hammering at full cadence. Jitter (±15%) spreads
    // devices across the window; consecutive heartbeat failures back off
    // exponentially to a 5-minute cap and reset on the first success.
    private static final long MAX_BACKOFF_MS = 5 * 60_000L;
    private volatile int heartbeatFailures = 0;

    private static long jittered(long baseMs) {
        return baseMs + (long) ((Math.random() - 0.5) * 0.3 * baseMs);
    }

    private long heartbeatDelay() {
        int f = heartbeatFailures;
        if (f <= 0) return jittered(POLL_MS);
        return jittered(Math.min(MAX_BACKOFF_MS, POLL_MS << Math.min(f, 4)));
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            sendOnlineHeartbeat();
            startBackgroundCheckIfNeeded();
            pollHandler.postDelayed(this, heartbeatDelay());
        }
    };

    // ===== PLAYBACK WATCHDOG =====
    // A TextureView keeps its last decoded frame forever, so a player that errors,
    // ends, or fails to get a surface leaves the screen frozen or black until the
    // next content/layout change or an app restart. Nothing here ever noticed that.
    // This lightweight watchdog checks the active player(s) every few seconds and
    // rebuilds playback when they are stuck (IDLE/ENDED or drained) for two
    // consecutive checks (~14s) — the same rebuild a dashboard content change does,
    // just automatic. Covers single, template and grid modes.
    private static final long WATCHDOG_MS = 7_000L;
    private int playbackStallStrikes = 0;
    private int singleErrorStrikes = 0;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            try { checkPlaybackHealth(); } catch (Exception e) { Log.w(TAG, "watchdog check failed", e); }
            watchdogHandler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private static final String ROOT_DIR = "video";
    private static final String TEMP_DIR = "video_new";
    private static final int MAX_RETRIES = 5;

    // Views - using TextureView instead of PlayerView
    private FrameLayout rootContainer;
    private TextureView textureView;
    private ExoPlayer player;
    private Surface surface;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Screen templates: when the device's company has a linked template, we render
    // a transparent zone overlay on top and confine the existing single-mode
    // playback to the template's "playlist" zone. templateActive gates the normal
    // full-screen single/grid playback so the two don't fight.
    private static final String PREF_TEMPLATE_JSON = "cached_template_json";
    private TemplateRenderer templateRenderer;
    private FrameLayout templateOverlay;
    private volatile boolean templateActive = false;
    private volatile String templateStamp = null;
    // Zone media is served from presigned S3 URLs (7-day TTL). The template is
    // only re-fetched when its stamp changes (a dashboard edit), so a screen left
    // untouched past the TTL would 403 and black-box its zone videos. Force a
    // silent re-fetch on this interval to renew the URLs well before they expire.
    private static final long TEMPLATE_REFRESH_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private volatile long lastTemplateApplyMs = 0L;
    private final java.util.concurrent.atomic.AtomicBoolean templateFetchInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private ActivityResultLauncher<String> legacyPermLauncher;

    // BLE
    private static final String ESP32_DEVICE_NAME = "ESP32_PLAYER_CTRL_BLE";
    private volatile String bleTargetName = null;  // "ESP32_PLAYER_CTRL_BLE_<id>" — only connect to this exact name
    private static final int REQ_BT_PERMS = 2001;
    private static final UUID NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_CHAR_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_CHAR_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt btGatt;
    private BluetoothGattCharacteristic nusTxChar, nusRxChar;
    private final Handler bleHandler = new Handler(Looper.getMainLooper());
    private volatile boolean btShouldReconnect = true;
    private volatile boolean bleScanning = false;
    private volatile boolean bleConnected = false;
    private volatile boolean bleAuthPending = false;
    private final Runnable reconnectRunnable = this::autoConnectToEsp32;

    private static final Pattern TEMP_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
    private volatile Float lastTemperatureValue = null;

    private final Handler tempHandler = new Handler(Looper.getMainLooper());
    private final long TEMP_POST_INTERVAL_MS = 5_000L;
    private final Runnable tempPostRunnable = new Runnable() {
        @Override
        public void run() {
            if (lastTemperatureValue != null) sendTemperatureToServer(lastTemperatureValue);
            tempHandler.postDelayed(this, TEMP_POST_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Force black background BEFORE super.onCreate to eliminate white flash on boot
        getWindow().setBackgroundDrawableResource(android.R.color.black);

        super.onCreate(savedInstanceState);
        // We're up — cancel any pending crash-relaunch alarm so it can't
        // CLEAR_TASK this healthy instance a few seconds from now.
        DgxApp.cancelRelaunchAlarms(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // At boot the screen is OFF and the lock screen is active.
        // Without these flags the activity starts but is invisible — Android
        // treats it as a background process and kills it within ~2 seconds.
        // FLAG_SHOW_WHEN_LOCKED : display above the keyguard/lock screen
        // FLAG_TURN_SCREEN_ON   : wake the display if it is off
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        setContentView(R.layout.activity_fullscreen_player);

        // Detect if running on Android TV
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        isTvDevice = (uiModeManager != null &&
                uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION);
        Log.d(TAG, "Device type: " + (isTvDevice ? "Android TV" : "Mobile"));

        // Get screen dimensions
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        Log.d(TAG, "Screen size: " + screenWidth + "x" + screenHeight);

        // Find views
        rootContainer = findViewById(R.id.rootContainer);
        textureView = findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(this);

        // Create ImageView dynamically for image display
        imageView = new ImageView(this);
        imageView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setVisibility(View.GONE);
        rootContainer.addView(imageView, 0);  // Add behind textureView

        // Create "Content updating..." overlay (shown during downloads)
        createDownloadStatusOverlay();

        // Find enrollment overlay views
        enrollmentOverlay = findViewById(R.id.enrollmentOverlay);
        deviceIdText = findViewById(R.id.deviceIdText);
        retryText = findViewById(R.id.retryText);
        copyButton = findViewById(R.id.copyButton);
        headerBar = findViewById(R.id.headerBar);
        footerBar = findViewById(R.id.footerBar);

        // Set device ID in the overlay
        String deviceId = getAndroidId();
        if (deviceIdText != null) {
            deviceIdText.setText(deviceId);
        }

        // Set up copy button
        if (copyButton != null) {
            copyButton.setOnClickListener(v -> copyDeviceIdToClipboard());
        }

        applyImmersive();

        // Don't show toast anymore - we show full screen enrollment if not enrolled
        Log.d(TAG, "Android ID: " + deviceId);

        legacyPermLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) startEverything(); else toast("Storage permission denied."); });

        // Check enrollment first before starting everything
        checkEnrollmentStatus();
        enrollmentCheckHandler.postDelayed(enrollmentCheckRunnable, ENROLLMENT_CHECK_MS);

        // NEW: Report storage status when app starts
        reportStorageToServer();

        tempHandler.postDelayed(tempPostRunnable, TEMP_POST_INTERVAL_MS);
        rotationPollHandler.postDelayed(rotationPollRunnable, ROTATION_POLL_MS);

        // Self-update: check S3 for a newer APK, schedule background re-checks every 6 h
        AppUpdater appUpdater = new AppUpdater(this, BuildConfig.VERSION_CODE);
        UpdateUI updateUI = new UpdateUI(this);
        updateUI.attach(appUpdater);
        appUpdater.checkForUpdate();
        UpdateCheckWorker.schedule(this);

        // Reed count buffer — flush any counts saved while offline in a previous session
        reedCountStore = new ReedCountStore(this);
        ReedSyncWorker.enqueue(this);

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) ensureBluetoothPermissionAndConnect();
    }

    // ===== TextureView.SurfaceTextureListener =====

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        Log.d(TAG, "SurfaceTexture available: " + width + "x" + height);
        surface = new Surface(surfaceTexture);
        if (player != null) {
            player.setVideoSurface(surface);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        Log.d(TAG, "SurfaceTexture size changed: " + width + "x" + height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        Log.d(TAG, "SurfaceTexture destroyed");
        if (player != null) {
            player.setVideoSurface(null);
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // Called every frame - no logging needed
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop all polling so background threads don't keep running while the app is hidden
        pollHandler.removeCallbacksAndMessages(null);
        rotationPollHandler.removeCallbacksAndMessages(null);
        enrollmentCheckHandler.removeCallbacksAndMessages(null);
        tempHandler.removeCallbacksAndMessages(null);
        imageTimerHandler.removeCallbacksAndMessages(null);
        transitionWatcherHandler.removeCallbacksAndMessages(null);
        watchdogHandler.removeCallbacksAndMessages(null);
        // Pause video players to release audio focus and reduce resource use
        if (player != null) {
            try { player.pause(); } catch (Exception ignored) {}
        }
        for (ExoPlayer gp : gridPlayers) {
            if (gp != null) try { gp.pause(); } catch (Exception ignored) {}
        }
        // Clear the in-progress flag so switchToGridMode isn't permanently blocked
        gridSwitchInProgress = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.d(TAG, "onNewIntent — activity re-launched (singleTask)");
        // No extra action needed — onResume() will run next and resume playback.
        // This handler is required because with launchMode="singleTask",
        // re-launches call onNewIntent+onResume instead of onCreate.
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        // Resume players that were paused by onPause() (fixes stuck screen after Home press).
        if (player != null) {
            try { player.play(); } catch (Exception ignored) {}
        }
        for (ExoPlayer gp : gridPlayers) {
            if (gp != null) try { gp.play(); } catch (Exception ignored) {}
        }
        // Only restart poll handler if device is enrolled (otherwise enrollment check will start it)
        if (isEnrolled) {
            pollHandler.removeCallbacksAndMessages(null);
            pollHandler.postDelayed(pollRunnable, POLL_MS);
        } else {
            // KNOWN-BUG FIX: onPause() cancels the enrollment check but nothing
            // rescheduled it, so an unenrolled screen stopped noticing it had
            // been added in the dashboard until the app was killed. Resume it.
            enrollmentCheckHandler.removeCallbacksAndMessages(null);
            checkEnrollmentStatus();
            enrollmentCheckHandler.postDelayed(enrollmentCheckRunnable, ENROLLMENT_CHECK_MS);
        }
        rotationPollHandler.removeCallbacksAndMessages(null);
        rotationPollHandler.postDelayed(rotationPollRunnable, 1000);
        tempHandler.removeCallbacksAndMessages(null);
        tempHandler.postDelayed(tempPostRunnable, TEMP_POST_INTERVAL_MS);
        // Self-healing watchdog: recover a frozen/black player without waiting for
        // a content change or restart. Self-guards on enrollment.
        startWatchdog();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (templateRenderer != null) templateRenderer.release(); // zone video decoders
        pollHandler.removeCallbacksAndMessages(null);
        rotationPollHandler.removeCallbacksAndMessages(null);
        tempHandler.removeCallbacksAndMessages(null);
        enrollmentCheckHandler.removeCallbacksAndMessages(null);
        imageTimerHandler.removeCallbacksAndMessages(null);
        transitionWatcherHandler.removeCallbacksAndMessages(null);
        watchdogHandler.removeCallbacksAndMessages(null);
        btShouldReconnect = false;
        bleConnected = false;
        bleHandler.removeCallbacksAndMessages(null);
        stopBleScan();
        if (btGatt != null) {
            try { btGatt.disconnect(); } catch (Exception ignored) {}
            try { btGatt.close(); } catch (Exception ignored) {}
            btGatt = null;
        }
        if (player != null) {
            try {
                player.setVideoSurface(null);
                player.stop();
                player.clearMediaItems();
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing player: " + e.getMessage());
            }
            player = null;
        }
        if (surface != null) {
            try {
                surface.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing surface: " + e.getMessage());
            }
            surface = null;
        }
        // Release TextureView SurfaceTexture
        if (textureView != null) {
            try {
                SurfaceTexture st = textureView.getSurfaceTexture();
                if (st != null) {
                    st.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error releasing textureView: " + e.getMessage());
            }
        }
        // Release grid resources
        releaseGridResources();
        // Stop TV time-sharing rotation
        stopTvRotation();
        // Clear image view bitmap
        if (imageView != null) {
            try {
                imageView.setImageBitmap(null);
            } catch (Exception ignored) {}
        }
    }

    private void releaseGridResources() {
        for (int i = 0; i < gridPlayers.length; i++) {
            if (gridPlayers[i] != null) {
                try {
                    // Detach surface first to prevent buffer queue issues
                    gridPlayers[i].setVideoSurface(null);
                    gridPlayers[i].stop();
                    gridPlayers[i].clearMediaItems();
                    gridPlayers[i].release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing grid player " + i + ": " + e.getMessage());
                }
                gridPlayers[i] = null;
            }
            if (gridSurfaces[i] != null) {
                try {
                    gridSurfaces[i].release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing grid surface " + i + ": " + e.getMessage());
                }
                gridSurfaces[i] = null;
            }
            // Clear TextureView SurfaceTexture to release buffers
            if (gridTextureViews[i] != null) {
                try {
                    SurfaceTexture st = gridTextureViews[i].getSurfaceTexture();
                    if (st != null) {
                        st.release();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing grid texture " + i + ": " + e.getMessage());
                }
            }
            gridTextureViews[i] = null;

            // Clear grid image views and recycle bitmaps
            if (gridImageViews[i] != null) {
                try {
                    gridImageViews[i].setImageBitmap(null);
                } catch (Exception ignored) {}
                gridImageViews[i] = null;
            }
        }
        // Also clear the grid video files array
        for (int i = 0; i < gridVideoFiles.length; i++) {
            gridVideoFiles[i] = null;
            lastGridRotations[i] = 0;
        }
    }

    // ===== ENROLLMENT CHECK =====
    private void checkEnrollmentStatus() {
        new Thread(() -> {
            try {
                if (!isOnline()) {
                    // Check if device was previously enrolled and has local content
                    // BUT only if it wasn't explicitly deactivated
                    boolean wasEnrolled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_WAS_ENROLLED, false);
                    if (wasEnrolled && !isEnrolled) {
                        // Offline but was previously enrolled - play cached content
                        File mainDir = ensureMainDir();
                        File[] localFiles = mainDir.listFiles();
                        if (localFiles != null && localFiles.length > 0) {
                            Log.d(TAG, "Offline but previously enrolled - playing " + localFiles.length + " cached files");
                            isEnrolled = true;
                            reconnectNeeded = true; // flag so we re-sync when network returns
                            // Load cached layout mode and metadata (rotation, fit, grid positions)
                            loadCachedLayoutMetadata();
                            ui.post(() -> {
                                showEnrollmentOverlay(false);
                                // Use correct layout mode from cache
                                if ("single".equals(layoutMode) || layoutMode == null) {
                                    playMixedPlaylist(mainDir);
                                } else {
                                    switchToGridMode();
                                }
                                // Offline: render the last cached template over playback.
                                loadCachedTemplateIfAny();
                            });
                            return;
                        }
                    }
                    ui.post(() -> {
                        if (retryText != null) {
                            retryText.setText("No internet connection. Retrying...");
                        }
                    });
                    return;
                }

                String urlStr = checkDeviceUrl(getAndroidId());
                Log.d(TAG, "Checking enrollment: " + urlStr);

                HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setRequestMethod("GET");
                c.connect();

                int responseCode = c.getResponseCode();
                c.disconnect();

                if (responseCode == 200) {
                    // Device is enrolled
                    Log.d(TAG, "Device is enrolled!");
                    boolean firstEnrollment = !isEnrolled;
                    if (!isEnrolled) {
                        isEnrolled = true;
                        // Persist enrolled state for offline recovery
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_WAS_ENROLLED, true).apply();
                    }
                    boolean shouldReconnect = reconnectNeeded;
                    reconnectNeeded = false;
                    ui.post(() -> {
                        if (firstEnrollment) {
                            // First time enrolled (or re-enrolled after deactivation) — full init
                            resetPlaybackState();
                            showEnrollmentOverlay(false);
                            pollHandler.removeCallbacksAndMessages(null);
                            pollHandler.postDelayed(pollRunnable, POLL_MS);
                            ensureAllFilesAccessThenStart();
                            // Paint the template immediately rather than waiting for
                            // the first 30s heartbeat.
                            fetchAndApplyTemplate(templateStamp);
                        } else if (shouldReconnect) {
                            // Network came back after offline boot — restart heartbeat and re-sync
                            // content/layout so the app reflects current server state.
                            Log.d(TAG, "Network restored after offline boot — re-syncing with server");
                            pollHandler.removeCallbacksAndMessages(null);
                            pollHandler.postDelayed(pollRunnable, 0); // fire immediately to sync mute + status
                            ensureAllFilesAccessThenStart();
                        }
                    });
                } else if (responseCode == 404) {
                    // Device not found or deactivated - show enrollment screen
                    Log.d(TAG, "Device not enrolled or deactivated (404)");
                    handleDeviceDeactivated();
                } else {
                    // Other error - keep checking
                    Log.d(TAG, "Enrollment check returned: " + responseCode);
                    ui.post(() -> {
                        if (retryText != null) {
                            retryText.setText("Server error (" + responseCode + "). Retrying...");
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Enrollment check error: " + e.getMessage());
                // Server unreachable (timeout, DNS failure, etc.) but internet may be up.
                // Fall back to cached content exactly like the no-internet path.
                boolean wasEnrolled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(PREF_WAS_ENROLLED, false);
                if (wasEnrolled && !isEnrolled) {
                    File mainDir = ensureMainDir();
                    File[] localFiles = mainDir.listFiles();
                    if (localFiles != null && localFiles.length > 0) {
                        Log.d(TAG, "Server unreachable — falling back to " + localFiles.length + " cached files");
                        isEnrolled = true;
                        reconnectNeeded = true;
                        loadCachedLayoutMetadata();
                        ui.post(() -> {
                            showEnrollmentOverlay(false);
                            if ("single".equals(layoutMode) || layoutMode == null) {
                                playMixedPlaylist(mainDir);
                            } else {
                                switchToGridMode();
                            }
                            // Offline: render the last cached template over the playback.
                            loadCachedTemplateIfAny();
                        });
                        return;
                    }
                }
                ui.post(() -> {
                    if (retryText != null) {
                        retryText.setText("Server unreachable. Retrying...");
                    }
                });
            }
        }).start();
    }

    /**
     * Handle device deactivation - stop playback, clear cached enrolled state,
     * and show enrollment screen. This is called when:
     * - Device returns 404 (not found/deactivated)
     * - Heartbeat returns is_active=false or company_expired=true
     */
    private void handleDeviceDeactivated() {
        boolean wasEnrolled = isEnrolled;
        isEnrolled = false;

        // CRITICAL: Clear the enrolled flag so it NEVER plays cached content
        // This ensures device stays on enrollment screen even after restart
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_WAS_ENROLLED, false)
                .apply();

        ui.post(() -> {
            // Stop all playback if device was previously enrolled
            if (wasEnrolled) {
                Log.d(TAG, "Device was deactivated - stopping playback immediately");
                stopAllPlayback();
            }
            // stopAllPlayback() doesn't touch the template overlay or its zone
            // video players — tear them down too, or the (opaque) template keeps
            // rendering ABOVE the enrollment screen and looks like the delete was
            // never detected.
            removeTemplateOverlay();
            // Stop poll handler so it doesn't restart playback
            pollHandler.removeCallbacksAndMessages(null);
            showEnrollmentOverlay(true);
            if (retryText != null) {
                retryText.setText("Device not enrolled. Checking again in 15 seconds...");
            }
            // Re-arm the enrollment watchdog: onPause() cancels it and onResume()
            // only restarts it when NOT enrolled — so at this point it may be dead,
            // and without it a re-added device would never be noticed.
            enrollmentCheckHandler.removeCallbacksAndMessages(null);
            enrollmentCheckHandler.postDelayed(enrollmentCheckRunnable, ENROLLMENT_CHECK_MS);
        });
    }

    private void showEnrollmentOverlay(boolean show) {
        if (enrollmentOverlay != null) {
            // Ensure enrollment overlay is in the view hierarchy
            if (show && enrollmentOverlay.getParent() == null) {
                rootContainer.addView(enrollmentOverlay);
            }
            enrollmentOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (textureView != null) {
            textureView.setVisibility(show ? View.GONE : View.VISIBLE);
        }
        // Stop video playback if showing enrollment
        if (show && player != null) {
            player.stop();
        }
    }

    /**
     * Fully reset all playback state so re-enrollment after deactivation
     * starts completely fresh (equivalent to an app restart).
     * Without this, stale flags/views prevent grid layout from updating.
     */
    private void resetPlaybackState() {
        Log.d(TAG, "Resetting all playback state for fresh start after re-enrollment");

        // 1) Reset working flags so startEverything() is not blocked
        isWorking = false;
        downloadInProgress.set(false);

        // 2) Reset video state tracking so content detection works fresh
        lastKnownVideoState = "";
        lastKnownContentType = "";

        // 3) Reset layout mode so it gets re-fetched from server
        layoutMode = "single";
        isGridMode = false;

        // 4) Reset rotation tracking
        lastAppliedRotation = -9999;
        lastAppliedFitMode = "";

        // 5) Clear metadata maps so they get re-fetched
        metadataByVideoName = new HashMap<>();
        metadataByFilename = new HashMap<>();

        // 6) Clear playlists and image state
        currentPlaylistFiles.clear();
        currentImageFiles.clear();
        currentImageIndex = 0;
        isShowingImage = false;
        imageTimerHandler.removeCallbacksAndMessages(null);

        // 7) Release single player
        if (player != null) {
            try {
                player.setVideoSurface(null);
                player.stop();
                player.clearMediaItems();
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing player during reset: " + e.getMessage());
            }
            player = null;
        }
        if (surface != null) {
            try { surface.release(); } catch (Exception ignored) {}
            surface = null;
        }

        // 8) Release grid resources
        releaseGridResources();

        // 9) Reset grid video files tracking
        for (int i = 0; i < gridVideoFiles.length; i++) {
            gridVideoFiles[i] = null;
            lastGridRotations[i] = 0;
        }

        // 10) Detach overlays before clearing rootContainer (keep them alive for re-add)
        if (enrollmentOverlay != null && enrollmentOverlay.getParent() != null) {
            rootContainer.removeView(enrollmentOverlay);
        }
        if (downloadStatusOverlay != null && downloadStatusOverlay.getParent() != null) {
            // May live on rootContainer OR the window content root (it reparents
            // there when shown, to sit above the template overlay).
            ((ViewGroup) downloadStatusOverlay.getParent()).removeView(downloadStatusOverlay);
        }

        // 11) Clear rootContainer and rebuild views fresh
        rootContainer.removeAllViews();

        // Re-add ImageView (behind video)
        imageView = new ImageView(this);
        imageView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setVisibility(View.GONE);
        rootContainer.addView(imageView);

        // Re-add single TextureView
        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        rootContainer.addView(textureView, params);

        // Re-add enrollment overlay (so it can be shown/hidden later)
        if (enrollmentOverlay != null) {
            rootContainer.addView(enrollmentOverlay);
        }

        // Re-add download status overlay on top of everything. When it is
        // showing, it belongs on the window content root (above any template
        // overlay); hidden, rootContainer is fine — the next show reparents it.
        if (downloadStatusOverlay != null) {
            if (downloadStatusOverlay.getVisibility() == View.VISIBLE) {
                applyDownloadOverlay(true, null);
            } else {
                rootContainer.addView(downloadStatusOverlay);
            }
        } else {
            createDownloadStatusOverlay();
        }

        Log.d(TAG, "Playback state reset complete");
    }

    private void copyDeviceIdToClipboard() {
        String deviceId = getAndroidId();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("Device ID", deviceId);
            clipboard.setPrimaryClip(clip);

            // Update button text temporarily to show feedback
            if (copyButton != null) {
                copyButton.setText("Copied!");
                copyButton.postDelayed(() -> {
                    copyButton.setText("Copy to Clipboard");
                }, 2000);
            }

            toast("Device ID copied to clipboard");
        }
    }

    // ===== DOWNLOAD STATUS OVERLAY =====

    private void createDownloadStatusOverlay() {
        downloadStatusOverlay = new TextView(this);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        p.gravity = android.view.Gravity.CENTER;
        downloadStatusOverlay.setLayoutParams(p);
        downloadStatusOverlay.setText("Updating content…");
        downloadStatusOverlay.setTextColor(0xFFFFFFFF);
        downloadStatusOverlay.setTextSize(18f);
        downloadStatusOverlay.setGravity(android.view.Gravity.CENTER);
        downloadStatusOverlay.setBackgroundColor(0xCC000000);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        downloadStatusOverlay.setPadding(pad * 2, pad, pad * 2, pad);
        downloadStatusOverlay.setVisibility(View.GONE);
        rootContainer.addView(downloadStatusOverlay);
    }

    private void setDownloadingOverlay(boolean show) {
        // Reset to the generic label whenever we (re)show or hide, so a stale
        // percentage from a previous run never lingers on screen.
        applyDownloadOverlay(show, show ? "Updating content…" : null);
    }

    /**
     * Update the overlay with live download progress, then auto-hide it when the
     * batch completes — the way common apps show a transient updating indicator.
     * percent < 0 means "size unknown" (indeterminate); render a spinner-ish label.
     */
    private void updateDownloadProgress(int fileIndex, int fileCount, int percent, long doneBytes) {
        StringBuilder sb = new StringBuilder("Updating content…");
        if (percent >= 0) {
            sb.append("  ").append(Math.min(100, Math.max(0, percent))).append('%');
        } else if (doneBytes > 0) {
            sb.append("  ").append(doneBytes / 1024 / 1024).append(" MB");
        }
        if (fileCount > 1) {
            sb.append("\nItem ").append(Math.min(fileIndex + 1, fileCount)).append(" of ").append(fileCount);
        }
        applyDownloadOverlay(true, sb.toString());
    }

    private void applyDownloadOverlay(boolean show, String text) {
        Runnable r = () -> {
            if (downloadStatusOverlay == null) return;
            if (text != null) downloadStatusOverlay.setText(text);
            if (show) {
                // The template overlay lives on android.R.id.content ABOVE
                // rootContainer, so an indicator parked in rootContainer is
                // invisible whenever a template is active — the reported
                // "nothing shows when I update content". Host the hint on the
                // window content root and keep it above whatever was added since.
                try {
                    ViewGroup contentRoot = findViewById(android.R.id.content);
                    if (downloadStatusOverlay.getParent() != contentRoot) {
                        ViewGroup old = (ViewGroup) downloadStatusOverlay.getParent();
                        if (old != null) old.removeView(downloadStatusOverlay);
                        contentRoot.addView(downloadStatusOverlay);
                    }
                    downloadStatusOverlay.bringToFront();
                } catch (Exception ignored) { }
            }
            downloadStatusOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run();
        else ui.post(r);
    }

    /** Apply mute/unmute to every active ExoPlayer and persist the state locally. */
    private void applyMuteToAllPlayers(boolean muted) {
        // Persist so the state survives offline periods and app restarts
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_IS_MUTED, muted).apply();
        float vol = muted ? 0f : 1f;
        ui.post(() -> {
            if (player != null) try { player.setVolume(vol); } catch (Exception ignored) {}
            for (ExoPlayer gp : gridPlayers) {
                if (gp != null) try { gp.setVolume(vol); } catch (Exception ignored) {}
            }
        });
    }

    // ===== HEADER / FOOTER (device-specific, optional) =====
    // Config comes from the heartbeat JSON (null-safe — renders only when present):
    //   header_enabled (bool), header_text (string)
    //   footer_enabled (bool), footer_image_url (S3 presigned image URL)
    private void applyHeaderFooter(JSONObject json) {
        final boolean headerEnabled = json.optBoolean("header_enabled", false);
        final String headerText = json.isNull("header_text") ? "" : json.optString("header_text", "");
        final boolean footerEnabled = json.optBoolean("footer_enabled", false);
        final String footerUrl = json.isNull("footer_image_url") ? "" : json.optString("footer_image_url", "");

        // Styling (colors, font, size, rotation, fit, ...) as one JSON blob
        JSONObject style = null;
        try {
            String raw = json.isNull("header_footer_style") ? null : json.optString("header_footer_style", null);
            if (raw != null && raw.trim().length() > 0) style = new JSONObject(raw);
        } catch (Exception ignored) {}
        final JSONObject headerStyle = style != null ? style.optJSONObject("header") : null;
        final JSONObject footerStyle = style != null ? style.optJSONObject("footer") : null;

        // Header = text
        ui.post(() -> {
            if (headerBar != null) {
                if (headerEnabled && headerText.trim().length() > 0) {
                    headerBar.setText(headerText);
                    applyHeaderStyle(headerStyle);
                    headerBar.setVisibility(View.VISIBLE);
                } else {
                    headerBar.setVisibility(View.GONE);
                }
            }
        });

        // Footer = image
        if (footerBar != null) {
            if (footerEnabled && footerUrl.trim().length() > 0) {
                ui.post(() -> applyFooterStyle(footerStyle));
                loadFooterImage(footerUrl.trim());
            } else {
                lastFooterSig = null;
                ui.post(() -> { footerBar.setImageDrawable(null); footerBar.setVisibility(View.GONE); });
            }
        }
    }

    private int parseColor(String hex, int fallback) {
        try { return Color.parseColor(hex); } catch (Exception e) { return fallback; }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void applyHeaderStyle(JSONObject s) {
        if (headerBar == null) return;
        if (s == null) s = new JSONObject();
        headerBar.setBackgroundColor(parseColor(s.optString("bgColor", "#000000"), Color.parseColor("#CC000000")));
        headerBar.setTextColor(parseColor(s.optString("textColor", "#FFFFFF"), Color.WHITE));
        headerBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) s.optDouble("fontSize", 22));

        String fam = s.optString("fontFamily", "sans");
        Typeface base = "serif".equals(fam) ? Typeface.SERIF
                      : "monospace".equals(fam) ? Typeface.MONOSPACE : Typeface.SANS_SERIF;
        headerBar.setTypeface(base, s.optBoolean("bold", false) ? Typeface.BOLD : Typeface.NORMAL);

        String align = s.optString("align", "center");
        int g = "left".equals(align) ? Gravity.START : "right".equals(align) ? Gravity.END : Gravity.CENTER_HORIZONTAL;
        headerBar.setGravity(g | Gravity.CENTER_VERTICAL);

        headerBar.setRotation((float) s.optInt("rotation", 0));
    }

    private void applyFooterStyle(JSONObject s) {
        if (footerBar == null) return;
        if (s == null) s = new JSONObject();
        footerBar.setBackgroundColor(parseColor(s.optString("bgColor", "#000000"), Color.parseColor("#CC000000")));

        int h = s.optInt("heightDp", 80);
        LinearLayout.LayoutParams lp = (footerBar.getLayoutParams() instanceof LinearLayout.LayoutParams)
                ? (LinearLayout.LayoutParams) footerBar.getLayoutParams()
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(h));
        lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
        lp.height = dpToPx(h);
        footerBar.setLayoutParams(lp);

        String fit = s.optString("scaleType", "fit");
        footerBar.setScaleType("fill".equals(fit) ? ImageView.ScaleType.FIT_XY
                : "center".equals(fit) ? ImageView.ScaleType.CENTER_INSIDE
                : ImageView.ScaleType.FIT_CENTER);

        footerBar.setRotation((float) s.optInt("rotation", 0));
    }

    private void loadFooterImage(final String url) {
        // Presigned URLs change their query string each heartbeat; key on the path only
        // so we don't re-download the same image every time.
        final String cacheKey = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (cacheKey.equals(lastFooterSig)) {
            ui.post(() -> footerBar.setVisibility(View.VISIBLE));
            return;
        }
        lastFooterSig = cacheKey;
        new Thread(() -> {
            final Bitmap b = downloadImage(url, cacheKey);
            ui.post(() -> {
                if (b != null) {
                    footerBar.setImageBitmap(b);
                    footerBar.setVisibility(View.VISIBLE);
                } else {
                    footerBar.setVisibility(View.GONE);
                }
            });
        }).start();
    }

    // Download (and cache locally, so it survives offline) an image and decode it.
    private Bitmap downloadImage(String url, String cacheKey) {
        try {
            File dir = new File(getFilesDir(), "hf");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, Integer.toHexString(cacheKey.hashCode()) + ".img");
            if (!f.exists() || f.length() == 0) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally { c.disconnect(); }
            }
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "header image load failed: " + url, e);
            return null;
        }
    }

    // ===== ROTATION POLLING =====
    private void pollRotationMetadata() {
        if (!isEnrolled) return; // Don't poll when device is not enrolled
        new Thread(() -> {
            try {
                if (!isOnline()) return;
                String deviceId = getAndroidId();
                String urlStr = listDownloadsUrl(deviceId);
                Log.d(TAG, "Polling rotation from: " + urlStr);

                HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setRequestMethod("GET");
                c.connect();

                if (c.getResponseCode() / 100 != 2) { c.disconnect(); return; }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                } finally { c.disconnect(); }

                Map<String, VideoMetadata> byName = new HashMap<>();
                Map<String, VideoMetadata> byFile = new HashMap<>();

                JSONObject obj = new JSONObject(sb.toString());

                // Parse layout mode
                String newLayoutMode = obj.optString("layout_mode", "single");
                boolean layoutChanged = !newLayoutMode.equals(layoutMode);
                layoutMode = newLayoutMode;

                JSONArray items = obj.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it != null) {
                            VideoMetadata vm = new VideoMetadata();
                            vm.videoName = it.optString("video_name", "").trim();
                            vm.filename = it.optString("filename", "").trim();
                            vm.rotation = it.optInt("rotation", 0);
                            vm.fitMode = it.optString("fit_mode", "cover");
                            vm.gridPosition = it.optInt("grid_position", 0);
                            vm.contentType = it.optString("content_type", "video");
                            vm.displayDuration = it.optInt("display_duration", 10);

                            if (!vm.videoName.isEmpty()) byName.put(vm.videoName.toLowerCase(), vm);
                            if (!vm.filename.isEmpty()) byFile.put(vm.filename.toLowerCase(), vm);
                            Log.d(TAG, "Polled: " + vm.videoName + "/" + vm.filename + " rot=" + vm.rotation + " fit=" + vm.fitMode + " grid=" + vm.gridPosition + " type=" + vm.contentType);
                        }
                    }
                }

                metadataByVideoName = byName;
                metadataByFilename = byFile;

                Log.d(TAG, "Layout mode: " + layoutMode + " (changed: " + layoutChanged + ")");

                // Also check download_status - if backend says download needed, trigger refresh
                boolean downloadNeeded = false;
                try {
                    downloadNeeded = !readDownloadStatus(readStatusUrl(deviceId));
                    if (downloadNeeded) {
                        Log.d(TAG, "Backend indicates download_status=false, triggering sync");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error checking download status: " + e.getMessage());
                }

                // If backend says download_status=false, run a full sync FIRST (before
                // checkForMissingVideosAndDownload) and return early.  Using compareAndSet
                // here ensures that even if checkForMissingVideosAndDownload races with us
                // on the next poll interval, only one sync thread can run at a time.
                if (downloadNeeded && downloadInProgress.compareAndSet(false, true)) {
                    File mainDir = ensureMainDir();
                    setDownloadingOverlay(true);
                    try {
                        smartSyncVideos(mainDir, deviceId);
                        postUpdateStatusTrue(updateStatusUrl(deviceId));
                        // After sync, apply the correct mode.
                        // If the layout also changed (e.g. grid→single), we MUST call the
                        // switch function to tear down the old views/codecs first.
                        // Calling playMixedPlaylist() directly while grid players still hold
                        // their BufferQueues causes "waitForFreeSlotThenRelock: timeout".
                        final String currentLayoutMode = layoutMode;
                        final boolean layoutAlsoChanged = layoutChanged;
                        ui.post(() -> {
                            setDownloadingOverlay(false);
                            if (templateActive) {
                                // Template mode: single-mode playback confined to the
                                // playlist zone (textureView is sized to that rect).
                                playMixedPlaylist(mainDir);
                            } else if (layoutAlsoChanged) {
                                // Layout changed during the sync window — do a proper switch
                                if ("single".equals(currentLayoutMode) || currentLayoutMode == null) {
                                    switchToSingleMode();
                                } else {
                                    switchToGridMode();
                                }
                            } else {
                                // Same layout, just refresh content
                                if ("single".equals(currentLayoutMode) || currentLayoutMode == null) {
                                    playMixedPlaylist(mainDir);
                                } else {
                                    switchToGridMode();
                                }
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Sync error: " + e.getMessage());
                        setDownloadingOverlay(false);
                    } finally {
                        downloadInProgress.set(false);
                    }
                    return;  // Skip rotation apply since we're refreshing
                }

                // Check if there are videos we don't have locally - trigger download
                checkForMissingVideosAndDownload(byFile);

                // Template mode: content still downloads (above), but playback stays
                // confined to the template's playlist zone in single-mode — never
                // switch to full-screen grid, which would fight the overlay.
                if (templateActive) {
                    ui.post(() -> { if (!isGridMode) applyRotationForCurrentVideo(); });
                    return;
                }

                // If layout changed, switch between single and grid mode
                if (layoutChanged) {
                    ui.post(() -> {
                        if ("single".equals(layoutMode)) {
                            switchToSingleMode();
                        } else {
                            switchToGridMode();
                        }
                    });
                } else if (isGridMode) {
                    // Apply rotation updates to grid videos live
                    ui.post(this::applyRotationForGridVideos);
                } else {
                    ui.post(this::applyRotationForCurrentVideo);
                }
            } catch (Exception e) {
                Log.e(TAG, "Poll error: " + e.getMessage());
            }
        }).start();
    }

    private VideoMetadata getMetadataForFile(File file) {
        if (file == null) return null;
        String filename = file.getName().toLowerCase();

        VideoMetadata vm = metadataByFilename.get(filename);
        if (vm != null) return vm;

        String base = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
        vm = metadataByVideoName.get(base);
        if (vm != null) return vm;

        for (Map.Entry<String, VideoMetadata> e : metadataByVideoName.entrySet()) {
            if (filename.contains(e.getKey()) || e.getKey().contains(base)) return e.getValue();
        }
        for (Map.Entry<String, VideoMetadata> e : metadataByFilename.entrySet()) {
            String k = e.getKey().replace(".mp4", "");
            if (filename.contains(k) || k.contains(base)) return e.getValue();
        }
        return null;
    }

    // Check if there are videos we need to download OR if video assignments changed
    private String lastKnownVideoState = "";  // JSON-like string of video assignments
    private String lastKnownContentType = ""; // Track content type changes

    private void checkForMissingVideosAndDownload(Map<String, VideoMetadata> serverContent) {
        if (downloadInProgress.get()) return;  // quick non-blocking early exit

        // Handle empty server response (device might have no content assigned)
        if (serverContent.isEmpty()) {
            Log.d(TAG, "No content from server (may be unassigned)");
            return;
        }

        try {
            File mainDir = ensureMainDir();
            Set<String> localFiles = new HashSet<>();
            File[] files = mainDir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".mp4") || lower.endsWith(".jpg") || lower.endsWith(".png") ||
                        lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".jpeg");
            });
            if (files != null) {
                for (File f : files) {
                    localFiles.add(f.getName().toLowerCase());
                }
            }

            Log.d(TAG, "Local files: " + localFiles.size() + ", Server content: " + serverContent.size());

            // Build a state string that includes video name + position + contentType
            // This detects when videos are swapped, replaced, OR content type changes (video <-> image)
            StringBuilder stateBuilder = new StringBuilder();
            List<String> sortedKeys = new ArrayList<>(serverContent.keySet());
            Collections.sort(sortedKeys);
            for (String key : sortedKeys) {
                VideoMetadata vm = serverContent.get(key);
                stateBuilder.append(key).append(":").append(vm.gridPosition)
                        .append(":").append(vm.contentType).append(";");
            }
            String currentState = stateBuilder.toString();
            boolean videoStateChanged = !currentState.equals(lastKnownVideoState);

            Log.d(TAG, "State check - Current: " + currentState);
            Log.d(TAG, "State check - Previous: " + lastKnownVideoState);
            Log.d(TAG, "State changed: " + videoStateChanged);

            // Check if any server content is missing locally
            boolean hasMissingContent = false;
            for (String filename : serverContent.keySet()) {
                if (!localFiles.contains(filename.toLowerCase())) {
                    Log.d(TAG, "Missing content detected: " + filename);
                    hasMissingContent = true;
                    break;
                }
            }

            if (hasMissingContent) {
                // Atomically claim the download lock before spawning the thread.
                // This prevents a TOCTOU race where pollRotationMetadata's inline sync
                // and this background thread both see downloadInProgress=false and both
                // proceed with a simultaneous download of the same files.
                if (!downloadInProgress.compareAndSet(false, true)) {
                    Log.d(TAG, "Download already in progress, skipping duplicate download");
                    return;
                }
                Log.d(TAG, "New content detected, triggering download...");
                ui.post(() -> {
                    toast("New content detected, downloading...");
                    setDownloadingOverlay(true);
                });
                lastKnownVideoState = currentState;

                // Trigger download in background (lock already acquired via compareAndSet above)
                new Thread(() -> {
                    try {
                        String id = getAndroidId();
                        boolean downloadSuccess = smartSyncVideos(mainDir, id);
                        // DON'T update status here - wait until content plays

                        // Capture layoutMode now (on background thread) before posting to UI.
                        // Using isGridMode here is WRONG after a wipe: resetPlaybackState()
                        // clears isGridMode=false, but layoutMode is re-fetched from the
                        // server by pollRotationMetadata() before this download was triggered,
                        // so layoutMode correctly reflects the intended grid configuration.
                        final String modeAfterDownload = layoutMode;
                        ui.post(() -> {
                            setDownloadingOverlay(false);
                            if ("single".equals(modeAfterDownload) || modeAfterDownload == null) {
                                playMixedPlaylist(mainDir);
                            } else {
                                switchToGridMode();
                            }

                            // Update download status AFTER playback starts
                            if (downloadSuccess) {
                                ui.postDelayed(() -> {
                                    new Thread(() -> {
                                        try {
                                            postUpdateStatusTrue(updateStatusUrl(id));
                                            Log.d(TAG, "Download status updated after content refresh");
                                        } catch (Exception e) {
                                            Log.e(TAG, "Failed to update download status: " + e.getMessage());
                                        }
                                    }).start();
                                }, 2000); // 2 second delay
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Download error: " + e.getMessage());
                        setDownloadingOverlay(false);
                    } finally {
                        downloadInProgress.set(false);
                    }
                }).start();
            } else if (videoStateChanged) {
                // Content assignments changed but all files exist - refresh playback
                Log.d(TAG, "Content assignments changed (files already exist), refreshing playback...");
                lastKnownVideoState = currentState;
                ui.post(() -> {
                    toast("Content updated new");
                    if (isGridMode) {
                        switchToGridMode();
                    } else {
                        // Use mixed playlist to handle both videos and images
                        playMixedPlaylist(mainDir);
                    }
                });
            } else {
                // No changes detected
                Log.d(TAG, "No content changes detected");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking for missing content: " + e.getMessage());
        }
    }

    private void applyRotationForCurrentVideo() {
        // Check if we're showing image or video
        if (isShowingImage && !currentImageFiles.isEmpty()) {
            // Currently showing images - check for rotation/fit changes
            if (currentImageIndex < currentImageFiles.size()) {
                File f = currentImageFiles.get(currentImageIndex);
                VideoMetadata vm = getMetadataForFile(f);
                int targetRotation = vm != null ? vm.rotation : 0;
                String fitMode = vm != null ? vm.fitMode : "cover";

                if (targetRotation != lastAppliedRotation || !fitMode.equals(lastAppliedFitMode)) {
                    Log.d(TAG, "Image rotation/fit changed, refreshing...");
                    lastAppliedRotation = targetRotation;
                    lastAppliedFitMode = fitMode;
                    displayImage(f, targetRotation, fitMode);
                }
            }
            return;
        }

        // Video playback mode
        if (player == null || currentPlaylistFiles.isEmpty()) return;
        int idx = player.getCurrentMediaItemIndex();
        if (idx < 0 || idx >= currentPlaylistFiles.size()) return;

        File f = currentPlaylistFiles.get(idx);
        VideoMetadata vm = getMetadataForFile(f);
        int targetRotation = vm != null ? vm.rotation : 0;
        String fitMode = vm != null ? vm.fitMode : "cover";

        // Only apply if rotation or fit mode changed
        if (targetRotation == lastAppliedRotation && fitMode.equals(lastAppliedFitMode)) {
            Log.d(TAG, "Rotation unchanged (" + targetRotation + "°), skipping");
            return;
        }

        Log.d(TAG, "ROTATION CHANGED: " + lastAppliedRotation + " -> " + targetRotation + "° fitMode=" + fitMode + " for " + f.getName());
        lastAppliedRotation = targetRotation;
        lastAppliedFitMode = fitMode;

        applyTextureViewTransform(targetRotation, fitMode);
        toast("Rotation: " + targetRotation + "°");
    }

    /**
     * Apply rotation using TextureView's Matrix transform.
     * This is the proper way to rotate video content.
     */
    private void applyTextureViewTransform(int rotation, String fitMode) {
        if (textureView == null) return;

        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();

        if (viewWidth == 0 || viewHeight == 0) {
            Log.d(TAG, "TextureView not measured yet, retrying...");
            textureView.post(() -> applyTextureViewTransform(rotation, fitMode));
            return;
        }

        // Use video dimensions if known, otherwise use view dimensions
        int vw = videoWidth > 0 ? videoWidth : viewWidth;
        int vh = videoHeight > 0 ? videoHeight : viewHeight;

        Log.d(TAG, "Applying transform: rotation=" + rotation + " fitMode=" + fitMode +
                " view=" + viewWidth + "x" + viewHeight + " video=" + vw + "x" + vh);

        Matrix matrix = new Matrix();

        // Center of the view
        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        // For 90/270 rotation, the video dimensions are swapped after rotation
        boolean isRotated90or270 = (rotation == 90 || rotation == 270);

        // Calculate scale based on fit mode
        float scaleX, scaleY;

        if (isRotated90or270) {
            // After rotation, video width becomes height and vice versa
            // So we compare rotated video dimensions to view dimensions
            float rotatedVideoWidth = vh;  // video height becomes width after rotation
            float rotatedVideoHeight = vw; // video width becomes height after rotation

            if ("contain".equals(fitMode)) {
                // Fit inside - show entire video, may have black bars
                float scale = Math.min((float) viewWidth / rotatedVideoWidth, (float) viewHeight / rotatedVideoHeight);
                scaleX = scale * vw / viewWidth;
                scaleY = scale * vh / viewHeight;
            } else if ("fill".equals(fitMode)) {
                // Stretch to fill exactly (may distort aspect ratio)
                // After 90/270 rotation, what was horizontal becomes vertical and vice versa.
                // TextureView default stretches video to fill its bounds (viewWidth x viewHeight).
                // After rotation, that becomes viewHeight x viewWidth.
                // To make it fill viewWidth x viewHeight again, we must pre-scale so that
                // the rotated result matches the view dimensions.
                scaleX = (float) viewHeight / viewWidth;
                scaleY = (float) viewWidth / viewHeight;
            } else {
                // Cover (default) - fill screen, may crop
                float scale = Math.max((float) viewWidth / rotatedVideoWidth, (float) viewHeight / rotatedVideoHeight);
                scaleX = scale * vw / viewWidth;
                scaleY = scale * vh / viewHeight;
            }
        } else {
            // 0 or 180 rotation - no dimension swap
            if ("contain".equals(fitMode)) {
                float scale = Math.min((float) viewWidth / vw, (float) viewHeight / vh);
                scaleX = scale * vw / viewWidth;
                scaleY = scale * vh / viewHeight;
            } else if ("fill".equals(fitMode)) {
                // Stretch to fill exactly
                scaleX = 1f;
                scaleY = 1f;
            } else {
                // Cover
                float scale = Math.max((float) viewWidth / vw, (float) viewHeight / vh);
                scaleX = scale * vw / viewWidth;
                scaleY = scale * vh / viewHeight;
            }
        }

        // Apply transformations: scale first, then rotate around center
        matrix.setScale(scaleX, scaleY, centerX, centerY);
        matrix.postRotate(rotation, centerX, centerY);

        textureView.setTransform(matrix);
        Log.d(TAG, "Transform applied: rotation=" + rotation + " scaleX=" + scaleX + " scaleY=" + scaleY);
    }

    // ===== STORAGE =====
    private void ensureAllFilesAccessThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else startEverything();
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
                startEverything();
            else legacyPermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private void startEverything() {
        if (isWorking || downloadInProgress.get()) return;
        isWorking = true;
        new Thread(() -> {
            try {
                File mainDir = ensureMainDir(), tmpDir = ensureTempDir();
                String id = getAndroidId();
                try { postOnlineTrue(updateOnlineUrl(id)); } catch (Exception ignored) {}

                if (!isOnline()) {
                    // Load cached layout metadata for correct grid/rotation/fit mode
                    loadCachedLayoutMetadata();
                    ui.post(() -> {
                        toast("Offline - playing local content");
                        if ("single".equals(layoutMode) || layoutMode == null) {
                            playMixedPlaylist(mainDir);
                        } else {
                            switchToGridMode();
                        }
                    });
                    return;
                }

                boolean downloadSuccess = true;
                if (!readDownloadStatus(readStatusUrl(id))) {
                    downloadInProgress.set(true);
                    setDownloadingOverlay(true);
                    try {
                        // SMART SYNC: Only download new, delete unassigned
                        downloadSuccess = smartSyncVideos(mainDir, id);
                        // DON'T update status here - wait until content plays
                    } finally { downloadInProgress.set(false); }
                }

                // Fetch layout mode and metadata BEFORE playing
                fetchLayoutModeAndMetadata(id);

                // Decide single or grid mode based on layout_mode
                final boolean finalDownloadSuccess = downloadSuccess;
                final String finalId = id;
                ui.post(() -> {
                    setDownloadingOverlay(false);
                    if ("single".equals(layoutMode) || layoutMode == null) {
                        // Use mixed playlist to handle both videos and images
                        playMixedPlaylist(mainDir);
                    } else {
                        switchToGridMode();
                    }

                    // Update download status AFTER playback starts (with small delay to ensure it started)
                    if (finalDownloadSuccess) {
                        ui.postDelayed(() -> {
                            new Thread(() -> {
                                try {
                                    postUpdateStatusTrue(updateStatusUrl(finalId));
                                    Log.d(TAG, "Download status updated after playback started");
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to update download status: " + e.getMessage());
                                }
                            }).start();
                        }, 2000); // 2 second delay to ensure content is playing
                    }
                });
            } catch (Exception e) {
                ui.post(() -> toast("Error: " + e.getMessage()));
                // On error, try to play local content anyway with cached layout
                try {
                    File mainDir = ensureMainDir();
                    loadCachedLayoutMetadata();
                    ui.post(() -> {
                        if ("single".equals(layoutMode) || layoutMode == null) {
                            playMixedPlaylist(mainDir);
                        } else {
                            switchToGridMode();
                        }
                    });
                } catch (Exception ignored) {}
            }
            finally { isWorking = false; }
        }).start();
    }

    // Fetch layout mode and metadata synchronously (called from background thread)
    private void fetchLayoutModeAndMetadata(String deviceId) {
        try {
            String urlStr = listDownloadsUrl(deviceId);
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("GET");
            c.connect();

            if (c.getResponseCode() / 100 != 2) { c.disconnect(); return; }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            } finally { c.disconnect(); }

            String jsonStr = sb.toString();
            // Cache the raw JSON for offline recovery
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_LAYOUT_JSON, jsonStr).apply();

            parseLayoutJson(jsonStr);
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch layout mode: " + e.getMessage());
            layoutMode = "single";
        }
    }

    /** Parse layout JSON and populate layoutMode + metadata maps. Called from both online fetch and offline cache. */
    private void parseLayoutJson(String jsonStr) {
        try {
            Map<String, VideoMetadata> byName = new HashMap<>();
            Map<String, VideoMetadata> byFile = new HashMap<>();

            JSONObject obj = new JSONObject(jsonStr);

            // Parse layout mode
            layoutMode = obj.optString("layout_mode", "single");
            Log.d(TAG, "Parsed layout_mode: " + layoutMode);

            JSONArray items = obj.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it != null) {
                        VideoMetadata vm = new VideoMetadata();
                        vm.videoName = it.optString("video_name", "").trim();
                        vm.filename = it.optString("filename", "").trim();
                        vm.rotation = it.optInt("rotation", 0);
                        vm.fitMode = it.optString("fit_mode", "cover");
                        vm.gridPosition = it.optInt("grid_position", 0);
                        vm.contentType = it.optString("content_type", "video");
                        vm.displayDuration = it.optInt("display_duration", 10);

                        if (!vm.videoName.isEmpty()) byName.put(vm.videoName.toLowerCase(), vm);
                        if (!vm.filename.isEmpty()) byFile.put(vm.filename.toLowerCase(), vm);
                    }
                }
            }

            metadataByVideoName = byName;
            metadataByFilename = byFile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse layout JSON: " + e.getMessage());
            layoutMode = "single";
        }
    }

    /** Load cached layout JSON from SharedPreferences. Returns true if cache was found and loaded. */
    private boolean loadCachedLayoutMetadata() {
        try {
            String cachedJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_LAYOUT_JSON, null);
            if (cachedJson != null && !cachedJson.isEmpty()) {
                parseLayoutJson(cachedJson);
                Log.d(TAG, "Loaded cached layout: mode=" + layoutMode);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cached layout: " + e.getMessage());
        }
        return false;
    }

    // Smart sync: only download new videos/images, delete unassigned ones
    // Returns true if all expected content is now available locally
    // ═══════════════════════════════════════════════════════════════════════════
    // STORAGE MANAGEMENT - Report storage & reuse local files
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Read response body from HttpURLConnection
     */
    private String readResponse(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * Reports device storage capacity, usage, and RAM to the server.
     * Called on startup and after downloads for dashboard visibility.
     */
    private void reportStorageToServer() {
        new Thread(() -> {
            try {
                File storageDir = ensureMainDir();
                if (storageDir == null) return;

                // Get storage statistics
                StatFs stat = new StatFs(storageDir.getPath());
                long totalBytes = stat.getTotalBytes();
                long availableBytes = stat.getAvailableBytes();
                long usedBytes = totalBytes - availableBytes;

                // Calculate content storage (files in our app directory)
                long contentBytes = calculateContentSize(storageDir);

                // Get RAM info
                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory();
                long usedMemory = runtime.totalMemory() - runtime.freeMemory();
                long availableMemory = maxMemory - usedMemory;

                // Get system memory info
                android.app.ActivityManager activityManager =
                        (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
                if (activityManager != null) {
                    activityManager.getMemoryInfo(memInfo);
                }

                String url = storageReportUrl(getAndroidId());
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);

                JSONObject body = new JSONObject();
                // Storage info
                body.put("total_bytes", totalBytes);
                body.put("available_bytes", availableBytes);
                body.put("used_bytes", usedBytes);
                body.put("content_bytes", contentBytes);
                body.put("storage_percent_used", (int)((usedBytes * 100) / totalBytes));

                // RAM info
                body.put("ram_total_bytes", maxMemory);
                body.put("ram_available_bytes", availableMemory);
                body.put("ram_used_bytes", usedMemory);
                body.put("system_ram_total", memInfo.totalMem);
                body.put("system_ram_available", memInfo.availMem);
                body.put("low_memory", memInfo.lowMemory);

                // Device type
                body.put("is_tv", isTvDevice);

                try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                    out.writeBytes(body.toString());
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    Log.d(TAG, String.format("Storage reported: total=%dMB, available=%dMB, content=%dMB, RAM=%dMB/%dMB",
                            totalBytes/1024/1024, availableBytes/1024/1024, contentBytes/1024/1024,
                            availableMemory/1024/1024, maxMemory/1024/1024));
                } else {
                    Log.w(TAG, "Storage report failed with code: " + responseCode);
                }

                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error reporting storage: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Delete cached media files that are NOT in the current server-assigned set.
     * This frees storage when the device fills up and, just as importantly, stops
     * stale/unassigned files from ever being picked up by the offline fallback
     * playlist. Safety guards:
     *   - Does nothing unless the assigned set is non-empty (a failed/empty server
     *     fetch must never wipe the cache).
     *   - Never deletes a file that is in the currently-playing playlist.
     * Returns the number of bytes freed.
     *
     * Note: the assigned set comes from the paged downloads list (server cap 200);
     * a device with more than 200 assigned items is not expected for signage.
     */
    private long pruneUnassignedContent(File mainDir, List<String> assignedFilenames) {
        if (mainDir == null || assignedFilenames == null || assignedFilenames.isEmpty()) return 0;
        Set<String> keep = new HashSet<>();
        for (String n : assignedFilenames) if (n != null) keep.add(n.toLowerCase());
        Set<String> playing = new HashSet<>();
        for (File f : currentPlaylistFiles) if (f != null) playing.add(f.getName().toLowerCase());

        File[] files = mainDir.listFiles((d, n) -> {
            String lower = n.toLowerCase();
            return lower.endsWith(".mp4") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp");
        });
        if (files == null) return 0;

        long freed = 0; int removed = 0;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (keep.contains(name) || playing.contains(name)) continue;
            long sz = f.length();
            if (f.delete()) { freed += sz; removed++; Log.d(TAG, "Pruned unassigned content: " + f.getName()); }
            else Log.w(TAG, "Failed to prune unassigned file: " + f.getName());
        }
        if (removed > 0) Log.d(TAG, "Pruned " + removed + " unassigned file(s), freed " + (freed / 1024 / 1024) + "MB");
        return freed;
    }

    /**
     * Calculate total size of downloaded content files.
     */
    private long calculateContentSize(File directory) {
        long size = 0;
        File[] files = directory.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".mp4") || lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".gif") || lower.endsWith(".webp");
        });

        if (files != null) {
            for (File file : files) {
                size += file.length();
            }
        }
        return size;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STORAGE AND RAM CHECKS - Verify before download
    // ═══════════════════════════════════════════════════════════════════════════

    private static class StorageCheckResult {
        boolean canDownload;
        String message;
        long availableMB;
        long totalMB;

        StorageCheckResult(boolean canDownload, String message, long availableMB, long totalMB) {
            this.canDownload = canDownload;
            this.message = message;
            this.availableMB = availableMB;
            this.totalMB = totalMB;
        }
    }

    private static class RamCheckResult {
        boolean canProceed;
        String message;
        long availableMB;
        long totalMB;

        RamCheckResult(boolean canProceed, String message, long availableMB, long totalMB) {
            this.canProceed = canProceed;
            this.message = message;
            this.availableMB = availableMB;
            this.totalMB = totalMB;
        }
    }

    /**
     * Check if device has enough storage space before downloading.
     */
    private StorageCheckResult checkStorageBeforeDownload(int fileCount) {
        try {
            File storageDir = ensureMainDir();
            StatFs stat = new StatFs(storageDir.getPath());

            long availableBytes = stat.getAvailableBytes();
            long totalBytes = stat.getTotalBytes();
            long availableMB = availableBytes / (1024 * 1024);
            long totalMB = totalBytes / (1024 * 1024);

            // Estimate space needed (assume average 50MB per file for videos)
            long estimatedNeededMB = fileCount * 50;

            Log.d(TAG, String.format("Storage check: %dMB available, %dMB total, need ~%dMB for %d files",
                    availableMB, totalMB, estimatedNeededMB, fileCount));

            if (availableMB < MIN_STORAGE_REQUIRED_MB) {
                return new StorageCheckResult(false,
                        String.format("Not enough storage! Only %dMB free (need %dMB minimum)",
                                availableMB, MIN_STORAGE_REQUIRED_MB),
                        availableMB, totalMB);
            }

            if (availableMB < estimatedNeededMB) {
                return new StorageCheckResult(false,
                        String.format("Low storage! %dMB free, may need ~%dMB for %d files",
                                availableMB, estimatedNeededMB, fileCount),
                        availableMB, totalMB);
            }

            return new StorageCheckResult(true, "Storage OK", availableMB, totalMB);

        } catch (Exception e) {
            Log.e(TAG, "Error checking storage: " + e.getMessage());
            // Allow download on error, but log the issue
            return new StorageCheckResult(true, "Storage check failed, proceeding anyway", 0, 0);
        }
    }

    /**
     * Check if device has enough RAM available.
     */
    private RamCheckResult checkRamBeforeDownload() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long availableMemory = maxMemory - usedMemory;

            long availableMB = availableMemory / (1024 * 1024);
            long totalMB = maxMemory / (1024 * 1024);

            Log.d(TAG, String.format("RAM check: %dMB available, %dMB max heap", availableMB, totalMB));

            if (availableMB < MIN_RAM_REQUIRED_MB) {
                // Try to free memory
                System.gc();

                // Re-check after GC
                usedMemory = runtime.totalMemory() - runtime.freeMemory();
                availableMemory = maxMemory - usedMemory;
                availableMB = availableMemory / (1024 * 1024);

                if (availableMB < MIN_RAM_REQUIRED_MB) {
                    return new RamCheckResult(false,
                            String.format("Low memory! Only %dMB RAM free", availableMB),
                            availableMB, totalMB);
                }
            }

            return new RamCheckResult(true, "RAM OK", availableMB, totalMB);

        } catch (Exception e) {
            Log.e(TAG, "Error checking RAM: " + e.getMessage());
            return new RamCheckResult(true, "RAM check failed, proceeding anyway", 0, 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SMART SYNC - Reuses local files, only downloads new content, NEVER deletes
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean smartSyncVideos(File mainDir, String deviceId) throws Exception {
        String url = listDownloadsUrl(deviceId);

        // Get expected filenames from server (both videos and images)
        List<String> expectedFilenames = fetchExpectedFilenames(url);
        List<String> downloadUrls = fetchDownloadUrls(url);

        if (expectedFilenames.isEmpty() && downloadUrls.isEmpty()) {
            Log.d(TAG, "No content assigned to this device");
            return true; // Nothing to download is considered success
        }

        // Get current local files (both videos AND images)
        File[] localFiles = mainDir.listFiles((d, n) -> {
            String lower = n.toLowerCase();
            return lower.endsWith(".mp4") || lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".gif") || lower.endsWith(".webp");
        });
        Set<String> localFilenames = new HashSet<>();
        if (localFiles != null) {
            for (File f : localFiles) {
                localFilenames.add(f.getName().toLowerCase());
            }
        }

        // Determine which files to download (only NEW ones - reuse local!)
        List<String> urlsToDownload = new ArrayList<>();

        for (int i = 0; i < downloadUrls.size(); i++) {
            String urlStr = downloadUrls.get(i);
            String filename = i < expectedFilenames.size() ? expectedFilenames.get(i) : filenameFromUrl(urlStr);

            // KEY: Only download if NOT already local (reuse existing files!)
            if (!localFilenames.contains(filename.toLowerCase())) {
                urlsToDownload.add(urlStr);
                Log.d(TAG, "Will download new content: " + filename);
            } else {
                Log.d(TAG, "Reusing local content: " + filename);
            }
        }

        // Free storage and stop stale playback: delete any cached media that is
        // no longer in the server-assigned set. Guarded inside so an empty/failed
        // server list never wipes the cache, and the currently-playing file is
        // never deleted. This keeps the device to exactly its assigned content
        // (fixes "plays a random old local video") and reclaims space when full.
        pruneUnassignedContent(mainDir, expectedFilenames);

        // Download only NEW content
        if (!urlsToDownload.isEmpty()) {
            // Check storage space before downloading
            StorageCheckResult storageCheck = checkStorageBeforeDownload(urlsToDownload.size());
            if (!storageCheck.canDownload) {
                ui.post(() -> toast("⚠️ " + storageCheck.message));
                Log.e(TAG, "Download aborted: " + storageCheck.message);
                // Report storage status to server so dashboard shows the issue
                reportStorageToServer();
                return false;
            }

            // Check RAM availability
            RamCheckResult ramCheck = checkRamBeforeDownload();
            if (!ramCheck.canProceed) {
                ui.post(() -> toast("⚠️ " + ramCheck.message));
                Log.e(TAG, "Download aborted: " + ramCheck.message);
                return false;
            }

            ui.post(() -> toast("Downloading " + urlsToDownload.size() + " new file(s)…"));
            int downloaded = 0;
            int failed = 0;

            for (int di = 0; di < urlsToDownload.size(); di++) {
                String urlStr = urlsToDownload.get(di);
                try {
                    File f = bigFileDownloadWithResume(urlStr, mainDir, di, urlsToDownload.size());
                    if (f != null && f.exists() && f.length() > 0) {
                        downloaded++;
                        Log.d(TAG, "Downloaded: " + f.getName() + " (" + f.length() + " bytes)");
                    } else {
                        failed++;
                        Log.e(TAG, "Download returned null or empty file for: " + urlStr);
                    }
                } catch (Exception e) {
                    failed++;
                    Log.e(TAG, "Download failed: " + e.getMessage());
                }
            }

            // Report storage after downloads (for dashboard visibility)
            reportStorageToServer();

            final int finalDownloaded = downloaded;
            final int finalFailed = failed;

            if (failed > 0) {
                ui.post(() -> toast("Downloaded " + finalDownloaded + "/" + (finalDownloaded + finalFailed) + " file(s)"));
            } else {
                ui.post(() -> toast("Downloaded " + finalDownloaded + " file(s)"));
            }

            // Refresh playlist if we downloaded something
            if (downloaded > 0) {
                stopPlaybackForRefresh();
            }

            return failed == 0;
        } else {
            Log.d(TAG, "All content is up to date (reusing local files)");
            return true;
        }
    }

    private void startBackgroundCheckIfNeeded() {
        if (!isEnrolled) return; // Don't sync if device is not enrolled
        if (isWorking || downloadInProgress.get()) return;
        isWorking = true;
        new Thread(() -> {
            try {
                File mainDir = ensureMainDir();
                if (!isOnline()) {
                    // Offline: just continue playing local content
                    return;
                }
                String id = getAndroidId();

                // Re-fetch layout metadata so layout changes are picked up
                // even when download_status is already true
                String previousLayoutMode = layoutMode;
                boolean previousGridMode = isGridMode;
                fetchLayoutModeAndMetadata(id);
                boolean layoutChanged = !layoutMode.equals(previousLayoutMode);

                // If layout changed but no download needed, switch mode now
                if (layoutChanged && readDownloadStatus(readStatusUrl(id))) {
                    Log.d(TAG, "Background check: layout changed " + previousLayoutMode + " -> " + layoutMode + " (no download needed)");
                    final String newMode = layoutMode;
                    ui.post(() -> {
                        if ("single".equals(newMode) || newMode == null) {
                            switchToSingleMode();
                        } else {
                            switchToGridMode();
                        }
                    });
                    return;
                }

                if (!readDownloadStatus(readStatusUrl(id))) {
                    downloadInProgress.set(true);
                    setDownloadingOverlay(true);
                    try {
                        // Use smart sync instead of full re-download
                        boolean downloadSuccess = smartSyncVideos(mainDir, id);

                        // Use current layoutMode to decide playback mode
                        final String currentLayoutMode = layoutMode;
                        ui.post(() -> {
                            setDownloadingOverlay(false);
                            if ("single".equals(currentLayoutMode) || currentLayoutMode == null) {
                                playMixedPlaylist(mainDir);
                            } else {
                                switchToGridMode();
                            }

                            // Update download status AFTER playback starts
                            if (downloadSuccess) {
                                ui.postDelayed(() -> {
                                    new Thread(() -> {
                                        try {
                                            postUpdateStatusTrue(updateStatusUrl(id));
                                            Log.d(TAG, "Download status updated after background sync");
                                        } catch (Exception e) {
                                            Log.e(TAG, "Failed to update download status: " + e.getMessage());
                                        }
                                    }).start();
                                }, 2000); // 2 second delay
                            }
                        });
                    } finally { downloadInProgress.set(false); }
                }
            } catch (Exception ignored) {}
            finally { isWorking = false; }
        }).start();
    }

    private void sendOnlineHeartbeat() {
        if (!isEnrolled) return; // Don't send heartbeat if device is not enrolled
        new Thread(() -> {
            try {
                boolean stillActive = postOnlineAndCheckStatus(updateOnlineUrl(getAndroidId()));
                heartbeatFailures = 0; // reachable again — resume normal cadence
                if (!stillActive) {
                    // Device deleted/deactivated or company expired — full teardown
                    // (playback + template overlay + re-armed enrollment watchdog)
                    // via the shared path, then the heartbeat-specific message.
                    Log.d(TAG, "Heartbeat returned inactive - showing enrollment screen");
                    handleDeviceDeactivated();
                    ui.post(() -> {
                        if (retryText != null) {
                            retryText.setText("Device deactivated or subscription expired. Waiting for reactivation...");
                        }
                    });
                }
            } catch (Exception e) {
                heartbeatFailures++; // next poll backs off (capped at 5 min)
                Log.e(TAG, "Heartbeat error (" + heartbeatFailures + " consecutive): " + e.getMessage());
            }
        }).start();
    }

    /**
     * Post online status and check if device is still active.
     * Returns true if device should continue playing, false if it should show enrollment screen.
     * Also handles wipe_videos command from server.
     */
    private boolean postOnlineAndCheckStatus(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            // Include app_version so the fleet is visible in the dashboard (heartbeat
            // is the only signal that reaches every device), plus crash telemetry
            // from the DgxApp supervisor — a crash-looping cohort must be visible
            // in the dashboard within a heartbeat, not discovered on site.
            // crash_count is a monotonic GAUGE (device-lifetime total), not a
            // delta: retransmits after a lost response are idempotent and no
            // reset handshake exists to drift out of sync. overlay_ok reports
            // whether crash-relaunch works on this box (Android 10+ needs the
            // "Display over other apps" grant) so un-provisioned screens are
            // visible in the dashboard instead of discovered on site.
            android.content.SharedPreferences cp =
                    getSharedPreferences(DgxApp.CRASH_PREFS, MODE_PRIVATE);
            int crashTotal = cp.getInt(DgxApp.K_COUNT_TOTAL, 0);
            long lastCrashAt = cp.getLong(DgxApp.K_LAST_AT, 0);
            String lastCrashMsg = cp.getString(DgxApp.K_LAST_MSG, "");
            boolean overlayOk = android.os.Build.VERSION.SDK_INT < 23
                    || android.provider.Settings.canDrawOverlays(this);
            String body = "{\"is_online\": true, \"resolution\": \""
                    + screenWidth + "x" + screenHeight + "\", \"app_version\": \""
                    + BuildConfig.VERSION_NAME + "\""
                    + ", \"crash_count\": " + crashTotal
                    + ", \"last_crash_at\": " + lastCrashAt
                    + ", \"last_crash_msg\": " + JSONObject.quote(lastCrashMsg == null ? "" : lastCrashMsg)
                    + ", \"overlay_ok\": " + overlayOk
                    + "}";
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = c.getResponseCode();

        // 404 means device not found or deactivated
        if (responseCode == 404) {
            c.disconnect();
            return false;
        }

        // For 200, parse the response to check is_active, company_expired, and wipe_videos
        if (responseCode == 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String response = sb.toString();

                // Parse JSON response
                JSONObject json = new JSONObject(response);

                // Deactivation verdict FIRST: the caller swallows exceptions, so a
                // failure in any cosmetic side-effect below must never mask an
                // inactive/expired signal (device would keep playing forever).
                if (json.optBoolean("company_expired", false)) {
                    Log.d(TAG, "Company subscription expired");
                    c.disconnect();
                    return false;
                }
                if (!json.optBoolean("is_active", true)) {
                    Log.d(TAG, "Device is not active");
                    c.disconnect();
                    return false;
                }
                if (json.optBoolean("force_refresh", false)) {
                    Log.d(TAG, "Force refresh flag is set");
                    c.disconnect();
                    return false;
                }

                try {
                    // Check for wipe_videos command from server
                    if (json.optBoolean("wipe_videos", false)) {
                        Log.d(TAG, "Server requested video wipe - deleting all local content");
                        ui.post(() -> wipeAllLocalVideos());
                    }

                    // Apply mute/unmute from dashboard setting
                    applyMuteToAllPlayers(json.optBoolean("is_muted", false));

                    // Cache BLE device ID set by admin in web dashboard
                    String bleId = json.isNull("ble_device_id") ? "" : json.optString("ble_device_id", "");
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(PREF_BLE_DEVICE_ID, bleId).apply();

                    // Apply optional device-specific header (text) / footer (images)
                    applyHeaderFooter(json);

                    // Screen template change detection: the backend merges
                    // template_stamp into the heartbeat; fetch/apply only when it moves.
                    handleTemplateStamp(json.isNull("template_stamp") ? null : json.optString("template_stamp", null));
                } catch (Exception e) {
                    Log.w(TAG, "Heartbeat side-effect failed (device stays active): " + e.getMessage());
                }
            }
        } else {
            // Transient server trouble (5xx/ALB errors) — stay active, but say so:
            // silent fall-through made delayed delete-detection undiagnosable.
            Log.w(TAG, "Heartbeat unexpected HTTP " + responseCode + " — treating as still active");
        }

        c.disconnect();
        return true;
    }

    // ===== SCREEN TEMPLATE =====

    private static String templateUrl(String id) { return API_BASE + "/device/" + id + "/template"; }

    /**
     * Called from the heartbeat with the server's template_stamp (or null when the
     * company has no template linked). Fetches + applies only when it changes.
     */
    private void handleTemplateStamp(String stamp) {
        if (stamp == null) {
            if (templateActive) ui.post(this::clearTemplate);
            templateStamp = null;
            return;
        }
        if (stamp.equals(templateStamp) && templateActive) {
            // Unchanged content — but periodically re-fetch anyway to renew the
            // zone media presigned URLs before they expire (silent, no hint).
            if (System.currentTimeMillis() - lastTemplateApplyMs < TEMPLATE_REFRESH_MS) return;
            Log.d(TAG, "Template unchanged; refresh interval elapsed — renewing zone URLs");
            fetchAndApplyTemplate(stamp, false);
            return;
        }
        fetchAndApplyTemplate(stamp, true);
    }

    private void fetchAndApplyTemplate(final String stamp) {
        fetchAndApplyTemplate(stamp, true);
    }

    private void fetchAndApplyTemplate(final String stamp, final boolean showHint) {
        if (!templateFetchInProgress.compareAndSet(false, true)) return;
        // Show a brief on-screen "Updating content…" hint when a LIVE template's
        // content changes (dashboard edit), so an operator sees the box refresh
        // instead of it happening silently. Not shown on first/offline load or on
        // a silent periodic URL-renewal refresh.
        final boolean liveUpdate = templateActive && showHint;
        if (liveUpdate) ui.post(() -> applyDownloadOverlay(true, "Updating content…"));
        new Thread(() -> {
            boolean applied = false;
            try {
                String id = getAndroidId();
                HttpURLConnection c = (HttpURLConnection) new URL(templateUrl(id)).openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code == 404) { c.disconnect(); templateStamp = null; ui.post(this::clearTemplate); return; }
                if (code / 100 != 2) { c.disconnect(); return; }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                } finally { c.disconnect(); }
                final JSONObject tpl = new JSONObject(sb.toString());
                // Cache for offline boot.
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_TEMPLATE_JSON, sb.toString()).apply();
                // Trust the stamp in the response (fallback to the heartbeat's).
                templateStamp = tpl.isNull("template_stamp") ? stamp : tpl.optString("template_stamp", stamp);
                lastTemplateApplyMs = System.currentTimeMillis();
                ui.post(() -> applyTemplate(tpl));
                applied = true;
            } catch (Exception e) {
                Log.e(TAG, "template fetch failed: " + e.getMessage());
            } finally {
                templateFetchInProgress.set(false);
                // Hide the hint just after the rebuild (kept briefly so it's visible),
                // or immediately if the fetch failed. applyTemplate re-adds the same
                // overlay view on top, preserving this state until we hide it.
                if (liveUpdate) {
                    // Hold long enough that a person watching the screen actually
                    // reads it — 1s vanished before operators noticed it.
                    final long delay = applied ? 2500L : 0L;
                    ui.post(() -> {
                        if (downloadStatusOverlay != null)
                            downloadStatusOverlay.postDelayed(() -> applyDownloadOverlay(false, null), delay);
                        else applyDownloadOverlay(false, null);
                    });
                }
            }
        }).start();
    }

    /**
     * Render a template as a transparent overlay on the activity's content root.
     * The overlay lives ABOVE rootContainer (which the player rebuilds), so it
     * survives every textureView/player teardown. The playlist plays full-screen
     * in single mode BEHIND the overlay and shows through the transparent
     * playlist-zone hole; opaque header/qr/promo zones mask the rest.
     */
    /** True if the template defines a playlist (rotation) zone. */
    private boolean templateHasPlaylistZone(JSONObject tpl) {
        JSONArray zones = tpl == null ? null : tpl.optJSONArray("zones");
        if (zones == null) return false;
        for (int i = 0; i < zones.length(); i++) {
            JSONObject z = zones.optJSONObject(i);
            if (z != null && "playlist".equals(z.optString("type", ""))) return true;
        }
        return false;
    }

    private void applyTemplate(JSONObject tpl) {
        try {
            templateActive = true;
            String o = tpl.optString("orientation", "landscape");
            int want = "portrait".equals(o)
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            if (getRequestedOrientation() != want) setRequestedOrientation(want);

            // Full-screen single-mode playback behind the overlay — ONLY when the
            // template actually has a playlist zone. A template built entirely from
            // fixed zones (text/qr/image/video) has no rotation, so starting the
            // main playlist here was pointless and, on a device with no assigned
            // rotation, popped a spurious "No media files found" toast over a
            // perfectly-good template.
            boolean hasPlaylistZone = templateHasPlaylistZone(tpl);
            if (hasPlaylistZone) {
                if (isGridMode) {
                    switchToSingleMode();
                } else {
                    try { File d = ensureMainDir(); if (d != null) playMixedPlaylist(d); } catch (Exception ignored) {}
                }
            } else {
                // No playlist zone in this template — NOTHING may keep playing
                // behind the overlay (grid players/slideshow would be invisible
                // but audible; leftovers also bleed through gaps between zones).
                try {
                    imageTimerHandler.removeCallbacksAndMessages(null);
                    stopImageSlideshow();
                    if (player != null) { player.stop(); player.clearMediaItems(); }
                    releaseGridResources();
                    isGridMode = false;
                } catch (Exception ignored) {}
            }

            ViewGroup contentRoot = findViewById(android.R.id.content);
            if (templateOverlay != null) {
                try { contentRoot.removeView(templateOverlay); } catch (Exception ignored) {}
            }
            if (templateRenderer != null) templateRenderer.release(); // free zone video decoders
            // Zone math must use the TARGET orientation's dimensions: the cached
            // onCreate() values are stale when the template just forced a rotation
            // (portrait phone + landscape template drew every zone swapped).
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            int tw = dm.widthPixels, th = dm.heightPixels;
            boolean wantPortrait = "portrait".equals(o);
            if (wantPortrait != (th >= tw)) { int t = tw; tw = th; th = t; }
            templateRenderer = new TemplateRenderer(this, tw, th);
            templateOverlay = templateRenderer.build(tpl);
            contentRoot.addView(templateOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            // The fresh template overlay just landed above everything — keep the
            // "Updating content…" hint visible THROUGH the rebuild, or a live
            // content update shows no feedback at all.
            if (downloadStatusOverlay != null && downloadStatusOverlay.getVisibility() == View.VISIBLE) {
                applyDownloadOverlay(true, null);
            }
            applyImmersive();
            Log.d(TAG, "Template applied (id=" + tpl.optString("template_id") + ", stamp=" + templateStamp + ")");
        } catch (Exception e) {
            Log.e(TAG, "applyTemplate error: " + e.getMessage());
        }
    }

    /**
     * Tear down the template overlay + its zone video players WITHOUT resuming
     * playback. Shared by clearTemplate() (which then resumes) and
     * handleDeviceDeactivated() (which must NOT resume). The overlay sits above
     * the whole window (android.R.id.content), so leaving it up would cover the
     * enrollment screen — a deleted device kept rendering its template forever.
     * Must run on the UI thread.
     */
    private void removeTemplateOverlay() {
        templateActive = false;
        try {
            ViewGroup contentRoot = findViewById(android.R.id.content);
            if (templateOverlay != null) { contentRoot.removeView(templateOverlay); templateOverlay = null; }
        } catch (Exception ignored) {}
        if (templateRenderer != null) templateRenderer.release(); // free zone video decoders
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_TEMPLATE_JSON).apply();
    }

    private void clearTemplate() {
        if (!templateActive && templateOverlay == null) return;
        removeTemplateOverlay();
        // Resume normal full-screen playback.
        try {
            if ("single".equals(layoutMode) || layoutMode == null) {
                File d = ensureMainDir(); if (d != null) playMixedPlaylist(d);
            } else {
                switchToGridMode();
            }
        } catch (Exception ignored) {}
        Log.d(TAG, "Template cleared; resumed normal playback");
    }

    /** On offline/enrolled boot, render the last cached template (if any). */
    private void loadCachedTemplateIfAny() {
        String cached = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_TEMPLATE_JSON, null);
        if (cached == null) return;
        try {
            final JSONObject tpl = new JSONObject(cached);
            templateStamp = tpl.optString("template_stamp", null);
            ui.post(() -> applyTemplate(tpl));
        } catch (Exception e) {
            Log.w(TAG, "cached template parse failed: " + e.getMessage());
        }
    }

    /**
     * Simple fire-and-forget online heartbeat (used during startup).
     * For regular heartbeats, use sendOnlineHeartbeat() which checks the response.
     */
    private void postOnlineTrue(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            String body = "{\"is_online\": true, \"resolution\": \""
                    + screenWidth + "x" + screenHeight + "\"}";
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        c.getResponseCode();
        c.disconnect();
    }

    private void atomicSwapIntoMain(File main, File tmp) {
        File backup = new File(main.getParent(), main.getName() + "_old");
        try {
            if (backup.exists()) { deleteAllInDirectory(backup); backup.delete(); }
            if (main.exists()) main.renameTo(backup);
            tmp.renameTo(main);
            if (backup.exists()) { deleteAllInDirectory(backup); backup.delete(); }
        } catch (Exception ignored) {}
    }

    private void stopPlaybackForRefresh() {
        ui.post(() -> { if (player != null) { player.stop(); player.clearMediaItems(); } });
    }

    private void stopAllPlayback() {
        // Stop main player
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }

        // Stop grid players and release resources
        releaseGridResources();

        // Stop TV time-sharing rotation (for Android TV grid mode)
        stopTvRotation();

        // Stop image slideshow
        stopImageSlideshow();

        // Hide all views
        if (textureView != null) textureView.setVisibility(View.GONE);
        if (imageView != null) imageView.setVisibility(View.GONE);

        // Hide grid texture views
        for (int i = 0; i < gridTextureViews.length; i++) {
            if (gridTextureViews[i] != null) {
                gridTextureViews[i].setVisibility(View.GONE);
            }
        }

        // Hide grid image views
        for (int i = 0; i < gridImageViews.length; i++) {
            if (gridImageViews[i] != null) {
                gridImageViews[i].setVisibility(View.GONE);
            }
        }

        // Reset grid mode
        isGridMode = false;

        // Clear playlist
        currentPlaylistFiles.clear();

        Log.d(TAG, "All playback stopped");
    }

    /**
     * Delete all video and image files from the device storage.
     * Called when dashboard sends a "wipe_videos" command.
     * This deletes from the same folder where videos are downloaded.
     */
    private void wipeAllLocalVideos() {
        Log.d(TAG, "Wiping all local videos from device storage...");

        // Stop any current playback first
        stopAllPlayback();

        new Thread(() -> {
            try {
                // Get the main video directory - same location where videos are downloaded
                File mainDir = ensureMainDir();
                File tempDir = ensureTempDir();

                int deletedCount = 0;

                // Delete all files in main directory
                if (mainDir.exists() && mainDir.isDirectory()) {
                    File[] files = mainDir.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.isFile()) {
                                String name = file.getName().toLowerCase();
                                // Delete video and image files
                                if (name.endsWith(".mp4") || name.endsWith(".webm") ||
                                        name.endsWith(".mkv") || name.endsWith(".avi") ||
                                        name.endsWith(".mov") || name.endsWith(".3gp") ||
                                        name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                                        name.endsWith(".png") || name.endsWith(".gif") ||
                                        name.endsWith(".webp") || name.endsWith(".bmp")) {
                                    if (file.delete()) {
                                        deletedCount++;
                                        Log.d(TAG, "Deleted: " + file.getName());
                                    } else {
                                        Log.e(TAG, "Failed to delete: " + file.getName());
                                    }
                                }
                            }
                        }
                    }
                }

                // Also clean temp directory
                if (tempDir.exists() && tempDir.isDirectory()) {
                    File[] tempFiles = tempDir.listFiles();
                    if (tempFiles != null) {
                        for (File file : tempFiles) {
                            if (file.isFile()) {
                                if (file.delete()) {
                                    Log.d(TAG, "Deleted temp file: " + file.getName());
                                }
                            }
                        }
                    }
                }

                final int finalCount = deletedCount;
                Log.d(TAG, "Wipe complete. Deleted " + finalCount + " files.");

                // Clear metadata cache
                metadataByVideoName.clear();
                metadataByFilename.clear();
                currentPlaylistFiles.clear();
                currentImageFiles.clear();

                // Reset playback state on UI thread
                ui.post(() -> {
                    resetPlaybackState();
                    toast("Deleted " + finalCount + " files. Waiting for new content...");
                });

            } catch (Exception e) {
                Log.e(TAG, "Error wiping videos: " + e.getMessage());
                ui.post(() -> toast("Error deleting files: " + e.getMessage()));
            }
        }).start();
    }

    private void deleteAllInDirectory(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) { deleteAllInDirectory(f); f.delete(); }
            else f.delete();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(net);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private List<String> fetchDownloadUrls(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS); c.setReadTimeout(READ_TIMEOUT_MS); c.setRequestMethod("GET"); c.connect();
        int responseCode = c.getResponseCode();

        // Handle 404 gracefully - device has no videos linked yet
        if (responseCode == 404) {
            c.disconnect();
            Log.i(TAG, "No videos linked to this device yet (404)");
            return new ArrayList<>();
        }

        if (responseCode / 100 != 2) {
            c.disconnect();
            throw new RuntimeException("HTTP error: " + responseCode);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        } finally { c.disconnect(); }
        List<String> urls = new ArrayList<>();
        JSONArray items = new JSONObject(sb.toString()).optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            String u = items.optJSONObject(i).optString("url", "").trim();
            if (!u.isEmpty()) urls.add(u);
        }
        return urls;
    }

    // Returns list of expected filenames from server
    private List<String> fetchExpectedFilenames(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS); c.setReadTimeout(READ_TIMEOUT_MS); c.setRequestMethod("GET"); c.connect();
        if (c.getResponseCode() / 100 != 2) { c.disconnect(); return new ArrayList<>(); }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        } finally { c.disconnect(); }
        List<String> filenames = new ArrayList<>();
        JSONArray items = new JSONObject(sb.toString()).optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            String filename = item.optString("filename", "").trim();
            if (filename.isEmpty()) {
                // Extract filename from URL if not provided
                String urlStr = item.optString("url", "").trim();
                if (!urlStr.isEmpty()) {
                    filename = filenameFromUrl(urlStr);
                }
            }
            if (!filename.isEmpty()) filenames.add(filename);
        }
        return filenames;
    }

    private int downloadAllWithResume(List<String> urls, File dir) {
        if (urls == null || urls.isEmpty()) return 0;
        ui.post(() -> toast("Downloading " + urls.size() + " video(s)…"));
        int ok = 0;
        for (int di = 0; di < urls.size(); di++) {
            String url = urls.get(di);
            try {
                File f = bigFileDownloadWithResume(url, dir, di, urls.size());
                if (f != null && f.exists() && f.length() > 0) ok++;
            } catch (Exception e) { Log.e(TAG, "DL fail: " + e.getMessage()); }
        }
        return ok;
    }

    private File bigFileDownloadWithResume(String urlStr, File dir) throws Exception {
        return bigFileDownloadWithResume(urlStr, dir, 0, 1);
    }

    private File bigFileDownloadWithResume(String urlStr, File dir, int fileIndex, int fileCount) throws Exception {
        String name = filenameFromUrl(urlStr);
        File part = new File(dir, name + ".part");
        long have = part.exists() ? part.length() : 0;
        String finalUrl = resolveRedirects(urlStr);

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(finalUrl).openConnection();
                c.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
                c.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
                if (have > 0) {
                    c.setRequestProperty("Range", "bytes=" + have + "-");
                    Log.d(TAG, "Resuming download from byte " + have);
                }

                int code = c.getResponseCode();
                Log.d(TAG, "Download response code: " + code + " for " + name);

                if (code == 200 || code == 206) {
                    // Get total file size
                    long contentLength = c.getContentLengthLong();
                    long totalSize = (code == 206) ? have + contentLength : contentLength;

                    // Check if we have enough storage for this file
                    if (totalSize > 0) {
                        StatFs stat = new StatFs(dir.getPath());
                        long availableBytes = stat.getAvailableBytes();
                        if (availableBytes < totalSize + (10 * 1024 * 1024)) { // Need file size + 10MB buffer
                            throw new RuntimeException("Not enough storage space for " + name +
                                    " (need " + (totalSize / 1024 / 1024) + "MB, have " + (availableBytes / 1024 / 1024) + "MB)");
                        }
                    }

                    String cd = c.getHeaderField("Content-Disposition");
                    String fn = cd != null && cd.contains("filename=") ?
                            cd.substring(cd.indexOf("filename=") + 9).replace("\"", "").trim() : name;
                    File out = new File(dir, fn.isEmpty() ? name : fn);

                    if (code == 200 && have > 0) {
                        part.delete();
                        have = 0;
                    }

                    try (InputStream in = c.getInputStream();
                         FileOutputStream fos = new FileOutputStream(part, have > 0)) {
                        byte[] buf = new byte[131072]; // 128KB buffer
                        int n;
                        long downloaded = have;
                        long lastProgressLog = System.currentTimeMillis();
                        long lastUiUpdate = 0;
                        int lastUiPercent = -1;

                        while ((n = in.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                            downloaded += n;

                            long now = System.currentTimeMillis();

                            // Live on-screen progress: update when the whole-number
                            // percent changes, throttled to ~300ms so we never spam
                            // the UI thread on fast connections.
                            int percent = totalSize > 0 ? (int) ((downloaded * 100) / totalSize) : -1;
                            if (percent != lastUiPercent && now - lastUiUpdate > 300) {
                                lastUiPercent = percent;
                                lastUiUpdate = now;
                                updateDownloadProgress(fileIndex, fileCount, percent, downloaded);
                            }

                            // Log progress every 5 seconds
                            if (now - lastProgressLog > 5000) {
                                lastProgressLog = now;
                                if (totalSize > 0) {
                                    Log.d(TAG, "Downloading " + name + ": " + percent + "% (" +
                                            (downloaded / 1024 / 1024) + "MB / " + (totalSize / 1024 / 1024) + "MB)");
                                } else {
                                    Log.d(TAG, "Downloading " + name + ": " + (downloaded / 1024 / 1024) + "MB");
                                }
                            }
                        }
                        fos.flush();
                    }

                    c.disconnect();

                    // Verify file was written correctly
                    if (!part.exists() || part.length() == 0) {
                        throw new RuntimeException("Download completed but file is empty: " + name);
                    }

                    // Rename part file to final name
                    if (out.exists()) out.delete();
                    if (part.renameTo(out)) {
                        Log.d(TAG, "Download complete: " + out.getName() + " (" + (out.length() / 1024 / 1024) + "MB)");
                        return out;
                    } else {
                        throw new RuntimeException("Failed to rename downloaded file: " + name);
                    }
                } else if (code == 416) {
                    // Range not satisfiable - file may be complete, or server doesn't support resume
                    Log.w(TAG, "Range not satisfiable, deleting partial and retrying full download");
                    part.delete();
                    have = 0;
                    c.disconnect();
                    continue;
                } else {
                    Log.e(TAG, "Download failed with code " + code + " for " + name);
                    c.disconnect();
                }
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "Download timeout for " + name + " (attempt " + (attempt + 1) + "): " + e.getMessage());
                if (c != null) c.disconnect();
            } catch (java.io.IOException e) {
                Log.e(TAG, "Download IO error for " + name + " (attempt " + (attempt + 1) + "): " + e.getMessage());
                if (c != null) c.disconnect();
            }

            // Wait before retry with exponential backoff
            long sleepTime = 2000L * (attempt + 1);
            Log.d(TAG, "Retrying download in " + sleepTime + "ms (attempt " + (attempt + 2) + "/" + MAX_RETRIES + ")");
            Thread.sleep(sleepTime);
            have = part.exists() ? part.length() : 0;
        }

        // Clean up partial file on final failure
        if (part.exists()) {
            part.delete();
        }
        throw new RuntimeException("Download failed after " + MAX_RETRIES + " attempts: " + name);
    }

    private String resolveRedirects(String url) throws Exception {
        for (int i = 0; i < 10; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("HEAD"); c.connect();
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (code / 100 == 3 && loc != null) { url = loc; continue; }
            return url;
        }
        return url;
    }

    private String filenameFromUrl(String url) {
        String p = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int s = p.lastIndexOf('/');
        String n = s >= 0 ? p.substring(s + 1) : "video.mp4";
        return n.isEmpty() ? "video.mp4" : n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void playLocalPlaylistOrToast(File dir, int thisGeneration) {
        List<File> allFiles = listMp4(dir);
        if (allFiles.isEmpty()) { if (!templateActive) ui.post(() -> toast("No videos found")); return; }

        // Filter to only assigned videos (files with server metadata)
        List<File> files = new ArrayList<>();
        for (File f : allFiles) {
            VideoMetadata vm = getMetadataForFile(f);
            if (vm != null) {
                files.add(f);
            } else {
                Log.d(TAG, "Playlist: skipping unassigned video: " + f.getName());
            }
        }

        // Fall back to all files only if no metadata exists at all (offline, no cache)
        if (files.isEmpty() && !metadataByFilename.isEmpty()) {
            Log.d(TAG, "No assigned videos found. Nothing to play.");
            ui.post(() -> toast("No assigned videos"));
            return;
        } else if (files.isEmpty()) {
            Log.w(TAG, "No metadata available, falling back to all videos");
            files = allFiles;
        }

        final List<File> playFiles = files;
        currentPlaylistFiles = new ArrayList<>(playFiles);

        // Pre-load ALL video metadata into a map for instant access
        final Map<String, VideoMetadata> metadataCache = new HashMap<>();
        for (File f : playFiles) {
            VideoMetadata vm = getMetadataForFile(f);
            if (vm != null) {
                metadataCache.put(f.getName().toLowerCase(), vm);
            }
        }

        // Get first video's rotation
        VideoMetadata firstVm = metadataCache.get(playFiles.get(0).getName().toLowerCase());
        int firstRotation = firstVm != null ? firstVm.rotation : 0;
        String firstFitMode = firstVm != null ? firstVm.fitMode : "cover";
        lastAppliedRotation = firstRotation;
        lastAppliedFitMode = firstFitMode;

        ui.post(() -> {
            // CRITICAL: If a newer playback session was started while we were queued,
            // bail out. This prevents two ExoPlayers from running simultaneously
            // (which causes dual audio streams and frozen video from buffer contention).
            if (thisGeneration != playbackGeneration) {
                Log.d(TAG, "playLocalPlaylistOrToast: stale generation " + thisGeneration + " vs current " + playbackGeneration + ", skipping");
                return;
            }
            // IMPORTANT: Stop image slideshow if active and switch to video mode
            imageTimerHandler.removeCallbacksAndMessages(null);
            isShowingImage = false;
            if (imageView != null) {
                imageView.setVisibility(View.GONE);
            }
            if (textureView != null) {
                textureView.setVisibility(View.VISIBLE);
            }

            initPlayer();
            List<MediaItem> items = new ArrayList<>();
            for (File f : playFiles) if (f.exists() && f.length() > 0) items.add(MediaItem.fromUri(Uri.fromFile(f)));
            if (items.isEmpty()) { toast("No playable videos"); return; }

            // Start with near-invisible alpha to hide initial frame while rotation is applied,
            // then fade in once video size is known.
            // CRITICAL: Do NOT use 0f here. On Samsung/Qualcomm devices, alpha=0 causes the
            // hardware compositor to skip compositing the TextureView entirely, which means
            // updateTexImage() is never called on the SurfaceTexture. This starves the video
            // decoder's BufferQueue (all slots dequeued, none acquired) causing permanent
            // "waitForFreeSlotThenRelock: timeout" and frozen video. Using 0.01f keeps the
            // compositor running while being effectively invisible.
            textureView.setAlpha(0.01f);
            applyTextureViewTransform(firstRotation, firstFitMode);

            player.setMediaItems(items, true);
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
            player.addListener(new Player.Listener() {
                @Override
                public void onMediaItemTransition(MediaItem m, int r) {
                    int nextIdx = player.getCurrentMediaItemIndex();
                    if (nextIdx >= 0 && nextIdx < currentPlaylistFiles.size()) {
                        File nextFile = currentPlaylistFiles.get(nextIdx);
                        VideoMetadata nextVm = metadataCache.get(nextFile.getName().toLowerCase());
                        int nextRotation = nextVm != null ? nextVm.rotation : 0;
                        String nextFitMode = nextVm != null ? nextVm.fitMode : "cover";

                        // Apply rotation immediately - textureView should already be hidden
                        applyTextureViewTransform(nextRotation, nextFitMode);
                        lastAppliedRotation = nextRotation;
                        lastAppliedFitMode = nextFitMode;

                        // Fade in after rotation is applied
                        textureView.postDelayed(() -> {
                            if (textureView != null && textureView.getAlpha() < 1f) {
                                textureView.animate().alpha(1f).setDuration(200).start();
                            }
                        }, 50); // Small delay to ensure rotation is applied
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    videoWidth = size.width;
                    videoHeight = size.height;
                    Log.d(TAG, "Video size: " + videoWidth + "x" + videoHeight);
                    // Re-apply current rotation (not default!)
                    applyTextureViewTransform(lastAppliedRotation, lastAppliedFitMode);

                    // Fade in if this is the first video load
                    if (textureView != null && textureView.getAlpha() < 1f) {
                        textureView.postDelayed(() -> {
                            textureView.animate().alpha(1f).setDuration(200).start();
                        }, 50);
                    }
                }
            });

            // Start a handler to check for upcoming transitions and pre-fade
            startTransitionWatcher(metadataCache);

            player.prepare();
            player.play();
        });
    }

    // Watch for upcoming video transitions and fade out before they happen
    private void startTransitionWatcher(final Map<String, VideoMetadata> metadataCache) {
        // Cancel any previously-running watcher and bump the generation so stale
        // Runnables self-terminate. Without this, every playLocalPlaylistOrToast call
        // leaked a new Handler that kept fading textureView to 0.01f, racing against
        // onVideoSizeChanged's fade-in and leaving the screen permanently invisible.
        transitionWatcherHandler.removeCallbacksAndMessages(null);
        final int myGeneration = ++transitionWatcherGeneration;

        final Runnable transitionChecker = new Runnable() {
            @Override
            public void run() {
                // Stop if a newer playback session has started
                if (myGeneration != transitionWatcherGeneration) return;

                if (player == null || currentPlaylistFiles == null || currentPlaylistFiles.size() <= 1) {
                    return;
                }

                long position = player.getCurrentPosition();
                long duration = player.getDuration();

                // If we're within 300ms of the end, start fading out and pre-apply next rotation
                if (duration > 0 && position > 0 && (duration - position) < 300 && (duration - position) > 50) {
                    if (textureView != null && textureView.getAlpha() > 0.5f) {
                        // Pre-apply the NEXT video's rotation while fading out
                        int currentIdx = player.getCurrentMediaItemIndex();
                        int nextIdx = (currentIdx + 1) % currentPlaylistFiles.size();
                        File nextFile = currentPlaylistFiles.get(nextIdx);
                        VideoMetadata nextVm = metadataCache.get(nextFile.getName().toLowerCase());
                        int nextRotation = nextVm != null ? nextVm.rotation : 0;
                        String nextFitMode = nextVm != null ? nextVm.fitMode : "cover";

                        // Fade out and pre-apply rotation when fade completes
                        textureView.animate()
                                .alpha(0.01f)
                                .setDuration(200)
                                .withEndAction(() -> {
                                    applyTextureViewTransform(nextRotation, nextFitMode);
                                    lastAppliedRotation = nextRotation;
                                    lastAppliedFitMode = nextFitMode;
                                })
                                .start();
                    }
                }

                // Keep checking while playing
                if (myGeneration == transitionWatcherGeneration && player != null && player.isPlaying()) {
                    transitionWatcherHandler.postDelayed(this, 50);
                }
            }
        };

        // Start checking after playback begins
        transitionWatcherHandler.postDelayed(transitionChecker, 500);
    }

    // Smooth fade transition when changing rotation (kept for manual triggers)
    private void fadeOutApplyRotationFadeIn(int rotation, String fitMode) {
        if (textureView == null) return;

        textureView.animate()
                .alpha(0.01f)
                .setDuration(150)
                .withEndAction(() -> {
                    applyTextureViewTransform(rotation, fitMode);
                    textureView.animate()
                            .alpha(1f)
                            .setDuration(150)
                            .start();
                })
                .start();
    }

    private List<File> listMp4(File dir) {
        File[] arr = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".mp4"));
        if (arr == null) return new ArrayList<>();
        List<File> list = new ArrayList<>();
        Collections.addAll(list, arr);
        list.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
        return list;
    }

    private List<File> listImages(File dir) {
        File[] arr = dir.listFiles((d, n) -> {
            String lower = n.toLowerCase();
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") || lower.endsWith(".gif") ||
                    lower.endsWith(".webp");
        });
        if (arr == null) return new ArrayList<>();
        List<File> list = new ArrayList<>();
        Collections.addAll(list, arr);
        list.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
        return list;
    }

    private List<File> listAllMedia(File dir) {
        File[] arr = dir.listFiles((d, n) -> {
            String lower = n.toLowerCase();
            return lower.endsWith(".mp4") || lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".gif") || lower.endsWith(".webp");
        });
        if (arr == null) return new ArrayList<>();
        List<File> list = new ArrayList<>();
        Collections.addAll(list, arr);
        list.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
        return list;
    }

    private boolean isImageFile(File file) {
        if (file == null) return false;
        String lower = file.getName().toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".webp");
    }

    private boolean isVideoFile(File file) {
        if (file == null) return false;
        return file.getName().toLowerCase().endsWith(".mp4");
    }

    // ===== IMAGE DISPLAY METHODS =====

    /** Calculate optimal inSampleSize for bitmap loading to prevent OOM */
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfH = height / 2;
            int halfW = width / 2;
            while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    /**
     * Display a single image with rotation and fit mode
     */
    private void displayImage(File imageFile, int rotation, String fitMode) {
        if (imageFile == null || !imageFile.exists()) {
            Log.e(TAG, "Image file not found: " + imageFile);
            return;
        }

        try {
            // Load bitmap with inSampleSize for large images to prevent OOM
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);

            // Calculate sample size if image is very large (>4096 in any dimension)
            int maxDim = Math.max(screenWidth, screenHeight);
            if (maxDim <= 0) maxDim = 1920;
            opts.inSampleSize = calculateInSampleSize(opts, maxDim, maxDim);
            opts.inJustDecodeBounds = false;

            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode image: " + imageFile.getName());
                return;
            }

            // Apply rotation if needed
            if (rotation != 0) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            // Set scale type based on fit mode
            ImageView.ScaleType scaleType;
            switch (fitMode) {
                case "contain":
                    scaleType = ImageView.ScaleType.FIT_CENTER;
                    break;
                case "fill":
                    scaleType = ImageView.ScaleType.FIT_XY;
                    break;
                case "none":
                    scaleType = ImageView.ScaleType.CENTER;
                    break;
                case "cover":
                default:
                    scaleType = ImageView.ScaleType.CENTER_CROP;
                    break;
            }

            final Bitmap finalBitmap = bitmap;
            ui.post(() -> {
                // Hide video, show image
                textureView.setVisibility(View.GONE);
                if (player != null) {
                    player.pause();
                }

                imageView.setScaleType(scaleType);
                imageView.setImageBitmap(finalBitmap);
                imageView.setVisibility(View.VISIBLE);
                isShowingImage = true;

                Log.d(TAG, "Displaying image: " + imageFile.getName() + " rotation=" + rotation + " fitMode=" + fitMode);
            });

        } catch (Exception e) {
            Log.e(TAG, "Error displaying image: " + e.getMessage());
        }
    }

    /**
     * Play a mixed playlist of videos and images
     */
    private void playMixedPlaylist(File dir) {
        // Increment playback generation so any previously-queued playback lambdas bail out
        final int thisGeneration = ++playbackGeneration;
        Log.d(TAG, "playMixedPlaylist: generation " + thisGeneration);

        // Stop any existing playback first
        imageTimerHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping player: " + e.getMessage());
            }
        }

        // CRITICAL: Release any grid players that may still be playing audio
        // in the background. When switching from grid → single, grid players
        // were not being released here, causing multiple audio streams.
        // NOTE: Only release if grid players still exist (they won't if we came
        // from switchToSingleMode which already cleaned up everything).
        if (isGridMode || gridPlayers[0] != null || gridPlayers[1] != null
                || gridPlayers[2] != null || gridPlayers[3] != null) {
            releaseGridResources();
        }
        isGridMode = false;

        // Deactivated/deleted device: a queued lambda from an in-flight sync
        // worker must not restart playback after the enrollment screen was shown.
        if (!isEnrolled) {
            Log.d(TAG, "playMixedPlaylist skipped: not enrolled");
            return;
        }

        // Template WITHOUT a playlist zone: nothing may play behind the overlay.
        // The playlist would be invisible behind the opaque zones but AUDIBLE
        // (the "another video playing in the background" bug). Existing playback
        // was already stopped above; just don't start anything new. Guarding here
        // covers every caller (post-sync, polls, layout fetches, ...).
        if (templateActive && (templateRenderer == null || templateRenderer.playlistRectPx() == null)) {
            Log.d(TAG, "playMixedPlaylist skipped: template active with no playlist zone");
            return;
        }

        List<File> allMedia = listAllMedia(dir);
        if (allMedia.isEmpty()) {
            if (!templateActive) toast("No media files found");
            return;
        }

        // CRITICAL: Filter to only files that are assigned to this device (have server metadata).
        // Without this, old/unassigned files left on disk get played too.
        List<File> assignedMedia = new ArrayList<>();
        for (File f : allMedia) {
            VideoMetadata vm = getMetadataForFile(f);
            if (vm != null) {
                assignedMedia.add(f);
            } else {
                Log.d(TAG, "Single mode: skipping unassigned file: " + f.getName());
            }
        }

        // Fall back to all media only if no metadata available at all (e.g., fully offline with no cache)
        if (assignedMedia.isEmpty() && !metadataByFilename.isEmpty()) {
            // We have metadata but no files matched - nothing assigned
            Log.d(TAG, "No assigned media found, and metadata exists. Nothing to play.");
            toast("No assigned content");
            return;
        } else if (assignedMedia.isEmpty()) {
            // No metadata at all (offline, no cache) - fall back to all files
            Log.w(TAG, "No metadata available, falling back to all media");
            assignedMedia = allMedia;
        }

        // Sort by grid position if available
        assignedMedia.sort((a, b) -> {
            VideoMetadata vmA = getMetadataForFile(a);
            VideoMetadata vmB = getMetadataForFile(b);
            int posA = vmA != null ? vmA.gridPosition : 0;
            int posB = vmB != null ? vmB.gridPosition : 0;
            return Integer.compare(posA, posB);
        });

        // Separate videos and images
        List<File> videos = new ArrayList<>();
        List<File> images = new ArrayList<>();

        for (File f : assignedMedia) {
            if (isVideoFile(f)) {
                videos.add(f);
            } else if (isImageFile(f)) {
                images.add(f);
            }
        }

        Log.d(TAG, "playMixedPlaylist: " + videos.size() + " videos, " + images.size() + " images");

        currentImageFiles = images;
        currentPlaylistFiles = videos;

        if (!videos.isEmpty() && images.isEmpty()) {
            // Only videos - play normally
            Log.d(TAG, "Playing videos only");
            playLocalPlaylistOrToast(dir, thisGeneration);
        } else if (videos.isEmpty() && !images.isEmpty()) {
            // Only images - start image slideshow
            Log.d(TAG, "Playing images only");
            startImageSlideshow();
        } else if (!videos.isEmpty() && !images.isEmpty()) {
            // Mixed content - alternate between videos and images
            Log.d(TAG, "Playing mixed content");
            playMixedContent(videos, images, thisGeneration);
        } else {
            if (!templateActive) toast("No media files found");
        }
    }

    /**
     * Start image slideshow with timer
     */
    private void startImageSlideshow() {
        if (currentImageFiles.isEmpty()) {
            Log.d(TAG, "No images to show");
            return;
        }

        // Stop video playback when switching to image mode
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping player for image slideshow: " + e.getMessage());
            }
        }

        currentImageIndex = 0;
        showNextImage();
    }

    private void showNextImage() {
        if (currentImageFiles.isEmpty()) return;

        // Stop any existing timer
        imageTimerHandler.removeCallbacks(imageTimerRunnable);

        File imageFile = currentImageFiles.get(currentImageIndex);
        VideoMetadata vm = getMetadataForFile(imageFile);

        int rotation = vm != null ? vm.rotation : 0;
        String fitMode = vm != null ? vm.fitMode : "cover";
        int duration = vm != null ? vm.displayDuration : 10;

        displayImage(imageFile, rotation, fitMode);

        // Schedule next image
        imageTimerRunnable = () -> {
            currentImageIndex = (currentImageIndex + 1) % currentImageFiles.size();
            showNextImage();
        };
        imageTimerHandler.postDelayed(imageTimerRunnable, duration * 1000L);

        Log.d(TAG, "Showing image " + (currentImageIndex + 1) + "/" + currentImageFiles.size() +
                " for " + duration + " seconds");
    }

    /**
     * Play mixed video and image content
     * This alternates between video playback and image display
     */
    private void playMixedContent(List<File> videos, List<File> images, int thisGeneration) {
        // For now, play videos first, then show images
        // A more sophisticated approach would interleave based on grid_position

        if (!videos.isEmpty()) {
            currentPlaylistFiles = videos;

            ui.post(() -> {
                // Bail out if a newer playback session started
                if (thisGeneration != playbackGeneration) {
                    Log.d(TAG, "playMixedContent: stale generation " + thisGeneration + ", skipping");
                    return;
                }

                // Hide image, show video
                imageView.setVisibility(View.GONE);
                textureView.setVisibility(View.VISIBLE);
                isShowingImage = false;

                initPlayer();
                List<MediaItem> items = new ArrayList<>();
                for (File f : videos) {
                    if (f.exists() && f.length() > 0) {
                        items.add(MediaItem.fromUri(Uri.fromFile(f)));
                    }
                }

                if (items.isEmpty()) {
                    if (!images.isEmpty()) {
                        currentImageFiles = images;
                        startImageSlideshow();
                    }
                    return;
                }

                // Get first video's settings
                VideoMetadata firstVm = getMetadataForFile(videos.get(0));
                int firstRotation = firstVm != null ? firstVm.rotation : 0;
                String firstFitMode = firstVm != null ? firstVm.fitMode : "cover";
                lastAppliedRotation = firstRotation;
                lastAppliedFitMode = firstFitMode;

                textureView.setAlpha(0.01f);
                applyTextureViewTransform(firstRotation, firstFitMode);

                player.setMediaItems(items, true);
                player.setRepeatMode(Player.REPEAT_MODE_ALL);
                player.prepare();
                player.play();

                // Fade in
                textureView.postDelayed(() -> {
                    textureView.animate().alpha(1f).setDuration(200).start();
                }, 100);
            });
        } else if (!images.isEmpty()) {
            currentImageFiles = images;
            startImageSlideshow();
        }
    }

    /**
     * Stop image slideshow
     */
    private void stopImageSlideshow() {
        imageTimerHandler.removeCallbacks(imageTimerRunnable);
        isShowingImage = false;
    }

    /**
     * Switch from image to video display
     */
    private void switchToVideoDisplay() {
        stopImageSlideshow();
        ui.post(() -> {
            imageView.setVisibility(View.GONE);
            textureView.setVisibility(View.VISIBLE);
            isShowingImage = false;
        });
    }

    /**
     * Display image in a specific grid slot
     */
    private void displayImageInSlot(int slotIndex, File imageFile, int rotation, String fitMode) {
        if (slotIndex < 0 || slotIndex >= 4) return;
        if (imageFile == null || !imageFile.exists()) return;

        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            if (bitmap == null) return;

            // Apply rotation
            if (rotation != 0) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            // Set scale type
            ImageView.ScaleType scaleType;
            switch (fitMode) {
                case "contain": scaleType = ImageView.ScaleType.FIT_CENTER; break;
                case "fill": scaleType = ImageView.ScaleType.FIT_XY; break;
                case "none": scaleType = ImageView.ScaleType.CENTER; break;
                default: scaleType = ImageView.ScaleType.CENTER_CROP; break;
            }

            final Bitmap finalBitmap = bitmap;
            final ImageView slotImageView = gridImageViews[slotIndex];

            if (slotImageView != null) {
                ui.post(() -> {
                    slotImageView.setScaleType(scaleType);
                    slotImageView.setImageBitmap(finalBitmap);
                    slotImageView.setVisibility(View.VISIBLE);

                    // Hide the corresponding TextureView if exists
                    if (gridTextureViews[slotIndex] != null) {
                        gridTextureViews[slotIndex].setVisibility(View.GONE);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error displaying image in slot " + slotIndex + ": " + e.getMessage());
        }
    }

    private boolean readDownloadStatus(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS); c.setReadTimeout(READ_TIMEOUT_MS); c.connect();
        if (c.getResponseCode() / 100 != 2) { c.disconnect(); return false; }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        } finally { c.disconnect(); }
        JSONObject o = new JSONObject(sb.toString());
        return o.optBoolean("download_status", false) || o.optBoolean("status", false);
    }

    private void postUpdateStatusTrue(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write("{\"status\": true}".getBytes(StandardCharsets.UTF_8));
        }
        c.getResponseCode(); c.disconnect();
    }

    private void initPlayer() {
        // ALWAYS release old player to prevent listener stacking and audio leaks.
        // Previously, if player was in STATE_READY/STATE_BUFFERING, it was reused
        // without clearing listeners, causing duplicate audio and transition handlers.
        if (player != null) {
            try {
                player.setVideoSurface(null);
                player.stop();
                player.clearMediaItems();
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing old player: " + e.getMessage());
            }
            player = null;
        }

        Log.d(TAG, "Creating new ExoPlayer");
        player = new ExoPlayer.Builder(this).build();

        // Apply cached mute state immediately so audio is correct before the first heartbeat
        boolean cachedMuted = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_IS_MUTED, false);
        player.setVolume(cachedMuted ? 0f : 1f);

        // Connect player to TextureView surface
        if (surface != null) {
            player.setVideoSurface(surface);
            Log.d(TAG, "Connected player to surface");
        } else {
            Log.w(TAG, "Surface is null when initializing player");
        }

        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                Log.e(TAG, "Player error: " + e.getMessage());
                int count = player.getMediaItemCount();
                if (count > 1) {
                    // Drop the offending item and keep playing the rest. Safe because
                    // count > 1, so this can NEVER drain the playlist to empty — the
                    // old code did, leaving a permanent black screen once the last
                    // item errored.
                    int idx = player.getCurrentMediaItemIndex();
                    player.removeMediaItem(idx);
                    player.seekTo(Math.min(idx, player.getMediaItemCount() - 1), 0);
                    player.prepare();
                    player.play();
                } else {
                    // The only/last item errored — never remove it. Retry a few times
                    // (transient decode/IO), then rebuild the playlist from disk. The
                    // watchdog is the final backstop if even that stalls.
                    if (++singleErrorStrikes <= 3) {
                        player.seekTo(0);
                        player.prepare();
                        player.play();
                    } else {
                        singleErrorStrikes = 0;
                        ui.postDelayed(() -> {
                            if (isEnrolled && !isShowingImage) playMixedPlaylist(ensureMainDir());
                        }, 1500);
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) singleErrorStrikes = 0; // recovered cleanly
            }
        });
    }

    private void applyImmersive() {
        View d = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = d.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    // Stable device id (persisted, survives reinstall/re-sign) — see DeviceIdentity.
    // Kept the method name so all existing call sites use the stable id unchanged;
    // it is seeded from ANDROID_ID on first run so enrolled devices keep their id.
    private String getAndroidId() { return DeviceIdentity.get(this); }

    private File ensureMainDir() { File d = new File(Environment.getExternalStorageDirectory(), ROOT_DIR); if (!d.exists()) d.mkdirs(); return d; }
    private File ensureTempDir() { File d = new File(Environment.getExternalStorageDirectory(), TEMP_DIR); if (!d.exists()) d.mkdirs(); return d; }

    // ===== PLAYBACK WATCHDOG IMPLEMENTATION =====
    // Runs on the UI thread. Only acts when the device is enrolled, focused and
    // meant to be showing video (not an image slideshow, not the enrollment
    // screen). A stuck player must persist across TWO checks before we rebuild,
    // so a legitimate buffer/transition isn't mistaken for a stall.
    private void checkPlaybackHealth() {
        if (!isEnrolled || isShowingImage || !hasWindowFocus()) { playbackStallStrikes = 0; return; }
        boolean healthy = isGridMode ? gridPlaybackHealthy() : singlePlaybackHealthy();
        if (healthy) { playbackStallStrikes = 0; return; }
        playbackStallStrikes++;
        Log.w(TAG, "Watchdog: playback looks stuck (mode=" + (isGridMode ? "grid" : "single")
                + ", strike " + playbackStallStrikes + ")");
        if (playbackStallStrikes >= 2) {
            playbackStallStrikes = 0;
            Log.w(TAG, "Watchdog: rebuilding playback to recover from a stall/black screen");
            restartPlaybackForRecovery();
        }
    }

    // Single/template mode: the one main player. Loops with REPEAT_MODE_ALL, so a
    // healthy player is never IDLE/ENDED; those states mean it errored out or the
    // playlist was drained to empty.
    private boolean singlePlaybackHealthy() {
        ExoPlayer p = player;
        if (p == null) return true; // nothing to supervise yet (e.g. mid-rebuild)
        if (p.getMediaItemCount() == 0) return true; // no content assigned — not a stall
        int st = p.getPlaybackState();
        if (st == Player.STATE_IDLE || st == Player.STATE_ENDED) return false;
        // Ready but not advancing while we want it to play = frozen.
        if (st == Player.STATE_READY && !p.isPlaying() && p.getPlayWhenReady()) return false;
        return true;
    }

    // Grid mode: unhealthy if any slot that HAS media has died (IDLE/ENDED). One
    // dead slot is a black half/quarter of the screen, so recover the whole grid.
    private boolean gridPlaybackHealthy() {
        for (ExoPlayer gp : gridPlayers) {
            if (gp == null || gp.getMediaItemCount() == 0) continue;
            int st = gp.getPlaybackState();
            if (st == Player.STATE_IDLE || st == Player.STATE_ENDED) return false;
        }
        return true;
    }

    private void restartPlaybackForRecovery() {
        try {
            if (isGridMode && !templateActive) {
                switchToGridMode();
            } else {
                playMixedPlaylist(ensureMainDir());
            }
        } catch (Exception e) {
            Log.e(TAG, "Watchdog restart failed", e);
        }
    }

    private void startWatchdog() {
        watchdogHandler.removeCallbacksAndMessages(null);
        playbackStallStrikes = 0;
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_MS);
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ===== BLE =====
    private void ensureBluetoothPermissionAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, REQ_BT_PERMS);
                return;
            }
        } else {
            // Android 6–11: BLE scan returns NOTHING without ACCESS_FINE_LOCATION granted at runtime.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLE: requesting ACCESS_FINE_LOCATION (required for scan on Android <= 11)");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BT_PERMS);
                return;
            }
        }
        autoConnectToEsp32();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_BT_PERMS) {
            boolean ok = res.length > 0;
            for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) autoConnectToEsp32();
        }
    }

    private void autoConnectToEsp32() {
        if (btAdapter == null) { Log.d(TAG, "BLE: no adapter"); return; }
        if (!btAdapter.isEnabled()) {
            // Bluetooth may still be initialising (e.g. right after boot). Retry in 3 s.
            Log.d(TAG, "BLE: adapter disabled, retrying in 3s");
            if (btShouldReconnect) bleHandler.postDelayed(this::autoConnectToEsp32, 3_000L);
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bm != null) btAdapter = bm.getAdapter();
        bleScanner = btAdapter.getBluetoothLeScanner();
        if (bleScanner != null) startBleScan();
        else Log.d(TAG, "BLE: no LE scanner");
    }

    private void startBleScan() {
        if (bleScanner == null || bleScanning || bleConnected) return;
        // Only connect to the ESP32 whose ID matches the one configured for THIS phone.
        // No ID configured = this phone has no ESP32 paired → don't scan, so it can never
        // grab another device's ESP32 connection slot. Retry later in case the ID arrives via heartbeat.
        String savedId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_BLE_DEVICE_ID, "").trim();
        if (savedId.isEmpty()) {
            Log.d(TAG, "No BLE device ID configured — skipping ESP32 scan");
            scheduleBleReconnect();
            return;
        }
        bleTargetName = ESP32_DEVICE_NAME + "_" + savedId;
        bleScanning = true;
        Log.d(TAG, "BLE: scanning (no filter) for '" + bleTargetName + "'");
        // Scan with NO filter and match the name in software. Hardware-offloaded scan
        // filters (APCF) on some chipsets (incl. this AIC part) silently drop everything,
        // especially 128-bit service UUIDs. No filter = the stack reports every device,
        // so we always see the ESP32 if it is advertising. Fine for a foreground app.
        bleScanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        bleHandler.postDelayed(() -> { if (bleScanning) { Log.d(TAG, "BLE: scan timeout, no '" + bleTargetName + "' found — retrying"); stopBleScan(); scheduleBleReconnect(); } }, 15_000L);
    }

    private void stopBleScan() { if (bleScanning && bleScanner != null) { try { bleScanner.stopScan(scanCallback); } catch (Exception ignored) {} bleScanning = false; } }

    // Dedupe reconnect attempts — without this, a scan timeout + a disconnect can
    // queue several autoConnect calls and run overlapping scans, which itself makes
    // connecting flaky. Always cancel any pending attempt before scheduling a new one.
    private void scheduleBleReconnect() {
        if (!btShouldReconnect) return;
        bleHandler.removeCallbacks(reconnectRunnable);
        bleHandler.postDelayed(reconnectRunnable, 2_000L);
    }

    // Connect on the MAIN thread with explicit LE transport. Calling connectGatt from
    // the scan-callback (binder) thread or without TRANSPORT_LE are the two classic
    // causes of the Android "status 133" failure that makes connecting slow/unreliable.
    private void connectToDevice(final BluetoothDevice dev) {
        bleHandler.postDelayed(() -> {
            if (!btShouldReconnect || bleConnected) return;
            try {
                if (btGatt != null) { try { btGatt.close(); } catch (Exception ignored) {} btGatt = null; }
                Log.d(TAG, "BLE: connecting to '" + dev.getName() + "' (" + dev.getAddress() + ")");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    btGatt = dev.connectGatt(FullScreenPlayerActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    btGatt = dev.connectGatt(FullScreenPlayerActivity.this, false, gattCallback);
                }
            } catch (Exception e) { Log.w(TAG, "BLE: connectGatt failed", e); scheduleBleReconnect(); }
        }, 200L);  // brief gap after stopScan so the BLE stack settles before connecting
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int t, ScanResult r) {
            BluetoothDevice dev = r.getDevice();
            if (dev == null) return;
            // Name may come from the scan record (adv data) even when dev.getName() is null
            String advName = (r.getScanRecord() != null) ? r.getScanRecord().getDeviceName() : null;
            String name = (dev.getName() != null) ? dev.getName() : advName;
            Log.d(TAG, "BLE: saw device name='" + name + "' addr=" + dev.getAddress() + " (target='" + bleTargetName + "')");
            String target = bleTargetName;
            if (target != null && target.equals(name)) {
                Log.d(TAG, "BLE: MATCH — connecting");
                stopBleScan();
                connectToDevice(dev);
            }
        }
        @Override public void onScanFailed(int e) { Log.w(TAG, "BLE: scan failed, code=" + e); bleScanning = false; scheduleBleReconnect(); }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int s, int n) {
            Log.d(TAG, "BLE: connState status=" + s + " newState=" + n);
            if (n == BluetoothGatt.STATE_CONNECTED && s == BluetoothGatt.GATT_SUCCESS) {
                bleConnected = true;
                bleHandler.removeCallbacks(reconnectRunnable);  // cancel any stale retry
                // brief settle delay before service discovery improves reliability
                bleHandler.postDelayed(() -> { try { g.discoverServices(); } catch (Exception ignored) {} }, 300L);
            } else {
                // Disconnected OR a connection error (e.g. status 133) — always close and retry.
                bleConnected = false;
                try { g.close(); } catch (Exception ignored) {}
                if (btGatt == g) btGatt = null;
                scheduleBleReconnect();
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int s) {
            Log.d(TAG, "BLE: servicesDiscovered status=" + s);
            if (s != BluetoothGatt.GATT_SUCCESS) { scheduleBleReconnect(); return; }
            BluetoothGattService svc = g.getService(NUS_SERVICE_UUID);
            if (svc == null) { Log.w(TAG, "BLE: NUS service not found"); scheduleBleReconnect(); return; }
            nusTxChar = svc.getCharacteristic(NUS_CHAR_TX_UUID);
            nusRxChar = svc.getCharacteristic(NUS_CHAR_RX_UUID);
            if (nusTxChar == null || nusRxChar == null) { Log.w(TAG, "BLE: NUS chars not found"); scheduleBleReconnect(); return; }
            try {
                g.setCharacteristicNotification(nusTxChar, true);
                BluetoothGattDescriptor d = nusTxChar.getDescriptor(CCCD_UUID);
                if (d != null) { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
            } catch (Exception e) { scheduleBleReconnect(); }
            // AUTH is sent in onDescriptorWrite after CCCD write is confirmed
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (!CCCD_UUID.equals(d.getUuid())) return;
            // CCCD write done — now safe to send the AUTH command (no pending GATT op conflict)
            String savedId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_BLE_DEVICE_ID, "").trim();
            Log.d(TAG, "BLE: notifications enabled, savedId='" + savedId + "' — sending AUTH");
            if (!savedId.isEmpty()) {
                bleAuthPending = true;
                bleHandler.postDelayed(() -> {
                    if (bleAuthPending) {
                        Log.w(TAG, "BLE auth timeout — disconnecting");
                        if (btGatt != null) btGatt.disconnect();
                    }
                }, 5000);
                sendToEsp32("AUTH");
            }
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (!NUS_CHAR_TX_UUID.equals(c.getUuid())) return;
            byte[] v = c.getValue(); if (v == null) return;
            String msg = new String(v, StandardCharsets.UTF_8).trim();
            if (!msg.isEmpty()) handleBtCommand(msg);
        }
    };

    private void handleBtCommand(String cmd) {
        String lower = cmd.toLowerCase(Locale.US);

        // BLE auth response: "ID:01"
        if (lower.startsWith("id:")) {
            String receivedId = cmd.substring(3).trim();
            String savedId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_BLE_DEVICE_ID, "").trim();
            bleAuthPending = false;
            if (!savedId.isEmpty() && !receivedId.equals(savedId)) {
                Log.w(TAG, "BLE auth FAILED — expected '" + savedId + "' got '" + receivedId + "' — disconnecting");
                if (btGatt != null) btGatt.disconnect();
            } else {
                Log.d(TAG, "BLE auth OK — '" + receivedId + "' matches — connection established");
            }
            return;
        }

        if (lower.contains("temperature")) {
            Matcher m = TEMP_PATTERN.matcher(cmd);
            if (m.find()) try { lastTemperatureValue = Float.parseFloat(m.group(1)); } catch (Exception ignored) {}
        }
        if (lower.contains("reed") && lower.contains("open")) incrementCounts();
        ui.post(() -> {
            switch (cmd.toUpperCase(Locale.US)) {
                case "PLAY": if (player != null) player.play(); break;
                case "PAUSE": if (player != null) player.pause(); break;
                case "NEXT": if (player != null && player.getMediaItemCount() > 0) { player.seekTo((player.getCurrentMediaItemIndex()+1) % player.getMediaItemCount(), 0); player.play(); } break;
            }
        });
    }

    /** Write a command string to the ESP32 RX characteristic (Android → ESP32). */
    @SuppressLint("MissingPermission")
    private void sendToEsp32(String cmd) {
        if (nusRxChar == null || btGatt == null) return;
        try {
            nusRxChar.setValue((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            btGatt.writeCharacteristic(nusRxChar);
        } catch (Exception ignored) {}
    }

    private void sendTemperatureToServer(float t) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(updateTemperatureUrl(getAndroidId())).openConnection();
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
                    out.write(String.format(Locale.US, "{\"temperature\": %.2f}", t).getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode(); c.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private void incrementCounts() {
        // 1. Persist locally first — survives offline periods, reboots, and crashes.
        reedCountStore.recordOpen();

        // 2. Try to flush to the server immediately on a background thread.
        //    If the POST fails (offline), the count stays in the local buffer.
        //    ReedSyncWorker will drain the buffer the next time network is available.
        new Thread(() -> {
            String id       = getAndroidId();
            boolean dailyOk = tryPostCount(dailyUpdateUrl(id),   "daily_count");
            boolean mthlyOk = tryPostCount(monthlyUpdateUrl(id), "monthly_count");
            if (dailyOk)  reedCountStore.decrementDaily();
            if (mthlyOk)  reedCountStore.decrementMonthly();
            if (!dailyOk || !mthlyOk) ReedSyncWorker.enqueue(FullScreenPlayerActivity.this);
        }).start();
    }

    /** Returns true if the POST succeeded (2xx), false on any error or non-2xx status. */
    private boolean tryPostCount(String url, String key) {
        try {
            postCount(url, key, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void postCount(String url, String key, int val) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write(("{\"" + key + "\": " + val + "}").getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
    }

    // ===== GRID MODE METHODS =====

    private void switchToSingleMode() {
        // Deactivated device: don't rebuild views / restart playback under the
        // enrollment screen (in-flight sync lambdas can land here late).
        if (!isEnrolled) {
            Log.d(TAG, "switchToSingleMode skipped: not enrolled");
            return;
        }
        Log.d(TAG, "Switching to SINGLE mode");
        isGridMode = false;

        // Cancel any pending playback lambdas
        playbackGeneration++;

        // Stop image slideshow
        imageTimerHandler.removeCallbacksAndMessages(null);
        isShowingImage = false;

        // ── Step 1: Detach surfaces from ALL codecs BEFORE releasing players ──
        // This tells the hardware codec to disconnect from the BufferQueue.
        // Without this, the Qualcomm codec keeps its BufferQueue producer reference
        // even after ExoPlayer.release(), causing "waitForFreeSlotThenRelock: timeout"
        // on the old SurfaceTexture when the new player starts.
        for (int i = 0; i < gridPlayers.length; i++) {
            if (gridPlayers[i] != null) {
                try {
                    gridPlayers[i].setVideoSurface(null);
                } catch (Exception ignored) {}
            }
        }
        if (player != null) {
            try {
                player.setVideoSurface(null);
            } catch (Exception ignored) {}
        }

        // ── Step 2: Release the old Surface objects ──
        // These are wrappers around the SurfaceTexture's BufferQueue producer.
        // Releasing them signals the BufferQueue that the producer is gone.
        if (surface != null) {
            try { surface.release(); } catch (Exception ignored) {}
            surface = null;
        }
        for (int i = 0; i < gridSurfaces.length; i++) {
            if (gridSurfaces[i] != null) {
                try { gridSurfaces[i].release(); } catch (Exception ignored) {}
                gridSurfaces[i] = null;
            }
        }

        // ── Step 3: Remove all views from container ──
        // This detaches the grid TextureViews which triggers onSurfaceTextureDestroyed
        // on each one, releasing their SurfaceTextures and clearing the BufferQueues.
        if (enrollmentOverlay != null && enrollmentOverlay.getParent() != null) {
            rootContainer.removeView(enrollmentOverlay);
        }
        rootContainer.removeAllViews();

        // ── Step 4: NOW release the ExoPlayers (and their MediaCodec instances) ──
        // At this point all surfaces and SurfaceTextures are gone, so the hardware
        // codec has nothing to hold onto.
        for (int i = 0; i < gridPlayers.length; i++) {
            if (gridPlayers[i] != null) {
                try {
                    gridPlayers[i].stop();
                    gridPlayers[i].clearMediaItems();
                    gridPlayers[i].release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing grid player " + i + ": " + e.getMessage());
                }
                gridPlayers[i] = null;
            }
            gridTextureViews[i] = null;
            if (gridImageViews[i] != null) {
                try { gridImageViews[i].setImageBitmap(null); } catch (Exception ignored) {}
                gridImageViews[i] = null;
            }
        }
        for (int i = 0; i < gridVideoFiles.length; i++) {
            gridVideoFiles[i] = null;
            lastGridRotations[i] = 0;
        }
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing single player: " + e.getMessage());
            }
            player = null;
        }

        // ── Step 5: Wait for hardware codec to fully release ──
        // Qualcomm's c2.qti.avc.decoder needs time to tear down its internal
        // BufferQueue producer after the Surface and MediaCodec are released.
        // 300ms is enough for the decoder to be returned to the pool.
        // THEN create the new TextureView — its onSurfaceTextureAvailable will
        // create a fresh Surface and a new ExoPlayer with a clean codec instance.
        ui.postDelayed(() -> {
            // Re-add ImageView first (behind video)
            imageView = new ImageView(this);
            imageView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setVisibility(View.GONE);
            rootContainer.addView(imageView);

            // Re-add single textureView — when its surface becomes available,
            // onSurfaceTextureAvailable() will set `surface` and start playback
            textureView = new TextureView(this);
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    Log.d(TAG, "Single mode: new surface available " + w + "x" + h);
                    surface = new Surface(st);
                    // Surface is ready — NOW start playback
                    File mainDir = ensureMainDir();
                    playMixedPlaylist(mainDir);
                }
                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    Log.d(TAG, "SurfaceTexture size changed: " + w + "x" + h);
                }
                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    Log.d(TAG, "SurfaceTexture destroyed");
                    if (player != null) {
                        player.setVideoSurface(null);
                    }
                    if (surface != null) {
                        surface.release();
                        surface = null;
                    }
                    return true;
                }
                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            );
            rootContainer.addView(textureView, params);

            // Re-add enrollment overlay on top — PRESERVING its state: forcing it
            // GONE here un-showed the enrollment screen when a pending mode-switch
            // lambda ran just after a deactivation.
            if (enrollmentOverlay != null && enrollmentOverlay.getParent() == null) {
                enrollmentOverlay.setVisibility(isEnrolled ? View.GONE : View.VISIBLE);
                rootContainer.addView(enrollmentOverlay);
            }

            // Re-add download status overlay on top of everything
            if (downloadStatusOverlay != null && downloadStatusOverlay.getParent() == null) {
                rootContainer.addView(downloadStatusOverlay);
            }

            // NO playMixedPlaylist here — it will be called when surface is ready
            // NO playMixedPlaylist here — it will be called when surface is ready
        }, 300);
    }

    private void switchToGridMode() {
        // Template mode is single-mode only ("never switch to full-screen grid,
        // which would fight the overlay"). Guarding here covers EVERY caller —
        // grid players behind the opaque template are invisible but audible.
        // Same for a deactivated device: queued sync lambdas must not restart.
        if (templateActive || !isEnrolled) {
            Log.d(TAG, "switchToGridMode skipped: " + (templateActive ? "template active" : "not enrolled"));
            return;
        }
        // Debounce: if a switch is already in progress (resources releasing, delay pending),
        // skip this call. Qualcomm c2 decoders need ~300ms to free output buffer slots after
        // player.release() — stacking calls exhausts the SurfaceTexture buffer queue (NO_MEMORY).
        if (gridSwitchInProgress) {
            Log.d(TAG, "switchToGridMode: switch already in progress, skipping duplicate call");
            return;
        }
        gridSwitchInProgress = true;
        Log.d(TAG, "Switching to GRID mode: " + layoutMode);
        isGridMode = true;

        // Cancel any pending single-mode playback lambdas
        playbackGeneration++;

        // Stop image slideshow if running
        imageTimerHandler.removeCallbacksAndMessages(null);
        isShowingImage = false;

        // ── Step 1: Detach surfaces from ALL codecs before anything else ──
        // Tells the hardware codec to disconnect from the BufferQueue.
        if (player != null) {
            try { player.setVideoSurface(null); } catch (Exception ignored) {}
        }
        for (int i = 0; i < gridPlayers.length; i++) {
            if (gridPlayers[i] != null) {
                try { gridPlayers[i].setVideoSurface(null); } catch (Exception ignored) {}
            }
        }

        // ── Step 2: Release Surface wrapper objects ──
        if (surface != null) {
            try { surface.release(); } catch (Exception ignored) {}
            surface = null;
        }
        for (int i = 0; i < gridSurfaces.length; i++) {
            if (gridSurfaces[i] != null) {
                try { gridSurfaces[i].release(); } catch (Exception ignored) {}
                gridSurfaces[i] = null;
            }
        }

        // ── Step 3: Remove ALL views synchronously (mirrors switchToSingleMode) ──
        // This triggers onSurfaceTextureDestroyed on each TextureView immediately,
        // so the old content disappears from the screen RIGHT NOW instead of staying
        // frozen for the 300ms codec-drain window. Without this the old frame was
        // visible as a frozen image until the postDelayed fired.
        if (enrollmentOverlay != null && enrollmentOverlay.getParent() != null) {
            rootContainer.removeView(enrollmentOverlay);
        }
        if (downloadStatusOverlay != null && downloadStatusOverlay.getParent() != null) {
            rootContainer.removeView(downloadStatusOverlay);
        }
        rootContainer.removeAllViews();

        // ── Step 4: Release ExoPlayers AFTER views are removed ──
        // Views are gone so onSurfaceTextureDestroyed already called setVideoSurface(null).
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing single player: " + e.getMessage());
            }
            player = null;
        }
        for (int i = 0; i < gridPlayers.length; i++) {
            if (gridPlayers[i] != null) {
                try {
                    gridPlayers[i].stop();
                    gridPlayers[i].clearMediaItems();
                    gridPlayers[i].release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing grid player " + i + ": " + e.getMessage());
                }
                gridPlayers[i] = null;
            }
            gridTextureViews[i] = null;
            if (gridImageViews[i] != null) {
                try { gridImageViews[i].setImageBitmap(null); } catch (Exception ignored) {}
                gridImageViews[i] = null;
            }
        }
        for (int i = 0; i < gridVideoFiles.length; i++) {
            gridVideoFiles[i] = null;
            lastGridRotations[i] = 0;
        }

        // ── Step 5: Wait 300ms for Qualcomm codec to release buffer slots, then build new views ──
        // Qualcomm's c2 codec framework releases output buffer slots asynchronously;
        // 50ms was not enough, causing NO_MEMORY on the next decoder init.
        ui.postDelayed(() -> {
            gridSwitchInProgress = false; // allow new switch calls once views are rebuilt

            // Determine grid configuration
            final int numSlots = getGridSlots(layoutMode);
            final int[] surfacesReady = {0}; // Counter for ready surfaces

            // Create TextureViews for grid
            for (int i = 0; i < numSlots; i++) {
                gridTextureViews[i] = new TextureView(this);
                final int slotIndex = i;
                gridTextureViews[i].setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                        Log.d(TAG, "Grid surface " + slotIndex + " available: " + w + "x" + h);
                        gridSurfaces[slotIndex] = new Surface(st);

                        // If player already exists (fallback path), attach surface now
                        if (gridPlayers[slotIndex] != null) {
                            gridPlayers[slotIndex].setVideoSurface(gridSurfaces[slotIndex]);
                        }

                        // Count ready surfaces and start playback when all are ready
                        surfacesReady[0]++;
                        if (surfacesReady[0] == numSlots) {
                            Log.d(TAG, "All " + numSlots + " surfaces ready, starting grid playback");
                            File mainDir = ensureMainDir();
                            playGridVideos(mainDir, numSlots);
                        }
                    }
                    @Override
                    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                        if (gridPlayers[slotIndex] != null) {
                            gridPlayers[slotIndex].setVideoSurface(null);
                        }
                        if (gridSurfaces[slotIndex] != null) {
                            gridSurfaces[slotIndex].release();
                            gridSurfaces[slotIndex] = null;
                        }
                        return true;
                    }
                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture st) {}
                });

                FrameLayout.LayoutParams params = getGridLayoutParams(layoutMode, i, numSlots);
                rootContainer.addView(gridTextureViews[i], params);
            }

            // Fallback: if surfaces don't become available within 500ms, start anyway
            ui.postDelayed(() -> {
                if (surfacesReady[0] < numSlots && gridPlayers[0] == null) {
                    Log.w(TAG, "Timeout waiting for surfaces, starting playback anyway (" + surfacesReady[0] + "/" + numSlots + " ready)");
                    File mainDir = ensureMainDir();
                    playGridVideos(mainDir, numSlots);
                }
            }, 500);

            // Re-add enrollment overlay on top so it's not lost — PRESERVING its
            // state: forcing it GONE here un-showed the enrollment screen when a
            // pending mode-switch lambda ran just after a deactivation.
            if (enrollmentOverlay != null && enrollmentOverlay.getParent() == null) {
                enrollmentOverlay.setVisibility(isEnrolled ? View.GONE : View.VISIBLE);
                rootContainer.addView(enrollmentOverlay);
            }

            // Re-add download status overlay on top of everything
            if (downloadStatusOverlay != null && downloadStatusOverlay.getParent() == null) {
                rootContainer.addView(downloadStatusOverlay);
            }
        }, 300);
    }

    private int getGridSlots(String mode) {
        switch (mode) {
            case "split_h":
            case "split_v":
                return 2;
            case "grid_3":
                return 3;
            case "grid_4":
            case "grid_1x4":
                return 4;
            default:
                return 1;
        }
    }

    private FrameLayout.LayoutParams getGridLayoutParams(String mode, int index, int numSlots) {
        int halfW = screenWidth / 2;
        int halfH = screenHeight / 2;
        int quarterH = screenHeight / 4;

        FrameLayout.LayoutParams params;

        switch (mode) {
            case "split_h": // 2 videos side by side
                params = new FrameLayout.LayoutParams(halfW, FrameLayout.LayoutParams.MATCH_PARENT);
                params.gravity = android.view.Gravity.TOP | (index == 0 ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT);
                break;

            case "split_v": // 2 videos stacked vertically (top and bottom)
                params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, halfH);
                params.gravity = android.view.Gravity.LEFT;
                params.topMargin = index * halfH;
                break;

            case "grid_3": // 2 on top, 1 full width bottom
                if (index < 2) {
                    params = new FrameLayout.LayoutParams(halfW, halfH);
                    params.gravity = android.view.Gravity.TOP | (index == 0 ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT);
                } else {
                    params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, halfH);
                    params.gravity = android.view.Gravity.LEFT;
                    params.topMargin = halfH;
                }
                break;

            case "grid_4": // 2x2 grid
                params = new FrameLayout.LayoutParams(halfW, halfH);
                params.gravity = android.view.Gravity.LEFT | android.view.Gravity.TOP;
                params.leftMargin = (index % 2) * halfW;
                params.topMargin = (index / 2) * halfH;
                break;

            case "grid_1x4": // 4 videos stacked vertically (1 column x 4 rows)
                params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, quarterH);
                params.gravity = android.view.Gravity.LEFT | android.view.Gravity.TOP;
                params.topMargin = index * quarterH;
                break;

            default:
                params = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );
        }

        return params;
    }

    // Store current grid video files for rotation updates
    private File[] gridVideoFiles = new File[4];
    private int[] lastGridRotations = new int[4];

    private void applyRotationForGridVideos() {
        if (!isGridMode) return;

        for (int i = 0; i < gridPlayers.length; i++) {
            if (gridPlayers[i] != null && gridVideoFiles[i] != null && gridTextureViews[i] != null) {
                VideoMetadata vm = getMetadataForFile(gridVideoFiles[i]);
                int newRotation = vm != null ? vm.rotation : 0;
                String fitMode = vm != null ? vm.fitMode : "cover";

                // Only apply if rotation changed
                if (newRotation != lastGridRotations[i]) {
                    Log.d(TAG, "Grid slot " + i + " rotation changed: " + lastGridRotations[i] + " -> " + newRotation);
                    lastGridRotations[i] = newRotation;
                    applyGridTextureViewTransform(gridTextureViews[i], newRotation, fitMode, i);
                }
            }
        }
    }

    private void playGridVideos(File dir, int numSlots) {
        // Deactivated device: surface-ready callbacks and the 500ms fallback can
        // land after the enrollment screen was shown — don't restart playback.
        if (!isEnrolled) {
            Log.d(TAG, "playGridVideos skipped: not enrolled");
            return;
        }
        // Get all media files (videos and images)
        List<File> allMedia = listAllMedia(dir);
        if (allMedia.isEmpty()) {
            if (!templateActive) toast("No content found for grid");
            return;
        }

        // CRITICAL: Filter to only files that have metadata (i.e., are assigned to this device)
        // Without this, old/unassigned files in the directory get gridPosition=0
        // and take slot 0, pushing assigned files to wrong positions
        List<File> assignedMedia = new ArrayList<>();
        for (File f : allMedia) {
            VideoMetadata vm = getMetadataForFile(f);
            if (vm != null) {
                assignedMedia.add(f);
            } else {
                Log.d(TAG, "Grid: skipping unassigned file: " + f.getName());
            }
        }

        // Fall back to all media if no metadata available (e.g., offline with no cache)
        if (assignedMedia.isEmpty()) {
            Log.w(TAG, "No assigned media found, falling back to all media");
            assignedMedia = allMedia;
        }

        // Sort files by grid position from metadata
        assignedMedia.sort((a, b) -> {
            VideoMetadata vmA = getMetadataForFile(a);
            VideoMetadata vmB = getMetadataForFile(b);
            int posA = vmA != null ? vmA.gridPosition : 0;
            int posB = vmB != null ? vmB.gridPosition : 0;
            return Integer.compare(posA, posB);
        });

        // Log the sorted order for debugging grid position issues
        for (int i = 0; i < assignedMedia.size(); i++) {
            File f = assignedMedia.get(i);
            VideoMetadata vm = getMetadataForFile(f);
            Log.d(TAG, "Grid sorted[" + i + "]: " + f.getName()
                    + " gridPos=" + (vm != null ? vm.gridPosition : "null")
                    + " videoName=" + (vm != null ? vm.videoName : "null")
                    + " filename=" + (vm != null ? vm.filename : "null"));
        }

        Log.d(TAG, "Playing " + Math.min(numSlots, assignedMedia.size()) + " media items in grid");

        // Reset tracking arrays
        for (int i = 0; i < gridVideoFiles.length; i++) {
            gridVideoFiles[i] = null;
            lastGridRotations[i] = 0;
        }

        // Count video slots needed
        int videoSlotCount = 0;
        for (File f : assignedMedia) {
            VideoMetadata vm = getMetadataForFile(f);
            String contentType = vm != null ? vm.contentType : (isImageFile(f) ? "image" : "video");
            if (!"image".equals(contentType) && !isImageFile(f)) {
                videoSlotCount++;
            }
        }

        // Standard mode: play all videos simultaneously on both mobile and TV
        Log.d(TAG, "STANDARD MODE: Playing " + videoSlotCount + " videos simultaneously");

        // Create players/imageviews for each slot.
        // Use gridPosition (1-based) as the target slot index so videos land in
        // the correct slot even when some slots are intentionally empty.
        for (int i = 0; i < assignedMedia.size(); i++) {
            File mediaFile = assignedMedia.get(i);
            VideoMetadata vm = getMetadataForFile(mediaFile);

            // Determine target slot: gridPosition is 1-based, slot index is 0-based.
            // Fall back to array index if gridPosition is 0 or missing.
            int rawPos = vm != null ? vm.gridPosition : 0;
            final int slotIndex = (rawPos > 0 && rawPos <= numSlots) ? rawPos - 1 : i;
            if (slotIndex >= numSlots) continue; // safety guard

            int rotation = vm != null ? vm.rotation : 0;
            String fitMode = vm != null ? vm.fitMode : "cover";
            String contentType = vm != null ? vm.contentType : (isImageFile(mediaFile) ? "image" : "video");

            // Track file and rotation for live updates using the correct slot index
            gridVideoFiles[slotIndex] = mediaFile;
            lastGridRotations[slotIndex] = rotation;

            if ("image".equals(contentType) || isImageFile(mediaFile)) {
                // Handle image slot
                Log.d(TAG, "Grid slot " + slotIndex + " is IMAGE: " + mediaFile.getName());

                // Hide TextureView, create and show ImageView for this slot
                if (gridTextureViews[slotIndex] != null) {
                    gridTextureViews[slotIndex].setVisibility(View.GONE);
                }

                // Create ImageView for this slot if not already created
                if (gridImageViews[slotIndex] == null) {
                    gridImageViews[slotIndex] = new ImageView(this);
                    FrameLayout.LayoutParams params = getGridLayoutParams(layoutMode, slotIndex, numSlots);
                    rootContainer.addView(gridImageViews[slotIndex], params);
                }

                // Load and display image
                try {
                    Bitmap bitmap = BitmapFactory.decodeFile(mediaFile.getAbsolutePath());
                    if (bitmap != null) {
                        // Apply rotation if needed
                        if (rotation != 0) {
                            Matrix matrix = new Matrix();
                            matrix.postRotate(rotation);
                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        }

                        // Set scale type based on fit mode
                        ImageView.ScaleType scaleType;
                        switch (fitMode.toLowerCase()) {
                            case "contain":
                                scaleType = ImageView.ScaleType.FIT_CENTER;
                                break;
                            case "fill":
                                scaleType = ImageView.ScaleType.FIT_XY;
                                break;
                            case "none":
                                scaleType = ImageView.ScaleType.CENTER;
                                break;
                            case "cover":
                            default:
                                scaleType = ImageView.ScaleType.CENTER_CROP;
                                break;
                        }

                        gridImageViews[slotIndex].setScaleType(scaleType);
                        gridImageViews[slotIndex].setImageBitmap(bitmap);
                        gridImageViews[slotIndex].setVisibility(View.VISIBLE);

                        Log.d(TAG, "Grid slot " + slotIndex + ": " + mediaFile.getName() + " (image) rotation=" + rotation);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load image for grid slot " + slotIndex + ": " + e.getMessage());
                }
            } else {
                // Handle video slot
                Log.d(TAG, "Grid slot " + slotIndex + " is VIDEO: " + mediaFile.getName());

                // Skip slots whose file hasn't finished downloading yet.
                // Without this guard, ExoPlayer throws FileDataSourceException when a large
                // video is still downloading while switchToGridMode() fires for the other
                // already-downloaded files — resulting in a partial grid (e.g. 2/4 slots).
                if (!mediaFile.exists() || mediaFile.length() == 0) {
                    Log.w(TAG, "Grid slot " + slotIndex + ": file missing or empty, skipping: " + mediaFile.getName());
                    continue;
                }

                // Make sure TextureView is visible
                if (gridTextureViews[slotIndex] != null) {
                    gridTextureViews[slotIndex].setVisibility(View.VISIBLE);
                }

                // Hide ImageView if it exists
                if (gridImageViews[slotIndex] != null) {
                    gridImageViews[slotIndex].setVisibility(View.GONE);
                }

                // Release any existing player for this slot before creating a new one
                if (gridPlayers[slotIndex] != null) {
                    try {
                        gridPlayers[slotIndex].setVideoSurface(null);
                        gridPlayers[slotIndex].stop();
                        gridPlayers[slotIndex].release();
                    } catch (Exception ignored) {}
                    gridPlayers[slotIndex] = null;
                }
                gridPlayers[slotIndex] = new ExoPlayer.Builder(this).build();
                boolean cachedMuted = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(PREF_IS_MUTED, false);
                gridPlayers[slotIndex].setVolume(cachedMuted ? 0f : 1f);
                gridPlayers[slotIndex].setRepeatMode(Player.REPEAT_MODE_ONE);

                if (gridSurfaces[slotIndex] != null) {
                    gridPlayers[slotIndex].setVideoSurface(gridSurfaces[slotIndex]);
                }

                // Apply rotation transform to TextureView
                if (gridTextureViews[slotIndex] != null) {
                    applyGridTextureViewTransform(gridTextureViews[slotIndex], rotation, fitMode, slotIndex);
                }

                MediaItem item = MediaItem.fromUri(Uri.fromFile(mediaFile));
                final ExoPlayer gridPlayer = gridPlayers[slotIndex];
                // Grid players had NO error handling — a slot that failed to prepare
                // (corrupt file, or NO_MEMORY under up to 4 concurrent decoders) went
                // black permanently. Retry the slot a few times; if it stays dead the
                // watchdog (gridPlaybackHealthy) rebuilds the whole grid.
                gridPlayer.addListener(new Player.Listener() {
                    private int errs = 0;
                    @Override
                    public void onPlayerError(PlaybackException e) {
                        if (gridPlayers[slotIndex] != gridPlayer) return; // stale (slot rebuilt)
                        Log.e(TAG, "Grid slot " + slotIndex + " error: " + e.getMessage());
                        if (++errs <= 3) {
                            gridPlayer.prepare();
                            gridPlayer.play();
                        }
                    }
                });
                gridPlayer.setMediaItem(item);
                gridPlayer.prepare();
                gridPlayer.play();

                Log.d(TAG, "Grid slot " + slotIndex + ": " + mediaFile.getName() + " (video) rotation=" + rotation);
            }
        }
    }

    // ==================== TV TIME-SHARING MODE ====================
    // For Android TV with limited hardware decoders, we use a single player
    // that rotates between video slots, playing each video in its position
    // for a period of time before moving to the next.

    private Handler tvRotationHandler = new Handler(Looper.getMainLooper());
    private Runnable tvRotationRunnable;
    private int tvCurrentSlotIndex = 0;
    private List<Integer> tvVideoSlots = new ArrayList<>();
    private List<File> tvVideoFiles = new ArrayList<>();
    private ExoPlayer tvSharedPlayer;
    private static final long TV_ROTATION_INTERVAL_MS = 8000; // 8 seconds per video

    private void playGridVideosTvMode(List<File> assignedMedia, int numSlots) {
        // Clear previous TV rotation state
        stopTvRotation();
        tvVideoSlots.clear();
        tvVideoFiles.clear();

        // First, display all images in their slots (images don't need decoder)
        // and collect video slots for rotation
        for (int i = 0; i < assignedMedia.size(); i++) {
            File mediaFile = assignedMedia.get(i);
            VideoMetadata vm = getMetadataForFile(mediaFile);

            int rawPos = vm != null ? vm.gridPosition : 0;
            final int slotIndex = (rawPos > 0 && rawPos <= numSlots) ? rawPos - 1 : i;
            if (slotIndex >= numSlots) continue;

            int rotation = vm != null ? vm.rotation : 0;
            String fitMode = vm != null ? vm.fitMode : "cover";
            String contentType = vm != null ? vm.contentType : (isImageFile(mediaFile) ? "image" : "video");

            gridVideoFiles[slotIndex] = mediaFile;
            lastGridRotations[slotIndex] = rotation;

            if ("image".equals(contentType) || isImageFile(mediaFile)) {
                // Handle image slot (same as standard mode)
                if (gridTextureViews[slotIndex] != null) {
                    gridTextureViews[slotIndex].setVisibility(View.GONE);
                }

                if (gridImageViews[slotIndex] == null) {
                    gridImageViews[slotIndex] = new ImageView(this);
                    FrameLayout.LayoutParams params = getGridLayoutParams(layoutMode, slotIndex, numSlots);
                    rootContainer.addView(gridImageViews[slotIndex], params);
                }

                try {
                    Bitmap bitmap = BitmapFactory.decodeFile(mediaFile.getAbsolutePath());
                    if (bitmap != null) {
                        if (rotation != 0) {
                            Matrix matrix = new Matrix();
                            matrix.postRotate(rotation);
                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        }
                        gridImageViews[slotIndex].setScaleType(ImageView.ScaleType.CENTER_CROP);
                        gridImageViews[slotIndex].setImageBitmap(bitmap);
                        gridImageViews[slotIndex].setVisibility(View.VISIBLE);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load image for grid slot " + slotIndex);
                }
            } else {
                // Video slot - add to rotation list
                tvVideoSlots.add(slotIndex);
                tvVideoFiles.add(mediaFile);

                // Make TextureView visible for this slot
                if (gridTextureViews[slotIndex] != null) {
                    gridTextureViews[slotIndex].setVisibility(View.VISIBLE);
                }
                if (gridImageViews[slotIndex] != null) {
                    gridImageViews[slotIndex].setVisibility(View.GONE);
                }
            }
        }

        if (tvVideoSlots.isEmpty()) {
            Log.d(TAG, "TV MODE: No videos to rotate");
            return;
        }

        Log.d(TAG, "TV MODE: Starting rotation for " + tvVideoSlots.size() + " video slots");

        // Create single shared player
        tvSharedPlayer = new ExoPlayer.Builder(this).build();
        tvSharedPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);

        // Start with first video slot
        tvCurrentSlotIndex = 0;
        playTvSlot(tvCurrentSlotIndex);

        // Set up rotation timer
        tvRotationRunnable = new Runnable() {
            @Override
            public void run() {
                // Move to next slot
                tvCurrentSlotIndex = (tvCurrentSlotIndex + 1) % tvVideoSlots.size();
                playTvSlot(tvCurrentSlotIndex);
                tvRotationHandler.postDelayed(this, TV_ROTATION_INTERVAL_MS);
            }
        };
        tvRotationHandler.postDelayed(tvRotationRunnable, TV_ROTATION_INTERVAL_MS);
    }

    private void playTvSlot(int index) {
        if (index >= tvVideoSlots.size() || index >= tvVideoFiles.size()) return;
        if (tvSharedPlayer == null) return;

        int slotIndex = tvVideoSlots.get(index);
        File mediaFile = tvVideoFiles.get(index);
        VideoMetadata vm = getMetadataForFile(mediaFile);
        int rotation = vm != null ? vm.rotation : 0;
        String fitMode = vm != null ? vm.fitMode : "cover";

        Log.d(TAG, "TV MODE: Playing slot " + slotIndex + " -> " + mediaFile.getName());

        // Detach from previous surface
        tvSharedPlayer.setVideoSurface(null);

        // Attach to new slot's surface
        if (gridSurfaces[slotIndex] != null) {
            tvSharedPlayer.setVideoSurface(gridSurfaces[slotIndex]);
        }

        // Apply rotation transform
        if (gridTextureViews[slotIndex] != null) {
            applyGridTextureViewTransform(gridTextureViews[slotIndex], rotation, fitMode, slotIndex);
        }

        // Play the video
        MediaItem item = MediaItem.fromUri(Uri.fromFile(mediaFile));
        tvSharedPlayer.setMediaItem(item);
        tvSharedPlayer.prepare();
        tvSharedPlayer.play();
    }

    private void stopTvRotation() {
        tvRotationHandler.removeCallbacksAndMessages(null);
        if (tvSharedPlayer != null) {
            try {
                tvSharedPlayer.stop();
                tvSharedPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing TV shared player: " + e.getMessage());
            }
            tvSharedPlayer = null;
        }
        tvVideoSlots.clear();
        tvVideoFiles.clear();
    }

    private void applyGridTextureViewTransform(TextureView tv, int rotation, String fitMode, int slotIndex) {
        if (tv == null) return;

        tv.post(() -> {
            int viewWidth = tv.getWidth();
            int viewHeight = tv.getHeight();

            if (viewWidth == 0 || viewHeight == 0) return;

            Matrix matrix = new Matrix();
            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            if ("fill".equals(fitMode)) {
                // Fill: stretch to cover entire slot after rotation
                if (rotation == 90 || rotation == 270) {
                    float scaleX = (float) viewHeight / viewWidth;
                    float scaleY = (float) viewWidth / viewHeight;
                    matrix.setScale(scaleX, scaleY, centerX, centerY);
                }
                // For 0/180, identity scale is correct (TextureView fills its bounds by default)
                matrix.postRotate(rotation, centerX, centerY);
            } else if ("contain".equals(fitMode)) {
                // Contain: fit inside slot, may have bars
                if (rotation == 90 || rotation == 270) {
                    float scale = Math.min((float) viewWidth / viewHeight, (float) viewHeight / viewWidth);
                    matrix.setScale(scale, scale, centerX, centerY);
                }
                matrix.postRotate(rotation, centerX, centerY);
            } else {
                // Cover (default): fill slot, may crop
                matrix.postRotate(rotation, centerX, centerY);
                if (rotation == 90 || rotation == 270) {
                    float scale = Math.max((float) viewWidth / viewHeight, (float) viewHeight / viewWidth);
                    matrix.postScale(scale, scale, centerX, centerY);
                }
            }

            tv.setTransform(matrix);
            Log.d(TAG, "Grid slot " + slotIndex + " transform: rotation=" + rotation + " fitMode=" + fitMode);
        });
    }
}