// Host unit tests for xr_palette.c: hand-mounted pose & right-hand mirror,
// 20-deg eye correction & 100 ms LPF, 60-deg angle cull, 160/120 ms fade,
// pin/unpin 200 ms cubic ease-out, Control Bar 20-deg drop @ 0.5 m open,
// +-60 deg reopen cone, 1.5 cm edge deadzone, 20 px hover hysteresis,
// palette-hand suppression, grip & hand-tracking drag, and 40% adjust dimming.

#include "check.h"
#include "xr_palette.h"
#include <math.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define MS(x) ((int64_t)(x) * 1000000LL)

static XrPosef identityPose(float x, float y, float z) {
    XrPosef p;
    memset(&p, 0, sizeof(p));
    p.orientation.w = 1.0f;
    p.position.x = x;
    p.position.y = y;
    p.position.z = z;
    return p;
}

// Aim pose at `origin` pointing along unit `dir`
static XrPosef aimToward(Vec3 origin, Vec3 dir) {
    Vec3 fwd = vecNorm(dir);
    Vec3 zAxis = { -fwd.x, -fwd.y, -fwd.z };
    Vec3 worldUp = { 0.0f, 1.0f, 0.0f };
    Vec3 xAxis = vecCross(worldUp, zAxis);
    if (sqrtf(vecDot(xAxis, xAxis)) < 1e-3f) {
        xAxis.x = 1.0f; xAxis.y = 0.0f; xAxis.z = 0.0f;
    } else {
        xAxis = vecNorm(xAxis);
    }
    Vec3 yAxis = vecNorm(vecCross(zAxis, xAxis));
    XrPosef p;
    p.position.x = origin.x;
    p.position.y = origin.y;
    p.position.z = origin.z;
    p.orientation = quatFromBasis(xAxis, yAxis, zAxis);
    return p;
}

static void testHandMountPoseAndRightHandMirror(void) {
    // Grip sits 0.45 m in front of eye, 0.25 m below eye, 0.15 m left/right
    Vec3 head = { 0.0f, 0.0f, 0.0f };
    XrPosef leftGrip = identityPose(-0.15f, -0.25f, -0.45f);
    XrPosef rightGrip = identityPose(0.15f, -0.25f, -0.45f);

    XrQuaternionf lpfL = { 0, 0, 0, 1 }, lpfR = { 0, 0, 0, 1 };
    int validL = 0, validR = 0;

    XrPosef pL = paletteComputeHandPose(leftGrip, head, 0, &lpfL, &validL, 0.0f);
    XrPosef pR = paletteComputeHandPose(rightGrip, head, 1, &lpfR, &validR, 0.0f);

    CHECK(validL == 1);
    CHECK(validR == 1);
    // Left hand shifts +2 cm toward midline (+X), right hand shifts -2 cm (-X)
    CHECK_NEAR(pL.position.x, -0.15f + PALETTE_MOUNT_IN_M, 1e-4f);
    CHECK_NEAR(pR.position.x,  0.15f - PALETTE_MOUNT_IN_M, 1e-4f);
    // Exact horizontal mirror: X is negated, Y and Z are identical
    CHECK_NEAR(pL.position.x, -pR.position.x, 1e-4f);
    CHECK_NEAR(pL.position.y,  pR.position.y, 1e-4f);
    CHECK_NEAR(pL.position.z,  pR.position.z, 1e-4f);

    // Bottom edge is 8 cm above grip, center adds half-height rotated by 40 deg pitch back
    float pitchRad = PALETTE_MOUNT_PITCH_DEG * (float)M_PI / 180.0f;
    float expectedY = -0.25f + PALETTE_MOUNT_UP_M + (PALETTE_H_M * 0.5f) * cosf(pitchRad);
    float expectedZ = -0.45f - (PALETTE_H_M * 0.5f) * sinf(pitchRad);
    CHECK_NEAR(pL.position.y, expectedY, 1e-4f);
    CHECK_NEAR(pL.position.z, expectedZ, 1e-4f);

    // Eye-facing correction capped at <= 20 deg from base normal:
    // Turn grip 90 deg left so base normal points along -X; eye correction must
    // NOT rotate normal more than 20 deg toward head.
    Vec3 up = { 0.0f, 1.0f, 0.0f };
    XrPosef sideGrip = identityPose(0.0f, -0.20f, -0.50f);
    sideGrip.orientation = axisAngleQuat(up, (float)M_PI * 0.5f);
    XrPosef pSide = paletteComputeHandPose(sideGrip, head, 0, NULL, NULL, 0.0f);
    Vec3 localX = { 1.0f, 0.0f, 0.0f };
    XrQuaternionf baseQ = quatNorm(quatMul(sideGrip.orientation,
                                           axisAngleQuat(localX, -pitchRad)));
    Vec3 zLocal = { 0.0f, 0.0f, 1.0f };
    Vec3 baseN = quatRotate(baseQ, zLocal);
    Vec3 corrN = quatRotate(pSide.orientation, zLocal);
    float dot = vecDot(baseN, corrN);
    if (dot > 1.0f) dot = 1.0f;
    float corrDeg = acosf(dot) * 180.0f / (float)M_PI;
    CHECK(corrDeg <= PALETTE_EYE_CORRECT_MAX_DEG + 0.1f);
    CHECK(corrDeg >= PALETTE_EYE_CORRECT_MAX_DEG - 0.5f);

    // 100 ms LPF damps a sudden 30 deg wrist jump rather than snapping in 1 frame
    XrQuaternionf lpf = { 0, 0, 0, 1 };
    int lpfOk = 0;
    XrPosef p0 = paletteComputeHandPose(leftGrip, head, 0, &lpf, &lpfOk, 0.016f);
    XrPosef jumpedGrip = leftGrip;
    jumpedGrip.orientation = axisAngleQuat(up, 30.0f * (float)M_PI / 180.0f);
    XrPosef p1 = paletteComputeHandPose(jumpedGrip, head, 0, &lpf, &lpfOk, 0.016f);
    XrPosef pTarget = paletteComputeHandPose(jumpedGrip, head, 0, NULL, NULL, 0.0f);
    Vec3 n0 = quatRotate(p0.orientation, zLocal);
    Vec3 n1 = quatRotate(p1.orientation, zLocal);
    Vec3 nT = quatRotate(pTarget.orientation, zLocal);
    float dStart = acosf(fminf(1.0f, vecDot(n0, nT)));
    float dStep = acosf(fminf(1.0f, vecDot(n0, n1)));
    CHECK(dStep > 0.0f && dStep < dStart * 0.35f);
    // After 500 ms (5 * tau), LPF converges close to target
    XrPosef pConv = p1;
    for (int i = 0; i < 30; i++) {
        pConv = paletteComputeHandPose(jumpedGrip, head, 0, &lpf, &lpfOk, 0.016f);
    }
    Vec3 nC = quatRotate(pConv.orientation, zLocal);
    CHECK(acosf(fminf(1.0f, vecDot(nC, nT))) * 180.0f / (float)M_PI < 1.0f);
}

static void testAngleCullAndFade(void) {
    PaletteState st;
    paletteInit(&st);
    XrPosef head = identityPose(0.0f, 0.0f, 0.0f);
    XrPosef grip = identityPose(-0.15f, -0.25f, -0.45f);
    PaletteRenderPose rp;

    paletteOpenFromMenu(&st, head, grip, 1, 0, MS(1000));
    CHECK(st.open == 1);
    CHECK(st.pinned == 0);

    // Mid-way through 160 ms open fade (80 ms -> alpha ~0.5)
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 0, 0.016f, MS(1080), 1, &rp);
    CHECK(rp.visible == 1);
    CHECK(rp.inputAllowed == 1);
    CHECK_NEAR(rp.alpha, 0.5f, 0.05f);

    // Complete 160 ms fade -> alpha == 1.0
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 0, 0.016f, MS(1200), 1, &rp);
    CHECK_NEAR(rp.alpha, 1.0f, 1e-4f);

    // Turn controller away 180 deg -> normal angle > 60 deg -> input culled & fades out
    Vec3 up = { 0.0f, 1.0f, 0.0f };
    XrPosef flippedGrip = grip;
    flippedGrip.orientation = axisAngleQuat(up, (float)M_PI);
    // Step several frames so LPF rotates away past 60 deg
    for (int i = 0; i < 40; i++) {
        paletteStepPoseAndFade(&st, head, flippedGrip, 1, 0, 0, 0.02f, MS(1220 + i * 20), 1, &rp);
    }
    CHECK(rp.inputAllowed == 0);
    CHECK(rp.visible == 0);
    CHECK_NEAR(rp.alpha, 0.0f, 1e-4f);

    // Close palette: 120 ms fade out from alpha=1
    paletteInit(&st);
    paletteOpenFromMenu(&st, head, grip, 1, 0, MS(1000));
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 0, 0.016f, MS(1200), 1, &rp);
    CHECK_NEAR(rp.alpha, 1.0f, 1e-4f);
    paletteClose(&st, MS(1200));
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 0, 0.016f, MS(1260), 1, &rp);
    CHECK(rp.visible == 1);
    CHECK(rp.inputAllowed == 0);
    CHECK_NEAR(rp.alpha, 0.5f, 0.05f);
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 0, 0.016f, MS(1330), 1, &rp);
    CHECK(rp.visible == 0);
    CHECK_NEAR(rp.alpha, 0.0f, 1e-4f);
}

static void testPinUnpinFlyBackAndReopenCone(void) {
    PaletteState st;
    paletteInit(&st);
    XrPosef head = identityPose(0.0f, 0.0f, 0.0f);
    Vec3 headPos = { 0.0f, 0.0f, 0.0f };
    XrPosef grip = identityPose(-0.20f, -0.25f, -0.45f);
    PaletteRenderPose rp;

    // Open via Control Bar: pinned 20 deg below gaze at 0.50 m
    paletteOpenFromBar(&st, head, 0, MS(1000));
    CHECK(st.open == 1);
    CHECK(st.pinned == 1);
    float dropRad = PALETTE_PIN_DROP_DEG * (float)M_PI / 180.0f;
    CHECK_NEAR(st.pinnedPose.position.x, 0.0f, 1e-4f);
    CHECK_NEAR(st.pinnedPose.position.y, -PALETTE_PIN_DIST_M * sinf(dropRad), 1e-4f);
    CHECK_NEAR(st.pinnedPose.position.z, -PALETTE_PIN_DIST_M * cosf(dropRad), 1e-4f);
    // Upright facing eye -> normal angle ~0 deg
    CHECK(paletteNormalAngleDeg(st.pinnedPose, headPos) < 1.0f);

    // Close and reopen while still facing forward (inside ±60 deg cone) -> stays pinned there!
    paletteClose(&st, MS(1500));
    paletteOpenFromMenu(&st, head, grip, 1, 0, MS(2000));
    CHECK(st.pinned == 1);
    CHECK_NEAR(st.pinnedPose.position.z, -PALETTE_PIN_DIST_M * cosf(dropRad), 1e-4f);

    // Turn head 90 deg away (outside ±60 deg cone) and reopen -> resets to hand-mounted!
    paletteClose(&st, MS(2100));
    Vec3 up = { 0.0f, 1.0f, 0.0f };
    XrPosef turnedHead = head;
    turnedHead.orientation = axisAngleQuat(up, (float)M_PI * 0.5f);
    CHECK(paletteWithinReopenCone(st.pinnedPose, turnedHead) == 0);
    paletteOpenFromMenu(&st, turnedHead, grip, 1, 0, MS(2200));
    CHECK(st.pinned == 0);

    // Pin in place -> animates upright over 200 ms
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 0, 0.016f, MS(2300), 1, &rp);
    paletteTogglePin(&st, headPos, 0, MS(2300));
    CHECK(st.pinned == 1);
    CHECK(st.animMode == PALETTE_ANIM_TO_PINNED);
    // Mid-animation blocks input, ends at 200 ms
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 0, 0.016f, MS(2400), 1, &rp);
    CHECK(rp.inputAllowed == 0);
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 0, 0.016f, MS(2510), 1, &rp);
    CHECK(st.animMode == PALETTE_ANIM_NONE);
    CHECK(rp.inputAllowed == 1);

    // Unpin -> flies back to hand over 200 ms
    paletteTogglePin(&st, headPos, 0, MS(2600));
    CHECK(st.pinned == 0);
    CHECK(st.animMode == PALETTE_ANIM_TO_HAND);
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 0, 0.016f, MS(2700), 1, &rp);
    CHECK(rp.inputAllowed == 0);
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 0, 0.016f, MS(2810), 1, &rp);
    CHECK(st.animMode == PALETTE_ANIM_NONE);
    CHECK(rp.inputAllowed == 1);

    // Hand-tracking mode: opens pinned and top-right button (×) closes it
    paletteInit(&st);
    paletteOpenFromMenu(&st, head, grip, 0, 1, MS(3000));
    CHECK(st.open == 1);
    CHECK(st.pinned == 1);
    CHECK(st.handTrackingMode == 1);
    paletteTogglePin(&st, headPos, 1, MS(3100));
    CHECK(st.open == 0);
}

static void testDeadzoneAndHoverHysteresis(void) {
    PaletteState st;
    paletteInit(&st);
    XrPosef panel = identityPose(0.0f, 0.0f, -0.50f);
    Vec3 eye = { 0.0f, 0.0f, 0.0f };
    PaletteHit hit;

    // 1. Inside 1.5 cm deadzone right of panel (+0.10 m edge + 0.008 m = +0.108 m)
    Vec3 dirDead = { 0.108f, 0.0f, -0.50f };
    paletteRayHit(&st, panel, aimToward(eye, dirDead), 0xF, &hit);
    CHECK(hit.zone == PALETTE_HIT_DEADZONE);

    // 2. Outside 1.5 cm deadzone (+0.10 m + 0.020 m = +0.120 m) -> NONE
    Vec3 dirFar = { 0.120f, 0.0f, -0.50f };
    paletteRayHit(&st, panel, aimToward(eye, dirFar), 0xF, &hit);
    CHECK(hit.zone == PALETTE_HIT_NONE);

    // 3. Aim squarely at Tab 0 center (px=88, py=66 -> u=88/1024, v=66/768)
    float uTab0 = (PALETTE_TAB0_X + PALETTE_TAB0_W * 0.5f) / (float)PALETTE_TEX_W;
    float vTab0 = (PALETTE_TAB_Y + PALETTE_TAB_H * 0.5f) / (float)PALETTE_TEX_H;
    Vec3 dirTab0 = { (uTab0 - 0.5f) * PALETTE_W_M, (0.5f - vTab0) * PALETTE_H_M, -0.50f };
    paletteRayHit(&st, panel, aimToward(eye, dirTab0), 0xF, &hit);
    CHECK(hit.zone == PALETTE_HIT_TAB);
    CHECK(hit.index == 0);

    // 4. Move 10 px below Tab 0 bottom (inside 20 px hysteresis) -> STILL holds Tab 0!
    float vBelow10 = (PALETTE_TAB_Y + PALETTE_TAB_H + 10.0f) / (float)PALETTE_TEX_H;
    Vec3 dirHyst = { (uTab0 - 0.5f) * PALETTE_W_M, (0.5f - vBelow10) * PALETTE_H_M, -0.50f };
    paletteRayHit(&st, panel, aimToward(eye, dirHyst), 0xF, &hit);
    CHECK(hit.zone == PALETTE_HIT_TAB);
    CHECK(hit.index == 0);

    // 5. Move 25 px below Tab 0 bottom (> 20 px hysteresis) -> leaves Tab 0
    float vBelow25 = (PALETTE_TAB_Y + PALETTE_TAB_H + 25.0f) / (float)PALETTE_TEX_H;
    Vec3 dirLeave = { (uTab0 - 0.5f) * PALETTE_W_M, (0.5f - vBelow25) * PALETTE_H_M, -0.50f };
    paletteRayHit(&st, panel, aimToward(eye, dirLeave), 0xF, &hit);
    CHECK(hit.zone != PALETTE_HIT_TAB);

    // 6. Row hit with activeRowMask: row 2 adjustable only when bit 2 is set
    float uRow = 0.5f;
    float vRow2 = (PALETTE_ROW_Y0 + 2.5f * PALETTE_ROW_H) / (float)PALETTE_TEX_H;
    Vec3 dirRow2 = { (uRow - 0.5f) * PALETTE_W_M, (0.5f - vRow2) * PALETTE_H_M, -0.50f };
    paletteRayHit(&st, panel, aimToward(eye, dirRow2), 0xB, &hit); // bit 2 cleared (e.g. curvature in room)
    CHECK(hit.zone == PALETTE_HIT_ROW);
    CHECK(hit.index == 2);
    CHECK(hit.rowAdjustable == 0);

    paletteRayHit(&st, panel, aimToward(eye, dirRow2), 0xF, &hit); // bit 2 set
    CHECK(hit.zone == PALETTE_HIT_ROW);
    CHECK(hit.index == 2);
    CHECK(hit.rowAdjustable == 1);
}

static void testHandSuppressionAndDragging(void) {
    PaletteState st;
    paletteInit(&st);
    XrPosef head = identityPose(0.0f, 0.0f, 0.0f);
    Vec3 headPos = { 0.0f, 0.0f, 0.0f };
    XrPosef grip = identityPose(-0.15f, -0.20f, -0.45f);

    // Closed -> neither hand suppressed
    CHECK(paletteShouldSuppressHand(&st, 0, 0) == 0);
    CHECK(paletteShouldSuppressHand(&st, 1, 0) == 0);

    // Open on left hand (default) -> hand 0 suppressed, hand 1 operates
    paletteOpenFromMenu(&st, head, grip, 1, 0, MS(1000));
    CHECK(paletteMountHand(&st) == 0);
    CHECK(paletteOpHand(&st) == 1);
    CHECK(paletteShouldSuppressHand(&st, 0, 0) == 1);
    CHECK(paletteShouldSuppressHand(&st, 1, 0) == 0);

    // Swap to right hand -> hand 1 suppressed, hand 0 operates
    paletteSetHand(&st, 1);
    CHECK(paletteMountHand(&st) == 1);
    CHECK(paletteOpHand(&st) == 0);
    CHECK(paletteShouldSuppressHand(&st, 1, 0) == 1);
    CHECK(paletteShouldSuppressHand(&st, 0, 0) == 0);

    // Hand-tracking -> never suppresses either hand
    CHECK(paletteShouldSuppressHand(&st, 1, 1) == 0);

    // Grip detach & drag: detaches into pinned state and moves panel while keeping upright
    XrPosef aimStart = identityPose(0.15f, -0.15f, -0.25f);
    paletteStartDrag(&st, aimStart, headPos, 1, MS(1200));
    CHECK(st.pinned == 1);
    CHECK(st.dragging == 1);
    // Pinned -> mount hand is no longer suppressed!
    CHECK(paletteShouldSuppressHand(&st, 1, 0) == 0);

    XrPosef aimMoved = identityPose(0.35f, -0.05f, -0.25f);
    float oldX = st.pinnedPose.position.x;
    paletteStepDrag(&st, aimMoved, headPos);
    CHECK(st.pinnedPose.position.x > oldX + 0.15f);
    CHECK(paletteNormalAngleDeg(st.pinnedPose, headPos) < 1.0f);
    paletteEndDrag(&st);
    CHECK(st.dragging == 0);

    // Hand-tracking 8 mm horizontal row drag
    Vec3 rightAxis = { 1.0f, 0.0f, 0.0f };
    Vec3 h0 = { 0.0f, 0.0f, -0.40f };
    CHECK(paletteHandRowDragStep(&st, 1, 1, h0, rightAxis) == 0);
    Vec3 h5mm = { 0.005f, 0.0f, -0.40f };
    CHECK(paletteHandRowDragStep(&st, 1, 1, h5mm, rightAxis) == 0);
    Vec3 h9mm = { 0.009f, 0.0f, -0.40f };
    CHECK(paletteHandRowDragStep(&st, 1, 1, h9mm, rightAxis) == 1);
    // Anchor advanced by 8 mm, so moving to -10 mm (-18 mm from 8 mm anchor) emits -2 steps
    Vec3 hNeg = { -0.010f, 0.0f, -0.40f };
    CHECK(paletteHandRowDragStep(&st, 1, 1, hNeg, rightAxis) == -2);
    // Release resets drag cleanly
    CHECK(paletteHandRowDragStep(&st, 0, -1, hNeg, rightAxis) == 0);
    CHECK(st.handRowDragging == 0);
}

static void testScreenAdjustDimmingAndLayerPredicate(void) {
    PaletteState st;
    paletteInit(&st);
    XrPosef head = identityPose(0.0f, 0.0f, 0.0f);
    XrPosef grip = identityPose(-0.15f, -0.20f, -0.45f);
    PaletteRenderPose rp;

    paletteOpenFromMenu(&st, head, grip, 1, 0, MS(1000));
    // With colorScale supported: screenAdjustActive dims alpha to 40%, dimFallback = 0
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 1, 0.016f, MS(1300), 1, &rp);
    CHECK(rp.visible == 1);
    CHECK_NEAR(rp.alpha, PALETTE_DIM_ALPHA, 1e-4f);
    CHECK(rp.dimFallback == 0);

    // Without colorScale extension: alpha stays 1.0 and dimFallback = 1 triggers Java 40% redraw
    paletteStepPoseAndFade(&st, head, grip, 1, 0, 1, 0.016f, MS(1320), 0, &rp);
    CHECK(rp.visible == 1);
    CHECK_NEAR(rp.alpha, 1.0f, 1e-4f);
    CHECK(rp.dimFallback == 1);

    // Layer predicate matches xr_layers.c single-layer submission
    CHECK(paletteShowLayer(1, 1, 1, 0.4f) == 1);
    CHECK(paletteShowLayer(0, 1, 1, 1.0f) == 0);
    CHECK(paletteShowLayer(1, 0, 1, 1.0f) == 0);
    CHECK(paletteShowLayer(1, 1, 0, 1.0f) == 0);
    CHECK(paletteShowLayer(1, 1, 1, 0.0f) == 0);
}

// G2 review #4 & Should-Fix #3/#4: dimmer gated on localMedia, SBS/OU stops depth
// inference, and Full-SBS / Full-OU single-eye aspect ratio heuristic.
static void testDimmerDepthGatingAndStereoAspect(void) {
    // Passthrough dimmer MUST NOT fire in PC streaming (localMedia == 0)
    CHECK(paletteShowDimmerLayer(0, 0, 1, 1, 1) == 0);
    CHECK(paletteShowDimmerLayer(1, 0, 1, 1, 1) == 1);
    CHECK(paletteShowDimmerLayer(1, 1, 1, 1, 1) == 0); // mutually exclusive with 3D room
    CHECK(paletteShowDimmerLayer(1, 0, 0, 1, 1) == 0); // void/non-passthrough
    CHECK(paletteShowDimmerLayer(1, 0, 1, 0, 1) == 0); // dim off
    CHECK(paletteShowDimmerLayer(1, 0, 1, 1, 0) == 0); // art not ready

    // Depth inference only runs when stereoMode != OFF, stereoLive == 1, and source == AUTO
    CHECK(paletteNeedsDepthInference(DEPTH_MODE_MODEL, 1, STEREO_SRC_AUTO) == 1);
    CHECK(paletteNeedsDepthInference(DEPTH_MODE_MODEL, 1, STEREO_SRC_SBS) == 0);
    CHECK(paletteNeedsDepthInference(DEPTH_MODE_MODEL, 1, STEREO_SRC_OU) == 0);
    CHECK(paletteNeedsDepthInference(DEPTH_MODE_MODEL, 0, STEREO_SRC_AUTO) == 0);
    CHECK(paletteNeedsDepthInference(DEPTH_MODE_OFF, 1, STEREO_SRC_AUTO) == 0);

    // Aspect ratio: Full-SBS 3840x1080 -> 16:9 per eye (0.5625), Half-SBS 1920x1080 -> 0.5625
    CHECK_NEAR(paletteVideoHeightOverWidth(3840, 1080, STEREO_SRC_SBS), 9.0f / 16.0f, 1e-4f);
    CHECK_NEAR(paletteVideoHeightOverWidth(1920, 1080, STEREO_SRC_SBS), 9.0f / 16.0f, 1e-4f);
    // Full-OU 1920x2160 -> 16:9 per eye (0.5625), Half-OU 1920x1080 -> 0.5625
    CHECK_NEAR(paletteVideoHeightOverWidth(1920, 2160, STEREO_SRC_OU), 9.0f / 16.0f, 1e-4f);
    CHECK_NEAR(paletteVideoHeightOverWidth(1920, 1080, STEREO_SRC_OU), 9.0f / 16.0f, 1e-4f);
    // AUTO uses raw frame aspect
    CHECK_NEAR(paletteVideoHeightOverWidth(3840, 1080, STEREO_SRC_AUTO), 1080.0f / 3840.0f, 1e-4f);
    // 2.39:1 cinema Half-SBS (2560x1072, ratio ~2.388 < 2.60) stays Half-SBS; Full-SBS (5120x1072) halves width
    CHECK_NEAR(paletteVideoHeightOverWidth(2560, 1072, STEREO_SRC_SBS), 1072.0f / 2560.0f, 1e-4f);
    CHECK_NEAR(paletteVideoHeightOverWidth(5120, 1072, STEREO_SRC_SBS), 2144.0f / 5120.0f, 1e-4f);
}

// G2 review #7: lifting palette-hand suppression while trigger or grip is still held
// MUST NOT produce a phantom click/grab until physically released below PRESS_OFF.
static void testSuppressReleaseLatchNoPhantomEdge(void) {
    PaletteState st;
    PaletteSuppressLatch latch;
    paletteInit(&st);
    paletteSuppressLatchReset(&latch);

    XrPosef head = identityPose(0.0f, 0.0f, 0.0f);
    XrPosef grip = identityPose(-0.15f, -0.20f, -0.45f);
    paletteOpenFromMenu(&st, head, grip, 1, 0, MS(1000));

    // Frame 1: palette open on left hand (0), user holds BOTH left trigger and left grip
    PalettePreGlueIn in;
    PalettePreGlueOut out;
    memset(&in, 0, sizeof(in));
    in.headPose = head;
    in.gripPose = grip;
    in.gripValid = 1;
    in.colorScaleSupported = 1;
    in.dt = 0.016f;
    in.nowNs = MS(1200);
    in.rawTriggerDown[0] = 1;
    in.rawTriggerVal[0] = 0.95f;
    in.rawGripVal[0] = 0.90f;
    in.pressOff = 0.35f;
    paletteGluePreStep(&st, &latch, &in, &out);
    CHECK(out.suppressRay[0] == 1);
    CHECK(out.blockTrigger[0] == 1);
    CHECK(out.blockGrip[0] == 1);
    CHECK(latch.triggerWaitRelease[0] == 1);
    CHECK(latch.gripWaitRelease[0] == 1);

    // Frame 2: palette closes (or pins) while left trigger & left grip are STILL held!
    in.nowNs = MS(1216);
    in.closeRequested = 1;
    paletteGluePreStep(&st, &latch, &in, &out);
    CHECK(st.open == 0);
    CHECK(out.suppressRay[0] == 0);
    // Latched: MUST stay blocked so readSources/applyGrab cannot emit phantom edges!
    CHECK(out.blockTrigger[0] == 1);
    CHECK(out.blockGrip[0] == 1);

    // Frame 3: trigger dips to 0.50 (> PRESS_OFF) and grip stays 0.45 (> PRESS_OFF) -> still blocked
    in.closeRequested = 0;
    in.nowNs = MS(1232);
    in.rawTriggerDown[0] = 0;
    in.rawTriggerVal[0] = 0.50f;
    in.rawGripVal[0] = 0.45f;
    paletteGluePreStep(&st, &latch, &in, &out);
    CHECK(out.blockTrigger[0] == 1);
    CHECK(out.blockGrip[0] == 1);

    // Frame 4: user releases both below PRESS_OFF -> latches clear cleanly
    in.nowNs = MS(1248);
    in.rawTriggerDown[0] = 0;
    in.rawTriggerVal[0] = 0.10f;
    in.rawGripVal[0] = 0.05f;
    paletteGluePreStep(&st, &latch, &in, &out);
    CHECK(out.blockTrigger[0] == 0);
    CHECK(out.blockGrip[0] == 0);
    CHECK(latch.triggerWaitRelease[0] == 0);
    CHECK(latch.gripWaitRelease[0] == 0);

    // Frame 5: fresh deliberate press after release goes through immediately!
    in.nowNs = MS(1264);
    in.rawTriggerDown[0] = 1;
    in.rawTriggerVal[0] = 0.90f;
    in.rawGripVal[0] = 0.90f;
    paletteGluePreStep(&st, &latch, &in, &out);
    CHECK(out.blockTrigger[0] == 0);
    CHECK(out.blockGrip[0] == 0);
}

// Pure-C xr_input.c glue tests: updateLocalPalettePost (click, slider drag continuation,
// deadzone swallow, grip detach drag, pin toggle) and right-hand stick mirroring.
static void testPaletteGluePostAndStickMirror(void) {
    PaletteState st;
    paletteInit(&st);
    XrPosef head = identityPose(0.0f, 0.0f, 0.0f);
    XrPosef panel = identityPose(0.0f, 0.0f, -0.50f);
    paletteOpenFromBar(&st, head, 0, MS(1000));
    st.pinnedPose = panel;
    st.currentPose = panel;
    st.poseValid = 1;

    PaletteRenderPose rp;
    memset(&rp, 0, sizeof(rp));
    rp.visible = 1;
    rp.inputAllowed = 1;
    rp.pose = panel;
    rp.alpha = 1.0f;

    // 1. Right controller clicks row 0 -> clickState == 1, swallows trigger, starts slider drag
    float vRow0 = (PALETTE_ROW_Y0 + 0.5f * PALETTE_ROW_H) / (float)PALETTE_TEX_H;
    Vec3 eye = { 0.0f, 0.0f, 0.0f };
    Vec3 dirRow0 = { 0.0f, (0.5f - vRow0) * PALETTE_H_M, -0.50f };

    PalettePostGlueIn pin;
    PalettePostGlueOut pout;
    memset(&pin, 0, sizeof(pin));
    pin.headPose = head;
    pin.activeRowMask = 0xF;
    pin.nowNs = MS(1200);
    pin.pressOn = 0.65f;
    pin.pressOff = 0.35f;
    pin.canPoint[1] = 1;
    pin.aimValid[1] = 1;
    pin.aimPoses[1] = aimToward(eye, dirRow0);
    pin.triggerDown[1] = 1;
    pin.triggerEdge[1] = 1;
    pin.dragHand = -1;
    pin.sliderHand = -1;
    pin.renderPose = rp;

    paletteGluePostStep(&st, &pin, &pout);
    CHECK(pout.claimed == 1);
    CHECK(pout.claimedHand == 1);
    CHECK(pout.clickState == 1);
    CHECK(pout.swallowHand == 1);
    CHECK(pout.nextSliderDragging == 1);
    CHECK(pout.nextSliderHand == 1);

    // 2. While holding trigger and drifting slightly past right edge, slider drag continues (clickState == 2)
    Vec3 dirRightEdge = { 0.12f, (0.5f - vRow0) * PALETTE_H_M, -0.50f };
    pin.aimPoses[1] = aimToward(eye, dirRightEdge);
    pin.triggerEdge[1] = 0;
    pin.triggerDown[1] = 1;
    pin.sliderDragging = pout.nextSliderDragging;
    pin.sliderHand = pout.nextSliderHand;
    paletteGluePostStep(&st, &pin, &pout);
    CHECK(pout.claimed == 1);
    CHECK(pout.clickState == 2);
    CHECK_NEAR(pout.hit.u, 1.0f, 1e-4f);

    // 3. Release trigger -> slider drag terminates cleanly
    pin.triggerDown[1] = 0;
    pin.sliderDragging = pout.nextSliderDragging;
    pin.sliderHand = pout.nextSliderHand;
    paletteGluePostStep(&st, &pin, &pout);
    CHECK(pout.nextSliderDragging == 0);
    CHECK(pout.nextSliderHand == -1);

    // 4. Deadzone trigger click is swallowed (does not leak to video)
    Vec3 dirDead = { 0.108f, 0.0f, -0.50f };
    pin.sliderDragging = 0;
    pin.sliderHand = -1;
    pin.aimPoses[1] = aimToward(eye, dirDead);
    pin.triggerDown[1] = 1;
    pin.triggerEdge[1] = 1;
    paletteGluePostStep(&st, &pin, &pout);
    CHECK(pout.claimed == 1);
    CHECK(pout.hit.zone == PALETTE_HIT_DEADZONE);
    CHECK(pout.swallowHand == 1);
    CHECK(pout.clickState == 0);

    // 5. Stick mirroring when palette is open on left hand vs right hand (§6.3)
    int bothCtrl[2] = { 1, 1 };
    float opX = 0, opY = 0, palX = 0, palY = 0;
    int opClick = 0;
    // Left-mounted (default): right stick operates (0.8, -0.4, click=1), left stick switches tabs (-0.7, 0.6)
    paletteSetHand(&st, 0);
    paletteGlueMirrorSticks(&st, bothCtrl, -0.7f, 0.6f, 0, 0.8f, -0.4f, 1,
                            &opX, &opY, &opClick, &palX, &palY);
    CHECK_NEAR(opX, 0.8f, 1e-5f);
    CHECK_NEAR(opY, -0.4f, 1e-5f);
    CHECK(opClick == 1);
    CHECK_NEAR(palX, -0.7f, 1e-5f);
    CHECK_NEAR(palY, 0.6f, 1e-5f);

    // Right-mounted: mirrored! Left stick operates (-0.7, 0.6, click=1), right stick switches tabs (0.8, -0.4)
    paletteSetHand(&st, 1);
    paletteGlueMirrorSticks(&st, bothCtrl, -0.7f, 0.6f, 1, 0.8f, -0.4f, 0,
                            &opX, &opY, &opClick, &palX, &palY);
    CHECK_NEAR(opX, -0.7f, 1e-5f);
    CHECK_NEAR(opY, 0.6f, 1e-5f);
    CHECK(opClick == 1);
    CHECK_NEAR(palX, 0.8f, 1e-5f);
    CHECK_NEAR(palY, -0.4f, 1e-5f);

    // 6. B-button glue: non-close B (e.g. adjust undo / card close) keeps palette open;
    //    close-palette B closes palette and requests clearing active drags.
    int clearDrags = 0;
    CHECK(paletteGlueApplyCloseOnB(&st, 0, MS(1900), &clearDrags) == 0);
    CHECK(st.open == 1);
    CHECK(clearDrags == 0);

    CHECK(paletteGlueApplyCloseOnB(&st, 1, MS(1950), &clearDrags) == 1);
    CHECK(st.open == 0);
    CHECK(clearDrags == 1);
    // Repeated close on already-closed palette is a clean no-op
    CHECK(paletteGlueApplyCloseOnB(&st, 1, MS(1960), &clearDrags) == 0);
    CHECK(clearDrags == 0);

    // Closed palette: right stick always operates regardless of right-hand mount setting
    paletteGlueMirrorSticks(&st, bothCtrl, -0.7f, 0.6f, 1, 0.8f, -0.4f, 0,
                            &opX, &opY, &opClick, &palX, &palY);
    CHECK_NEAR(opX, 0.8f, 1e-5f);
    CHECK(opClick == 0);
}

int main(void) {
    testHandMountPoseAndRightHandMirror();
    testAngleCullAndFade();
    testPinUnpinFlyBackAndReopenCone();
    testDeadzoneAndHoverHysteresis();
    testHandSuppressionAndDragging();
    testScreenAdjustDimmingAndLayerPredicate();
    testDimmerDepthGatingAndStereoAspect();
    testSuppressReleaseLatchNoPhantomEdge();
    testPaletteGluePostAndStickMirror();
    return checksDone("xr_palette");
}
