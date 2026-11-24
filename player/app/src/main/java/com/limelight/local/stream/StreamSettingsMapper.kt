package com.limelight.local.stream

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.limelight.binding.video.DepthPresets
import com.limelight.binding.video.EnvironmentIds
import com.limelight.binding.video.XrShared
import com.limelight.preferences.PreferenceConfiguration
import kotlin.math.roundToInt

/** Thin key-value store abstraction so settings mapping is 100% JVM unit-testable. */
interface PrefsStore {
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
    override fun getString(key: String, def: String?): String? = prefs.getString(key, def)
    override fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    override fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }

    companion object {
        fun defaultPrefs(context: Context): AndroidPrefsStore =
            AndroidPrefsStore(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))
    }
}

class MapPrefsStore : PrefsStore {
    private val map = LinkedHashMap<String, Any>()
    override fun getString(key: String, def: String?): String? = map[key] as? String ?: def
    override fun getInt(key: String, def: Int): Int = map[key] as? Int ?: def
    override fun getBoolean(key: String, def: Boolean): Boolean = map[key] as? Boolean ?: def
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    fun snapshot(): Map<String, Any> = LinkedHashMap(map)
}

enum class StreamResolution(val prefValue: String) {
    R720P(PreferenceConfiguration.RES_720P),
    R1080P(PreferenceConfiguration.RES_1080P),
    R1440P(PreferenceConfiguration.RES_1440P),
    R4K(PreferenceConfiguration.RES_4K);

    companion object {
        fun fromPref(value: String?): StreamResolution =
            entries.firstOrNull { it.prefValue == value } ?: R1440P
    }
}

enum class StreamFps(val fps: Int) {
    FPS_60(60),
    FPS_72(72),
    FPS_90(90);

    val prefValue: String get() = fps.toString()

    companion object {
        fun fromPref(value: String?, isGen1: Boolean): StreamFps {
            val parsed = value?.toIntOrNull()
            return entries.firstOrNull { it.fps == parsed } ?: if (isGen1) FPS_72 else FPS_72
        }
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
    }
}

enum class StreamCodec(val prefValue: String) {
    AUTO("auto"),
    H264("neverh265"),
    HEVC("forceh265");

    companion object {
        fun fromPref(value: String?): StreamCodec = when (value) {
            "neverh265" -> H264
            "forceh265" -> HEVC
            else -> AUTO
        }
    }
}

enum class MouseMode {
    RELATIVE,
    ABSOLUTE,
}

enum class GamepadToggleShortcut(val prefValue: String) {
    MENU_GRIP(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_MENU_GRIP),
    STICKS(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_STICKS),
    TRIGGERS_GRIPS(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_TRIGGERS_GRIPS);

    companion object {
        fun fromPref(value: String?): GamepadToggleShortcut =
            entries.firstOrNull { it.prefValue == value } ?: MENU_GRIP
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

data class Depth3dSettingsSnapshot(
    val resolution: StreamResolution,
    val fps: StreamFps,
    val bitratePreset: BitratePreset,
    val bitrateMbps: Int,
    val codec: StreamCodec,
    val mouseMode: MouseMode,
    val multiController: Boolean,
    val flipFaceButtons: Boolean,
    val gamepadShortcut: GamepadToggleShortcut,
    val depthStrength: DepthStrength,
    val depthQuality: DepthQualityMode,
    val sharpening: Boolean,
    val supersampling: Boolean,
    val screenSize: ScreenSizePreset,
    val screenDistance: ScreenDistancePreset,
    val curvedScreen: Boolean,
    val environment: VrEnvironmentPreset,
    val ambilight: Boolean,
    val doffGrace: Boolean,
    val handTracking: Boolean,
    val showRay: Boolean,
    val pointerSleep: Boolean,
    val headAim: Boolean,
    val clickSound: Boolean,
    val fileLog: FileLogMode,
    val crashReporting: Boolean,
    val isGen1Headset: Boolean,
) {
    val warn3dLag: Boolean
        get() = StreamSettingsMapper.shouldWarn3dLag(resolution, fps)
}

/**
 * Translates between depth3d's Compose settings UI and [PreferenceConfiguration]'s
 * exact SharedPreferences keys so all changes take immediate effect in VR streaming & playback.
 */
object StreamSettingsMapper {
    const val KEY_RESOLUTION = "list_resolution"
    const val KEY_FPS = "list_fps"
    const val KEY_UNLOCK_FPS = "checkbox_unlock_fps"
    const val KEY_BITRATE_KBPS = "seekbar_bitrate_kbps"
    const val KEY_BITRATE_PRESET = "depth3d_bitrate_preset"
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

    const val KEY_SCREEN_SIZE = "seekbar_vr_screen_size"
    const val KEY_SCREEN_DISTANCE = "seekbar_vr_distance"
    const val KEY_CURVATURE = "seekbar_vr_curvature"
    const val KEY_ENV_ID = PreferenceConfiguration.VR_ENVIRONMENT_ID_PREF_STRING
    const val KEY_PASSTHROUGH = PreferenceConfiguration.VR_PASSTHROUGH_PREF_STRING
    const val KEY_AMBILIGHT = PreferenceConfiguration.VR_AMBILIGHT_PREF_STRING
    const val KEY_DOFF_GRACE = PreferenceConfiguration.VR_DOFF_GRACE_PREF_STRING
    const val KEY_SCREEN_POSE = PreferenceConfiguration.VR_SCREEN_POSE_PREF_STRING

    const val KEY_HAND_TRACKING = "checkbox_vr_hand_tracking"
    const val KEY_SHOW_RAY = PreferenceConfiguration.VR_SHOW_RAY_PREF_STRING
    const val KEY_POINTER_SLEEP = PreferenceConfiguration.VR_POINTER_SLEEP_PREF_STRING
    const val KEY_HEAD_AIM = PreferenceConfiguration.VR_HEAD_AIM_PREF_STRING
    const val KEY_CLICK_SOUND = PreferenceConfiguration.VR_CLICK_SOUND_PREF_STRING

    const val KEY_FILE_LOG = PreferenceConfiguration.FILE_LOG_PREF_STRING
    const val KEY_CRASH_REPORTING = "depth3d_crash_reporting"

    const val MIN_BITRATE_MBPS = 5
    const val MAX_BITRATE_MBPS = 150
    private const val CURVED_DEFAULT = 45

    /** Spec: warn 「3D 会变卡」 when 4K or 90 fps is selected. */
    fun shouldWarn3dLag(resolution: StreamResolution, fps: StreamFps): Boolean =
        resolution == StreamResolution.R4K || fps == StreamFps.FPS_90

    /** Computes target bitrate in kbps for a preset + resolution + fps combination. */
    fun bitrateKbpsForPreset(
        preset: BitratePreset,
        resolution: StreamResolution,
        fps: StreamFps,
        customMbps: Int,
    ): Int {
        val base = PreferenceConfiguration.getDefaultBitrate(resolution.prefValue, fps.prefValue)
        val raw = when (preset) {
            BitratePreset.AUTO -> base
            BitratePreset.SMOOTH -> (base * 0.65f).roundToInt()
            BitratePreset.SHARP -> (base * 1.45f).roundToInt()
            BitratePreset.CUSTOM -> customMbps * 1000
        }
        return raw.coerceIn(MIN_BITRATE_MBPS * 1000, MAX_BITRATE_MBPS * 1000)
    }

    fun read(store: PrefsStore, isGen1Headset: Boolean): Depth3dSettingsSnapshot {
        val defaultRes = StreamResolution.R1440P
        val defaultFps = if (isGen1Headset) StreamFps.FPS_72 else StreamFps.FPS_72
        val resolution = if (store.contains(KEY_RESOLUTION)) {
            StreamResolution.fromPref(store.getString(KEY_RESOLUTION, defaultRes.prefValue))
        } else {
            defaultRes
        }
        val fps = if (store.contains(KEY_FPS)) {
            StreamFps.fromPref(store.getString(KEY_FPS, defaultFps.prefValue), isGen1Headset)
        } else {
            defaultFps
        }
        val bitratePreset = BitratePreset.fromPref(store.getString(KEY_BITRATE_PRESET, BitratePreset.AUTO.prefValue))
        val defaultKbps = PreferenceConfiguration.getDefaultBitrate(resolution.prefValue, fps.prefValue)
        val storedKbps = if (store.contains(KEY_BITRATE_KBPS)) {
            store.getInt(KEY_BITRATE_KBPS, defaultKbps)
        } else {
            defaultKbps
        }
        val bitrateMbps = (storedKbps / 1000f).roundToInt().coerceIn(MIN_BITRATE_MBPS, MAX_BITRATE_MBPS)

        val codec = StreamCodec.fromPref(store.getString(KEY_VIDEO_FORMAT, StreamCodec.AUTO.prefValue))
        val mouseMode = if (store.getBoolean(KEY_ABSOLUTE_MOUSE, false)) MouseMode.ABSOLUTE else MouseMode.RELATIVE
        val multiController = store.getBoolean(KEY_MULTI_CONTROLLER, true)
        val flipFaceButtons = store.getBoolean(KEY_FLIP_FACE_BUTTONS, false)
        val gamepadShortcut = GamepadToggleShortcut.fromPref(
            store.getString(KEY_GAMEPAD_TOGGLE, PreferenceConfiguration.VR_GAMEPAD_TOGGLE_MENU_GRIP)
        )

        val depthSource = store.getString(KEY_DEPTH_SOURCE, PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH)
        val defaultSep = PreferenceConfiguration.defaultSeparation(PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH)
        val storedSep = store.getInt(KEY_SEPARATION, defaultSep)
        val depthStrength = when {
            depthSource == "off" -> DepthStrength.OFF
            storedSep <= DepthPresets.value(DepthPresets.COMFORT, defaultSep) -> DepthStrength.SOFT
            storedSep >= DepthPresets.value(DepthPresets.STRONG, defaultSep) -> DepthStrength.STRONG
            else -> DepthStrength.STANDARD
        }

        val defaultRate = if (isGen1Headset) XrShared.DEPTH_RATE_GEN1 else XrShared.DEPTH_RATE_DEFAULT
        val storedRate = store.getInt(KEY_DEPTH_RATE, defaultRate)
        val depthQuality = if (storedRate >= XrShared.DEPTH_RATE_DEFAULT) DepthQualityMode.QUALITY else DepthQualityMode.SMOOTH

        val sharpening = store.getString(KEY_SHARPENING, "quality") != "off"
        val supersampling = store.getString(KEY_SUPERSAMPLING, "off") != "off"

        val screenSize = ScreenSizePreset.fromDecimeters(store.getInt(KEY_SCREEN_SIZE, 30))
        val screenDistance = ScreenDistancePreset.fromDecimeters(store.getInt(KEY_SCREEN_DISTANCE, 30))
        val curvedScreen = store.getInt(KEY_CURVATURE, 0) > 0
        val envId = if (store.contains(KEY_ENV_ID)) {
            VrEnvironmentPreset.fromEnvId(store.getInt(KEY_ENV_ID, PreferenceConfiguration.VR_ENV_VOID)).envId
        } else if (store.getBoolean(KEY_PASSTHROUGH, false)) {
            PreferenceConfiguration.VR_ENV_PASSTHROUGH
        } else {
            PreferenceConfiguration.VR_ENV_VOID
        }
        val environment = VrEnvironmentPreset.fromEnvId(envId)
        val ambilight = store.getBoolean(KEY_AMBILIGHT, PreferenceConfiguration.DEFAULT_VR_AMBILIGHT)
        val doffGrace = store.getBoolean(KEY_DOFF_GRACE, true)

        val handTracking = store.getBoolean(KEY_HAND_TRACKING, true)
        val showRay = store.getBoolean(KEY_SHOW_RAY, true)
        val pointerSleep = store.getBoolean(KEY_POINTER_SLEEP, true)
        val headAim = store.getBoolean(KEY_HEAD_AIM, false)
        val clickSound = store.getBoolean(KEY_CLICK_SOUND, true)

        val fileLog = FileLogMode.fromPref(store.getString(KEY_FILE_LOG, PreferenceConfiguration.DEFAULT_FILE_LOG))
        val crashReporting = store.getBoolean(KEY_CRASH_REPORTING, false)

        return Depth3dSettingsSnapshot(
            resolution = resolution,
            fps = fps,
            bitratePreset = bitratePreset,
            bitrateMbps = bitrateMbps,
            codec = codec,
            mouseMode = mouseMode,
            multiController = multiController,
            flipFaceButtons = flipFaceButtons,
            gamepadShortcut = gamepadShortcut,
            depthStrength = depthStrength,
            depthQuality = depthQuality,
            sharpening = sharpening,
            supersampling = supersampling,
            screenSize = screenSize,
            screenDistance = screenDistance,
            curvedScreen = curvedScreen,
            environment = environment,
            ambilight = ambilight,
            doffGrace = doffGrace,
            handTracking = handTracking,
            showRay = showRay,
            pointerSleep = pointerSleep,
            headAim = headAim,
            clickSound = clickSound,
            fileLog = fileLog,
            crashReporting = crashReporting,
            isGen1Headset = isGen1Headset,
        )
    }

    fun setResolution(store: PrefsStore, resolution: StreamResolution, isGen1: Boolean) {
        val current = read(store, isGen1)
        store.putString(KEY_RESOLUTION, resolution.prefValue)
        if (current.bitratePreset != BitratePreset.CUSTOM) {
            store.putInt(
                KEY_BITRATE_KBPS,
                bitrateKbpsForPreset(current.bitratePreset, resolution, current.fps, current.bitrateMbps),
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
                bitrateKbpsForPreset(current.bitratePreset, current.resolution, fps, current.bitrateMbps),
            )
        }
    }

    fun setBitratePreset(store: PrefsStore, preset: BitratePreset, isGen1: Boolean) {
        val current = read(store, isGen1)
        store.putString(KEY_BITRATE_PRESET, preset.prefValue)
        val kbps = bitrateKbpsForPreset(preset, current.resolution, current.fps, current.bitrateMbps)
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

    fun setEnvironment(store: PrefsStore, preset: VrEnvironmentPreset) {
        val id = VrEnvironmentPreset.fromEnvId(preset.envId).envId
        store.putInt(KEY_ENV_ID, id)
        store.putBoolean(KEY_PASSTHROUGH, id == PreferenceConfiguration.VR_ENV_PASSTHROUGH)
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

    fun setCrashReporting(store: PrefsStore, enabled: Boolean) {
        store.putBoolean(KEY_CRASH_REPORTING, enabled)
    }
}
