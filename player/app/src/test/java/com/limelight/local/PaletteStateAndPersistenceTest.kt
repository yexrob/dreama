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

        // Default 50° 中 (§3.8 / §6.12), 3.0 m, 0% 平面, 透视 -> all reset buttons hidden
        assertEquals("50° 中", c.formatSizeValue())
        assertEquals("3.0 m", c.formatDistValue())
        assertEquals("平面", c.formatCurveValue())
        for (r in 0..3) {
            assertFalse("row $r reset should be hidden at default", c.isRowResetVisible(XrShared.PALETTE_TAB_SCREEN, 0, r))
        }

        // Step size up to 62° 大 detent -> detent haptic
        c.stepRow(0, 11) // 61°
        assertEquals("61°", c.formatSizeValue())
        val detentHit = c.stepRow(0, 1) // 62° 大
        assertEquals("62° 大", c.formatSizeValue())
        assertEquals(XrShared.PALETTE_HAPTIC_DETENT, detentHit.hapticKind)

        // Curvature 5% steps
        c.stepRow(2, 9) // 45%
        assertEquals(45, c.global.screenCurvaturePct)
        assertEquals("45%", c.formatCurveValue())
        assertTrue(c.isRowResetVisible(XrShared.PALETTE_TAB_SCREEN, 0, 2))
        assertEquals(0xF, c.activeRowMask())

        // Switch to 影院: size, distance & curvature rows ALL disable with clear reasons,
        // remembered values are preserved, reset buttons hidden, only row 3 (环境) active!
        c.selectChip(3, PaletteEnvironment.THEATER.ordinal)
        assertEquals("影院房间里画面固定在银幕上", c.screenSizeDisabledMessage)
        assertEquals("影院房间里距离固定", c.screenDistDisabledMessage)
        assertEquals("影院房间里屏幕是平的", c.curvatureDisabledMessage)
        assertEquals(62f, c.global.screenFovDeg, 0.01f)
        assertEquals(45, c.global.screenCurvaturePct)
        assertFalse(c.isRowResetVisible(XrShared.PALETTE_TAB_SCREEN, 0, 0))
        assertFalse(c.isRowResetVisible(XrShared.PALETTE_TAB_SCREEN, 0, 1))
        assertFalse(c.isRowResetVisible(XrShared.PALETTE_TAB_SCREEN, 0, 2))
        assertEquals(0b1000, c.activeRowMask()) // only Environment row active
        // Trying to step size/dist in room is ignored and does not mutate saved values
        assertFalse(c.stepRow(0, 5).changed)
        assertFalse(c.stepRow(1, 3).changed)
        assertEquals(62f, c.global.screenFovDeg, 0.01f)
        assertEquals(3.0f, c.global.screenDistM, 0.01f)

        // Leaving room restores size/dist/curvature rows; unsupported headset disables curvature only
        c.selectChip(3, PaletteEnvironment.DIM.ordinal)
        assertNull(c.screenSizeDisabledMessage)
        assertNull(c.screenDistDisabledMessage)
        assertNull(c.curvatureDisabledMessage)
        assertEquals(45, c.global.screenCurvaturePct)
        assertEquals(0xF, c.activeRowMask())
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

        // Add text + bitmap subtitles and 2 audio tracks (resetting defaults for new item)
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
            forceResetDefaults = true,
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
    fun renderHashSuppressesIdleRedrawsAndChangesOnAnyVisualDelta() {
        val c = PaletteController(danmakuSupported = true)
        val h0 = c.renderHash()
        // Identical idle frame -> exact same 128-bit hash (0 redraws, 0 String allocations)
        c.processFrameInput(
            open = true, pinned = false, handTrackingMode = false, dimFallback = false,
            hoverX = -1f, hoverY = -1f, clickState = 0,
            rowStep = 0, rowReset = false, tabStep = 0, pageStep = 0,
        )
        assertEquals(h0, c.renderHash())

        // Hovering Tab 1 changes hash
        val tab1X = PaletteController.TAB_X[1] + 20f
        val tabY = XrShared.PALETTE_TAB_Y + 20f
        c.processFrameInput(
            open = true, pinned = false, handTrackingMode = false, dimFallback = false,
            hoverX = tab1X, hoverY = tabY, clickState = 0,
            rowStep = 0, rowReset = false, tabStep = 0, pageStep = 0,
        )
        val hHover = c.renderHash()
        assertNotEquals(h0, hHover)

        // Pinning or dim fallback also updates hash
        c.syncNativeVisualState(open = true, pinned = true, handTrackingMode = false, dimFallback = true)
        assertNotEquals(hHover, c.renderHash())
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

        store.resetApplyCount()
        PaletteStore.saveGlobal(store, g1.copy(environment = PaletteEnvironment.DIM))
        // G2 review #6: entire saveGlobal commits in ONE single batched apply()!
        assertEquals(1, store.applyCount)
        val gDim = PaletteStore.readGlobal(store)
        assertEquals(PaletteEnvironment.DIM, gDim.environment)
        assertTrue(PreferenceConfiguration.passthroughDimOn(store))

        // Switching back to 影院 restores remembered GRAND_CINEMA!
        store.resetApplyCount()
        PaletteStore.saveGlobal(store, gDim.copy(environment = PaletteEnvironment.THEATER))
        assertEquals(1, store.applyCount)
        assertEquals(VrEnvironmentPreset.GRAND_CINEMA, StreamSettingsMapper.read(store, false).environment)
        assertFalse(PreferenceConfiguration.passthroughDimOn(store))
    }

    @Test
    fun bitmapSubtitleMimeAndLocalizedLanguageHelpersMatchExpectedLabels() {
        val labels = TrackLabelStrings()
        assertTrue(PaletteTrackManager.isBitmapSubtitleMime("application/pgs"))
        assertTrue(PaletteTrackManager.isBitmapSubtitleMime("application/vobsub"))
        assertTrue(PaletteTrackManager.isBitmapSubtitleMime("application/dvbsubs"))
        assertFalse(PaletteTrackManager.isBitmapSubtitleMime("application/x-subrip"))
        assertFalse(PaletteTrackManager.isBitmapSubtitleMime("text/x-ssa"))

        assertEquals("简体中文", PaletteTrackManager.friendlyLanguageName("zh-CN", labels))
        assertEquals("繁體中文", PaletteTrackManager.friendlyLanguageName("zh-TW", labels))
        assertEquals("英语", PaletteTrackManager.friendlyLanguageName("en", labels))
        assertEquals("日语", PaletteTrackManager.friendlyLanguageName("ja", labels))
        assertEquals("", PaletteTrackManager.friendlyLanguageName("und", labels))
    }

    @Test
    fun pluginStereoHintSeparateFromUserOverrideAndExplicitAutoPersists() {
        val videoStore = MapPrefsStore()
        val key = PaletteStore.videoKey("plugin:demo", "sbs_movie", null)

        // Plugin hints SBS -> stored as defaultSourceFormat, NOT written into video overrides!
        val c1 = PaletteController(initialVideo = PaletteStore.readVideoOverrides(videoStore, key))
        c1.seedDefaultSourceFormat(StereoSourceFormat.SBS)
        assertEquals(StereoSourceFormat.SBS, c1.effectiveSourceFormat)
        assertNull(c1.video.sourceFormat)
        assertFalse(c1.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 1))

        // User explicitly overrides to AUTO -> persisted as AUTO in videoStore!
        val rAuto = c1.selectChip(1, StereoSourceFormat.AUTO.ordinal)
        assertTrue(rAuto.sourceFormatChanged)
        assertTrue(rAuto.saveVideoOverrides)
        assertEquals(StereoSourceFormat.AUTO, c1.effectiveSourceFormat)
        assertEquals(StereoSourceFormat.AUTO, c1.video.sourceFormat)
        assertTrue(c1.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 1))
        PaletteStore.saveVideoOverrides(videoStore, key, c1.video)

        // Reload same video with same plugin SBS hint -> user's explicit AUTO wins!
        val c2 = PaletteController(initialVideo = PaletteStore.readVideoOverrides(videoStore, key))
        c2.seedDefaultSourceFormat(StereoSourceFormat.SBS)
        assertEquals(StereoSourceFormat.AUTO, c2.effectiveSourceFormat)
        assertTrue(c2.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 1))

        // Reset row 1 restores plugin hint (SBS) and clears persisted override
        c2.resetRow(1)
        assertEquals(StereoSourceFormat.SBS, c2.effectiveSourceFormat)
        assertNull(c2.video.sourceFormat)
        assertFalse(c2.isRowResetVisible(XrShared.PALETTE_TAB_3D, 0, 1))
    }

    @Test
    fun trackResetPreventsSubtitleOffLeakAndDefaultsStayFixedAcrossSubsequentTracksChanged() {
        val labels = TrackLabelStrings()
        val rawSubsVideoA = listOf(
            RawTrackInfo(0, 0, "s1", "zh-CN", "简体", "application/x-subrip", isSelected = true, isDefaultFlag = true),
            RawTrackInfo(1, 0, "s2", "en", "English", "application/x-subrip", isSelected = false),
        )
        val rawAudsVideoA = listOf(
            RawTrackInfo(2, 0, "a1", "zh", "国语", "audio/ac3", channelCount = 6, isSelected = true, isDefaultFlag = true),
            RawTrackInfo(3, 0, "a2", "en", "English", "audio/ac3", channelCount = 6, isSelected = false),
        )

        // 1. Video A opens: capture natural defaults ONCE (zh sub, zh audio)
        val snapA1 = PaletteTrackManager.buildTracks(rawSubsVideoA, rawAudsVideoA, labels)
        val cA = PaletteController()
        cA.selectTab(XrShared.PALETTE_TAB_SUBTITLE)
        cA.setTracks(snapA1.subtitles, snapA1.audios, snapA1.naturalDefaultSubId, snapA1.naturalDefaultAudId)
        assertEquals("简体中文 · 简体", cA.selectedSubtitleTrack?.label)
        assertFalse(cA.isRowResetVisible(XrShared.PALETTE_TAB_SUBTITLE, 0, 0))
        assertFalse(cA.isRowResetVisible(XrShared.PALETTE_TAB_SUBTITLE, 0, 1))

        // 2. User switches Video A subtitle to English, then ExoPlayer fires follow-up onTracksChanged
        //    with English marked isSelected=true! Default MUST NOT follow current selection!
        cA.stepRow(0, 1)
        assertEquals("英语 · English", cA.selectedSubtitleTrack?.label)
        val rawSubsAfterSwitch = listOf(
            rawSubsVideoA[0].copy(isSelected = false),
            rawSubsVideoA[1].copy(isSelected = true),
        )
        val snapA2 = PaletteTrackManager.buildTracks(rawSubsAfterSwitch, rawAudsVideoA, labels)
        cA.setTracks(snapA2.subtitles, snapA2.audios, snapA2.naturalDefaultSubId, snapA2.naturalDefaultAudId)
        assertTrue("Reset button must stay visible after ExoPlayer onTracksChanged",
            cA.isRowResetVisible(XrShared.PALETTE_TAB_SUBTITLE, 0, 0))

        // Reset restores the original Chinese default!
        cA.resetRow(0)
        assertEquals("简体中文 · 简体", cA.selectedSubtitleTrack?.label)
        assertFalse(cA.isRowResetVisible(XrShared.PALETTE_TAB_SUBTITLE, 0, 0))

        // 3. User disables subtitles on Video A (plan disables TEXT)
        cA.stepRow(0, -1) // OFF
        assertNull(cA.selectedSubtitleTrack)
        val disablePlan = PaletteTrackManager.planSelection(true, cA.selectedSubtitleTrack, cA.selectedAudioTrack)
        assertTrue(disablePlan.disableText)

        // 4. Switching to Video B MUST issue planNewItemReset() (clears disableText & overrides)
        val resetPlan = PaletteTrackManager.planNewItemReset()
        assertTrue(resetPlan.resetAll)
        assertFalse(resetPlan.disableText)

        // Video B loads with its own natural default subtitle active (no leakage from Video A!)
        val cB = PaletteController()
        cB.selectTab(XrShared.PALETTE_TAB_SUBTITLE)
        val snapB = PaletteTrackManager.buildTracks(rawSubsVideoA, rawAudsVideoA, labels)
        cB.setTracks(snapB.subtitles, snapB.audios, snapB.naturalDefaultSubId, snapB.naturalDefaultAudId)
        assertNotNull(cB.selectedSubtitleTrack)
        assertEquals("简体中文 · 简体", cB.selectedSubtitleTrack?.label)

        // 5. Stable track IDs match across group-index reordering on relaunch
        val savedSubId = snapA1.subtitles[1].id // English track originally at group 1
        assertFalse("Stable track ID should not be pure index", savedSubId.contains("|idx:"))
        val reorderedSubs = listOf(
            rawSubsVideoA[1].copy(groupIndex = 5, trackIndex = 0),
            rawSubsVideoA[0].copy(groupIndex = 6, trackIndex = 0),
        )
        val snapReordered = PaletteTrackManager.buildTracks(reorderedSubs, rawAudsVideoA, labels)
        val cReload = PaletteController(initialVideo = VideoPaletteOverrides(subtitleId = savedSubId))
        cReload.setTracks(snapReordered.subtitles, snapReordered.audios,
            snapReordered.naturalDefaultSubId, snapReordered.naturalDefaultAudId)
        assertEquals("英语 · English", cReload.selectedSubtitleTrack?.label)
        assertEquals(5, cReload.selectedSubtitleTrack?.groupIndex)
    }

    @Test
    fun mailboxCoalescingAndDebouncerPreventPerFrameSavesAndAllocations() {
        val mailbox = PaletteInputMailbox()
        val drained = PaletteDrainedInput()

        // Idle frames with identical state do not wake worker
        assertFalse(mailbox.offerFromRenderThread(false, false, false, false, -1f, -1f, 0, 0, false, 0, 0))
        assertFalse(mailbox.drainOnWorker(drained))

        // Multiple discrete steps while worker is busy coalesce without dropping any step
        assertTrue(mailbox.offerFromRenderThread(true, false, false, false, 400f, 240f, 1, 1, false, 0, 0))
        // Second & third offers while drain is already scheduled return false (no redundant Handler posts)
        assertFalse(mailbox.offerFromRenderThread(true, false, false, false, 405f, 242f, 0, 1, false, 1, 0))
        assertFalse(mailbox.offerFromRenderThread(true, false, false, false, 410f, 244f, 0, 1, true, 0, -1))

        assertTrue(mailbox.drainOnWorker(drained))
        assertEquals(1, drained.clickState)
        assertEquals(400f, drained.clickX, 0.01f) // Click preserved original click coords!
        assertEquals(240f, drained.clickY, 0.01f)
        assertEquals(3, drained.rowStep)
        assertTrue(drained.rowReset)
        assertEquals(1, drained.tabStep)
        assertEquals(-1, drained.pageStep)

        // Save debouncer: 10 frames of slider drag (clickState == 2) trigger ZERO flushes!
        val debouncer = PaletteSaveDebouncer()
        for (i in 0 until 10) {
            val flags = debouncer.onFrameResult(isOpen = true, clickState = 2, saveGlobal = true, saveVideo = false)
            assertEquals(0, flags and PaletteSaveDebouncer.FLAG_FLUSH_GLOBAL)
            assertTrue((flags and PaletteSaveDebouncer.FLAG_SCHEDULE_DEBOUNCE) != 0)
        }
        // Releasing slider drag flushes global ONCE
        val releaseFlags = debouncer.onFrameResult(isOpen = true, clickState = 0, saveGlobal = false, saveVideo = false)
        assertTrue((releaseFlags and PaletteSaveDebouncer.FLAG_FLUSH_GLOBAL) != 0)
        // Subsequent idle frame flushes nothing
        assertEquals(0, debouncer.onFrameResult(isOpen = true, clickState = 0, saveGlobal = false, saveVideo = false))
    }

    @Test
    fun firstPlayGestureHintPersistsOnceAndSplitsCleanlyAndStereoAspectMatchesNative() {
        val store = MapPrefsStore()
        assertFalse(PaletteStore.isScreenGestureHintSeen(store))
        PaletteStore.markScreenGestureHintSeen(store)
        assertTrue(PaletteStore.isScreenGestureHintSeen(store))

        val lines = PaletteTrackManager.splitGestureHintForToast(
            "指着画面按住扳机，再推右摇杆：前后调远近 · 左右调大小"
        )
        assertEquals("指着画面按住扳机，再推右摇杆", lines[0])
        assertEquals("前后调远近 · 左右调大小", lines[1])

        // Full-SBS 3840x1080 -> 16:9, Half-SBS 1920x1080 -> 16:9, Full-OU 1920x2160 -> 16:9
        assertEquals(16f / 9f, PaletteController.effectiveVideoAspect(3840, 1080, StereoSourceFormat.SBS), 1e-3f)
        assertEquals(16f / 9f, PaletteController.effectiveVideoAspect(1920, 1080, StereoSourceFormat.SBS), 1e-3f)
        assertEquals(16f / 9f, PaletteController.effectiveVideoAspect(1920, 2160, StereoSourceFormat.OU), 1e-3f)
        assertEquals(16f / 9f, PaletteController.effectiveVideoAspect(1920, 1080, StereoSourceFormat.OU), 1e-3f)
    }
}
