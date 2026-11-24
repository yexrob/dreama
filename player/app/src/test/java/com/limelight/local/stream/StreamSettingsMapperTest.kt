package com.limelight.local.stream

import com.limelight.binding.video.DepthPresets
import com.limelight.binding.video.EnvironmentIds
import com.limelight.binding.video.XrShared
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class StreamSettingsMapperTest {

    /**
     * Core invariant (review #2): every displayed value produced by StreamSettingsMapper.read
     * must exactly match the effective value read by PreferenceConfiguration.readPreferences.
     */
    private fun assertExactParityWithPreferenceConfiguration(store: MapPrefsStore, isGen1: Boolean) {
        val snap = StreamSettingsMapper.read(store, isGen1)
        val cfg = PreferenceConfiguration.readPreferences(store, isGen1)

        assertEquals("width mismatch", cfg.width, snap.actualWidth)
        assertEquals("height mismatch", cfg.height, snap.actualHeight)
        snap.resolution?.let {
            assertEquals(cfg.width, it.width)
            assertEquals(cfg.height, it.height)
            assertNull(snap.customResolutionLabel)
        } ?: assertEquals("${cfg.width}×${cfg.height}", snap.customResolutionLabel)

        assertEquals("fps mismatch", cfg.fps, snap.actualFps)
        snap.fps?.let {
            assertEquals(cfg.fps, it.fps)
            assertNull(snap.customFpsLabel)
        } ?: assertEquals("${cfg.fps} fps", snap.customFpsLabel)

        assertEquals("bitrate kbps mismatch", cfg.bitrate, snap.actualBitrateKbps)
        assertEquals("bitrate Mbps mismatch", (cfg.bitrate / 1000f).roundToInt().coerceAtLeast(1), snap.bitrateMbps)

        assertEquals("codec mismatch", cfg.videoFormat, snap.actualCodec)
        snap.codec?.let {
            assertEquals(cfg.videoFormat, it.formatOption)
            assertNull(snap.customCodecLabel)
        }

        assertEquals(
            if (cfg.absoluteMouseMode) MouseMode.ABSOLUTE else MouseMode.RELATIVE,
            snap.mouseMode,
        )
        assertEquals(cfg.multiController, snap.multiController)
        assertEquals(cfg.flipFaceButtons, snap.flipFaceButtons)
        assertEquals(GamepadToggleShortcut.fromXrCode(cfg.vrGamepadToggle), snap.gamepadShortcut)

        assertEquals(cfg.vrDepthRate, snap.actualDepthRate)
        assertEquals(cfg.vrDepthRate >= XrShared.DEPTH_RATE_DEFAULT, snap.depthQuality == DepthQualityMode.QUALITY)
        assertEquals(cfg.vrDepthMode == XrShared.DEPTH_MODE_OFF, snap.depthStrength == DepthStrength.OFF)
        assertEquals(cfg.vrSharpening != 0, snap.sharpening)
        assertEquals(cfg.vrSupersampling != 0, snap.supersampling)

        assertEquals(cfg.vrScreenSize, snap.actualScreenSizeDm)
        assertEquals(cfg.vrDistance, snap.actualScreenDistanceDm)
        assertEquals(cfg.vrCurvature, snap.actualCurvature)
        assertEquals(cfg.vrCurvature > 0, snap.curvedScreen)

        val expectedEnvId = EnvironmentIds.listValue(store).toIntOrNull() ?: PreferenceConfiguration.VR_ENV_VOID
        assertEquals(VrEnvironmentPreset.fromEnvId(expectedEnvId), snap.environment)

        assertEquals(cfg.vrAmbilight, snap.ambilight)
        assertEquals(cfg.vrDoffGrace, snap.doffGrace)
        assertEquals(cfg.vrHandTracking, snap.handTracking)
        assertEquals(cfg.vrShowRay, snap.showRay)
        assertEquals(cfg.vrPointerSleep, snap.pointerSleep)
        assertEquals(cfg.vrHeadAim, snap.headAim)
        assertEquals(cfg.vrClickSound, snap.clickSound)
        assertEquals(cfg.fileLogLevel, snap.fileLog.prefValue)

        // 3D lag warning must match the REAL effective width/height/fps
        assertEquals(
            cfg.width >= 3840 || cfg.height >= 2160 || cfg.fps >= 90,
            snap.warn3dLag,
        )
    }

    @Test
    fun freshStoreDefaultsExactlyMatchPreferenceConfiguration() {
        val store = MapPrefsStore()
        // Non-Gen1 default FPS in PreferenceConfiguration is 90 (not 72!).
        val snap = StreamSettingsMapper.read(store, isGen1Headset = false)
        assertExactParityWithPreferenceConfiguration(store, isGen1 = false)

        assertEquals(StreamResolution.R1440P, snap.resolution)
        assertEquals(StreamFps.FPS_90, snap.fps)
        assertEquals(90, snap.actualFps)
        assertTrue("90 fps default should trigger 3D lag warning", snap.warn3dLag)
        assertEquals(BitratePreset.AUTO, snap.bitratePreset)
        assertEquals(StreamCodec.AUTO, snap.codec)
        assertEquals(MouseMode.RELATIVE, snap.mouseMode)
        assertTrue(snap.multiController)
        assertFalse(snap.flipFaceButtons)
        assertEquals(GamepadToggleShortcut.MENU_GRIP, snap.gamepadShortcut)
        assertEquals(DepthStrength.STANDARD, snap.depthStrength)
        assertEquals(DepthQualityMode.QUALITY, snap.depthQuality)
        assertTrue(snap.sharpening)
        assertFalse(snap.supersampling)
        assertEquals(ScreenSizePreset.MEDIUM, snap.screenSize)
        assertEquals(ScreenDistancePreset.MEDIUM, snap.screenDistance)
        assertFalse(snap.curvedScreen)
        assertEquals(VrEnvironmentPreset.VOID, snap.environment)
        assertTrue(snap.doffGrace)
        assertTrue(snap.handTracking)
        assertTrue(snap.showRay)
        assertTrue(snap.pointerSleep)
        assertFalse(snap.headAim)
        assertTrue(snap.clickSound)
        assertEquals(FileLogMode.BASIC, snap.fileLog)

        // Gen1 headset without explicit FPS still matches PreferenceConfiguration's effective reader
        assertExactParityWithPreferenceConfiguration(store, isGen1 = true)
        val gen1 = StreamSettingsMapper.read(store, isGen1Headset = true)
        assertEquals(DepthQualityMode.SMOOTH, gen1.depthQuality)

        // When Gen1 perf profile seeds 72 fps (as Depth3dApp does on first launch), UI shows 72 fps
        store.putString(StreamSettingsMapper.KEY_FPS, "72")
        val gen1Seeded = StreamSettingsMapper.read(store, isGen1Headset = true)
        assertEquals(StreamFps.FPS_72, gen1Seeded.fps)
        assertFalse(gen1Seeded.warn3dLag)
        assertExactParityWithPreferenceConfiguration(store, isGen1 = true)
    }

    @Test
    fun unknownStoredValuesAreDisplayedTruthfullyWithoutCoercion() {
        val store = MapPrefsStore()
        // Unknown resolution 854x480, unknown FPS 120, AV1 codec, custom bitrate 42 Mbps
        store.putString(StreamSettingsMapper.KEY_RESOLUTION, "854x480")
        store.putString(StreamSettingsMapper.KEY_FPS, "120")
        store.putString(StreamSettingsMapper.KEY_VIDEO_FORMAT, "forceav1")
        store.putInt(StreamSettingsMapper.KEY_BITRATE_KBPS, 42_000)

        val snap = StreamSettingsMapper.read(store, isGen1Headset = false)
        assertExactParityWithPreferenceConfiguration(store, isGen1 = false)

        assertNull("Unknown resolution must not be coerced to 1440p", snap.resolution)
        assertEquals(854, snap.actualWidth)
        assertEquals(480, snap.actualHeight)
        assertEquals("854×480", snap.customResolutionLabel)

        assertNull("Unknown 120 fps must not be coerced to 72 fps", snap.fps)
        assertEquals(120, snap.actualFps)
        assertEquals("120 fps", snap.customFpsLabel)
        assertTrue("120 fps must trigger 3D lag warning", snap.warn3dLag)

        assertNull("AV1 must not be coerced to Auto", snap.codec)
        assertEquals("AV1", snap.customCodecLabel)
        assertEquals(BitratePreset.CUSTOM, snap.bitratePreset)
        assertEquals(42, snap.bitrateMbps)

        // Low custom FPS (30) should NOT trigger 3D lag warning
        store.putString(StreamSettingsMapper.KEY_FPS, "30")
        val snap30 = StreamSettingsMapper.read(store, isGen1Headset = false)
        assertNull(snap30.fps)
        assertEquals(30, snap30.actualFps)
        assertEquals("30 fps", snap30.customFpsLabel)
        assertFalse(snap30.warn3dLag)
        assertExactParityWithPreferenceConfiguration(store, isGen1 = false)
    }

    @Test
    fun warn3dLagTriggersOnlyOn4kOr90Fps() {
        assertFalse(StreamSettingsMapper.shouldWarn3dLag(StreamResolution.R720P, StreamFps.FPS_60))
        assertFalse(StreamSettingsMapper.shouldWarn3dLag(StreamResolution.R1080P, StreamFps.FPS_72))
        assertFalse(StreamSettingsMapper.shouldWarn3dLag(StreamResolution.R1440P, StreamFps.FPS_72))

        assertTrue(StreamSettingsMapper.shouldWarn3dLag(StreamResolution.R4K, StreamFps.FPS_60))
        assertTrue(StreamSettingsMapper.shouldWarn3dLag(StreamResolution.R4K, StreamFps.FPS_72))
        assertTrue(StreamSettingsMapper.shouldWarn3dLag(StreamResolution.R1080P, StreamFps.FPS_90))
        assertTrue(StreamSettingsMapper.shouldWarn3dLag(StreamResolution.R1440P, StreamFps.FPS_90))
        assertTrue(StreamSettingsMapper.shouldWarn3dLag(1920, 1080, 120))
    }

    @Test
    fun fpsAndResolutionSyncUnlockFpsAndAutoBitrate() {
        val store = MapPrefsStore()

        StreamSettingsMapper.setFps(store, StreamFps.FPS_90, isGen1 = false)
        assertEquals("90", store.getString(StreamSettingsMapper.KEY_FPS, null))
        assertTrue(store.getBoolean(StreamSettingsMapper.KEY_UNLOCK_FPS, false))
        assertTrue(StreamSettingsMapper.read(store, false).warn3dLag)
        assertExactParityWithPreferenceConfiguration(store, false)

        StreamSettingsMapper.setFps(store, StreamFps.FPS_60, isGen1 = false)
        assertFalse(store.getBoolean(StreamSettingsMapper.KEY_UNLOCK_FPS, true))
        assertExactParityWithPreferenceConfiguration(store, false)

        // Custom bitrate is preserved when changing resolution
        StreamSettingsMapper.setCustomBitrateMbps(store, 85)
        assertEquals(85_000, store.getInt(StreamSettingsMapper.KEY_BITRATE_KBPS, 0))
        StreamSettingsMapper.setResolution(store, StreamResolution.R4K, isGen1 = false)
        assertEquals(85_000, store.getInt(StreamSettingsMapper.KEY_BITRATE_KBPS, 0))
        assertExactParityWithPreferenceConfiguration(store, false)

        // Out-of-bounds custom bitrate clamps cleanly to [5, 150] Mbps
        StreamSettingsMapper.setCustomBitrateMbps(store, 1)
        assertEquals(5_000, store.getInt(StreamSettingsMapper.KEY_BITRATE_KBPS, 0))
        StreamSettingsMapper.setCustomBitrateMbps(store, 999)
        assertEquals(150_000, store.getInt(StreamSettingsMapper.KEY_BITRATE_KBPS, 0))

        // Switching back to SHARP recalculates from resolution + fps
        StreamSettingsMapper.setBitratePreset(store, BitratePreset.SHARP, isGen1 = false)
        val expectedSharp = StreamSettingsMapper.bitrateKbpsForPreset(
            BitratePreset.SHARP, StreamResolution.R4K, StreamFps.FPS_60, 150,
        )
        assertEquals(expectedSharp, store.getInt(StreamSettingsMapper.KEY_BITRATE_KBPS, 0))
        assertExactParityWithPreferenceConfiguration(store, false)
    }

    @Test
    fun depthStrengthAndEnvironmentAndPoseWriteExpectedKeys() {
        val store = MapPrefsStore()
        val defaultSep = PreferenceConfiguration.defaultSeparation(PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH)

        StreamSettingsMapper.setDepthStrength(store, DepthStrength.OFF)
        assertEquals("off", store.getString(StreamSettingsMapper.KEY_DEPTH_SOURCE, null))
        assertEquals(DepthStrength.OFF, StreamSettingsMapper.read(store, false).depthStrength)
        assertExactParityWithPreferenceConfiguration(store, false)

        StreamSettingsMapper.setDepthStrength(store, DepthStrength.SOFT)
        assertEquals(PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH, store.getString(StreamSettingsMapper.KEY_DEPTH_SOURCE, null))
        assertEquals(DepthPresets.value(DepthPresets.COMFORT, defaultSep), store.getInt(StreamSettingsMapper.KEY_SEPARATION, -1))
        assertEquals(DepthStrength.SOFT, StreamSettingsMapper.read(store, false).depthStrength)
        assertExactParityWithPreferenceConfiguration(store, false)

        StreamSettingsMapper.setDepthStrength(store, DepthStrength.STRONG)
        assertEquals(DepthPresets.value(DepthPresets.STRONG, defaultSep), store.getInt(StreamSettingsMapper.KEY_SEPARATION, -1))
        assertEquals(DepthStrength.STRONG, StreamSettingsMapper.read(store, false).depthStrength)
        assertExactParityWithPreferenceConfiguration(store, false)

        // Environment syncs passthrough flag both ways
        StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.PASSTHROUGH)
        assertEquals(PreferenceConfiguration.VR_ENV_PASSTHROUGH, store.getInt(StreamSettingsMapper.KEY_ENV_ID, -1))
        assertTrue(store.getBoolean(StreamSettingsMapper.KEY_PASSTHROUGH, false))
        assertExactParityWithPreferenceConfiguration(store, false)

        StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.GRAND_CINEMA)
        assertEquals(PreferenceConfiguration.VR_ENV_GRAND_CINEMA, store.getInt(StreamSettingsMapper.KEY_ENV_ID, -1))
        assertFalse(store.getBoolean(StreamSettingsMapper.KEY_PASSTHROUGH, true))
        assertEquals(VrEnvironmentPreset.GRAND_CINEMA, StreamSettingsMapper.read(store, false).environment)
        assertExactParityWithPreferenceConfiguration(store, false)

        // Screen pose reset removes the stored pose key
        store.putString(StreamSettingsMapper.KEY_SCREEN_POSE, "1,2,3")
        assertTrue(store.contains(StreamSettingsMapper.KEY_SCREEN_POSE))
        StreamSettingsMapper.resetScreenPose(store)
        assertFalse(store.contains(StreamSettingsMapper.KEY_SCREEN_POSE))
    }

    @Test
    fun allRemainingSettingsRoundTripWithExactPreferenceConfigurationParity() {
        val store = MapPrefsStore()
        StreamSettingsMapper.setCodec(store, StreamCodec.HEVC)
        StreamSettingsMapper.setMouseMode(store, MouseMode.ABSOLUTE)
        StreamSettingsMapper.setMultiController(store, false)
        StreamSettingsMapper.setFlipFaceButtons(store, true)
        StreamSettingsMapper.setGamepadShortcut(store, GamepadToggleShortcut.STICKS)
        StreamSettingsMapper.setDepthQuality(store, DepthQualityMode.SMOOTH)
        StreamSettingsMapper.setSharpening(store, false)
        StreamSettingsMapper.setSupersampling(store, true)
        StreamSettingsMapper.setScreenSize(store, ScreenSizePreset.GIANT)
        StreamSettingsMapper.setScreenDistance(store, ScreenDistancePreset.FAR)
        StreamSettingsMapper.setCurvedScreen(store, true)
        StreamSettingsMapper.setAmbilight(store, false)
        StreamSettingsMapper.setDoffGrace(store, false)
        StreamSettingsMapper.setHandTracking(store, false)
        StreamSettingsMapper.setShowRay(store, false)
        StreamSettingsMapper.setPointerSleep(store, false)
        StreamSettingsMapper.setHeadAim(store, true)
        StreamSettingsMapper.setClickSound(store, false)
        StreamSettingsMapper.setFileLog(store, FileLogMode.VERBOSE)

        assertExactParityWithPreferenceConfiguration(store, isGen1 = false)
        assertExactParityWithPreferenceConfiguration(store, isGen1 = true)

        val snap = StreamSettingsMapper.read(store, isGen1Headset = false)
        assertEquals(StreamCodec.HEVC, snap.codec)
        assertEquals(MouseMode.ABSOLUTE, snap.mouseMode)
        assertFalse(snap.multiController)
        assertTrue(snap.flipFaceButtons)
        assertEquals(GamepadToggleShortcut.STICKS, snap.gamepadShortcut)
        assertEquals(DepthQualityMode.SMOOTH, snap.depthQuality)
        assertEquals(XrShared.DEPTH_RATE_GEN1, store.getInt(StreamSettingsMapper.KEY_DEPTH_RATE, -1))
        assertFalse(snap.sharpening)
        assertTrue(snap.supersampling)
        assertEquals(ScreenSizePreset.GIANT, snap.screenSize)
        assertEquals(ScreenDistancePreset.FAR, snap.screenDistance)
        assertTrue(snap.curvedScreen)
        assertFalse(snap.ambilight)
        assertFalse(snap.doffGrace)
        assertFalse(snap.handTracking)
        assertFalse(snap.showRay)
        assertFalse(snap.pointerSleep)
        assertTrue(snap.headAim)
        assertFalse(snap.clickSound)
        assertEquals(FileLogMode.VERBOSE, snap.fileLog)

        // Review #1: fake crash reporting key must never exist
        assertFalse(store.contains("depth3d_crash_reporting"))
    }

    @Test
    fun decoderCrashRecoveryPromptsAndAutoResetsEveryThirdCrash() {
        val tombstone = MapPrefsStore()
        val streaming = MapPrefsStore()

        // No crashes -> no prompt
        assertNull(DecoderCrashRecovery.checkAndPrepare(tombstone, streaming))

        // Customize streaming settings, then simulate 1 decoder crash
        StreamSettingsMapper.setResolution(streaming, StreamResolution.R4K, isGen1 = false)
        StreamSettingsMapper.setFps(streaming, StreamFps.FPS_90, isGen1 = false)
        StreamSettingsMapper.setCodec(streaming, StreamCodec.HEVC)
        StreamSettingsMapper.setBitratePreset(streaming, BitratePreset.SHARP, isGen1 = false)
        tombstone.putInt(DecoderCrashRecovery.KEY_CRASH_COUNT, 1)

        val p1 = DecoderCrashRecovery.checkAndPrepare(tombstone, streaming)
        assertNotNull(p1)
        assertEquals(1, p1!!.crashCount)
        assertFalse(p1.autoResetApplied)
        // Not auto-reset yet on 1st crash
        assertEquals(StreamResolution.R4K, StreamSettingsMapper.read(streaming, false).resolution)

        // Acknowledging suppresses repeat prompt for the same crash count
        DecoderCrashRecovery.acknowledge(tombstone, 1)
        assertNull(DecoderCrashRecovery.checkAndPrepare(tombstone, streaming))

        // Manual reset restores default streaming settings and clears tombstone counters
        tombstone.putInt(DecoderCrashRecovery.KEY_CRASH_COUNT, 2)
        DecoderCrashRecovery.resetDefaultsAndClear(tombstone, streaming)
        assertEquals(0, tombstone.getInt(DecoderCrashRecovery.KEY_CRASH_COUNT, -1))
        assertEquals(StreamResolution.R1440P, StreamSettingsMapper.read(streaming, false).resolution)
        assertEquals(StreamCodec.AUTO, StreamSettingsMapper.read(streaming, false).codec)
        assertEquals(BitratePreset.AUTO, StreamSettingsMapper.read(streaming, false).bitratePreset)

        // 3rd consecutive crash automatically resets streaming settings
        StreamSettingsMapper.setResolution(streaming, StreamResolution.R4K, isGen1 = false)
        StreamSettingsMapper.setCodec(streaming, StreamCodec.HEVC)
        tombstone.putInt(DecoderCrashRecovery.KEY_CRASH_COUNT, 3)
        val p3 = DecoderCrashRecovery.checkAndPrepare(tombstone, streaming)
        assertNotNull(p3)
        assertTrue(p3!!.autoResetApplied)
        assertEquals(StreamResolution.R1440P, StreamSettingsMapper.read(streaming, false).resolution)
        assertEquals(StreamCodec.AUTO, StreamSettingsMapper.read(streaming, false).codec)
    }

    @Test
    fun vrQuickMenuCellIndicesMapCleanlyToPresets() {
        assertEquals(StreamResolution.R720P, StreamResolution.fromCellIndex(0))
        assertEquals(StreamResolution.R1080P, StreamResolution.fromCellIndex(1))
        assertEquals(StreamResolution.R1440P, StreamResolution.fromCellIndex(2))
        assertEquals(StreamResolution.R4K, StreamResolution.fromCellIndex(3))

        assertEquals(BitratePreset.SMOOTH, BitratePreset.fromVrQuickCell(0))
        assertEquals(BitratePreset.AUTO, BitratePreset.fromVrQuickCell(1))
        assertEquals(BitratePreset.SHARP, BitratePreset.fromVrQuickCell(2))
    }
}
