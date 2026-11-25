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

int main(void) {
    testHandMountPoseAndRightHandMirror();
    testAngleCullAndFade();
    testPinUnpinFlyBackAndReopenCone();
    testDeadzoneAndHoverHysteresis();
    testHandSuppressionAndDragging();
    testScreenAdjustDimmingAndLayerPredicate();
    return checksDone("xr_palette");
}
