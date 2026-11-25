package com.limelight.local.danmaku

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Worker-thread-only 2-channel (`GL_RG8`) glyph + outline + soft-shadow rasterizer for danmaku
 * items (ux-spec.md §6.10, §7).
 *
 * - Runs strictly on the dedicated `depth3d-danmaku` worker thread — NEVER on the XR render thread.
 * - Reuses a single scratch [Bitmap], [Canvas], [IntArray], and direct [ByteBuffer] so steady-state
 *   danmaku rasterization allocates ZERO heap memory.
 * - Encodes:
 *   - Channel R = crisp white glyph fill alpha (`Noto Sans CJK SC Bold 700` / `Typeface.DEFAULT_BOLD`)
 *   - Channel G = `0.08 em` `#0E0F12` stroke + `1 px` soft drop shadow alpha
 *   So the GPU fragment shader multiplies arbitrary per-item RGB color & live global opacity in one pass.
 */
class DanmakuRasterizer : DanmakuTextMeasurer {
    private val maxPatchW = 1024
    private val maxPatchH = 92

    private val fillBitmap = Bitmap.createBitmap(maxPatchW, maxPatchH, Bitmap.Config.ARGB_8888)
    private val strokeBitmap = Bitmap.createBitmap(maxPatchW, maxPatchH, Bitmap.Config.ARGB_8888)
    private val fillCanvas = Canvas(fillBitmap)
    private val strokeCanvas = Canvas(strokeBitmap)

    private val fillPixels = IntArray(maxPatchW * maxPatchH)
    private val strokePixels = IntArray(maxPatchW * maxPatchH)
    private val rgBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(maxPatchW * maxPatchH * 2)
        .order(ByteOrder.nativeOrder())

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = Color.argb(175, 255, 255, 255)
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
        val padPx = textPx * 0.24f + 8f
        return (rawW + padPx) / pxPerDeg
    }

    /**
     * Rasterizes [text] into the reusable direct [ByteBuffer] sized `[slot.width x slot.height]`
     * in 2-byte-per-pixel `GL_RG8` format (top-down row order matching shader UV layout).
     */
    fun rasterizeToRgBuffer(text: String, slot: DanmakuAtlasSlot): ByteBuffer {
        val w = slot.width.coerceIn(1, maxPatchW)
        val h = slot.height.coerceIn(1, maxPatchH)

        // Pick font pixel size fitting cleanly inside slot height with stroke & shadow margin
        val textPx = (h * 0.68f).coerceIn(26f, 64f)
        val strokePx = (textPx * 0.16f).coerceIn(3.0f, 9.0f) // 0.08 em radius -> 0.16 em full stroke width

        fillPaint.textSize = textPx
        strokePaint.textSize = textPx
        strokePaint.strokeWidth = strokePx
        shadowPaint.textSize = textPx
        shadowPaint.strokeWidth = strokePx * 0.85f

        // Shrink slightly if measured text is wider than allocated patch width
        val availW = (w - strokePx - 6f).coerceAtLeast(16f)
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
        val drawX = (strokePaint.strokeWidth * 0.5f) + 2.5f

        // Pass 1: Glyph fill alpha (R channel)
        fillCanvas.save()
        fillCanvas.clipRect(0, 0, w, h)
        fillCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        fillCanvas.drawText(text, drawX, baselineY, fillPaint)
        fillCanvas.restore()

        // Pass 2: 0.08 em stroke + 1-texel offset soft drop shadow alpha (G channel)
        strokeCanvas.save()
        strokeCanvas.clipRect(0, 0, w, h)
        strokeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        strokeCanvas.drawText(text, drawX + 1.0f, baselineY + 1.5f, shadowPaint)
        strokeCanvas.drawText(text, drawX, baselineY, strokePaint)
        strokeCanvas.restore()

        fillBitmap.getPixels(fillPixels, 0, w, 0, 0, w, h)
        strokeBitmap.getPixels(strokePixels, 0, w, 0, 0, w, h)

        rgBuffer.clear()
        val total = w * h
        for (i in 0 until total) {
            val fillA = (fillPixels[i] ushr 24) and 0xFF
            val strokeA = (strokePixels[i] ushr 24) and 0xFF
            rgBuffer.put(fillA.toByte())
            rgBuffer.put(strokeA.toByte())
        }
        rgBuffer.flip()
        return rgBuffer
    }

    fun release() {
        if (!fillBitmap.isRecycled) fillBitmap.recycle()
        if (!strokeBitmap.isRecycled) strokeBitmap.recycle()
    }
}
