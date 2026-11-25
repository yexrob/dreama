// Host unit tests for xr_overlay.c (shared G4 danmaku & G3 subtitle foundation):
// - Disparity-to-distance math & §6.10 table verification (2 m / 3 m / 5 m)
// - Comfort ceiling compression order (1.0 deg & >= 1.60 m: compress delta first, then far)
// - 2D / native 3D / AI-3D content depth resolution & asymmetric smoothing (0.05 deg/s retreat)
// - 64-bin 98th-percentile depth histogram in bottom-up GL row layout
// - Per-eye horizontal disparity shift sign (crossed parallax in front of screen)
// - Shared curved/flat/custom overlay surface placement (never behind curved screen!)
// - §6.5 composition layer budget verification across every local playback state

#include "check.h"
#include "xr_overlay.h"
#include <math.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static XrPosef identityPose(float x, float y, float z) {
    XrPosef p;
    memset(&p, 0, sizeof(p));
    p.orientation.w = 1.0f;
    p.position.x = x;
    p.position.y = y;
    p.position.z = z;
    return p;
}

static void testDisparityDistanceTableAndCeilings(void) {
    // §6.10 table at D = 3 m (IPD = 63 mm):
    // phi = 0.10 deg -> ~23 cm in front -> d ~ 2.77 m
    CHECK_NEAR(3.0f - overlayDisparityToDistM(0.10f, 3.0f), 0.23f, 0.02f);
    // phi = 0.25 deg -> ~52 cm in front -> d ~ 2.48 m
    CHECK_NEAR(3.0f - overlayDisparityToDistM(0.25f, 3.0f), 0.52f, 0.03f);
    // phi = 0.60 deg -> ~100 cm in front -> d ~ 2.00 m
    CHECK_NEAR(3.0f - overlayDisparityToDistM(0.60f, 3.0f), 1.00f, 0.03f);
    // phi = 1.00 deg -> ~136 cm in front -> d ~ 1.64 m
    CHECK_NEAR(overlayDisparityToDistM(1.00f, 3.0f), 1.64f, 0.03f);

    // At D = 2 m: 1.60 m minimum distance caps phi around 0.45 deg
    float max2m = overlayMaxPhiAtDistDeg(2.0f);
    CHECK_NEAR(max2m, 0.45f, 0.02f);
    CHECK_NEAR(overlayDisparityToDistM(max2m, 2.0f), 1.60f, 0.01f);

    // At D = 3 m and D = 5 m: 1.0 deg angular cap dominates (>= 1.60 m)
    CHECK_NEAR(overlayMaxPhiAtDistDeg(3.0f), 1.0f, 1e-4f);
    CHECK_NEAR(overlayMaxPhiAtDistDeg(5.0f), 1.0f, 1e-4f);
    CHECK(overlayDisparityToDistM(1.0f, 3.0f) >= 1.60f);
    CHECK(overlayDisparityToDistM(1.0f, 5.0f) >= 1.60f);

    // Worked example from §6.10: D = 3 m, content = 0.20 deg, auto k = 0, medium delta = 0.30 deg
    // far = 0.30 deg (~2.40 m), mid = 0.45 deg (~2.18 m), near = 0.60 deg (~2.00 m)
    OverlayDepthLayers dl;
    overlayComputeDepthLayers(0.20f, 1, 0, 0, 0.30f, 3.0f, &dl);
    CHECK_NEAR(dl.farPhiDeg, 0.30f, 1e-4f);
    CHECK_NEAR(dl.midPhiDeg, 0.45f, 1e-4f);
    CHECK_NEAR(dl.nearPhiDeg, 0.60f, 1e-4f);
    CHECK_NEAR(dl.farDistM, 2.40f, 0.03f);
    CHECK_NEAR(dl.midDistM, 2.18f, 0.03f);
    CHECK_NEAR(dl.nearDistM, 2.00f, 0.03f);

    // Ceiling compression order: at D = 2 m (maxPhi ~ 0.45 deg), content = 0.20 deg -> far = 0.30 deg,
    // requested delta = 0.30 deg exceeds 0.45 deg -> delta is compressed to ~0.15 deg while far stays 0.30!
    overlayComputeDepthLayers(0.20f, 1, 0, 0, 0.30f, 2.0f, &dl);
    CHECK_NEAR(dl.farPhiDeg, 0.30f, 1e-4f);
    CHECK_NEAR(dl.nearPhiDeg, max2m, 1e-3f);
    CHECK(dl.appliedDeltaDeg < 0.20f && dl.appliedDeltaDeg > 0.10f);
    CHECK(dl.nearDistM >= OVERLAY_MIN_EYE_DIST_M - 1e-3f);

    // Extreme case: farRaw itself exceeds ceiling -> delta becomes 0 and far clamps to maxPhi
    overlayComputeDepthLayers(0.50f, 1, 2, 0, 0.45f, 2.0f, &dl);
    CHECK_NEAR(dl.farPhiDeg, max2m, 1e-3f);
    CHECK_NEAR(dl.nearPhiDeg, max2m, 1e-3f);
    CHECK_NEAR(dl.appliedDeltaDeg, 0.0f, 1e-4f);
    CHECK(dl.nearDistM >= OVERLAY_MIN_EYE_DIST_M - 1e-3f);

    // Fixed float mode: k = 0 -> 0 deg (flat on screen), k = 12 -> 0.60 deg
    overlayComputeDepthLayers(0.40f, 0, 0, 0, 0.0f, 3.0f, &dl);
    CHECK_NEAR(dl.farPhiDeg, 0.0f, 1e-4f);
    CHECK_NEAR(dl.nearPhiDeg, 0.0f, 1e-4f);
    overlayComputeDepthLayers(0.0f, 0, 0, 12, 0.30f, 3.0f, &dl);
    CHECK_NEAR(dl.farPhiDeg, 0.60f, 1e-4f);
    CHECK_NEAR(dl.nearPhiDeg, 0.90f, 1e-4f);
}

static void testContentDepthSmoothingAndHistogram98(void) {
    // 2D -> 0, native SBS/OU -> 0.25 deg, AI 3D -> measured
    CHECK_NEAR(overlayResolveContentPhi(0, STEREO_SRC_AUTO, 0.40f), 0.0f, 1e-4f);
    CHECK_NEAR(overlayResolveContentPhi(0, STEREO_SRC_SBS, 0.0f), 0.25f, 1e-4f);
    CHECK_NEAR(overlayResolveContentPhi(1, STEREO_SRC_OU, 0.0f), 0.25f, 1e-4f);
    CHECK_NEAR(overlayResolveContentPhi(1, STEREO_SRC_AUTO, 0.32f), 0.32f, 1e-4f);

    // Asymmetric temporal smoothing: instant forward, max 0.05 deg/s retreat
    CHECK_NEAR(overlayStepContentDepth(0.10f, 0.35f, 0.016f), 0.35f, 1e-4f);
    CHECK_NEAR(overlayStepContentDepth(0.35f, 0.00f, 1.0f), 0.30f, 1e-4f);
    CHECK_NEAR(overlayStepContentDepth(0.35f, 0.34f, 1.0f), 0.34f, 1e-4f);

    // 64-bin 98th percentile histogram on 10x10 bottom-up depth map:
    // Top half (rows 5..9 in bottom-up GL order) has 48 background pixels at 0.3 and 2 foreground at 0.9;
    // Bottom half (rows 0..4) has 1.0 everywhere (should be ignored for HALF-screen danmaku!).
    float map[100];
    for (int y = 0; y < 5; y++) {
        for (int x = 0; x < 10; x++) map[y * 10 + x] = 1.0f;
    }
    for (int y = 5; y < 10; y++) {
        for (int x = 0; x < 10; x++) map[y * 10 + x] = 0.30f;
    }
    map[9 * 10 + 0] = 0.90f; // 2 out of 50 pixels = 4% -> 98th percentile lands at 0.90!
    map[9 * 10 + 1] = 0.90f;
    // conv = 0.50, sep = 0.005, fov = 50 deg -> phi = 2 * 0.005 * (0.90 - 0.50) * 50 = 0.20 deg
    float phiHalf = overlayHistogram98Percentile(map, 10, 10, 0.0f, 0.5f, 0.50f, 0.005f, 50.0f);
    CHECK_NEAR(phiHalf, 0.20f, 0.015f);

    // With only 1 hot outlier pixel (2% = 1/50 -> 49/50 = 98% is still 0.30 < conv 0.50) -> 0 deg!
    map[9 * 10 + 1] = 0.30f;
    float phiOutlier = overlayHistogram98Percentile(map, 10, 10, 0.0f, 0.5f, 0.50f, 0.005f, 50.0f);
    CHECK_NEAR(phiOutlier, 0.0f, 1e-4f);

    // Per-eye horizontal UV shift: left eye (+) right, right eye (-) left, magnitude = phi/(2*fov)
    float shiftL = overlayEyeShiftNormU(0.60f, 50.0f, 0);
    float shiftR = overlayEyeShiftNormU(0.60f, 50.0f, 1);
    CHECK_NEAR(shiftL, 0.006f, 1e-5f);
    CHECK_NEAR(shiftR, -0.006f, 1e-5f);
}

static void testSharedCurvedAndChordOverlaySurfaces(void) {
    XrPosef screen = identityPose(0.0f, 0.0f, -3.0f);
    float w = 0.0f, r = 0.0f;
    screenPlacementForFov(50.0f, 3.0f, 0.60f, 1, &w, &r);
    float h = w * (9.0f / 16.0f);
    float halfAngle = cylinderAngle(w, r) * 0.5f;
    float chordZ = screenCurvedChordZ(r, halfAngle);
    CHECK(chordZ > 0.15f); // 60% curvature brings edges significantly toward viewer

    // 1. Follow-screen mode on curved screen -> coaxial cylinder, radius slightly smaller than screen
    OverlaySurfaceGeom surf;
    CHECK(overlayComputeSurface(screen, w, h, r, 1, 1, 0,
                                OVERLAY_CURVE_FOLLOW, 0.0f, 3.0f,
                                0.0f, 0.5f, 0.0f, &surf) == 1);
    CHECK(surf.useCylinder == 1);
    CHECK_NEAR(surf.radius, r - OVERLAY_PROUD_M, 1e-4f);
    CHECK_NEAR(surf.centralAngle, cylinderAngle(w, r), 1e-4f);
    // Top half center sits above screen center (+Y > 0)
    CHECK(surf.axisPose.position.y > 0.2f);
    CHECK_NEAR(surf.axisPose.position.z, -3.0f + r, 1e-4f);

    // 2. Flat overlay mode on curved screen -> quad placed ON CHORD (+chordZ + proud) so edges never clip behind!
    CHECK(overlayComputeSurface(screen, w, h, r, 1, 1, 0,
                                OVERLAY_CURVE_FLAT, 0.0f, 3.0f,
                                0.0f, 0.5f, 0.0f, &surf) == 1);
    CHECK(surf.useCylinder == 0);
    CHECK(surf.quadPose.position.z >= -3.0f + chordZ + OVERLAY_PROUD_M - 1e-4f);
    CHECK(surf.quadPose.position.y > 0.2f);

    // 3. Custom curve 20% (flatter than 60% screen) -> tangent at edges, NEVER behind curved screen!
    CHECK(overlayComputeSurface(screen, w, h, r, 1, 1, 0,
                                OVERLAY_CURVE_CUSTOM, 20.0f, 3.0f,
                                0.0f, 0.5f, 0.0f, &surf) == 1);
    CHECK(surf.useCylinder == 1);
    float ovCenterZ = surf.axisPose.position.z - surf.radius;
    float ovEdgeZ = ovCenterZ + screenCurvedChordZ(surf.radius, surf.centralAngle * 0.5f);
    CHECK(ovCenterZ >= -3.0f + OVERLAY_PROUD_M - 1e-3f);
    CHECK(ovEdgeZ >= -3.0f + chordZ + OVERLAY_PROUD_M - 1e-3f);

    // 4. Custom curve 100% (more curved than 60% screen) -> tangent at center, edges even further in front!
    CHECK(overlayComputeSurface(screen, w, h, r, 1, 1, 0,
                                OVERLAY_CURVE_CUSTOM, 100.0f, 3.0f,
                                0.0f, 0.5f, 0.0f, &surf) == 1);
    CHECK(surf.useCylinder == 1);
    ovCenterZ = surf.axisPose.position.z - surf.radius;
    ovEdgeZ = ovCenterZ + screenCurvedChordZ(surf.radius, surf.centralAngle * 0.5f);
    CHECK(ovCenterZ >= -3.0f + OVERLAY_PROUD_M - 1e-3f);
    CHECK(ovEdgeZ >= -3.0f + chordZ + OVERLAY_PROUD_M - 1e-3f);

    // 5. G3 subtitle reuse: bottom strip [0.75, 1.0] with real geometric pop-out 0.60 m in front!
    CHECK(overlayComputeSurface(screen, w, h, r, 1, 1, 0,
                                OVERLAY_CURVE_FOLLOW, 0.0f, 3.0f,
                                0.75f, 1.0f, 0.60f, &surf) == 1);
    CHECK(surf.useCylinder == 1);
    CHECK_NEAR(surf.radius, r - (OVERLAY_PROUD_M + 0.60f), 1e-4f);
    CHECK(surf.axisPose.position.y < -0.2f); // bottom strip sits below screen center

    // 6. Room mode or !cylinderSupported always forces flat quad
    CHECK(overlayComputeSurface(screen, w, h, r, 1, 1, 1 /* roomOn */,
                                OVERLAY_CURVE_FOLLOW, 0.0f, 3.0f,
                                0.0f, 0.5f, 0.0f, &surf) == 1);
    CHECK(surf.useCylinder == 0);
    CHECK(overlayComputeSurface(screen, w, h, r, 1, 0 /* !cyl */, 0,
                                OVERLAY_CURVE_CUSTOM, 50.0f, 3.0f,
                                0.0f, 0.5f, 0.0f, &surf) == 1);
    CHECK(surf.useCylinder == 0);
}

static void testSection65LayerBudgetAllStates(void) {
    OverlayLayerFlags f;

    // 1. Immersive: dimmer(1) + video(2) + danmaku(2) + subtitle(1) = 6
    memset(&f, 0, sizeof(f));
    f.roomOrDimmer = 1; f.videoEyes = 2; f.danmakuEyes = 2; f.subtitle = 1;
    CHECK(overlayCountLocalLayers(&f) == 6);

    // 2. Immersive + Toast = 7
    f.toastOrReadout = 1;
    CHECK(overlayCountLocalLayers(&f) == 7);

    // 3. Control bar visible (toast suppressed by §6.5/§6.7): +bar(1) +ray(2) +model(1) = 10
    f.transportBar = 1; f.pointerRay = 1; f.controllerModel = 1;
    CHECK(overlayCountLocalLayers(&f) == 10);

    // 4. Palette + Control Bar = 11
    f.palette = 1;
    CHECK(overlayCountLocalLayers(&f) == 11);

    // 5. Palette + Control Bar + Popup Card = 12
    f.popupCard = 1;
    CHECK(overlayCountLocalLayers(&f) == 12);

    // 6. Worst case with Ambilight Glow ON = 13 <= LAYER_BUDGET_DESIGN_MAX (13) <= 16!
    f.glow = 1;
    int worst = overlayCountLocalLayers(&f);
    CHECK(worst == 13);
    CHECK(worst <= LAYER_BUDGET_DESIGN_MAX);
    CHECK(worst <= LAYER_BUDGET_HARDWARE_MAX);

    // 7. No active danmaku saves 2 layers (13 -> 11)
    f.danmakuEyes = 0;
    CHECK(overlayCountLocalLayers(&f) == 11);
}

static void testSlotGenerationTableGating(void) {
    DanmakuSlotTable t;
    danmakuSlotTableClear(&t);

    // Un-uploaded slot must NEVER be considered ready
    CHECK(danmakuSlotTableIsReady(&t, 0, 1) == 0);
    CHECK(danmakuSlotTableIsReady(&t, 5, 1) == 0);
    // Out-of-bounds & generation 0 safely return 0
    CHECK(danmakuSlotTableIsReady(&t, -1, 1) == 0);
    CHECK(danmakuSlotTableIsReady(&t, DANMAKU_MAX_SLOTS, 1) == 0);
    CHECK(danmakuSlotTableIsReady(&t, 0, 0) == 0);

    // Upload generation 1 into slot 5 -> ready ONLY for generation 1
    danmakuSlotTableMarkUploaded(&t, 5, 1);
    CHECK(danmakuSlotTableIsReady(&t, 5, 1) == 1);
    // Slot reuse with generation 2 BEFORE upload lands -> MUST be skipped (no stale text!)
    CHECK(danmakuSlotTableIsReady(&t, 5, 2) == 0);

    // Once generation 2 upload lands -> gen 2 ready, old gen 1 no longer matches
    danmakuSlotTableMarkUploaded(&t, 5, 2);
    CHECK(danmakuSlotTableIsReady(&t, 5, 2) == 1);
    CHECK(danmakuSlotTableIsReady(&t, 5, 1) == 0);

    // Atlas clear invalidates all landed generations immediately
    danmakuSlotTableClear(&t);
    CHECK(danmakuSlotTableIsReady(&t, 5, 2) == 0);
}

static void testAnchorMonotonicSnapshotClock(void) {
    DanmakuClockTracker c;
    danmakuClockReset(&c);

    // First anchor at t=10.000 s, mono=100.000 s, playing
    int64_t t0Ns = 100000000000LL;
    danmakuClockSetAnchor(&c, 10000, t0Ns, 0, 1);
    float prev = danmakuClockSample(&c, t0Ns, 0.0111f);
    CHECK_NEAR(prev, 10.0f, 0.02f);

    // Simulate 90 Hz frames (11.11 ms) while 20 Hz snapshots arrive with +/-14 ms jitter:
    // clock MUST stay strictly monotonic (never step backward) and never jump +/-14 ms!
    int64_t nowNs = t0Ns;
    for (int frame = 1; frame <= 90; frame++) {
        nowNs += 11111111LL;
        if (frame % 4 == 0) {
            int64_t jitterMs = (frame % 8 == 0) ? -14 : 14;
            int64_t anchorMs = 10000 + (int64_t)((nowNs - t0Ns) / 1000000LL) + jitterMs;
            danmakuClockSetAnchor(&c, anchorMs, nowNs, 0, 0);
        }
        float cur = danmakuClockSample(&c, nowNs, 0.011111f);
        CHECK(cur >= prev); // strictly monotonic: zero backward step
        float step = cur - prev;
        CHECK(step <= 0.011111f * 1.26f + 1e-4f); // bounded smooth slew: zero +/-14 ms jump
        prev = cur;
    }
    // After 1.0 s of 90 Hz frames, total clock should be right around 11.0 s
    CHECK_NEAR(prev, 11.0f, 0.04f);

    // Pause freezes clock even if wall time advances and jittery pause anchor arrives slightly earlier
    danmakuClockSetAnchor(&c, (int64_t)(prev * 1000.0f) - 10, nowNs, 1, 0);
    float frozen1 = danmakuClockSample(&c, nowNs + 500000000LL, 0.0111f);
    float frozen2 = danmakuClockSample(&c, nowNs + 2000000000LL, 0.0111f);
    CHECK(frozen1 >= prev);
    CHECK_NEAR(frozen2, frozen1, 1e-5f);

    // Explicit hardReset (seek / new item) snaps cleanly backward to 0.0 s
    danmakuClockSetAnchor(&c, 0, nowNs + 2000000000LL, 0, 1);
    float afterReset = danmakuClockSample(&c, nowNs + 2000000000LL, 0.0111f);
    CHECK_NEAR(afterReset, 0.0f, 0.02f);
}

static void testRealProductionLayerBudgetAndDropLadder(void) {
    // Case 1: Local playback worst-case steady frame (13 layers: room+glow+2 video+2 danmaku+
    // transport+palette+popup+2 ray+models+toast) against 16-layer hardware cap -> all 13 kept!
    int local13[13] = {
        OVERLAY_SLOT_OTHER, // room/dimmer
        OVERLAY_SLOT_OTHER, // glow
        OVERLAY_SLOT_OTHER, // video L
        OVERLAY_SLOT_OTHER, // video R
        OVERLAY_SLOT_DANMAKU, // danmaku L
        OVERLAY_SLOT_DANMAKU, // danmaku R
        OVERLAY_SLOT_OTHER, // transport
        OVERLAY_SLOT_OTHER, // palette
        OVERLAY_SLOT_OTHER, // popup
        OVERLAY_SLOT_MODELS,
        OVERLAY_SLOT_OTHER, // beam
        OVERLAY_SLOT_OTHER, // dot
        OVERLAY_SLOT_TOAST
    };
    int keep[24];
    int kept = overlayApplyLayerBudget(local13, 13, 32 /* Quest reports 32 -> clamped to 16 */, keep);
    CHECK(kept == 13);

    // Case 2: Over-limit 19-layer frame (12 OTHER + 2 DANMAKU + toast + models + cogHover +
    // cogButton + cogClock = 19) -> drops toast, models, cogHover (3 dropped -> 16 kept),
    // keeping danmaku & video intact!
    int frame19[19];
    for (int i = 0; i < 12; i++) frame19[i] = OVERLAY_SLOT_OTHER;
    frame19[12] = OVERLAY_SLOT_DANMAKU;
    frame19[13] = OVERLAY_SLOT_DANMAKU;
    frame19[14] = OVERLAY_SLOT_COG_BUTTON;
    frame19[15] = OVERLAY_SLOT_COG_CLOCK;
    frame19[16] = OVERLAY_SLOT_COG_HOVER;
    frame19[17] = OVERLAY_SLOT_MODELS;
    frame19[18] = OVERLAY_SLOT_TOAST;
    kept = overlayApplyLayerBudget(frame19, 19, 32, keep);
    CHECK(kept == 16);
    CHECK(keep[18] == 0); // toast dropped 1st
    CHECK(keep[17] == 0); // models dropped 2nd
    CHECK(keep[16] == 0); // cogHover dropped 3rd
    CHECK(keep[14] == 1); // cogButton kept
    CHECK(keep[15] == 1); // cogClock kept
    CHECK(keep[12] == 1 && keep[13] == 1); // danmaku kept

    // Case 3: Extreme 22-layer frame (15 OTHER + 2 DANMAKU + 5 droppable UI) -> drops all 5 UI
    // AND both danmaku eyes together so frame lands at 15 <= 16 (never rejected whole by xrEndFrame!)
    int frame22[22];
    for (int i = 0; i < 15; i++) frame22[i] = OVERLAY_SLOT_OTHER;
    frame22[15] = OVERLAY_SLOT_DANMAKU;
    frame22[16] = OVERLAY_SLOT_DANMAKU;
    frame22[17] = OVERLAY_SLOT_COG_BUTTON;
    frame22[18] = OVERLAY_SLOT_COG_CLOCK;
    frame22[19] = OVERLAY_SLOT_COG_HOVER;
    frame22[20] = OVERLAY_SLOT_MODELS;
    frame22[21] = OVERLAY_SLOT_TOAST;
    kept = overlayApplyLayerBudget(frame22, 22, 32, keep);
    CHECK(kept == 15);
    CHECK(kept <= OVERLAY_HARD_MAX_LAYERS);
    CHECK(keep[15] == 0 && keep[16] == 0); // both danmaku eyes dropped cleanly together
    for (int i = 0; i < 15; i++) CHECK(keep[i] == 1); // core video/UI intact
}

int main(void) {
    testDisparityDistanceTableAndCeilings();
    testContentDepthSmoothingAndHistogram98();
    testSharedCurvedAndChordOverlaySurfaces();
    testSection65LayerBudgetAllStates();
    testSlotGenerationTableGating();
    testAnchorMonotonicSnapshotClock();
    testRealProductionLayerBudgetAndDropLadder();
    return checksDone("xr_overlay");
}
