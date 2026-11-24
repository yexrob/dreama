package com.limelight.local.stream

import com.limelight.binding.video.DepthPresets
import com.limelight.binding.video.XrShared
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSettingsMapperTest {

    @Test
    fun freshStoreDefaultsMatchProductSpec() {
        val store = MapPrefsStore()
        val snap = StreamSettingsMapper.read(store, isGen1Headset = false)

        assertEquals(StreamResolution.R1440P, snap.resolution)
        assertEquals(StreamFps.FPS_72, snap.fps)
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
        assertFalse(snap.crashReporting)
        assertFalse(snap.warn3dLag)

        val gen1 = StreamSettingsMapper.read(store, isGen1Headset = true)
        assertEquals(DepthQualityMode.SMOOTH, gen1.depthQuality)
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
    }

    @Test
    fun fpsAndResolutionSyncUnlockFpsAndAutoBitrate() {
        val store = MapPrefsStore()

        StreamSettingsMapper.setFps(store, StreamFps.FPS_90, isGen1 = false)
        assertEquals("90", store.getString(StreamSettingsMapper.KEY_FPS, null))
        assertTrue(store.getBoolean(StreamSettingsMapper.KEY_UNLOCK_FPS, false))
        assertTrue(StreamSettingsMapper.read(store, false).warn3dLag)

        StreamSettingsMapper.setFps(store, StreamFps.FPS_60, isGen1 = false)
        assertFalse(store.getBoolean(StreamSettingsMapper.KEY_UNLOCK_FPS, true))

        // Custom bitrate is preserved when changing resolution
        StreamSettingsMapper.setCustomBitrateMbps(store, 85)
        assertEquals(85_000, store.getInt(StreamSettingsMapper.KEY_BITRATE_KBPS, 0))
        StreamSettingsMapper.setResolution(store, StreamResolution.R4K, isGen1 = false)
        assertEquals(85_000, store.getInt(StreamSettingsMapper.KEY_BITRATE_KBPS, 0))

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
    }

    @Test
    fun depthStrengthAndEnvironmentAndPoseWriteExpectedKeys() {
        val store = MapPrefsStore()
        val defaultSep = PreferenceConfiguration.defaultSeparation(PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH)

        StreamSettingsMapper.setDepthStrength(store, DepthStrength.OFF)
        assertEquals("off", store.getString(StreamSettingsMapper.KEY_DEPTH_SOURCE, null))
        assertEquals(DepthStrength.OFF, StreamSettingsMapper.read(store, false).depthStrength)

        StreamSettingsMapper.setDepthStrength(store, DepthStrength.SOFT)
        assertEquals(PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH, store.getString(StreamSettingsMapper.KEY_DEPTH_SOURCE, null))
        assertEquals(DepthPresets.value(DepthPresets.COMFORT, defaultSep), store.getInt(StreamSettingsMapper.KEY_SEPARATION, -1))
        assertEquals(DepthStrength.SOFT, StreamSettingsMapper.read(store, false).depthStrength)

        StreamSettingsMapper.setDepthStrength(store, DepthStrength.STRONG)
        assertEquals(DepthPresets.value(DepthPresets.STRONG, defaultSep), store.getInt(StreamSettingsMapper.KEY_SEPARATION, -1))
        assertEquals(DepthStrength.STRONG, StreamSettingsMapper.read(store, false).depthStrength)

        // Environment syncs passthrough flag both ways
        StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.PASSTHROUGH)
        assertEquals(PreferenceConfiguration.VR_ENV_PASSTHROUGH, store.getInt(StreamSettingsMapper.KEY_ENV_ID, -1))
        assertTrue(store.getBoolean(StreamSettingsMapper.KEY_PASSTHROUGH, false))

        StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.GRAND_CINEMA)
        assertEquals(PreferenceConfiguration.VR_ENV_GRAND_CINEMA, store.getInt(StreamSettingsMapper.KEY_ENV_ID, -1))
        assertFalse(store.getBoolean(StreamSettingsMapper.KEY_PASSTHROUGH, true))
        assertEquals(VrEnvironmentPreset.GRAND_CINEMA, StreamSettingsMapper.read(store, false).environment)

        // Screen pose reset removes the stored pose key
        store.putString(StreamSettingsMapper.KEY_SCREEN_POSE, "1,2,3")
        assertTrue(store.contains(StreamSettingsMapper.KEY_SCREEN_POSE))
        StreamSettingsMapper.resetScreenPose(store)
        assertFalse(store.contains(StreamSettingsMapper.KEY_SCREEN_POSE))
    }

    @Test
    fun allRemainingSettingsRoundTripCleanly() {
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
        StreamSettingsMapper.setCrashReporting(store, true)

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
        assertTrue(snap.crashReporting)
    }
}
