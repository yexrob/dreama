// Pure C shared overlay surface geometry, stereoscopic layered-float math,
// 64-bin depth 98th-percentile histogram, and §6.5 layer budget accounting.
// Shared directly by G4 (danmaku) and G3 (static subtitles).

#include "xr_overlay.h"
#include <math.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static float degToRad(float d) {
    return d * ((float)M_PI / 180.0f);
}

static float radToDeg(float r) {
    return r * (180.0f / (float)M_PI);
}

static XrPosef localOffset(XrPosef base, Vec3 local) {
    Vec3 off = quatRotate(base.orientation, local);
    XrPosef out;
    out.orientation = base.orientation;
    out.position.x = base.position.x + off.x;
    out.position.y = base.position.y + off.y;
    out.position.z = base.position.z + off.z;
    return out;
}

float overlayDisparityToDistM(float phiDeg, float screenDistM) {
    float d = screenDistM > 0.25f ? screenDistM : 0.25f;
    float phi = phiDeg > 0.0f ? phiDeg : 0.0f;
    float phiRad = degToRad(phi);
    float invDist = (phiRad / OVERLAY_IPD_M) + (1.0f / d);
    return invDist > 1e-4f ? (1.0f / invDist) : d;
}

float overlayMaxPhiAtDistDeg(float screenDistM) {
    if (screenDistM <= OVERLAY_MIN_EYE_DIST_M + 1e-4f) {
        return 0.0f;
    }
    float maxRad = OVERLAY_IPD_M * ((1.0f / OVERLAY_MIN_EYE_DIST_M) - (1.0f / screenDistM));
    float maxDeg = radToDeg(maxRad);
    if (maxDeg < 0.0f) {
        maxDeg = 0.0f;
    }
    if (maxDeg > OVERLAY_MAX_NEAR_PHI_DEG) {
        maxDeg = OVERLAY_MAX_NEAR_PHI_DEG;
    }
    return maxDeg;
}

void overlayComputeDepthLayers(float contentPhiDeg, int floatAuto,
                               int floatAutoOffsetSteps, int floatFixedSteps,
                               float layerSpreadDeg, float screenDistM,
                               OverlayDepthLayers* out) {
    if (out == NULL) {
        return;
    }
    memset(out, 0, sizeof(*out));
    float d = screenDistM > 0.25f ? screenDistM : 3.0f;
    float maxPhi = overlayMaxPhiAtDistDeg(d);
    out->maxPhiDeg = maxPhi;

    float farRaw = 0.0f;
    if (floatAuto) {
        float c = contentPhiDeg > 0.0f ? contentPhiDeg : 0.0f;
        int k = floatAutoOffsetSteps;
        if (k < -2) k = -2;
        if (k > 6) k = 6;
        farRaw = c + OVERLAY_AUTO_MARGIN_PHI_DEG + (float)k * OVERLAY_FLOAT_STEP_DEG;
        if (farRaw < 0.0f) {
            farRaw = 0.0f;
        }
    }
    else {
        int k = floatFixedSteps;
        if (k < 0) k = 0;
        if (k > 12) k = 12;
        farRaw = (float)k * OVERLAY_FLOAT_STEP_DEG;
    }

    float delta = layerSpreadDeg > 0.0f ? layerSpreadDeg : 0.0f;
    float farPhi = farRaw;
    // §6.10 ceiling order: compress layer spread delta FIRST, then compress far layer
    if (farPhi > maxPhi) {
        farPhi = maxPhi;
        delta = 0.0f;
    }
    else if (farPhi + delta > maxPhi) {
        delta = maxPhi - farPhi;
        if (delta < 0.0f) {
            delta = 0.0f;
        }
    }

    out->farPhiDeg = farPhi;
    out->midPhiDeg = farPhi + delta * 0.5f;
    out->nearPhiDeg = farPhi + delta;
    out->appliedDeltaDeg = delta;
    out->farDistM = overlayDisparityToDistM(out->farPhiDeg, d);
    out->midDistM = overlayDisparityToDistM(out->midPhiDeg, d);
    out->nearDistM = overlayDisparityToDistM(out->nearPhiDeg, d);
}

float overlayResolveContentPhi(int stereoActive, int stereoSourceFormat,
                               float measuredDepthPhiDeg) {
    if (stereoSourceFormat == STEREO_SRC_SBS || stereoSourceFormat == STEREO_SRC_OU) {
        return OVERLAY_NATIVE_3D_CONTENT_PHI_DEG;
    }
    if (!stereoActive) {
        return 0.0f;
    }
    return clampf(measuredDepthPhiDeg, 0.0f, OVERLAY_MAX_NEAR_PHI_DEG);
}

float overlayStepContentDepth(float curPhiDeg, float targetPhiDeg, float dt) {
    float cur = curPhiDeg > 0.0f ? curPhiDeg : 0.0f;
    float target = targetPhiDeg > 0.0f ? targetPhiDeg : 0.0f;
    if (target >= cur) {
        // Closer foreground: follow immediately so text never sinks behind objects
        return target;
    }
    if (dt <= 0.0f) {
        return cur;
    }
    float maxDrop = OVERLAY_DEPTH_RETREAT_DEG_PER_S * dt;
    float next = cur - maxDrop;
    return next < target ? target : next;
}

float overlayHistogram98Percentile(const float* depthNorm, int w, int h,
                                   float vTopFrac, float vBotFrac,
                                   float convergence, float separation,
                                   float screenFovDeg) {
    if (depthNorm == NULL || w <= 0 || h <= 0 || separation <= 0.0f || screenFovDeg <= 1.0f) {
        return 0.0f;
    }
    float topF = clampf(vTopFrac, 0.0f, 1.0f);
    float botF = clampf(vBotFrac, topF, 1.0f);
    // depthNorm is stored bottom-up (row 0 = bottom of picture, row h-1 = top).
    int yMin = (int)floorf((1.0f - botF) * (float)h);
    int yMax = (int)ceilf((1.0f - topF) * (float)h);
    if (yMin < 0) yMin = 0;
    if (yMax > h) yMax = h;
    if (yMin >= yMax) {
        return 0.0f;
    }

    int bins[OVERLAY_HIST_BINS];
    memset(bins, 0, sizeof(bins));
    int total = 0;
    for (int y = yMin; y < yMax; y++) {
        const float* row = depthNorm + (size_t)y * (size_t)w;
        for (int x = 0; x < w; x++) {
            float v = clampf(row[x], 0.0f, 1.0f);
            int b = (int)(v * (float)(OVERLAY_HIST_BINS - 1) + 0.5f);
            if (b < 0) b = 0;
            if (b >= OVERLAY_HIST_BINS) b = OVERLAY_HIST_BINS - 1;
            bins[b]++;
            total++;
        }
    }
    if (total <= 0) {
        return 0.0f;
    }
    int targetCount = (total * 98 + 99) / 100;
    int accum = 0;
    int p98Bin = 0;
    for (int b = 0; b < OVERLAY_HIST_BINS; b++) {
        accum += bins[b];
        if (accum >= targetCount) {
            p98Bin = b;
            break;
        }
    }
    float d98 = (float)p98Bin / (float)(OVERLAY_HIST_BINS - 1);
    float popOutNorm = d98 - convergence;
    if (popOutNorm <= 0.0f) {
        return 0.0f;
    }
    // Binocular crossed disparity across both eyes = 2 * separation * (d98 - convergence) * fovDeg
    float phiDeg = 2.0f * separation * popOutNorm * screenFovDeg;
    return clampf(phiDeg, 0.0f, OVERLAY_MAX_NEAR_PHI_DEG);
}

float overlayEyeShiftNormU(float phiDeg, float screenFovDeg, int eyeIndex) {
    if (screenFovDeg <= 1.0f || fabsf(phiDeg) < 1e-5f) {
        return 0.0f;
    }
    float halfShift = (phiDeg * 0.5f) / screenFovDeg;
    return eyeIndex == 0 ? halfShift : -halfShift;
}

int overlayComputeSurface(XrPosef screenPose, float screenWidth, float screenHeight,
                          float screenRadius, int screenCurved, int cylinderSupported,
                          int roomOn, int curveMode, float customCurvePct,
                          float screenDistM, float vTopFrac, float vBotFrac,
                          float popOutM, OverlaySurfaceGeom* out) {
    if (out == NULL || screenWidth <= 1e-4f || screenHeight <= 1e-4f) {
        return 0;
    }
    memset(out, 0, sizeof(*out));
    float topF = clampf(vTopFrac, 0.0f, 1.0f);
    float botF = clampf(vBotFrac, topF, 1.0f);
    float fracH = botF - topF;
    if (fracH <= 1e-4f) {
        return 0;
    }
    float pop = popOutM > 0.0f ? popOutM : 0.0f;
    float d = screenDistM > 0.25f ? screenDistM : 3.0f;

    // Effective drawn screen bounds (accounting for cylinder clamp if curved)
    float drawnW = screenWidth;
    float drawnH = screenHeight;
    float screenHalfAngle = 0.0f;
    float screenChordZ = 0.0f;
    if (screenCurved && screenRadius > 1e-4f) {
        float fit = cylinderFit(screenWidth, screenRadius);
        drawnW = screenWidth * fit;
        drawnH = screenHeight * fit;
        screenHalfAngle = cylinderAngle(screenWidth, screenRadius) * 0.5f;
        screenChordZ = screenCurvedChordZ(screenRadius, screenHalfAngle);
    }

    // Center Y of the vertical sub-region [topF, botF] in screen-local coordinates
    // (+Y is up, screen top = +drawnH/2, screen bottom = -drawnH/2)
    float regionCenterFracFromTop = 0.5f * (topF + botF);
    float yCenter = (0.5f - regionCenterFracFromTop) * drawnH;
    float regionH = drawnH * fracH;

    // Determine effective overlay curvature mode (§6.12):
    // In a 3D theater room or on headsets without cylinder extension, forced flat.
    int canCurve = cylinderSupported && !roomOn;
    float desiredCurve = 0.0f;
    int followCoaxial = 0;
    if (canCurve) {
        if (curveMode == OVERLAY_CURVE_FOLLOW) {
            followCoaxial = screenCurved && screenRadius > 1e-4f;
        }
        else if (curveMode == OVERLAY_CURVE_CUSTOM) {
            desiredCurve = clampf(customCurvePct / 100.0f, 0.0f, 1.0f);
        }
    }

    if (followCoaxial) {
        // Coaxial cylinder sharing the screen's axis, pulled forward by OVERLAY_PROUD_M + pop
        float angle = cylinderAngle(screenWidth, screenRadius);
        float r = screenRadius - (OVERLAY_PROUD_M + pop);
        if (r < 0.05f) {
            r = 0.05f;
        }
        // Keep angular height identical when pulling closer (scale vertical size with r / screenRadius)
        float scale = r / screenRadius;
        float scaledH = regionH * scale;
        float scaledY = yCenter * scale;
        float arcW = r * angle;
        if (scaledH <= 1e-4f || arcW <= 1e-4f) {
            return 0;
        }
        Vec3 axisLocal = { 0.0f, scaledY, screenRadius };
        out->useCylinder = 1;
        out->axisPose = localOffset(screenPose, axisLocal);
        out->radius = r;
        out->centralAngle = angle;
        out->aspectRatio = arcW / scaledH;
        return 1;
    }

    if (canCurve && desiredCurve > SCREEN_CURVE_EPS) {
        // Custom overlay curvature (§6.12): radius R_ov = D / c_ov, tangent to the
        // screen at center OR edges (whichever sits closer to viewer) so overlay NEVER
        // passes behind the video screen!
        float ovRadius = d / desiredCurve;
        float screenAngle = screenCurved && screenRadius > 1e-4f
                ? cylinderAngle(screenWidth, screenRadius)
                : 2.0f * atanf(drawnW * 0.5f / d);
        float ovAngle = clampf(screenAngle, 1e-3f, CYLINDER_MAX_ANGLE);
        float ovHalfAngle = ovAngle * 0.5f;
        float ovChordZ = screenCurvedChordZ(ovRadius, ovHalfAngle);

        // Screen surface local Z at center is 0, at edges is +screenChordZ (+Z toward viewer).
        // After perspective scaling r = ovRadius * (d - zCenter) / d, overlay edge Z is:
        //   zCenter + ovChordZ * (d - zCenter) / d >= screenChordZ + clearance
        // Solving directly for zCenter ensures exact edge clearance with zero clipping!
        float clearance = OVERLAY_PROUD_M + pop;
        float zCenter = clearance;
        float denom = 1.0f - (ovChordZ / d);
        if (denom > 0.1f) {
            float zEdgeNeeded = (screenChordZ + clearance - ovChordZ) / denom;
            if (zEdgeNeeded > zCenter) {
                zCenter = zEdgeNeeded;
            }
        }
        else if (screenChordZ + clearance > zCenter) {
            zCenter = screenChordZ + clearance;
        }
        float scale = (d - zCenter) / d;
        if (scale < 0.25f) {
            scale = 0.25f;
        }
        float r = ovRadius * scale;
        if (r < 0.05f) {
            r = 0.05f;
        }
        float arcW = r * ovAngle;
        float scaledH = regionH * scale;
        float scaledY = yCenter * scale;
        // Axis sits +r behind overlay center (which is at local Z = zCenter)
        Vec3 axisLocal = { 0.0f, scaledY, zCenter + r };
        out->useCylinder = 1;
        out->axisPose = localOffset(screenPose, axisLocal);
        out->radius = r;
        out->centralAngle = ovAngle;
        out->aspectRatio = arcW / scaledH;
        return 1;
    }

    // Flat quad mode (flat screen, room, !cylinderSupported, or OVERLAY_CURVE_FLAT):
    // When video screen is curved, place the flat quad on the screen's chord (+screenChordZ)
    // plus clearance so its edges never sink behind the curved picture (§6.12).
    float zFront = screenChordZ + OVERLAY_PROUD_M + pop;
    float scale = (d - zFront) / d;
    if (scale < 0.25f) {
        scale = 0.25f;
    }
    float chordW = (screenCurved && screenRadius > 1e-4f)
            ? (2.0f * screenRadius * sinf(screenHalfAngle))
            : drawnW;
    // Scale proportionally when popped closer to eye so angular width stays locked to picture
    float quadW = (screenCurved && screenRadius > 1e-4f)
            ? chordW * ((d - zFront) / (d - screenChordZ > 0.1f ? (d - screenChordZ) : d))
            : drawnW * scale;
    float quadH = regionH * scale;
    float quadY = yCenter * scale;
    Vec3 quadLocal = { 0.0f, quadY, zFront };
    out->useCylinder = 0;
    out->quadPose = localOffset(screenPose, quadLocal);
    out->quadWidth = quadW;
    out->quadHeight = quadH;
    out->aspectRatio = quadH > 1e-4f ? (quadW / quadH) : 1.0f;
    return 1;
}

int overlayCountLocalLayers(const OverlayLayerFlags* f) {
    if (f == NULL) {
        return 0;
    }
    int total = 0;
    if (f->roomOrDimmer) total += 1;
    if (f->glow) total += 1;
    total += f->videoEyes > 1 ? 2 : (f->videoEyes > 0 ? 1 : 0);
    total += f->danmakuEyes > 1 ? 2 : (f->danmakuEyes > 0 ? 1 : 0);
    if (f->subtitle) total += 1;
    if (f->transportBar) total += 1;
    if (f->popupCard) total += 1;
    if (f->palette) total += 1;
    // §6.5 & §6.7: toast/readout is mutually exclusive with transportBar
    if (f->toastOrReadout && !f->transportBar) total += 1;
    if (f->pointerRay) total += 2;
    if (f->controllerModel) total += 1;
    return total;
}
