package com.limelight.local

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import com.limelight.binding.video.ScreenReadout
import com.limelight.binding.video.XrShared
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws the 1024x768 VR Quick Palette texture matching `design-opus/mockups/vr-palette.html`
 * and `design-opus/ux-spec.md` §6.3 onto a single preallocated Bitmap + double-buffered
 * DirectByteBuffer (zero per-frame allocations; redrawn ONLY when [PaletteController.renderSignature]
 * changes).
 */
class PaletteRenderer {
    private val width = XrShared.PALETTE_TEX_W
    private val height = XrShared.PALETTE_TEX_H

    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas: Canvas = Canvas(bitmap)
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect: RectF = RectF()
    private val iconPath: Path = Path()
    private val buffers: Array<ByteBuffer> = Array(2) {
        ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    }
    private var nextBuf = 0
    private var hasDrawn = false
    private var lastHashLo = 0L
    private var lastHashHi = 0L
    private val hashScratch = LongArray(2)

    private val regularTypeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    /**
     * Redraws ONLY if [controller.computeRenderHash] differs from the last uploaded sheet.
     * Zero heap allocations when unchanged.
     * Returns the ready-to-upload top-down RGBA [ByteBuffer], or `null` if unchanged.
     */
    fun drawIfChanged(controller: PaletteController): ByteBuffer? {
        controller.computeRenderHash(hashScratch)
        if (hasDrawn && hashScratch[0] == lastHashLo && hashScratch[1] == lastHashHi) return null
        hasDrawn = true
        lastHashLo = hashScratch[0]
        lastHashHi = hashScratch[1]
        return drawNow(controller)
    }

    /** Forces a fresh render on next call (e.g., when session restarts). */
    fun invalidate() {
        hasDrawn = false
    }

    private fun drawNow(c: PaletteController): ByteBuffer {
        canvas.drawColor(0, PorterDuff.Mode.SRC)
        val dimAlpha = if (c.isDimFallback) 102 else 255 // 40% software fallback when colorScale unsupported
        val saveCount = if (dimAlpha < 255) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.saveLayerAlpha(rect, dimAlpha)
        } else {
            canvas.save()
        }

        // Outer rounded card (1024x768, radius 48px)
        paint.style = Paint.Style.FILL
        paint.color = COLOR_BG
        rect.set(1f, 1f, width - 1f, height - 1f)
        canvas.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, paint)

        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 2f
        strokePaint.color = COLOR_BORDER
        canvas.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, strokePaint)

        drawHeader(c)
        drawBody(c)

        canvas.restoreToCount(saveCount)

        val buf = buffers[nextBuf]
        nextBuf = (nextBuf + 1) % buffers.size
        buf.rewind()
        bitmap.copyPixelsToBuffer(buf)
        buf.rewind()
        return buf
    }

    private fun drawHeader(c: PaletteController) {
        val tabLabels = arrayOf(
            c.strings.tab3d,
            c.strings.tabScreen,
            c.strings.tabDanmaku,
            c.strings.tabSubAudio,
        )
        val ty = XrShared.PALETTE_TAB_Y.toFloat()
        val th = XrShared.PALETTE_TAB_H.toFloat()
        for (i in 0 until XrShared.PALETTE_TAB_COUNT) {
            val tx = PaletteController.TAB_X[i]
            val tw = PaletteController.TAB_W[i]
            val active = c.activeTab == i
            val hovered = c.hover is PaletteHover.Tab && (c.hover as PaletteHover.Tab).index == i
            if (active || hovered) {
                paint.style = Paint.Style.FILL
                paint.color = if (active) COLOR_TAB_ACTIVE_BG else COLOR_HOVER_BG
                rect.set(tx, ty, tx + tw, ty + th)
                canvas.drawRoundRect(rect, TAB_RADIUS, TAB_RADIUS, paint)
            }
            textPaint.typeface = if (active) boldTypeface else regularTypeface
            textPaint.textSize = 32f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = when {
                active -> COLOR_TEXT
                hovered -> COLOR_TEXT2
                else -> COLOR_TEXT3
            }
            val cy = ty + th * 0.5f + 11f
            canvas.drawText(tabLabels[i], tx + tw * 0.5f, cy, textPaint)
        }

        // Pin / Unpin (controller) or Close × (hand-tracking) button
        val px = XrShared.PALETTE_PIN_X.toFloat()
        val py = XrShared.PALETTE_PIN_Y.toFloat()
        val ps = XrShared.PALETTE_PIN_SIZE.toFloat()
        val pinHot = c.hover is PaletteHover.Pin
        paint.style = Paint.Style.FILL
        paint.color = when {
            !c.isHandTrackingMode && c.isPinned -> COLOR_AMBER_TINT
            pinHot -> COLOR_CHIP_HOVER_BG
            else -> COLOR_CHIP_BG
        }
        rect.set(px, py, px + ps, py + ps)
        canvas.drawRoundRect(rect, TAB_RADIUS, TAB_RADIUS, paint)

        val cx = px + ps * 0.5f
        val cy = py + ps * 0.5f
        if (c.isHandTrackingMode) {
            drawCloseIcon(cx, cy, if (pinHot) COLOR_TEXT else COLOR_TEXT2)
        } else {
            drawPinIcon(cx, cy, c.isPinned, if (c.isPinned) COLOR_AMBER else if (pinHot) COLOR_TEXT else COLOR_TEXT2)
        }

        // Divider below header (between tab bottom 152 and row0 top 168)
        paint.style = Paint.Style.FILL
        paint.color = COLOR_DIVIDER
        canvas.drawRect(24f, 159f, width - 24f, 161f, paint)
    }

    private fun drawBody(c: PaletteController) {
        when (c.activeTab) {
            XrShared.PALETTE_TAB_3D -> drawTab3d(c)
            XrShared.PALETTE_TAB_SCREEN -> drawTabScreen(c)
            XrShared.PALETTE_TAB_DANMAKU -> drawTabDanmaku(c)
            XrShared.PALETTE_TAB_SUBTITLE -> drawTabSubAudio(c)
        }
    }

    private fun drawTab3d(c: PaletteController) {
        val s = c.strings
        val strengthErr = c.strengthDisabledMessage
        if (strengthErr != null) {
            drawDisabledRow(c, 0, s.row3dStrength, strengthErr)
        } else {
            drawChipsRow(
                c, 0, s.row3dStrength,
                arrayOf(s.strengthOff, s.strengthSoft, s.strengthStandard, s.strengthStrong),
                c.effectiveStrength.ordinal, enabled = true,
            )
        }
        drawChipsRow(
            c, 1, s.row3dSource,
            arrayOf(s.sourceAuto, s.sourceSbs, s.sourceOu),
            c.effectiveSourceFormat.ordinal, enabled = true,
        )
        drawFloatRow(c, 2)
        drawChipsRow(
            c, 3, s.row3dLayer,
            arrayOf(s.layerOff, s.layerShallow, s.layerMedium, s.layerDeep),
            c.global.depthLayer.ordinal, enabled = true,
        )
    }

    private fun drawTabScreen(c: PaletteController) {
        val s = c.strings
        val sizeErr = c.screenSizeDisabledMessage
        if (sizeErr != null) {
            drawDisabledRow(c, 0, s.rowScreenSize, sizeErr)
        } else {
            drawSliderRow(
                c, 0, s.rowScreenSize,
                progress = c.sliderNormalized(XrShared.PALETTE_TAB_SCREEN, 0, 0),
                valueText = c.formatSizeValue(),
                onDetent = PaletteController.isFovDetent(c.global.screenFovDeg),
                ticks = ScreenReadout.SIZE_TICK_POSITIONS,
                enabled = true,
            )
        }
        val distErr = c.screenDistDisabledMessage
        if (distErr != null) {
            drawDisabledRow(c, 1, s.rowScreenDist, distErr)
        } else {
            drawSliderRow(
                c, 1, s.rowScreenDist,
                progress = c.sliderNormalized(XrShared.PALETTE_TAB_SCREEN, 0, 1),
                valueText = c.formatDistValue(),
                onDetent = PaletteController.isDistDetent(c.global.screenDistM),
                ticks = ScreenReadout.DIST_TICK_POSITIONS,
                enabled = true,
            )
        }
        val curveErr = c.curvatureDisabledMessage
        if (curveErr != null) {
            drawDisabledRow(c, 2, s.rowScreenCurve, curveErr)
        } else {
            drawSliderRow(
                c, 2, s.rowScreenCurve,
                progress = c.sliderNormalized(XrShared.PALETTE_TAB_SCREEN, 0, 2),
                valueText = c.formatCurveValue(),
                onDetent = c.global.screenCurvaturePct == 0,
                ticks = null,
                enabled = true,
            )
        }
        drawScreenEnvRowWithHint(
            c,
            arrayOf(s.envPassthrough, s.envDim, s.envTheater, s.envVoid),
            c.global.environment.ordinal,
        )
    }

    /**
     * Draws Row 3 (Environment) slightly compact vertically on the Screen tab so a crisp,
     * unobstructed gesture hint strip sits along the bottom edge (y = 714..760).
     */
    private fun drawScreenEnvRowWithHint(c: PaletteController, options: Array<String>, selected: Int) {
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + 3 * XrShared.PALETTE_ROW_H).toFloat() // 600
        val rw = XrShared.PALETTE_ROW_W.toFloat()
        val rowTop = ry + 6f   // 606
        val rowBot = ry + 106f // 706
        val rowMid = (rowTop + rowBot) * 0.5f // 656
        val hovered = rowHover(c, 3) != null
        if (hovered) {
            paint.style = Paint.Style.FILL
            paint.color = COLOR_ROW_HOVER
            rect.set(rx, rowTop, rx + rw, rowBot)
            canvas.drawRoundRect(rect, ROW_RADIUS, ROW_RADIUS, paint)
        }
        textPaint.typeface = regularTypeface
        textPaint.textSize = 32f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = if (hovered) COLOR_TEXT else COLOR_TEXT2
        canvas.drawText(c.strings.rowScreenEnv, rx + XrShared.PALETTE_LABEL_OFFSET_X, rowMid + 11f, textPaint)

        val startX = rx + XrShared.PALETTE_CTRL_OFFSET_X
        val totalW = XrShared.PALETTE_CTRL_W.toFloat()
        val count = options.size
        val chipW = (totalW - PaletteController.CHIP_GAP * (count - 1)) / count
        val chipH = 76f
        val chipY = rowMid - chipH * 0.5f
        val hoverChip = rowHover(c, 3)?.chipIndex ?: -1
        for (i in 0 until count) {
            val cx = startX + i * (chipW + PaletteController.CHIP_GAP)
            val isSel = i == selected
            val isHot = i == hoverChip
            paint.style = Paint.Style.FILL
            paint.color = when {
                isSel -> COLOR_AMBER
                isHot -> COLOR_CHIP_HOVER_BG
                else -> COLOR_CHIP_BG
            }
            rect.set(cx, chipY, cx + chipW, chipY + chipH)
            canvas.drawRoundRect(rect, CHIP_RADIUS, CHIP_RADIUS, paint)

            textPaint.typeface = if (isSel) boldTypeface else regularTypeface
            textPaint.textSize = 28f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = when {
                isSel -> COLOR_ON_AMBER
                isHot -> COLOR_TEXT
                else -> COLOR_TEXT2
            }
            canvas.drawText(options[i], cx + chipW * 0.5f, chipY + chipH * 0.5f + 10f, textPaint)
        }

        if (c.isRowResetVisible(c.activeTab, c.danmakuPage, 3)) {
            val hitCx = rx + XrShared.PALETTE_RESET_OFFSET_X + XrShared.PALETTE_RESET_SIZE * 0.5f
            val visSize = 76f
            val hot = rowHover(c, 3)?.onReset == true
            paint.style = Paint.Style.FILL
            paint.color = if (hot) COLOR_CHIP_HOVER_BG else COLOR_CHIP_BG
            rect.set(hitCx - visSize * 0.5f, rowMid - visSize * 0.5f, hitCx + visSize * 0.5f, rowMid + visSize * 0.5f)
            canvas.drawRoundRect(rect, 20f, 20f, paint)
            drawResetIcon(hitCx, rowMid, if (hot) COLOR_TEXT else COLOR_TEXT2)
        }

        // Subtle divider + screen manipulation gesture hint line
        paint.style = Paint.Style.FILL
        paint.color = COLOR_DIVIDER
        canvas.drawRect(36f, 712f, width - 36f, 714f, paint)

        textPaint.typeface = regularTypeface
        textPaint.textSize = 26f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = COLOR_TEXT3
        canvas.drawText(ellipsize(c.strings.screenGestureHint, width - 72f), width * 0.5f, 748f, textPaint)
    }

    private fun drawTabDanmaku(c: PaletteController) {
        val s = c.strings
        if (!c.danmakuSupported) {
            drawInfoRow(0, s.danmakuUnsupportedTitle, primary = true)
            drawInfoRow(1, s.danmakuUnsupportedSub, primary = false)
            return
        }
        if (c.danmakuGlUnavailable) {
            drawInfoRow(0, s.danmakuGlUnavailableTitle, primary = true)
            drawInfoRow(1, s.danmakuGlUnavailableSub, primary = false)
            return
        }
        if (c.danmakuLoadFailed) {
            drawDanmakuFailedRow(c)
            return
        }
        val on = c.global.danmakuOn
        if (c.danmakuPage == 0) {
            drawChipsRow(
                c, 0, s.rowDanmakuToggle,
                arrayOf(s.toggleOn, s.toggleOff),
                if (on) 0 else 1, enabled = true,
            )
            drawChipsRow(
                c, 1, s.rowDanmakuArea,
                arrayOf(s.areaFull, s.areaHalf, s.areaQuarter),
                c.global.danmakuArea.ordinal, enabled = on,
            )
            drawSliderRow(
                c, 2, s.rowDanmakuOpacity,
                progress = c.sliderNormalized(XrShared.PALETTE_TAB_DANMAKU, 0, 2),
                valueText = "${c.global.danmakuOpacityPct}%",
                onDetent = false,
                ticks = null,
                enabled = on,
            )
            drawNavRow(c, 3, s.danmakuMore, forward = true, enabled = on)
        } else {
            drawChipsRow(
                c, 0, s.rowDanmakuSize,
                arrayOf(s.sizeSmall, s.sizeMedium, s.sizeLarge),
                c.global.danmakuSize.ordinal, enabled = on,
            )
            drawChipsRow(
                c, 1, s.rowDanmakuSpeed,
                arrayOf(s.speedSlow, s.speedNormal, s.speedFast),
                c.global.danmakuSpeed.ordinal, enabled = on,
            )
            drawBlockChipsRow(
                c, 2, s.rowDanmakuBlock,
                arrayOf(s.blockScroll, s.blockTop, s.blockBottom, s.blockColor),
                c.global.danmakuBlockMask, enabled = on,
            )
            drawNavRow(c, 3, s.danmakuBack, forward = false, enabled = true)
        }
    }

    private fun drawTabSubAudio(c: PaletteController) {
        val s = c.strings
        if (c.subtitleTracks.isEmpty()) {
            drawDisabledRow(c, 0, s.rowSubtitle, s.subtitleNoneReason)
        } else {
            val subLabel = c.selectedSubtitleTrack?.label ?: s.subtitleOff
            drawStepperRow(c, 0, s.rowSubtitle, subLabel)
        }
        if (c.audioTracks.size <= 1) {
            drawDisabledRow(c, 1, s.rowAudio, s.audioSingleReason)
        } else {
            val audLabel = c.selectedAudioTrack?.label.orEmpty()
            drawStepperRow(c, 1, s.rowAudio, audLabel)
        }

        val subStyleEnabled = c.subtitleTracks.isNotEmpty() && c.selectedSubtitleIndex > 0
        if (c.selectedSubtitleIsBitmap) {
            drawSliderRow(
                c, 2, s.rowSubScale,
                progress = c.sliderNormalized(XrShared.PALETTE_TAB_SUBTITLE, 0, 2),
                valueText = "${c.global.subtitleBitmapScalePct}%",
                onDetent = c.global.subtitleBitmapScalePct == PaletteController.DEFAULT_SUB_BITMAP_SCALE,
                ticks = null,
                enabled = subStyleEnabled,
            )
        } else {
            drawChipsRow(
                c, 2, s.rowSubSize,
                arrayOf(s.sizeSmall, s.sizeMedium, s.sizeLarge),
                c.global.subtitleTextSize.ordinal,
                enabled = subStyleEnabled,
            )
        }
        drawSliderRow(
            c, 3, s.rowSubPos,
            progress = c.sliderNormalized(XrShared.PALETTE_TAB_SUBTITLE, 0, 3),
            valueText = c.formatSubPosValue(),
            onDetent = kotlin.math.abs(c.global.subtitlePosDeg - PaletteController.DEFAULT_SUB_POS_DEG) < 0.1f,
            ticks = null,
            enabled = subStyleEnabled,
        )
    }

    // ---- Row primitives ----

    private fun rowHover(c: PaletteController, row: Int): PaletteHover.Row? {
        val h = c.hover
        return if (h is PaletteHover.Row && h.index == row) h else null
    }

    private fun drawRowFrameAndLabel(c: PaletteController, row: Int, label: String, enabled: Boolean) {
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + row * XrShared.PALETTE_ROW_H).toFloat()
        val rw = XrShared.PALETTE_ROW_W.toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        val hovered = rowHover(c, row) != null && enabled
        if (hovered) {
            paint.style = Paint.Style.FILL
            paint.color = COLOR_ROW_HOVER
            rect.set(rx, ry + 10f, rx + rw, ry + rh - 10f)
            canvas.drawRoundRect(rect, ROW_RADIUS, ROW_RADIUS, paint)
        }
        textPaint.typeface = regularTypeface
        textPaint.textSize = 32f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = if (enabled) (if (hovered) COLOR_TEXT else COLOR_TEXT2) else COLOR_DISABLED
        canvas.drawText(label, rx + XrShared.PALETTE_LABEL_OFFSET_X, ry + rh * 0.5f + 11f, textPaint)
    }

    private fun drawResetButton(c: PaletteController, row: Int) {
        if (!c.isRowResetVisible(c.activeTab, c.danmakuPage, row)) return
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + row * XrShared.PALETTE_ROW_H).toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        val hitCx = rx + XrShared.PALETTE_RESET_OFFSET_X + XrShared.PALETTE_RESET_SIZE * 0.5f
        val hitCy = ry + rh * 0.5f
        val visSize = 80f
        val hot = rowHover(c, row)?.onReset == true

        paint.style = Paint.Style.FILL
        paint.color = if (hot) COLOR_CHIP_HOVER_BG else COLOR_CHIP_BG
        rect.set(hitCx - visSize * 0.5f, hitCy - visSize * 0.5f, hitCx + visSize * 0.5f, hitCy + visSize * 0.5f)
        canvas.drawRoundRect(rect, 20f, 20f, paint)
        drawResetIcon(hitCx, hitCy, if (hot) COLOR_TEXT else COLOR_TEXT2)
    }

    private fun drawChipsRow(
        c: PaletteController,
        row: Int,
        label: String,
        options: Array<String>,
        selected: Int,
        enabled: Boolean,
    ) {
        drawRowFrameAndLabel(c, row, label, enabled)
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + row * XrShared.PALETTE_ROW_H).toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        val startX = rx + XrShared.PALETTE_CTRL_OFFSET_X
        val totalW = XrShared.PALETTE_CTRL_W.toFloat()
        val count = options.size
        val chipW = (totalW - PaletteController.CHIP_GAP * (count - 1)) / count
        val chipH = 80f
        val chipY = ry + (rh - chipH) * 0.5f
        val hoverChip = if (enabled) rowHover(c, row)?.chipIndex ?: -1 else -1

        for (i in 0 until count) {
            val cx = startX + i * (chipW + PaletteController.CHIP_GAP)
            val isSel = i == selected
            val isHot = i == hoverChip
            paint.style = Paint.Style.FILL
            paint.color = when {
                !enabled && isSel -> COLOR_CHIP_DISABLED_ACTIVE
                !enabled -> COLOR_CHIP_DISABLED_BG
                isSel -> COLOR_AMBER
                isHot -> COLOR_CHIP_HOVER_BG
                else -> COLOR_CHIP_BG
            }
            rect.set(cx, chipY, cx + chipW, chipY + chipH)
            canvas.drawRoundRect(rect, CHIP_RADIUS, CHIP_RADIUS, paint)

            textPaint.typeface = if (isSel && enabled) boldTypeface else regularTypeface
            textPaint.textSize = 28f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = when {
                !enabled -> COLOR_DISABLED
                isSel -> COLOR_ON_AMBER
                isHot -> COLOR_TEXT
                else -> COLOR_TEXT2
            }
            canvas.drawText(options[i], cx + chipW * 0.5f, chipY + chipH * 0.5f + 10f, textPaint)
        }
        drawResetButton(c, row)
    }

    private fun drawBlockChipsRow(
        c: PaletteController,
        row: Int,
        label: String,
        options: Array<String>,
        blockMask: Int,
        enabled: Boolean,
    ) {
        drawRowFrameAndLabel(c, row, label, enabled)
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + row * XrShared.PALETTE_ROW_H).toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        val startX = rx + XrShared.PALETTE_CTRL_OFFSET_X
        val totalW = XrShared.PALETTE_CTRL_W.toFloat()
        val count = options.size
        val chipW = (totalW - PaletteController.CHIP_GAP * (count - 1)) / count
        val chipH = 80f
        val chipY = ry + (rh - chipH) * 0.5f
        val hoverChip = if (enabled) rowHover(c, row)?.chipIndex ?: -1 else -1

        for (i in 0 until count) {
            val cx = startX + i * (chipW + PaletteController.CHIP_GAP)
            val blocked = (blockMask and (1 shl i)) != 0
            val isHot = i == hoverChip
            paint.style = Paint.Style.FILL
            paint.color = when {
                !enabled -> COLOR_CHIP_DISABLED_BG
                blocked -> COLOR_DANGER_BG
                isHot -> COLOR_CHIP_HOVER_BG
                else -> COLOR_CHIP_BG
            }
            rect.set(cx, chipY, cx + chipW, chipY + chipH)
            canvas.drawRoundRect(rect, CHIP_RADIUS, CHIP_RADIUS, paint)

            textPaint.typeface = regularTypeface
            textPaint.textSize = 28f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.isStrikeThruText = blocked
            textPaint.color = when {
                !enabled -> COLOR_DISABLED
                blocked -> COLOR_DANGER
                else -> COLOR_TEXT
            }
            canvas.drawText(options[i], cx + chipW * 0.5f, chipY + chipH * 0.5f + 10f, textPaint)
            textPaint.isStrikeThruText = false
        }
        drawResetButton(c, row)
    }

    private fun drawSliderRow(
        c: PaletteController,
        row: Int,
        label: String,
        progress: Float,
        valueText: String,
        onDetent: Boolean,
        ticks: FloatArray?,
        enabled: Boolean,
    ) {
        drawRowFrameAndLabel(c, row, label, enabled)
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + row * XrShared.PALETTE_ROW_H).toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        val cy = ry + rh * 0.5f
        val trackX = rx + XrShared.PALETTE_SLIDER_OFFSET_X
        val trackW = XrShared.PALETTE_SLIDER_W.toFloat()
        val hovered = enabled && rowHover(c, row) != null

        drawSliderTrack(trackX, cy, trackW, progress.coerceIn(0f, 1f), ticks, enabled, hovered)

        textPaint.typeface = if (onDetent && enabled) boldTypeface else regularTypeface
        textPaint.textSize = 30f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = when {
            !enabled -> COLOR_DISABLED
            onDetent -> COLOR_AMBER
            else -> COLOR_TEXT
        }
        val display = if (hovered) "‹ $valueText ›" else valueText
        canvas.drawText(display, rx + XrShared.PALETTE_CTRL_OFFSET_X, cy + 11f, textPaint)
        drawResetButton(c, row)
    }

    private fun drawFloatRow(c: PaletteController, row: Int) {
        drawRowFrameAndLabel(c, row, c.strings.row3dFloat, enabled = true)
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + row * XrShared.PALETTE_ROW_H).toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        val cy = ry + rh * 0.5f
        val autoX = rx + XrShared.PALETTE_CTRL_OFFSET_X
        val autoW = XrShared.PALETTE_CHIP_W.toFloat()
        val chipH = 80f
        val chipY = ry + (rh - chipH) * 0.5f
        val hoverRow = rowHover(c, row)
        val autoActive = c.global.floatAuto
        val autoHot = hoverRow?.onFloatAutoChip == true

        paint.style = Paint.Style.FILL
        paint.color = when {
            autoActive -> COLOR_AMBER
            autoHot -> COLOR_CHIP_HOVER_BG
            else -> COLOR_CHIP_BG
        }
        rect.set(autoX, chipY, autoX + autoW, chipY + chipH)
        canvas.drawRoundRect(rect, CHIP_RADIUS, CHIP_RADIUS, paint)

        textPaint.typeface = if (autoActive) boldTypeface else regularTypeface
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = if (autoActive) COLOR_ON_AMBER else COLOR_TEXT2
        canvas.drawText(c.strings.floatAuto, autoX + autoW * 0.5f, chipY + chipH * 0.5f + 10f, textPaint)

        val trackX = rx + XrShared.PALETTE_FLOAT_SLIDER_OFFSET_X
        val trackW = XrShared.PALETTE_FLOAT_SLIDER_W.toFloat()
        val progress = c.sliderNormalized(XrShared.PALETTE_TAB_3D, 0, row)
        val autoTicks = if (autoActive) floatArrayOf(0.25f) else null // 0 offset detent in -2..+6
        drawSliderTrack(trackX, cy, trackW, progress, autoTicks, enabled = true, hovered = hoverRow != null)

        textPaint.typeface = regularTypeface
        textPaint.textSize = 30f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = COLOR_TEXT
        canvas.drawText(c.formatFloatValue(), rx + XrShared.PALETTE_FLOAT_VAL_OFFSET_X, cy + 11f, textPaint)
        drawResetButton(c, row)
    }

    private fun drawSliderTrack(
        x: Float,
        cy: Float,
        w: Float,
        progress: Float,
        ticks: FloatArray?,
        enabled: Boolean,
        hovered: Boolean,
    ) {
        val trackH = 10f
        paint.style = Paint.Style.FILL
        paint.color = if (enabled) COLOR_TRACK_BG else COLOR_CHIP_DISABLED_BG
        rect.set(x, cy - trackH * 0.5f, x + w, cy + trackH * 0.5f)
        canvas.drawRoundRect(rect, trackH * 0.5f, trackH * 0.5f, paint)

        val fillW = w * progress
        if (fillW > 2f) {
            paint.color = if (enabled) COLOR_AMBER else COLOR_DISABLED
            rect.set(x, cy - trackH * 0.5f, x + fillW, cy + trackH * 0.5f)
            canvas.drawRoundRect(rect, trackH * 0.5f, trackH * 0.5f, paint)
        }

        if (ticks != null && enabled) {
            for (t in ticks) {
                val tx = x + w * t
                val near = kotlin.math.abs(t - progress) < 0.025f
                paint.color = if (near) COLOR_AMBER else COLOR_TICK
                canvas.drawRect(tx - 1.5f, cy - 12f, tx + 1.5f, cy + 12f, paint)
            }
        }

        val thumbX = x + fillW
        val thumbR = if (hovered) 19f else 16f
        paint.color = if (enabled) COLOR_TEXT else COLOR_DISABLED
        canvas.drawCircle(thumbX, cy, thumbR, paint)
    }

    private fun drawStepperRow(c: PaletteController, row: Int, label: String, valueLabel: String) {
        drawRowFrameAndLabel(c, row, label, enabled = true)
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + row * XrShared.PALETTE_ROW_H).toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        val sx = rx + XrShared.PALETTE_CTRL_OFFSET_X
        val sw = XrShared.PALETTE_CTRL_W.toFloat()
        val sh = 80f
        val sy = ry + (rh - sh) * 0.5f
        val hoverDir = rowHover(c, row)?.stepperDir ?: 0

        paint.style = Paint.Style.FILL
        paint.color = COLOR_CHIP_BG
        rect.set(sx, sy, sx + sw, sy + sh)
        canvas.drawRoundRect(rect, CHIP_RADIUS, CHIP_RADIUS, paint)

        // Left / right arrow hover fills
        if (hoverDir < 0) {
            paint.color = COLOR_CHIP_HOVER_BG
            rect.set(sx, sy, sx + PaletteController.STEPPER_BTN_W, sy + sh)
            canvas.drawRoundRect(rect, CHIP_RADIUS, CHIP_RADIUS, paint)
        } else if (hoverDir > 0) {
            paint.color = COLOR_CHIP_HOVER_BG
            rect.set(sx + sw - PaletteController.STEPPER_BTN_W, sy, sx + sw, sy + sh)
            canvas.drawRoundRect(rect, CHIP_RADIUS, CHIP_RADIUS, paint)
        }

        val cy = sy + sh * 0.5f + 10f
        textPaint.typeface = boldTypeface
        textPaint.textSize = 36f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = if (hoverDir < 0) COLOR_TEXT else COLOR_TEXT2
        canvas.drawText("‹", sx + PaletteController.STEPPER_BTN_W * 0.5f, cy, textPaint)
        textPaint.color = if (hoverDir > 0) COLOR_TEXT else COLOR_TEXT2
        canvas.drawText("›", sx + sw - PaletteController.STEPPER_BTN_W * 0.5f, cy, textPaint)

        // Center ellipsized label
        textPaint.typeface = regularTypeface
        textPaint.textSize = 28f
        textPaint.color = COLOR_TEXT
        val maxCenterW = sw - 2f * PaletteController.STEPPER_BTN_W - 24f
        val shown = ellipsize(valueLabel, maxCenterW)
        canvas.drawText(shown, sx + sw * 0.5f, sy + sh * 0.5f + 10f, textPaint)
        drawResetButton(c, row)
    }

    private fun drawDisabledRow(c: PaletteController, row: Int, label: String, reason: String) {
        drawRowFrameAndLabel(c, row, label, enabled = false)
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + row * XrShared.PALETTE_ROW_H).toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        textPaint.typeface = regularTypeface
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = COLOR_TEXT3
        canvas.drawText(reason, rx + XrShared.PALETTE_CTRL_OFFSET_X, ry + rh * 0.5f + 10f, textPaint)
    }

    private fun drawNavRow(c: PaletteController, row: Int, text: String, forward: Boolean, enabled: Boolean) {
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + row * XrShared.PALETTE_ROW_H).toFloat()
        val rw = XrShared.PALETTE_ROW_W.toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        val hot = enabled && rowHover(c, row) != null
        val pillH = 88f
        val pillY = ry + (rh - pillH) * 0.5f

        paint.style = Paint.Style.FILL
        paint.color = when {
            !enabled -> COLOR_CHIP_DISABLED_BG
            hot -> COLOR_CHIP_HOVER_BG
            else -> COLOR_CHIP_BG
        }
        rect.set(rx + 12f, pillY, rx + rw - 12f, pillY + pillH)
        canvas.drawRoundRect(rect, ROW_RADIUS, ROW_RADIUS, paint)

        textPaint.typeface = regularTypeface
        textPaint.textSize = 30f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = if (enabled) (if (hot) COLOR_TEXT else COLOR_TEXT2) else COLOR_DISABLED
        val display = if (forward) "$text  ›" else "‹  $text"
        canvas.drawText(display, rx + 40f, pillY + pillH * 0.5f + 10f, textPaint)
    }

    private fun drawInfoRow(row: Int, text: String, primary: Boolean) {
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = (XrShared.PALETTE_ROW_Y0 + row * XrShared.PALETTE_ROW_H).toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        textPaint.typeface = if (primary) boldTypeface else regularTypeface
        textPaint.textSize = if (primary) 32f else 28f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = if (primary) COLOR_TEXT else COLOR_TEXT3
        canvas.drawText(text, rx + 24f, ry + rh * 0.5f + 10f, textPaint)
    }

    private fun drawDanmakuFailedRow(c: PaletteController) {
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val ry = XrShared.PALETTE_ROW_Y0.toFloat()
        val rh = XrShared.PALETTE_ROW_H.toFloat()
        textPaint.typeface = boldTypeface
        textPaint.textSize = 32f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = COLOR_DANGER
        canvas.drawText(c.strings.danmakuFailedTitle, rx + 24f, ry + rh * 0.5f + 11f, textPaint)

        val bx = rx + PaletteController.RETRY_BTN_LOCAL_X
        val bw = PaletteController.RETRY_BTN_W
        val bh = 76f
        val by = ry + (rh - bh) * 0.5f
        val hot = rowHover(c, 0)?.onRetry == true
        paint.style = Paint.Style.FILL
        paint.color = if (hot) COLOR_TEXT else COLOR_AMBER
        rect.set(bx, by, bx + bw, by + bh)
        canvas.drawRoundRect(rect, CHIP_RADIUS, CHIP_RADIUS, paint)

        textPaint.typeface = boldTypeface
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = COLOR_ON_AMBER
        canvas.drawText(c.strings.actionRetry, bx + bw * 0.5f, by + bh * 0.5f + 10f, textPaint)
    }

    // ---- Crisp vector icons ----

    private fun drawPinIcon(cx: Float, cy: Float, filled: Boolean, color: Int) {
        iconPath.reset()
        // Tilted push-pin silhouette (matching vr-palette.html)
        iconPath.moveTo(cx - 10f, cy - 14f)
        iconPath.lineTo(cx + 10f, cy - 14f)
        iconPath.lineTo(cx + 7f, cy + 2f)
        iconPath.lineTo(cx + 15f, cy + 7f)
        iconPath.lineTo(cx - 15f, cy + 7f)
        iconPath.lineTo(cx - 7f, cy + 2f)
        iconPath.close()
        if (filled) {
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawPath(iconPath, paint)
        } else {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = 3f
            strokePaint.color = color
            canvas.drawPath(iconPath, strokePaint)
        }
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 3.5f
        strokePaint.color = color
        canvas.drawLine(cx, cy + 7f, cx, cy + 19f, strokePaint)
    }

    private fun drawCloseIcon(cx: Float, cy: Float, color: Int) {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 4f
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = color
        canvas.drawLine(cx - 13f, cy - 13f, cx + 13f, cy + 13f, strokePaint)
        canvas.drawLine(cx + 13f, cy - 13f, cx - 13f, cy + 13f, strokePaint)
    }

    private fun drawResetIcon(cx: Float, cy: Float, color: Int) {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 3.2f
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = color
        val r = 13f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, -135f, 290f, false, strokePaint)
        // Arrowhead at top-left end of arc
        iconPath.reset()
        iconPath.moveTo(cx - 13f, cy - 15f)
        iconPath.lineTo(cx - 13f, cy - 5f)
        iconPath.lineTo(cx - 3f, cy - 5f)
        canvas.drawPath(iconPath, strokePaint)
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        val count = textPaint.breakText(text, true, maxWidth - 28f, null)
        return if (count > 0 && count < text.length) text.substring(0, count) + "…" else text
    }

    companion object {
        private const val CARD_RADIUS = 48f
        private const val TAB_RADIUS = 22f
        private const val ROW_RADIUS = 26f
        private const val CHIP_RADIUS = 18f

        // Design tokens from design-opus/ux-spec.md §5.1 & vr-palette.html
        private const val COLOR_BG = 0xF0121317.toInt()
        private const val COLOR_BORDER = 0x1AFFFFFF
        private const val COLOR_DIVIDER = 0x14FFFFFF
        private const val COLOR_TAB_ACTIVE_BG = 0x1CFFFFFF
        private const val COLOR_HOVER_BG = 0x10FFFFFF
        private const val COLOR_ROW_HOVER = 0x0CFFFFFF
        private const val COLOR_CHIP_BG = 0x10FFFFFF
        private const val COLOR_CHIP_HOVER_BG = 0x22FFFFFF
        private const val COLOR_CHIP_DISABLED_BG = 0x08FFFFFF
        private const val COLOR_CHIP_DISABLED_ACTIVE = 0x16FFFFFF
        private const val COLOR_TRACK_BG = 0x24FFFFFF
        private const val COLOR_TICK = 0x48FFFFFF
        private const val COLOR_TEXT = 0xFFECECEE.toInt()
        private const val COLOR_TEXT2 = 0xFFA6A9B0.toInt()
        private const val COLOR_TEXT3 = 0xFF7D818A.toInt()
        private const val COLOR_DISABLED = 0xFF50535A.toInt()
        private const val COLOR_AMBER = 0xFFF0A957.toInt()
        private const val COLOR_AMBER_TINT = 0x2EF0A957
        private const val COLOR_ON_AMBER = 0xFF0E0F12.toInt()
        private const val COLOR_DANGER = 0xFFEE8577.toInt()
        private const val COLOR_DANGER_BG = 0x2EEE8577
    }
}
