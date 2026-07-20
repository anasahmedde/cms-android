package com.example.videoplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Renders a resolved screen template (from GET /device/{id}/template) as a
 * transparent full-screen overlay of percentage-positioned zone views.
 *
 * Deliberately does NOT own video playback: the existing single-mode ExoPlayer
 * (textureView) is retargeted by the activity to the "playlist" zone's rectangle
 * and shows THROUGH the transparent gaps of this overlay. That reuses the
 * battle-tested playback/download/rotation path and keeps decoder pressure to
 * the one player the app already runs (avoids the Qualcomm multi-decoder traps).
 *
 * Zone types handled here: text, ticker, clock, image media/qr, and video
 * media zones: each a muted, looping ExoPlayer streaming straight from the
 * resolved URL (presigned S3 or external). Every video zone gets its own
 * decoder up to MAX_ZONE_VIDEO_PLAYERS; beyond that budget a zone falls back
 * to an opaque box (so the main playlist video behind the transparent overlay
 * doesn't bleed through and look like the wrong video). The cap keeps the
 * concurrent decoder count bounded to stay clear of the Qualcomm multi-decoder
 * traps. QR zones stay image-only (a QR is a picture).
 * The playlist zone is rendered by the activity. Callers MUST invoke
 * release() when swapping or removing the overlay.
 *
 * All geometry is PERCENT (0-100) of the screen, matching the backend contract.
 */
public class TemplateRenderer {

    private static final String TAG = "TemplateRenderer";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    // Max simultaneous in-zone video decoders. Each zone video is one hardware
    // decoder; together with the main playlist player that is up to MAX+1
    // concurrent decoders. Kept small so a pathological template can't exhaust
    // the decoder pool on low-end (e.g. Qualcomm) signage boxes. Video zones
    // past the cap render as an opaque box, not a transparent hole.
    private static final int MAX_ZONE_VIDEO_PLAYERS = 3;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int screenW, screenH;

    private FrameLayout overlay;
    private JSONObject template;
    private int[] playlistRectPx; // {left, top, width, height} or null if no playlist zone
    private final List<ExoPlayer> zonePlayers = new ArrayList<>();

    public TemplateRenderer(Context ctx, int screenW, int screenH) {
        this.ctx = ctx;
        this.screenW = screenW;
        this.screenH = screenH;
    }

    public String orientation() {
        return template == null ? null : template.optString("orientation", "landscape");
    }

    /** Pixel rect of the playlist zone, or null if the template has none. */
    public int[] playlistRectPx() { return playlistRectPx; }

    public FrameLayout overlayView() { return overlay; }

    /**
     * Build the overlay view tree for a template. Must be called on the UI thread.
     * Returns the overlay FrameLayout (transparent, MATCH_PARENT) with zone views
     * added; the caller adds it on top of the video surface.
     */
    public FrameLayout build(JSONObject tpl) {
        release(); // defensively free any players from a previous overlay
        this.template = tpl;
        this.playlistRectPx = null;
        overlay = new FrameLayout(ctx);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setClickable(false);

        JSONArray zones = tpl.optJSONArray("zones");
        if (zones == null) return overlay;
        pruneZoneCache(zones); // drop cached media this template no longer uses

        for (int i = 0; i < zones.length(); i++) {
            JSONObject z = zones.optJSONObject(i);
            if (z == null) continue;
            String type = z.optString("type", "");
            int[] rect = rectPx(z);
            if ("playlist".equals(type)) {
                // The activity positions the video surface here; leave a hole.
                playlistRectPx = rect;
                continue;
            }
            View v = buildZone(type, z, rect);
            if (v != null) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(rect[2], rect[3]);
                lp.leftMargin = rect[0];
                lp.topMargin = rect[1];
                overlay.addView(v, lp);
            }
        }
        return overlay;
    }

    private int[] rectPx(JSONObject z) {
        double x = z.optDouble("x", 0), y = z.optDouble("y", 0);
        double w = z.optDouble("w", 100), h = z.optDouble("h", 100);
        int left = (int) Math.round(x / 100.0 * screenW);
        int top = (int) Math.round(y / 100.0 * screenH);
        int width = Math.max(1, (int) Math.round(w / 100.0 * screenW));
        int height = Math.max(1, (int) Math.round(h / 100.0 * screenH));
        return new int[]{left, top, width, height};
    }

    private View buildZone(String type, JSONObject z, int[] rect) {
        JSONObject style = z.optJSONObject("style");
        if (style == null) style = new JSONObject();
        JSONObject content = z.optJSONObject("content");
        if (content == null) content = new JSONObject();

        // Multiple positioned text items inside one zone (designer-composed).
        JSONArray runs = content.optJSONArray("runs");
        if (("text".equals(type) || "ticker".equals(type)) && runs != null && runs.length() > 0) {
            return buildTextRuns(style, content, runs, rect);
        }

        switch (type) {
            case "text":   return buildText(z, style, content, rect, false);
            case "ticker": return buildText(z, style, content, rect, true);
            case "clock":  return buildClock(content, style, rect);
            case "media":
            case "qr":     return buildImage(style, content, rect, "qr".equals(type));
            default:       return null;
        }
    }

    /**
     * A zone holding multiple positioned, individually-styled text items. Each
     * run's x/y/w and font size are PERCENTAGES of the zone, so the layout
     * scales with the screen. The zone's own background still applies underneath.
     */
    private View buildTextRuns(JSONObject style, JSONObject content, JSONArray runs, int[] rect) {
        FrameLayout holder = new FrameLayout(ctx);
        // Zone-level background (color/gradient/image) behind the runs.
        applyTextBackground(holder, content, style, rect);
        int zoneW = rect[2], zoneH = rect[3];
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r == null) continue;
            TextView tv = new TextView(ctx);
            tv.setText(r.optString("text", ""));
            tv.setTextColor(parseColor(r.optString("text_color", "#FFFFFF"), Color.WHITE));
            double fs = r.optDouble("font_size_vh", 20);
            float px = Math.max(dp(8), (float) (fs / 100.0 * zoneH));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
            if (r.optBoolean("bold", false)) tv.setTypeface(Typeface.DEFAULT_BOLD);
            String align = r.optString("align", "left");
            tv.setGravity("center".equals(align) ? Gravity.CENTER_HORIZONTAL
                    : "right".equals(align) ? Gravity.END : Gravity.START);
            double rx = clampPct(r.optDouble("x", 0)), ry = clampPct(r.optDouble("y", 0));
            double rw = r.has("w") ? clampPct(r.optDouble("w", 100)) : (100 - rx);
            int w = Math.max(1, (int) Math.round(rw / 100.0 * zoneW));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    w, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = (int) Math.round(rx / 100.0 * zoneW);
            lp.topMargin = (int) Math.round(ry / 100.0 * zoneH);
            holder.addView(tv, lp);
        }
        return holder;
    }

    private double clampPct(double v) { return Math.max(0, Math.min(100, v)); }

    private TextView baseText(JSONObject content, JSONObject style, int[] rect) {
        TextView tv = new TextView(ctx);
        applyTextBackground(tv, content, style, rect);
        String tc = content.optString("text_color", style.optString("text_color", "#FFFFFF"));
        tv.setTextColor(parseColor(tc, Color.WHITE));
        // font_size_vh is a % of the zone height; convert to px.
        double vh = style.optDouble("font_size_vh", 40);
        float px = (float) (vh / 100.0 * rect[3]);
        // clamp so a huge zone doesn't produce an absurd size
        px = Math.max(dp(10), Math.min(px, rect[3] * 0.9f));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        String align = style.optString("align", "center");
        int g = "left".equals(align) ? Gravity.START : "right".equals(align) ? Gravity.END : Gravity.CENTER_HORIZONTAL;
        String valign = style.optString("valign", "middle");
        int vg = "top".equals(valign) ? Gravity.TOP : "bottom".equals(valign) ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL;
        tv.setGravity(g | vg);
        if (style.optBoolean("bold", false)) tv.setTypeface(Typeface.DEFAULT_BOLD);
        // RTL for Urdu/Arabic
        if ("rtl".equals(style.optString("direction", "ltr"))) {
            tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        }
        tv.setPadding(dp(6), dp(2), dp(6), dp(2));
        return tv;
    }

    /** Background precedence: content image > content gradient > content/style color. */
    private void applyTextBackground(final View tv, JSONObject content, JSONObject style, int[] rect) {
        String img = content.optString("bg_image", "");
        if (!img.isEmpty()) {
            final String url = img;
            final int target = Math.max(rect[2], rect[3]);
            new Thread(() -> {
                final Bitmap b = downloadAndDecode(url, target);
                if (b != null) ui.post(() -> tv.setBackground(new BitmapDrawable(ctx.getResources(), b)));
            }).start();
            return;
        }
        JSONObject grad = content.optJSONObject("bg_gradient");
        if (grad != null) {
            GradientDrawable gd = buildGradient(grad);
            if (gd != null) { tv.setBackground(gd); return; }
        }
        String col = content.optString("bg_color", style.optString("bg_color", ""));
        if (!col.isEmpty()) tv.setBackgroundColor(parseColor(col, Color.TRANSPARENT));
    }

    private GradientDrawable buildGradient(JSONObject grad) {
        JSONArray stops = grad.optJSONArray("stops");
        if (stops == null || stops.length() < 2) return null;
        int[] colors = new int[stops.length()];
        for (int i = 0; i < stops.length(); i++) colors[i] = parseColor(stops.optString(i, "#000000"), Color.BLACK);
        GradientDrawable gd = new GradientDrawable();
        gd.setColors(colors);
        gd.setOrientation(orientationForAngle(grad.optInt("angle", 135)));
        return gd;
    }

    private GradientDrawable.Orientation orientationForAngle(int a) {
        a = ((a % 360) + 360) % 360;  // CSS angle → the nearest of 8 drawable directions
        if (a < 23 || a >= 338) return GradientDrawable.Orientation.LEFT_RIGHT;
        if (a < 68) return GradientDrawable.Orientation.BL_TR;
        if (a < 113) return GradientDrawable.Orientation.BOTTOM_TOP;
        if (a < 158) return GradientDrawable.Orientation.BR_TL;
        if (a < 203) return GradientDrawable.Orientation.RIGHT_LEFT;
        if (a < 248) return GradientDrawable.Orientation.TR_BL;
        if (a < 293) return GradientDrawable.Orientation.TOP_BOTTOM;
        return GradientDrawable.Orientation.TL_BR;
    }

    private View buildText(JSONObject z, JSONObject style, JSONObject content, int[] rect, boolean ticker) {
        TextView tv = baseText(content, style, rect);
        tv.setText(content.optString("text", ""));
        if (ticker) {
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            tv.setMarqueeRepeatLimit(-1);
            tv.setSelected(true); // needed to start the marquee
            tv.setHorizontallyScrolling(true);
        } else {
            // Auto-fit: text shrinks to fit the box no matter how long it is.
            // The designer's font_size_vh becomes the MAX; text scales down (to a
            // small floor) so nothing overflows or gets clipped.
            // With the designer's "Auto-fit text" checkbox (style.text_fit=fill —
            // the text twin of media fit=fill) the words also GROW: the cap is
            // the ZONE height instead of the designed size, and the text centers
            // both ways, so it always fills the box as large as it fits.
            tv.setMaxLines(20);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            boolean fillFit = "fill".equals(style.optString("text_fit", ""));
            if (fillFit) tv.setGravity(Gravity.CENTER);
            int maxPx = fillFit
                    ? Math.max(dp(12), Math.round(rect[3] * 0.95f))
                    : Math.max(dp(11), Math.round(tv.getTextSize()));
            int minPx = dp(8);
            if (minPx < maxPx) {
                try {
                    androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                            tv, minPx, maxPx, Math.max(1, dp(1)), TypedValue.COMPLEX_UNIT_PX);
                } catch (Exception ignored) { /* keep the fixed size on any OEM quirk */ }
            }
        }
        return tv;
    }

    private View buildClock(JSONObject content, JSONObject style, int[] rect) {
        final TextView tv = baseText(content, style, rect);
        final String fmt = "HH:mm:ss".equals(style.optString("format", "HH:mm")) ? "HH:mm:ss" : "HH:mm";
        final SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.getDefault());
        final Runnable tick = new Runnable() {
            @Override public void run() {
                tv.setText(sdf.format(new Date()));
                tv.postDelayed(this, 1000);
            }
        };
        tv.post(tick);
        return tv;
    }

    private View buildImage(JSONObject style, JSONObject content, int[] rect, boolean isQr) {
        String url = content.optString("media_url", "");
        String mediaType = content.optString("media_type", "image");
        FrameLayout holder = new FrameLayout(ctx);
        // No white default for QR zones anymore: the quiet zone lives on a square
        // card around the code (below), so an unset/non-square QR box no longer
        // renders as a white slab. An explicitly designed background still paints.
        String bg = style.optString("bg_color", "");
        if (!bg.isEmpty()) holder.setBackgroundColor(parseColor(bg, Color.TRANSPARENT));
        if (url.isEmpty()) {
            return holder; // nothing to show; keep the (bg) box — never an error on screen
        }
        // QR boxes with an EXPLICIT fit (sheet fit column / dashboard Fit
        // dropdown) render like media boxes — no quiet-zone card, the image
        // fills the box per the fit. Blank fit keeps the square scannable
        // card below, which is what generated QR codes need.
        String fitMode = style.optString("fit_mode", "cover");
        boolean qrCard = isQr && style.optString("fit_mode", "").isEmpty();
        // fit=contain letterboxes media inside the zone (and fit=none can leave
        // gaps) — the leftover area was TRANSPARENT, so the fullscreen playlist
        // video bled through around the contained media and read as broken
        // ("small video inside another video"). Back the zone with opaque black
        // (designer bg still wins). cover/fill always paint the whole zone, so
        // they need no backing.
        if (!qrCard && bg.isEmpty() && ("contain".equals(fitMode) || "none".equals(fitMode))) {
            holder.setBackgroundColor(Color.BLACK);
        }
        if ("video".equals(mediaType) && !isQr) {
            // Warm the offline cache on every build, not only once streaming
            // starts — a restart mid-download or a budget-blocked zone must
            // still end up cached for the next offline boot.
            if (cachedZoneVideo(url) == null) downloadZoneVideoAsync(url);
            if (zonePlayers.size() >= MAX_ZONE_VIDEO_PLAYERS) {
                // Over the decoder budget: paint an opaque box so the main
                // playlist video behind the transparent overlay doesn't bleed
                // through this zone and read as the wrong video.
                if (bg.isEmpty()) holder.setBackgroundColor(Color.BLACK);
                Log.w(TAG, "zone video decoder budget reached (" + MAX_ZONE_VIDEO_PLAYERS
                        + "); extra video zone shown as a static box");
                return holder;
            }
            attachZoneVideo(holder, url, style);
            return holder;
        }
        if ("video".equals(mediaType)) {
            return holder; // QR zones are image-only (with or without a fit)
        }
        final ImageView iv = new ImageView(ctx);
        iv.setScaleType(qrCard ? ImageView.ScaleType.FIT_CENTER : scaleType(style));
        if (qrCard) {
            // The light quiet zone a QR needs in order to scan hugs the code as a
            // centered SQUARE card (side = the box's smaller dimension); painting
            // the whole zone white turned non-square QR boxes into a white slab.
            // Matches static/player.html fillQr. bg precedence: content > style > white.
            int side = Math.min(rect[2], rect[3]);
            int pad = (int) (style.optDouble("padding_pct", 6) / 100.0 * side);
            FrameLayout card = new FrameLayout(ctx);
            String cardBg = content.optString("bg_color", style.optString("bg_color", "#FFFFFF"));
            card.setBackgroundColor(parseColor(cardBg, Color.WHITE));
            iv.setPadding(pad, pad, pad, pad);
            card.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            holder.addView(card, new FrameLayout.LayoutParams(side, side, Gravity.CENTER));
        } else {
            holder.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        loadImageAsync(iv, url, Math.max(rect[2], rect[3]));
        return holder;
    }

    /** Muted, looping video streamed from the resolved URL inside a zone. */
    private void attachZoneVideo(FrameLayout holder, String url, JSONObject style) {
        try {
            PlayerView pv = new PlayerView(ctx);
            pv.setUseController(false);
            // Same fit vocabulary as the playlist path (applyTextureViewTransform)
            // and the web player: fill = stretch to the whole zone (no crop, no
            // bars, may distort). ExoPlayer has no "original size" mode, so none
            // falls back to FIT — the no-crop, no-distort rendering.
            int resizeMode;
            switch (style.optString("fit_mode", "cover")) {
                case "contain":
                case "none":
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; break;
                case "fill":
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; break;
                default:
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            }
            pv.setResizeMode(resizeMode);
            final ExoPlayer player = new ExoPlayer.Builder(ctx).build();
            zonePlayers.add(player);
            pv.setPlayer(player);
            // Offline-first: play the cached copy when we have one (survives lost
            // internet and expired presigns); stream only on first sight of a new
            // file, while a background download fills the cache for the next
            // build/boot. The cache key is the URL PATH, so presign renewals hit
            // the same file and only a real content change re-downloads.
            final File cached = cachedZoneVideo(url);
            final boolean playingLocal = cached != null;
            player.setMediaItem(MediaItem.fromUri(playingLocal
                    ? android.net.Uri.fromFile(cached).toString() : url));
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
            player.setVolume(0f); // the main playlist owns audio
            player.setPlayWhenReady(true);
            player.addListener(new Player.Listener() {
                private int errs = 0;
                private boolean fellBack = false;
                @Override public void onPlayerError(PlaybackException error) {
                    Log.w(TAG, "zone video error (" + (playingLocal && !fellBack ? "cache" : "stream")
                            + " " + url + "): " + error.getErrorCodeName());
                    // A corrupt cached file can never recover — drop it and fall
                    // back to streaming the source once (re-cached in background).
                    // ONLY while online: offline, the cached copy is ALL we have —
                    // deleting it on a transient decode error would leave the box
                    // permanently black across offline reboots. Keep it and retry
                    // below; once online, a real corruption still falls through here.
                    if (playingLocal && !fellBack && isOnline()) {
                        fellBack = true;
                        try { cached.delete(); } catch (Exception ignored) { }
                        downloadZoneVideoAsync(url);
                        try {
                            player.setMediaItem(MediaItem.fromUri(url));
                            player.prepare();
                            player.play();
                            return;
                        } catch (Exception ignored) { }
                    }
                    // Bounded re-prepare handles a transient network stall so the box
                    // doesn't stay black. A hard failure (e.g. the presigned URL has
                    // expired — a 403) can't recover from the same URL: the activity's
                    // periodic template re-fetch renews the URL and rebuilds the zone.
                    if (++errs <= 3) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try { player.prepare(); player.play(); } catch (Exception ignored) { }
                        }, 3000L * errs);
                    }
                }
            });
            player.prepare();
            holder.addView(pv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            if (!playingLocal) downloadZoneVideoAsync(url);
        } catch (Exception e) {
            Log.w(TAG, "zone video setup failed: " + e.getMessage());
        }
    }

    /** Release every zone video player. MUST be called when the overlay is swapped/removed. */
    public void release() {
        for (ExoPlayer p : zonePlayers) {
            try { p.release(); } catch (Exception ignored) { }
        }
        zonePlayers.clear();
    }

    private ImageView.ScaleType scaleType(JSONObject style) {
        switch (style.optString("fit_mode", "cover")) {
            case "contain":
                return ImageView.ScaleType.FIT_CENTER;
            case "fill":   // stretch: fill the whole zone, no crop/bars, may distort
                return ImageView.ScaleType.FIT_XY;
            case "none":   // original size, centered (cropped if larger than the zone)
                return ImageView.ScaleType.CENTER;
            default:
                return ImageView.ScaleType.CENTER_CROP;
        }
    }

    // ── Zone media cache (filesDir/tpl) ──────────────────────────────────────
    // Keyed by URL PATH (presign query stripped), same convention as the image
    // cache, so renewed presigns keep hitting the same file and only a real
    // content change (new S3 key) downloads again.

    private static final long MAX_ZONE_VIDEO_BYTES = 512L * 1024 * 1024; // storage guard
    private static final java.util.Set<String> zoneDownloadsInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private File tplCacheDir() {
        File dir = new File(ctx.getFilesDir(), "tpl");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String cacheName(String url, String ext) {
        // Key by URL PATH only. The presign QUERY changes on every renewal and
        // the S3 HOST form can flip (bucket.s3.amazonaws.com vs regional
        // endpoint) — neither is a content change, and a host-keyed cache
        // silently misses after either, which breaks offline playback. Only a
        // real S3 key change should re-download.
        String key = url;
        int q = key.indexOf('?');
        if (q >= 0) key = key.substring(0, q);
        int scheme = key.indexOf("://");
        if (scheme >= 0) {
            int slash = key.indexOf('/', scheme + 3);
            if (slash >= 0) key = key.substring(slash);
        }
        return Integer.toHexString(key.hashCode()) + ext;
    }

    /** Best-effort connectivity check — decides whether a cache-playback error
     *  may fall back to streaming (and re-downloading) or must keep the file. */
    private boolean isOnline() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true; // unknown → assume online (old behavior)
        }
    }

    /** The cached copy of a zone video, or null when not (fully) downloaded yet. */
    private File cachedZoneVideo(String url) {
        File f = new File(tplCacheDir(), cacheName(url, ".vid"));
        return f.exists() && f.length() > 0 ? f : null;
    }

    /**
     * Download a zone video to the cache in the background (temp file + atomic
     * rename, so a torn download never plays). The CURRENT playback keeps
     * streaming; the cached copy takes over on the next template build/boot —
     * from then on the box plays with no internet at all.
     */
    private void downloadZoneVideoAsync(final String url) {
        final String name = cacheName(url, ".vid");
        if (!zoneDownloadsInFlight.add(name)) return; // already downloading
        new Thread(() -> {
            File tmp = new File(tplCacheDir(), name + ".part");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                long len = c.getContentLengthLong();
                if (len > MAX_ZONE_VIDEO_BYTES) {
                    Log.w(TAG, "zone video too large to cache (" + len + "b): " + url);
                    c.disconnect();
                    return;
                }
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    c.disconnect();
                }
                File dst = new File(tplCacheDir(), name);
                if (!tmp.renameTo(dst)) {
                    tmp.delete();
                } else {
                    Log.d(TAG, "zone video cached (" + dst.length() + "b): " + url);
                }
            } catch (Exception e) {
                Log.w(TAG, "zone video cache failed (" + url + "): " + e.getMessage());
                tmp.delete();
            } finally {
                zoneDownloadsInFlight.remove(name);
            }
        }).start();
    }

    /**
     * Delete cached zone media the current template no longer references —
     * a content change swaps the S3 key, so the old file would sit on disk
     * forever otherwise. In-flight downloads (.part) are left alone.
     */
    private void pruneZoneCache(JSONArray zones) {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (int i = 0; i < zones.length(); i++) {
            JSONObject z = zones.optJSONObject(i);
            if (z == null) continue;
            JSONObject c = z.optJSONObject("content");
            if (c == null) continue;
            String media = c.optString("media_url", "");
            if (!media.isEmpty()) {
                wanted.add(cacheName(media, ".vid"));
                wanted.add(cacheName(media, ".img"));
            }
            String bg = c.optString("bg_image", "");
            if (!bg.isEmpty()) wanted.add(cacheName(bg, ".img"));
        }
        new Thread(() -> {
            File[] files = tplCacheDir().listFiles();
            if (files == null) return;
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".part")) continue; // in-flight download
                if (!wanted.contains(n) && f.delete()) {
                    Log.d(TAG, "pruned stale zone media: " + n);
                }
            }
        }).start();
    }

    /** Download+cache (by URL path, so presign query changes don't re-download) and decode sampled. */
    private void loadImageAsync(final ImageView iv, final String url, final int targetPx) {
        new Thread(() -> {
            final Bitmap bmp = downloadAndDecode(url, targetPx);
            if (bmp != null) ui.post(() -> iv.setImageBitmap(bmp));
        }).start();
    }

    private Bitmap downloadAndDecode(String url, int targetPx) {
        try {
            File dir = new File(ctx.getFilesDir(), "tpl");
            if (!dir.exists()) dir.mkdirs();
            // Same naming as the video cache + pruneZoneCache's wanted-set —
            // one formula, or the pruner deletes live cache files.
            File f = new File(dir, cacheName(url, ".img"));
            if (!f.exists() || f.length() == 0) {
                // Atomic write (temp + rename, like the video cache): a
                // connection drop mid-download used to leave a truncated .img
                // that decoded to null and was cached FOREVER — the box stayed
                // blank until a content change moved the URL.
                File tmp = new File(dir, f.getName() + ".part");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally { c.disconnect(); }
                if (!tmp.renameTo(f)) { tmp.delete(); return null; }
            }
            // Sample to the target size to avoid OOM on large images.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (targetPx > 0 && longest / sample > targetPx * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (bmp == null) {
                // Undecodable cached file (corrupt survivor from the pre-atomic
                // era, or a bad object) — delete it so the next build retries.
                Log.w(TAG, "cached image undecodable, deleting: " + f.getName());
                f.delete();
            }
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "template image load failed: " + url, e);
            return null;
        }
    }

    private int parseColor(String s, int fallback) {
        try { return Color.parseColor(s); } catch (Exception e) { return fallback; }
    }

    private int dp(float d) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, d, ctx.getResources().getDisplayMetrics());
    }
}
