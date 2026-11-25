package com.limelight.local.stream

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.limelight.binding.video.DepthPresets
import com.limelight.binding.video.EnvironmentIds
import com.limelight.binding.video.ScreenReadout
import com.limelight.binding.video.XrShared
import com.limelight.preferences.PreferenceConfiguration
import kotlin.math.roundToInt

/** Thin key-value store abstraction backed by SharedPreferences for 100% JVM testability. */
interface PrefsStore {
    fun asSharedPreferences(): SharedPreferences
    fun getString(key: String, def: String?): String?
    fun getInt(key: String, def: Int): Int
    fun getBoolean(key: String, def: Boolean): Boolean
    fun contains(key: String): Boolean
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

class AndroidPrefsStore(private val prefs: SharedPreferences) : PrefsStore {
    override fun asSharedPreferences(): SharedPreferences = prefs
    override fun getString(key: String, def: String?): String? = prefs.getString(key, def)
    override fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    override fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }

    companion object {
        @Suppress("DEPRECATION")
        fun defaultPrefs(context: Context): AndroidPrefsStore =
            AndroidPrefsStore(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))

        fun namedPrefs(context: Context, name: String): AndroidPrefsStore =
            AndroidPrefsStore(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))
    }
}

class MapPrefsStore : PrefsStore, SharedPreferences {
    private val map = LinkedHashMap<String, Any?>()

    override fun asSharedPreferences(): SharedPreferences = this
    override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
    override fun getString(key: String, def: String?): String? = map[key] as? String ?: def
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, def: MutableSet<String>?): MutableSet<String>? =
        map[key] as? MutableSet<String> ?: def
    override fun getInt(key: String, def: Int): Int = map[key] as? Int ?: def
    override fun getLong(key: String, def: Long): Long = map[key] as? Long ?: def
    override fun getFloat(key: String, def: Float): Float = map[key] as? Float ?: def
    override fun getBoolean(key: String, def: Boolean): Boolean = map[key] as? Boolean ?: def
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = MapEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun putString(key: String, value: String) { map[key] = value }
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }

    private inner class MapEditor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (values == null) map.remove(key) else map[key] = LinkedHashSet(values)
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { map[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { map[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { map[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { map[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { map.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { map.clear() }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }

    fun snapshot(): Map<String, Any?> = LinkedHashMap(map)
}

enum class StreamResolution(val width: Int, val height: Int, val prefValue: String) {
    R720P(1280, 720, PreferenceConfiguration.RES_720P),
    R1080P(1920, 1080, PreferenceConfiguration.RES_1080P),
    R1440P(2560, 1440, PreferenceConfiguration.RES_1440P),
    R4K(3840, 2160, PreferenceConfiguration.RES_4K);

    companion object {
        fun fromDimensionsOrNull(width: Int, height: Int): StreamResolution? =
            entries.firstOrNull { it.width == width && it.height == height }

        fun fromCellIndex(cell: Int): StreamResolution =
            entries.getOrElse(cell) { R1440P }
    }
}

enum class StreamFps(val fps: Int) {
    FPS_60(60),
    FPS_72(72),
    FPS_90(90);

    val prefValue: String get() = fps.toString()

    companion object {
        fun fromFpsOrNull(fps: Int): StreamFps? = entries.firstOrNull { it.fps == fps }
    }
}

enum class BitratePreset(val prefValue: String) {
    AUTO("auto"),
    SMOOTH("smooth"),
    SHARP("sharp"),
    CUSTOM("custom");

    companion object {
        fun fromPref(value: String?): BitratePreset =
            entries.firstOrNull { it.prefValue == value } ?: AUTO

        fun fromVrQuickCell(cell: Int): BitratePreset = when (cell) {
            0 -> SMOOTH
            1 -> AUTO
            2 -> SHARP
            else -> AUTO
        }
    }
}

enum class StreamCodec(val prefValue: String, val formatOption: PreferenceConfiguration.FormatOption) {
    AUTO("auto", PreferenceConfiguration.FormatOption.AUTO),
    H264("neverh265", PreferenceConfiguration.FormatOption.FORCE_H264),
    HEVC("forceh265", PreferenceConfiguration.FormatOption.FORCE_HEVC);

    companion object {
        fun fromFormatOptionOrNull(option: PreferenceConfiguration.FormatOption): StreamCodec? =
            entries.firstOrNull { it.formatOption == option }
    }
}

enum class MouseMode {
    RELATIVE,
    ABSOLUTE,
}

enum class GamepadToggleShortcut(val prefValue: String, val xrCode: Int) {
    MENU_GRIP(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_MENU_GRIP, XrShared.PAD_SHORTCUT_MENU_GRIP),
    STICKS(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_STICKS, XrShared.PAD_SHORTCUT_STICKS),
    TRIGGERS_GRIPS(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_TRIGGERS_GRIPS, XrShared.PAD_SHORTCUT_TRIGGERS_GRIPS);

    companion object {
        fun fromXrCode(code: Int): GamepadToggleShortcut =
            entries.firstOrNull { it.xrCode == code } ?: MENU_GRIP
    }
}

enum class DepthStrength {
    OFF,
    SOFT,
    STANDARD,
    STRONG,
}

enum class DepthQualityMode {
    SMOOTH,
    QUALITY,
}

enum class ScreenSizePreset(val decimeters: Int) {
    SMALL(22),
    MEDIUM(30),
    LARGE(40),
    GIANT(54);

    companion object {
        fun fromDecimeters(value: Int): ScreenSizePreset = when {
            value <= 25 -> SMALL
            value <= 35 -> MEDIUM
            value <= 47 -> LARGE
            else -> GIANT
        }
    }
}

enum class ScreenDistancePreset(val decimeters: Int) {
    NEAR(20),
    MEDIUM(30),
    FAR(45);

    companion object {
        fun fromDecimeters(value: Int): ScreenDistancePreset = when {
            value <= 24 -> NEAR
            value <= 37 -> MEDIUM
            else -> FAR
        }
    }
}

enum class VrEnvironmentPreset(val envId: Int) {
    PASSTHROUGH(PreferenceConfiguration.VR_ENV_PASSTHROUGH),
    VOID(PreferenceConfiguration.VR_ENV_VOID),
    HOME_THEATER(PreferenceConfiguration.VR_ENV_HOME_THEATER),
    GRAND_CINEMA(PreferenceConfiguration.VR_ENV_GRAND_CINEMA),
    SYNTHWAVE(PreferenceConfiguration.VR_ENV_SYNTHWAVE);

    companion object {
        fun fromEnvId(id: Int): VrEnvironmentPreset =
            entries.firstOrNull { it.envId == id } ?: VOID
    }
}

enum class FileLogMode(val prefValue: String) {
    OFF("off"),
    BASIC("basic"),
    VERBOSE("verbose");

    companion object {
        fun fromPref(value: String?): FileLogMode =
            entries.firstOrNull { it.prefValue == value } ?: BASIC
    }
}

/** Why continuous screen curvature cannot be adjusted right now (§6.12). */
enum class CurvatureDisabledReason {
    IN_ROOM,
    UNSUPPORTED_HEADSET,
}

data class Depth3dSettingsSnapshot(
    val actualWidth: Int,
    val actualHeight: Int,
    val resolution: StreamResolution?,
    val actualFps: Int,
    val fps: StreamFps?,
    val bitratePreset: BitratePreset,
    val actualBitrateKbps: Int,
    val bitrateMbps: Int,
    val actualCodec: PreferenceConfiguration.FormatOption,
    val codec: StreamCodec?,
    val mouseMode: MouseMode,
    val multiController: Boolean,
    val flipFaceButtons: Boolean,
    val gamepadShortcut: GamepadToggleShortcut,
    val depthStrength: DepthStrength,
    val depthQuality: DepthQualityMode,
    val actualDepthRate: Int,
    val sharpening: Boolean,
    val supersampling: Boolean,
    val screenSize: ScreenSizePreset,
    val actualScreenSizeDm: Int,
    val screenDistance: ScreenDistancePreset,
    val actualScreenDistanceDm: Int,
    val curvedScreen: Boolean,
    val actualCurvature: Int,
    val environment: VrEnvironmentPreset,
    val ambilight: Boolean,
    val doffGrace: Boolean,
    val handTracking: Boolean,
    val showRay: Boolean,
    val pointerSleep: Boolean,
    val headAim: Boolean,
    val clickSound: Boolean,
    val fileLog: FileLogMode,
    val isGen1Headset: Boolean,
    val screenFovDeg: Float = ScreenReadout.clampFov(
        ScreenReadout.fovDegFromFlatWidth(actualScreenSizeDm / 10f, actualScreenDistanceDm / 10f),
    ),
    val screenDiagonalInches: Int = ScreenReadout.diagonalInches(
        ScreenReadout.clampFov(
            ScreenReadout.fovDegFromFlatWidth(actualScreenSizeDm / 10f, actualScreenDistanceDm / 10f),
        ),
        ScreenReadout.clampDist(actualScreenDistanceDm / 10f),
    ),
    val triggerScreenAdjust: Boolean = PreferenceConfiguration.DEFAULT_VR_TRIGGER_SCREEN_ADJUST,
    val cylinderSupported: Boolean = true,
    val curvatureDisabledReason: CurvatureDisabledReason? = null,
) {
    val warn3dLag: Boolean
        get() = StreamSettingsMapper.shouldWarn3dLag(actualWidth, actualHeight, actualFps)

    /** Non-null only when the stored resolution is outside the 4 standard presets (e.g. 854x480). */
    val customResolutionLabel: String?
        get() = if (resolution == null) "${actualWidth}×${actualHeight}" else null

    /** Non-null only when the stored FPS is outside 60/72/90 (e.g. 30 or 120). */
    val customFpsLabel: String?
        get() = if (fps == null) "$actualFps fps" else null

    /** Non-null only when stored codec is outside Auto/H.264/HEVC (e.g. AV1). */
    val customCodecLabel: String?
        get() = if (codec == null && actualCodec == PreferenceConfiguration.FormatOption.FORCE_AV1) "AV1" else null
}

/**
 * Translates between depth3d's Compose settings UI, the in-VR quick menu, and
 * [PreferenceConfiguration]. Always reads through [PreferenceConfiguration.readPreferences]
 * so what the UI displays is guaranteed identical to what actually takes effect.
 */
object StreamSettingsMapper {
    const val KEY_RESOLUTION = PreferenceConfiguration.RESOLUTION_PREF_STRING
    const val KEY_FPS = PreferenceConfiguration.FPS_PREF_STRING
    const val KEY_UNLOCK_FPS = "checkbox_unlock_fps"
    const val KEY_BITRATE_KBPS = PreferenceConfiguration.BITRATE_PREF_STRING
    const val KEY_BITRATE_PRESET = PreferenceConfiguration.DEPTH3D_BITRATE_PRESET_PREF_STRING
    const val KEY_VIDEO_FORMAT = "video_format"
    const val KEY_ABSOLUTE_MOUSE = "checkbox_absolute_mouse_mode"
    const val KEY_MULTI_CONTROLLER = "checkbox_multi_controller"
    const val KEY_FLIP_FACE_BUTTONS = "checkbox_flip_face_buttons"
    const val KEY_GAMEPAD_TOGGLE = PreferenceConfiguration.VR_GAMEPAD_TOGGLE_PREF_STRING

    const val KEY_DEPTH_SOURCE = PreferenceConfiguration.VR_DEPTH_SOURCE_PREF_STRING
    const val KEY_SEPARATION = PreferenceConfiguration.VR_SEPARATION_PREF_STRING
    const val KEY_DEPTH_RATE = "seekbar_vr_inference_cadence"
    const val KEY_SHARPENING = PreferenceConfiguration.VR_SHARPENING_PREF_STRING
    const val KEY_SUPERSAMPLING = PreferenceConfiguration.VR_SUPERSAMPLING_PREF_STRING

    const val KEY_SCREEN_SIZE = PreferenceConfiguration.VR_SCREEN_SIZE_PREF_STRING
    const val KEY_SCREEN_DISTANCE = PreferenceConfiguration.VR_DISTANCE_PREF_STRING
    const val KEY_CURVATURE = PreferenceConfiguration.VR_CURVATURE_PREF_STRING
    const val KEY_TRIGGER_SCREEN_ADJUST = PreferenceConfiguration.VR_TRIGGER_SCREEN_ADJUST_PREF_STRING
    const val KEY_CYLINDER_SUPPORTED = PreferenceConfiguration.VR_CYLINDER_SUPPORTED_PREF_STRING
    const val KEY_ENV_ID = PreferenceConfiguration.VR_ENVIRONMENT_ID_PREF_STRING
    const val KEY_PASSTHROUGH = PreferenceConfiguration.VR_PASSTHROUGH_PREF_STRING
    const val KEY_AMBILIGHT = PreferenceConfiguration.VR_AMBILIGHT_PREF_STRING
    const val KEY_DOFF_GRACE = PreferenceConfiguration.VR_DOFF_GRACE_PREF_STRING
    const val KEY_SCREEN_POSE = PreferenceConfiguration.VR_SCREEN_POSE_PREF_STRING

    const val KEY_HAND_TRACKING = PreferenceConfiguration.VR_HAND_TRACKING_PREF_STRING
    const val KEY_SHOW_RAY = PreferenceConfiguration.VR_SHOW_RAY_PREF_STRING
    const val KEY_POINTER_SLEEP = PreferenceConfiguration.VR_POINTER_SLEEP_PREF_STRING
    const val KEY_HEAD_AIM = PreferenceConfiguration.VR_HEAD_AIM_PREF_STRING
    const val KEY_CLICK_SOUND = PreferenceConfiguration.VR_CLICK_SOUND_PREF_STRING

    const val KEY_FILE_LOG = PreferenceConfiguration.FILE_LOG_PREF_STRING

    const val MIN_BITRATE_MBPS = 5
    const val MAX_BITRATE_MBPS = 150
    private const val CURVED_DEFAULT = 45

    /** Spec: warn 「3D 会变卡」 whenever actual effective resolution >= 4K or FPS >= 90. */
    fun shouldWarn3dLag(width: Int, height: Int, fps: Int): Boolean =
        height >= 2160 || width >= 3840 || fps >= 90

    fun shouldWarn3dLag(resolution: StreamResolution, fps: StreamFps): Boolean =
        shouldWarn3dLag(resolution.width, resolution.height, fps.fps)

    /** Computes target bitrate in kbps for a preset + actual resolution + actual fps. */
    fun bitrateKbpsForPreset(
        preset: BitratePreset,
        width: Int,
        height: Int,
        fps: Int,
        customMbps: Int,
    ): Int {
        val base = PreferenceConfiguration.getDefaultBitrate("${width}x${height}", fps.toString())
        val raw = when (preset) {
            BitratePreset.AUTO -> base
            BitratePreset.SMOOTH -> (base * 0.65f).roundToInt()
            BitratePreset.SHARP -> (base * 1.45f).roundToInt()
            BitratePreset.CUSTOM -> customMbps * 1000
        }
        return raw.coerceIn(MIN_BITRATE_MBPS * 1000, MAX_BITRATE_MBPS * 1000)
    }

    fun bitrateKbpsForPreset(
        preset: BitratePreset,
        resolution: StreamResolution,
        fps: StreamFps,
        customMbps: Int,
    ): Int = bitrateKbpsForPreset(preset, resolution.width, resolution.height, fps.fps, customMbps)

    fun read(store: PrefsStore, isGen1Headset: Boolean): Depth3dSettingsSnapshot {
        val prefs = store.asSharedPreferences()
        val cfg = PreferenceConfiguration.readPreferences(prefs, isGen1Headset)

        val resolution = StreamResolution.fromDimensionsOrNull(cfg.width, cfg.height)
        val fps = StreamFps.fromFpsOrNull(cfg.fps)

        val storedPreset = if (store.contains(KEY_BITRATE_PRESET)) {
            BitratePreset.fromPref(store.getString(KEY_BITRATE_PRESET, BitratePreset.AUTO.prefValue))
        } else {
            null
        }
        val actualMbps = (cfg.bitrate / 1000f).roundToInt().coerceAtLeast(1)
        val bitratePreset = resolveEffectiveBitratePreset(
            storedPreset = storedPreset,
            actualBitrateKbps = cfg.bitrate,
            hasStoredBitrate = store.contains(KEY_BITRATE_KBPS),
            width = cfg.width,
            height = cfg.height,
            fps = cfg.fps,
            customMbps = actualMbps,
        )

        val codec = StreamCodec.fromFormatOptionOrNull(cfg.videoFormat)
        val mouseMode = if (cfg.absoluteMouseMode) MouseMode.ABSOLUTE else MouseMode.RELATIVE
        val gamepadShortcut = GamepadToggleShortcut.fromXrCode(cfg.vrGamepadToggle)

        val defaultSep = PreferenceConfiguration.defaultSeparation(cfg.vrDepthModel)
        val depthStrength = when {
            cfg.vrDepthMode == XrShared.DEPTH_MODE_OFF -> DepthStrength.OFF
            cfg.vrStereoSeparation <= DepthPresets.value(DepthPresets.COMFORT, defaultSep) -> DepthStrength.SOFT
            cfg.vrStereoSeparation >= DepthPresets.value(DepthPresets.STRONG, defaultSep) -> DepthStrength.STRONG
            else -> DepthStrength.STANDARD
        }

        val depthQuality = if (cfg.vrDepthRate >= XrShared.DEPTH_RATE_DEFAULT) {
            DepthQualityMode.QUALITY
        } else {
            DepthQualityMode.SMOOTH
        }

        val envId = EnvironmentIds.listValue(prefs).toIntOrNull() ?: PreferenceConfiguration.VR_ENV_VOID
        val environment = VrEnvironmentPreset.fromEnvId(envId)
        val cylinderSupported = PreferenceConfiguration.isCylinderSupported(prefs)
        val inRoom = PreferenceConfiguration.isRoomEnvironment(envId)
        val curvatureDisabledReason = when {
            !cylinderSupported -> CurvatureDisabledReason.UNSUPPORTED_HEADSET
            inRoom -> CurvatureDisabledReason.IN_ROOM
            else -> null
        }
        val distM = ScreenReadout.clampDist(cfg.vrDistance / 10f)
        val fovDeg = ScreenReadout.clampFov(
            ScreenReadout.fovDegFromFlatWidth(cfg.vrScreenSize / 10f, cfg.vrDistance / 10f),
        )
        val diagInches = ScreenReadout.diagonalInches(fovDeg, distM)

        return Depth3dSettingsSnapshot(
            actualWidth = cfg.width,
            actualHeight = cfg.height,
            resolution = resolution,
            actualFps = cfg.fps,
            fps = fps,
            bitratePreset = bitratePreset,
            actualBitrateKbps = cfg.bitrate,
            bitrateMbps = actualMbps,
            actualCodec = cfg.videoFormat,
            codec = codec,
            mouseMode = mouseMode,
            multiController = cfg.multiController,
            flipFaceButtons = cfg.flipFaceButtons,
            gamepadShortcut = gamepadShortcut,
            depthStrength = depthStrength,
            depthQuality = depthQuality,
            actualDepthRate = cfg.vrDepthRate,
            sharpening = cfg.vrSharpening != 0,
            supersampling = cfg.vrSupersampling != 0,
            screenSize = ScreenSizePreset.fromDecimeters(cfg.vrScreenSize),
            actualScreenSizeDm = cfg.vrScreenSize,
            screenDistance = ScreenDistancePreset.fromDecimeters(cfg.vrDistance),
            actualScreenDistanceDm = cfg.vrDistance,
            curvedScreen = cfg.vrCurvature > 0,
            actualCurvature = cfg.vrCurvature,
            environment = environment,
            ambilight = cfg.vrAmbilight,
            doffGrace = cfg.vrDoffGrace,
            handTracking = cfg.vrHandTracking,
            showRay = cfg.vrShowRay,
            pointerSleep = cfg.vrPointerSleep,
            headAim = cfg.vrHeadAim,
            clickSound = cfg.vrClickSound,
            fileLog = FileLogMode.fromPref(cfg.fileLogLevel),
            isGen1Headset = isGen1Headset,
            screenFovDeg = fovDeg,
            screenDiagonalInches = diagInches,
            triggerScreenAdjust = cfg.vrTriggerScreenAdjust,
            cylinderSupported = cylinderSupported,
            curvatureDisabledReason = curvatureDisabledReason,
        )
    }

    private fun resolveEffectiveBitratePreset(
        storedPreset: BitratePreset?,
        actualBitrateKbps: Int,
        hasStoredBitrate: Boolean,
        width: Int,
        height: Int,
        fps: Int,
        customMbps: Int,
    ): BitratePreset {
        if (!hasStoredBitrate && (storedPreset == null || storedPreset == BitratePreset.AUTO)) {
            return BitratePreset.AUTO
        }
        val autoKbps = bitrateKbpsForPreset(BitratePreset.AUTO, width, height, fps, customMbps)
        val smoothKbps = bitrateKbpsForPreset(BitratePreset.SMOOTH, width, height, fps, customMbps)
        val sharpKbps = bitrateKbpsForPreset(BitratePreset.SHARP, width, height, fps, customMbps)
        if (storedPreset != null && storedPreset != BitratePreset.CUSTOM) {
            val expected = when (storedPreset) {
                BitratePreset.AUTO -> autoKbps
                BitratePreset.SMOOTH -> smoothKbps
                BitratePreset.SHARP -> sharpKbps
                BitratePreset.CUSTOM -> actualBitrateKbps
            }
            if (actualBitrateKbps == expected) return storedPreset
        }
        return when (actualBitrateKbps) {
            autoKbps -> BitratePreset.AUTO
            smoothKbps -> BitratePreset.SMOOTH
            sharpKbps -> BitratePreset.SHARP
            else -> BitratePreset.CUSTOM
        }
    }

    fun setResolution(store: PrefsStore, resolution: StreamResolution, isGen1: Boolean) {
        val current = read(store, isGen1)
        store.putString(KEY_RESOLUTION, resolution.prefValue)
        if (current.bitratePreset != BitratePreset.CUSTOM) {
            store.putInt(
                KEY_BITRATE_KBPS,
                bitrateKbpsForPreset(current.bitratePreset, resolution.width, resolution.height, current.actualFps, current.bitrateMbps),
            )
        }
    }

    fun setFps(store: PrefsStore, fps: StreamFps, isGen1: Boolean) {
        val current = read(store, isGen1)
        store.putString(KEY_FPS, fps.prefValue)
        store.putBoolean(KEY_UNLOCK_FPS, fps.fps > 60)
        if (current.bitratePreset != BitratePreset.CUSTOM) {
            store.putInt(
                KEY_BITRATE_KBPS,
                bitrateKbpsForPreset(current.bitratePreset, current.actualWidth, current.actualHeight, fps.fps, current.bitrateMbps),
            )
        }
    }

    fun setBitratePreset(store: PrefsStore, preset: BitratePreset, isGen1: Boolean) {
        val current = read(store, isGen1)
        store.putString(KEY_BITRATE_PRESET, preset.prefValue)
        val kbps = bitrateKbpsForPreset(preset, current.actualWidth, current.actualHeight, current.actualFps, current.bitrateMbps)
        store.putInt(KEY_BITRATE_KBPS, kbps)
    }

    fun setCustomBitrateMbps(store: PrefsStore, mbps: Int) {
        val clamped = mbps.coerceIn(MIN_BITRATE_MBPS, MAX_BITRATE_MBPS)
        store.putString(KEY_BITRATE_PRESET, BitratePreset.CUSTOM.prefValue)
        store.putInt(KEY_BITRATE_KBPS, clamped * 1000)
    }

    fun setCodec(store: PrefsStore, codec: StreamCodec) {
        store.putString(KEY_VIDEO_FORMAT, codec.prefValue)
    }

    fun setMouseMode(store: PrefsStore, mode: MouseMode) {
        store.putBoolean(KEY_ABSOLUTE_MOUSE, mode == MouseMode.ABSOLUTE)
    }

    fun setMultiController(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_MULTI_CONTROLLER, enabled)
    }

    fun setFlipFaceButtons(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_FLIP_FACE_BUTTONS, enabled)
    }

    fun setGamepadShortcut(store: PrefsStore, shortcut: GamepadToggleShortcut) {
        store.putString(KEY_GAMEPAD_TOGGLE, shortcut.prefValue)
    }

    fun setDepthStrength(store: PrefsStore, strength: DepthStrength) {
        val defaultSep = PreferenceConfiguration.defaultSeparation(PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH)
        when (strength) {
            DepthStrength.OFF -> store.putString(KEY_DEPTH_SOURCE, "off")
            DepthStrength.SOFT -> {
                store.putString(KEY_DEPTH_SOURCE, PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH)
                store.putInt(KEY_SEPARATION, DepthPresets.value(DepthPresets.COMFORT, defaultSep))
            }
            DepthStrength.STANDARD -> {
                store.putString(KEY_DEPTH_SOURCE, PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH)
                store.putInt(KEY_SEPARATION, DepthPresets.value(DepthPresets.BALANCED, defaultSep))
            }
            DepthStrength.STRONG -> {
                store.putString(KEY_DEPTH_SOURCE, PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH)
                store.putInt(KEY_SEPARATION, DepthPresets.value(DepthPresets.STRONG, defaultSep))
            }
        }
    }

    fun setDepthQuality(store: PrefsStore, quality: DepthQualityMode) {
        val rate = when (quality) {
            DepthQualityMode.SMOOTH -> XrShared.DEPTH_RATE_GEN1
            DepthQualityMode.QUALITY -> XrShared.DEPTH_RATE_DEFAULT
        }
        store.putInt(KEY_DEPTH_RATE, rate)
    }

    fun setSharpening(store: PrefsStore, enabled: Boolean) {
        store.putString(KEY_SHARPENING, if (enabled) "quality" else "off")
    }

    fun setSupersampling(store: PrefsStore, enabled: Boolean) {
        store.putString(KEY_SUPERSAMPLING, if (enabled) "normal" else "off")
    }

    fun setScreenSize(store: PrefsStore, preset: ScreenSizePreset) {
        store.putInt(KEY_SCREEN_SIZE, preset.decimeters)
    }

    fun setScreenDistance(store: PrefsStore, preset: ScreenDistancePreset) {
        store.putInt(KEY_SCREEN_DISTANCE, preset.decimeters)
    }

    fun setCurvedScreen(store: PrefsStore, curved: Boolean) {
        store.putInt(KEY_CURVATURE, if (curved) CURVED_DEFAULT else 0)
    }

    /** Continuous curvature 0..100% quantized in 5% steps (§3.8, §6.12). */
    fun setCurvaturePercent(store: PrefsStore, percent: Int) {
        store.putInt(KEY_CURVATURE, ScreenReadout.quantizeCurvature(percent))
    }

    /** Sets horizontal screen FOV (30°..90°) keeping current distance unchanged (§6.12). */
    fun setScreenFovDeg(store: PrefsStore, fovDeg: Float) {
        val distDm = store.getInt(KEY_SCREEN_DISTANCE, 30).coerceIn(20, 80)
        val distM = distDm / 10f
        val clampedFov = ScreenReadout.clampFov(fovDeg)
        val flatWidthDm = (ScreenReadout.flatWidthMeters(clampedFov, distM) * 10f).roundToInt().coerceIn(8, 80)
        store.putInt(KEY_SCREEN_SIZE, flatWidthDm)
    }

    /** Sets viewing distance (2.0..8.0 m) while keeping angular FOV constant (§6.12). */
    fun setScreenDistanceMeters(store: PrefsStore, meters: Float) {
        val prevDistDm = store.getInt(KEY_SCREEN_DISTANCE, 30).coerceIn(10, 80)
        val prevSizeDm = store.getInt(KEY_SCREEN_SIZE, 30).coerceIn(8, 80)
        val fovDeg = ScreenReadout.clampFov(
            ScreenReadout.fovDegFromFlatWidth(prevSizeDm / 10f, prevDistDm / 10f),
        )
        val newDistM = ScreenReadout.clampDist(meters)
        val newDistDm = (newDistM * 10f).roundToInt().coerceIn(20, 80)
        val newSizeDm = (ScreenReadout.flatWidthMeters(fovDeg, newDistM) * 10f).roundToInt().coerceIn(8, 80)
        store.putInt(KEY_SCREEN_DISTANCE, newDistDm)
        store.putInt(KEY_SCREEN_SIZE, newSizeDm)
    }

    /** Setting: "按住扳机调整屏幕" (§3.8, §6.4 Rule 12). */
    fun setTriggerScreenAdjust(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_TRIGGER_SCREEN_ADJUST, enabled)
    }

    fun setEnvironment(store: PrefsStore, preset: VrEnvironmentPreset) {
        EnvironmentIds.store(store.asSharedPreferences(), preset.envId)
    }

    fun setAmbilight(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_AMBILIGHT, enabled)
    }

    fun setDoffGrace(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_DOFF_GRACE, enabled)
    }

    fun resetScreenPose(store: PrefsStore) {
        store.remove(KEY_SCREEN_POSE)
    }

    fun setHandTracking(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_HAND_TRACKING, enabled)
    }

    fun setShowRay(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_SHOW_RAY, enabled)
    }

    fun setPointerSleep(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_POINTER_SLEEP, enabled)
    }

    fun setHeadAim(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_HEAD_AIM, enabled)
    }

    fun setClickSound(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_CLICK_SOUND, enabled)
    }

    fun setFileLog(store: PrefsStore, mode: FileLogMode) {
        store.putString(KEY_FILE_LOG, mode.prefValue)
    }

    /** Java-friendly helper for the in-VR Stream quick menu tab. */
    @JvmStatic
    fun readVrStreamCells(context: Context): IntArray {
        val snap = read(AndroidPrefsStore.defaultPrefs(context), PreferenceConfiguration.isXr2Gen1Headset())
        val resCell = snap.resolution?.ordinal ?: -1
        val bitrateCell = when (snap.bitratePreset) {
            BitratePreset.SMOOTH -> 0
            BitratePreset.AUTO -> 1
            BitratePreset.SHARP -> 2
            BitratePreset.CUSTOM -> -1
        }
        val mouseCell = if (snap.mouseMode == MouseMode.ABSOLUTE) 1 else 0
        return intArrayOf(resCell, bitrateCell, mouseCell)
    }

    @JvmStatic
    fun applyVrStreamResolutionCell(context: Context, cell: Int) {
        setResolution(
            AndroidPrefsStore.defaultPrefs(context),
            StreamResolution.fromCellIndex(cell),
            PreferenceConfiguration.isXr2Gen1Headset(),
        )
    }

    @JvmStatic
    fun applyVrStreamBitrateCell(context: Context, cell: Int) {
        setBitratePreset(
            AndroidPrefsStore.defaultPrefs(context),
            BitratePreset.fromVrQuickCell(cell),
            PreferenceConfiguration.isXr2Gen1Headset(),
        )
    }

    @JvmStatic
    fun applyVrStreamMouseCell(context: Context, cell: Int) {
        setMouseMode(
            AndroidPrefsStore.defaultPrefs(context),
            if (cell == 1) MouseMode.ABSOLUTE else MouseMode.RELATIVE,
        )
    }
}

/**
 * Decoder crash recovery state persisted in SharedPreferences("DecoderTombstone") by Game.java.
 * Replaces the removed upstream PcView/AppView decoder crash dialog with a clean Cast-page prompt.
 */
data class CrashRecoveryPrompt(
    val crashCount: Int,
    val autoResetApplied: Boolean,
)

object DecoderCrashRecovery {
    const val TOMBSTONE_PREFS_NAME = "DecoderTombstone"
    const val KEY_CRASH_COUNT = "CrashCount"
    const val KEY_LAST_NOTIFIED = "LastNotifiedCrashCount"
    const val KEY_LAST_AUTO_RESET = "LastAutoResetCrashCount"

    fun checkAndPrepare(
        tombstone: PrefsStore,
        streamingPrefs: PrefsStore,
        isGen1: Boolean,
    ): CrashRecoveryPrompt? {
        val crashCount = tombstone.getInt(KEY_CRASH_COUNT, 0)
        val lastNotified = tombstone.getInt(KEY_LAST_NOTIFIED, 0)
        if (crashCount <= 0 || crashCount == lastNotified) {
            return null
        }
        return if (crashCount % 3 == 0) {
            // M3: Run auto-reset at most once per crash count even across repeated onStart() calls
            // before the user dismisses the recovery card.
            if (tombstone.getInt(KEY_LAST_AUTO_RESET, 0) != crashCount) {
                PreferenceConfiguration.resetStreamingSettings(streamingPrefs.asSharedPreferences(), isGen1)
                tombstone.putInt(KEY_LAST_AUTO_RESET, crashCount)
            }
            CrashRecoveryPrompt(crashCount = crashCount, autoResetApplied = true)
        } else {
            CrashRecoveryPrompt(crashCount = crashCount, autoResetApplied = false)
        }
    }

    fun resetDefaultsAndClear(
        tombstone: PrefsStore,
        streamingPrefs: PrefsStore,
        isGen1: Boolean,
    ) {
        PreferenceConfiguration.resetStreamingSettings(streamingPrefs.asSharedPreferences(), isGen1)
        tombstone.putInt(KEY_CRASH_COUNT, 0)
        tombstone.putInt(KEY_LAST_NOTIFIED, 0)
        tombstone.putInt(KEY_LAST_AUTO_RESET, 0)
    }

    fun acknowledge(tombstone: PrefsStore, crashCount: Int) {
        tombstone.putInt(KEY_LAST_NOTIFIED, crashCount)
    }
}
