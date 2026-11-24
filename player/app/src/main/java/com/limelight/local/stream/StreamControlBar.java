package com.limelight.local.stream;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.limelight.binding.video.XrShared;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * In-VR streaming status bar drawn onto XrRenderer's transport quad layer
 * (1536x112 RGBA, rows bottom-up). Follows design-opus/ux-spec.md §6.9:
 * shows "<PC name> · 串流中" cleanly on connect and lights up a red warning
 * pill (#EE8577) whenever latency > 60 ms, packet loss > 0%, or network is poor.
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
    private static final int PILL_BG = 0x1FFFFFFF;

    private final Bitmap bitmap = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bitmap);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ByteBuffer[] buffers = new ByteBuffer[2];
    private int nextBuffer;

    public StreamControlBar() {
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = ByteBuffer.allocateDirect(TEX_W * TEX_H * 4).order(ByteOrder.nativeOrder());
        }
    }

    /** Returns whether the bar should show a network degradation warning. */
    public static boolean shouldWarnNetwork(int rttMs, float packetLossPct, boolean poorConnection) {
        return poorConnection || rttMs > LATENCY_WARN_MS || packetLossPct >= LOSS_WARN_PCT;
    }

    /** Formats the red network warning copy, or null when network health is normal. */
    public static String formatNetworkWarning(int rttMs, float packetLossPct, boolean poorConnection) {
        if (!shouldWarnNetwork(rttMs, packetLossPct, poorConnection)) {
            return null;
        }
        int roundedLoss = Math.round(packetLossPct);
        if (rttMs > 0 && roundedLoss > 0) {
            return "延迟 " + rttMs + " ms · 丢包 " + roundedLoss + "%";
        }
        if (rttMs > LATENCY_WARN_MS) {
            return "网络延迟偏高 · " + rttMs + " ms";
        }
        if (roundedLoss > 0) {
            return "网络丢包 · " + roundedLoss + "%";
        }
        return "当前网络较差，画面可能卡顿";
    }

    /**
     * Draws one frame of the streaming bar and returns a direct RGBA ByteBuffer ready for
     * {@link com.limelight.binding.video.XrRenderer#postTransport(ByteBuffer)}.
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

        String warn = formatNetworkWarning(rttMs, packetLossPct, poorConnection);
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

        // PC name + "串流中"
        String host = (pcName != null && !pcName.trim().isEmpty()) ? pcName.trim() : "电脑";
        if (host.length() > 22) {
            host = host.substring(0, 21) + "…";
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
        canvas.drawText(" · 串流中", 86f + nameWidth + 4f, subBaseline, paint);

        // Right-aligned status / warning pills
        float rightEdge = TEX_W - 36f;

        if (gamepadMode) {
            String padLabel = "手柄模式";
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
        } else {
            String hint = "下方操作栏：退出 · 键盘 · 手柄 · 调整";
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(25f);
            float hintW = paint.measureText(hint) + 38f;
            RectF hintPill = new RectF(rightEdge - hintW, 24f, rightEdge, TEX_H - 24f);
            paint.setColor(PILL_BG);
            canvas.drawRoundRect(hintPill, hintPill.height() * 0.5f, hintPill.height() * 0.5f, paint);
            paint.setColor(TEXT_SECONDARY);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(hint, hintPill.centerX(),
                    hintPill.centerY() - (paint.ascent() + paint.descent()) * 0.5f, paint);
        }

        canvas.restore();

        ByteBuffer out = buffers[nextBuffer];
        nextBuffer = (nextBuffer + 1) % buffers.length;
        out.rewind();
        bitmap.copyPixelsToBuffer(out);
        out.rewind();
        return out;
    }

    public void release() {
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
