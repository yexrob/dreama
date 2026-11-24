package com.limelight.local;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;

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
    // TRANSPORT_WIDTH and TRANSPORT_HEIGHT in xr_shared.h
    static final int TEX_W = 1536;
    static final int TEX_H = 112;
    private static final float MARGIN = 0.02f;

    // Texture x of each part
    private static final float BTN_END = 120f;
    private static final float TRACK_START = 330f;
    private static final float TRACK_END = TEX_W - 210f;

    private final Bitmap bitmap = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bitmap);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ByteBuffer[] buffers = new ByteBuffer[2];
    private int next;
    private final float aspect;  // video width over height

    TransportBar(int videoWidth, int videoHeight) {
        aspect = (float) videoWidth / Math.max(1, videoHeight);
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = ByteBuffer.allocateDirect(TEX_W * TEX_H * 4).order(ByteOrder.nativeOrder());
        }
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
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
        return x <= BTN_END + 20f;
    }

    /** 0..1 along the track for texture x, or NaN when it is not on the track. */
    static float trackFraction(float x) {
        if (x < TRACK_START - 30f || x > TRACK_END + 30f) {
            return Float.NaN;
        }
        return Math.max(0f, Math.min(1f, (x - TRACK_START) / (TRACK_END - TRACK_START)));
    }

    // ---- drawing ----

    /**
     * Draws the strip and returns the buffer to post. hover is the track
     * fraction under the pointer, or NaN.
     */
    ByteBuffer draw(boolean playing, int posMs, int durMs, float hover, boolean buttonHot,
                    String title) {
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
        paint.setColor(buttonHot ? 0xFF7FD4FF : Color.WHITE);
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
        float x0 = TRACK_START, x1 = TRACK_END, h = 10f;
        paint.setColor(0x50FFFFFF);
        canvas.drawRoundRect(new RectF(x0, cy - h / 2, x1, cy + h / 2), h / 2, h / 2, paint);
        float xp = x0 + (x1 - x0) * frac;
        paint.setColor(0xFF7FD4FF);
        canvas.drawRoundRect(new RectF(x0, cy - h / 2, xp, cy + h / 2), h / 2, h / 2, paint);
        canvas.drawCircle(xp, cy, 16f, paint);

        String right = clock(durMs);
        if (!Float.isNaN(hover)) {
            float xh = x0 + (x1 - x0) * hover;
            paint.setColor(Color.WHITE);
            canvas.drawRect(xh - 2f, cy - 22f, xh + 2f, cy + 22f, paint);
            right = clock((int) (hover * durMs));
            paint.setColor(0xFF7FD4FF);
        } else {
            paint.setColor(0xCCFFFFFF);
        }
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(right, TRACK_END + 28f, cy + 14f, paint);

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

    static String clock(int ms) {
        int s = Math.max(0, ms) / 1000;
        int h = s / 3600;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
