// Comprehensive host-side unit tests for G1:
// - Screen size/distance/inches & continuous curvature math (ux-spec.md §6.12)
// - Coaxial curved strip geometry & TransportBar hit test
// - Grip sphere drag distance invariance
// - §6.5 composition layer budget verification
// - Exhaustive table-driven coverage of every cell & all 12 rules of ux-spec.md §6.4
#include <math.h>
#include <string.h>

#include "check.h"
#include "xr_screen_ctrl.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define MS(x) ((int64_t)(x) * 1000000LL)

static ScreenInputFrame baseFrame(int64_t nowNs, float dt) {
    ScreenInputFrame f;
    memset(&f, 0, sizeof(f));
    f.nowNs = nowNs;
    f.dt = dt;
    f.target = SCREEN_TARGET_SCREEN;
    f.triggerAdjustEnabled = 1;
    f.aimDir.x = 0.0f;
    f.aimDir.y = 0.0f;
    f.aimDir.z = -1.0f;
    f.headRight.x = 1.0f;
    f.headForward.z = -1.0f;
    f.curFovDeg = 50.0f;
    f.curDistM = 3.0f;
    return f;
}

static void testInchesAndNorms(void) {
    // Exact spec §6.12 worked examples
    CHECK(screenDiagonalInches(50.0f, 3.0f) == 126);
    CHECK(screenDiagonalInches(50.0f, 3.4f) == 143);
    CHECK(screenDiagonalInches(62.0f, 3.0f) == 163);

    // Preset indices match 40 / 50 / 62 / 80 deg
    CHECK(screenSizePresetIndex(40.0f) == SCREEN_PRESET_SMALL);
    CHECK(screenSizePresetIndex(50.0f) == SCREEN_PRESET_MEDIUM);
    CHECK(screenSizePresetIndex(62.0f) == SCREEN_PRESET_LARGE);
    CHECK(screenSizePresetIndex(80.0f) == SCREEN_PRESET_GIANT);
    CHECK(screenSizePresetIndex(54.0f) == SCREEN_PRESET_NONE);

    // Log distance scale and linear size scale endpoints & detents
    CHECK_NEAR(screenDistNorm(2.0f), 0.0f, 1e-5);
    CHECK_NEAR(screenDistNorm(8.0f), 1.0f, 1e-5);
    CHECK_NEAR(screenDistNorm(4.0f), 0.5f, 1e-5); // geometric midpoint of 2..8
    CHECK_NEAR(screenSizeNorm(30.0f), 0.0f, 1e-5);
    CHECK_NEAR(screenSizeNorm(60.0f), 0.5f, 1e-5);
    CHECK_NEAR(screenSizeNorm(90.0f), 1.0f, 1e-5);

    // Curvature 5% quantization
    CHECK_NEAR(screenQuantizeCurve(0.0f), 0.0f, 1e-5);
    CHECK_NEAR(screenQuantizeCurve(0.02f), 0.0f, 1e-5);
    CHECK_NEAR(screenQuantizeCurve(0.03f), 0.05f, 1e-5);
    CHECK_NEAR(screenQuantizeCurve(0.47f), 0.45f, 1e-5);
    CHECK_NEAR(screenQuantizeCurve(0.48f), 0.50f, 1e-5);
    CHECK_NEAR(screenQuantizeCurve(1.0f), 1.0f, 1e-5);
}

static void testCurvatureFormulaAndAngularInvariance(void) {
    // R = D / c, flat when c <= 0.005
    CHECK_NEAR(screenCurvatureRadius(3.0f, 0.0f), 0.0f, 1e-5);
    CHECK_NEAR(screenCurvatureRadius(3.0f, 0.004f), 0.0f, 1e-5);
    CHECK_NEAR(screenCurvatureRadius(3.0f, 1.0f), 3.0f, 1e-4);
    CHECK_NEAR(screenCurvatureRadius(3.0f, 0.5f), 6.0f, 1e-4);
    CHECK_NEAR(screenCurvatureRadius(4.0f, 0.25f), 16.0f, 1e-4);
    CHECK_NEAR(screenCurvatureRadius(3.0f, 0.05f), 60.0f, 1e-3);

    // At c = 1 (R = D), arc length equals D * fov_rad
    float arc1 = screenCurvedArcWidth(60.0f, 3.0f, 1.0f);
    CHECK_NEAR(arc1, 3.0f * (float)M_PI / 3.0f, 1e-3);

    // Angular width invariance across all combinations of FOV, distance, curvature
    const float fovs[] = { 30.0f, 40.0f, 50.0f, 62.0f, 80.0f, 90.0f };
    const float dists[] = { 2.0f, 3.0f, 5.0f, 8.0f };
    const float curves[] = { 0.0f, 0.05f, 0.25f, 0.5f, 0.75f, 1.0f };
    for (int fi = 0; fi < 6; fi++) {
        for (int di = 0; di < 4; di++) {
            for (int ci = 0; ci < 6; ci++) {
                float fov = fovs[fi];
                float d = dists[di];
                float c = curves[ci];
                int curved = c > SCREEN_CURVE_EPS;
                float r = screenCurvatureRadius(d, c);
                float w = screenCurvedArcWidth(fov, d, c);
                float back = screenFovFromPlacement(w, d, r, curved);
                CHECK_NEAR(back, fov, 0.02);
            }
        }
    }

    // Unified screenPlacementForFov (Review #7 & #8):
    // 2 m, 100% curvature, oversized target FOV clamps to 90 deg (never wraps 229 deg!)
    float w2, r2;
    screenPlacementForFov(160.0f, 2.0f, 1.0f, 1, &w2, &r2);
    CHECK_NEAR(r2, 2.0f, 1e-4);
    CHECK_NEAR(cylinderAngle(w2, r2), (float)M_PI * 0.5f, 1e-3); // exact 90 deg arc
    CHECK_NEAR(screenFovFromPlacement(w2, 2.0f, r2, 1), 90.0f, 0.05);

    // 8 m @ 62 deg and 90 deg flat are NEVER truncated to 8 m (SCREEN_MAX_WIDTH = 16 m)
    float w8_62, r8_62, w8_90, r8_90;
    screenPlacementForFov(62.0f, 8.0f, 0.0f, 1, &w8_62, &r8_62);
    CHECK_NEAR(w8_62, screenFlatWidth(62.0f, 8.0f), 1e-3);
    CHECK(w8_62 > 9.5f && w8_62 <= SCREEN_MAX_WIDTH);
    screenPlacementForFov(90.0f, 8.0f, 0.0f, 1, &w8_90, &r8_90);
    CHECK_NEAR(w8_90, 16.0f, 1e-3);

    // Legacy pose migration (Review #9):
    // Old formula stored R = D * (1 + 3*(1-c)) = 7.95 m at D=3 m, c=0.45, W=3.0 m.
    // Migrating recomputes R = D/c = 6.667 m while preserving exact angular FOV.
    XrPosef legacyPose = { { 0.0f, 0.0f, 0.0f, 1.0f }, { 0.0f, 0.0f, -3.0f } };
    Vec3 origin = { 0.0f, 0.0f, 0.0f };
    float oldR = 3.0f * (1.0f + 3.0f * (1.0f - 0.45f));
    float oldW = 3.0f;
    float oldFov = screenFovFromPlacement(oldW, 3.0f, oldR, 1);
    float migW = 0.0f, migR = 0.0f;
    screenMigratePoseGeometry(legacyPose, origin, oldW, oldR, 0.45f, 1, &migW, &migR);
    CHECK_NEAR(migR, 3.0f / 0.45f, 1e-3);
    CHECK_NEAR(screenFovFromPlacement(migW, 3.0f, migR, 1), oldFov, 0.05);
    // Already-migrated pose is stable (idempotent)
    float migW2 = 0.0f, migR2 = 0.0f;
    screenMigratePoseGeometry(legacyPose, origin, migW, migR, 0.45f, 1, &migW2, &migR2);
    CHECK_NEAR(migW2, migW, 1e-4);
    CHECK_NEAR(migR2, migR, 1e-4);

    // Unified viewer distance reference (Review #10): non-origin head position
    XrPosef offPose = { { 0.0f, 0.0f, 0.0f, 1.0f }, { 0.3f, 0.2f, -3.5f } };
    Vec3 leanedHead = { 0.3f, 0.2f, -0.5f };
    CHECK_NEAR(screenDistanceFrom(offPose, leanedHead), 3.0f, 1e-4);

    // Exact §6.12 readout dimensions fit inside toast swapchain (Review #12)
    CHECK(READOUT_TEX_W == 960 && READOUT_TEX_H == 112);
    CHECK(READOUT_TEX_W <= TOAST_TEX_W && READOUT_TEX_H <= TOAST_TEX_H);
    CHECK_NEAR(SCREEN_READOUT_FOV_W_DEG, 24.0f, 1e-5);
    CHECK_NEAR(SCREEN_READOUT_FOV_H_DEG, 2.8f, 1e-5);
}

static void testCurvedTransportStripAndHit(void) {
    XrPosef screen = { { 0.0f, 0.0f, 0.0f, 1.0f }, { 0.0f, 0.0f, -3.0f } };
    float fov = 62.0f;
    float d = 3.0f;
    float c = 0.5f;
    float r = screenCurvatureRadius(d, c);
    float w = screenCurvedArcWidth(fov, d, c);
    float videoAspect = 16.0f / 9.0f;
    float h = w / videoAspect;
    float texAspect = (float)TRANSPORT_WIDTH / (float)TRANSPORT_HEIGHT;

    CurvedStrip strip;
    CHECK(screenCurvedStrip(screen, w, h, r, TRANSPORT_MARGIN_FRAC, texAspect,
                            TRANSPORT_PROUD_M, &strip) == 1);
    // Coaxial with video cylinder, proud by 1.2 cm toward viewer
    CHECK_NEAR(strip.radius, r - TRANSPORT_PROUD_M, 1e-5);
    CHECK_NEAR(strip.axisPose.position.z, screen.position.z + r, 1e-5);
    CHECK_NEAR(strip.axisPose.position.x, 0.0f, 1e-5);
    CHECK(strip.axisPose.position.y < 0.0f);
    CHECK_NEAR(strip.centralAngle, (1.0f - 2.0f * TRANSPORT_MARGIN_FRAC) * cylinderAngle(w, r),
               1e-4);
    CHECK_NEAR(strip.aspectRatio, texAspect, 1e-5);

    // Chord z offset is 0 at 0 angle and positive for curved half-angle
    CHECK_NEAR(screenCurvedChordZ(r, 0.0f), 0.0f, 1e-5);
    CHECK_NEAR(screenCurvedChordZ(3.0f, (float)M_PI / 3.0f), 1.5f, 1e-4);

    // TransportBar hit test matches Java bounds
    CHECK(screenTransportBarHit(0.5f, 0.90f, videoAspect) == 1);
    CHECK(screenTransportBarHit(0.01f, 0.90f, videoAspect) == 0);  // left margin
    CHECK(screenTransportBarHit(0.99f, 0.90f, videoAspect) == 0);  // right margin
    CHECK(screenTransportBarHit(0.5f, 0.50f, videoAspect) == 0);   // screen center
    CHECK(screenTransportBarHit(0.5f, 0.10f, videoAspect) == 0);   // screen top
}

static void testSphereDragPreservesDistance(void) {
    XrPosef screen = { { 0.0f, 0.0f, 0.0f, 1.0f }, { 0.0f, 0.0f, -3.5f } };
    XrPosef grabAim = { { 0.0f, 0.0f, 0.0f, 1.0f }, { 0.2f, -0.2f, -0.3f } };
    Vec3 up = { 0.0f, 1.0f, 0.0f };
    XrPosef curAim = grabAim;
    curAim.orientation = axisAngleQuat(up, 0.45f);
    Vec3 head = { 0.0f, 0.0f, 0.0f };

    XrPosef moved = screenSphereDrag(screen, grabAim, curAim, head, 0.0f, 0.0f);
    float dist = sqrtf(moved.position.x * moved.position.x
                       + moved.position.y * moved.position.y
                       + moved.position.z * moved.position.z);
    CHECK_NEAR(dist, 3.5f, 1e-4);
    // Moved horizontally (left because +Y rotation swings -Z toward -X)
    CHECK(moved.position.x < -1.0f);
    // Screen faces back toward viewer (+Z local points toward head)
    Vec3 fwd = { 0.0f, 0.0f, 1.0f };
    Vec3 screenNormal = quatRotate(moved.orientation, fwd);
    Vec3 toHead = vecNorm(vecSub(head, (Vec3){ moved.position.x, moved.position.y, moved.position.z }));
    CHECK_NEAR(vecDot(screenNormal, toHead), 1.0f, 1e-3);
}

static void testLayerBudgetPerSpec65(void) {
    // Shared xr_layers.c predicates (Review #13):
    // Control bar hidden during local screen adjust; PC streaming unaffected
    CHECK(screenShowTransportLayer(1, 0, 1, 0, 1) == 1);
    CHECK(screenShowTransportLayer(1, 1, 1, 0, 1) == 0);
    CHECK(screenShowTransportLayer(0, 1, 0, 1, 1) == 1);
    // Bar area suppressed during local screen adjust
    CHECK(screenShowBarArea(1, 0, 0, 1) == 1);
    CHECK(screenShowBarArea(1, 1, 0, 1) == 0);
    // Readout & notice toast are mutually exclusive on the single toast slot
    CHECK(screenShowReadoutLayer(1, 1, 1, TOAST_SCREEN_READOUT) == 1);
    CHECK(screenShowNoticeToastLayer(1, 1, 1, TOAST_SCREEN_READOUT, 1, 1) == 0);
    CHECK(screenShowReadoutLayer(1, 0, 1, TOAST_SCREEN_READOUT) == 0);
    CHECK(screenShowNoticeToastLayer(1, 0, 1, TOAST_SCREEN_HINT, 1, 1) == 1);

    // Every §6.5 state counts dimmer/room = 1 regardless of inRoom=0 (passthrough dimmer) or inRoom=1 (3D room)
    for (int inRoom = 0; inRoom <= 1; inRoom++) {
        // 1. Immersive playback: dimmer/room(1) + stereo(2) + danmaku(2) + subtitle(1) = 6
        LayerBudgetScene s;
        memset(&s, 0, sizeof(s));
        s.inRoom = inRoom; s.stereo = 1; s.hasDanmaku = 1; s.hasSubtitle = 1;
        CHECK(screenLayerBudgetCount(&s) == 6);

        // 2. Toast / buffering: 7
        s.hasToast = 1;
        CHECK(screenLayerBudgetCount(&s) == 7);
        s.hasToast = 0; s.hasCard = 1;
        CHECK(screenLayerBudgetCount(&s) == 7);
        s.hasCard = 0;

        // 3. Control bar shown: +bar(1) + ray/dot(2) + controller(1) = 10
        s.hasControlBar = 1; s.hasPointer = 1; s.hasController = 1;
        CHECK(screenLayerBudgetCount(&s) == 10);

        // 4. Palette open: 10; Palette + control bar: 11; + card: 12; + glow: 13
        s.hasControlBar = 0; s.hasPalette = 1;
        CHECK(screenLayerBudgetCount(&s) == 10);
        s.hasControlBar = 1;
        CHECK(screenLayerBudgetCount(&s) == 11);
        s.hasCard = 1;
        CHECK(screenLayerBudgetCount(&s) == 12);
        s.hasGlow = 1;
        int cMaxBar = screenLayerBudgetCount(&s);
        CHECK(cMaxBar == 13);
        CHECK(screenLayerBudgetOk(cMaxBar, LAYER_BUDGET_DESIGN_MAX));
        CHECK(screenLayerBudgetOk(cMaxBar, LAYER_BUDGET_HARDWARE_MAX));

        // 5. Screen adjust (hides control bar via shared predicate, reuses toast slot):
        //    base 6 + readout 1 + pointer 2 + controller 1 = 10; + palette = 11; + glow = 12
        s.hasGlow = 0; s.hasCard = 0; s.hasPalette = 0;
        s.hasControlBar = 1; s.hasReadout = 1; s.hasToast = 1;
        CHECK(screenLayerBudgetCount(&s) == 10);
        s.hasPalette = 1;
        CHECK(screenLayerBudgetCount(&s) == 11);
        s.hasGlow = 1; s.hasCard = 1;
        int cMaxAdj = screenLayerBudgetCount(&s);
        CHECK(cMaxAdj == 13);
        CHECK(screenLayerBudgetOk(cMaxAdj, LAYER_BUDGET_DESIGN_MAX));
        CHECK(screenLayerBudgetOk(cMaxAdj, LAYER_BUDGET_HARDWARE_MAX));
    }
}

// Rule 1 & Rule 2: trigger on control never adjusts; quick release on screen toggles play/pause
static void testRules1And2ClickVsControl(void) {
    ScreenInputState st;
    ScreenInputResult res;
    screenInputInit(&st);

    // Rule 1: trigger held on TransportBar/control + stick > 0.5 NEVER enters adjust
    ScreenInputFrame f = baseFrame(MS(1000), 0.016f);
    f.target = SCREEN_TARGET_CONTROL;
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.clickControl == 1);
    CHECK(res.adjustActive == 0);

    f.nowNs = MS(1400);
    f.rStickX = 0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 0);
    CHECK(res.showHintToast == 0);

    f.nowNs = MS(1450);
    f.triggerDown = 0;
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);
    CHECK(res.adjustSaved == 0);

    // Rule 2: trigger on screen released at 200 ms (< 350 ms) with stick in deadzone -> playPause
    f = baseFrame(MS(2000), 0.016f);
    f.triggerDown = 1;
    f.rStickX = 0.15f; // inside deadzone 0.25
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);
    CHECK(res.adjustActive == 0);

    f.nowNs = MS(2200);
    f.triggerDown = 0;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 1);

    // Rule 2 boundary: stick nudged outside deadzone (0.30) during <350 ms hold -> NO playPause
    f = baseFrame(MS(3000), 0.016f);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(3100);
    f.rStickX = 0.30f; // > 0.25 deadzone, < 0.50 engage
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 0);
    f.nowNs = MS(3200);
    f.triggerDown = 0;
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);
}

// Rule 3, Rule 4, Rule 6: immediate adjust, 350 ms hold hint, mid-hold ray leave
static void testRules3And4And6AdjustAndHint(void) {
    ScreenInputState st;
    ScreenInputResult res;
    screenInputInit(&st);

    // Rule 3: immediate stick push at 50 ms (< 350 ms) enters adjust right away
    ScreenInputFrame f = baseFrame(MS(1000), 0.016f);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 0);

    f.nowNs = MS(1050);
    f.rStickX = 0.8f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustStarted == 1);
    CHECK(res.adjustActive == 1);
    CHECK(res.adjustAxis == ADJUST_AXIS_SIZE);
    CHECK(res.hideControlBar == 1);

    // Moving ray off screen AFTER adjust started does NOT cancel adjust
    f.nowNs = MS(1100);
    f.target = SCREEN_TARGET_NONE;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 1);

    // Release saves and does NOT toggle play/pause
    f.nowNs = MS(1150);
    f.triggerDown = 0;
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustSaved == 1);
    CHECK(res.adjustActive == 0);
    CHECK(res.playPause == 0);

    // Rule 4: hold >= 350 ms without stick push -> hint toast + 5ms/0.2 haptic, no pause on release
    f = baseFrame(MS(2000), 0.016f);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(2340);
    screenInputStep(&st, &f, &res);
    CHECK(res.showHintToast == 0);
    f.nowNs = MS(2350);
    screenInputStep(&st, &f, &res);
    CHECK(res.showHintToast == 1);
    CHECK(res.hapticEvent == SCREEN_HAPTIC_HINT);
    // Second frame does not re-fire hint
    f.nowNs = MS(2400);
    screenInputStep(&st, &f, &res);
    CHECK(res.showHintToast == 0);
    // Release after hint -> nothing happens (no playPause)
    f.nowNs = MS(2500);
    f.triggerDown = 0;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);
    CHECK(res.adjustSaved == 0);

    // Rule 4 -> Rule 3: pushing stick AFTER hint toast enters adjust
    f = baseFrame(MS(3000), 0.016f);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(3400);
    screenInputStep(&st, &f, &res);
    CHECK(res.showHintToast == 1);
    f.nowNs = MS(3500);
    f.rStickY = 0.8f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustStarted == 1);
    CHECK(res.adjustActive == 1);
    CHECK(res.adjustAxis == ADJUST_AXIS_DIST);
    f.nowNs = MS(3550);
    f.triggerDown = 0;
    f.rStickY = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustSaved == 1);

    // Rule 6: ray leaves screen before adjust begins -> cancels, release does not pause
    f = baseFrame(MS(4000), 0.016f);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(4100);
    f.target = SCREEN_TARGET_NONE;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(4150);
    f.target = SCREEN_TARGET_SCREEN;
    f.triggerDown = 0;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);
    CHECK(res.adjustActive == 0);
}

// Rule 5: pre-pushed stick continues seeking until returning to deadzone, then enters adjust
static void testRule5PrePushedStick(void) {
    ScreenInputState st;
    ScreenInputResult res;
    screenInputInit(&st);

    // User already seeking right
    ScreenInputFrame f = baseFrame(MS(1000), 0.016f);
    f.rStickX = 0.85f;
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 10);

    // Presses trigger while stick is still pushed -> MUST NOT enter adjust yet
    f.nowNs = MS(1400);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 0);
    CHECK(res.seekDeltaSec == 10); // seek continues!

    // Stick returns to center while trigger stays held
    f.nowNs = MS(1500);
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 0);

    // Now pushing stick > 0.5 enters adjust!
    f.nowNs = MS(1550);
    f.rStickY = 0.8f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustStarted == 1);
    CHECK(res.adjustActive == 1);
    CHECK(res.adjustAxis == ADJUST_AXIS_DIST);
}

// Rule 7: axis lock until stick returns to deadzone
static void testRule7AxisLock(void) {
    ScreenInputState st;
    ScreenInputResult res;
    screenInputInit(&st);

    ScreenInputFrame f = baseFrame(MS(1000), 0.1f);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);

    // Push X first -> locks SIZE
    f.nowNs = MS(1100);
    f.rStickX = 0.9f;
    f.rStickY = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustAxis == ADJUST_AXIS_SIZE);
    float distBefore = res.distM;

    // Now push Y hard while X is still > 0.25 -> axis stays SIZE, distance MUST NOT move
    f.nowNs = MS(1200);
    f.rStickX = 0.4f;
    f.rStickY = 1.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustAxis == ADJUST_AXIS_SIZE);
    CHECK_NEAR(res.distM, distBefore, 1e-5);

    // Return both axes to deadzone (<= 0.25) -> axis unlocks
    f.nowNs = MS(1300);
    f.rStickX = 0.1f;
    f.rStickY = 0.2f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustAxis == ADJUST_AXIS_NONE);

    // Push Y > 0.5 -> now locks DIST, and X is ignored
    float fovBefore = res.fovDeg;
    f.nowNs = MS(1400);
    f.rStickX = 0.3f;
    f.rStickY = 1.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustAxis == ADJUST_AXIS_DIST);
    CHECK(res.distM > distBefore);
    CHECK_NEAR(res.fovDeg, fovBefore, 1e-5);
}

// Rule 8 & Rule 11: stick X seek (10s -> 30s after 1s), palette row lock without leaking to seek,
// and stick Y no-op outside adjust
static void testRules8And11StickSeekAndPalette(void) {
    ScreenInputState st;
    ScreenInputResult res;
    screenInputInit(&st);

    // Rule 11: stick Y up/down outside adjust does NOTHING
    ScreenInputFrame f = baseFrame(MS(1000), 0.016f);
    f.rStickY = 1.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 0);
    CHECK(res.adjustActive == 0);
    f.nowNs = MS(1100);
    f.rStickY = -1.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 0);

    // Rule 8: stick X right seeks +10s immediately, +10s at 400ms & 800ms, +30s after >= 1.0s
    f = baseFrame(MS(2000), 0.016f);
    f.rStickX = 0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 10);

    f.nowNs = MS(2200);
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 0);

    f.nowNs = MS(2400);
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 10);

    f.nowNs = MS(2800);
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 10);

    f.nowNs = MS(3200); // 1.2 s held -> 30 s/step!
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 30);

    // Return to center
    f.nowNs = MS(3300);
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);

    // Rule 8: on palette row -> adjusts row; sliding ray off row stops repeat and NEVER seeks
    f = baseFrame(MS(4000), 0.016f);
    f.paletteOpen = 1;
    f.target = SCREEN_TARGET_PALETTE_ROW;
    f.rStickX = -0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.paletteRowStep == -1);
    CHECK(res.seekDeltaSec == 0);

    // Ray slides onto screen while stick still held left -> must NOT become seek!
    f.nowNs = MS(4500);
    f.target = SCREEN_TARGET_SCREEN;
    screenInputStep(&st, &f, &res);
    CHECK(res.paletteRowStep == 0);
    CHECK(res.seekDeltaSec == 0);

    // Only after returning to deadzone can it seek again
    f.nowNs = MS(4600);
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(4700);
    f.rStickX = -0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == -10);
}

// Rule 9 & Rule 10 & Rule 12: non-modal palette, B priority chain, double-B exit, trigger setting OFF
static void testRules9And10And12(void) {
    ScreenInputState st;
    ScreenInputResult res;
    screenInputInit(&st);

    // Rule 9: palette open, pointing at screen -> adjust works & dims palette
    ScreenInputFrame f = baseFrame(MS(1000), 0.1f);
    f.paletteOpen = 1;
    f.target = SCREEN_TARGET_SCREEN;
    f.curFovDeg = 50.0f;
    f.curDistM = 3.0f;
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(1050);
    f.rStickX = 1.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 1);
    CHECK(res.paletteDimmed == 1);
    CHECK(res.fovDeg > 50.0f);

    // Rule 10 priority 1: B during adjust undoes adjust and restores pre-adjust values
    // even while palette is also open! AND still-deflected stick must NOT seek (Review #1).
    f.nowNs = MS(1100);
    f.btnB = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.bAction == SCREEN_B_UNDO_ADJUST);
    CHECK(res.adjustUndone == 1);
    CHECK(res.adjustActive == 0);
    CHECK(res.seekDeltaSec == 0);
    CHECK_NEAR(res.fovDeg, 50.0f, 1e-4);
    CHECK_NEAR(res.distM, 3.0f, 1e-4);

    // Still holding trigger & stick deflected after B undo must NOT re-enter adjust,
    // must NOT seek (even past 400 ms / 1200 ms), and must NOT pause on release
    f.nowNs = MS(1550);
    f.btnB = 0;
    f.rStickX = 1.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 0);
    CHECK(res.seekDeltaSec == 0);
    f.nowNs = MS(2300);
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 0);
    f.nowNs = MS(2350);
    f.triggerDown = 0;
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);
    CHECK(res.seekDeltaSec == 0);

    // Review #1: Releasing trigger while stick is STILL deflected must NOT fire seek!
    screenInputInit(&st);
    f = baseFrame(MS(1000), 0.05f);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(1050);
    f.rStickX = 0.95f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 1);
    // Release trigger while X is STILL 0.95 -> saves, zero seek!
    f.nowNs = MS(1100);
    f.triggerDown = 0;
    f.rStickX = 0.95f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustSaved == 1);
    CHECK(res.adjustActive == 0);
    CHECK(res.seekDeltaSec == 0);
    // Holding deflected for 1.5 s still produces ZERO seek until stick returns to deadzone
    f.nowNs = MS(1600);
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 0);
    f.nowNs = MS(2500);
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 0);
    // Return to deadzone, then push right -> normal +10 s seek resumes
    f.nowNs = MS(2550);
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(2600);
    f.rStickX = 0.95f;
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 10);

    // Review #1 (Y -> X roll): releasing grip while pushing Y and rolling straight
    // to X without centering must ALSO produce zero seek until both axes center!
    screenInputInit(&st);
    f = baseFrame(MS(1000), 0.05f);
    f.gripDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(1050);
    f.rStickY = 0.95f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 1);
    f.nowNs = MS(1100);
    f.gripDown = 0;
    f.rStickY = 0.95f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustSaved == 1);
    CHECK(res.seekDeltaSec == 0);
    // Roll stick directly from Y=0.95 to X=0.95 without passing through deadzone
    f.nowNs = MS(1150);
    f.rStickY = 0.4f;
    f.rStickX = 0.95f;
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 0);
    // Center both axes, then push X -> seeks normally
    f.nowNs = MS(1200);
    f.rStickX = 0.0f;
    f.rStickY = 0.0f;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(1250);
    f.rStickX = 0.95f;
    screenInputStep(&st, &f, &res);
    CHECK(res.seekDeltaSec == 10);

    // Rule 10 priority 2: B with card + palette open closes card first
    f = baseFrame(MS(2000), 0.016f);
    f.paletteOpen = 1;
    f.cardKind = SCREEN_CARD_ERROR;
    f.btnB = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.bAction == SCREEN_B_CLOSE_CARD);

    // Rule 10 priority 3: B with palette open (no card) closes palette
    f.nowNs = MS(2050); f.btnB = 0;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(2100); f.cardKind = SCREEN_CARD_NONE; f.btnB = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.bAction == SCREEN_B_CLOSE_PALETTE);

    // Rule 10 priority 4: double-B within 2s exits playback; >2s re-prompts
    f.nowNs = MS(2150); f.paletteOpen = 0; f.btnB = 0;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(3000); f.btnB = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.bAction == SCREEN_B_EXIT_PROMPT);

    f.nowNs = MS(3100); f.btnB = 0;
    screenInputStep(&st, &f, &res);
    // > 2s later -> prompts again instead of exiting
    f.nowNs = MS(5500); f.btnB = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.bAction == SCREEN_B_EXIT_PROMPT);

    f.nowNs = MS(5600); f.btnB = 0;
    screenInputStep(&st, &f, &res);
    // Within 2s -> exits!
    f.nowNs = MS(6800); f.btnB = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.bAction == SCREEN_B_EXIT_CONFIRM);

    // Rule 12 (Review #3): setting "按住扳机调整屏幕" OFF disables Rules 3-7 on trigger,
    // while Rule 2 (< 350 ms release + stick stayed in deadzone) STILL applies!
    screenInputInit(&st);
    f = baseFrame(MS(1000), 0.1f);
    f.triggerAdjustEnabled = 0;
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    // Long hold (500 ms) + stick push -> no adjust, no hint, AND no playPause on release!
    f.nowNs = MS(1500);
    f.rStickX = 1.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 0);
    CHECK(res.showHintToast == 0);
    f.nowNs = MS(1600);
    f.triggerDown = 0;
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);

    // Long hold (400 ms) without stick push when OFF -> still NO playPause (Rule 2)
    f.nowNs = MS(1700);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(2100);
    f.triggerDown = 0;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);

    // Short click (< 350 ms, stick in deadzone) when OFF -> toggles playPause!
    f.nowNs = MS(2200);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(2400);
    f.triggerDown = 0;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 1);

    // Grip + stick STILL works when triggerAdjustEnabled == 0
    f.nowNs = MS(2500);
    f.gripDown = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.sphereDragActive == 1);
    f.nowNs = MS(2600);
    f.rStickY = 1.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustStarted == 1);
    CHECK(res.adjustActive == 1);
    CHECK(res.sphereDragActive == 0);
    f.nowNs = MS(2700);
    f.gripDown = 0;
    f.rStickY = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustSaved == 1);
}

// Detent 300ms hold, detent haptic, release snap (±1.5° / ±4%), and limit double haptic
static void testDetentsSnapAndLimits(void) {
    ScreenInputState st;
    ScreenInputResult res;
    screenInputInit(&st);

    // Start at 60 deg, push right across 62 deg detent
    ScreenInputFrame f = baseFrame(MS(1000), 0.1f);
    f.curFovDeg = 60.0f;
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);

    // Push full right: 20 deg/s * 0.15 s = +3 deg -> crosses 62 deg!
    f.nowNs = MS(1150);
    f.dt = 0.15f;
    f.rStickX = 1.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 1);
    CHECK_NEAR(res.fovDeg, 62.0f, 1e-4);
    CHECK(res.hapticEvent == SCREEN_HAPTIC_DETENT);

    // Held pinned at 62 deg for 300 ms
    f.nowNs = MS(1350); // 200 ms elapsed (< 300 ms)
    f.dt = 0.2f;
    screenInputStep(&st, &f, &res);
    CHECK_NEAR(res.fovDeg, 62.0f, 1e-4);
    CHECK(res.hapticEvent == SCREEN_HAPTIC_NONE);

    // After 300 ms hold expires, resumes moving past 62 deg
    f.nowNs = MS(1500);
    f.dt = 0.15f;
    screenInputStep(&st, &f, &res);
    CHECK(res.fovDeg > 62.5f);

    // Move to 63.0 deg (within ±1.5 deg of 62) and release -> snaps to 62.0 deg
    st.adjustFovDeg = 63.2f;
    f.nowNs = MS(1600);
    f.triggerDown = 0;
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustSaved == 1);
    CHECK_NEAR(res.fovDeg, 62.0f, 1e-4);

    // Release outside ±1.5 deg (64.0 deg) -> does NOT snap
    screenInputInit(&st);
    f = baseFrame(MS(2000), 0.1f);
    f.curFovDeg = 64.0f;
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(2050);
    f.rStickX = 0.6f;
    f.dt = 0.01f;
    screenInputStep(&st, &f, &res);
    float midFov = res.fovDeg;
    CHECK(midFov > 64.0f && midFov < 65.0f);
    f.nowNs = MS(2100);
    f.triggerDown = 0;
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK_NEAR(res.fovDeg, midFov, 1e-3);

    // Distance snap within ±4% (3.10 m is +3.3% of 3.0 m -> snaps to 3.0 m)
    screenInputInit(&st);
    f = baseFrame(MS(3000), 0.1f);
    f.curDistM = 3.10f;
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(3050);
    f.rStickY = 0.6f;
    f.dt = 0.001f;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(3100);
    f.triggerDown = 0;
    f.rStickY = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK_NEAR(res.distM, 3.0f, 1e-4);

    // Limit haptic fires ONCE when hitting 90 deg max, and 2 m -> 8 m takes ~3 s
    screenInputInit(&st);
    f = baseFrame(MS(4000), 0.1f);
    f.curFovDeg = 89.0f;
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(4100);
    f.dt = 0.1f;
    f.rStickX = 1.0f;
    screenInputStep(&st, &f, &res);
    CHECK_NEAR(res.fovDeg, 90.0f, 1e-4);
    CHECK(res.hapticEvent == SCREEN_HAPTIC_LIMIT);
    // Next frame still pushing at 90 deg -> does NOT re-trigger limit haptic endlessly
    f.nowNs = MS(4200);
    screenInputStep(&st, &f, &res);
    CHECK(res.hapticEvent == SCREEN_HAPTIC_NONE);

    // Full forward push from 2.0 m reaches 8.0 m in ~3.0 s (excluding 300 ms detents)
    screenInputInit(&st);
    f = baseFrame(MS(5000), 0.1f);
    f.curDistM = 2.0f;
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    int limitSeen = 0;
    for (int step = 1; step <= 40; step++) {
        f.nowNs = MS(5000 + step * 100);
        f.dt = 0.1f;
        f.rStickY = 1.0f;
        screenInputStep(&st, &f, &res);
        if (res.hapticEvent == SCREEN_HAPTIC_LIMIT) {
            limitSeen = 1;
        }
    }
    CHECK_NEAR(res.distM, 8.0f, 1e-4);
    CHECK(limitSeen == 1);
}

// Hand-tracking pinch-hold 350 ms (< 1.5° drift) adjust, 1 cm/deg, 1.5 cm/x1.05, no haptics
static void testHandTrackingAdjust(void) {
    ScreenInputState st;
    ScreenInputResult res;
    screenInputInit(&st);

    // Drift >= 1.5 deg before 350 ms cancels hand adjust
    ScreenInputFrame f = baseFrame(MS(1000), 0.05f);
    f.isHandTracking = 1;
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);

    // Rotate aimDir by 3 degrees
    float rad = 3.0f * (float)M_PI / 180.0f;
    f.aimDir.x = sinf(rad);
    f.aimDir.z = -cosf(rad);
    f.nowNs = MS(1200);
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(1400);
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 0);
    f.nowNs = MS(1450);
    f.triggerDown = 0;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);

    // Steady pinch (< 1.5 deg drift) for 350 ms enters hand adjust with NO haptics
    screenInputInit(&st);
    f = baseFrame(MS(2000), 0.05f);
    f.isHandTracking = 1;
    f.curFovDeg = 50.0f;
    f.curDistM = 3.0f;
    f.handPos = (Vec3){ 0.0f, 0.0f, -0.3f };
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);

    f.nowNs = MS(2350);
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustStarted == 1);
    CHECK(res.adjustActive == 1);
    CHECK(res.hapticEvent == SCREEN_HAPTIC_NONE);

    // Move hand right 2 cm (> 1.5 cm deadzone) -> locks SIZE axis
    f.nowNs = MS(2400);
    f.handPos.x = 0.02f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustAxis == ADJUST_AXIS_SIZE);

    // Move another 8 cm right -> +8 deg (50 -> 58 deg) while accidental forward drift is ignored
    f.nowNs = MS(2500);
    f.handPos.x = 0.10f;
    f.handPos.z = -0.35f; // orthogonal drift during SIZE lock must NOT change distance
    screenInputStep(&st, &f, &res);
    CHECK_NEAR(res.fovDeg, 58.0f, 0.2);
    CHECK_NEAR(res.distM, 3.0f, 1e-3);
    CHECK(res.hapticEvent == SCREEN_HAPTIC_NONE);

    // Review #11: pausing hand along locked axis for 300 ms releases axis lock
    // so user can switch to DIST within the SAME pinch without losing 58 deg!
    f.nowNs = MS(2810); // 310 ms pause with hand still at x = 0.10
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustAxis == ADJUST_AXIS_NONE);
    CHECK_NEAR(res.fovDeg, 58.0f, 0.2);

    // Now push forward 2 cm -> locks DIST; then push 4.5 cm more -> scales distance while keeping 58 deg
    f.nowNs = MS(2860);
    f.handPos.z = -0.37f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustAxis == ADJUST_AXIS_DIST);
    f.nowNs = MS(2960);
    f.handPos.z = -0.415f;
    screenInputStep(&st, &f, &res);
    CHECK_NEAR(res.fovDeg, 58.0f, 0.2);
    CHECK_NEAR(res.distM, 3.0f * powf(1.05f, 3.0f), 0.05);

    // Release saves BOTH adjusted FOV and adjusted distance
    f.nowNs = MS(3010);
    f.triggerDown = 0;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustSaved == 1);
    CHECK_NEAR(res.fovDeg, 58.0f, 0.2);
    CHECK_NEAR(res.distM, 3.0f * powf(1.05f, 3.0f), 0.05);

    // Hand forward push -> distance scales x1.05 per 1.5 cm
    screenInputInit(&st);
    f = baseFrame(MS(3000), 0.05f);
    f.isHandTracking = 1;
    f.curDistM = 3.0f;
    f.handPos = (Vec3){ 0.0f, 0.0f, -0.3f };
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(3360);
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 1);
    // Push forward (toward -Z) by 2 cm to lock DIST
    f.nowNs = MS(3400);
    f.handPos.z = -0.32f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustAxis == ADJUST_AXIS_DIST);
    // Push another 4.5 cm (3 steps of 1.5 cm -> 3.0 * 1.05^3 = 3.4729 m)
    f.nowNs = MS(3500);
    f.handPos.z = -0.365f;
    screenInputStep(&st, &f, &res);
    CHECK_NEAR(res.distM, 3.0f * powf(1.05f, 3.0f), 0.05);
}

// Full §6.4 matrix sweep for A, Y, Menu, RStickClick, LStick across all 5 states + room check
static void testSection64MatrixButtonsAndLeftStick(void) {
    ScreenInputState st;
    ScreenInputResult res;

    // 1. PLAYING: A=play/pause, Y=danmaku, Menu=palette, RStickClick=none, LStick=none
    screenInputInit(&st);
    ScreenInputFrame f = baseFrame(MS(1000), 0.016f);
    f.btnA = 1; f.btnY = 1; f.btnMenu = 1; f.rStickClick = 1; f.lStickX = 0.9f; f.lStickY = 0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 1);
    CHECK(res.yToggleDanmaku == 1);
    CHECK(res.menuTogglePalette == 1);
    CHECK(res.paletteRowReset == 0);
    CHECK(res.paletteTabStep == 0);
    CHECK(res.palettePageStep == 0);

    // 2. PALETTE_HOVER_ROW (Review #4): A=play/pause in EVERY non-adjust column!
    screenInputInit(&st);
    f = baseFrame(MS(2000), 0.016f);
    f.paletteOpen = 1;
    f.target = SCREEN_TARGET_PALETTE_ROW;
    f.btnA = 1; f.rStickClick = 1; f.lStickX = 0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 1);
    CHECK(res.clickControl == 0);
    CHECK(res.paletteRowReset == 1);
    CHECK(res.paletteTabStep == 1);

    // A button also toggles play/pause over CONTROL (TransportBar/modal) and CARD
    screenInputInit(&st);
    f = baseFrame(MS(2500), 0.016f);
    f.target = SCREEN_TARGET_CONTROL;
    f.btnA = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 1);

    screenInputInit(&st);
    f = baseFrame(MS(2600), 0.016f);
    f.cardKind = SCREEN_CARD_NEXT_EP;
    f.target = SCREEN_TARGET_CARD;
    f.btnA = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 1);

    // 3. PALETTE_HOVER_OTHER: A=play/pause, RStickClick=none, RStickX=none, LStickY=pageStep
    screenInputInit(&st);
    f = baseFrame(MS(3000), 0.016f);
    f.paletteOpen = 1;
    f.target = SCREEN_TARGET_PALETTE_OTHER;
    f.btnA = 1; f.rStickClick = 1; f.rStickX = 0.9f; f.lStickY = -0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 1);
    CHECK(res.paletteRowReset == 0);
    CHECK(res.paletteRowStep == 0);
    CHECK(res.seekDeltaSec == 0);
    CHECK(res.palettePageStep == -1);

    // 4. ADJUSTING: A, Y, Menu, RStickClick, LStick ALL ignored!
    screenInputInit(&st);
    f = baseFrame(MS(4000), 0.05f);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(4050);
    f.rStickX = 0.8f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 1);

    f.nowNs = MS(4100);
    f.btnA = 1; f.btnY = 1; f.btnMenu = 1; f.rStickClick = 1; f.lStickX = 0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);
    CHECK(res.yToggleDanmaku == 0);
    CHECK(res.menuTogglePalette == 0);
    CHECK(res.paletteRowReset == 0);
    CHECK(res.paletteTabStep == 0);

    // 5. In 3D cinema room (Review #3): long hold NEVER pauses, short click (<350 ms) pauses
    screenInputInit(&st);
    f = baseFrame(MS(5000), 0.05f);
    f.inRoom = 1;
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(5400);
    f.rStickX = 0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 0);
    CHECK(res.showHintToast == 0);
    f.nowNs = MS(5450);
    f.triggerDown = 0;
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 0);

    // Short click in room -> toggles play/pause
    f.nowNs = MS(5600);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(5800);
    f.triggerDown = 0;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 1);
}

// Review #5 & #14: updateLocalMediaInput glue helpers (target classification,
// suppressLeft lifecycle, A-over-modal, and applyGrab <-> screenAdjust mutual exclusion)
static void testLocalMediaGlueAndMutualExclusion(void) {
    float aspect = 16.0f / 9.0f;

    // Target classification across all HOVER_* cases
    CHECK(screenClassifyLocalTarget(1, HOVER_SCREEN, 0, 0.5f, 0.5f, aspect) == SCREEN_TARGET_CONTROL);
    CHECK(screenClassifyLocalTarget(0, HOVER_SCREEN, 0, 0.5f, 0.90f, aspect) == SCREEN_TARGET_SCREEN);
    CHECK(screenClassifyLocalTarget(0, HOVER_SCREEN, 1, 0.5f, 0.50f, aspect) == SCREEN_TARGET_SCREEN);
    CHECK(screenClassifyLocalTarget(0, HOVER_SCREEN, 1, 0.5f, 0.90f, aspect) == SCREEN_TARGET_CONTROL);
    CHECK(screenClassifyLocalTarget(0, HOVER_BAR, 0, 0.5f, 0.5f, aspect) == SCREEN_TARGET_CONTROL);
    CHECK(screenClassifyLocalTarget(0, HOVER_CORNER, 0, 0.5f, 0.5f, aspect) == SCREEN_TARGET_CONTROL);
    CHECK(screenClassifyLocalTarget(0, HOVER_COGPANEL, 0, 0.5f, 0.5f, aspect) == SCREEN_TARGET_CONTROL);
    CHECK(screenClassifyLocalTarget(0, HOVER_NONE, 1, 0.5f, 0.90f, aspect) == SCREEN_TARGET_NONE);
    CHECK(screenClassifyLocalTarget(0, HOVER_HALO, 1, 0.5f, 0.90f, aspect) == SCREEN_TARGET_NONE);

    // suppressLeft lifecycle: clicking visible TransportBar passes left-down then left-up, no playPause
    ScreenInputState st;
    ScreenInputResult res;
    screenInputInit(&st);
    ScreenInputFrame f = baseFrame(MS(1000), 0.016f);
    f.target = screenClassifyLocalTarget(0, HOVER_SCREEN, 1, 0.5f, 0.90f, aspect);
    CHECK(f.target == SCREEN_TARGET_CONTROL);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    CHECK(screenShouldSuppressLeft(&st, 0) == 0); // left button DOWN goes to Java TransportBar
    CHECK(res.playPause == 0);
    f.nowNs = MS(1100);
    f.triggerDown = 0;
    screenInputStep(&st, &f, &res);
    CHECK(screenShouldSuppressLeft(&st, 0) == 1); // left button UP delivered via mask drop
    CHECK(res.playPause == 0);

    // Clicking bare screen suppresses VR_BUTTON_LEFT on EVERY frame and fires IN_LOCAL_PLAY_PAUSE once
    f = baseFrame(MS(2000), 0.016f);
    f.target = screenClassifyLocalTarget(0, HOVER_SCREEN, 1, 0.5f, 0.50f, aspect);
    CHECK(f.target == SCREEN_TARGET_SCREEN);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    CHECK(screenShouldSuppressLeft(&st, 0) == 1);
    f.nowNs = MS(2100);
    f.triggerDown = 0;
    screenInputStep(&st, &f, &res);
    CHECK(screenShouldSuppressLeft(&st, 0) == 1);
    CHECK(res.playPause == 1);

    // A button while cog/picker modal is open (modalOpen=1 -> target=CONTROL) STILL toggles playPause
    f = baseFrame(MS(2500), 0.016f);
    f.target = screenClassifyLocalTarget(1, HOVER_COGPANEL, 0, 0.5f, 0.5f, aspect);
    f.paletteOpen = 1;
    f.btnA = 1;
    screenInputStep(&st, &f, &res);
    CHECK(res.playPause == 1);

    // Mutual exclusion #5 (direction A): while adjusting screen, applyGrab is blocked
    screenInputInit(&st);
    CHECK(screenAllowExternalGrab(GRAB_NONE, &st) == 1);
    CHECK(screenAllowExternalGrab(GRAB_SPHERE, &st) == 0);
    f = baseFrame(MS(3000), 0.05f);
    f.triggerDown = 1;
    screenInputStep(&st, &f, &res);
    f.nowNs = MS(3050);
    f.rStickX = 0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 1);
    CHECK(screenAllowExternalGrab(GRAB_NONE, &st) == 0); // cannot start GRAB_MOVE / GRAB_RESIZE mid-adjust!

    // Mutual exclusion #5 (direction B): during GRAB_RESIZE / GRAB_MOVE, adjust never starts
    // and releasing the grab's trigger/grip produces zero stale adjustSaved or playPause
    screenInputInit(&st);
    CHECK(screenIsExternalGrabBusy(GRAB_NONE) == 0);
    CHECK(screenIsExternalGrabBusy(GRAB_SPHERE) == 0);
    CHECK(screenIsExternalGrabBusy(GRAB_MOVE) == 1);
    CHECK(screenIsExternalGrabBusy(GRAB_RESIZE) == 1);

    f = baseFrame(MS(4000), 0.05f);
    f.externalGrabActive = 1;
    f.triggerDown = 1;
    f.gripDown = 1;
    f.rStickX = 1.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustActive == 0);
    CHECK(res.playPause == 0);
    CHECK(screenShouldSuppressLeft(&st, 1) == 1);

    // Grab ends and trigger/grip release on next frame -> zero stale save or pause!
    f.nowNs = MS(4100);
    f.externalGrabActive = 0;
    f.triggerDown = 0;
    f.gripDown = 0;
    f.rStickX = 0.0f;
    screenInputStep(&st, &f, &res);
    CHECK(res.adjustSaved == 0);
    CHECK(res.playPause == 0);
    CHECK(res.adjustActive == 0);
}

static void testPaletteStickRepeatAndTargetClassifier(void) {
    // Target classifier maps HOVER_PALETTE (adjustable vs non-adjustable) & DEADZONE cleanly
    CHECK(screenClassifyLocalTargetWithPalette(0, HOVER_PALETTE, 1, 0, 0.5f, 0.5f, 16.0f / 9.0f)
          == SCREEN_TARGET_PALETTE_ROW);
    CHECK(screenClassifyLocalTargetWithPalette(0, HOVER_PALETTE, 0, 0, 0.5f, 0.5f, 16.0f / 9.0f)
          == SCREEN_TARGET_PALETTE_OTHER);
    CHECK(screenClassifyLocalTargetWithPalette(0, HOVER_PALETTE_DEADZONE, 1, 0, 1.02f, 0.5f, 16.0f / 9.0f)
          == SCREEN_TARGET_PALETTE_OTHER);
    CHECK(screenClassifyLocalTargetWithPalette(0, HOVER_SCREEN, 0, 0, 0.5f, 0.5f, 16.0f / 9.0f)
          == SCREEN_TARGET_SCREEN);

    // §6.3 / §6.4: stick-X on PALETTE_ROW repeats after 400 ms delay at 8 Hz (125 ms interval)
    ScreenInputState st;
    ScreenInputResult res;
    screenInputInit(&st);

    ScreenInputFrame f = baseFrame(MS(1000), 0.016f);
    f.paletteOpen = 1;
    f.target = SCREEN_TARGET_PALETTE_ROW;
    f.rStickX = 0.9f;
    screenInputStep(&st, &f, &res);
    CHECK(res.paletteRowStep == 1);
    CHECK(res.seekDeltaSec == 0);

    // At +390 ms (< 400 ms delay): no repeat yet
    f.nowNs = MS(1390);
    screenInputStep(&st, &f, &res);
    CHECK(res.paletteRowStep == 0);

    // At +400 ms: first repeat fires
    f.nowNs = MS(1400);
    screenInputStep(&st, &f, &res);
    CHECK(res.paletteRowStep == 1);

    // At +520 ms (< 125 ms interval): no repeat
    f.nowNs = MS(1520);
    screenInputStep(&st, &f, &res);
    CHECK(res.paletteRowStep == 0);

    // At +525 ms (8 Hz): second repeat fires
    f.nowNs = MS(1525);
    screenInputStep(&st, &f, &res);
    CHECK(res.paletteRowStep == 1);

    // Sliding off row onto screen while stick still held must NEVER become seek!
    f.nowNs = MS(1650);
    f.target = SCREEN_TARGET_SCREEN;
    screenInputStep(&st, &f, &res);
    CHECK(res.paletteRowStep == 0);
    CHECK(res.seekDeltaSec == 0);
}

int main(void) {
    testInchesAndNorms();
    testCurvatureFormulaAndAngularInvariance();
    testCurvedTransportStripAndHit();
    testSphereDragPreservesDistance();
    testLayerBudgetPerSpec65();
    testRules1And2ClickVsControl();
    testRules3And4And6AdjustAndHint();
    testRule5PrePushedStick();
    testRule7AxisLock();
    testRules8And11StickSeekAndPalette();
    testRules9And10And12();
    testDetentsSnapAndLimits();
    testHandTrackingAdjust();
    testSection64MatrixButtonsAndLeftStick();
    testLocalMediaGlueAndMutualExclusion();
    testPaletteStickRepeatAndTargetClassifier();
    return checksDone("xr_screen_ctrl");
}
