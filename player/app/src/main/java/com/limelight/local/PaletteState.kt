package com.limelight.local

import com.limelight.binding.video.DepthPresets
import com.limelight.binding.video.ScreenReadout
import com.limelight.binding.video.XrShared
import com.limelight.local.stream.DepthStrength
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** Native stereo input layout (matches XrShared.STEREO_SRC_*). */
enum class StereoSourceFormat(val nativeCode: Int) {
    AUTO(XrShared.STEREO_SRC_AUTO),
    SBS(XrShared.STEREO_SRC_SBS),
    OU(XrShared.STEREO_SRC_OU);

    companion object {
        fun fromCode(code: Int): StereoSourceFormat =
            entries.firstOrNull { it.nativeCode == code } ?: AUTO
    }
}

/** 3D local depth detail level (Tab 0 Row 3 「层次」). */
enum class DepthLayerLevel(val depthScale: Int) {
    OFF(100),
    SHALLOW(130),
    MEDIUM(160),
    DEEP(200);

    companion object {
        fun fromOrdinal(ord: Int): DepthLayerLevel =
            entries.getOrElse(ord) { MEDIUM }
    }
}

/** Viewing environment options in Palette Tab 1 Row 3 (ux-spec §6.3). */
enum class PaletteEnvironment {
    PASSTHROUGH,
    DIM,
    THEATER,
    VOID;

    companion object {
        fun fromOrdinal(ord: Int): PaletteEnvironment =
            entries.getOrElse(ord) { PASSTHROUGH }
    }
}

/** Danmaku display region (Tab 2 Page 1 Row 1). */
enum class DanmakuArea {
    FULL,
    HALF,
    QUARTER;

    companion object {
        fun fromOrdinal(ord: Int): DanmakuArea = entries.getOrElse(ord) { HALF }
    }
}

/** Danmaku & text subtitle size tiers. */
enum class TextSizeTier {
    SMALL,
    MEDIUM,
    LARGE;

    companion object {
        fun fromOrdinal(ord: Int): TextSizeTier = entries.getOrElse(ord) { MEDIUM }
    }
}

/** Danmaku scrolling speed tiers. */
enum class DanmakuSpeed {
    SLOW,
    NORMAL,
    FAST;

    companion object {
        fun fromOrdinal(ord: Int): DanmakuSpeed = entries.getOrElse(ord) { NORMAL }
    }
}

/** Bitmask flags for blocked danmaku categories (Tab 2 Page 2 Row 2). */
object DanmakuBlockFlags {
    const val SCROLL = 1 shl 0
    const val TOP = 1 shl 1
    const val BOTTOM = 1 shl 2
    const val COLOR = 1 shl 3
    const val ALL_MASK = SCROLL or TOP or BOTTOM or COLOR
}

/** Describes one available subtitle track in the current video. */
data class PaletteSubtitleTrack(
    val id: String,
    val label: String,
    val isBitmap: Boolean = false,
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
)

/** Describes one available audio track in the current video. */
data class PaletteAudioTrack(
    val id: String,
    val label: String,
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
)

/** Localized strings injected from Android resources (or stubbed cleanly in JVM tests). */
data class PaletteStrings(
    val tab3d: String = "3D",
    val tabScreen: String = "屏幕",
    val tabDanmaku: String = "弹幕",
    val tabSubAudio: String = "字幕音轨",
    val row3dStrength: String = "强度",
    val row3dSource: String = "片源",
    val row3dFloat: String = "漂浮感",
    val row3dLayer: String = "层次",
    val strengthOff: String = "关闭",
    val strengthSoft: String = "柔和",
    val strengthStandard: String = "标准",
    val strengthStrong: String = "强烈",
    val sourceAuto: String = "自动",
    val sourceSbs: String = "左右",
    val sourceOu: String = "上下",
    val floatAuto: String = "自动",
    val floatFlat: String = "贴屏",
    val layerOff: String = "关",
    val layerShallow: String = "浅",
    val layerMedium: String = "中",
    val layerDeep: String = "深",
    val rowScreenSize: String = "大小",
    val rowScreenDist: String = "距离",
    val rowScreenCurve: String = "曲度",
    val rowScreenEnv: String = "环境",
    val sizeSmall: String = "小",
    val sizeMedium: String = "中",
    val sizeLarge: String = "大",
    val sizeGiant: String = "巨幕",
    val curveFlat: String = "平面",
    val curveDisabledInRoom: String = "影院房间里屏幕是平的",
    val curveUnsupportedHeadset: String = "这台头显不支持曲面",
    val envPassthrough: String = "透视",
    val envDim: String = "调暗",
    val envTheater: String = "影院",
    val envVoid: String = "纯黑",
    val rowDanmakuToggle: String = "弹幕",
    val rowDanmakuArea: String = "区域",
    val rowDanmakuOpacity: String = "不透明",
    val danmakuMore: String = "更多：字号 · 速度 · 屏蔽",
    val danmakuBack: String = "返回",
    val toggleOn: String = "开",
    val toggleOff: String = "关",
    val areaFull: String = "全屏",
    val areaHalf: String = "半屏",
    val areaQuarter: String = "1/4",
    val rowDanmakuSize: String = "字号",
    val rowDanmakuSpeed: String = "速度",
    val rowDanmakuBlock: String = "屏蔽",
    val speedSlow: String = "慢",
    val speedNormal: String = "标准",
    val speedFast: String = "快",
    val blockScroll: String = "滚动",
    val blockTop: String = "顶部",
    val blockBottom: String = "底部",
    val blockColor: String = "彩色",
    val danmakuUnsupportedTitle: String = "这个片源不提供弹幕",
    val danmakuUnsupportedSub: String = "插件声明了弹幕能力才有",
    val danmakuFailedTitle: String = "弹幕没加载出来",
    val actionRetry: String = "重试",
    val rowSubtitle: String = "字幕",
    val rowAudio: String = "音轨",
    val rowSubSize: String = "字号",
    val rowSubScale: String = "缩放",
    val rowSubPos: String = "位置",
    val subtitleOff: String = "关闭",
    val subtitleNoneReason: String = "这部片子没有字幕",
    val audioSingleReason: String = "只有一条音轨",
    val toastVideoOnly: String = "只改这部片子",
    val toastNoDanmaku: String = "这个片源没有弹幕",
    val toastDanmakuOn: String = "弹幕 已开",
    val toastDanmakuOff: String = "弹幕 已关",
    val screenSizeDisabledInRoom: String = "影院房间里画面固定在银幕上",
    val screenDistDisabledInRoom: String = "影院房间里距离固定",
    val screenGestureHint: String = "指着画面按住扳机，再推右摇杆：前后调远近 · 左右调大小",
    val depthModelUnavailableReason: String = "深度模型不可用，仅支持原生 3D",
)

/** Per-video persisted overrides (only the 4 per-video items per §6.3). */
data class VideoPaletteOverrides(
    val strength: DepthStrength? = null,
    val sourceFormat: StereoSourceFormat? = null,
    val subtitleId: String? = null,
    val audioId: String? = null,
    val strengthNoticeShown: Boolean = false,
)

/** Global persisted palette settings (shared across videos & synced with 2D Settings). */
data class GlobalPaletteSettings(
    val defaultStrength: DepthStrength = DepthStrength.STANDARD,
    val floatAuto: Boolean = true,
    val floatAutoOffsetSteps: Int = 0, // -2..+6 -> -0.10°..+0.30° in 0.05° steps
    val floatFixedSteps: Int = 0,      // 0..12 -> 0.00°..0.60° in 0.05° steps
    val depthLayer: DepthLayerLevel = DepthLayerLevel.MEDIUM,
    val screenFovDeg: Float = 50f,
    val screenDistM: Float = 3.0f,
    val screenCurvaturePct: Int = 0,
    val environment: PaletteEnvironment = PaletteEnvironment.PASSTHROUGH,
    val danmakuOn: Boolean = true,
    val danmakuArea: DanmakuArea = DanmakuArea.HALF,
    val danmakuOpacityPct: Int = 80,
    val danmakuSize: TextSizeTier = TextSizeTier.MEDIUM,
    val danmakuSpeed: DanmakuSpeed = DanmakuSpeed.NORMAL,
    val danmakuBlockMask: Int = 0,
    val subtitleTextSize: TextSizeTier = TextSizeTier.MEDIUM,
    val subtitleBitmapScalePct: Int = 100,
    val subtitlePosDeg: Float = 2.0f,
    val paletteOnRightHand: Boolean = false,
)

/** Hover target inside the 1024x768 palette coordinate space. */
sealed class PaletteHover {
    data object None : PaletteHover()
    data class Tab(val index: Int) : PaletteHover()
    data object Pin : PaletteHover()
    data class Row(
        val index: Int,
        val chipIndex: Int = -1,
        val onReset: Boolean = false,
        val onSlider: Boolean = false,
        val onFloatAutoChip: Boolean = false,
        val stepperDir: Int = 0,
        val onRetry: Boolean = false,
    ) : PaletteHover()
}

/** Side effects produced by a single palette interaction step. */
data class PaletteActionResult(
    val changed: Boolean = false,
    val stereoChanged: Boolean = false,
    val sourceFormatChanged: Boolean = false,
    val screenChanged: Boolean = false,
    val environmentChanged: Boolean = false,
    val danmakuChanged: Boolean = false,
    val danmakuRetryRequested: Boolean = false,
    val subtitleTrackChanged: Boolean = false,
    val audioTrackChanged: Boolean = false,
    val subtitleStyleChanged: Boolean = false,
    val hapticKind: Int = XrShared.PALETTE_HAPTIC_NONE,
    val clickSound: Boolean = false,
    val toastMessage: String? = null,
    val saveVideoOverrides: Boolean = false,
    val saveGlobalSettings: Boolean = false,
)

/**
 * Pure Kotlin state machine & 1024x768 layout hit-tester for the VR Quick Palette
 * (ux-spec.md §6.3-§6.4 & mockups/vr-palette.html). Zero Android graphics deps so every
 * tab, row, detent, reset, disabled state, per-video isolation rule, and pixel hitbox is
 * deterministically verified under JVM unit tests.
 */
class PaletteController(
    initialGlobal: GlobalPaletteSettings = GlobalPaletteSettings(),
    initialVideo: VideoPaletteOverrides = VideoPaletteOverrides(),
    val strings: PaletteStrings = PaletteStrings(),
    var cylinderSupported: Boolean = true,
    var danmakuSupported: Boolean = false,
    var danmakuLoadFailed: Boolean = false,
    var depthModelUnavailable: Boolean = false,
) {
    var global: GlobalPaletteSettings = initialGlobal
        private set
    var video: VideoPaletteOverrides = initialVideo
        private set

    var activeTab: Int = XrShared.PALETTE_TAB_3D
        private set
    var danmakuPage: Int = 0
        private set
    var isOpen: Boolean = false
        private set
    var isPinned: Boolean = false
        private set
    var isHandTrackingMode: Boolean = false
        private set
    var isDimFallback: Boolean = false
        private set
    var hover: PaletteHover = PaletteHover.None
        private set

    var subtitleTracks: List<PaletteSubtitleTrack> = emptyList()
        private set
    var audioTracks: List<PaletteAudioTrack> = emptyList()
        private set
    var defaultSourceFormat: StereoSourceFormat = StereoSourceFormat.AUTO
        private set
    var defaultSubtitleId: String? = null
        private set
    var defaultAudioId: String? = null
        private set
    private var subtitleDefaultCaptured: Boolean = false
    private var audioDefaultCaptured: Boolean = false

    /** Effective 3D strength (OFF when AI depth model failed in AUTO mode so UI matches actual flat output). */
    val effectiveStrength: DepthStrength
        get() = if (depthModelUnavailable && effectiveSourceFormat == StereoSourceFormat.AUTO) {
            DepthStrength.OFF
        } else {
            video.strength ?: global.defaultStrength
        }

    /** Why 3D strength row is disabled right now, or null if adjustable. */
    val strengthDisabledMessage: String?
        get() = if (depthModelUnavailable && effectiveSourceFormat == StereoSourceFormat.AUTO) {
            strings.depthModelUnavailableReason
        } else {
            null
        }

    /** Effective stereo source format (explicit per-video override, else plugin/container hint, else AUTO). */
    val effectiveSourceFormat: StereoSourceFormat
        get() = video.sourceFormat ?: defaultSourceFormat

    /** Selected subtitle index: 0 = OFF, 1..N = subtitleTracks[i - 1]. */
    val selectedSubtitleIndex: Int
        get() {
            if (subtitleTracks.isEmpty()) return 0
            val wanted = video.subtitleId ?: defaultSubtitleId ?: return 0
            if (wanted == SUBTITLE_OFF_ID) return 0
            val idx = subtitleTracks.indexOfFirst { it.id == wanted }
            if (idx >= 0) return idx + 1
            val normalizedWanted = PaletteTrackManager.normalizeLegacyTrackId(wanted)
            if (normalizedWanted != wanted) {
                val nIdx = subtitleTracks.indexOfFirst { it.id == normalizedWanted }
                if (nIdx >= 0) return nIdx + 1
            }
            return 0
        }

    val selectedSubtitleTrack: PaletteSubtitleTrack?
        get() {
            val idx = selectedSubtitleIndex
            return if (idx in 1..subtitleTracks.size) subtitleTracks[idx - 1] else null
        }

    val selectedSubtitleIsBitmap: Boolean
        get() = selectedSubtitleTrack?.isBitmap == true

    /** Selected audio index: 0..N-1 in audioTracks (or -1 if empty). */
    val selectedAudioIndex: Int
        get() {
            if (audioTracks.isEmpty()) return -1
            val wanted = video.audioId ?: defaultAudioId
            if (wanted != null) {
                val idx = audioTracks.indexOfFirst { it.id == wanted }
                if (idx >= 0) return idx
                val normalizedWanted = PaletteTrackManager.normalizeLegacyTrackId(wanted)
                if (normalizedWanted != wanted) {
                    val nIdx = audioTracks.indexOfFirst { it.id == normalizedWanted }
                    if (nIdx >= 0) return nIdx
                }
            }
            return 0
        }

    val selectedAudioTrack: PaletteAudioTrack?
        get() = audioTracks.getOrNull(selectedAudioIndex)

    /** Why screen size is disabled right now (3D theater room fixes screen to wall), or null if enabled. */
    val screenSizeDisabledMessage: String?
        get() = if (global.environment == PaletteEnvironment.THEATER) strings.screenSizeDisabledInRoom else null

    /** Why screen distance is disabled right now (3D theater room fixes screen to wall), or null if enabled. */
    val screenDistDisabledMessage: String?
        get() = if (global.environment == PaletteEnvironment.THEATER) strings.screenDistDisabledInRoom else null

    /** Why curvature is disabled right now, or null if enabled. */
    val curvatureDisabledMessage: String?
        get() = when {
            global.environment == PaletteEnvironment.THEATER -> strings.curveDisabledInRoom
            !cylinderSupported -> strings.curveUnsupportedHeadset
            else -> null
        }

    /**
     * Updates available ExoPlayer subtitle & audio tracks.
     * Captures the natural default track ID ONCE per media item (the first non-empty track list
     * observed before any user override is applied), so subsequent `onTracksChanged` callbacks
     * triggered by switching tracks NEVER overwrite the default or hide the reset button.
     */
    @JvmOverloads
    fun setTracks(
        subtitles: List<PaletteSubtitleTrack>,
        audios: List<PaletteAudioTrack>,
        defaultSubId: String? = subtitles.firstOrNull()?.id,
        defaultAudId: String? = audios.firstOrNull()?.id,
        forceResetDefaults: Boolean = false,
    ) {
        if (forceResetDefaults) {
            subtitleDefaultCaptured = false
            audioDefaultCaptured = false
            defaultSubtitleId = null
            defaultAudioId = null
        }
        subtitleTracks = subtitles
        audioTracks = audios
        if (!subtitleDefaultCaptured && subtitles.isNotEmpty()) {
            defaultSubtitleId = defaultSubId ?: SUBTITLE_OFF_ID
            subtitleDefaultCaptured = true
        }
        if (!audioDefaultCaptured && audios.isNotEmpty()) {
            defaultAudioId = defaultAudId ?: audios.first().id
            audioDefaultCaptured = true
        }
    }

    /**
     * Records a plugin/container stereo hint as this video's default source format WITHOUT
     * writing it into [video] overrides, so explicit user selection of AUTO persists and
     * row reset restores the plugin hint.
     */
    fun seedDefaultSourceFormat(format: StereoSourceFormat) {
        defaultSourceFormat = format
    }

    /** Syncs screen size & distance saved externally by G1 trigger+stick adjustment. */
    fun syncExternalScreen(fovDeg: Float, distM: Float) {
        global = global.copy(
            screenFovDeg = ScreenReadout.clampFov(fovDeg),
            screenDistM = ScreenReadout.clampDist(distM),
        )
    }

    /** Syncs palette native open/pinned/hand/dim-fallback state from IN_PALETTE_* slots. */
    fun syncNativeVisualState(
        open: Boolean,
        pinned: Boolean,
        handTrackingMode: Boolean,
        dimFallback: Boolean,
    ) {
        isOpen = open
        isPinned = pinned
        isHandTrackingMode = handTrackingMode
        isDimFallback = dimFallback
    }

    /** Computes native 3D warp separation (fraction of frame width), convergence (0..1), and depthLocal. */
    fun computeStereoParams(depthModel: String?): StereoRenderParams {
        val defaultSepUnits = com.limelight.preferences.PreferenceConfiguration.defaultSeparation(depthModel)
        val defaultConvPct = com.limelight.preferences.PreferenceConfiguration.defaultConvergence(depthModel)
        val strength = effectiveStrength
        val srcFmt = effectiveSourceFormat
        val on = if (srcFmt != StereoSourceFormat.AUTO) {
            // Native SBS/OU never relies on the depth model; stays stereo even if depthGaveUp
            true
        } else {
            !depthModelUnavailable && strength != DepthStrength.OFF
        }
        val sepUnits = when (strength) {
            DepthStrength.OFF -> 0
            DepthStrength.SOFT -> DepthPresets.value(DepthPresets.COMFORT, defaultSepUnits)
            DepthStrength.STANDARD -> DepthPresets.value(DepthPresets.BALANCED, defaultSepUnits)
            DepthStrength.STRONG -> DepthPresets.value(DepthPresets.STRONG, defaultSepUnits)
        }
        val effectiveFloatDeg = if (global.floatAuto) {
            0.15f + global.floatAutoOffsetSteps * FLOAT_STEP_DEG
        } else {
            global.floatFixedSteps * FLOAT_STEP_DEG
        }
        // Higher pop-out shifts convergence lower (pulls foreground forward out of screen)
        val convPct = (defaultConvPct - (effectiveFloatDeg - 0.15f) * 45f).roundToInt().coerceIn(10, 90)
        return StereoRenderParams(
            on = on,
            separation = sepUnits / 1000f,
            convergence = convPct / 100f,
            depthLocal = global.depthLayer.depthScale / 100f,
            sourceFormat = effectiveSourceFormat,
        )
    }

    /** Formats the float pop-out readout (`自动`, `+0.10°`, `贴屏`, or `N cm`). */
    fun formatFloatValue(): String {
        if (global.floatAuto) {
            val steps = global.floatAutoOffsetSteps
            if (steps == 0) return strings.floatAuto
            val deg = steps * FLOAT_STEP_DEG
            return String.format(Locale.US, "%+.2f°", deg)
        }
        if (global.floatFixedSteps <= 0) return strings.floatFlat
        val deg = global.floatFixedSteps * FLOAT_STEP_DEG
        val cm = popOutCm(deg, global.screenDistM)
        return if (cm <= 0) strings.floatFlat else "$cm cm"
    }

    /** Formats the screen size readout (`62° 大` on detent, `56°` otherwise). */
    fun formatSizeValue(): String {
        val fovInt = global.screenFovDeg.roundToInt()
        val presetIdx = ScreenReadout.sizePresetIndex(global.screenFovDeg)
        val labels = arrayOf(strings.sizeSmall, strings.sizeMedium, strings.sizeLarge, strings.sizeGiant)
        val preset = if (presetIdx in labels.indices) labels[presetIdx] else null
        return if (preset != null) "${fovInt}° $preset" else "${fovInt}°"
    }

    /** Formats distance readout (`3.0 m`). */
    fun formatDistValue(): String = String.format(Locale.US, "%.1f m", global.screenDistM)

    /** Formats curvature readout (`平面` or `45%`). */
    fun formatCurveValue(): String =
        if (global.screenCurvaturePct <= 0) strings.curveFlat else "${global.screenCurvaturePct}%"

    /** Formats subtitle vertical position (`2°` or `2.5°`). */
    fun formatSubPosValue(): String {
        val tenths = (global.subtitlePosDeg * 10f).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}°" else String.format(Locale.US, "%.1f°", tenths / 10f)
    }

    /** Bitmask of rows 0..3 on the active tab/page that accept stick-X / stick-click reset. */
    fun activeRowMask(): Int {
        var mask = 0
        for (r in 0 until XrShared.PALETTE_MAX_ROWS) {
            if (isRowAdjustable(activeTab, danmakuPage, r)) {
                mask = mask or (1 shl r)
            }
        }
        return mask
    }

    fun isRowAdjustable(tab: Int, page: Int, row: Int): Boolean = when (tab) {
        XrShared.PALETTE_TAB_3D -> when (row) {
            0 -> strengthDisabledMessage == null
            1, 2, 3 -> true
            else -> false
        }
        XrShared.PALETTE_TAB_SCREEN -> when (row) {
            0 -> screenSizeDisabledMessage == null
            1 -> screenDistDisabledMessage == null
            2 -> curvatureDisabledMessage == null
            3 -> true
            else -> false
        }
        XrShared.PALETTE_TAB_DANMAKU -> when {
            !danmakuSupported || danmakuLoadFailed -> false
            page == 0 -> when (row) {
                0 -> true
                1, 2 -> global.danmakuOn
                else -> false // Row 3 is "更多" navigation link
            }
            else -> when (row) {
                0, 1, 2 -> global.danmakuOn
                else -> false // Row 3 is "返回" navigation link
            }
        }
        XrShared.PALETTE_TAB_SUBTITLE -> when (row) {
            0 -> subtitleTracks.isNotEmpty()
            1 -> audioTracks.size > 1
            2, 3 -> subtitleTracks.isNotEmpty() && selectedSubtitleIndex > 0
            else -> false
        }
        else -> false
    }

    /** Whether the reset button (.rs) should be visible on this row (§6.3 strict rule). */
    fun isRowResetVisible(tab: Int, page: Int, row: Int): Boolean = when (tab) {
        XrShared.PALETTE_TAB_3D -> when (row) {
            0 -> strengthDisabledMessage == null && effectiveStrength != global.defaultStrength
            1 -> effectiveSourceFormat != defaultSourceFormat
            2 -> !global.floatAuto || global.floatAutoOffsetSteps != 0
            3 -> global.depthLayer != DepthLayerLevel.MEDIUM
            else -> false
        }
        XrShared.PALETTE_TAB_SCREEN -> when (row) {
            0 -> screenSizeDisabledMessage == null && abs(global.screenFovDeg - DEFAULT_FOV_DEG) > 0.4f
            1 -> screenDistDisabledMessage == null && abs(global.screenDistM - DEFAULT_DIST_M) > 0.04f
            2 -> curvatureDisabledMessage == null && global.screenCurvaturePct != 0
            3 -> global.environment != PaletteEnvironment.PASSTHROUGH
            else -> false
        }
        XrShared.PALETTE_TAB_DANMAKU -> when {
            !danmakuSupported || danmakuLoadFailed -> false
            page == 0 -> when (row) {
                0 -> !global.danmakuOn
                1 -> global.danmakuOn && global.danmakuArea != DanmakuArea.HALF
                2 -> global.danmakuOn && global.danmakuOpacityPct != DEFAULT_DANMAKU_OPACITY
                else -> false
            }
            else -> when (row) {
                0 -> global.danmakuOn && global.danmakuSize != TextSizeTier.MEDIUM
                1 -> global.danmakuOn && global.danmakuSpeed != DanmakuSpeed.NORMAL
                2 -> global.danmakuOn && global.danmakuBlockMask != 0
                else -> false
            }
        }
        XrShared.PALETTE_TAB_SUBTITLE -> when (row) {
            0 -> subtitleTracks.isNotEmpty()
                && (selectedSubtitleTrack?.id ?: SUBTITLE_OFF_ID) != (defaultSubtitleId ?: SUBTITLE_OFF_ID)
            1 -> audioTracks.size > 1 && selectedAudioTrack?.id != defaultAudioId
            2 -> subtitleTracks.isNotEmpty() && selectedSubtitleIndex > 0 && (
                if (selectedSubtitleIsBitmap) global.subtitleBitmapScalePct != DEFAULT_SUB_BITMAP_SCALE
                else global.subtitleTextSize != TextSizeTier.MEDIUM
            )
            3 -> subtitleTracks.isNotEmpty() && selectedSubtitleIndex > 0
                && abs(global.subtitlePosDeg - DEFAULT_SUB_POS_DEG) > 0.1f
            else -> false
        }
        else -> false
    }

    /** Switches tab directly; leaving Danmaku tab always resets its secondary page to 0 (§6.3). */
    fun selectTab(tabIndex: Int): PaletteActionResult {
        val clamped = tabIndex.coerceIn(0, XrShared.PALETTE_TAB_COUNT - 1)
        if (clamped == activeTab) return PaletteActionResult()
        if (activeTab == XrShared.PALETTE_TAB_DANMAKU && clamped != XrShared.PALETTE_TAB_DANMAKU) {
            danmakuPage = 0
        }
        activeTab = clamped
        return PaletteActionResult(
            changed = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
        )
    }

    /** Left-stick X tab switch (clamped at ends, double-pulse limit haptic at ends). */
    fun stepTab(dir: Int): PaletteActionResult {
        if (dir == 0) return PaletteActionResult()
        val next = (activeTab + dir).coerceIn(0, XrShared.PALETTE_TAB_COUNT - 1)
        if (next == activeTab) {
            return PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
        }
        return selectTab(next)
    }

    /** Left-stick Y secondary-page switch on Danmaku tab (dir < 0 = down to page 1, dir > 0 = up to page 0). */
    fun stepPage(dir: Int): PaletteActionResult {
        if (dir == 0 || activeTab != XrShared.PALETTE_TAB_DANMAKU
            || !danmakuSupported || danmakuLoadFailed || !global.danmakuOn
        ) {
            return PaletteActionResult()
        }
        // Stick pushed down (negative Y) moves to Page 2 ("更多"); pushed up (positive Y) returns to Page 1
        val target = if (dir < 0) 1 else 0
        if (target == danmakuPage) {
            return PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
        }
        danmakuPage = target
        return PaletteActionResult(
            changed = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
        )
    }

    /** Toggles danmaku globally via Y button or TransportBar 「弹」 button. */
    fun toggleDanmakuQuick(): PaletteActionResult {
        if (!danmakuSupported) {
            return PaletteActionResult(
                toastMessage = strings.toastNoDanmaku,
                hapticKind = XrShared.PALETTE_HAPTIC_LIMIT,
            )
        }
        val next = !global.danmakuOn
        global = global.copy(danmakuOn = next)
        if (!next) {
            danmakuPage = 0
        }
        return PaletteActionResult(
            changed = true,
            danmakuChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            toastMessage = if (next) strings.toastDanmakuOn else strings.toastDanmakuOff,
            saveGlobalSettings = true,
        )
    }

    /** Maps a 1024x768 pixel point to a semantic [PaletteHover] matching [PaletteRenderer]. */
    fun hitTest(px: Float, py: Float): PaletteHover {
        if (px < 0f || py < 0f || px > XrShared.PALETTE_TEX_W || py > XrShared.PALETTE_TEX_H) {
            return PaletteHover.None
        }
        // Tabs
        if (py in XrShared.PALETTE_TAB_Y.toFloat()..(XrShared.PALETTE_TAB_Y + XrShared.PALETTE_TAB_H).toFloat()) {
            for (t in 0 until XrShared.PALETTE_TAB_COUNT) {
                val tx = TAB_X[t]
                val tw = TAB_W[t]
                if (px >= tx && px <= tx + tw) return PaletteHover.Tab(t)
            }
            if (px >= XrShared.PALETTE_PIN_X && px <= XrShared.PALETTE_PIN_X + XrShared.PALETTE_PIN_SIZE) {
                return PaletteHover.Pin
            }
            return PaletteHover.None
        }
        // Rows 0..3
        val rx = XrShared.PALETTE_ROW_X.toFloat()
        val rw = XrShared.PALETTE_ROW_W.toFloat()
        if (px < rx || px > rx + rw) return PaletteHover.None
        val relY = py - XrShared.PALETTE_ROW_Y0
        if (relY < 0f) return PaletteHover.None
        val row = (relY / XrShared.PALETTE_ROW_H).toInt()
        if (row !in 0 until XrShared.PALETTE_MAX_ROWS) return PaletteHover.None

        val localX = px - rx
        val resetVisible = isRowResetVisible(activeTab, danmakuPage, row)
        if (resetVisible && localX >= XrShared.PALETTE_RESET_OFFSET_X
            && localX <= XrShared.PALETTE_RESET_OFFSET_X + XrShared.PALETTE_RESET_SIZE
        ) {
            return PaletteHover.Row(index = row, onReset = true)
        }

        // Danmaku load-failed Retry button on Row 0
        if (activeTab == XrShared.PALETTE_TAB_DANMAKU && danmakuSupported && danmakuLoadFailed && row == 0) {
            val onRetry = localX >= RETRY_BTN_LOCAL_X && localX <= RETRY_BTN_LOCAL_X + RETRY_BTN_W
            return PaletteHover.Row(index = row, onRetry = onRetry)
        }

        // Special nav rows (More / Back)
        if (activeTab == XrShared.PALETTE_TAB_DANMAKU && danmakuSupported && !danmakuLoadFailed && row == 3) {
            return PaletteHover.Row(index = row)
        }

        if (!isRowAdjustable(activeTab, danmakuPage, row)) {
            return PaletteHover.Row(index = row)
        }

        return when (rowKind(activeTab, danmakuPage, row)) {
            RowControlKind.CHIPS -> {
                val count = chipCount(activeTab, danmakuPage, row)
                val chipIdx = hitChipIndex(localX, count)
                PaletteHover.Row(index = row, chipIndex = chipIdx)
            }
            RowControlKind.SLIDER -> {
                val onSlider = localX >= XrShared.PALETTE_SLIDER_OFFSET_X - 12f
                    && localX <= XrShared.PALETTE_SLIDER_OFFSET_X + XrShared.PALETTE_SLIDER_W + 12f
                PaletteHover.Row(index = row, onSlider = onSlider)
            }
            RowControlKind.FLOAT_SLIDER -> {
                val onAuto = localX >= XrShared.PALETTE_CTRL_OFFSET_X
                    && localX <= XrShared.PALETTE_CTRL_OFFSET_X + XrShared.PALETTE_CHIP_W
                val onSlider = localX >= XrShared.PALETTE_FLOAT_SLIDER_OFFSET_X - 12f
                    && localX <= XrShared.PALETTE_FLOAT_SLIDER_OFFSET_X + XrShared.PALETTE_FLOAT_SLIDER_W + 12f
                PaletteHover.Row(index = row, onFloatAutoChip = onAuto, onSlider = onSlider)
            }
            RowControlKind.STEPPER -> {
                val ctrlL = XrShared.PALETTE_CTRL_OFFSET_X.toFloat()
                val ctrlR = ctrlL + XrShared.PALETTE_CTRL_W
                val dir = when {
                    localX in ctrlL..(ctrlL + STEPPER_BTN_W) -> -1
                    localX in (ctrlR - STEPPER_BTN_W)..ctrlR -> 1
                    else -> 0
                }
                PaletteHover.Row(index = row, stepperDir = dir)
            }
            RowControlKind.NONE -> PaletteHover.Row(index = row)
        }
    }

    /** Updates hover state and handles one frame of pointer/stick/button palette input. */
    fun processFrameInput(
        open: Boolean,
        pinned: Boolean,
        handTrackingMode: Boolean,
        dimFallback: Boolean,
        hoverX: Float,
        hoverY: Float,
        clickState: Int,
        rowStep: Int,
        rowReset: Boolean,
        tabStep: Int,
        pageStep: Int,
    ): PaletteActionResult {
        syncNativeVisualState(open, pinned, handTrackingMode, dimFallback)
        if (!open) {
            hover = PaletteHover.None
            return PaletteActionResult()
        }

        hover = hitTest(hoverX, hoverY)
        var acc = PaletteActionResult()

        if (tabStep != 0) {
            acc = merge(acc, stepTab(tabStep))
        }
        if (pageStep != 0) {
            acc = merge(acc, stepPage(pageStep))
        }

        val curHover = hover
        if (clickState == 1) {
            acc = merge(acc, handleClick(curHover, hoverX))
        } else if (clickState == 2 && curHover is PaletteHover.Row) {
            // Continuous slider drag while controller trigger is held
            acc = merge(acc, handleSliderDrag(curHover.index, hoverX))
        }

        if (rowStep != 0 && curHover is PaletteHover.Row && isRowAdjustable(activeTab, danmakuPage, curHover.index)) {
            acc = merge(acc, stepRow(curHover.index, rowStep))
        }
        if (rowReset && curHover is PaletteHover.Row && isRowResetVisible(activeTab, danmakuPage, curHover.index)) {
            acc = merge(acc, resetRow(curHover.index))
        }

        return acc
    }

    private fun handleClick(target: PaletteHover, px: Float): PaletteActionResult = when (target) {
        is PaletteHover.Tab -> selectTab(target.index)
        is PaletteHover.Row -> {
            val row = target.index
            if (target.onReset) {
                resetRow(row)
            } else if (target.onRetry) {
                danmakuLoadFailed = false
                PaletteActionResult(
                    changed = true,
                    danmakuRetryRequested = true,
                    hapticKind = XrShared.PALETTE_HAPTIC_TICK,
                    clickSound = true,
                )
            } else if (activeTab == XrShared.PALETTE_TAB_DANMAKU && danmakuSupported
                && !danmakuLoadFailed && global.danmakuOn && row == 3
            ) {
                danmakuPage = if (danmakuPage == 0) 1 else 0
                PaletteActionResult(
                    changed = true,
                    hapticKind = XrShared.PALETTE_HAPTIC_TICK,
                    clickSound = true,
                )
            } else if (!isRowAdjustable(activeTab, danmakuPage, row)) {
                PaletteActionResult()
            } else {
                when (rowKind(activeTab, danmakuPage, row)) {
                    RowControlKind.CHIPS -> {
                        if (target.chipIndex >= 0) selectChip(row, target.chipIndex)
                        else PaletteActionResult()
                    }
                    RowControlKind.FLOAT_SLIDER -> {
                        if (target.onFloatAutoChip) toggleFloatAuto()
                        else if (target.onSlider) handleSliderDrag(row, px)
                        else PaletteActionResult()
                    }
                    RowControlKind.SLIDER -> {
                        if (target.onSlider) handleSliderDrag(row, px)
                        else PaletteActionResult()
                    }
                    RowControlKind.STEPPER -> {
                        val dir = if (target.stepperDir != 0) target.stepperDir else 1
                        stepRow(row, dir)
                    }
                    RowControlKind.NONE -> PaletteActionResult()
                }
            }
        }
        else -> PaletteActionResult()
    }

    fun selectChip(row: Int, chipIdx: Int): PaletteActionResult {
        if (!isRowAdjustable(activeTab, danmakuPage, row)) return PaletteActionResult()
        return when (activeTab) {
            XrShared.PALETTE_TAB_3D -> when (row) {
                0 -> setStrength(DepthStrength.entries.getOrElse(chipIdx) { DepthStrength.STANDARD })
                1 -> setSourceFormat(StereoSourceFormat.entries.getOrElse(chipIdx) { StereoSourceFormat.AUTO })
                3 -> setDepthLayer(DepthLayerLevel.fromOrdinal(chipIdx))
                else -> PaletteActionResult()
            }
            XrShared.PALETTE_TAB_SCREEN -> if (row == 3) {
                setEnvironment(PaletteEnvironment.fromOrdinal(chipIdx))
            } else {
                PaletteActionResult()
            }
            XrShared.PALETTE_TAB_DANMAKU -> if (danmakuPage == 0) {
                when (row) {
                    0 -> setDanmakuOn(chipIdx == 0)
                    1 -> setDanmakuArea(DanmakuArea.fromOrdinal(chipIdx))
                    else -> PaletteActionResult()
                }
            } else {
                when (row) {
                    0 -> setDanmakuSize(TextSizeTier.fromOrdinal(chipIdx))
                    1 -> setDanmakuSpeed(DanmakuSpeed.fromOrdinal(chipIdx))
                    2 -> toggleDanmakuBlock(chipIdx)
                    else -> PaletteActionResult()
                }
            }
            XrShared.PALETTE_TAB_SUBTITLE -> if (row == 2 && !selectedSubtitleIsBitmap) {
                setSubtitleTextSize(TextSizeTier.fromOrdinal(chipIdx))
            } else {
                PaletteActionResult()
            }
            else -> PaletteActionResult()
        }
    }

    /** Steps a row value by `steps` discrete increments (right stick X or 8 mm hand-tracking drag). */
    fun stepRow(row: Int, steps: Int): PaletteActionResult {
        if (steps == 0 || !isRowAdjustable(activeTab, danmakuPage, row)) return PaletteActionResult()
        return when (activeTab) {
            XrShared.PALETTE_TAB_3D -> when (row) {
                0 -> {
                    val cur = effectiveStrength.ordinal
                    val next = (cur + steps).coerceIn(0, DepthStrength.entries.lastIndex)
                    if (next == cur) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
                    else setStrength(DepthStrength.entries[next])
                }
                1 -> {
                    val cur = effectiveSourceFormat.ordinal
                    val next = (cur + steps).coerceIn(0, StereoSourceFormat.entries.lastIndex)
                    if (next == cur) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
                    else setSourceFormat(StereoSourceFormat.entries[next])
                }
                2 -> stepFloatSlider(steps)
                3 -> {
                    val cur = global.depthLayer.ordinal
                    val next = (cur + steps).coerceIn(0, DepthLayerLevel.entries.lastIndex)
                    if (next == cur) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
                    else setDepthLayer(DepthLayerLevel.entries[next])
                }
                else -> PaletteActionResult()
            }
            XrShared.PALETTE_TAB_SCREEN -> when (row) {
                0 -> stepScreenFov(steps)
                1 -> stepScreenDist(steps)
                2 -> stepScreenCurvature(steps)
                3 -> {
                    val cur = global.environment.ordinal
                    val next = (cur + steps).coerceIn(0, PaletteEnvironment.entries.lastIndex)
                    if (next == cur) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
                    else setEnvironment(PaletteEnvironment.entries[next])
                }
                else -> PaletteActionResult()
            }
            XrShared.PALETTE_TAB_DANMAKU -> if (danmakuPage == 0) {
                when (row) {
                    0 -> {
                        val wantOn = steps < 0 // Left chip is 开 (0), Right chip is 关 (1)
                        if (wantOn == global.danmakuOn) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
                        else setDanmakuOn(wantOn)
                    }
                    1 -> {
                        val cur = global.danmakuArea.ordinal
                        val next = (cur + steps).coerceIn(0, DanmakuArea.entries.lastIndex)
                        if (next == cur) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
                        else setDanmakuArea(DanmakuArea.entries[next])
                    }
                    2 -> stepDanmakuOpacity(steps)
                    else -> PaletteActionResult()
                }
            } else {
                when (row) {
                    0 -> {
                        val cur = global.danmakuSize.ordinal
                        val next = (cur + steps).coerceIn(0, TextSizeTier.entries.lastIndex)
                        if (next == cur) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
                        else setDanmakuSize(TextSizeTier.entries[next])
                    }
                    1 -> {
                        val cur = global.danmakuSpeed.ordinal
                        val next = (cur + steps).coerceIn(0, DanmakuSpeed.entries.lastIndex)
                        if (next == cur) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
                        else setDanmakuSpeed(DanmakuSpeed.entries[next])
                    }
                    else -> PaletteActionResult() // Multi-select block chips are toggled via click
                }
            }
            XrShared.PALETTE_TAB_SUBTITLE -> when (row) {
                0 -> stepSubtitleTrack(steps)
                1 -> stepAudioTrack(steps)
                2 -> if (selectedSubtitleIsBitmap) stepSubtitleBitmapScale(steps) else {
                    val cur = global.subtitleTextSize.ordinal
                    val next = (cur + steps).coerceIn(0, TextSizeTier.entries.lastIndex)
                    if (next == cur) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
                    else setSubtitleTextSize(TextSizeTier.entries[next])
                }
                3 -> stepSubtitlePos(steps)
                else -> PaletteActionResult()
            }
            else -> PaletteActionResult()
        }
    }

    /** Handles direct pointer drag along a slider track (normalized 0..1 from texture X). */
    fun handleSliderDrag(row: Int, px: Float): PaletteActionResult {
        if (!isRowAdjustable(activeTab, danmakuPage, row)) return PaletteActionResult()
        val localX = px - XrShared.PALETTE_ROW_X
        return when (activeTab) {
            XrShared.PALETTE_TAB_3D -> if (row == 2) {
                val frac = ((localX - XrShared.PALETTE_FLOAT_SLIDER_OFFSET_X) /
                    XrShared.PALETTE_FLOAT_SLIDER_W.toFloat()).coerceIn(0f, 1f)
                setFloatSliderNormalized(frac)
            } else PaletteActionResult()
            XrShared.PALETTE_TAB_SCREEN -> {
                val frac = sliderFrac(localX)
                when (row) {
                    0 -> {
                        var fov = ScreenReadout.FOV_MIN_DEG + frac * (ScreenReadout.FOV_MAX_DEG - ScreenReadout.FOV_MIN_DEG)
                        fov = fov.roundToInt().toFloat()
                        val snapped = snapFovDetent(fov)
                        setScreenFov(snapped, hitDetent = isFovDetent(snapped))
                    }
                    1 -> {
                        val raw = ScreenReadout.DIST_MIN_M * (ScreenReadout.DIST_MAX_M / ScreenReadout.DIST_MIN_M).toDouble().pow(frac.toDouble()).toFloat()
                        val snapped = snapDistDetent(raw)
                        setScreenDist(snapped, hitDetent = isDistDetent(snapped))
                    }
                    2 -> {
                        val pct = ScreenReadout.quantizeCurvature((frac * 100f).roundToInt())
                        setScreenCurvature(pct)
                    }
                    else -> PaletteActionResult()
                }
            }
            XrShared.PALETTE_TAB_DANMAKU -> if (danmakuPage == 0 && row == 2) {
                val frac = sliderFrac(localX)
                val pct = (20 + (frac * 8f).roundToInt() * 10).coerceIn(20, 100)
                setDanmakuOpacity(pct)
            } else PaletteActionResult()
            XrShared.PALETTE_TAB_SUBTITLE -> {
                val frac = sliderFrac(localX)
                when (row) {
                    2 -> if (selectedSubtitleIsBitmap) {
                        val pct = (70 + (frac * 8f).roundToInt() * 10).coerceIn(70, 150)
                        setSubtitleBitmapScale(pct)
                    } else PaletteActionResult()
                    3 -> {
                        val halfSteps = (frac * 22f).roundToInt().coerceIn(0, 22)
                        setSubtitlePos(1.0f + halfSteps * 0.5f)
                    }
                    else -> PaletteActionResult()
                }
            }
            else -> PaletteActionResult()
        }
    }

    /** Resets a single row per §6.3: per-video -> Settings default & clear override; global -> factory default. */
    fun resetRow(row: Int): PaletteActionResult {
        if (!isRowAdjustable(activeTab, danmakuPage, row)) return PaletteActionResult()
        return when (activeTab) {
            XrShared.PALETTE_TAB_3D -> when (row) {
                0 -> {
                    video = video.copy(strength = null)
                    PaletteActionResult(
                        changed = true,
                        stereoChanged = true,
                        hapticKind = XrShared.PALETTE_HAPTIC_TICK,
                        clickSound = true,
                        saveVideoOverrides = true,
                    )
                }
            1 -> {
                video = video.copy(sourceFormat = null)
                PaletteActionResult(
                    changed = true,
                    sourceFormatChanged = true,
                    hapticKind = XrShared.PALETTE_HAPTIC_TICK,
                    clickSound = true,
                    saveVideoOverrides = true,
                )
            }
            2 -> {
                global = global.copy(floatAuto = true, floatAutoOffsetSteps = 0, floatFixedSteps = 0)
                PaletteActionResult(
                    changed = true,
                    stereoChanged = true,
                    hapticKind = XrShared.PALETTE_HAPTIC_TICK,
                    clickSound = true,
                    saveGlobalSettings = true,
                )
            }
            3 -> setDepthLayer(DepthLayerLevel.MEDIUM)
            else -> PaletteActionResult()
        }
        XrShared.PALETTE_TAB_SCREEN -> when (row) {
            0 -> if (screenSizeDisabledMessage == null) setScreenFov(DEFAULT_FOV_DEG, hitDetent = true) else PaletteActionResult()
            1 -> if (screenDistDisabledMessage == null) setScreenDist(DEFAULT_DIST_M, hitDetent = true) else PaletteActionResult()
            2 -> if (curvatureDisabledMessage == null) setScreenCurvature(0) else PaletteActionResult()
            3 -> setEnvironment(PaletteEnvironment.PASSTHROUGH)
            else -> PaletteActionResult()
        }
        XrShared.PALETTE_TAB_DANMAKU -> if (danmakuPage == 0) {
            when (row) {
                0 -> setDanmakuOn(true)
                1 -> setDanmakuArea(DanmakuArea.HALF)
                2 -> setDanmakuOpacity(DEFAULT_DANMAKU_OPACITY)
                else -> PaletteActionResult()
            }
        } else {
            when (row) {
                0 -> setDanmakuSize(TextSizeTier.MEDIUM)
                1 -> setDanmakuSpeed(DanmakuSpeed.NORMAL)
                2 -> {
                    global = global.copy(danmakuBlockMask = 0)
                    PaletteActionResult(
                        changed = true,
                        danmakuChanged = true,
                        hapticKind = XrShared.PALETTE_HAPTIC_TICK,
                        clickSound = true,
                        saveGlobalSettings = true,
                    )
                }
                else -> PaletteActionResult()
            }
        }
        XrShared.PALETTE_TAB_SUBTITLE -> when (row) {
            0 -> {
                video = video.copy(subtitleId = null)
                PaletteActionResult(
                    changed = true,
                    subtitleTrackChanged = true,
                    hapticKind = XrShared.PALETTE_HAPTIC_TICK,
                    clickSound = true,
                    saveVideoOverrides = true,
                )
            }
            1 -> {
                video = video.copy(audioId = null)
                PaletteActionResult(
                    changed = true,
                    audioTrackChanged = true,
                    hapticKind = XrShared.PALETTE_HAPTIC_TICK,
                    clickSound = true,
                    saveVideoOverrides = true,
                )
            }
            2 -> if (selectedSubtitleIsBitmap) setSubtitleBitmapScale(DEFAULT_SUB_BITMAP_SCALE)
                 else setSubtitleTextSize(TextSizeTier.MEDIUM)
            3 -> setSubtitlePos(DEFAULT_SUB_POS_DEG)
            else -> PaletteActionResult()
        }
        else -> PaletteActionResult()
        }
    }

    // ---- Individual setters ----

    private fun setStrength(value: DepthStrength): PaletteActionResult {
        if (value == effectiveStrength) return PaletteActionResult()
        val firstChangeForVideo = !video.strengthNoticeShown
        val storedOverride = if (value == global.defaultStrength) null else value
        video = video.copy(
            strength = storedOverride,
            strengthNoticeShown = true,
        )
        return PaletteActionResult(
            changed = true,
            stereoChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            toastMessage = if (firstChangeForVideo) strings.toastVideoOnly else null,
            saveVideoOverrides = true,
        )
    }

    private fun setSourceFormat(value: StereoSourceFormat): PaletteActionResult {
        if (value == effectiveSourceFormat) return PaletteActionResult()
        video = video.copy(sourceFormat = if (value == defaultSourceFormat) null else value)
        return PaletteActionResult(
            changed = true,
            sourceFormatChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveVideoOverrides = true,
        )
    }

    private fun toggleFloatAuto(): PaletteActionResult {
        global = global.copy(floatAuto = !global.floatAuto)
        return PaletteActionResult(
            changed = true,
            stereoChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveGlobalSettings = true,
        )
    }

    private fun stepFloatSlider(steps: Int): PaletteActionResult {
        return if (global.floatAuto) {
            val cur = global.floatAutoOffsetSteps
            val next = (cur + steps).coerceIn(FLOAT_AUTO_MIN_STEPS, FLOAT_AUTO_MAX_STEPS)
            if (next == cur) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
            else {
                global = global.copy(floatAutoOffsetSteps = next)
                PaletteActionResult(
                    changed = true,
                    stereoChanged = true,
                    hapticKind = if (next == 0) XrShared.PALETTE_HAPTIC_DETENT else XrShared.PALETTE_HAPTIC_TICK,
                    saveGlobalSettings = true,
                )
            }
        } else {
            val cur = global.floatFixedSteps
            val next = (cur + steps).coerceIn(0, FLOAT_FIXED_MAX_STEPS)
            if (next == cur) PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
            else {
                global = global.copy(floatFixedSteps = next)
                PaletteActionResult(
                    changed = true,
                    stereoChanged = true,
                    hapticKind = XrShared.PALETTE_HAPTIC_TICK,
                    saveGlobalSettings = true,
                )
            }
        }
    }

    private fun setFloatSliderNormalized(frac: Float): PaletteActionResult {
        return if (global.floatAuto) {
            val span = FLOAT_AUTO_MAX_STEPS - FLOAT_AUTO_MIN_STEPS
            val next = (FLOAT_AUTO_MIN_STEPS + (frac * span).roundToInt())
                .coerceIn(FLOAT_AUTO_MIN_STEPS, FLOAT_AUTO_MAX_STEPS)
            if (next == global.floatAutoOffsetSteps) PaletteActionResult()
            else {
                global = global.copy(floatAutoOffsetSteps = next)
                PaletteActionResult(
                    changed = true,
                    stereoChanged = true,
                    hapticKind = if (next == 0) XrShared.PALETTE_HAPTIC_DETENT else XrShared.PALETTE_HAPTIC_TICK,
                    saveGlobalSettings = true,
                )
            }
        } else {
            val next = (frac * FLOAT_FIXED_MAX_STEPS).roundToInt().coerceIn(0, FLOAT_FIXED_MAX_STEPS)
            if (next == global.floatFixedSteps) PaletteActionResult()
            else {
                global = global.copy(floatFixedSteps = next)
                PaletteActionResult(
                    changed = true,
                    stereoChanged = true,
                    hapticKind = XrShared.PALETTE_HAPTIC_TICK,
                    saveGlobalSettings = true,
                )
            }
        }
    }

    private fun setDepthLayer(value: DepthLayerLevel): PaletteActionResult {
        if (value == global.depthLayer) return PaletteActionResult()
        global = global.copy(depthLayer = value)
        return PaletteActionResult(
            changed = true,
            stereoChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveGlobalSettings = true,
        )
    }

    private fun stepScreenFov(steps: Int): PaletteActionResult {
        val cur = global.screenFovDeg.roundToInt()
        val next = (cur + steps).coerceIn(ScreenReadout.FOV_MIN_DEG.toInt(), ScreenReadout.FOV_MAX_DEG.toInt())
        if (next == cur) return PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
        return setScreenFov(next.toFloat(), hitDetent = isFovDetent(next.toFloat()))
    }

    private fun setScreenFov(fovDeg: Float, hitDetent: Boolean): PaletteActionResult {
        val clamped = ScreenReadout.clampFov(fovDeg)
        if (abs(clamped - global.screenFovDeg) < 0.05f) return PaletteActionResult()
        global = global.copy(screenFovDeg = clamped)
        return PaletteActionResult(
            changed = true,
            screenChanged = true,
            hapticKind = if (hitDetent) XrShared.PALETTE_HAPTIC_DETENT else XrShared.PALETTE_HAPTIC_TICK,
            saveGlobalSettings = true,
        )
    }

    private fun stepScreenDist(steps: Int): PaletteActionResult {
        val factor = DIST_LOG_STEP_RATIO.pow(steps.toDouble()).toFloat()
        val raw = ScreenReadout.clampDist(global.screenDistM * factor)
        if (abs(raw - global.screenDistM) < 0.005f) {
            return PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
        }
        val snapped = snapDistDetent(raw)
        return setScreenDist(snapped, hitDetent = isDistDetent(snapped))
    }

    private fun setScreenDist(distM: Float, hitDetent: Boolean): PaletteActionResult {
        val clamped = ScreenReadout.clampDist(distM)
        if (abs(clamped - global.screenDistM) < 0.005f) return PaletteActionResult()
        global = global.copy(screenDistM = clamped)
        return PaletteActionResult(
            changed = true,
            screenChanged = true,
            hapticKind = if (hitDetent) XrShared.PALETTE_HAPTIC_DETENT else XrShared.PALETTE_HAPTIC_TICK,
            saveGlobalSettings = true,
        )
    }

    private fun stepScreenCurvature(steps: Int): PaletteActionResult {
        val cur = global.screenCurvaturePct
        val next = ScreenReadout.quantizeCurvature(cur + steps * ScreenReadout.CURVATURE_STEP)
        if (next == cur) return PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
        return setScreenCurvature(next)
    }

    private fun setScreenCurvature(percent: Int): PaletteActionResult {
        val q = ScreenReadout.quantizeCurvature(percent)
        if (q == global.screenCurvaturePct) return PaletteActionResult()
        global = global.copy(screenCurvaturePct = q)
        return PaletteActionResult(
            changed = true,
            screenChanged = true,
            hapticKind = if (q == 0) XrShared.PALETTE_HAPTIC_DETENT else XrShared.PALETTE_HAPTIC_TICK,
            saveGlobalSettings = true,
        )
    }

    private fun setEnvironment(env: PaletteEnvironment): PaletteActionResult {
        if (env == global.environment) return PaletteActionResult()
        global = global.copy(environment = env)
        return PaletteActionResult(
            changed = true,
            environmentChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveGlobalSettings = true,
        )
    }

    private fun setDanmakuOn(on: Boolean): PaletteActionResult {
        if (on == global.danmakuOn) return PaletteActionResult()
        global = global.copy(danmakuOn = on)
        if (!on) danmakuPage = 0
        return PaletteActionResult(
            changed = true,
            danmakuChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveGlobalSettings = true,
        )
    }

    private fun setDanmakuArea(area: DanmakuArea): PaletteActionResult {
        if (area == global.danmakuArea) return PaletteActionResult()
        global = global.copy(danmakuArea = area)
        return PaletteActionResult(
            changed = true,
            danmakuChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveGlobalSettings = true,
        )
    }

    private fun stepDanmakuOpacity(steps: Int): PaletteActionResult {
        val cur = global.danmakuOpacityPct
        val next = (cur + steps * 10).coerceIn(20, 100)
        if (next == cur) return PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
        return setDanmakuOpacity(next)
    }

    private fun setDanmakuOpacity(pct: Int): PaletteActionResult {
        val clamped = ((pct / 10f).roundToInt() * 10).coerceIn(20, 100)
        if (clamped == global.danmakuOpacityPct) return PaletteActionResult()
        global = global.copy(danmakuOpacityPct = clamped)
        return PaletteActionResult(
            changed = true,
            danmakuChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            saveGlobalSettings = true,
        )
    }

    private fun setDanmakuSize(size: TextSizeTier): PaletteActionResult {
        if (size == global.danmakuSize) return PaletteActionResult()
        global = global.copy(danmakuSize = size)
        return PaletteActionResult(
            changed = true,
            danmakuChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveGlobalSettings = true,
        )
    }

    private fun setDanmakuSpeed(speed: DanmakuSpeed): PaletteActionResult {
        if (speed == global.danmakuSpeed) return PaletteActionResult()
        global = global.copy(danmakuSpeed = speed)
        return PaletteActionResult(
            changed = true,
            danmakuChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveGlobalSettings = true,
        )
    }

    private fun toggleDanmakuBlock(chipIdx: Int): PaletteActionResult {
        if (chipIdx !in 0..3) return PaletteActionResult()
        val bit = 1 shl chipIdx
        global = global.copy(danmakuBlockMask = global.danmakuBlockMask xor bit)
        return PaletteActionResult(
            changed = true,
            danmakuChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveGlobalSettings = true,
        )
    }

    private fun stepSubtitleTrack(steps: Int): PaletteActionResult {
        if (subtitleTracks.isEmpty()) return PaletteActionResult()
        val total = subtitleTracks.size + 1 // index 0 = OFF
        val cur = selectedSubtitleIndex
        val next = ((cur + steps) % total + total) % total
        if (next == cur) return PaletteActionResult()
        val id = if (next == 0) SUBTITLE_OFF_ID else subtitleTracks[next - 1].id
        val naturalDef = defaultSubtitleId ?: SUBTITLE_OFF_ID
        video = video.copy(subtitleId = if (id == naturalDef) null else id)
        return PaletteActionResult(
            changed = true,
            subtitleTrackChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveVideoOverrides = true,
        )
    }

    private fun stepAudioTrack(steps: Int): PaletteActionResult {
        if (audioTracks.size <= 1) return PaletteActionResult()
        val total = audioTracks.size
        val cur = selectedAudioIndex.coerceAtLeast(0)
        val next = ((cur + steps) % total + total) % total
        if (next == cur) return PaletteActionResult()
        val id = audioTracks[next].id
        video = video.copy(audioId = if (id == defaultAudioId) null else id)
        return PaletteActionResult(
            changed = true,
            audioTrackChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveVideoOverrides = true,
        )
    }

    private fun setSubtitleTextSize(size: TextSizeTier): PaletteActionResult {
        if (size == global.subtitleTextSize) return PaletteActionResult()
        global = global.copy(subtitleTextSize = size)
        return PaletteActionResult(
            changed = true,
            subtitleStyleChanged = true,
            hapticKind = XrShared.PALETTE_HAPTIC_TICK,
            clickSound = true,
            saveGlobalSettings = true,
        )
    }

    private fun stepSubtitleBitmapScale(steps: Int): PaletteActionResult {
        val cur = global.subtitleBitmapScalePct
        val next = (cur + steps * 10).coerceIn(70, 150)
        if (next == cur) return PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
        return setSubtitleBitmapScale(next)
    }

    private fun setSubtitleBitmapScale(pct: Int): PaletteActionResult {
        val clamped = ((pct / 10f).roundToInt() * 10).coerceIn(70, 150)
        if (clamped == global.subtitleBitmapScalePct) return PaletteActionResult()
        global = global.copy(subtitleBitmapScalePct = clamped)
        return PaletteActionResult(
            changed = true,
            subtitleStyleChanged = true,
            hapticKind = if (clamped == DEFAULT_SUB_BITMAP_SCALE) XrShared.PALETTE_HAPTIC_DETENT else XrShared.PALETTE_HAPTIC_TICK,
            saveGlobalSettings = true,
        )
    }

    private fun stepSubtitlePos(steps: Int): PaletteActionResult {
        val curHalf = (global.subtitlePosDeg * 2f).roundToInt()
        val nextHalf = (curHalf + steps).coerceIn(2, 24) // 1.0°..12.0°
        if (nextHalf == curHalf) return PaletteActionResult(hapticKind = XrShared.PALETTE_HAPTIC_LIMIT)
        return setSubtitlePos(nextHalf * 0.5f)
    }

    private fun setSubtitlePos(deg: Float): PaletteActionResult {
        val clamped = ((deg * 2f).roundToInt().coerceIn(2, 24)) * 0.5f
        if (abs(clamped - global.subtitlePosDeg) < 0.05f) return PaletteActionResult()
        global = global.copy(subtitlePosDeg = clamped)
        return PaletteActionResult(
            changed = true,
            subtitleStyleChanged = true,
            hapticKind = if (abs(clamped - DEFAULT_SUB_POS_DEG) < 0.05f) XrShared.PALETTE_HAPTIC_DETENT else XrShared.PALETTE_HAPTIC_TICK,
            saveGlobalSettings = true,
        )
    }

    /** Normalized [0..1] progress for any slider row (shared with [PaletteRenderer]). */
    fun sliderNormalized(tab: Int, page: Int, row: Int): Float = when (tab) {
        XrShared.PALETTE_TAB_3D -> if (row == 2) {
            if (global.floatAuto) {
                (global.floatAutoOffsetSteps - FLOAT_AUTO_MIN_STEPS) /
                    (FLOAT_AUTO_MAX_STEPS - FLOAT_AUTO_MIN_STEPS).toFloat()
            } else {
                global.floatFixedSteps / FLOAT_FIXED_MAX_STEPS.toFloat()
            }
        } else 0f
        XrShared.PALETTE_TAB_SCREEN -> when (row) {
            0 -> ScreenReadout.sizeNorm(global.screenFovDeg)
            1 -> ScreenReadout.distNorm(global.screenDistM)
            2 -> global.screenCurvaturePct / 100f
            else -> 0f
        }
        XrShared.PALETTE_TAB_DANMAKU -> if (page == 0 && row == 2) {
            (global.danmakuOpacityPct - 20) / 80f
        } else 0f
        XrShared.PALETTE_TAB_SUBTITLE -> when (row) {
            2 -> (global.subtitleBitmapScalePct - 70) / 80f
            3 -> (global.subtitlePosDeg - 1.0f) / 11.0f
            else -> 0f
        }
        else -> 0f
    }

    /**
     * Zero-allocation 128-bit visual state hash written into a 2-element [LongArray].
     * Replaces per-frame String building so idle/unchanged frames allocate zero heap objects.
     */
    fun computeRenderHash(out: LongArray) {
        var h1 = -3750763034362895579L // FNV-1a 64-bit offset basis
        var h2 = 0x9E3779B97F4A7C15uL.toLong()
        fun mix(v: Long) {
            h1 = (h1 xor v) * 1099511628211L
            var z = h2 + v + 0x9E3779B97F4A7C15uL.toLong()
            z = (z xor (z ushr 30)) * -4658895280553007687L
            z = (z xor (z ushr 27)) * -7723592293110705685L
            h2 = z xor (z ushr 31)
        }
        fun mixInt(v: Int) = mix(v.toLong())
        fun mixBool(v: Boolean) = mix(if (v) 1L else 0L)

        mixInt(activeTab)
        mixInt(danmakuPage)
        mixBool(isPinned)
        mixBool(isHandTrackingMode)
        mixBool(isDimFallback)
        mixBool(cylinderSupported)
        mixBool(danmakuSupported)
        mixBool(danmakuLoadFailed)
        mixBool(depthModelUnavailable)

        when (val hv = hover) {
            PaletteHover.None -> mixInt(0)
            is PaletteHover.Tab -> {
                mixInt(1)
                mixInt(hv.index)
            }
            PaletteHover.Pin -> mixInt(2)
            is PaletteHover.Row -> {
                mixInt(3)
                mixInt(hv.index)
                mixInt(hv.chipIndex)
                mixBool(hv.onReset)
                mixBool(hv.onSlider)
                mixBool(hv.onFloatAutoChip)
                mixInt(hv.stepperDir)
                mixBool(hv.onRetry)
            }
        }

        when (activeTab) {
            XrShared.PALETTE_TAB_3D -> {
                mixInt(effectiveStrength.ordinal)
                mixInt(effectiveSourceFormat.ordinal)
                mixInt(defaultSourceFormat.ordinal)
                mixInt(global.defaultStrength.ordinal)
                mixBool(global.floatAuto)
                mixInt(global.floatAutoOffsetSteps)
                mixInt(global.floatFixedSteps)
                mixInt((global.screenDistM * 10f).roundToInt())
                mixInt(global.depthLayer.ordinal)
            }
            XrShared.PALETTE_TAB_SCREEN -> {
                mixInt(global.screenFovDeg.roundToInt())
                mixInt((global.screenDistM * 100f).roundToInt())
                mixInt(global.screenCurvaturePct)
                mixInt(global.environment.ordinal)
            }
            XrShared.PALETTE_TAB_DANMAKU -> {
                mixBool(global.danmakuOn)
                mixInt(global.danmakuArea.ordinal)
                mixInt(global.danmakuOpacityPct)
                mixInt(global.danmakuSize.ordinal)
                mixInt(global.danmakuSpeed.ordinal)
                mixInt(global.danmakuBlockMask)
            }
            XrShared.PALETTE_TAB_SUBTITLE -> {
                mixInt(subtitleTracks.size)
                mixInt(selectedSubtitleIndex)
                mixInt(selectedSubtitleTrack?.label?.hashCode() ?: 0)
                mixBool(selectedSubtitleIsBitmap)
                mixInt(audioTracks.size)
                mixInt(selectedAudioIndex)
                mixInt(selectedAudioTrack?.label?.hashCode() ?: 0)
                mixInt(global.subtitleTextSize.ordinal)
                mixInt(global.subtitleBitmapScalePct)
                mixInt((global.subtitlePosDeg * 10f).roundToInt())
                mixInt(defaultSubtitleId?.hashCode() ?: 0)
                mixInt(defaultAudioId?.hashCode() ?: 0)
            }
        }
        out[0] = h1
        out[1] = h2
    }

    /** Convenience 64-bit visual hash for tests. */
    fun renderHash(): Long {
        val buf = LongArray(2)
        computeRenderHash(buf)
        return buf[0] xor buf[1]
    }

    internal enum class RowControlKind { CHIPS, SLIDER, FLOAT_SLIDER, STEPPER, NONE }

    internal fun rowKind(tab: Int, page: Int, row: Int): RowControlKind = when (tab) {
        XrShared.PALETTE_TAB_3D -> when (row) {
            0, 1, 3 -> RowControlKind.CHIPS
            2 -> RowControlKind.FLOAT_SLIDER
            else -> RowControlKind.NONE
        }
        XrShared.PALETTE_TAB_SCREEN -> when (row) {
            0, 1, 2 -> RowControlKind.SLIDER
            3 -> RowControlKind.CHIPS
            else -> RowControlKind.NONE
        }
        XrShared.PALETTE_TAB_DANMAKU -> if (!danmakuSupported || danmakuLoadFailed) {
            RowControlKind.NONE
        } else if (page == 0) {
            when (row) {
                0, 1 -> RowControlKind.CHIPS
                2 -> RowControlKind.SLIDER
                else -> RowControlKind.NONE
            }
        } else {
            when (row) {
                0, 1, 2 -> RowControlKind.CHIPS
                else -> RowControlKind.NONE
            }
        }
        XrShared.PALETTE_TAB_SUBTITLE -> when (row) {
            0, 1 -> RowControlKind.STEPPER
            2 -> if (selectedSubtitleIsBitmap) RowControlKind.SLIDER else RowControlKind.CHIPS
            3 -> RowControlKind.SLIDER
            else -> RowControlKind.NONE
        }
        else -> RowControlKind.NONE
    }

    internal fun chipCount(tab: Int, page: Int, row: Int): Int = when (tab) {
        XrShared.PALETTE_TAB_3D -> when (row) {
            0, 3 -> 4
            1 -> 3
            else -> 0
        }
        XrShared.PALETTE_TAB_SCREEN -> if (row == 3) 4 else 0
        XrShared.PALETTE_TAB_DANMAKU -> if (page == 0) {
            when (row) {
                0 -> 2
                1 -> 3
                else -> 0
            }
        } else {
            when (row) {
                0, 1 -> 3
                2 -> 4
                else -> 0
            }
        }
        XrShared.PALETTE_TAB_SUBTITLE -> if (row == 2 && !selectedSubtitleIsBitmap) 3 else 0
        else -> 0
    }

    companion object {
        const val SUBTITLE_OFF_ID = "__off__"
        const val FLOAT_STEP_DEG = 0.05f
        const val FLOAT_AUTO_MIN_STEPS = -2
        const val FLOAT_AUTO_MAX_STEPS = 6
        const val FLOAT_FIXED_MAX_STEPS = 12
        const val DEFAULT_FOV_DEG = 50f
        const val DEFAULT_DIST_M = 3.0f
        const val FULL_SBS_MIN_RATIO = 2.60f
        const val FULL_OU_MAX_RATIO = 1.25f

        /** Effective per-eye display aspect (width / height), matching native paletteVideoHeightOverWidth. */
        @JvmStatic
        fun effectiveVideoAspect(videoW: Int, videoH: Int, format: StereoSourceFormat): Float {
            if (videoW <= 0 || videoH <= 0) return 16f / 9f
            val raw = videoW.toFloat() / videoH.toFloat()
            return when {
                format == StereoSourceFormat.SBS && raw >= FULL_SBS_MIN_RATIO -> raw * 0.5f
                format == StereoSourceFormat.OU && raw <= FULL_OU_MAX_RATIO -> raw * 2.0f
                else -> raw
            }
        }
        const val DEFAULT_DANMAKU_OPACITY = 80
        const val DEFAULT_SUB_BITMAP_SCALE = 100
        const val DEFAULT_SUB_POS_DEG = 2.0f
        const val DIST_LOG_STEP_RATIO = 1.05
        const val CHIP_GAP = XrShared.PALETTE_SEG_GAP.toFloat()
        const val STEPPER_BTN_W = 84f
        const val RETRY_BTN_LOCAL_X = 636f
        const val RETRY_BTN_W = 180f

        val TAB_X = floatArrayOf(
            XrShared.PALETTE_TAB0_X.toFloat(),
            XrShared.PALETTE_TAB1_X.toFloat(),
            XrShared.PALETTE_TAB2_X.toFloat(),
            XrShared.PALETTE_TAB3_X.toFloat(),
        )
        val TAB_W = floatArrayOf(
            XrShared.PALETTE_TAB0_W.toFloat(),
            XrShared.PALETTE_TAB1_W.toFloat(),
            XrShared.PALETTE_TAB2_W.toFloat(),
            XrShared.PALETTE_TAB3_W.toFloat(),
        )

        private val FOV_DETENTS = floatArrayOf(40f, 50f, 62f, 80f)
        private val DIST_DETENTS = floatArrayOf(2.0f, 3.0f, 5.0f)

        fun isFovDetent(fov: Float): Boolean = FOV_DETENTS.any { abs(fov - it) <= 0.5f }
        fun isDistDetent(dist: Float): Boolean = DIST_DETENTS.any { abs(dist - it) <= 0.05f }

        fun snapFovDetent(fov: Float): Float =
            FOV_DETENTS.firstOrNull { abs(fov - it) <= 1.2f } ?: fov

        fun snapDistDetent(dist: Float): Float =
            DIST_DETENTS.firstOrNull { abs(dist - it) / it <= 0.03f } ?: dist

        /** Stereoscopic pop-out distance in cm in front of screen for horizontal disparity angle deg (IPD = 6.3 cm). */
        fun popOutCm(deg: Float, distM: Float): Int {
            if (deg <= 0.001f || distM <= 0.1f) return 0
            val rad = Math.toRadians(deg.toDouble())
            val sepM = 2.0 * distM * tan(rad * 0.5)
            val ipdM = 0.063
            val deltaZ = distM * sepM / (ipdM + sepM)
            return (deltaZ * 100.0).roundToInt().coerceAtLeast(1)
        }

        fun sliderFrac(localX: Float): Float =
            ((localX - XrShared.PALETTE_SLIDER_OFFSET_X) / XrShared.PALETTE_SLIDER_W.toFloat())
                .coerceIn(0f, 1f)

        fun hitChipIndex(localX: Float, count: Int): Int {
            if (count <= 0) return -1
            val start = XrShared.PALETTE_CTRL_OFFSET_X.toFloat()
            val totalW = XrShared.PALETTE_CTRL_W.toFloat()
            val chipW = (totalW - CHIP_GAP * (count - 1)) / count
            for (i in 0 until count) {
                val x0 = start + i * (chipW + CHIP_GAP)
                if (localX >= x0 && localX <= x0 + chipW) return i
            }
            return -1
        }

        fun logDistFraction(distM: Float): Float = ScreenReadout.distNorm(distM)

        private fun merge(a: PaletteActionResult, b: PaletteActionResult): PaletteActionResult =
            PaletteActionResult(
                changed = a.changed || b.changed,
                stereoChanged = a.stereoChanged || b.stereoChanged,
                sourceFormatChanged = a.sourceFormatChanged || b.sourceFormatChanged,
                screenChanged = a.screenChanged || b.screenChanged,
                environmentChanged = a.environmentChanged || b.environmentChanged,
                danmakuChanged = a.danmakuChanged || b.danmakuChanged,
                danmakuRetryRequested = a.danmakuRetryRequested || b.danmakuRetryRequested,
                subtitleTrackChanged = a.subtitleTrackChanged || b.subtitleTrackChanged,
                audioTrackChanged = a.audioTrackChanged || b.audioTrackChanged,
                subtitleStyleChanged = a.subtitleStyleChanged || b.subtitleStyleChanged,
                hapticKind = if (b.hapticKind != XrShared.PALETTE_HAPTIC_NONE) b.hapticKind else a.hapticKind,
                clickSound = a.clickSound || b.clickSound,
                toastMessage = b.toastMessage ?: a.toastMessage,
                saveVideoOverrides = a.saveVideoOverrides || b.saveVideoOverrides,
                saveGlobalSettings = a.saveGlobalSettings || b.saveGlobalSettings,
            )
    }
}

/** Native stereo uniforms computed from [PaletteController]. */
data class StereoRenderParams(
    val on: Boolean,
    val separation: Float,
    val convergence: Float,
    val depthLocal: Float,
    val sourceFormat: StereoSourceFormat,
)
