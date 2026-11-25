package com.limelight.local;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.limelight.binding.video.XrShared;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * The strip along the bottom of the picture in local playback: play or pause,
 * elapsed time, the track, and the total. Drawn here, shown by the renderer
 * (addTransportLayer in xr_layers.c), and hit tested here off the pointer's
 * place on the picture, so the geometry below has to match that function:
 * the strip is the picture's width less a 2% margin each side, the same
 * margin under it, and TEX_H / TEX_W as tall as it is wide.
 */
final class TransportBar {
    // Shared with xr_shared.h so C hit-testing, curved cylinder rendering, and Java drawing agree
    static final int TEX_W = XrShared.TRANSPORT_WIDTH;
    static final int TEX_H = XrShared.TRANSPORT_HEIGHT;
    private static final float MARGIN = XrShared.TRANSPORT_MARGIN_FRAC;

    // Texture x of each part inside 1536x112 strip (matches vr-palette.html bar view)
    private static final float BTN_END = 120f;
    private static final float TRACK_START = 330f;
    // Right-hand buttons: 「调整」 always shown; 「弹」 shown only when source supports danmaku
    private static final float ADJUST_X0 = TEX_W - 188f;     // 1348
    private static final float ADJUST_X1 = TEX_W - 24f;      // 1512
    private static final float DANMAKU_X0 = ADJUST_X0 - 94f; // 1254
    private static final float DANMAKU_X1 = ADJUST_X0 - 16f; // 1332
    private static final float TIME_RIGHT_W = 190f;
    private static final float TRACK_END_PLAIN = ADJUST_X0 - TIME_RIGHT_W;    // 1158
    private static final float TRACK_END_DANMAKU = DANMAKU_X0 - TIME_RIGHT_W; // 1064

    // Error state pill buttons on the right of the strip
    private static final float ERR_RETRY_X0 = TEX_W - 436f;
    private static final float ERR_RETRY_X1 = TEX_W - 236f;
    private static final float ERR_BACK_X0 = TEX_W - 216f;
    private static final float ERR_BACK_X1 = TEX_W - 32f;

    private final Bitmap bitmap = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bitmap);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ByteBuffer[] buffers = new ByteBuffer[2];
    private int next;
    private volatile float aspect;  // effective display width over height

    TransportBar(int videoWidth, int videoHeight) {
        aspect = (float) videoWidth / Math.max(1, videoHeight);
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = ByteBuffer.allocateDirect(TEX_W * TEX_H * 4).order(ByteOrder.nativeOrder());
        }
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    /** Updates effective per-eye display aspect (e.g. Full-SBS 3840x1080 -> 16:9). */
    void setDisplayAspect(float widthOverHeight) {
        if (widthOverHeight > 0.1f && widthOverHeight < 10.0f) {
            aspect = widthOverHeight;
        }
    }

    // ---- where the strip is on the picture, u and v in 0..1, v down ----

    private float top() {
        return 1f - MARGIN * aspect - stripHeight();
    }

    private float bottom() {
        return 1f - MARGIN * aspect;
    }

    private float stripHeight() {
        return (1f - 2f * MARGIN) * TEX_H / TEX_W * aspect;
    }

    /** Texture x under picture u, or NaN when the point is off the strip. */
    float texX(float u, float v) {
        float slack = stripHeight() * 0.35f;
        if (v < top() - slack || v > bottom() + slack || u < MARGIN || u > 1f - MARGIN) {
            return Float.NaN;
        }
        return (u - MARGIN) / (1f - 2f * MARGIN) * TEX_W;
    }

    static boolean onButton(float x) {
        return !Float.isNaN(x) && x <= BTN_END + 20f;
    }

    static boolean onAdjustButton(float x) {
        return !Float.isNaN(x) && x >= ADJUST_X0 - 8f && x <= ADJUST_X1 + 8f;
    }

    static boolean onDanmakuButton(float x, boolean showDanmakuToggle) {
        return showDanmakuToggle && !Float.isNaN(x) && x >= DANMAKU_X0 - 8f && x <= DANMAKU_X1 + 8f;
    }

    static boolean onErrorRetry(float x) {
        return !Float.isNaN(x) && x >= ERR_RETRY_X0 - 10f && x <= ERR_RETRY_X1 + 10f;
    }

    static boolean onErrorBack(float x) {
        return !Float.isNaN(x) && x >= ERR_BACK_X0 - 10f && x <= ERR_BACK_X1 + 10f;
    }

    static float trackEnd(boolean showDanmakuToggle) {
        return showDanmakuToggle ? TRACK_END_DANMAKU : TRACK_END_PLAIN;
    }

    /** 0..1 along the track for texture x, or NaN when it is not on the track. */
    static float trackFraction(float x) {
        return trackFraction(x, false);
    }

    /** 0..1 along the track for texture x, accounting for the optional 「弹」 button. */
    static float trackFraction(float x, boolean showDanmakuToggle) {
        float end = trackEnd(showDanmakuToggle);
        if (Float.isNaN(x) || x < TRACK_START - 30f || x > end + 30f) {
            return Float.NaN;
        }
        return Math.max(0f, Math.min(1f, (x - TRACK_START) / (end - TRACK_START)));
    }

    // ---- drawing ----

    /**
     * Draws the strip and returns the buffer to post. hover is the track
     * fraction under the pointer, or NaN. Labels must be supplied from Android string resources.
     */
    ByteBuffer draw(boolean playing, int posMs, int durMs, float hover, boolean buttonHot,
                    String title, String adjustLabel, String danmakuLabel,
                    boolean showDanmakuToggle, boolean danmakuOn, boolean paletteOpen,
                    boolean adjustHot, boolean danmakuHot) {
        canvas.drawColor(0, PorterDuff.Mode.SRC);
        canvas.save();
        // Texture rows run bottom up
        canvas.translate(0f, TEX_H);
        canvas.scale(1f, -1f);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xC0101014);
        canvas.drawRoundRect(new RectF(0, 0, TEX_W, TEX_H), TEX_H / 2f, TEX_H / 2f, paint);

        // Play or pause
        float cy = TEX_H / 2f;
        paint.setColor(buttonHot ? 0xFFF0A957 : Color.WHITE);
        if (playing) {
            canvas.drawRoundRect(new RectF(44, 30, 58, TEX_H - 30), 3, 3, paint);
            canvas.drawRoundRect(new RectF(70, 30, 84, TEX_H - 30), 3, 3, paint);
        } else {
            Path tri = new Path();
            tri.moveTo(46, 28);
            tri.lineTo(90, cy);
            tri.lineTo(46, TEX_H - 28);
            tri.close();
            canvas.drawPath(tri, paint);
        }

        float frac = durMs > 0 ? Math.max(0f, Math.min(1f, posMs / (float) durMs)) : 0f;
        paint.setTextSize(40f);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(clock(posMs), TRACK_START - 28f, cy + 14f, paint);

        // Track
        float x0 = TRACK_START;
        float x1 = trackEnd(showDanmakuToggle);
        float h = 10f;
        paint.setColor(0x50FFFFFF);
        canvas.drawRoundRect(new RectF(x0, cy - h / 2, x1, cy + h / 2), h / 2, h / 2, paint);
        float xp = x0 + (x1 - x0) * frac;
        paint.setColor(0xFFF0A957);
        canvas.drawRoundRect(new RectF(x0, cy - h / 2, xp, cy + h / 2), h / 2, h / 2, paint);
        canvas.drawCircle(xp, cy, 16f, paint);

        String right = clock(durMs);
        if (!Float.isNaN(hover)) {
            float xh = x0 + (x1 - x0) * hover;
            paint.setColor(Color.WHITE);
            canvas.drawRect(xh - 2f, cy - 22f, xh + 2f, cy + 22f, paint);
            right = clock((int) (hover * durMs));
            paint.setColor(0xFFF0A957);
        } else {
            paint.setColor(0xCCFFFFFF);
        }
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(right, x1 + 24f, cy + 14f, paint);

        // Optional 「弹」 quick toggle pill (hidden when source has no danmaku capability)
        if (showDanmakuToggle) {
            int dmBg = danmakuOn
                    ? (danmakuHot ? 0x55F0A957 : 0x33F0A957)
                    : (danmakuHot ? 0x36FFFFFF : 0x1AFFFFFF);
            paint.setColor(dmBg);
            canvas.drawRoundRect(new RectF(DANMAKU_X0, 18f, DANMAKU_X1, TEX_H - 18f), 20f, 20f, paint);
            paint.setTextSize(32f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(danmakuOn ? 0xFFF0A957 : 0x88ECECEE);
            paint.setStrikeThruText(!danmakuOn);
            canvas.drawText(danmakuLabel != null ? danmakuLabel : "",
                    (DANMAKU_X0 + DANMAKU_X1) / 2f, cy + 11f, paint);
            paint.setStrikeThruText(false);
        }

        // 「调整」 pill button opening pinned VR Quick Palette (20 deg below gaze @ 0.5 m)
        int adjBg = paletteOpen
                ? 0xFFF0A957
                : (adjustHot ? 0x3AFFFFFF : 0x22FFFFFF);
        paint.setColor(adjBg);
        canvas.drawRoundRect(new RectF(ADJUST_X0, 18f, ADJUST_X1, TEX_H - 18f), 20f, 20f, paint);
        paint.setTextSize(32f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(paletteOpen ? 0xFF0E0F12 : 0xFFECECEE);
        canvas.drawText(adjustLabel != null ? adjustLabel : "",
                (ADJUST_X0 + ADJUST_X1) / 2f, cy + 11f, paint);

        if (title != null && !playing) {
            paint.setTextSize(26f);
            paint.setColor(0x99FFFFFF);
            paint.setTextAlign(Paint.Align.CENTER);
            String t = title.length() > 70 ? title.substring(0, 67) + "…" : title;
            canvas.drawText(t, (x0 + x1) / 2f, 30f, paint);
        }
        canvas.restore();

        ByteBuffer buf = buffers[next];
        next = (next + 1) % buffers.length;
        buf.rewind();
        bitmap.copyPixelsToBuffer(buf);
        buf.rewind();
        return buf;
    }

    /**
     * Draws an error state strip with the localized reason plus clickable Retry and Back pills so
     * a failed plugin or network stream never leaves the user on a silent black screen.
     */
    ByteBuffer drawError(String reason, String retryLabel, String backLabel,
                         boolean showRetry, boolean retryHot, boolean backHot) {
        canvas.drawColor(0, PorterDuff.Mode.SRC);
        canvas.save();
        canvas.translate(0f, TEX_H);
        canvas.scale(1f, -1f);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE014151A);
        canvas.drawRoundRect(new RectF(0, 0, TEX_W, TEX_H), TEX_H / 2f, TEX_H / 2f, paint);

        float cy = TEX_H / 2f;
        // Alert icon circle
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4.5f);
        paint.setColor(0xFFEE8577);
        canvas.drawCircle(64f, cy, 22f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(61.5f, cy - 12f, 66.5f, cy + 3f), 2f, 2f, paint);
        canvas.drawCircle(64f, cy + 11f, 3f, paint);

        // Reason text
        paint.setTextSize(34f);
        paint.setColor(0xFFECECEE);
        paint.setTextAlign(Paint.Align.LEFT);
        String text = reason != null ? reason : "";
        float maxTextW = (showRetry ? ERR_RETRY_X0 : ERR_BACK_X0) - 128f;
        if (paint.measureText(text) > maxTextW && text.length() > 8) {
            int cut = paint.breakText(text, true, maxTextW - 36f, null);
            text = text.substring(0, Math.max(1, cut)) + "…";
        }
        canvas.drawText(text, 108f, cy + 12f, paint);

        // Retry pill (white primary)
        if (showRetry) {
            paint.setColor(retryHot ? Color.WHITE : 0xFFECECEE);
            canvas.drawRoundRect(new RectF(ERR_RETRY_X0, 18f, ERR_RETRY_X1, TEX_H - 18f), 20f, 20f, paint);
            paint.setColor(0xFF0E0F12);
            paint.setTextSize(32f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(retryLabel != null ? retryLabel : "", (ERR_RETRY_X0 + ERR_RETRY_X1) / 2f, cy + 11f, paint);
        }

        // Back pill (translucent plain)
        paint.setColor(backHot ? 0x47FFFFFF : 0x26FFFFFF);
        canvas.drawRoundRect(new RectF(ERR_BACK_X0, 18f, ERR_BACK_X1, TEX_H - 18f), 20f, 20f, paint);
        paint.setColor(0xFFECECEE);
        paint.setTextSize(32f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(backLabel != null ? backLabel : "", (ERR_BACK_X0 + ERR_BACK_X1) / 2f, cy + 11f, paint);

        canvas.restore();

        ByteBuffer buf = buffers[next];
        next = (next + 1) % buffers.length;
        buf.rewind();
        bitmap.copyPixelsToBuffer(buf);
        buf.rewind();
        return buf;
    }

    static String clock(int ms) {
        int s = Math.max(0, ms) / 1000;
        int h = s / 3600;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
