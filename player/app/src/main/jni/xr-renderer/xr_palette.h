// Controller-mounted & pinnable VR Quick Palette spatial math, pose filter,
// ray intersection with 1.5 cm edge deadzone, 20 px hover hysteresis,
// grip/hand-tracking drag, and fade/dimming controller (ux-spec.md §6.3-§6.5).
// Pure C with zero GL, Android, or OpenXR runtime calls so host unit tests
// verify every angle, offset, mirror, deadzone, and transition deterministically.

#ifndef XR_PALETTE_H
#define XR_PALETTE_H

#include <stdint.h>
#include "xr_math.h"
#include "xr_shared.h"

// ============================================================================
// Tunable spatial, ergonomic & interaction constants (待头显验证)
// Centralized here so headset tuning modifies a single header.
// ============================================================================

// Physical panel size (metres): 20 x 15 cm (51.2 texel/cm at 1024x768)
#define PALETTE_W_M                 0.20f
#define PALETTE_H_M                 0.15f

// Hand-mount offset relative to controller grip space (待头显验证)
// Bottom edge sits 8 cm above grip origin (clears controller tracking ring),
// shifted 2 cm toward body midline (+X on left hand, -X on right hand).
#define PALETTE_MOUNT_UP_M          0.08f
#define PALETTE_MOUNT_IN_M          0.02f
// Pitch back relative to grip so panel faces viewer (~40 deg, 待头显验证)
#define PALETTE_MOUNT_PITCH_DEG     40.0f
// Eye-facing angular correction cap & low-pass filter time constant (待头显验证)
#define PALETTE_EYE_CORRECT_MAX_DEG 20.0f
#define PALETTE_EYE_LPF_TAU_S       0.100f
// Normal-to-gaze angle beyond which hand-mounted palette fades out & stops input
#define PALETTE_CULL_ANGLE_DEG      60.0f

// Control Bar 「调整」 open / hand-tracking pinned placement (待头显验证)
#define PALETTE_PIN_DIST_M          0.50f
#define PALETTE_PIN_DROP_DEG        20.0f
// Re-opening via ≡ keeps previous pinned pose if within ±60 deg of gaze
#define PALETTE_REOPEN_PIN_MAX_DEG  60.0f

// Motion & fade timings (§5.6, §6.3, 待头显验证)
#define PALETTE_FLY_NS              200000000LL
#define PALETTE_OPEN_FADE_NS        160000000LL
#define PALETTE_CLOSE_FADE_NS       120000000LL
// Opacity while adjusting screen (§6.3)
#define PALETTE_DIM_ALPHA           0.40f

// Hit-test safety margins (§6.3, 待头显验证)
// 1.5 cm deadzone outside edges stops trigger clicks from leaking to video
#define PALETTE_DEADZONE_M          0.015f
// 20 px (~0.39 cm) hitbox expansion on leave for stable hover
#define PALETTE_HYSTERESIS_PX       20.0f

// Hand-tracking horizontal row drag distance per discrete step (8 mm, 待头显验证)
#define PALETTE_HAND_DRAG_STEP_M    0.008f

// Palette UI haptics on operating hand (§4.5, 待头显验证)
#define PALETTE_HAPTIC_TICK_MS      8
#define PALETTE_HAPTIC_TICK_AMP     0.22f
#define PALETTE_HAPTIC_DETENT_MS    15
#define PALETTE_HAPTIC_DETENT_AMP   0.50f
#define PALETTE_HAPTIC_LIMIT_MS     10
#define PALETTE_HAPTIC_LIMIT_AMP    0.40f
#define PALETTE_HAPTIC_LIMIT_GAP_MS 60

// Palette hit classification
#define PALETTE_HIT_NONE      0
#define PALETTE_HIT_DEADZONE  1
#define PALETTE_HIT_TAB       2
#define PALETTE_HIT_PIN       3
#define PALETTE_HIT_DRAGBAR   4
#define PALETTE_HIT_ROW       5
#define PALETTE_HIT_OTHER     6

// Palette animation modes
#define PALETTE_ANIM_NONE          0
#define PALETTE_ANIM_TO_PINNED     1
#define PALETTE_ANIM_TO_HAND       2

typedef struct {
    int open;
    int pinned;
    int onRightHand;      // 0 = left hand (default), 1 = right hand (mirrored)
    int handTrackingMode; // 1 = opened/operating in hand tracking (always pinned, × close)

    int hasLastPinned;
    XrPosef pinnedPose;

    int animMode;
    int64_t animStartNs;
    XrPosef animFromPose;

    // Eye-correction orientation low-pass filter state
    int lpfValid;
    XrQuaternionf lpfOrient;

    // Current rendered pose & fade state
    int poseValid;
    XrPosef currentPose;
    float fadeAlpha;
    int fadeRunning;
    int fadeTarget;
    int64_t fadeStartNs;
    float fadeStartAlpha;

    // Hover hysteresis state (last interior hit zone)
    int hoverZone;
    int hoverIndex; // tab index 0..3 or row index 0..3
    float hoverPx;
    float hoverPy;

    // Grip / hand-tracking reposition drag state
    int dragging;
    int dragByGrip;
    XrPosef dragStartAim;
    XrPosef dragStartPose;

    // Hand-tracking horizontal row value drag state (8 mm / step)
    int handRowDragging;
    int handRowIdx;
    Vec3 handRowAnchor;
    float handRowAccumM;
} PaletteState;

typedef struct {
    int visible;       // 1 if layer should be submitted this frame
    int inputAllowed;  // 1 if ray hit-testing & stick controls are active
    XrPosef pose;
    float alpha;       // final colorScale alpha (includes 40% screen-adjust dimming)
    int dimFallback;   // 1 if dimmed AND colorScaleSupported == 0 (Java redraws at 40%)
} PaletteRenderPose;

typedef struct {
    int zone;          // PALETTE_HIT_*
    int index;         // tab 0..3 or row 0..3, else -1
    int rowAdjustable; // 1 if zone == PALETTE_HIT_ROW and row bit is set in activeRowMask
    float u;           // normalized [0..1] (may be outside [0..1] in deadzone)
    float v;           // normalized [0..1]
    float px;          // texture pixel X [0..1024]
    float py;          // texture pixel Y [0..768]
} PaletteHit;

void paletteInit(PaletteState* st);
void paletteSetHand(PaletteState* st, int onRightHand);
int paletteMountHand(const PaletteState* st);
int paletteOpHand(const PaletteState* st);

// Returns 1 when `hand` is the hand holding a hand-mounted palette in
// controller mode (its ray, trigger, and grip are disabled per §6.3).
int paletteShouldSuppressHand(const PaletteState* st, int hand, int handTracking);

// Pure spatial pose calculators
XrPosef paletteComputeHandPose(XrPosef gripPose, Vec3 headPos, int onRightHand,
                               XrQuaternionf* lpfOrient, int* lpfValid, float dt);
XrPosef paletteComputeUprightPose(Vec3 position, Vec3 headPos);
XrPosef paletteComputeBarOpenPose(XrPosef headPose);
float paletteNormalAngleDeg(XrPosef palettePose, Vec3 headPos);
int paletteWithinReopenCone(XrPosef pinnedPose, XrPosef headPose);

// State transitions
void paletteOpenFromMenu(PaletteState* st, XrPosef headPose, XrPosef gripPose,
                         int gripValid, int handTracking, int64_t nowNs);
void paletteOpenFromBar(PaletteState* st, XrPosef headPose, int handTracking, int64_t nowNs);
void paletteTogglePin(PaletteState* st, Vec3 headPos, int handTracking, int64_t nowNs);
void paletteClose(PaletteState* st, int64_t nowNs);

// Dragging (grip detach/drag or hand-tracking tab-bar drag)
void paletteStartDrag(PaletteState* st, XrPosef aimPose, Vec3 headPos, int byGrip, int64_t nowNs);
void paletteStepDrag(PaletteState* st, XrPosef curAimPose, Vec3 headPos);
void paletteEndDrag(PaletteState* st);

// Per-frame pose, fly-back, 60-deg cull, fade & dimming step
void paletteStepPoseAndFade(PaletteState* st, XrPosef headPose, XrPosef gripPose,
                            int gripValid, int handTracking, int screenAdjustActive,
                            float dt, int64_t nowNs, int colorScaleSupported,
                            PaletteRenderPose* out);

// Ray hit-test with 1.5 cm edge deadzone and 20 px hover hysteresis
void paletteRayHit(PaletteState* st, XrPosef palettePose, XrPosef aimPose,
                   int activeRowMask, PaletteHit* out);

// Hand-tracking horizontal row value drag (8 mm per discrete step)
int paletteHandRowDragStep(PaletteState* st, int pinchingOnRow, int rowIdx,
                           Vec3 handPos, Vec3 paletteRight);

// Predicate matching xr_layers.c single-layer submission
int paletteShowLayer(int localMedia, int paletteVisible, int paletteArtReady, float alpha);

// Predicate matching xr_layers.c passthrough dimmer submission (strictly localMedia only)
int paletteShowDimmerLayer(int localMedia, int roomOn, int passthrough,
                           int passthroughDim, int dimmerArtReady);

// Whether depth model inference should run (false for native SBS/OU stereo sources)
int paletteNeedsDepthInference(int stereoMode, int stereoLive, int stereoSourceFormat);

// Aspect-ratio heuristic separating Half-SBS (<=2.55) from Full-SBS (>=2.67) and
// Full-OU (<=1.20) from Half-OU (>=1.33) for standard 4:3 / 16:9 / 2.39:1 sources.
#define PALETTE_FULL_SBS_MIN_RATIO 2.60f
#define PALETTE_FULL_OU_MAX_RATIO  1.25f

// Effective per-eye display height / width ratio accounting for Full-SBS & Full-OU.
float paletteVideoHeightOverWidth(int videoW, int videoH, int stereoSourceFormat);

// Per-hand release latch preventing phantom trigger/grip edges when palette-hand
// suppression lifts while a button is still physically held down.
typedef struct {
    int triggerWaitRelease[2];
    int gripWaitRelease[2];
} PaletteSuppressLatch;

void paletteSuppressLatchReset(PaletteSuppressLatch* latch);

void paletteStepSuppressLatch(int suppressed, int rawTriggerDown, float rawTriggerVal,
                              float rawGripVal, float pressOff,
                              int* triggerLatch, int* gripLatch,
                              int* outBlockTrigger, int* outBlockGrip);

// ---- Pure-C glue matching xr_input.c updateLocalPalettePre / Post / stick mirror ----

typedef struct {
    XrPosef headPose;
    XrPosef gripPose;
    int gripValid;
    int handTracking;
    int screenAdjustActive;
    int colorScaleSupported;
    float dt;
    int64_t nowNs;
    int closeRequested;
    int barOpenRequested;
    int pinToggleRequested;
    int rawTriggerDown[2];
    float rawTriggerVal[2];
    float rawGripVal[2];
    float pressOff;
} PalettePreGlueIn;

typedef struct {
    PaletteRenderPose renderPose;
    int clearActiveDrags;
    int hapticKind;
    int suppressRay[2];
    int blockTrigger[2];
    int blockGrip[2];
} PalettePreGlueOut;

void paletteGluePreStep(PaletteState* st, PaletteSuppressLatch* latch,
                        const PalettePreGlueIn* in, PalettePreGlueOut* out);

typedef struct {
    XrPosef headPose;
    int handTracking;
    int screenAdjustActive;
    int grabMode;
    int activeRowMask;
    int64_t nowNs;
    float pressOn;
    float pressOff;
    int canPoint[3];
    int aimValid[3];
    XrPosef aimPoses[3];
    int triggerDown[3];
    int triggerEdge[3];
    float grabVal[2];
    int grabDown[2];
    Vec3 handDragPos[2];
    int dragHand;
    int sliderDragging;
    int sliderHand;
    PaletteRenderPose renderPose;
} PalettePostGlueIn;

typedef struct {
    PaletteHit hit;
    int claimed;
    int claimedHand;
    int claimedHover; // HOVER_PALETTE or HOVER_PALETTE_DEADZONE
    float claimedU;
    float claimedV;
    int clickState;   // 0=none, 1=down edge, 2=slider hold
    int rowStep;
    int hapticKind;
    int clickSound;
    int swallowHand;  // -1 if none, else src index
    int nextDragHand;
    int nextSliderDragging;
    int nextSliderHand;
    XrPosef updatedPose;
    int poseUpdated;
} PalettePostGlueOut;

void paletteGluePostStep(PaletteState* st, const PalettePostGlueIn* in, PalettePostGlueOut* out);

// Mirrors operating vs palette-hand sticks when palette is open on right hand (§6.3).
// `profileIsController[2]` indicates whether each physical hand is a controller.
void paletteGlueMirrorSticks(const PaletteState* st, const int profileIsController[2],
                             float leftX, float leftY, int leftStickClick,
                             float rightX, float rightY, int rightStickClick,
                             float* outOpX, float* outOpY, int* outOpStickClick,
                             float* outPalX, float* outPalY);

// Applies a local-playback B-button decision to the palette: closes the palette and
// signals drag clearing iff `isClosePaletteB` is set (higher-priority B actions such
// as adjust-undo or card-close leave the palette open). Returns 1 if palette closed.
int paletteGlueApplyCloseOnB(PaletteState* st, int isClosePaletteB, int64_t nowNs,
                             int* outClearDrags);

#endif
