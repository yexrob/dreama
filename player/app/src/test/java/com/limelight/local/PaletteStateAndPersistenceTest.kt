package com.limelight.local

import com.limelight.binding.video.XrShared
import com.limelight.local.stream.DepthStrength
import com.limelight.local.stream.MapPrefsStore
import com.limelight.local.stream.PaletteHand
import com.limelight.local.stream.StreamSettingsMapper
import com.limelight.local.stream.VrEnvironmentPreset
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for G2 VR Quick Palette state machine, 1024x768 layout hit-testing,
 * per-video vs global persistence isolation, TransportBar 「调整」/「弹」 buttons,
 * bitmap subtitle switching, and 2D Settings hand-swap preference.
 */
class PaletteStateAndPersistenceTest {

    @Test
    fun allFourTabsAndDanmakuSecondaryPageResetOnTabSwitch() {
        val c = PaletteController(danmakuSupported = true)
        assertEquals(XrShared.PALETTE_TAB_3D, c.activeTab)

        // Step right through all 4 tabs; pushing past last tab fires limit haptic
        assertTrue(c.stepTab(1).changed)
        assertEquals(XrShared.PALETTE_TAB_SCREEN, c.activeTab)
        assertTrue(c.stepTab(1).changed)
        assertEquals(XrShared.PALETTE_TAB_DANMAKU, c.activeTab)

        // Enter Danmaku Page 2 via stick-Y down (-1)
        val pageRes = c.stepPage(-1)
        assertTrue(pageRes.changed)
        assertEquals(1, c.danmakuPage)

        // Leaving Danmaku tab resets danmakuPage back to 0 (§6.3)
        assertTrue(c.stepTab(1).changed)
        assertEquals(XrShared.PALETTE_TAB_SUBTITLE, c.activeTab)
        assertEquals(0, c.danmakuPage)

        // Pushing right past Tab 3 clamps and emits limit haptic
        val limit = c.stepTab(1)
        assertFalse(limit.changed)
        assertEquals(XrShared.PALETTE_HAPTIC_LIMIT, limit.hapticKind)
    }

    @Test
    fun tab3dStrengthIsPerVideoWithFirstChangeToastAndResetToSettingsDefault() {
        val globalStore = MapPrefsStore()
        val videoStore = MapPrefsStore()
        StreamSettingsMapper.setDepthStrength(globalStore, DepthStrength.SOFT)

        val g = PaletteStore.readGlobal(globalStore)
        assertEquals(DepthStrength.SOFT, g.defaultStrength)

        // Video A: starts at 2D Settings default (SOFT); reset button hidden
        val keyA = PaletteStore.videoKey("local", "movieA", null)
        val cA = PaletteController(g, PaletteStore.readVideoOverrides(videoStore, keyA))
        assertEquals(DepthStrength.SOFT, cA.effectiveStrength)
        assertFalse(cA.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 0))

        // First change on Video A -> STRONG, shows 「只改这部片子」 toast once!
        val r1 = cA.selectChip(0, DepthStrength.STRONG.ordinal)
        assertTrue(r1.changed)
        assertTrue(r1.stereoChanged)
        assertEquals("只改这部片子", r1.toastMessage)
        assertTrue(cA.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 0))
        PaletteStore.saveVideoOverrides(videoStore, keyA, cA.video)

        // Second change on Video A -> OFF, does NOT repeat toast!
        val r2 = cA.selectChip(0, DepthStrength.OFF.ordinal)
        assertNull(r2.toastMessage)
        PaletteStore.saveVideoOverrides(videoStore, keyA, cA.video)

        // Video B is completely isolated from Video A's override
        val keyB = PaletteStore.videoKey("local", "movieB", null)
        val cB = PaletteController(g, PaletteStore.readVideoOverrides(videoStore, keyB))
        assertEquals(DepthStrength.SOFT, cB.effectiveStrength)
        assertFalse(cB.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 0))

        // Resetting Row 0 on Video A returns to 2D Settings default (SOFT) and clears override
        val rReset = cA.resetRow(0)
        assertTrue(rReset.stereoChanged)
        assertEquals(DepthStrength.SOFT, cA.effectiveStrength)
        assertNull(cA.video.strength)
        assertFalse(cA.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 0))
    }

    @Test
    fun tab3dSourceFloatAndLayerControlsAndResetBehavior() {
        val c = PaletteController()
        // Row 1: Source Auto / SBS / OU (per-video)
        assertEquals(StereoSourceFormat.AUTO, c.effectiveSourceFormat)
        assertFalse(c.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 1))
        val sbs = c.selectChip(1, StereoSourceFormat.SBS.ordinal)
        assertTrue(sbs.sourceFormatChanged)
        assertEquals(XrShared.STEREO_SRC_SBS, c.computeStereoParams(null).sourceFormat.nativeCode)
        assertTrue(c.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 1))
        c.resetRow(1)
        assertEquals(StereoSourceFormat.AUTO, c.effectiveSourceFormat)
        assertFalse(c.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 1))

        // Row 2: Float pop-out (Auto vs Fixed)
        assertEquals("自动", c.formatFloatValue())
        assertFalse(c.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 2))
        c.stepRow(2, 2) // +0.10° in auto mode
        assertEquals("+0.10°", c.formatFloatValue())
        assertTrue(c.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 2))

        // Click "自动" chip to switch to fixed mode
        val autoChipX = (XrShared.PALETTE_ROW_X + XrShared.PALETTE_CTRL_OFFSET_X + 30).toFloat()
        val row2Y = (XrShared.PALETTE_ROW_Y0 + 2 * XrShared.PALETTE_ROW_H + 74).toFloat()
        c.processFrameInput(
            open = true, pinned = false, handTrackingMode = false, dimFallback = false,
            hoverX = autoChipX, hoverY = row2Y, clickState = 1,
            rowStep = 0, rowReset = false, tabStep = 0, pageStep = 0,
        )
        assertFalse(c.global.floatAuto)
        assertEquals("贴屏", c.formatFloatValue())
        c.stepRow(2, 4) // 0.20° -> positive cm pop-out at 3 m
        assertTrue(c.formatFloatValue().endsWith(" cm"))

        // Reset Row 2 restores Auto @ 0 offset
        c.resetRow(2)
        assertTrue(c.global.floatAuto)
        assertEquals(0, c.global.floatAutoOffsetSteps)
        assertFalse(c.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 2))

        // Row 3: Layer depth (OFF/SHALLOW/MEDIUM/DEEP)
        assertEquals(DepthLayerLevel.MEDIUM, c.global.depthLayer)
        c.stepRow(3, 1)
        assertEquals(DepthLayerLevel.DEEP, c.global.depthLayer)
        assertEquals(2.0f, c.computeStereoParams(null).depthLocal, 1e-4f)
        assertEquals(XrShared.PALETTE_HAPTIC_LIMIT, c.stepRow(3, 1).hapticKind)
        c.resetRow(3)
        assertEquals(DepthLayerLevel.MEDIUM, c.global.depthLayer)
    }

    @Test
    fun tabScreenSizeDistanceCurvatureAndEnvironmentRules() {
        val c = PaletteController(cylinderSupported = true)
        c.selectTab(XrShared.PALETTE_TAB_SCREEN)

        // Default 62° 大, 3.0 m, 0% 平面, 透视 -> all reset buttons hidden
        assertEquals("62° 大", c.formatSizeValue())
        assertEquals("3.0 m", c.formatDistValue())
        assertEquals("平面", c.formatCurveValue())
        for (r in 0..3) {
            assertFalse("row $r reset should be hidden at default", c.isRowResetVisible(XrShared.PALETTE_TAB_SCREEN, 0, r))
        }

        // Step size down to 50° detent -> detent haptic
        c.stepRow(0, -11) // 51°
        assertEquals("51°", c.formatSizeValue())
        val detentHit = c.stepRow(0, -1) // 50° 中
        assertEquals("50° 中", c.formatSizeValue())
        assertEquals(XrShared.PALETTE_HAPTIC_DETENT, detentHit.hapticKind)

        // Curvature 5% steps
        c.stepRow(2, 9) // 45%
        assertEquals(45, c.global.screenCurvaturePct)
        assertEquals("45%", c.formatCurveValue())
        assertTrue(c.isRowResetVisible(XrShared.PALETTE_TAB_SCREEN, 0, 2))
        assertEquals(0xF, c.activeRowMask())

        // Switch to 影院: curvature row disables with spec reason, remembers 45%, hides reset, clears mask bit 2
        c.selectChip(3, PaletteEnvironment.THEATER.ordinal)
        assertEquals("影院房间里屏幕是平的", c.curvatureDisabledMessage)
        assertEquals(45, c.global.screenCurvaturePct)
        assertFalse(c.isRowResetVisible(XrShared.PALETTE_TAB_SCREEN, 0, 2))
        assertEquals(0xB, c.activeRowMask()) // bit 2 = 0

        // Unsupported headset reason when back outside room
        c.selectChip(3, PaletteEnvironment.DIM.ordinal)
        assertNull(c.curvatureDisabledMessage)
        assertEquals(45, c.global.screenCurvaturePct)
        c.cylinderSupported = false
        assertEquals("这台头显不支持曲面", c.curvatureDisabledMessage)
        assertEquals(0xB, c.activeRowMask())
    }

    @Test
    fun tabDanmakuUnsupportedFailedOffAndTwoPages() {
        // 1. Source without DANMAKU capability
        val noDm = PaletteController(danmakuSupported = false)
        noDm.selectTab(XrShared.PALETTE_TAB_DANMAKU)
        assertEquals(0, noDm.activeRowMask())
        val yRes = noDm.toggleDanmakuQuick()
        assertEquals("这个片源没有弹幕", yRes.toastMessage)
        assertEquals(XrShared.PALETTE_HAPTIC_LIMIT, yRes.hapticKind)

        // 2. Source with DANMAKU capability, load failed -> Retry clickable
        val failed = PaletteController(danmakuSupported = true, danmakuLoadFailed = true)
        failed.selectTab(XrShared.PALETTE_TAB_DANMAKU)
        assertEquals(0, failed.activeRowMask())
        val retryX = XrShared.PALETTE_ROW_X + PaletteController.RETRY_BTN_LOCAL_X + 50f
        val row0Y = XrShared.PALETTE_ROW_Y0 + 74f
        val retryRes = failed.processFrameInput(
            open = true, pinned = false, handTrackingMode = false, dimFallback = false,
            hoverX = retryX, hoverY = row0Y, clickState = 1,
            rowStep = 0, rowReset = false, tabStep = 0, pageStep = 0,
        )
        assertTrue(retryRes.danmakuRetryRequested)
        assertFalse(failed.danmakuLoadFailed)

        // 3. Normal Danmaku tab: Page 1 controls & disabling rows 1..3 when danmaku OFF
        val dm = PaletteController(danmakuSupported = true)
        dm.selectTab(XrShared.PALETTE_TAB_DANMAKU)
        assertEquals(0b0111, dm.activeRowMask()) // rows 0,1,2 adjustable; row 3 is "更多" nav

        val toggleOff = dm.toggleDanmakuQuick()
        assertEquals("弹幕 已关", toggleOff.toastMessage)
        assertFalse(dm.global.danmakuOn)
        assertEquals(0b0001, dm.activeRowMask()) // rows 1..3 disabled!

        val toggleOn = dm.toggleDanmakuQuick()
        assertEquals("弹幕 已开", toggleOn.toastMessage)
        assertTrue(dm.global.danmakuOn)

        // Opacity 20..100% step 10%
        dm.stepRow(2, -2) // 80 -> 60
        assertEquals(60, dm.global.danmakuOpacityPct)
        assertTrue(dm.isRowResetVisible(XrShared.PALETTE_TAB_DANMAKU, 0, 2))

        // Open Page 2 & toggle multi-select block flags
        dm.stepPage(-1)
        assertEquals(1, dm.danmakuPage)
        dm.selectChip(2, 0) // block SCROLL
        dm.selectChip(2, 3) // block COLOR
        assertEquals(DanmakuBlockFlags.SCROLL or DanmakuBlockFlags.COLOR, dm.global.danmakuBlockMask)
        assertTrue(dm.isRowResetVisible(XrShared.PALETTE_TAB_DANMAKU, 1, 2))
        dm.resetRow(2)
        assertEquals(0, dm.global.danmakuBlockMask)
        assertFalse(dm.isRowResetVisible(XrShared.PALETTE_TAB_DANMAKU, 1, 2))
    }

    @Test
    fun tabSubAudioEmptySingleTextAndBitmapSubtitleSwitch() {
        val c = PaletteController()
        c.selectTab(XrShared.PALETTE_TAB_SUBTITLE)

        // No subtitles, single audio -> all 4 rows disabled (activeRowMask == 0)
        c.setTracks(
            subtitles = emptyList(),
            audios = listOf(PaletteAudioTrack("aud:0", "国语 · 2.0")),
        )
        assertEquals(0, c.activeRowMask())

        // Add text + bitmap subtitles and 2 audio tracks
        c.setTracks(
            subtitles = listOf(
                PaletteSubtitleTrack("sub:text", "简体中文", isBitmap = false),
                PaletteSubtitleTrack("sub:pgs", "蓝光中文 (PGS)", isBitmap = true),
            ),
            audios = listOf(
                PaletteAudioTrack("aud:0", "国语 · 5.1"),
                PaletteAudioTrack("aud:1", "英语 · 5.1"),
            ),
            defaultSubId = "sub:text",
            defaultAudId = "aud:0",
        )
        assertEquals(0xF, c.activeRowMask())
        assertFalse(c.selectedSubtitleIsBitmap)

        // Step to bitmap PGS subtitle -> Row 2 automatically switches to bitmap 缩放 70..150%!
        val stepRes = c.stepRow(0, 1)
        assertTrue(stepRes.subtitleTrackChanged)
        assertTrue(c.selectedSubtitleIsBitmap)
        assertEquals(100, c.global.subtitleBitmapScalePct)
        c.stepRow(2, 2) // 120%
        assertEquals(120, c.global.subtitleBitmapScalePct)
        assertTrue(c.isRowResetVisible(XrShared.PALETTE_TAB_SUBTITLE, 0, 2))

        // Step subtitle again -> cycles to OFF -> rows 2 & 3 disable!
        c.stepRow(0, 1)
        assertEquals(0, c.selectedSubtitleIndex)
        assertNull(c.selectedSubtitleTrack)
        assertEquals(0b0011, c.activeRowMask())

        // Audio stepper cycles cleanly
        val audRes = c.stepRow(1, 1)
        assertTrue(audRes.audioTrackChanged)
        assertEquals("aud:1", c.selectedAudioTrack?.id)
        assertTrue(c.isRowResetVisible(XrShared.PALETTE_TAB_SUBTITLE, 0, 1))
        c.resetRow(1)
        assertEquals("aud:0", c.selectedAudioTrack?.id)
    }

    @Test
    fun renderSignatureSuppressesIdleRedrawsAndChangesOnAnyVisualDelta() {
        val c = PaletteController(danmakuSupported = true)
        val s0 = c.renderSignature()
        // Identical idle frame -> exact same signature (0 redraws)
        c.processFrameInput(
            open = true, pinned = false, handTrackingMode = false, dimFallback = false,
            hoverX = -1f, hoverY = -1f, clickState = 0,
            rowStep = 0, rowReset = false, tabStep = 0, pageStep = 0,
        )
        assertEquals(s0, c.renderSignature())

        // Hovering Tab 1 changes signature
        val tab1X = PaletteController.TAB_X[1] + 20f
        val tabY = XrShared.PALETTE_TAB_Y + 20f
        c.processFrameInput(
            open = true, pinned = false, handTrackingMode = false, dimFallback = false,
            hoverX = tab1X, hoverY = tabY, clickState = 0,
            rowStep = 0, rowReset = false, tabStep = 0, pageStep = 0,
        )
        val sHover = c.renderSignature()
        assertNotEquals(s0, sHover)

        // Pinning or dim fallback also updates signature
        c.syncNativeVisualState(open = true, pinned = true, handTrackingMode = false, dimFallback = true)
        assertNotEquals(sHover, c.renderSignature())
    }

    @Test
    fun transportBarAdjustAndConditionalDanmakuHitTestAndTrackExpansion() {
        // 「调整」 is always hit on right pill (1348..1512 inside 1536x112 strip)
        assertTrue(TransportBar.onAdjustButton(1430f))
        assertFalse(TransportBar.onAdjustButton(1290f))

        // 「弹」 only hits when showDanmakuToggle == true (1254..1332)
        assertTrue(TransportBar.onDanmakuButton(1290f, true))
        assertFalse(TransportBar.onDanmakuButton(1290f, false))

        // Track expands cleanly when 「弹」 is hidden (1064 -> 1158)
        assertTrue(TransportBar.trackEnd(false) > TransportBar.trackEnd(true))
        assertTrue(!TransportBar.trackFraction(1120f, false).isNaN())
        assertTrue(TransportBar.trackFraction(1120f, true).isNaN())
    }

    @Test
    fun paletteStoreAndSettingsMapperHandSwapAndEnvironmentPersistence() {
        val store = MapPrefsStore()

        // Default palette hand is LEFT (§3.8)
        assertEquals(PaletteHand.LEFT, StreamSettingsMapper.read(store, false).paletteHand)
        StreamSettingsMapper.setPaletteHand(store, PaletteHand.RIGHT)
        assertEquals(PaletteHand.RIGHT, StreamSettingsMapper.read(store, false).paletteHand)
        assertTrue(PreferenceConfiguration.readPreferences(store, false).vrPaletteOnRightHand)

        // Remember custom 3D room (GRAND_CINEMA) when toggling through 调暗 -> 影院
        StreamSettingsMapper.setEnvironment(store, VrEnvironmentPreset.GRAND_CINEMA)
        val g1 = PaletteStore.readGlobal(store)
        assertEquals(PaletteEnvironment.THEATER, g1.environment)

        PaletteStore.saveGlobal(store, g1.copy(environment = PaletteEnvironment.DIM))
        val gDim = PaletteStore.readGlobal(store)
        assertEquals(PaletteEnvironment.DIM, gDim.environment)
        assertTrue(PreferenceConfiguration.passthroughDimOn(store))

        // Switching back to 影院 restores remembered GRAND_CINEMA!
        PaletteStore.saveGlobal(store, gDim.copy(environment = PaletteEnvironment.THEATER))
        assertEquals(VrEnvironmentPreset.GRAND_CINEMA, StreamSettingsMapper.read(store, false).environment)
        assertFalse(PreferenceConfiguration.passthroughDimOn(store))
    }

    @Test
    fun bitmapSubtitleMimeAndLanguageHelpersMatchExpectedLabels() {
        assertTrue(LocalPlayerXR.isBitmapSubtitleMime("application/pgs"))
        assertTrue(LocalPlayerXR.isBitmapSubtitleMime("application/vobsub"))
        assertTrue(LocalPlayerXR.isBitmapSubtitleMime("application/dvbsubs"))
        assertFalse(LocalPlayerXR.isBitmapSubtitleMime("application/x-subrip"))
        assertFalse(LocalPlayerXR.isBitmapSubtitleMime("text/x-ssa"))

        assertEquals("简体中文", LocalPlayerXR.friendlyLanguageName("zh-CN"))
        assertEquals("繁體中文", LocalPlayerXR.friendlyLanguageName("zh-TW"))
        assertEquals("英语", LocalPlayerXR.friendlyLanguageName("en"))
        assertEquals("日语", LocalPlayerXR.friendlyLanguageName("ja"))
        assertEquals("", LocalPlayerXR.friendlyLanguageName("und"))
    }
}
