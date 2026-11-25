package com.limelight.local.danmaku

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Worker-thread-only premultiplied `GL_RGBA8` glyph + emoji + outline + soft-shadow rasterizer
 * for danmaku items (ux-spec.md §6.10, §7).
 *
 * - Runs strictly on the dedicated `depth3d-danmaku` worker thread — NEVER on the XR render thread.
 * - Uses a single premultiplied RGBA8 Canvas pass so colored text, full-color Android NotoColorEmoji,
 *   `0.08 em` `#0E0F12` stroke, and `1 px` soft drop shadow render together cleanly (zero monochrome
 *   emoji silhouettes, zero dual-bitmap overhead).
 * - Explicitly resolves CJK Bold 700 (`noto-sans-cjk-sc` / `sans-serif` weight 700) rather than
 *   relying on `Typeface.DEFAULT_BOLD`.
 * - Reuses a single scratch [Bitmap], [Canvas], [IntArray], and direct [ByteBuffer] (zero steady-state
 *   heap allocations).
 */
class DanmakuRasterizer : DanmakuTextMeasurer {
    private val maxPatchW = 1024
    private val maxPatchH = 92

    private val bitmap = Bitmap.createBitmap(maxPatchW, maxPatchH, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val pixels = IntArray(maxPatchW * maxPatchH)
    private val rgbaBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(maxPatchW * maxPatchH * 4)
        .order(ByteOrder.nativeOrder())

    private val cjkBoldTypeface: Typeface = resolveCjkBoldTypeface()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = cjkBoldTypeface
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = cjkBoldTypeface
        color = STROKE_COLOR_ARGB
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = cjkBoldTypeface
        color = SHADOW_COLOR_ARGB
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private var currentFovDeg: Float = 50.0f

    fun setScreenFovDeg(fovDeg: Float) {
        currentFovDeg = fovDeg.coerceIn(30.0f, 90.0f)
    }

    override fun measureWidthDeg(text: String, fontDeg: Float): Float {
        val pxPerDeg = com.limelight.binding.video.XrShared.DANMAKU_TEX_W.toFloat() / currentFovDeg
        val textPx = (fontDeg * pxPerDeg).coerceIn(28f, 68f)
        fillPaint.textSize = textPx
        val rawW = fillPaint.measureText(text)
        val padPx = textPx * 0.24f + 10f
        return (rawW + padPx) / pxPerDeg
    }

    /**
     * Rasterizes [text] with [colorRgb] into the reusable direct [ByteBuffer] sized
     * `[slot.width x slot.height]` in premultiplied `GL_RGBA8` format (top-down row order).
     * Clears the entire `[0..w, 0..h)` patch to transparent zero before drawing so reused
     * atlas regions carry zero stale pixels.
     */
    fun rasterizeToRgbaBuffer(text: String, colorRgb: Int, slot: DanmakuAtlasSlot): ByteBuffer {
        val w = slot.width.coerceIn(1, maxPatchW)
        val h = slot.height.coerceIn(1, maxPatchH)

        val textPx = (h * 0.68f).coerceIn(26f, 64f)
        val strokePx = (textPx * 0.16f).coerceIn(3.0f, 9.0f) // 0.08 em radius

        fillPaint.textSize = textPx
        fillPaint.color = (colorRgb and 0xFFFFFF) or 0xFF000000.toInt()
        strokePaint.textSize = textPx
        strokePaint.strokeWidth = strokePx
        shadowPaint.textSize = textPx
        shadowPaint.strokeWidth = strokePx * 0.85f

        val availW = (w - strokePx - 8f).coerceAtLeast(16f)
        val measured = fillPaint.measureText(text)
        if (measured > availW && measured > 1f) {
            val scale = availW / measured
            fillPaint.textSize = textPx * scale
            strokePaint.textSize = textPx * scale
            strokePaint.strokeWidth = strokePx * scale
            shadowPaint.textSize = textPx * scale
            shadowPaint.strokeWidth = strokePx * 0.85f * scale
        }

        val fm = fillPaint.fontMetrics
        val baselineY = (h - (fm.descent - fm.ascent)) * 0.5f - fm.ascent
        val drawX = (strokePaint.strokeWidth * 0.5f) + 3.0f

        canvas.save()
        canvas.clipRect(0, 0, w, h)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        // 1. Soft 1-px offset drop shadow
        canvas.drawText(text, drawX + 1.0f, baselineY + 1.5f, shadowPaint)
        // 2. Crisp 0.08 em dark outline (#0E0F12)
        canvas.drawText(text, drawX, baselineY, strokePaint)
        // 3. Colored text fill (Android NotoColorEmoji automatically draws in full color!)
        canvas.drawText(text, drawX, baselineY, fillPaint)
        canvas.restore()

        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        rgbaBuffer.clear()
        val total = w * h
        for (i in 0 until total) {
            val argb = pixels[i]
            val a = (argb ushr 24) and 0xFF
            if (a == 0) {
                rgbaBuffer.put(0)
                rgbaBuffer.put(0)
                rgbaBuffer.put(0)
                rgbaBuffer.put(0)
            } else if (a == 255) {
                rgbaBuffer.put(((argb ushr 16) and 0xFF).toByte())
                rgbaBuffer.put(((argb ushr 8) and 0xFF).toByte())
                rgbaBuffer.put((argb and 0xFF).toByte())
                rgbaBuffer.put(0xFF.toByte())
            } else {
                // Premultiply RGB by alpha to match glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
                val r = (((argb ushr 16) and 0xFF) * a + 127) / 255
                val g = (((argb ushr 8) and 0xFF) * a + 127) / 255
                val b = ((argb and 0xFF) * a + 127) / 255
                rgbaBuffer.put(r.toByte())
                rgbaBuffer.put(g.toByte())
                rgbaBuffer.put(b.toByte())
                rgbaBuffer.put(a.toByte())
            }
        }
        rgbaBuffer.flip()
        return rgbaBuffer
    }

    fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    companion object {
        private const val STROKE_COLOR_ARGB = 0xFF0E0F12.toInt()
        private const val SHADOW_COLOR_ARGB = 0xAF0E0F12.toInt()

        internal fun resolveCjkBoldTypeface(): Typeface {
            val base = runCatching {
                Typeface.create("noto-sans-cjk-sc", Typeface.BOLD)
            }.getOrNull()
                ?.takeIf { it != Typeface.DEFAULT }
                ?: Typeface.SANS_SERIF
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { Typeface.create(base, 700, false) }
                    .getOrElse { Typeface.create(base, Typeface.BOLD) }
            } else {
                Typeface.create(base, Typeface.BOLD)
            }
        }
    }
}
