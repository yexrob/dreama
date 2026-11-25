package com.limelight.local.danmaku

import com.limelight.binding.video.XrShared
import com.limelight.local.DanmakuArea
import com.limelight.local.DanmakuBlockFlags
import com.limelight.local.DanmakuSpeed
import com.limelight.local.DepthLayerLevel
import com.limelight.local.GlobalPaletteSettings
import com.limelight.local.StereoSourceFormat
import com.limelight.local.TextSizeTier
import com.limelight.local.plugin.DanmakuMode
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Density presets from ux-spec.md §6.10. */
enum class DanmakuDensity(val maxOnScreen: Int, val tailGapChars: Float) {
    SPARSE(maxOnScreen = 20, tailGapChars = 4.0f),
    MEDIUM(maxOnScreen = 40, tailGapChars = 2.0f),
    DENSE(maxOnScreen = 80, tailGapChars = 1.0f);

    companion object {
        @JvmStatic
        fun fromOrdinal(ord: Int): DanmakuDensity =
            entries.getOrElse(ord) { MEDIUM }
    }
}

/** Curvature mode for danmaku (and G3 subtitle) overlay layers per ux-spec.md §6.12. */
enum class DanmakuCurvatureMode(val nativeCode: Int) {
    FOLLOW_SCREEN(XrShared.OVERLAY_CURVE_FOLLOW),
    FLAT(XrShared.OVERLAY_CURVE_FLAT),
    CUSTOM(XrShared.OVERLAY_CURVE_CUSTOM);

    companion object {
        @JvmStatic
        fun fromOrdinal(ord: Int): DanmakuCurvatureMode =
            entries.getOrElse(ord) { FOLLOW_SCREEN }
    }
}

/** Stereoscopic depth band per ux-spec.md §6.10 (tracks cycle FAR, MID, NEAR, MID). */
enum class DanmakuLayerBand(val nativeCode: Int) {
    FAR(XrShared.DANMAKU_LAYER_FAR),
    MID(XrShared.DANMAKU_LAYER_MID),
    NEAR(XrShared.DANMAKU_LAYER_NEAR);

    companion object {
        private val TRACK_CYCLE = arrayOf(FAR, MID, NEAR, MID)

        @JvmStatic
        fun forScrollTrack(trackIndex: Int): DanmakuLayerBand =
            TRACK_CYCLE[Math.floorMod(trackIndex, TRACK_CYCLE.size)]
    }
}

/** Unified danmaku & depth-float settings consumed by [DanmakuEngine] and native GPU compositor. */
data class DanmakuSettings(
    val on: Boolean = true,
    val area: DanmakuArea = DanmakuArea.HALF,
    val opacityPct: Int = 80,
    val sizeTier: TextSizeTier = TextSizeTier.MEDIUM,
    val speed: DanmakuSpeed = DanmakuSpeed.NORMAL,
    val blockMask: Int = 0,
    val density: DanmakuDensity = DanmakuDensity.MEDIUM,
    val mergeDuplicates: Boolean = true,
    val curvatureMode: DanmakuCurvatureMode = DanmakuCurvatureMode.FOLLOW_SCREEN,
    val customCurvaturePct: Int = 0,
    val floatAuto: Boolean = true,
    val floatAutoOffsetSteps: Int = 0,
    val floatFixedSteps: Int = 0,
    val depthLayer: DepthLayerLevel = DepthLayerLevel.MEDIUM,
) {
    fun fontAngularDeg(): Float = when (sizeTier) {
        TextSizeTier.SMALL -> FONT_DEG_SMALL
        TextSizeTier.MEDIUM -> FONT_DEG_MEDIUM
        TextSizeTier.LARGE -> FONT_DEG_LARGE
    }

    fun transitDurationSec(): Float = when (speed) {
        DanmakuSpeed.SLOW -> 10.0f
        DanmakuSpeed.NORMAL -> 8.0f
        DanmakuSpeed.FAST -> 6.0f
    }

    fun layerSpreadDeg(): Float = when (depthLayer) {
        DepthLayerLevel.OFF -> 0.0f
        DepthLayerLevel.SHALLOW -> 0.15f
        DepthLayerLevel.MEDIUM -> 0.30f
        DepthLayerLevel.DEEP -> 0.45f
    }

    fun isBlocked(mode: DanmakuMode, colorRgb: Int): Boolean {
        if ((blockMask and DanmakuBlockFlags.SCROLL) != 0 && mode == DanmakuMode.SCROLL) return true
        if ((blockMask and DanmakuBlockFlags.TOP) != 0 && mode == DanmakuMode.TOP) return true
        if ((blockMask and DanmakuBlockFlags.BOTTOM) != 0 && mode == DanmakuMode.BOTTOM) return true
        if ((blockMask and DanmakuBlockFlags.COLOR) != 0 && (colorRgb and 0xFFFFFF) != 0xFFFFFF) return true
        return false
    }

    companion object {
        const val FONT_DEG_SMALL = 1.2f
        const val FONT_DEG_MEDIUM = 1.4f
        const val FONT_DEG_LARGE = 1.7f

        @JvmStatic
        fun fromGlobalPalette(g: GlobalPaletteSettings): DanmakuSettings = DanmakuSettings(
            on = g.danmakuOn,
            area = g.danmakuArea,
            opacityPct = g.danmakuOpacityPct.coerceIn(20, 100),
            sizeTier = g.danmakuSize,
            speed = g.danmakuSpeed,
            blockMask = g.danmakuBlockMask,
            density = g.danmakuDensity,
            mergeDuplicates = g.danmakuMergeDuplicates,
            curvatureMode = g.danmakuCurvatureMode,
            customCurvaturePct = g.danmakuCustomCurvaturePct,
            floatAuto = g.floatAuto,
            floatAutoOffsetSteps = g.floatAutoOffsetSteps,
            floatFixedSteps = g.floatFixedSteps,
            depthLayer = g.depthLayer,
        )
    }
}

/** Normalized raw danmaku event ingested from a plugin window or local/WebDAV XML file. */
data class DanmakuRawItem(
    val id: Long,
    val timeMs: Long,
    val text: String,
    val colorRgb: Int = 0xFFFFFF,
    val mode: DanmakuMode = DanmakuMode.SCROLL,
    val repeatCount: Int = 1,
    val windowIndex: Int = -1,
) {
    val displayText: String
        get() = if (repeatCount >= 3) "$text ×$repeatCount" else text
}

/** Monotonic real-time clock abstraction for deterministic virtual-clock tests. */
fun interface DanmakuClock {
    fun nowRealMs(): Long
}

/** Measures angular width of a danmaku string in degrees at [fontDeg]. */
fun interface DanmakuTextMeasurer {
    fun measureWidthDeg(text: String, fontDeg: Float): Float
}

/** Deterministic heuristic measurer for JVM unit tests (CJK ~1.0 em, ASCII ~0.58 em, +padding). */
object HeuristicDanmakuMeasurer : DanmakuTextMeasurer {
    override fun measureWidthDeg(text: String, fontDeg: Float): Float {
        var em = 0.0f
        for (ch in text) {
            em += when {
                ch.code <= 0x7F -> 0.58f
                else -> 1.0f
            }
        }
        return (em + 0.35f) * fontDeg
    }
}

/** Allocated rectangle inside the 2048x2048 2-channel GL_RG8 danmaku atlas. */
data class DanmakuAtlasSlot(
    val slotId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val shelfIndex: Int,
) {
    val u0: Float get() = x.toFloat() / XrShared.DANMAKU_ATLAS_W.toFloat()
    val v0: Float get() = y.toFloat() / XrShared.DANMAKU_ATLAS_H.toFloat()
    val du: Float get() = width.toFloat() / XrShared.DANMAKU_ATLAS_W.toFloat()
    val dv: Float get() = height.toFloat() / XrShared.DANMAKU_ATLAS_H.toFloat()
}

/** Live placed danmaku item currently visible on screen (or stationary preview). */
data class ActiveDanmakuItem(
    val runtimeId: Long,
    val displayText: String,
    val colorRgb: Int,
    val mode: DanmakuMode,
    val trackIndex: Int,
    val layerBand: DanmakuLayerBand,
    var fixedPhiDeg: Float,
    val spawnRealMs: Long,
    val durationRealMs: Long,
    val fadeMs: Long,
    val xStartNorm: Float,
    val velNormPerSec: Float,
    val yNormInArea: Float,
    val wNorm: Float,
    val hNormInArea: Float,
    val widthDeg: Float,
    val isPreview: Boolean = false,
    var atlasSlot: DanmakuAtlasSlot? = null,
    var rasterized: Boolean = false,
) {
    fun xNormAt(nowRealMs: Long): Float {
        val elapsedSec = max(0L, nowRealMs - spawnRealMs) / 1000.0f
        return xStartNorm + velNormPerSec * elapsedSec
    }

    fun tailNormAt(nowRealMs: Long): Float = xNormAt(nowRealMs) + wNorm

    fun isExpiredAt(nowRealMs: Long): Boolean =
        durationRealMs > 0L && (nowRealMs - spawnRealMs) >= durationRealMs
}

/** Computed Far / Mid / Near disparities mirroring native `xr_overlay.c` exactly. */
data class DanmakuDepthBands(
    val farPhiDeg: Float,
    val midPhiDeg: Float,
    val nearPhiDeg: Float,
    val farDistM: Float,
    val midDistM: Float,
    val nearDistM: Float,
    val maxPhiDeg: Float,
) {
    fun phiFor(band: DanmakuLayerBand): Float = when (band) {
        DanmakuLayerBand.FAR -> farPhiDeg
        DanmakuLayerBand.MID -> midPhiDeg
        DanmakuLayerBand.NEAR -> nearPhiDeg
    }
}

/** Pure-Kotlin mirror of `xr_overlay.c` disparity/distance math (kept bit-consistent with C tests). */
object DanmakuDepthMath {
    const val IPD_M = 0.063f
    const val MAX_NEAR_PHI_DEG = 1.0f
    const val MIN_EYE_DIST_M = 1.60f
    const val NATIVE_3D_CONTENT_PHI_DEG = 0.25f
    const val AUTO_MARGIN_PHI_DEG = 0.10f
    const val FLOAT_STEP_DEG = 0.05f

    @JvmStatic
    fun disparityToDistM(phiDeg: Float, screenDistM: Float): Float {
        val d = max(0.25f, screenDistM)
        val phi = max(0.0f, phiDeg)
        val phiRad = (phi * PI / 180.0).toFloat()
        val inv = (phiRad / IPD_M) + (1.0f / d)
        return if (inv > 1e-4f) 1.0f / inv else d
    }

    @JvmStatic
    fun maxPhiAtDistDeg(screenDistM: Float): Float {
        if (screenDistM <= MIN_EYE_DIST_M + 1e-4f) return 0.0f
        val maxRad = IPD_M * ((1.0f / MIN_EYE_DIST_M) - (1.0f / screenDistM))
        val maxDeg = (maxRad * 180.0 / PI).toFloat()
        return maxDeg.coerceIn(0.0f, MAX_NEAR_PHI_DEG)
    }

    @JvmStatic
    fun resolveContentPhi(
        stereoActive: Boolean,
        sourceFormat: StereoSourceFormat,
        measuredDepthPhiDeg: Float,
    ): Float = when {
        sourceFormat == StereoSourceFormat.SBS || sourceFormat == StereoSourceFormat.OU -> NATIVE_3D_CONTENT_PHI_DEG
        !stereoActive -> 0.0f
        else -> measuredDepthPhiDeg.coerceIn(0.0f, MAX_NEAR_PHI_DEG)
    }

    @JvmStatic
    fun computeDepthBands(
        contentPhiDeg: Float,
        settings: DanmakuSettings,
        screenDistM: Float,
    ): DanmakuDepthBands {
        val d = max(0.25f, screenDistM)
        val maxPhi = maxPhiAtDistDeg(d)
        val farRaw = if (settings.floatAuto) {
            val k = settings.floatAutoOffsetSteps.coerceIn(-2, 6)
            max(0.0f, max(0.0f, contentPhiDeg) + AUTO_MARGIN_PHI_DEG + k * FLOAT_STEP_DEG)
        } else {
            val k = settings.floatFixedSteps.coerceIn(0, 12)
            k * FLOAT_STEP_DEG
        }
        var delta = max(0.0f, settings.layerSpreadDeg())
        var far = farRaw
        if (far > maxPhi) {
            far = maxPhi
            delta = 0.0f
        } else if (far + delta > maxPhi) {
            delta = max(0.0f, maxPhi - far)
        }
        val mid = far + delta * 0.5f
        val near = far + delta
        return DanmakuDepthBands(
            farPhiDeg = far,
            midPhiDeg = mid,
            nearPhiDeg = near,
            farDistM = disparityToDistM(far, d),
            midDistM = disparityToDistM(mid, d),
            nearDistM = disparityToDistM(near, d),
            maxPhiDeg = maxPhi,
        )
    }

    /** Parses `"#RRGGBB"` or `"RRGGBB"` into 0x00RRGGBB; falls back to white `0xFFFFFF`. */
    @JvmStatic
    fun parseColorRgb(colorStr: String?): Int {
        if (colorStr.isNullOrBlank()) return 0xFFFFFF
        val clean = colorStr.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (clean.length != 6) return 0xFFFFFF
        return clean.toIntOrNull(16) ?: 0xFFFFFF
    }

    /** Sanitizes text per §6.10: collapses newlines to spaces, trims, caps at 40 chars + `…`. */
    @JvmStatic
    fun sanitizeText(raw: String, maxChars: Int = 40): String {
        val sb = StringBuilder(min(raw.length, maxChars + 2))
        var lastWasSpace = true
        for (ch in raw) {
            val mapped = if (ch == '\n' || ch == '\r' || ch == '\t') ' ' else ch
            if (mapped == ' ') {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(mapped)
                lastWasSpace = false
            }
        }
        val trimmed = sb.toString().trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.length > maxChars) trimmed.substring(0, maxChars) + "…" else trimmed
    }

    /** Truncates sanitized text to fit within `maxWidthDeg` (60% of screen width per §6.10). */
    @JvmStatic
    fun fitToMaxWidthDeg(
        text: String,
        fontDeg: Float,
        maxWidthDeg: Float,
        measurer: DanmakuTextMeasurer,
    ): Pair<String, Float> {
        val fullW = measurer.measureWidthDeg(text, fontDeg)
        if (fullW <= maxWidthDeg || text.length <= 2) {
            return text to min(fullW, maxWidthDeg)
        }
        var lo = 1
        var hi = text.length
        var best = text.substring(0, 1) + "…"
        var bestW = measurer.measureWidthDeg(best, fontDeg)
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cand = text.substring(0, mid) + "…"
            val w = measurer.measureWidthDeg(cand, fontDeg)
            if (w <= maxWidthDeg) {
                best = cand
                bestW = w
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best to bestW
    }

    /** Computes track geometry matching §6.10 (11 / 7 / 3 tracks on 50° 16:9 medium screen). */
    @JvmStatic
    fun computeTrackCount(usableAreaDeg: Float, fontDeg: Float): Int {
        val pitchDeg = fontDeg * 1.3f
        if (usableAreaDeg <= 0.5f || pitchDeg <= 0.1f) return 1
        return max(1, floor(((usableAreaDeg - 0.5f) / pitchDeg).toDouble()).toInt())
    }
}
