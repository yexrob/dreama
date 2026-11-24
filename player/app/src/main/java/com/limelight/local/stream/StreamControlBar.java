package com.limelight.local.stream;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.limelight.R;
import com.limelight.binding.video.XrShared;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * In-VR streaming status bar drawn onto XrRenderer's transport quad layer
 * (1536x112 RGBA, rows bottom-up). Follows design-opus/ux-spec.md §6.9:
 * always visible whenever the VR control bar is visible, showing
 * "<PC name> · 串流中", optional gamepad-mode badge, and a red warning pill
 * (#EE8577) whenever latency > 60 ms, packet loss >= 0.5%, or network is poor.
 * All user-facing copy is loaded from Android XML resources.
 */
public final class StreamControlBar {
    public static final int TEX_W = XrShared.TRANSPORT_WIDTH;
    public static final int TEX_H = XrShared.TRANSPORT_HEIGHT;

    public static final int LATENCY_WARN_MS = 60;
    public static final float LOSS_WARN_PCT = 0.5f;

    private static final int BG_COLOR = 0xE614161A;
    private static final int BORDER_COLOR = 0x26FFFFFF;
    private static final int TEXT_PRIMARY = 0xFFECECEE;
    private static final int TEXT_SECONDARY = 0xFFA6A9B0;
    private static final int ACCENT_AMBER = 0xFFE3A857;
    private static final int DANGER_RED = 0xFFEE8577;
    private static final int DANGER_BG = 0x38EE8577;

    private final StringLookup strings;
    private final Bitmap bitmap = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bitmap);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ByteBuffer scratch =
            ByteBuffer.allocateDirect(TEX_W * TEX_H * 4).order(ByteOrder.nativeOrder());

    public StreamControlBar(Context context) {
        this(new AndroidStringLookup(context));
    }

    public StreamControlBar(StringLookup strings) {
        this.strings = strings;
    }

    /** Returns whether the bar should show a network degradation warning. */
    public static boolean shouldWarnNetwork(int rttMs, float packetLossPct, boolean poorConnection) {
        return poorConnection || rttMs > LATENCY_WARN_MS || packetLossPct >= LOSS_WARN_PCT;
    }

    /** Formats the red network warning copy from XML resources, or null when network health is normal. */
    public static String formatNetworkWarning(
            StringLookup strings,
            int rttMs,
            float packetLossPct,
            boolean poorConnection
    ) {
        if (!shouldWarnNetwork(rttMs, packetLossPct, poorConnection)) {
            return null;
        }
        int roundedLoss = Math.round(packetLossPct);
        if (rttMs > 0 && roundedLoss > 0) {
            return strings.get(R.string.vr_stream_net_warn, rttMs, roundedLoss);
        }
        if (rttMs > LATENCY_WARN_MS) {
            return strings.get(R.string.vr_stream_net_warn_rtt, rttMs);
        }
        if (roundedLoss > 0) {
            return strings.get(R.string.vr_stream_net_warn_loss, roundedLoss);
        }
        return strings.get(R.string.vr_stream_net_warn_poor);
    }

    /**
     * Draws one frame of the streaming bar into the reusable scratch RGBA ByteBuffer.
     * Safe to pass directly to {@link com.limelight.binding.video.XrRenderer#postTransport(ByteBuffer)},
     * which copies the pixels into its internal double-buffered staging queue before returning.
     */
    public ByteBuffer draw(
            String pcName,
            int rttMs,
            float packetLossPct,
            boolean poorConnection,
            boolean gamepadMode
    ) {
        canvas.drawColor(0, PorterDuff.Mode.SRC);
        canvas.save();
        // OpenGL / OpenXR transport swapchain rows run bottom-up
        canvas.translate(0f, TEX_H);
        canvas.scale(1f, -1f);

        String warn = formatNetworkWarning(strings, rttMs, packetLossPct, poorConnection);
        boolean hasWarn = warn != null;

        // Main capsule background
        RectF barRect = new RectF(10f, 8f, TEX_W - 10f, TEX_H - 8f);
        float radius = barRect.height() * 0.5f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BG_COLOR);
        canvas.drawRoundRect(barRect, radius, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(hasWarn ? 0x66EE8577 : BORDER_COLOR);
        canvas.drawRoundRect(barRect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        float cy = TEX_H * 0.5f;

        // Live status indicator dot
        paint.setColor(hasWarn ? DANGER_RED : ACCENT_AMBER);
        canvas.drawCircle(58f, cy, 10f, paint);

        // PC name + status
        String host = (pcName != null && !pcName.trim().isEmpty())
                ? pcName.trim()
                : strings.get(R.string.vr_stream_default_pc);
        if (host.length() > 22) {
            host = host.substring(0, 21) + "\u2026";
        }
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(34f);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(TEXT_PRIMARY);
        float baseline = cy - (paint.ascent() + paint.descent()) * 0.5f;
        canvas.drawText(host, 86f, baseline, paint);

        float nameWidth = paint.measureText(host);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(28f);
        paint.setColor(TEXT_SECONDARY);
        float subBaseline = cy - (paint.ascent() + paint.descent()) * 0.5f;
        canvas.drawText(" \u00b7 " + strings.get(R.string.vr_stream_status_live),
                86f + nameWidth + 4f, subBaseline, paint);

        // Right-aligned status / warning pills
        float rightEdge = TEX_W - 36f;

        if (gamepadMode) {
            String padLabel = strings.get(R.string.vr_stream_gamepad_badge);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextSize(26f);
            float padW = paint.measureText(padLabel) + 40f;
            RectF padPill = new RectF(rightEdge - padW, 24f, rightEdge, TEX_H - 24f);
            paint.setColor(0x33E3A857);
            canvas.drawRoundRect(padPill, padPill.height() * 0.5f, padPill.height() * 0.5f, paint);
            paint.setColor(ACCENT_AMBER);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(padLabel, padPill.centerX(),
                    padPill.centerY() - (paint.ascent() + paint.descent()) * 0.5f, paint);
            rightEdge = padPill.left - 18f;
        }

        if (hasWarn) {
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextSize(28f);
            float warnW = paint.measureText(warn) + 48f;
            RectF warnPill = new RectF(rightEdge - warnW, 22f, rightEdge, TEX_H - 22f);
            paint.setColor(DANGER_BG);
            canvas.drawRoundRect(warnPill, warnPill.height() * 0.5f, warnPill.height() * 0.5f, paint);
            paint.setColor(DANGER_RED);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(warn, warnPill.centerX(),
                    warnPill.centerY() - (paint.ascent() + paint.descent()) * 0.5f, paint);
        }

        canvas.restore();

        scratch.rewind();
        bitmap.copyPixelsToBuffer(scratch);
        scratch.rewind();
        return scratch;
    }

    public void release() {
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
