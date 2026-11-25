// Screen manipulation, continuous curvature geometry, and local-playback input
// decision state machine (ux-spec.md §6.4, §6.5, §6.12).
#include "xr_screen_ctrl.h"

#include <math.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

const float SCREEN_FOV_DETENTS[SCREEN_FOV_DETENT_COUNT] = { 40.0f, 50.0f, 62.0f, 80.0f };
const float SCREEN_DIST_DETENTS[SCREEN_DIST_DETENT_COUNT] = { 2.0f, 3.0f, 5.0f };

static float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

float screenClampFov(float fovDeg) {
    return clampf(fovDeg, SCREEN_FOV_MIN_DEG, SCREEN_FOV_MAX_DEG);
}

float screenClampDist(float distM) {
    return clampf(distM, SCREEN_DIST_MIN_M, SCREEN_DIST_MAX_M);
}

float screenFlatWidth(float fovDeg, float distM) {
    float fov = clampf(fovDeg, 1.0f, 170.0f);
    float d = distM > 0.05f ? distM : 0.05f;
    float halfRad = fov * (float)M_PI / 360.0f;
    return 2.0f * d * tanf(halfRad);
}

float screenFovFromFlatWidth(float flatW, float distM) {
    if (flatW <= 1e-4f || distM <= 1e-4f) {
        return SCREEN_FOV_DEFAULT_DEG;
    }
    return 2.0f * atan2f(flatW * 0.5f, distM) * 180.0f / (float)M_PI;
}

int screenDiagonalInches(float fovDeg, float distM) {
    float flatW = screenFlatWidth(fovDeg, distM);
    float diagM = flatW / SCREEN_DIAG_WIDTH_RATIO;
    return (int)roundf(diagM / SCREEN_METRES_PER_INCH);
}

int screenSizePresetIndex(float fovDeg) {
    for (int i = 0; i < SCREEN_FOV_DETENT_COUNT; i++) {
        if (fabsf(fovDeg - SCREEN_FOV_DETENTS[i]) <= 0.55f) {
            return i;
        }
    }
    return SCREEN_PRESET_NONE;
}

float screenSizeNorm(float fovDeg) {
    return clampf((fovDeg - SCREEN_FOV_MIN_DEG) / (SCREEN_FOV_MAX_DEG - SCREEN_FOV_MIN_DEG),
                  0.0f, 1.0f);
}

float screenDistNorm(float distM) {
    float d = screenClampDist(distM);
    return clampf(logf(d / SCREEN_DIST_MIN_M) / logf(SCREEN_DIST_MAX_M / SCREEN_DIST_MIN_M),
                  0.0f, 1.0f);
}

float screenQuantizeCurve(float curve) {
    float c = clampf(curve, 0.0f, 1.0f);
    int pct = (int)roundf(c * 20.0f) * 5;
    return pct / 100.0f;
}

float screenCurvatureRadius(float distM, float curve) {
    if (curve <= SCREEN_CURVE_EPS) {
        return 0.0f;
    }
    float c = clampf(curve, SCREEN_CURVE_EPS, 1.0f);
    float d = distM > 0.05f ? distM : 0.05f;
    return d / c;
}

float screenCurvedArcWidth(float fovDeg, float distM, float curve) {
    float fov = clampf(fovDeg, 1.0f, 170.0f);
    float d = distM > 0.05f ? distM : 0.05f;
    if (curve <= SCREEN_CURVE_EPS) {
        return screenFlatWidth(fov, d);
    }
    float c = clampf(curve, SCREEN_CURVE_EPS, 1.0f);
    float R = d / c;
    float alpha = fov * (float)M_PI / 360.0f;
    float sinA = sinf(alpha);
    float cosA = cosf(alpha);
    float k = 1.0f - c;
    float disc = 1.0f - k * k * sinA * sinA;
    if (disc < 0.0f) {
        disc = 0.0f;
    }
    float sOverR = sqrtf(disc) - k * cosA;
    float beta = atan2f(sOverR * sinA, k + sOverR * cosA);
    return 2.0f * R * beta;
}

float screenFovFromPlacement(float width, float distM, float radius, int curved) {
    if (width <= 1e-4f || distM <= 1e-4f) {
        return SCREEN_FOV_DEFAULT_DEG;
    }
    if (!curved || radius <= 1e-4f) {
        return screenFovFromFlatWidth(width, distM);
    }
    float beta = cylinderAngle(width, radius) * 0.5f;
    float x = radius * sinf(beta);
    float zFromViewer = distM - radius * (1.0f - cosf(beta));
    return 2.0f * atan2f(x, zFromViewer) * 180.0f / (float)M_PI;
}

static XrPosef localOffsetPose(XrPosef base, Vec3 local) {
    Vec3 off = quatRotate(base.orientation, local);
    XrPosef out;
    out.orientation = base.orientation;
    out.position.x = base.position.x + off.x;
    out.position.y = base.position.y + off.y;
    out.position.z = base.position.z + off.z;
    return out;
}

int screenCurvedStrip(XrPosef screenPose, float screenWidth, float screenHeight,
                      float screenRadius, float marginFrac, float texAspect,
                      float proudM, CurvedStrip* out) {
    if (out == NULL || screenWidth <= 1e-4f || screenHeight <= 1e-4f
            || screenRadius <= 1e-4f || texAspect <= 1e-4f) {
        return 0;
    }
    float fit = cylinderFit(screenWidth, screenRadius);
    float drawnW = screenWidth * fit;
    float drawnH = screenHeight * fit;
    float videoAngle = cylinderAngle(screenWidth, screenRadius);
    float stripAngle = (1.0f - 2.0f * marginFrac) * videoAngle;
    if (stripAngle > CYLINDER_MAX_ANGLE) {
        stripAngle = CYLINDER_MAX_ANGLE;
    }
    if (stripAngle <= 1e-4f) {
        return 0;
    }
    float r = screenRadius - proudM;
    if (r < 0.05f) {
        r = 0.05f;
    }
    float stripH = (r * stripAngle) / texAspect;
    float yCenter = -drawnH * 0.5f + stripH * 0.5f + drawnW * marginFrac;
    Vec3 axisLocal = { 0.0f, yCenter, screenRadius };
    out->axisPose = localOffsetPose(screenPose, axisLocal);
    out->radius = r;
    out->centralAngle = stripAngle;
    out->aspectRatio = texAspect;
    return 1;
}

float screenCurvedChordZ(float radius, float halfAngle) {
    if (radius <= 1e-4f) {
        return 0.0f;
    }
    float a = clampf(fabsf(halfAngle), 0.0f, (float)M_PI * 0.49f);
    return radius * (1.0f - cosf(a));
}

int screenTransportBarHit(float u, float v, float videoAspect) {
    if (videoAspect <= 1e-4f) {
        return 0;
    }
    float margin = TRANSPORT_MARGIN_FRAC;
    float stripH = (1.0f - 2.0f * margin)
            * ((float)TRANSPORT_HEIGHT / (float)TRANSPORT_WIDTH) * videoAspect;
    float bottom = 1.0f - margin * videoAspect;
    float top = bottom - stripH;
    float slack = stripH * 0.35f;
    return u >= margin && u <= 1.0f - margin && v >= top - slack && v <= bottom + slack;
}

static XrQuaternionf orientFromYawPitchRoll(float yaw, float pitch, float roll) {
    Vec3 up = { 0.0f, 1.0f, 0.0f };
    Vec3 right = { 1.0f, 0.0f, 0.0f };
    Vec3 fwd = { 0.0f, 0.0f, 1.0f };
    XrQuaternionf q = quatMul(axisAngleQuat(up, yaw), axisAngleQuat(right, -pitch));
    return quatNorm(quatMul(q, axisAngleQuat(fwd, roll)));
}

XrPosef screenSphereDrag(XrPosef grabScreen, XrPosef grabAim, XrPosef curAim,
                         Vec3 headPos, float grabPitch, float grabRoll) {
    Vec3 offset = {
        grabScreen.position.x - headPos.x,
        grabScreen.position.y - headPos.y,
        grabScreen.position.z - headPos.z
    };
    float dist = sqrtf(vecDot(offset, offset));
    if (dist < 0.05f) {
        return grabScreen;
    }
    XrQuaternionf turn = quatMul(curAim.orientation, quatConj(grabAim.orientation));
    Vec3 rotated = quatRotate(turn, offset);
    float rLen = sqrtf(vecDot(rotated, rotated));
    if (rLen > 1e-5f) {
        float s = dist / rLen;
        rotated.x *= s;
        rotated.y *= s;
        rotated.z *= s;
    }
    XrPosef next = grabScreen;
    next.position.x = headPos.x + rotated.x;
    next.position.y = headPos.y + rotated.y;
    next.position.z = headPos.z + rotated.z;

    float hx = headPos.x - next.position.x;
    float hz = headPos.z - next.position.z;
    if (hx * hx + hz * hz > 0.0025f) {
        next.orientation = orientFromYawPitchRoll(atan2f(hx, hz), grabPitch, grabRoll);
    }
    return next;
}

int screenLayerBudgetCount(const LayerBudgetScene* s) {
    if (s == NULL) {
        return 0;
    }
    int count = 0;
    // Environment: room projection layer (or dimmer) + optional ambilight glow
    if (s->inRoom) {
        count += 1;
    }
    if (s->hasGlow) {
        count += 1;
    }
    // Video picture: 2 stereo eyes or 1 mono layer
    count += s->stereo ? 2 : 1;
    // Danmaku: 2 layers (L/R stereo parallax) when active
    if (s->hasDanmaku) {
        count += 2;
    }
    // Subtitle: 1 shared layer
    if (s->hasSubtitle) {
        count += 1;
    }
    // Control bar (hidden during screen adjust)
    if (s->hasControlBar && !s->hasReadout) {
        count += 1;
    }
    // Palette
    if (s->hasPalette) {
        count += 1;
    }
    // Readout & Toast share a single slot (1 layer at most)
    if (s->hasReadout || s->hasToast) {
        count += 1;
    }
    // Card (error / cast / next-episode)
    if (s->hasCard) {
        count += 1;
    }
    // Pointer ray + cursor dot
    if (s->hasPointer) {
        count += 2;
    }
    // Controller model projection layer
    if (s->hasController) {
        count += 1;
    }
    return count;
}

int screenLayerBudgetOk(int count, int maxLimit) {
    return count >= 0 && count <= maxLimit;
}

// ============================================================================
// Pure local-playback input state machine (§6.4, §6.12)
// ============================================================================

void screenInputInit(ScreenInputState* st) {
    memset(st, 0, sizeof(*st));
    st->adjustFovDeg = SCREEN_FOV_DEFAULT_DEG;
    st->adjustDistM = SCREEN_DIST_DEFAULT_M;
    st->preAdjustFovDeg = SCREEN_FOV_DEFAULT_DEG;
    st->preAdjustDistM = SCREEN_DIST_DEFAULT_M;
}

void screenInputReset(ScreenInputState* st) {
    float fov = st->adjustFovDeg;
    float dist = st->adjustDistM;
    memset(st, 0, sizeof(*st));
    st->adjustFovDeg = fov > 0.0f ? fov : SCREEN_FOV_DEFAULT_DEG;
    st->adjustDistM = dist > 0.0f ? dist : SCREEN_DIST_DEFAULT_M;
    st->preAdjustFovDeg = st->adjustFovDeg;
    st->preAdjustDistM = st->adjustDistM;
}

static float angleBetweenDeg(Vec3 a, Vec3 b) {
    float la = sqrtf(vecDot(a, a));
    float lb = sqrtf(vecDot(b, b));
    if (la < 1e-5f || lb < 1e-5f) {
        return 0.0f;
    }
    float d = clampf(vecDot(a, b) / (la * lb), -1.0f, 1.0f);
    return acosf(d) * 180.0f / (float)M_PI;
}

static float snapFovToDetent(float fovDeg) {
    for (int i = 0; i < SCREEN_FOV_DETENT_COUNT; i++) {
        if (fabsf(fovDeg - SCREEN_FOV_DETENTS[i]) <= SCREEN_FOV_SNAP_DEG) {
            return SCREEN_FOV_DETENTS[i];
        }
    }
    return screenClampFov(fovDeg);
}

static float snapDistToDetent(float distM) {
    for (int i = 0; i < SCREEN_DIST_DETENT_COUNT; i++) {
        float d = SCREEN_DIST_DETENTS[i];
        if (fabsf(distM - d) / d <= SCREEN_DIST_SNAP_FRAC) {
            return d;
        }
    }
    return screenClampDist(distM);
}

static void startAdjust(ScreenInputState* st, const ScreenInputFrame* in,
                        int byGrip, int byHand, ScreenInputResult* out) {
    st->adjustActive = 1;
    st->adjustByGrip = byGrip;
    st->adjustByHand = byHand;
    st->adjustAxis = ADJUST_AXIS_NONE;
    st->adjustFovDeg = screenClampFov(in->curFovDeg);
    st->adjustDistM = screenClampDist(in->curDistM);
    st->preAdjustFovDeg = st->adjustFovDeg;
    st->preAdjustDistM = st->adjustDistM;
    st->detentHoldUntilNs = 0;
    st->detentHoldAxis = ADJUST_AXIS_NONE;
    st->detentHoldDir = 0;
    st->hasLastDetent = 0;
    st->limitHitAxis = ADJUST_AXIS_NONE;
    st->limitHitDir = 0;
    st->stickXIntent = SCREEN_STICK_INTENT_NONE;
    st->stickXDir = 0;
    if (byHand) {
        st->handOrigin = in->handPos;
        st->handAxisOriginFov = st->adjustFovDeg;
        st->handAxisOriginDist = st->adjustDistM;
    }
    out->adjustStarted = 1;
}

static void finishAdjustSave(ScreenInputState* st, ScreenInputResult* out) {
    st->adjustFovDeg = snapFovToDetent(st->adjustFovDeg);
    st->adjustDistM = snapDistToDetent(st->adjustDistM);
    st->adjustActive = 0;
    st->adjustByGrip = 0;
    st->adjustByHand = 0;
    st->adjustAxis = ADJUST_AXIS_NONE;
    st->detentHoldUntilNs = 0;
    st->hasLastDetent = 0;
    st->limitHitAxis = ADJUST_AXIS_NONE;
    st->limitHitDir = 0;
    out->adjustSaved = 1;
}

static void cancelAdjustUndo(ScreenInputState* st, ScreenInputResult* out) {
    st->adjustFovDeg = st->preAdjustFovDeg;
    st->adjustDistM = st->preAdjustDistM;
    st->adjustActive = 0;
    st->adjustByGrip = 0;
    st->adjustByHand = 0;
    st->adjustAxis = ADJUST_AXIS_NONE;
    st->detentHoldUntilNs = 0;
    st->hasLastDetent = 0;
    st->limitHitAxis = ADJUST_AXIS_NONE;
    st->limitHitDir = 0;
    out->adjustUndone = 1;
}

// Checks whether stepping from prev -> next along axis crossed a detent.
// Returns 1 if a detent dwell was triggered (and writes *clampedNext = detent).
static int checkDetentCross(ScreenInputState* st, int axis, float prev, float next,
                            int dir, int64_t nowNs, int allowHaptic,
                            float* clampedNext, ScreenInputResult* out) {
    const float* detents = axis == ADJUST_AXIS_SIZE ? SCREEN_FOV_DETENTS : SCREEN_DIST_DETENTS;
    int count = axis == ADJUST_AXIS_SIZE ? SCREEN_FOV_DETENT_COUNT : SCREEN_DIST_DETENT_COUNT;
    float clearEps = axis == ADJUST_AXIS_SIZE ? 0.4f : 0.04f;

    if (st->hasLastDetent && st->lastDetentAxis == axis
            && fabsf(prev - st->lastDetentValue) > clearEps) {
        st->hasLastDetent = 0;
    }

    float crossed = 0.0f;
    int found = 0;
    for (int i = 0; i < count; i++) {
        float d = detents[i];
        if (st->hasLastDetent && st->lastDetentAxis == axis
                && fabsf(d - st->lastDetentValue) < 1e-4f) {
            continue;
        }
        if ((dir > 0 && prev < d - 1e-4f && next >= d)
                || (dir < 0 && prev > d + 1e-4f && next <= d)) {
            if (!found || (dir > 0 ? d < crossed : d > crossed)) {
                crossed = d;
                found = 1;
            }
        }
    }
    if (!found) {
        return 0;
    }
    *clampedNext = crossed;
    st->detentHoldUntilNs = nowNs + SCREEN_DETENT_HOLD_NS;
    st->detentHoldAxis = axis;
    st->detentHoldValue = crossed;
    st->detentHoldDir = dir;
    st->hasLastDetent = 1;
    st->lastDetentAxis = axis;
    st->lastDetentValue = crossed;
    if (allowHaptic && out->hapticEvent == SCREEN_HAPTIC_NONE) {
        out->hapticEvent = SCREEN_HAPTIC_DETENT;
    }
    return 1;
}

static void stepStickAdjust(ScreenInputState* st, const ScreenInputFrame* in,
                            ScreenInputResult* out) {
    float sx = in->rStickX;
    float sy = in->rStickY;
    float ax = fabsf(sx);
    float ay = fabsf(sy);
    float stickMax = ax > ay ? ax : ay;

    // Rule 7: returning to deadzone unlocks the axis and clears detent/limit latches
    if (stickMax <= SCREEN_STICK_DEADZONE) {
        st->adjustAxis = ADJUST_AXIS_NONE;
        st->detentHoldUntilNs = 0;
        st->detentHoldDir = 0;
        st->limitHitAxis = ADJUST_AXIS_NONE;
        st->limitHitDir = 0;
        return;
    }

    if (st->adjustAxis == ADJUST_AXIS_NONE) {
        if (stickMax <= SCREEN_STICK_ENGAGE) {
            return;
        }
        st->adjustAxis = ax >= ay ? ADJUST_AXIS_SIZE : ADJUST_AXIS_DIST;
        st->detentHoldUntilNs = 0;
        st->limitHitAxis = ADJUST_AXIS_NONE;
        st->limitHitDir = 0;
    }

    float raw = st->adjustAxis == ADJUST_AXIS_SIZE ? sx : sy;
    float magRaw = fabsf(raw);
    if (magRaw <= SCREEN_STICK_DEADZONE) {
        // Still deflected on the non-locked axis: ignore non-locked axis
        return;
    }
    int dir = raw > 0.0f ? 1 : -1;
    float normMag = clampf((magRaw - SCREEN_STICK_DEADZONE) / (1.0f - SCREEN_STICK_DEADZONE),
                           0.0f, 1.0f);

    // Detent 300 ms dwell pause (breaks immediately if stick reverses)
    if (st->detentHoldUntilNs != 0 && st->detentHoldAxis == st->adjustAxis) {
        if (dir != st->detentHoldDir) {
            st->detentHoldUntilNs = 0;
            st->hasLastDetent = 0;
        }
        else if (in->nowNs < st->detentHoldUntilNs) {
            if (st->adjustAxis == ADJUST_AXIS_SIZE) {
                st->adjustFovDeg = st->detentHoldValue;
            }
            else {
                st->adjustDistM = st->detentHoldValue;
            }
            return;
        }
        else {
            st->detentHoldUntilNs = 0;
        }
    }

    float dt = in->dt > 0.0f ? in->dt : 0.0f;
    if (st->adjustAxis == ADJUST_AXIS_SIZE) {
        float prev = st->adjustFovDeg;
        float next = prev + (float)dir * normMag * SCREEN_FOV_SPEED_DEG_S * dt;
        float clamped = next;
        if (checkDetentCross(st, ADJUST_AXIS_SIZE, prev, next, dir, in->nowNs, 1, &clamped, out)) {
            st->adjustFovDeg = clamped;
            st->limitHitAxis = ADJUST_AXIS_NONE;
            st->limitHitDir = 0;
            return;
        }
        int hitMin = next <= SCREEN_FOV_MIN_DEG && dir < 0;
        int hitMax = next >= SCREEN_FOV_MAX_DEG && dir > 0;
        st->adjustFovDeg = screenClampFov(next);
        if (hitMin || hitMax) {
            if (st->limitHitAxis != ADJUST_AXIS_SIZE || st->limitHitDir != dir) {
                st->limitHitAxis = ADJUST_AXIS_SIZE;
                st->limitHitDir = dir;
                if (out->hapticEvent == SCREEN_HAPTIC_NONE) {
                    out->hapticEvent = SCREEN_HAPTIC_LIMIT;
                }
            }
        }
        else if (st->limitHitAxis == ADJUST_AXIS_SIZE) {
            st->limitHitAxis = ADJUST_AXIS_NONE;
            st->limitHitDir = 0;
        }
    }
    else {
        float prev = st->adjustDistM;
        float factor = powf(SCREEN_DIST_LOG_RATE, (float)dir * normMag * dt);
        float next = prev * factor;
        float clamped = next;
        if (checkDetentCross(st, ADJUST_AXIS_DIST, prev, next, dir, in->nowNs, 1, &clamped, out)) {
            st->adjustDistM = clamped;
            st->limitHitAxis = ADJUST_AXIS_NONE;
            st->limitHitDir = 0;
            return;
        }
        int hitMin = next <= SCREEN_DIST_MIN_M && dir < 0;
        int hitMax = next >= SCREEN_DIST_MAX_M && dir > 0;
        st->adjustDistM = screenClampDist(next);
        if (hitMin || hitMax) {
            if (st->limitHitAxis != ADJUST_AXIS_DIST || st->limitHitDir != dir) {
                st->limitHitAxis = ADJUST_AXIS_DIST;
                st->limitHitDir = dir;
                if (out->hapticEvent == SCREEN_HAPTIC_NONE) {
                    out->hapticEvent = SCREEN_HAPTIC_LIMIT;
                }
            }
        }
        else if (st->limitHitAxis == ADJUST_AXIS_DIST) {
            st->limitHitAxis = ADJUST_AXIS_NONE;
            st->limitHitDir = 0;
        }
    }
}

static void stepHandAdjust(ScreenInputState* st, const ScreenInputFrame* in,
                           ScreenInputResult* out) {
    Vec3 delta = vecSub(in->handPos, st->handOrigin);
    float dx = vecDot(delta, in->headRight);
    float dfwd = vecDot(delta, in->headForward);
    float adx = fabsf(dx);
    float adf = fabsf(dfwd);

    if (st->adjustAxis == ADJUST_AXIS_NONE) {
        if (adx < SCREEN_HAND_DEADZONE_M && adf < SCREEN_HAND_DEADZONE_M) {
            return;
        }
        st->adjustAxis = adx >= adf ? ADJUST_AXIS_SIZE : ADJUST_AXIS_DIST;
        st->handOrigin = in->handPos;
        st->handAxisOriginFov = st->adjustFovDeg;
        st->handAxisOriginDist = st->adjustDistM;
        st->detentHoldUntilNs = 0;
        return;
    }

    if (st->adjustAxis == ADJUST_AXIS_SIZE) {
        float prev = st->adjustFovDeg;
        float target = st->handAxisOriginFov + dx * SCREEN_HAND_FOV_DEG_PER_M;
        int dir = target > prev ? 1 : (target < prev ? -1 : 0);
        if (st->detentHoldUntilNs != 0 && st->detentHoldAxis == ADJUST_AXIS_SIZE) {
            if (dir != 0 && dir != st->detentHoldDir) {
                st->detentHoldUntilNs = 0;
                st->hasLastDetent = 0;
            }
            else if (in->nowNs < st->detentHoldUntilNs) {
                st->adjustFovDeg = st->detentHoldValue;
                return;
            }
            else {
                st->detentHoldUntilNs = 0;
                // Re-anchor smoothly so screen doesn't jump after 300 ms visual pause
                st->handOrigin = in->handPos;
                st->handAxisOriginFov = st->detentHoldValue;
                st->adjustFovDeg = st->detentHoldValue;
                return;
            }
        }
        float clamped = target;
        if (dir != 0 && checkDetentCross(st, ADJUST_AXIS_SIZE, prev, target, dir, in->nowNs,
                                         0, &clamped, out)) {
            st->adjustFovDeg = clamped;
            return;
        }
        st->adjustFovDeg = screenClampFov(target);
    }
    else {
        float prev = st->adjustDistM;
        float steps = dfwd / SCREEN_HAND_DIST_STEP_M;
        float target = st->handAxisOriginDist * powf(SCREEN_HAND_DIST_FACTOR, steps);
        int dir = target > prev ? 1 : (target < prev ? -1 : 0);
        if (st->detentHoldUntilNs != 0 && st->detentHoldAxis == ADJUST_AXIS_DIST) {
            if (dir != 0 && dir != st->detentHoldDir) {
                st->detentHoldUntilNs = 0;
                st->hasLastDetent = 0;
            }
            else if (in->nowNs < st->detentHoldUntilNs) {
                st->adjustDistM = st->detentHoldValue;
                return;
            }
            else {
                st->detentHoldUntilNs = 0;
                st->handOrigin = in->handPos;
                st->handAxisOriginDist = st->detentHoldValue;
                st->adjustDistM = st->detentHoldValue;
                return;
            }
        }
        float clamped = target;
        if (dir != 0 && checkDetentCross(st, ADJUST_AXIS_DIST, prev, target, dir, in->nowNs,
                                         0, &clamped, out)) {
            st->adjustDistM = clamped;
            return;
        }
        st->adjustDistM = screenClampDist(target);
    }
}

static int isControlTarget(int target) {
    return target == SCREEN_TARGET_CONTROL
            || target == SCREEN_TARGET_PALETTE_ROW
            || target == SCREEN_TARGET_PALETTE_OTHER
            || target == SCREEN_TARGET_CARD;
}

void screenInputStep(ScreenInputState* st, const ScreenInputFrame* in,
                     ScreenInputResult* out) {
    memset(out, 0, sizeof(*out));
    out->sizePresetIdx = SCREEN_PRESET_NONE;

    if (!st->adjustActive) {
        st->adjustFovDeg = screenClampFov(in->curFovDeg);
        st->adjustDistM = screenClampDist(in->curDistM);
    }

    int trigEdge = in->triggerDown && !st->prevTrigger;
    int trigRelease = !in->triggerDown && st->prevTrigger;
    int gripEdge = in->gripDown && !st->prevGrip;
    int gripRelease = !in->gripDown && st->prevGrip;
    int aEdge = in->btnA && !st->prevA;
    int bEdge = in->btnB && !st->prevB;
    int yEdge = in->btnY && !st->prevY;
    int menuEdge = in->btnMenu && !st->prevMenu;
    int rClickEdge = in->rStickClick && !st->prevRStickClick;

    float rx = in->rStickX;
    float ry = in->rStickY;
    float rStickMax = fabsf(rx) > fabsf(ry) ? fabsf(rx) : fabsf(ry);

    // 1. B button priority (Rule 10):
    //    adjust undo -> close card -> close palette -> double-B exit
    if (bEdge) {
        if (st->adjustActive) {
            cancelAdjustUndo(st, out);
            out->bAction = SCREEN_B_UNDO_ADJUST;
            if (in->triggerDown) {
                st->trigState = SCREEN_HOLD_CANCELLED;
            }
            if (in->gripDown) {
                st->gripState = SCREEN_GRIP_CANCELLED;
            }
            st->lastBExitPromptNs = 0;
        }
        else if (in->cardKind != SCREEN_CARD_NONE) {
            out->bAction = SCREEN_B_CLOSE_CARD;
            st->lastBExitPromptNs = 0;
        }
        else if (in->paletteOpen) {
            out->bAction = SCREEN_B_CLOSE_PALETTE;
            st->lastBExitPromptNs = 0;
        }
        else {
            if (st->lastBExitPromptNs != 0
                    && in->nowNs - st->lastBExitPromptNs <= SCREEN_B_EXIT_WINDOW_NS) {
                out->bAction = SCREEN_B_EXIT_CONFIRM;
                st->lastBExitPromptNs = 0;
            }
            else {
                out->bAction = SCREEN_B_EXIT_PROMPT;
                st->lastBExitPromptNs = in->nowNs;
            }
        }
    }

    // 2. Trigger / Pinch handling (Rules 1-6, 12, and hand-tracking pinch adjust)
    if (trigEdge) {
        if (isControlTarget(in->target)) {
            // Rule 1: trigger on any control / bar / palette / card never adjusts screen
            st->trigState = SCREEN_HOLD_CONTROL;
            out->clickControl = 1;
        }
        else if (in->target == SCREEN_TARGET_SCREEN) {
            st->trigState = SCREEN_HOLD_ON_SCREEN;
            st->trigDownNs = in->nowNs;
            st->trigStickLeftDz = rStickMax > SCREEN_STICK_DEADZONE;
            st->trigPrePushed = rStickMax > SCREEN_STICK_DEADZONE;
            st->trigHandMode = in->isHandTracking ? 1 : 0;
            st->trigStartDir = in->aimDir;
            st->trigMaxDriftDeg = 0.0f;
        }
        else {
            st->trigState = SCREEN_HOLD_CANCELLED;
        }
    }

    if (in->triggerDown && (st->trigState == SCREEN_HOLD_ON_SCREEN
                            || st->trigState == SCREEN_HOLD_HINTED)) {
        if (in->target != SCREEN_TARGET_SCREEN) {
            // Rule 6: ray left screen before adjust started -> cancel, no pause on release
            st->trigState = SCREEN_HOLD_CANCELLED;
        }
        else if (st->trigHandMode) {
            float drift = angleBetweenDeg(in->aimDir, st->trigStartDir);
            if (drift > st->trigMaxDriftDeg) {
                st->trigMaxDriftDeg = drift;
            }
            if (in->triggerAdjustEnabled && !in->inRoom && !st->adjustActive
                    && (in->nowNs - st->trigDownNs) >= SCREEN_CLICK_HOLD_NS) {
                if (st->trigMaxDriftDeg < SCREEN_HAND_DRIFT_MAX_DEG) {
                    st->trigState = SCREEN_HOLD_ADJUSTING;
                    startAdjust(st, in, 0, 1, out);
                }
                else {
                    st->trigState = SCREEN_HOLD_CANCELLED;
                }
            }
        }
        else {
            if (rStickMax > SCREEN_STICK_DEADZONE) {
                st->trigStickLeftDz = 1;
            }
            if (st->trigPrePushed && rStickMax <= SCREEN_STICK_DEADZONE) {
                // Rule 5: stick returned to deadzone; next push enters adjust
                st->trigPrePushed = 0;
            }
            if (in->triggerAdjustEnabled && !in->inRoom && !st->adjustActive
                    && !st->trigPrePushed) {
                if (rStickMax > SCREEN_STICK_ENGAGE) {
                    // Rule 3: enter adjust immediately regardless of 350 ms timer
                    st->trigState = SCREEN_HOLD_ADJUSTING;
                    startAdjust(st, in, 0, 0, out);
                }
                else if (st->trigState == SCREEN_HOLD_ON_SCREEN && !st->trigStickLeftDz
                         && (in->nowNs - st->trigDownNs) >= SCREEN_CLICK_HOLD_NS) {
                    // Rule 4: held >= 350 ms without stick push -> show hint toast + light haptic
                    st->trigState = SCREEN_HOLD_HINTED;
                    out->showHintToast = 1;
                    if (out->hapticEvent == SCREEN_HAPTIC_NONE) {
                        out->hapticEvent = SCREEN_HAPTIC_HINT;
                    }
                }
            }
        }
    }

    if (trigRelease) {
        if (st->trigState == SCREEN_HOLD_ADJUSTING && st->adjustActive && !st->adjustByGrip) {
            finishAdjustSave(st, out);
        }
        else if (st->trigState == SCREEN_HOLD_ON_SCREEN && in->target == SCREEN_TARGET_SCREEN) {
            if (!in->triggerAdjustEnabled || in->inRoom) {
                // Rule 12 / room: trigger on screen is click only
                out->playPause = 1;
            }
            else if ((in->nowNs - st->trigDownNs) < SCREEN_CLICK_HOLD_NS
                     && !st->trigStickLeftDz) {
                // Rule 2: release within 350 ms and stick stayed in deadzone
                out->playPause = 1;
            }
        }
        st->trigState = SCREEN_HOLD_IDLE;
    }

    // 3. Grip handling: sphere drag or grip + stick screen adjust (works even
    //    when triggerAdjustEnabled == 0 per Rule 12)
    if (gripEdge && !in->isHandTracking) {
        if (in->target == SCREEN_TARGET_SCREEN && !in->inRoom && !st->adjustActive) {
            st->gripState = SCREEN_GRIP_SPHERE;
            st->gripPrePushed = rStickMax > SCREEN_STICK_DEADZONE;
        }
        else {
            st->gripState = SCREEN_GRIP_CANCELLED;
        }
    }

    if (in->gripDown && st->gripState == SCREEN_GRIP_SPHERE) {
        if (st->gripPrePushed && rStickMax <= SCREEN_STICK_DEADZONE) {
            st->gripPrePushed = 0;
        }
        if (!st->gripPrePushed && !st->adjustActive && rStickMax > SCREEN_STICK_ENGAGE) {
            st->gripState = SCREEN_GRIP_ADJUSTING;
            startAdjust(st, in, 1, 0, out);
        }
    }

    if (gripRelease) {
        if (st->gripState == SCREEN_GRIP_ADJUSTING && st->adjustActive && st->adjustByGrip) {
            finishAdjustSave(st, out);
        }
        st->gripState = SCREEN_GRIP_IDLE;
    }

    // 4. Step active screen adjust (stick or hand-tracking)
    if (st->adjustActive) {
        if (st->adjustByHand) {
            stepHandAdjust(st, in, out);
        }
        else {
            stepStickAdjust(st, in, out);
        }
    }
    else {
        // 5. Non-adjust buttons (A, Y, Menu, Right stick click, Left stick, Right stick X)
        if (aEdge) {
            if (isControlTarget(in->target)) {
                out->clickControl = 1;
            }
            else if (in->cardKind == SCREEN_CARD_NONE) {
                out->playPause = 1;
            }
        }
        if (yEdge) {
            out->yToggleDanmaku = 1;
        }
        if (menuEdge) {
            out->menuTogglePalette = 1;
        }
        if (rClickEdge && in->paletteOpen && in->target == SCREEN_TARGET_PALETTE_ROW) {
            out->paletteRowReset = 1;
        }

        // Left stick: palette tabs (X) and page scroll (Y) only while palette is open
        if (in->paletteOpen) {
            float alx = fabsf(in->lStickX);
            float aly = fabsf(in->lStickY);
            if (alx <= SCREEN_STICK_DEADZONE) {
                st->lStickXLatched = 0;
            }
            else if (!st->lStickXLatched && alx > SCREEN_STICK_ENGAGE && alx >= aly) {
                st->lStickXLatched = 1;
                out->paletteTabStep = in->lStickX > 0.0f ? 1 : -1;
            }
            if (aly <= SCREEN_STICK_DEADZONE) {
                st->lStickYDir = 0;
            }
            else if (aly > SCREEN_STICK_ENGAGE && aly > alx) {
                int dir = in->lStickY > 0.0f ? 1 : -1;
                if (st->lStickYDir != dir) {
                    st->lStickYDir = dir;
                    st->lStickYNextNs = in->nowNs + SCREEN_SEEK_REPEAT_NS;
                    out->palettePageStep = dir;
                }
                else if (in->nowNs >= st->lStickYNextNs) {
                    st->lStickYNextNs = in->nowNs + SCREEN_SEEK_REPEAT_NS;
                    out->palettePageStep = dir;
                }
            }
        }
        else {
            st->lStickXLatched = 0;
            st->lStickYDir = 0;
        }

        // Right stick X outside adjust (Rule 8: intent locked until return to deadzone;
        // Rule 11: right stick Y has no action outside adjust)
        float arx = fabsf(rx);
        float ary = fabsf(ry);
        if (arx <= SCREEN_STICK_DEADZONE) {
            st->stickXIntent = SCREEN_STICK_INTENT_NONE;
            st->stickXDir = 0;
        }
        else if (st->stickXIntent == SCREEN_STICK_INTENT_NONE) {
            if (arx > SCREEN_STICK_ENGAGE && arx >= ary) {
                int dir = rx > 0.0f ? 1 : -1;
                st->stickXDir = dir;
                st->stickXStartNs = in->nowNs;
                st->stickXNextRepeatNs = in->nowNs + SCREEN_SEEK_REPEAT_NS;
                if (in->cardKind != SCREEN_CARD_NONE) {
                    st->stickXIntent = SCREEN_STICK_INTENT_NOOP;
                }
                else if (in->paletteOpen && in->target == SCREEN_TARGET_PALETTE_ROW) {
                    st->stickXIntent = SCREEN_STICK_INTENT_PALETTE_ROW;
                    out->paletteRowStep = dir;
                }
                else if (in->paletteOpen && in->target == SCREEN_TARGET_PALETTE_OTHER) {
                    st->stickXIntent = SCREEN_STICK_INTENT_NOOP;
                }
                else {
                    st->stickXIntent = SCREEN_STICK_INTENT_SEEK;
                    out->seekDeltaSec = dir * SCREEN_SEEK_STEP_SEC;
                }
            }
        }
        else if (st->stickXIntent == SCREEN_STICK_INTENT_PALETTE_ROW) {
            if (!in->paletteOpen || in->target != SCREEN_TARGET_PALETTE_ROW) {
                // Rule 8: sliding off row stops repeat without becoming seek
                st->stickXIntent = SCREEN_STICK_INTENT_NOOP;
            }
            else if (arx > SCREEN_STICK_ENGAGE && in->nowNs >= st->stickXNextRepeatNs) {
                int dir = rx > 0.0f ? 1 : -1;
                st->stickXNextRepeatNs = in->nowNs + SCREEN_SEEK_REPEAT_NS;
                out->paletteRowStep = dir;
            }
        }
        else if (st->stickXIntent == SCREEN_STICK_INTENT_SEEK) {
            if (arx > SCREEN_STICK_ENGAGE && in->nowNs >= st->stickXNextRepeatNs) {
                int dir = rx > 0.0f ? 1 : -1;
                int step = (in->nowNs - st->stickXStartNs) >= SCREEN_SEEK_FAST_HOLD_NS
                        ? SCREEN_SEEK_FAST_SEC : SCREEN_SEEK_STEP_SEC;
                st->stickXNextRepeatNs = in->nowNs + SCREEN_SEEK_REPEAT_NS;
                out->seekDeltaSec = dir * step;
            }
        }
    }

    // Populate outputs
    out->adjustActive = st->adjustActive;
    out->adjustAxis = st->adjustAxis;
    out->sphereDragActive = (!st->adjustActive && st->gripState == SCREEN_GRIP_SPHERE
                             && !st->gripPrePushed) ? 1 : 0;
    out->hideControlBar = st->adjustActive ? 1 : 0;
    out->paletteDimmed = (st->adjustActive && in->paletteOpen) ? 1 : 0;
    out->fovDeg = st->adjustFovDeg;
    out->distM = st->adjustDistM;
    out->diagonalInches = screenDiagonalInches(out->fovDeg, out->distM);
    out->sizePresetIdx = screenSizePresetIndex(out->fovDeg);

    // Save previous edges
    st->prevTrigger = in->triggerDown ? 1 : 0;
    st->prevGrip = in->gripDown ? 1 : 0;
    st->prevA = in->btnA ? 1 : 0;
    st->prevB = in->btnB ? 1 : 0;
    st->prevY = in->btnY ? 1 : 0;
    st->prevMenu = in->btnMenu ? 1 : 0;
    st->prevRStickClick = in->rStickClick ? 1 : 0;
}
