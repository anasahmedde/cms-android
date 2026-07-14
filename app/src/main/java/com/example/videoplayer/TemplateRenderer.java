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
 * Zone types handled here: text, ticker, clock, image media/qr, and (v10) ONE
 * video media zone: a muted, looping ExoPlayer streaming straight from the
 * resolved URL (presigned S3 or external). Only the FIRST video zone gets a
 * decoder — additional video zones fall back to their background box — keeping
 * total decoders at two (main playlist + one zone) to stay clear of the
 * Qualcomm multi-decoder traps. QR zones stay image-only (a QR is a picture).
 * The playlist zone is rendered by the activity. Callers MUST invoke
 * release() when swapping or removing the overlay.
 *
 * All geometry is PERCENT (0-100) of the screen, matching the backend contract.
 */
public class TemplateRenderer {

    private static final String TAG = "TemplateRenderer";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 15_000;

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

        switch (type) {
            case "text":   return buildText(z, style, content, rect, false);
            case "ticker": return buildText(z, style, content, rect, true);
            case "clock":  return buildClock(content, style, rect);
            case "media":
            case "qr":     return buildImage(style, content, rect, "qr".equals(type));
            default:       return null;
        }
    }

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
        tv.setGravity(g | Gravity.CENTER_VERTICAL);
        if (style.optBoolean("bold", false)) tv.setTypeface(Typeface.DEFAULT_BOLD);
        // RTL for Urdu/Arabic
        if ("rtl".equals(style.optString("direction", "ltr"))) {
            tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        }
        tv.setPadding(dp(6), dp(2), dp(6), dp(2));
        return tv;
    }

    /** Background precedence: content image > content gradient > content/style color. */
    private void applyTextBackground(final TextView tv, JSONObject content, JSONObject style, int[] rect) {
        String img = content.optString("bg_image", "");
        if (!img.isEmpty()) {
            final String url = img;
            final int target = Math.max(rect[2], rect[3]);
            new Thread(() -> {
                final Bitmap b = downloadAndDecode(url, url.contains("?") ? url.substring(0, url.indexOf('?')) : url, target);
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
            tv.setMaxLines(3);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
        String bg = style.optString("bg_color", isQr ? "#FFFFFF" : "");
        if (!bg.isEmpty()) holder.setBackgroundColor(parseColor(bg, isQr ? Color.WHITE : Color.TRANSPARENT));
        if (url.isEmpty()) {
            return holder; // nothing to show; keep the (bg) box — never an error on screen
        }
        if ("video".equals(mediaType) && !isQr) {
            if (!zonePlayers.isEmpty()) {
                Log.w(TAG, "Only one video zone is rendered per template; extra video zone skipped");
                return holder;
            }
            attachZoneVideo(holder, url, style);
            return holder;
        }
        if ("video".equals(mediaType)) {
            return holder; // QR zones are image-only
        }
        final ImageView iv = new ImageView(ctx);
        iv.setScaleType(isQr ? ImageView.ScaleType.FIT_CENTER : scaleType(style));
        int pad = isQr ? (int) (style.optDouble("padding_pct", 6) / 100.0 * Math.min(rect[2], rect[3])) : 0;
        iv.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        holder.addView(iv, lp);
        loadImageAsync(iv, url, Math.max(rect[2], rect[3]));
        return holder;
    }

    /** Muted, looping video streamed from the resolved URL inside a zone. */
    private void attachZoneVideo(FrameLayout holder, String url, JSONObject style) {
        try {
            PlayerView pv = new PlayerView(ctx);
            pv.setUseController(false);
            boolean contain = "contain".equals(style.optString("fit_mode", "cover"));
            pv.setResizeMode(contain
                    ? AspectRatioFrameLayout.RESIZE_MODE_FIT
                    : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            ExoPlayer player = new ExoPlayer.Builder(ctx).build();
            zonePlayers.add(player);
            pv.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(url));
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
            player.setVolume(0f); // the main playlist owns audio
            player.setPlayWhenReady(true);
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    // Keep the background box; the template refresh loop retries
                    // naturally when the stamp moves (e.g. presign renewed).
                    Log.w(TAG, "zone video error (" + url + "): " + error.getErrorCodeName());
                }
            });
            player.prepare();
            holder.addView(pv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
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
        String fit = style.optString("fit_mode", "cover");
        return "contain".equals(fit) ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP;
    }

    /** Download+cache (by URL path, so presign query changes don't re-download) and decode sampled. */
    private void loadImageAsync(final ImageView iv, final String url, final int targetPx) {
        final String cacheKey = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        new Thread(() -> {
            final Bitmap bmp = downloadAndDecode(url, cacheKey, targetPx);
            if (bmp != null) ui.post(() -> iv.setImageBitmap(bmp));
        }).start();
    }

    private Bitmap downloadAndDecode(String url, String cacheKey, int targetPx) {
        try {
            File dir = new File(ctx.getFilesDir(), "tpl");
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
            // Sample to the target size to avoid OOM on large images.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (targetPx > 0 && longest / sample > targetPx * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
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
