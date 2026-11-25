// Shared overlay surface geometry, stereoscopic layered-float depth placement,
// 64-bin depth histogram 98th-percentile readout, and §6.5 layer budget math.
// Built in G4 (danmaku) and designed for direct reuse by G3 (static subtitles)
// with zero GL, Android, or OpenXR runtime dependencies.

#ifndef XR_OVERLAY_H
#define XR_OVERLAY_H

#include <stdint.h>
#include "xr_math.h"
#include "xr_shared.h"
#include "xr_screen_ctrl.h"

// ============================================================================
// Tunable spatial, stereoscopic float & timing constants (待头显验证)
// ============================================================================

// Standard IPD used for disparity <-> distance conversion (§6.10)
#define OVERLAY_IPD_M                     0.063f
// Hard stereoscopic comfort ceilings (§6.10): near layer <= 1.0 deg & >= 1.60 m
#define OVERLAY_MAX_NEAR_PHI_DEG          1.0f
#define OVERLAY_MIN_EYE_DIST_M            1.60f
// Assumed foreground content disparity for native SBS/OU (no depth map, §6.10)
#define OVERLAY_NATIVE_3D_CONTENT_PHI_DEG 0.25f
// Automatic float clearance in front of 98th-percentile foreground content (§6.10)
#define OVERLAY_AUTO_MARGIN_PHI_DEG       0.10f
#define OVERLAY_FLOAT_STEP_DEG            0.05f
// Temporal smoothing on content depth: instant forward, <= 0.05 deg/s retreat (§6.10)
#define OVERLAY_DEPTH_RETREAT_DEG_PER_S   0.05f
// Smooth depth transition duration when user drags Float/Layer slider (§5.6, §6.10)
#define OVERLAY_DEPTH_ANIM_S              0.200f
// Left & right edge feather angular width on danmaku layer (§6.10)
#define OVERLAY_EDGE_FEATHER_DEG          1.5f
// Small proud offset in front of video surface to avoid z-fighting (8 mm)
#define OVERLAY_PROUD_M                   0.008f
// Histogram bin count for depth map 98th percentile (§7)
#define OVERLAY_HIST_BINS                 64

// Resulting stereoscopic disparities & physical distances for the 3 depth bands.
// G4 danmaku uses all 3 bands (FAR, MID, NEAR); G3 subtitles sit at FAR (farDistM).
typedef struct {
    float farPhiDeg;
    float midPhiDeg;
    float nearPhiDeg;
    float farDistM;
    float midDistM;
    float nearDistM;
    float appliedDeltaDeg;
    float maxPhiDeg;
} OverlayDepthLayers;

// Computed OpenXR layer placement for a screen-anchored overlay strip or sub-region
// (shared directly by G4 danmaku eyes and G3 static subtitles).
typedef struct {
    int useCylinder;     // 1 -> submit XrCompositionLayerCylinderKHR, 0 -> XrCompositionLayerQuad
    // Cylinder fields (when useCylinder == 1)
    XrPosef axisPose;
    float radius;
    float centralAngle;
    float aspectRatio;   // width / height of the active overlay region
    // Quad fields (when useCylinder == 0)
    XrPosef quadPose;
    float quadWidth;
    float quadHeight;
} OverlaySurfaceGeom;

// Flags describing every active local-playback composition layer for §6.5 budget checks.
typedef struct {
    int roomOrDimmer;    // 0 or 1 (mutually exclusive)
    int glow;            // 0 or 1
    int videoEyes;       // 1 (mono) or 2 (stereo)
    int danmakuEyes;     // 0 (none/off), 1 (flat mono), or 2 (layered stereo)
    int subtitle;        // 0 or 1 (G3 slot)
    int transportBar;    // 0 or 1
    int popupCard;       // 0 or 1
    int palette;         // 0 or 1
    int toastOrReadout;  // 0 or 1 (mutually exclusive with transportBar per §6.5/§6.7)
    int pointerRay;      // 0 or 1 (2 layers: beam + cursor dot)
    int controllerModel; // 0 or 1 (0 in hand tracking)
} OverlayLayerFlags;

// Converts crossed disparity angle (degrees) at screen distance D to physical eye distance (m).
float overlayDisparityToDistM(float phiDeg, float screenDistM);

// Computes the hard ceiling disparity (degrees) at distance D satisfying both
// phi <= 1.0 deg and physical distance >= 1.60 m.
float overlayMaxPhiAtDistDeg(float screenDistM);

// Computes Far / Mid / Near layer disparities and distances per §6.10, compressing
// spread delta first and far layer second whenever the comfort ceiling is exceeded.
void overlayComputeDepthLayers(float contentPhiDeg, int floatAuto,
                               int floatAutoOffsetSteps, int floatFixedSteps,
                               float layerSpreadDeg, float screenDistM,
                               OverlayDepthLayers* out);

// Resolves target foreground content disparity (deg) for 2D / AI-3D / native SBS/OU.
float overlayResolveContentPhi(int stereoActive, int stereoSourceFormat,
                               float measuredDepthPhiDeg);

// Steps smoothed content disparity: follows closer (larger phi) immediately,
// retreats farther (smaller phi) at most 0.05 deg/s.
float overlayStepContentDepth(float curPhiDeg, float targetPhiDeg, float dt);

// Computes 64-bin 98th-percentile foreground crossed disparity (degrees) from a
// normalized [0..1] depth map (bottom-up GL row order) over screen vertical band
// [vTopFrac, vBotFrac] (measured top-down: 0 = top of picture, 1 = bottom).
float overlayHistogram98Percentile(const float* depthNorm, int w, int h,
                                   float vTopFrac, float vBotFrac,
                                   float convergence, float separation,
                                   float screenFovDeg);

// Per-eye horizontal UV shift for a given disparity phiDeg on a screen of width screenFovDeg.
// eyeIndex == 0 (left) shifts right (+), eyeIndex == 1 (right) shifts left (-) -> pops forward.
float overlayEyeShiftNormU(float phiDeg, float screenFovDeg, int eyeIndex);

// Computes shared curved/flat overlay geometry for a vertical sub-region [vTopFrac, vBotFrac]
// (0 = top of screen, 1 = bottom of screen). Guarantees the overlay NEVER intersects behind
// the video screen for Follow-Screen, Flat (placed on chord), and Custom curvature modes,
// and supports geometric pop-out distance `popOutM` for G3 single-layer subtitles.
int overlayComputeSurface(XrPosef screenPose, float screenWidth, float screenHeight,
                          float screenRadius, int screenCurved, int cylinderSupported,
                          int roomOn, int curveMode, float customCurvePct,
                          float screenDistM, float vTopFrac, float vBotFrac,
                          float popOutM, OverlaySurfaceGeom* out);

// Returns total composition layers for a local playback frame per §6.5.
int overlayCountLocalLayers(const OverlayLayerFlags* f);

// ============================================================================
// Pure-C production helpers for danmaku slot generations, anchor-based monotonic
// snapshot clock, and §6.5/16-layer real drop ladder (shared & host-tested).
// ============================================================================

// Tracks the latest GPU-landed generation per atlas slot so an instance can NEVER
// render stale text or un-uploaded pixels when a slot is reused or queued behind
// the per-frame upload limit.
typedef struct {
    uint32_t uploadedGen[DANMAKU_MAX_SLOTS];
} DanmakuSlotTable;

void danmakuSlotTableClear(DanmakuSlotTable* t);
void danmakuSlotTableMarkUploaded(DanmakuSlotTable* t, int slotIndex, uint32_t gen);
int danmakuSlotTableIsReady(const DanmakuSlotTable* t, int slotIndex, uint32_t gen);

// Anchor-based monotonic clock for 72/90 Hz danmaku scrolling:
// Worker publishes (anchorTimelineMs, anchorMonoNs, paused); render thread extrapolates
// continuously from CLOCK_MONOTONIC without stepping backward or jumping +/-14 ms on
// every 20 Hz snapshot arrival.
typedef struct {
    int initialized;
    int paused;
    double anchorTimelineSec;
    int64_t anchorMonoNs;
    double currentSec;
} DanmakuClockTracker;

void danmakuClockReset(DanmakuClockTracker* c);
void danmakuClockSetAnchor(DanmakuClockTracker* c, int64_t timelineMs,
                           int64_t anchorMonoNs, int paused, int hardReset);
float danmakuClockSample(DanmakuClockTracker* c, int64_t nowMonoNs, float dtSec);

// Real composition-layer categories used by xr_layers.c and its §6.5 drop ladder.
typedef enum {
    OVERLAY_SLOT_OTHER = 0,
    OVERLAY_SLOT_TOAST = 1,
    OVERLAY_SLOT_MODELS = 2,
    OVERLAY_SLOT_COG_HOVER = 3,
    OVERLAY_SLOT_COG_BUTTON = 4,
    OVERLAY_SLOT_COG_CLOCK = 5,
    OVERLAY_SLOT_DANMAKU = 6
} OverlayLayerSlotKind;

// Evaluates the real production drop ladder against min(maxLayers, OVERLAY_HARD_MAX_LAYERS).
// Writes keepOut[i] = 1 (keep) or 0 (drop) for i in [0, count) and returns kept layer count.
// Drop priority order: toast -> models -> cogHover -> cogButton -> cogClock -> danmaku.
int overlayApplyLayerBudget(const int* slotKinds, int count, int maxLayers, int* keepOut);

#endif
