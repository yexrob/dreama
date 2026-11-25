package com.limelight.binding.video

import com.limelight.local.stream.CurvatureDisabledReason
import com.limelight.local.stream.MapPrefsStore
import com.limelight.local.stream.StreamSettingsMapper
import com.limelight.local.stream.VrEnvironmentPreset
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReadoutAndPrefsTest {

    private val presetLabels = arrayOf("小", "中", "大", "巨幕")

    @Test
    fun diagonalInchesMatchSpecExamples() {
        // ux-spec.md §6.12 exact worked examples
        assertEquals(126, ScreenReadout.diagonalInches(50.0f, 3.0f))
        assertEquals(143, ScreenReadout.diagonalInches(50.0f, 3.4f))
        assertEquals(163, ScreenReadout.diagonalInches(62.0f, 3.0f))
    }

    @Test
    fun readoutTextMatchesSpecFormatForDistanceAndSize() {
        val distStr = ScreenReadout.formatReadoutText(
            XrShared.ADJUST_AXIS_DIST,
            500,
            340,
            143,
            XrShared.SCREEN_PRESET_MEDIUM,
            presetLabels,
            "寸",
        )
        assertEquals("↕ 3.4 m · 中 50° · 143 寸", distStr)

        val sizeStr = ScreenReadout.formatReadoutText(
            XrShared.ADJUST_AXIS_SIZE,
            620,
            300,
            163,
            XrShared.SCREEN_PRESET_LARGE,
            presetLabels,
            "寸",
        )
        assertEquals("↔ 大 62° · 3.0 m · 163 寸", sizeStr)

        val customStr = ScreenReadout.formatReadoutText(
            XrShared.ADJUST_AXIS_SIZE,
            540,
            300,
            138,
            XrShared.SCREEN_PRESET_NONE,
            presetLabels,
            "寸",
        )
        assertEquals("↔ 54° · 3.0 m · 138 寸", customStr)
    }

    @Test
    fun curvatureRadiusAndQuantizationMatchSpec() {
        assertEquals(0.0f, ScreenReadout.curvatureRadiusMeters(3.0f, 0), 1e-5f)
        assertEquals(3.0f, ScreenReadout.curvatureRadiusMeters(3.0f, 100), 1e-4f)
        assertEquals(6.0f, ScreenReadout.curvatureRadiusMeters(3.0f, 50), 1e-4f)
        assertEquals(60.0f, ScreenReadout.curvatureRadiusMeters(3.0f, 5), 1e-3f)

        assertEquals(0, ScreenReadout.quantizeCurvature(-10))
        assertEquals(0, ScreenReadout.quantizeCurvature(2))
        assertEquals(5, ScreenReadout.quantizeCurvature(3))
        assertEquals(45, ScreenReadout.quantizeCurvature(45))
        assertEquals(100, ScreenReadout.quantizeCurvature(140))
    }

    @Test
    fun continuousCurvatureAndScreenPrefsMigrateWithoutLosingExistingPose() {
        val store = MapPrefsStore()
        // Simulate an existing install with saved pose and legacy 45% curved toggle
        val legacyPose = "0.1,0.2,-3.2,0,0,0,1,3.1,6.4,0.45"
        store.putString(PreferenceConfiguration.VR_SCREEN_POSE_PREF_STRING, legacyPose)
        store.putInt(PreferenceConfiguration.VR_CURVATURE_PREF_STRING, 45)

        val snap = StreamSettingsMapper.read(store, isGen1Headset = false)
        assertEquals(45, snap.actualCurvature)
        assertTrue(snap.curvedScreen)
        assertTrue(snap.triggerScreenAdjust)
        assertNull(snap.curvatureDisabledReason)
        assertEquals(legacyPose, store.getString(PreferenceConfiguration.VR_SCREEN_POSE_PREF_STRING, null))

        // Out-of-range legacy curvature clamps cleanly to [0, 100]
        store.putInt(PreferenceConfiguration.VR_CURVATURE_PREF_STRING, 180)
        assertEquals(100, PreferenceConfiguration.readPreferences(store, false).vrCurvature)
        store.putInt(PreferenceConfiguration.VR_CURVATURE_PREF_STRING, -5)
        assertEquals(0, PreferenceConfiguration.readPreferences(store, false).vrCurvature)

        // Continuous curvature 5% steps
        StreamSettingsMapper.setCurvaturePercent(store, 63)
        assertEquals(65, StreamSettingsMapper.read(store, false).actualCurvature)
        assertEquals(65, PreferenceConfiguration.readPreferences(store, false).vrCurvature)
        // Pose key remains untouched until explicitly reset
        assertEquals(legacyPose, store.getString(PreferenceConfiguration.VR_SCREEN_POSE_PREF_STRING, null))
    }

    @Test
    fun curvatureDisabledReasonReflectsRoomAndHardwareSupportWithoutErasingValue() {
        val store = MapPrefsStore()
        StreamSettingsMapper.setCurvaturePercent(store, 55)
        StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.HOME_THEATER)

        val inRoomSnap = StreamSettingsMapper.read(store, isGen1Headset = false)
        assertEquals(CurvatureDisabledReason.IN_ROOM, inRoomSnap.curvatureDisabledReason)
        // Remembered curvature must NOT be overwritten when entering a 3D room (§6.12)
        assertEquals(55, inRoomSnap.actualCurvature)

        // Switching back to Void restores live curvature
        StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.VOID)
        val voidSnap = StreamSettingsMapper.read(store, isGen1Headset = false)
        assertNull(voidSnap.curvatureDisabledReason)
        assertEquals(55, voidSnap.actualCurvature)

        // Headset without XR_KHR_composition_layer_cylinder reports UNSUPPORTED_HEADSET
        PreferenceConfiguration.saveCylinderSupported(store, false)
        val unsupportedSnap = StreamSettingsMapper.read(store, isGen1Headset = false)
        assertFalse(unsupportedSnap.cylinderSupported)
        assertEquals(CurvatureDisabledReason.UNSUPPORTED_HEADSET, unsupportedSnap.curvatureDisabledReason)
    }

    @Test
    fun triggerScreenAdjustAndGlobalScreenDimensionsRoundTrip() {
        val store = MapPrefsStore()
        assertTrue(StreamSettingsMapper.read(store, false).triggerScreenAdjust)

        StreamSettingsMapper.setTriggerScreenAdjust(store, false)
        assertFalse(StreamSettingsMapper.read(store, false).triggerScreenAdjust)
        assertFalse(PreferenceConfiguration.readPreferences(store, false).vrTriggerScreenAdjust)

        // Setting FOV keeps distance unchanged; setting distance keeps angular FOV constant
        StreamSettingsMapper.setScreenFovDeg(store, 62.0f)
        val afterFov = StreamSettingsMapper.read(store, false)
        assertEquals(30, afterFov.actualScreenDistanceDm)
        assertEquals(62.0f, afterFov.screenFovDeg, 1.5f)

        StreamSettingsMapper.setScreenDistanceMeters(store, 5.0f)
        val afterDist = StreamSettingsMapper.read(store, false)
        assertEquals(50, afterDist.actualScreenDistanceDm)
        assertEquals(62.0f, afterDist.screenFovDeg, 1.5f)

        // In-VR adjust save updates global prefs cleanly without truncating large FOV @ 8 m (Review #8)
        PreferenceConfiguration.saveGlobalScreenDimensions(store, 50.0f, 3.4f)
        assertEquals(34, store.getInt(PreferenceConfiguration.VR_DISTANCE_PREF_STRING, -1))
        assertEquals(32, store.getInt(PreferenceConfiguration.VR_SCREEN_SIZE_PREF_STRING, -1))

        // 62° @ 8.0 m (~9.61 m = 96 dm) must round-trip as ~62°, NOT truncated to 8 m (~53°)!
        PreferenceConfiguration.saveGlobalScreenDimensions(store, 62.0f, 8.0f)
        assertEquals(80, store.getInt(PreferenceConfiguration.VR_DISTANCE_PREF_STRING, -1))
        assertEquals(96, store.getInt(PreferenceConfiguration.VR_SCREEN_SIZE_PREF_STRING, -1))
        val after8m = StreamSettingsMapper.read(store, false)
        assertEquals(62.0f, after8m.screenFovDeg, 0.5f)

        // 90° @ 8.0 m (16.0 m = 160 dm) hits exact unified upper bound
        PreferenceConfiguration.saveGlobalScreenDimensions(store, 90.0f, 8.0f)
        assertEquals(160, store.getInt(PreferenceConfiguration.VR_SCREEN_SIZE_PREF_STRING, -1))
        assertEquals(90.0f, StreamSettingsMapper.read(store, false).screenFovDeg, 0.5f)
    }

    @Test
    fun readoutCacheSuppressesPerFrameRedrawAndStringFormatting() {
        val cache = ScreenReadout.ReadoutCache()

        // First call formats string and requests upload (49.9° -> bucket 21, displays "50°")
        assertTrue(cache.update(XrShared.ADJUST_AXIS_SIZE, 499, 300, 126, XrShared.SCREEN_PRESET_MEDIUM, presetLabels, "寸"))
        assertEquals("↔ 中 50° · 3.0 m · 126 寸", cache.text)
        assertEquals(1, cache.stringFormatCount)

        // Sub-display jitter within same integer deg/0.1m/inch and same 1/64 slider bucket (21):
        // ZERO string re-format and ZERO texture upload!
        assertFalse(cache.update(XrShared.ADJUST_AXIS_SIZE, 500, 300, 126, XrShared.SCREEN_PRESET_MEDIUM, presetLabels, "寸"))
        assertFalse(cache.update(XrShared.ADJUST_AXIS_SIZE, 501, 300, 126, XrShared.SCREEN_PRESET_MEDIUM, presetLabels, "寸"))
        assertEquals(1, cache.stringFormatCount)

        // Moving across a 1/64 slider bucket (50.4° -> bucket 22, still displays "50°") triggers
        // upload while still NOT re-formatting the string!
        assertTrue(cache.update(XrShared.ADJUST_AXIS_SIZE, 504, 300, 126, XrShared.SCREEN_PRESET_MEDIUM, presetLabels, "寸"))
        assertEquals(1, cache.stringFormatCount)

        // Changing displayed degree/inches formats string once and triggers upload
        assertTrue(cache.update(XrShared.ADJUST_AXIS_SIZE, 510, 300, 129, XrShared.SCREEN_PRESET_NONE, presetLabels, "寸"))
        assertEquals("↔ 51° · 3.0 m · 129 寸", cache.text)
        assertEquals(2, cache.stringFormatCount)

        // Spec §6.12 readout dimensions fit inside toast swapchain
        assertEquals(960, XrShared.READOUT_TEX_W)
        assertEquals(112, XrShared.READOUT_TEX_H)
        assertTrue(XrShared.READOUT_TEX_W <= XrShared.TOAST_TEX_W)
        assertTrue(XrShared.READOUT_TEX_H <= XrShared.TOAST_TEX_H)
    }

    @Test
    fun settingsPresetsMatchAngularAndDistanceSpecValues() {
        assertEquals(com.limelight.local.stream.ScreenSizePreset.SMALL, com.limelight.local.stream.ScreenSizePreset.fromFovDegOrNull(40f))
        assertEquals(com.limelight.local.stream.ScreenSizePreset.MEDIUM, com.limelight.local.stream.ScreenSizePreset.fromFovDegOrNull(50f))
        assertEquals(com.limelight.local.stream.ScreenSizePreset.LARGE, com.limelight.local.stream.ScreenSizePreset.fromFovDegOrNull(62f))
        assertEquals(com.limelight.local.stream.ScreenSizePreset.GIANT, com.limelight.local.stream.ScreenSizePreset.fromFovDegOrNull(80f))
        assertNull(com.limelight.local.stream.ScreenSizePreset.fromFovDegOrNull(55f))

        assertEquals(com.limelight.local.stream.ScreenDistancePreset.NEAR, com.limelight.local.stream.ScreenDistancePreset.fromDistanceDmOrNull(20))
        assertEquals(com.limelight.local.stream.ScreenDistancePreset.MEDIUM, com.limelight.local.stream.ScreenDistancePreset.fromDistanceDmOrNull(30))
        assertEquals(com.limelight.local.stream.ScreenDistancePreset.FAR, com.limelight.local.stream.ScreenDistancePreset.fromDistanceDmOrNull(50))
        assertNull(com.limelight.local.stream.ScreenDistancePreset.fromDistanceDmOrNull(37))
    }

    private fun layerBudget(
        inRoom: Boolean = false,
        stereo: Boolean = false,
        hasDanmaku: Boolean = false,
        hasSubtitle: Boolean = false,
        hasControlBar: Boolean = false,
        hasPalette: Boolean = false,
        hasReadout: Boolean = false,
        hasToast: Boolean = false,
        hasCard: Boolean = false,
        hasPointer: Boolean = false,
        hasController: Boolean = false,
        hasGlow: Boolean = false,
    ): Int = ScreenReadout.layerBudgetCount(
        inRoom, stereo, hasDanmaku, hasSubtitle, hasControlBar, hasPalette,
        hasReadout, hasToast, hasCard, hasPointer, hasController, hasGlow,
    )

    @Test
    fun layerBudgetNeverExceedsSpecLimits() {
        // Both passthrough dimmer (inRoom=false) and 3D room (inRoom=true) count 1 dimmer/room slot per §6.5
        for (inRoom in listOf(false, true)) {
            // Pure playback = 6
            assertEquals(6, layerBudget(
                inRoom = inRoom, stereo = true, hasDanmaku = true, hasSubtitle = true,
            ))
            // Screen adjust hides control bar and shares toast slot = 10
            assertEquals(10, layerBudget(
                inRoom = inRoom, stereo = true, hasDanmaku = true, hasSubtitle = true,
                hasControlBar = true, hasReadout = true, hasToast = true,
                hasPointer = true, hasController = true,
            ))
            // Screen adjust + palette open = 11 (<= 13 design target, <= 16 hardware ceiling)
            val adjPalette = layerBudget(
                inRoom = inRoom, stereo = true, hasDanmaku = true, hasSubtitle = true,
                hasControlBar = true, hasPalette = true, hasReadout = true, hasToast = true,
                hasPointer = true, hasController = true,
            )
            assertEquals(11, adjPalette)
            assertTrue(adjPalette <= ScreenReadout.LAYER_BUDGET_DESIGN_MAX)
            assertTrue(adjPalette <= ScreenReadout.LAYER_BUDGET_HARDWARE_MAX)
        }
    }

    @Test
    fun localPlaybackAndAdjustInputSlotsChainToInSlots() {
        assertEquals(XrShared.IN_HINT + 1, XrShared.IN_LOCAL_PLAY_PAUSE)
        assertEquals(XrShared.IN_LOCAL_PLAY_PAUSE + 1, XrShared.IN_LOCAL_SEEK_SEC)
        assertEquals(XrShared.IN_LOCAL_SEEK_SEC + 1, XrShared.IN_LOCAL_B_ACTION)
        assertEquals(XrShared.IN_LOCAL_B_ACTION + 1, XrShared.IN_LOCAL_Y_DANMAKU)
        assertEquals(XrShared.IN_LOCAL_Y_DANMAKU + 1, XrShared.IN_LOCAL_MENU)
        assertEquals(XrShared.IN_LOCAL_MENU + 1, XrShared.IN_ADJUST_ACTIVE)
        assertEquals(XrShared.IN_ADJUST_ACTIVE + 1, XrShared.IN_ADJUST_AXIS)
        assertEquals(XrShared.IN_ADJUST_AXIS + 1, XrShared.IN_ADJUST_FOV_TENTHS)
        assertEquals(XrShared.IN_ADJUST_FOV_TENTHS + 1, XrShared.IN_ADJUST_DIST_CM)
        assertEquals(XrShared.IN_ADJUST_DIST_CM + 1, XrShared.IN_ADJUST_INCHES)
        assertEquals(XrShared.IN_ADJUST_INCHES + 1, XrShared.IN_ADJUST_PRESET)
        assertEquals(XrShared.IN_ADJUST_PRESET + 1, XrShared.IN_ADJUST_SAVED)
        assertEquals(XrShared.IN_ADJUST_SAVED + 1, XrShared.IN_PALETTE_OPEN)
        assertEquals(XrShared.IN_PALETTE_OPEN + 1, XrShared.IN_PALETTE_PINNED)
        assertEquals(XrShared.IN_PALETTE_PINNED + 1, XrShared.IN_PALETTE_HAND_MODE)
        assertEquals(XrShared.IN_PALETTE_HAND_MODE + 1, XrShared.IN_PALETTE_HOVER_X)
        assertEquals(XrShared.IN_PALETTE_HOVER_X + 1, XrShared.IN_PALETTE_HOVER_Y)
        assertEquals(XrShared.IN_PALETTE_HOVER_Y + 1, XrShared.IN_PALETTE_CLICK)
        assertEquals(XrShared.IN_PALETTE_CLICK + 1, XrShared.IN_PALETTE_ROW_STEP)
        assertEquals(XrShared.IN_PALETTE_ROW_STEP + 1, XrShared.IN_PALETTE_ROW_RESET)
        assertEquals(XrShared.IN_PALETTE_ROW_RESET + 1, XrShared.IN_PALETTE_TAB_STEP)
        assertEquals(XrShared.IN_PALETTE_TAB_STEP + 1, XrShared.IN_PALETTE_PAGE_STEP)
        assertEquals(XrShared.IN_PALETTE_PAGE_STEP + 1, XrShared.IN_PALETTE_DIM_FALLBACK)
        assertEquals(XrShared.IN_PALETTE_DIM_FALLBACK + 1, XrShared.IN_CONTENT_PHI_CENTI)
        assertEquals(XrShared.IN_CONTENT_PHI_CENTI + 1, XrShared.IN_SLOTS)
    }
}
