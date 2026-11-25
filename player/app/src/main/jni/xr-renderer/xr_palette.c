// Pure C VR Quick Palette geometry, pose filter, hit-testing, edge deadzone,
// hover hysteresis, dragging, and fade/dimming controller (ux-spec.md §6.3-§6.5).

#include "xr_palette.h"
#include "xr_layout.h"
#include <math.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static float vecLen(Vec3 v) {
    return sqrtf(v.x * v.x + v.y * v.y + v.z * v.z);
}

static float radToDeg(float r) {
    return r * (180.0f / (float)M_PI);
}

static float degToRad(float d) {
    return d * ((float)M_PI / 180.0f);
}

// Shortest-arc normalized slerp (falls back to nlerp for tiny angles)
static XrQuaternionf quatSlerp(XrQuaternionf a, XrQuaternionf b, float t) {
    t = clampf(t, 0.0f, 1.0f);
    float dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
    if (dot < 0.0f) {
        b.x = -b.x;
        b.y = -b.y;
        b.z = -b.z;
        b.w = -b.w;
        dot = -dot;
    }
    if (dot > 0.9995f) {
        XrQuaternionf r = {
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t,
            a.w + (b.w - a.w) * t
        };
        return quatNorm(r);
    }
    float theta0 = acosf(clampf(dot, -1.0f, 1.0f));
    float theta = theta0 * t;
    float sin0 = sinf(theta0);
    float s0 = cosf(theta) - dot * sinf(theta) / sin0;
    float s1 = sinf(theta) / sin0;
    XrQuaternionf r = {
        s0 * a.x + s1 * b.x,
        s0 * a.y + s1 * b.y,
        s0 * a.z + s1 * b.z,
        s0 * a.w + s1 * b.w
    };
    return quatNorm(r);
}

static XrPosef poseLerp(XrPosef a, XrPosef b, float t) {
    // Cubic ease-out per §5.6 motion curve
    t = clampf(t, 0.0f, 1.0f);
    float u = 1.0f - t;
    float eased = 1.0f - u * u * u;
    XrPosef out;
    out.position.x = a.position.x + (b.position.x - a.position.x) * eased;
    out.position.y = a.position.y + (b.position.y - a.position.y) * eased;
    out.position.z = a.position.z + (b.position.z - a.position.z) * eased;
    out.orientation = quatSlerp(a.orientation, b.orientation, eased);
    return out;
}

// Minimum rotation taking unit vector `from` onto unit vector `to`, clamped to maxRad
static XrQuaternionf clampedAlignQuat(Vec3 from, Vec3 to, float maxRad) {
    float dot = clampf(vecDot(from, to), -1.0f, 1.0f);
    float angle = acosf(dot);
    if (angle < 1e-5f) {
        XrQuaternionf id = { 0.0f, 0.0f, 0.0f, 1.0f };
        return id;
    }
    Vec3 axis = vecCross(from, to);
    float len = vecLen(axis);
    if (len < 1e-5f) {
        XrQuaternionf id = { 0.0f, 0.0f, 0.0f, 1.0f };
        return id;
    }
    axis.x /= len;
    axis.y /= len;
    axis.z /= len;
    if (angle > maxRad) {
        angle = maxRad;
    }
    return axisAngleQuat(axis, angle);
}

void paletteInit(PaletteState* st) {
    memset(st, 0, sizeof(*st));
    st->currentPose.orientation.w = 1.0f;
    st->pinnedPose.orientation.w = 1.0f;
    st->lpfOrient.w = 1.0f;
    st->hoverIndex = -1;
    st->handRowIdx = -1;
}

void paletteSetHand(PaletteState* st, int onRightHand) {
    int want = onRightHand ? 1 : 0;
    if (st->onRightHand != want) {
        st->onRightHand = want;
        st->lpfValid = 0;
    }
}

int paletteMountHand(const PaletteState* st) {
    return st->onRightHand ? 1 : 0;
}

int paletteOpHand(const PaletteState* st) {
    return st->onRightHand ? 0 : 1;
}

int paletteShouldSuppressHand(const PaletteState* st, int hand, int handTracking) {
    if (!st->open || st->pinned || handTracking) {
        return 0;
    }
    return hand == paletteMountHand(st) ? 1 : 0;
}

XrPosef paletteComputeUprightPose(Vec3 position, Vec3 headPos) {
    Vec3 toHead = vecSub(headPos, position);
    float len = vecLen(toHead);
    Vec3 zAxis;
    if (len < 1e-4f) {
        zAxis.x = 0.0f;
        zAxis.y = 0.0f;
        zAxis.z = 1.0f;
    }
    else {
        zAxis.x = toHead.x / len;
        zAxis.y = toHead.y / len;
        zAxis.z = toHead.z / len;
    }
    // Keep horizontal axis level with world up so pinned palette stands upright
    Vec3 worldUp = { 0.0f, 1.0f, 0.0f };
    Vec3 xAxis = vecCross(worldUp, zAxis);
    if (vecLen(xAxis) < 1e-3f) {
        xAxis.x = 1.0f;
        xAxis.y = 0.0f;
        xAxis.z = 0.0f;
    }
    else {
        xAxis = vecNorm(xAxis);
    }
    Vec3 yAxis = vecNorm(vecCross(zAxis, xAxis));

    XrPosef pose;
    pose.position.x = position.x;
    pose.position.y = position.y;
    pose.position.z = position.z;
    pose.orientation = quatFromBasis(xAxis, yAxis, zAxis);
    return pose;
}

XrPosef paletteComputeBarOpenPose(XrPosef headPose) {
    // 20 deg below current gaze direction, 0.50 m in front of eyes
    float dropRad = degToRad(PALETTE_PIN_DROP_DEG);
    Vec3 dirLocal = { 0.0f, -sinf(dropRad), -cosf(dropRad) };
    Vec3 dirWorld = quatRotate(headPose.orientation, dirLocal);
    Vec3 head = { headPose.position.x, headPose.position.y, headPose.position.z };
    Vec3 pos = {
        head.x + dirWorld.x * PALETTE_PIN_DIST_M,
        head.y + dirWorld.y * PALETTE_PIN_DIST_M,
        head.z + dirWorld.z * PALETTE_PIN_DIST_M
    };
    return paletteComputeUprightPose(pos, head);
}

XrPosef paletteComputeHandPose(XrPosef gripPose, Vec3 headPos, int onRightHand,
                               XrQuaternionf* lpfOrient, int* lpfValid, float dt) {
    // Grip frame convention (OpenXR): +Y up out of palm/handle, +Z back toward
    // user/wrist, +X right of controller.
    // 1. Base orientation: pitch back by PALETTE_MOUNT_PITCH_DEG around grip +X
    //    so the quad (+Z normal) tilts upward toward the viewer's eyes.
    Vec3 localX = { 1.0f, 0.0f, 0.0f };
    XrQuaternionf tiltBack = axisAngleQuat(localX, -degToRad(PALETTE_MOUNT_PITCH_DEG));
    XrQuaternionf baseOrient = quatNorm(quatMul(gripPose.orientation, tiltBack));

    // 2. Position: bottom edge sits PALETTE_MOUNT_UP_M above grip origin (+Y in
    //    grip frame) and shifted PALETTE_MOUNT_IN_M toward body midline
    //    (+X for left hand, -X for right hand). Center adds half-height along
    //    the tilted palette's own +Y axis.
    Vec3 bottomLocal = {
        onRightHand ? -PALETTE_MOUNT_IN_M : PALETTE_MOUNT_IN_M,
        PALETTE_MOUNT_UP_M,
        0.0f
    };
    Vec3 bottomWorld = quatRotate(gripPose.orientation, bottomLocal);
    Vec3 halfUpLocal = { 0.0f, PALETTE_H_M * 0.5f, 0.0f };
    Vec3 halfUpWorld = quatRotate(baseOrient, halfUpLocal);

    Vec3 center = {
        gripPose.position.x + bottomWorld.x + halfUpWorld.x,
        gripPose.position.y + bottomWorld.y + halfUpWorld.y,
        gripPose.position.z + bottomWorld.z + halfUpWorld.z
    };

    // 3. Eye-facing correction: rotate base normal toward eye by at most 20 deg
    Vec3 fwdNormalLocal = { 0.0f, 0.0f, 1.0f };
    Vec3 baseNormal = quatRotate(baseOrient, fwdNormalLocal);
    Vec3 toEye = vecSub(headPos, center);
    float dist = vecLen(toEye);
    XrQuaternionf targetOrient = baseOrient;
    if (dist > 1e-4f) {
        toEye.x /= dist;
        toEye.y /= dist;
        toEye.z /= dist;
        XrQuaternionf corr = clampedAlignQuat(baseNormal, toEye,
                                              degToRad(PALETTE_EYE_CORRECT_MAX_DEG));
        targetOrient = quatNorm(quatMul(corr, baseOrient));
    }

    // 4. 100 ms exponential low-pass filter on orientation to damp wrist tremor
    XrQuaternionf finalOrient = targetOrient;
    if (lpfOrient != NULL && lpfValid != NULL) {
        if (!*lpfValid || dt <= 0.0f) {
            *lpfOrient = targetOrient;
            *lpfValid = 1;
        }
        else {
            float alpha = 1.0f - expf(-dt / PALETTE_EYE_LPF_TAU_S);
            *lpfOrient = quatSlerp(*lpfOrient, targetOrient, alpha);
        }
        finalOrient = *lpfOrient;
    }

    XrPosef out;
    out.position.x = center.x;
    out.position.y = center.y;
    out.position.z = center.z;
    out.orientation = finalOrient;
    return out;
}

float paletteNormalAngleDeg(XrPosef palettePose, Vec3 headPos) {
    Vec3 center = { palettePose.position.x, palettePose.position.y, palettePose.position.z };
    Vec3 toHead = vecSub(headPos, center);
    float len = vecLen(toHead);
    if (len < 1e-4f) {
        return 0.0f;
    }
    toHead.x /= len;
    toHead.y /= len;
    toHead.z /= len;
    Vec3 normalLocal = { 0.0f, 0.0f, 1.0f };
    Vec3 normal = quatRotate(palettePose.orientation, normalLocal);
    float dot = clampf(vecDot(normal, toHead), -1.0f, 1.0f);
    return radToDeg(acosf(dot));
}

int paletteWithinReopenCone(XrPosef pinnedPose, XrPosef headPose) {
    Vec3 headPos = { headPose.position.x, headPose.position.y, headPose.position.z };
    Vec3 pinPos = { pinnedPose.position.x, pinnedPose.position.y, pinnedPose.position.z };
    Vec3 toPin = vecSub(pinPos, headPos);
    float len = vecLen(toPin);
    if (len < 0.05f) {
        return 0;
    }
    toPin.x /= len;
    toPin.y /= len;
    toPin.z /= len;
    Vec3 gazeLocal = { 0.0f, 0.0f, -1.0f };
    Vec3 gaze = quatRotate(headPose.orientation, gazeLocal);
    float dot = clampf(vecDot(gaze, toPin), -1.0f, 1.0f);
    return radToDeg(acosf(dot)) <= PALETTE_REOPEN_PIN_MAX_DEG ? 1 : 0;
}

static void startFade(PaletteState* st, int target, int64_t nowNs) {
    if (st->fadeTarget == target && (st->fadeRunning || st->fadeAlpha == (target ? 1.0f : 0.0f))) {
        return;
    }
    st->fadeTarget = target;
    st->fadeStartNs = nowNs;
    st->fadeStartAlpha = st->fadeAlpha;
    st->fadeRunning = 1;
}

void paletteOpenFromMenu(PaletteState* st, XrPosef headPose, XrPosef gripPose,
                         int gripValid, int handTracking, int64_t nowNs) {
    st->open = 1;
    st->handTrackingMode = handTracking ? 1 : 0;
    st->dragging = 0;
    st->handRowDragging = 0;
    st->hoverZone = PALETTE_HIT_NONE;
    st->hoverIndex = -1;
    st->animMode = PALETTE_ANIM_NONE;
    st->lpfValid = 0;

    if (handTracking || !gripValid) {
        st->pinned = 1;
        if (st->hasLastPinned && paletteWithinReopenCone(st->pinnedPose, headPose)) {
            Vec3 headPos = { headPose.position.x, headPose.position.y, headPose.position.z };
            Vec3 pinPos = { st->pinnedPose.position.x, st->pinnedPose.position.y,
                            st->pinnedPose.position.z };
            st->pinnedPose = paletteComputeUprightPose(pinPos, headPos);
        }
        else {
            st->pinnedPose = paletteComputeBarOpenPose(headPose);
        }
        st->hasLastPinned = 1;
        st->currentPose = st->pinnedPose;
        st->poseValid = 1;
    }
    else if (st->hasLastPinned && paletteWithinReopenCone(st->pinnedPose, headPose)) {
        // §6.3: reopen at previous pinned spot if still within ±60 deg of gaze
        Vec3 headPos = { headPose.position.x, headPose.position.y, headPose.position.z };
        Vec3 pinPos = { st->pinnedPose.position.x, st->pinnedPose.position.y,
                        st->pinnedPose.position.z };
        st->pinned = 1;
        st->pinnedPose = paletteComputeUprightPose(pinPos, headPos);
        st->currentPose = st->pinnedPose;
        st->poseValid = 1;
    }
    else {
        st->pinned = 0;
        Vec3 headPos = { headPose.position.x, headPose.position.y, headPose.position.z };
        st->currentPose = paletteComputeHandPose(gripPose, headPos, st->onRightHand,
                                                 &st->lpfOrient, &st->lpfValid, 0.0f);
        st->poseValid = 1;
    }
    startFade(st, 1, nowNs);
}

void paletteOpenFromBar(PaletteState* st, XrPosef headPose, int handTracking, int64_t nowNs) {
    st->open = 1;
    st->pinned = 1;
    st->handTrackingMode = handTracking ? 1 : 0;
    st->dragging = 0;
    st->handRowDragging = 0;
    st->hoverZone = PALETTE_HIT_NONE;
    st->hoverIndex = -1;
    st->animMode = PALETTE_ANIM_NONE;
    st->pinnedPose = paletteComputeBarOpenPose(headPose);
    st->hasLastPinned = 1;
    st->currentPose = st->pinnedPose;
    st->poseValid = 1;
    startFade(st, 1, nowNs);
}

void paletteTogglePin(PaletteState* st, Vec3 headPos, int handTracking, int64_t nowNs) {
    if (!st->open) {
        return;
    }
    if (handTracking) {
        // In hand-tracking mode, the top-right button is × (close)
        paletteClose(st, nowNs);
        return;
    }
    if (!st->pinned) {
        // Pin in place and smoothly turn upright facing the viewer over 200 ms
        Vec3 curPos = { st->currentPose.position.x, st->currentPose.position.y,
                        st->currentPose.position.z };
        st->pinned = 1;
        st->pinnedPose = paletteComputeUprightPose(curPos, headPos);
        st->hasLastPinned = 1;
        st->animMode = PALETTE_ANIM_TO_PINNED;
        st->animStartNs = nowNs;
        st->animFromPose = st->currentPose;
    }
    else {
        // Unpin: fly back smoothly to palette hand over 200 ms
        st->pinned = 0;
        st->hasLastPinned = 0;
        st->dragging = 0;
        st->animMode = PALETTE_ANIM_TO_HAND;
        st->animStartNs = nowNs;
        st->animFromPose = st->currentPose;
        st->lpfValid = 0;
    }
}

void paletteClose(PaletteState* st, int64_t nowNs) {
    if (!st->open) {
        return;
    }
    st->open = 0;
    st->dragging = 0;
    st->handRowDragging = 0;
    st->hoverZone = PALETTE_HIT_NONE;
    st->hoverIndex = -1;
    startFade(st, 0, nowNs);
}

void paletteStartDrag(PaletteState* st, XrPosef aimPose, Vec3 headPos, int byGrip, int64_t nowNs) {
    if (!st->open || !st->poseValid) {
        return;
    }
    // Detach from hand into pinned state if currently hand-mounted
    if (!st->pinned) {
        Vec3 curPos = { st->currentPose.position.x, st->currentPose.position.y,
                        st->currentPose.position.z };
        st->pinned = 1;
        st->pinnedPose = paletteComputeUprightPose(curPos, headPos);
        st->hasLastPinned = 1;
        st->animMode = PALETTE_ANIM_NONE;
    }
    st->dragging = 1;
    st->dragByGrip = byGrip ? 1 : 0;
    st->dragStartAim = aimPose;
    st->dragStartPose = st->pinnedPose;
    (void)nowNs;
}

void paletteStepDrag(PaletteState* st, XrPosef curAimPose, Vec3 headPos) {
    if (!st->dragging) {
        return;
    }
    // Rigid attach translation swung by aim rotation, orientation always upright facing viewer
    XrQuaternionf turn = quatMul(curAimPose.orientation, quatConj(st->dragStartAim.orientation));
    Vec3 offset = {
        st->dragStartPose.position.x - st->dragStartAim.position.x,
        st->dragStartPose.position.y - st->dragStartAim.position.y,
        st->dragStartPose.position.z - st->dragStartAim.position.z
    };
    Vec3 moved = quatRotate(turn, offset);
    Vec3 newPos = {
        curAimPose.position.x + moved.x,
        curAimPose.position.y + moved.y,
        curAimPose.position.z + moved.z
    };
    st->pinnedPose = paletteComputeUprightPose(newPos, headPos);
    st->currentPose = st->pinnedPose;
    st->hasLastPinned = 1;
}

void paletteEndDrag(PaletteState* st) {
    st->dragging = 0;
}

static void stepFadeValue(PaletteState* st, int wantVisible, int64_t nowNs,
                          int colorScaleSupported) {
    if (wantVisible != st->fadeTarget) {
        startFade(st, wantVisible, nowNs);
    }
    if (!colorScaleSupported) {
        st->fadeAlpha = wantVisible ? 1.0f : 0.0f;
        st->fadeRunning = 0;
        return;
    }
    if (!st->fadeRunning) {
        st->fadeAlpha = wantVisible ? 1.0f : 0.0f;
        return;
    }
    int64_t dur = wantVisible ? PALETTE_OPEN_FADE_NS : PALETTE_CLOSE_FADE_NS;
    float elapsed = (float)(nowNs - st->fadeStartNs);
    float frac = dur > 0 ? clampf(elapsed / (float)dur, 0.0f, 1.0f) : 1.0f;
    float targetVal = wantVisible ? 1.0f : 0.0f;
    st->fadeAlpha = st->fadeStartAlpha + (targetVal - st->fadeStartAlpha) * frac;
    if (frac >= 1.0f) {
        st->fadeAlpha = targetVal;
        st->fadeRunning = 0;
    }
}

void paletteStepPoseAndFade(PaletteState* st, XrPosef headPose, XrPosef gripPose,
                            int gripValid, int handTracking, int screenAdjustActive,
                            float dt, int64_t nowNs, int colorScaleSupported,
                            PaletteRenderPose* out) {
    memset(out, 0, sizeof(*out));
    Vec3 headPos = { headPose.position.x, headPose.position.y, headPose.position.z };

    int culledByAngle = 0;
    if (st->open) {
        st->handTrackingMode = handTracking ? 1 : 0;
        if (st->pinned) {
            if (st->animMode == PALETTE_ANIM_TO_PINNED) {
                float frac = (float)(nowNs - st->animStartNs) / (float)PALETTE_FLY_NS;
                if (frac >= 1.0f) {
                    st->currentPose = st->pinnedPose;
                    st->animMode = PALETTE_ANIM_NONE;
                }
                else {
                    st->currentPose = poseLerp(st->animFromPose, st->pinnedPose, frac);
                }
            }
            else {
                st->currentPose = st->pinnedPose;
            }
            st->poseValid = 1;
        }
        else if (gripValid) {
            XrPosef handTarget = paletteComputeHandPose(gripPose, headPos, st->onRightHand,
                                                        &st->lpfOrient, &st->lpfValid, dt);
            if (st->animMode == PALETTE_ANIM_TO_HAND) {
                float frac = (float)(nowNs - st->animStartNs) / (float)PALETTE_FLY_NS;
                if (frac >= 1.0f) {
                    st->currentPose = handTarget;
                    st->animMode = PALETTE_ANIM_NONE;
                }
                else {
                    st->currentPose = poseLerp(st->animFromPose, handTarget, frac);
                }
            }
            else {
                st->currentPose = handTarget;
            }
            st->poseValid = 1;
            // Hand-mounted angle cull: > 60 deg away from eye fades out & disables input
            if (st->animMode == PALETTE_ANIM_NONE
                    && paletteNormalAngleDeg(st->currentPose, headPos) > PALETTE_CULL_ANGLE_DEG) {
                culledByAngle = 1;
            }
        }
        else {
            // Grip tracking temporarily dropped while hand-mounted -> hide & block input
            culledByAngle = 1;
        }
    }

    int wantVisible = st->open && st->poseValid && !culledByAngle;
    stepFadeValue(st, wantVisible, nowNs, colorScaleSupported);

    float alpha = st->fadeAlpha;
    int dimFallback = 0;
    if (screenAdjustActive && st->open) {
        if (colorScaleSupported) {
            alpha *= PALETTE_DIM_ALPHA;
        }
        else {
            dimFallback = 1;
        }
    }

    out->visible = st->poseValid && alpha > 0.001f ? 1 : 0;
    out->inputAllowed = (st->open && st->poseValid && !culledByAngle
                         && st->animMode == PALETTE_ANIM_NONE) ? 1 : 0;
    out->pose = st->currentPose;
    out->alpha = clampf(alpha, 0.0f, 1.0f);
    out->dimFallback = dimFallback;
}

static int pointInRect(float px, float py, float x, float y, float w, float h, float pad) {
    return px >= x - pad && px <= x + w + pad && py >= y - pad && py <= y + h + pad;
}

static void getTabRect(int tab, float* x, float* y, float* w, float* h) {
    static const float TAB_X[PALETTE_TAB_COUNT] = {
        PALETTE_TAB0_X, PALETTE_TAB1_X, PALETTE_TAB2_X, PALETTE_TAB3_X
    };
    static const float TAB_W[PALETTE_TAB_COUNT] = {
        PALETTE_TAB0_W, PALETTE_TAB1_W, PALETTE_TAB2_W, PALETTE_TAB3_W
    };
    *x = TAB_X[tab];
    *y = (float)PALETTE_TAB_Y;
    *w = TAB_W[tab];
    *h = (float)PALETTE_TAB_H;
}

static void getRowRect(int row, float* x, float* y, float* w, float* h) {
    *x = (float)PALETTE_ROW_X;
    *y = (float)(PALETTE_ROW_Y0 + row * PALETTE_ROW_H);
    *w = (float)PALETTE_ROW_W;
    *h = (float)PALETTE_ROW_H;
}

static void classifyPointStrict(float px, float py, int* outZone, int* outIdx) {
    // 1. Tabs
    for (int t = 0; t < PALETTE_TAB_COUNT; t++) {
        float x, y, w, h;
        getTabRect(t, &x, &y, &w, &h);
        if (pointInRect(px, py, x, y, w, h, 0.0f)) {
            *outZone = PALETTE_HIT_TAB;
            *outIdx = t;
            return;
        }
    }
    // 2. Pin / Close button
    if (pointInRect(px, py, (float)PALETTE_PIN_X, (float)PALETTE_PIN_Y,
                    (float)PALETTE_PIN_SIZE, (float)PALETTE_PIN_SIZE, 0.0f)) {
        *outZone = PALETTE_HIT_PIN;
        *outIdx = -1;
        return;
    }
    // 3. Top blank drag bar (used for hand-tracking reposition)
    if (py < (float)PALETTE_ROW_Y0) {
        *outZone = PALETTE_HIT_DRAGBAR;
        *outIdx = -1;
        return;
    }
    // 4. Rows 0..3
    for (int r = 0; r < PALETTE_MAX_ROWS; r++) {
        float x, y, w, h;
        getRowRect(r, &x, &y, &w, &h);
        if (pointInRect(px, py, x, y, w, h, 0.0f)) {
            *outZone = PALETTE_HIT_ROW;
            *outIdx = r;
            return;
        }
    }
    *outZone = PALETTE_HIT_OTHER;
    *outIdx = -1;
}

static int insideExpandedPrevious(int zone, int idx, float px, float py) {
    float pad = PALETTE_HYSTERESIS_PX;
    if (zone == PALETTE_HIT_TAB && idx >= 0 && idx < PALETTE_TAB_COUNT) {
        float x, y, w, h;
        getTabRect(idx, &x, &y, &w, &h);
        return pointInRect(px, py, x, y, w, h, pad);
    }
    if (zone == PALETTE_HIT_PIN) {
        return pointInRect(px, py, (float)PALETTE_PIN_X, (float)PALETTE_PIN_Y,
                           (float)PALETTE_PIN_SIZE, (float)PALETTE_PIN_SIZE, pad);
    }
    if (zone == PALETTE_HIT_ROW && idx >= 0 && idx < PALETTE_MAX_ROWS) {
        float x, y, w, h;
        getRowRect(idx, &x, &y, &w, &h);
        return pointInRect(px, py, x, y, w, h, pad);
    }
    return 0;
}

void paletteRayHit(PaletteState* st, XrPosef palettePose, XrPosef aimPose,
                   int activeRowMask, PaletteHit* out) {
    memset(out, 0, sizeof(*out));
    out->zone = PALETTE_HIT_NONE;
    out->index = -1;
    out->px = -1.0f;
    out->py = -1.0f;

    float u = 0.0f, v = 0.0f;
    if (!screenProject(aimPose, palettePose, PALETTE_W_M, PALETTE_H_M, 0.0f, 0, &u, &v)) {
        st->hoverZone = PALETTE_HIT_NONE;
        st->hoverIndex = -1;
        return;
    }

    float dzU = PALETTE_DEADZONE_M / PALETTE_W_M;
    float dzV = PALETTE_DEADZONE_M / PALETTE_H_M;
    if (u < -dzU || u > 1.0f + dzU || v < -dzV || v > 1.0f + dzV) {
        st->hoverZone = PALETTE_HIT_NONE;
        st->hoverIndex = -1;
        return;
    }

    out->u = u;
    out->v = v;
    if (u < 0.0f || u > 1.0f || v < 0.0f || v > 1.0f) {
        // Inside 1.5 cm edge deadzone: neither palette click nor screen click-through!
        out->zone = PALETTE_HIT_DEADZONE;
        st->hoverZone = PALETTE_HIT_NONE;
        st->hoverIndex = -1;
        return;
    }

    float px = clampf(u * (float)PALETTE_TEX_W, 0.0f, (float)PALETTE_TEX_W);
    float py = clampf(v * (float)PALETTE_TEX_H, 0.0f, (float)PALETTE_TEX_H);
    out->px = px;
    out->py = py;

    int strictZone = PALETTE_HIT_OTHER;
    int strictIdx = -1;
    classifyPointStrict(px, py, &strictZone, &strictIdx);

    // 20 px hover hysteresis (§6.3): when leaving the previous target into a
    // gap or slightly across its border (<= 20 px), hold the previous target
    // unless the pointer has moved strictly past the 20 px hysteresis margin.
    int finalZone = strictZone;
    int finalIdx = strictIdx;
    if (st->hoverZone != PALETTE_HIT_NONE
            && (strictZone != st->hoverZone || strictIdx != st->hoverIndex)
            && insideExpandedPrevious(st->hoverZone, st->hoverIndex, px, py)) {
        finalZone = st->hoverZone;
        finalIdx = st->hoverIndex;
    }

    st->hoverZone = finalZone;
    st->hoverIndex = finalIdx;
    st->hoverPx = px;
    st->hoverPy = py;

    out->zone = finalZone;
    out->index = finalIdx;
    if (finalZone == PALETTE_HIT_ROW && finalIdx >= 0 && finalIdx < PALETTE_MAX_ROWS) {
        out->rowAdjustable = (activeRowMask & (1 << finalIdx)) ? 1 : 0;
    }
}

int paletteHandRowDragStep(PaletteState* st, int pinchingOnRow, int rowIdx,
                           Vec3 handPos, Vec3 paletteRight) {
    if (!pinchingOnRow || rowIdx < 0 || rowIdx >= PALETTE_MAX_ROWS) {
        st->handRowDragging = 0;
        st->handRowIdx = -1;
        st->handRowAccumM = 0.0f;
        return 0;
    }
    if (!st->handRowDragging || st->handRowIdx != rowIdx) {
        st->handRowDragging = 1;
        st->handRowIdx = rowIdx;
        st->handRowAnchor = handPos;
        st->handRowAccumM = 0.0f;
        return 0;
    }
    Vec3 delta = vecSub(handPos, st->handRowAnchor);
    float dx = vecDot(delta, paletteRight);
    int steps = 0;
    if (dx >= PALETTE_HAND_DRAG_STEP_M) {
        steps = (int)(dx / PALETTE_HAND_DRAG_STEP_M);
    }
    else if (dx <= -PALETTE_HAND_DRAG_STEP_M) {
        steps = -(int)((-dx) / PALETTE_HAND_DRAG_STEP_M);
    }
    if (steps != 0) {
        float consumed = (float)steps * PALETTE_HAND_DRAG_STEP_M;
        st->handRowAnchor.x += paletteRight.x * consumed;
        st->handRowAnchor.y += paletteRight.y * consumed;
        st->handRowAnchor.z += paletteRight.z * consumed;
    }
    return steps;
}

int paletteShowLayer(int localMedia, int paletteVisible, int paletteArtReady, float alpha) {
    return localMedia && paletteVisible && paletteArtReady && alpha > 0.001f ? 1 : 0;
}

int paletteShowDimmerLayer(int localMedia, int roomOn, int passthrough,
                           int passthroughDim, int dimmerArtReady) {
    return (localMedia && !roomOn && passthrough && passthroughDim && dimmerArtReady) ? 1 : 0;
}

int paletteNeedsDepthInference(int stereoMode, int stereoLive, int stereoSourceFormat,
                               int depthGaveUp) {
    return (stereoMode != DEPTH_MODE_OFF && stereoLive && !depthGaveUp
            && stereoSourceFormat == STEREO_SRC_AUTO) ? 1 : 0;
}

int paletteNeedStereoSwapchain(int localMedia, int stereoMode, int stereoLive,
                               int stereoSourceFormat, int depthGaveUp) {
    if (!localMedia) {
        return stereoMode != DEPTH_MODE_OFF ? 1 : 0;
    }
    if (stereoSourceFormat == STEREO_SRC_SBS || stereoSourceFormat == STEREO_SRC_OU) {
        return 1;
    }
    return (stereoMode != DEPTH_MODE_OFF && stereoLive && !depthGaveUp) ? 1 : 0;
}

int paletteMergeHaptic(int currentKind, int incomingKind) {
    if (incomingKind <= PALETTE_HAPTIC_NONE) {
        return currentKind;
    }
    return incomingKind > currentKind ? incomingKind : currentKind;
}

float paletteVideoHeightOverWidth(int videoW, int videoH, int stereoSourceFormat) {
    if (videoW <= 0 || videoH <= 0) {
        return 9.0f / 16.0f;
    }
    float rawRatio = (float)videoW / (float)videoH;
    if (stereoSourceFormat == STEREO_SRC_SBS && rawRatio >= PALETTE_FULL_SBS_MIN_RATIO) {
        return (2.0f * (float)videoH) / (float)videoW;
    }
    if (stereoSourceFormat == STEREO_SRC_OU && rawRatio <= PALETTE_FULL_OU_MAX_RATIO) {
        return (0.5f * (float)videoH) / (float)videoW;
    }
    return (float)videoH / (float)videoW;
}

void paletteSuppressLatchReset(PaletteSuppressLatch* latch) {
    memset(latch, 0, sizeof(*latch));
}

void paletteStepSuppressLatch(int suppressed, int rawTriggerDown, float rawTriggerVal,
                              float rawGripVal, float pressOff,
                              int* triggerLatch, int* gripLatch,
                              int* outBlockTrigger, int* outBlockGrip) {
    int trigHeld = rawTriggerDown || rawTriggerVal > pressOff;
    int gripHeld = rawGripVal > pressOff;
    if (suppressed) {
        *triggerLatch = trigHeld ? 1 : 0;
        *gripLatch = gripHeld ? 1 : 0;
        *outBlockTrigger = 1;
        *outBlockGrip = 1;
        return;
    }
    if (*triggerLatch) {
        if (trigHeld) {
            *outBlockTrigger = 1;
        }
        else {
            *triggerLatch = 0;
            *outBlockTrigger = 0;
        }
    }
    else {
        *outBlockTrigger = 0;
    }
    if (*gripLatch) {
        if (gripHeld) {
            *outBlockGrip = 1;
        }
        else {
            *gripLatch = 0;
            *outBlockGrip = 0;
        }
    }
    else {
        *outBlockGrip = 0;
    }
}

void paletteGluePreStep(PaletteState* st, PaletteSuppressLatch* latch,
                        const PalettePreGlueIn* in, PalettePreGlueOut* out) {
    memset(out, 0, sizeof(*out));
    Vec3 head = { in->headPose.position.x, in->headPose.position.y, in->headPose.position.z };

    if (in->closeRequested) {
        paletteClose(st, in->nowNs);
        out->clearActiveDrags = 1;
    }
    if (in->barOpenRequested) {
        paletteOpenFromBar(st, in->headPose, in->handTracking, in->nowNs);
        out->hapticKind = PALETTE_HAPTIC_TICK;
    }
    if (in->pinToggleRequested) {
        paletteTogglePin(st, head, in->handTracking, in->nowNs);
        out->hapticKind = PALETTE_HAPTIC_TICK;
    }

    paletteStepPoseAndFade(st, in->headPose, in->gripPose, in->gripValid, in->handTracking,
                           in->screenAdjustActive, in->dt, in->nowNs, in->colorScaleSupported,
                           &out->renderPose);

    for (int h = 0; h < 2; h++) {
        int suppressed = paletteShouldSuppressHand(st, h, in->handTracking);
        out->suppressRay[h] = suppressed;
        paletteStepSuppressLatch(suppressed, in->rawTriggerDown[h], in->rawTriggerVal[h],
                                 in->rawGripVal[h], in->pressOff,
                                 &latch->triggerWaitRelease[h], &latch->gripWaitRelease[h],
                                 &out->blockTrigger[h], &out->blockGrip[h]);
    }
}

void paletteGluePostStep(PaletteState* st, const PalettePostGlueIn* in, PalettePostGlueOut* out) {
    memset(out, 0, sizeof(*out));
    out->hit.zone = PALETTE_HIT_NONE;
    out->hit.index = -1;
    out->hit.px = -1.0f;
    out->hit.py = -1.0f;
    out->claimedHand = -1;
    out->swallowHand = -1;
    out->nextDragHand = in->dragHand;
    out->nextSliderDragging = in->sliderDragging;
    out->nextSliderHand = in->sliderHand;
    out->updatedPose = in->renderPose.pose;

    Vec3 zero = { 0.0f, 0.0f, 0.0f };
    if (!st->open) {
        out->nextSliderDragging = 0;
        out->nextSliderHand = -1;
        out->nextDragHand = -1;
        paletteHandRowDragStep(st, 0, -1, zero, zero);
        return;
    }

    Vec3 head = { in->headPose.position.x, in->headPose.position.y, in->headPose.position.z };

    // 1. Active reposition drag continuation (controller grip or hand-tracking tab bar)
    if (st->dragging) {
        int dh = in->dragHand;
        int held = dh >= 0 && dh < 3 && in->aimValid[dh]
                && (st->dragByGrip ? (dh < 2 && in->grabVal[dh] > in->pressOff)
                                   : in->triggerDown[dh]);
        if (held) {
            paletteStepDrag(st, in->aimPoses[dh], head);
            out->updatedPose = st->currentPose;
            out->poseUpdated = 1;
            out->claimed = 1;
            out->claimedHand = dh;
            out->claimedHover = HOVER_PALETTE;
            out->claimedU = 0.5f;
            out->claimedV = 0.5f;
            out->swallowHand = dh;
            return;
        }
        paletteEndDrag(st);
        out->nextDragHand = -1;
    }

    // 2. Active controller slider drag continuation (even if ray drifts past edge)
    if (in->sliderDragging && !in->handTracking) {
        int sh = in->sliderHand;
        float u = 0.0f, v = 0.0f;
        if (sh >= 0 && sh < 2 && in->triggerDown[sh]
                && in->renderPose.inputAllowed && in->aimValid[sh]
                && screenProject(in->aimPoses[sh], in->renderPose.pose,
                                 PALETTE_W_M, PALETTE_H_M, 0.0f, 0, &u, &v)) {
            float cu = clampf(u, 0.0f, 1.0f);
            float cv = clampf(v, 0.0f, 1.0f);
            out->hit.zone = PALETTE_HIT_ROW;
            out->hit.index = st->hoverIndex;
            out->hit.rowAdjustable = 1;
            out->hit.u = cu;
            out->hit.v = cv;
            out->hit.px = cu * (float)PALETTE_TEX_W;
            out->hit.py = cv * (float)PALETTE_TEX_H;
            out->clickState = 2;
            out->claimed = 1;
            out->claimedHand = sh;
            out->claimedHover = HOVER_PALETTE;
            out->claimedU = cu;
            out->claimedV = cv;
            return;
        }
        out->nextSliderDragging = 0;
        out->nextSliderHand = -1;
    }

    if (!in->renderPose.inputAllowed || in->screenAdjustActive || in->grabMode != 0) {
        paletteHandRowDragStep(st, 0, -1, zero, zero);
        return;
    }

    // 3. Candidate sources that can point at the palette
    int cands[3];
    int candCount = 0;
    if (in->handTracking) {
        cands[candCount++] = 1; // HAND_RIGHT
        cands[candCount++] = 0; // HAND_LEFT
        cands[candCount++] = 2; // SRC_GAZE
    }
    else {
        int opH = paletteOpHand(st);
        int palH = paletteMountHand(st);
        cands[candCount++] = opH;
        if (st->pinned) {
            cands[candCount++] = palH;
        }
    }

    int hitAnyInterior = 0;
    for (int i = 0; i < candCount; i++) {
        int src = cands[i];
        if (!in->canPoint[src] || !in->aimValid[src]) {
            continue;
        }
        PaletteHit hit;
        paletteRayHit(st, in->renderPose.pose, in->aimPoses[src], in->activeRowMask, &hit);
        if (hit.zone == PALETTE_HIT_NONE) {
            continue;
        }
        out->hit = hit;
        out->claimed = 1;
        out->claimedHand = src;
        out->claimedU = hit.u;
        out->claimedV = hit.v;
        if (hit.zone == PALETTE_HIT_DEADZONE) {
            out->claimedHover = HOVER_PALETTE_DEADZONE;
            if (in->triggerEdge[src]) {
                out->swallowHand = src;
            }
            break;
        }

        hitAnyInterior = 1;
        out->claimedHover = HOVER_PALETTE;

        // Controller grip on palette: detach from hand & drag reposition (§6.3)
        int gripEdge = !in->handTracking && src < 2
                && in->grabVal[src] > in->pressOn && !in->grabDown[src];
        if (gripEdge) {
            paletteStartDrag(st, in->aimPoses[src], head, 1, in->nowNs);
            out->nextDragHand = src;
            out->hapticKind = PALETTE_HAPTIC_TICK;
            out->clickSound = 1;
            break;
        }

        // Hand-tracking pinch on top blank bar: drag reposition
        if (in->handTracking && hit.zone == PALETTE_HIT_DRAGBAR && in->triggerEdge[src]) {
            paletteStartDrag(st, in->aimPoses[src], head, 0, in->nowNs);
            out->nextDragHand = src;
            out->swallowHand = src;
            break;
        }

        // Pin / Unpin (controller) or Close x (hand tracking)
        if (hit.zone == PALETTE_HIT_PIN && in->triggerEdge[src]) {
            paletteTogglePin(st, head, in->handTracking, in->nowNs);
            out->clickSound = 1;
            out->hapticKind = PALETTE_HAPTIC_TICK;
            out->swallowHand = src;
            break;
        }

        // Interior click (tabs, chips, sliders, steppers, reset buttons, more/back)
        if (in->triggerEdge[src]) {
            out->clickState = 1;
            out->swallowHand = src;
            if (!in->handTracking && hit.zone == PALETTE_HIT_ROW && src < 2) {
                out->nextSliderDragging = 1;
                out->nextSliderHand = src;
            }
        }

        // Hand-tracking horizontal row value drag: 8 mm = 1 discrete step (§6.3)
        if (in->handTracking && hit.zone == PALETTE_HIT_ROW && hit.rowAdjustable && src < 2) {
            Vec3 rxLocal = { 1.0f, 0.0f, 0.0f };
            Vec3 paletteRight = quatRotate(in->renderPose.pose.orientation, rxLocal);
            out->rowStep = paletteHandRowDragStep(st, in->triggerDown[src], hit.index,
                                                  in->handDragPos[src], paletteRight);
        }
        else {
            paletteHandRowDragStep(st, 0, -1, zero, zero);
        }
        break;
    }
    if (!hitAnyInterior) {
        paletteHandRowDragStep(st, 0, -1, zero, zero);
    }
}

void paletteGlueMirrorSticks(const PaletteState* st, const int profileIsController[2],
                             float leftX, float leftY, int leftStickClick,
                             float rightX, float rightY, int rightStickClick,
                             float* outOpX, float* outOpY, int* outOpStickClick,
                             float* outPalX, float* outPalY) {
    int opH = st->open ? paletteOpHand(st) : 1;
    int palH = st->open ? paletteMountHand(st) : 0;
    float xs[2] = { leftX, rightX };
    float ys[2] = { leftY, rightY };
    int clicks[2] = { leftStickClick, rightStickClick };

    if (profileIsController[opH]) {
        *outOpX = xs[opH];
        *outOpY = ys[opH];
        *outOpStickClick = clicks[opH];
    }
    else {
        *outOpX = 0.0f;
        *outOpY = 0.0f;
        *outOpStickClick = 0;
    }
    if (profileIsController[palH]) {
        *outPalX = xs[palH];
        *outPalY = ys[palH];
    }
    else {
        *outPalX = 0.0f;
        *outPalY = 0.0f;
    }
}

int paletteGlueApplyCloseOnB(PaletteState* st, int isClosePaletteB, int64_t nowNs,
                             int* outClearDrags) {
    if (outClearDrags != NULL) {
        *outClearDrags = 0;
    }
    if (!isClosePaletteB || !st->open) {
        return 0;
    }
    paletteClose(st, nowNs);
    if (outClearDrags != NULL) {
        *outClearDrags = 1;
    }
    return 1;
}
