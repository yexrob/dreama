package com.limelight.local

import android.content.Context
import com.limelight.binding.video.EnvironmentIds
import com.limelight.local.stream.AndroidPrefsStore
import com.limelight.local.stream.DepthStrength
import com.limelight.local.stream.PrefsStore
import com.limelight.local.stream.StreamSettingsMapper
import com.limelight.local.stream.VrEnvironmentPreset
import com.limelight.preferences.PreferenceConfiguration
import kotlin.math.roundToInt

/**
 * Persistence boundary for the VR Quick Palette (ux-spec.md §6.3):
 * - **Per-video overrides** (3D 强度, 3D 片源, 字幕轨, 音轨 + 「只改这部片子」 notice flag)
 *   are isolated per `videoKey` inside `depth3d_video_overrides`.
 * - **Global settings** (漂浮感, 层次, 屏幕大小/距离/曲度/环境, 弹幕偏好, 字幕字号/缩放/位置,
 *   调色板左右手) live in default SharedPreferences and stay in sync with 2D Settings.
 *
 * Uses [PrefsStore] so 100% of persistence & isolation logic is verified on JVM without Robolectric.
 */
object PaletteStore {
    const val VIDEO_OVERRIDES_PREFS = "depth3d_video_overrides"

    // Global palette-only keys (screen/3D/env/hand keys reuse PreferenceConfiguration constants)
    const val KEY_FLOAT_AUTO = "vr_palette_float_auto"
    const val KEY_FLOAT_AUTO_OFFSET = "vr_palette_float_auto_offset"
    const val KEY_FLOAT_FIXED_STEPS = "vr_palette_float_fixed_steps"
    const val KEY_DEPTH_LAYER = "vr_palette_depth_layer"
    const val KEY_LAST_ROOM_ENV_ID = "vr_palette_last_room_env_id"

    const val KEY_DANMAKU_ON = "vr_danmaku_on"
    const val KEY_DANMAKU_AREA = "vr_danmaku_area"
    const val KEY_DANMAKU_OPACITY = "vr_danmaku_opacity"
    const val KEY_DANMAKU_SIZE = "vr_danmaku_size"
    const val KEY_DANMAKU_SPEED = "vr_danmaku_speed"
    const val KEY_DANMAKU_BLOCK_MASK = "vr_danmaku_block_mask"

    const val KEY_SUB_TEXT_SIZE = "vr_sub_text_size"
    const val KEY_SUB_BITMAP_SCALE = "vr_sub_bitmap_scale"
    const val KEY_SUB_POS_TENTHS = "vr_sub_pos_tenths"

    @JvmStatic
    fun defaultGlobalStore(context: Context): PrefsStore = AndroidPrefsStore.defaultPrefs(context)

    @JvmStatic
    fun defaultVideoStore(context: Context): PrefsStore =
        AndroidPrefsStore.namedPrefs(context, VIDEO_OVERRIDES_PREFS)

    /** Builds a deterministic per-video key from source/item IDs or fallback URI. */
    @JvmStatic
    fun videoKey(sourceId: String?, itemId: String?, uriString: String?): String {
        val s = sourceId?.trim().orEmpty()
        val i = itemId?.trim().orEmpty()
        if (s.isNotEmpty() && i.isNotEmpty()) {
            return "$s:$i"
        }
        val u = uriString?.trim().orEmpty()
        return if (u.isNotEmpty()) "uri:$u" else "anonymous"
    }

    @JvmStatic
    fun readGlobal(store: PrefsStore, isGen1: Boolean = false): GlobalPaletteSettings {
        val snap = StreamSettingsMapper.read(store, isGen1)
        val passthroughDim = store.getBoolean(
            PreferenceConfiguration.VR_PASSTHROUGH_DIM_PREF_STRING,
            PreferenceConfiguration.DEFAULT_VR_PASSTHROUGH_DIM,
        )
        val env = when (snap.environment) {
            VrEnvironmentPreset.PASSTHROUGH ->
                if (passthroughDim) PaletteEnvironment.DIM else PaletteEnvironment.PASSTHROUGH
            VrEnvironmentPreset.VOID -> PaletteEnvironment.VOID
            VrEnvironmentPreset.HOME_THEATER,
            VrEnvironmentPreset.GRAND_CINEMA,
            VrEnvironmentPreset.SYNTHWAVE -> PaletteEnvironment.THEATER
        }
        return GlobalPaletteSettings(
            defaultStrength = snap.depthStrength,
            floatAuto = store.getBoolean(KEY_FLOAT_AUTO, true),
            floatAutoOffsetSteps = store.getInt(KEY_FLOAT_AUTO_OFFSET, 0)
                .coerceIn(PaletteController.FLOAT_AUTO_MIN_STEPS, PaletteController.FLOAT_AUTO_MAX_STEPS),
            floatFixedSteps = store.getInt(KEY_FLOAT_FIXED_STEPS, 0)
                .coerceIn(0, PaletteController.FLOAT_FIXED_MAX_STEPS),
            depthLayer = DepthLayerLevel.fromOrdinal(store.getInt(KEY_DEPTH_LAYER, DepthLayerLevel.MEDIUM.ordinal)),
            screenFovDeg = snap.screenFovDeg,
            screenDistM = snap.actualScreenDistanceDm / 10f,
            screenCurvaturePct = snap.actualCurvature,
            environment = env,
            danmakuOn = store.getBoolean(KEY_DANMAKU_ON, true),
            danmakuArea = DanmakuArea.fromOrdinal(store.getInt(KEY_DANMAKU_AREA, DanmakuArea.HALF.ordinal)),
            danmakuOpacityPct = store.getInt(KEY_DANMAKU_OPACITY, PaletteController.DEFAULT_DANMAKU_OPACITY)
                .coerceIn(20, 100),
            danmakuSize = TextSizeTier.fromOrdinal(store.getInt(KEY_DANMAKU_SIZE, TextSizeTier.MEDIUM.ordinal)),
            danmakuSpeed = DanmakuSpeed.fromOrdinal(store.getInt(KEY_DANMAKU_SPEED, DanmakuSpeed.NORMAL.ordinal)),
            danmakuBlockMask = store.getInt(KEY_DANMAKU_BLOCK_MASK, 0) and DanmakuBlockFlags.ALL_MASK,
            subtitleTextSize = TextSizeTier.fromOrdinal(store.getInt(KEY_SUB_TEXT_SIZE, TextSizeTier.MEDIUM.ordinal)),
            subtitleBitmapScalePct = store.getInt(KEY_SUB_BITMAP_SCALE, PaletteController.DEFAULT_SUB_BITMAP_SCALE)
                .coerceIn(70, 150),
            subtitlePosDeg = (store.getInt(KEY_SUB_POS_TENTHS, 20).coerceIn(10, 120)) / 10f,
            paletteOnRightHand = store.getBoolean(
                PreferenceConfiguration.VR_PALETTE_HAND_RIGHT_PREF_STRING,
                PreferenceConfiguration.DEFAULT_VR_PALETTE_HAND_RIGHT,
            ),
        )
    }

    @JvmStatic
    fun saveGlobal(store: PrefsStore, settings: GlobalPaletteSettings) {
        store.putBoolean(KEY_FLOAT_AUTO, settings.floatAuto)
        store.putInt(KEY_FLOAT_AUTO_OFFSET, settings.floatAutoOffsetSteps)
        store.putInt(KEY_FLOAT_FIXED_STEPS, settings.floatFixedSteps)
        store.putInt(KEY_DEPTH_LAYER, settings.depthLayer.ordinal)

        // Sync Screen tab directly with 2D Settings keys
        StreamSettingsMapper.setScreenDistanceMeters(store, settings.screenDistM)
        StreamSettingsMapper.setScreenFovDeg(store, settings.screenFovDeg)
        StreamSettingsMapper.setCurvaturePercent(store, settings.screenCurvaturePct)

        // Environment mapping: remember last-used 3D room when entering/leaving 影院
        val currentEnvId = EnvironmentIds.listValue(store.asSharedPreferences()).toIntOrNull()
            ?: PreferenceConfiguration.VR_ENV_VOID
        if (PreferenceConfiguration.isRoomEnvironment(currentEnvId)) {
            store.putInt(KEY_LAST_ROOM_ENV_ID, currentEnvId)
        }
        when (settings.environment) {
            PaletteEnvironment.PASSTHROUGH -> {
                store.putBoolean(PreferenceConfiguration.VR_PASSTHROUGH_DIM_PREF_STRING, false)
                StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.PASSTHROUGH)
            }
            PaletteEnvironment.DIM -> {
                store.putBoolean(PreferenceConfiguration.VR_PASSTHROUGH_DIM_PREF_STRING, true)
                StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.PASSTHROUGH)
            }
            PaletteEnvironment.THEATER -> {
                store.putBoolean(PreferenceConfiguration.VR_PASSTHROUGH_DIM_PREF_STRING, false)
                val roomId = rememberedRoomEnvId(store)
                StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.fromEnvId(roomId))
            }
            PaletteEnvironment.VOID -> {
                store.putBoolean(PreferenceConfiguration.VR_PASSTHROUGH_DIM_PREF_STRING, false)
                StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.VOID)
            }
        }

        // Danmaku & subtitle global prefs
        store.putBoolean(KEY_DANMAKU_ON, settings.danmakuOn)
        store.putInt(KEY_DANMAKU_AREA, settings.danmakuArea.ordinal)
        store.putInt(KEY_DANMAKU_OPACITY, settings.danmakuOpacityPct)
        store.putInt(KEY_DANMAKU_SIZE, settings.danmakuSize.ordinal)
        store.putInt(KEY_DANMAKU_SPEED, settings.danmakuSpeed.ordinal)
        store.putInt(KEY_DANMAKU_BLOCK_MASK, settings.danmakuBlockMask and DanmakuBlockFlags.ALL_MASK)

        store.putInt(KEY_SUB_TEXT_SIZE, settings.subtitleTextSize.ordinal)
        store.putInt(KEY_SUB_BITMAP_SCALE, settings.subtitleBitmapScalePct)
        store.putInt(KEY_SUB_POS_TENTHS, (settings.subtitlePosDeg * 10f).roundToInt())
    }

    /** Resolves which OpenXR environment cell ID `PaletteEnvironment.THEATER` activates. */
    @JvmStatic
    fun rememberedRoomEnvId(store: PrefsStore): Int {
        val current = EnvironmentIds.listValue(store.asSharedPreferences()).toIntOrNull() ?: -1
        if (PreferenceConfiguration.isRoomEnvironment(current)) return current
        val stored = store.getInt(KEY_LAST_ROOM_ENV_ID, PreferenceConfiguration.VR_ENV_HOME_THEATER)
        return if (PreferenceConfiguration.isRoomEnvironment(stored)) stored
        else PreferenceConfiguration.VR_ENV_HOME_THEATER
    }

    /** Resolves the native ENV_CELL_* index for a [PaletteEnvironment]. */
    @JvmStatic
    fun envCellFor(store: PrefsStore, env: PaletteEnvironment): Int = when (env) {
        PaletteEnvironment.PASSTHROUGH,
        PaletteEnvironment.DIM -> EnvironmentIds.cellForId(PreferenceConfiguration.VR_ENV_PASSTHROUGH)
        PaletteEnvironment.THEATER -> EnvironmentIds.cellForId(rememberedRoomEnvId(store))
        PaletteEnvironment.VOID -> EnvironmentIds.cellForId(PreferenceConfiguration.VR_ENV_VOID)
    }

    @JvmStatic
    fun readVideoOverrides(videoStore: PrefsStore, videoKey: String): VideoPaletteOverrides {
        val prefix = "$videoKey|"
        val strengthKey = "${prefix}strength"
        val sourceKey = "${prefix}source"
        val subKey = "${prefix}sub"
        val audioKey = "${prefix}audio"
        val noticeKey = "${prefix}strength_notice"

        val strength = if (videoStore.contains(strengthKey)) {
            val ord = videoStore.getInt(strengthKey, -1)
            DepthStrength.entries.getOrNull(ord)
        } else null
        val sourceFormat = if (videoStore.contains(sourceKey)) {
            StereoSourceFormat.fromCode(videoStore.getInt(sourceKey, StereoSourceFormat.AUTO.nativeCode))
        } else null
        val subId = videoStore.getString(subKey, null)
        val audioId = videoStore.getString(audioKey, null)
        val noticeShown = videoStore.getBoolean(noticeKey, false)
        return VideoPaletteOverrides(
            strength = strength,
            sourceFormat = sourceFormat,
            subtitleId = subId,
            audioId = audioId,
            strengthNoticeShown = noticeShown,
        )
    }

    @JvmStatic
    fun saveVideoOverrides(videoStore: PrefsStore, videoKey: String, overrides: VideoPaletteOverrides) {
        val prefix = "$videoKey|"
        val strengthKey = "${prefix}strength"
        val sourceKey = "${prefix}source"
        val subKey = "${prefix}sub"
        val audioKey = "${prefix}audio"
        val noticeKey = "${prefix}strength_notice"

        if (overrides.strength != null) videoStore.putInt(strengthKey, overrides.strength.ordinal)
        else videoStore.remove(strengthKey)

        if (overrides.sourceFormat != null) videoStore.putInt(sourceKey, overrides.sourceFormat.nativeCode)
        else videoStore.remove(sourceKey)

        if (overrides.subtitleId != null) videoStore.putString(subKey, overrides.subtitleId)
        else videoStore.remove(subKey)

        if (overrides.audioId != null) videoStore.putString(audioKey, overrides.audioId)
        else videoStore.remove(audioKey)

        if (overrides.strengthNoticeShown) videoStore.putBoolean(noticeKey, true)
        else videoStore.remove(noticeKey)
    }
}
