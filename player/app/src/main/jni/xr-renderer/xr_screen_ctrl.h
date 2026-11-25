// Screen manipulation, continuous curvature geometry, layer-budget predicates,
// and local-playback input decision state machine (ux-spec.md §6.4, §6.5, §6.12).
// Pure C with no GL, Android, or OpenXR runtime calls so host unit tests
// exercise every rule, boundary, glue function, and table cell deterministically.

#ifndef XR_SCREEN_CTRL_H
#define XR_SCREEN_CTRL_H

#include <stdint.h>
#include "xr_layout.h"

// ============================================================================
// Tunable interaction & geometry constants (待头显验证)
// Centralized here so headset tuning touches one place.
// ============================================================================

// Unified screen physical width bounds (metres).
// Max 16.0 m matches 90 deg flat width at 8.0 m (2 * 8 * tan(45 deg)) so VR
// screen adjust, corner resize, cog slider, pose restore, and 2D prefs all
// share the exact same upper bound without truncating 62 deg @ 8 m.
#define SCREEN_MIN_WIDTH          0.25f
#define SCREEN_MAX_WIDTH          16.0f

// Grab modes shared between applyGrab (xr_input.c) and glue guards
#define GRAB_NONE   0
#define GRAB_MOVE   1
#define GRAB_RESIZE 2
#define GRAB_SPHERE 3

// Screen size (horizontal angular width, degrees) and detents (§6.12)
#define SCREEN_FOV_MIN_DEG        30.0f
#define SCREEN_FOV_MAX_DEG        90.0f
#define SCREEN_FOV_DEFAULT_DEG    50.0f
#define SCREEN_FOV_DETENT_COUNT   4

// Screen viewing distance (metres, logarithmic scale) and detents (§6.12)
#define SCREEN_DIST_MIN_M         2.0f
#define SCREEN_DIST_MAX_M         8.0f
#define SCREEN_DIST_DEFAULT_M     3.0f
#define SCREEN_DIST_DETENT_COUNT  3

// Curvature below this threshold renders as a flat quad (§6.12)
#define SCREEN_CURVE_EPS          0.005f

// Trigger click vs hold-hint / hand-pinch enter threshold (350 ms, 待头显验证)
#define SCREEN_CLICK_HOLD_NS      350000000LL

// Thumbstick deadzone & engagement thresholds (§6.4, 待头显验证)
#define SCREEN_STICK_DEADZONE     0.25f
#define SCREEN_STICK_ENGAGE       0.50f

// Full-deflection adjustment rates (§6.12, 待头显验证)
// Size: 20 deg/s; Distance: x1.6/s (log scale, ~2.95 s across 2 m..8 m)
#define SCREEN_FOV_SPEED_DEG_S    20.0f
#define SCREEN_DIST_LOG_RATE      1.6f

// Detent dwell hold time (300 ms, 待头显验证) and release snap windows (§6.12)
#define SCREEN_DETENT_HOLD_NS     300000000LL
#define SCREEN_FOV_SNAP_DEG       1.5f
#define SCREEN_DIST_SNAP_FRAC     0.04f

// Hand-tracking pinch-adjust parameters (§6.4, 待头显验证)
#define SCREEN_HAND_DRIFT_MAX_DEG   1.5f
#define SCREEN_HAND_DEADZONE_M      0.015f
#define SCREEN_HAND_DIST_STEP_M     0.015f
#define SCREEN_HAND_DIST_FACTOR     1.05f
#define SCREEN_HAND_FOV_DEG_PER_M   100.0f
// Pause stillness window along the locked axis that releases hand axis lock
// so the user can switch between distance and size within a single pinch
#define SCREEN_HAND_UNLOCK_PAUSE_NS 300000000LL
#define SCREEN_HAND_UNLOCK_STILL_M  0.010f

// Stick seek cadence & double-B exit window (§6.4, repeat cadence 待头显验证)
#define SCREEN_SEEK_STEP_SEC      10
#define SCREEN_SEEK_FAST_SEC      30
#define SCREEN_SEEK_FAST_HOLD_NS  1000000000LL
#define SCREEN_SEEK_REPEAT_NS     400000000LL
#define SCREEN_B_EXIT_WINDOW_NS   2000000000LL

// Haptic feedback durations & amplitudes (§4.5, §6.12, 待头显验证)
#define SCREEN_HAPTIC_HINT_MS      5
#define SCREEN_HAPTIC_HINT_AMP     0.2f
#define SCREEN_HAPTIC_DETENT_MS    15
#define SCREEN_HAPTIC_DETENT_AMP   0.5f
#define SCREEN_HAPTIC_LIMIT_MS     10
#define SCREEN_HAPTIC_LIMIT_AMP    0.4f
#define SCREEN_HAPTIC_LIMIT_GAP_MS 60

// Screen-adjust readout placement in head space (§6.1, §6.12, 待头显验证)
// Exact spec §6.12: 24 deg x 2.8 deg, 960 x 112 px, 18 deg below gaze at 0.80 m
#define SCREEN_READOUT_DIST_M      0.80f
#define SCREEN_READOUT_FOV_W_DEG   24.0f
#define SCREEN_READOUT_FOV_H_DEG   2.8f
#define SCREEN_READOUT_DROP_DEG    18.0f

// Diagonal inches constant for 16:9 picture: cos(atan(9/16)) = 0.8716 (§6.12)
#define SCREEN_DIAG_WIDTH_RATIO    0.8716f
#define SCREEN_METRES_PER_INCH     0.0254f

// Proud offset for coaxial curved transport strip (1.2 cm in front of screen)
#define TRANSPORT_PROUD_M          0.012f

// Hardware ceiling & §6.5 simultaneous layer design budget
#define LAYER_BUDGET_HARDWARE_MAX  16
#define LAYER_BUDGET_DESIGN_MAX    13

// Ray target classification at input pass time (§6.4 Rule 1)
#define SCREEN_TARGET_NONE          0
#define SCREEN_TARGET_SCREEN        1
#define SCREEN_TARGET_CONTROL       2
#define SCREEN_TARGET_PALETTE_ROW   3
#define SCREEN_TARGET_PALETTE_OTHER 4
#define SCREEN_TARGET_CARD          5

// Overlay card kinds for B-button priority (§6.4 Rule 10)
#define SCREEN_CARD_NONE     0
#define SCREEN_CARD_ERROR    1
#define SCREEN_CARD_CAST     2
#define SCREEN_CARD_NEXT_EP  3

// B-button resolved actions
#define SCREEN_B_NONE          0
#define SCREEN_B_UNDO_ADJUST   1
#define SCREEN_B_CLOSE_CARD    2
#define SCREEN_B_CLOSE_PALETTE 3
#define SCREEN_B_EXIT_PROMPT   4
#define SCREEN_B_EXIT_CONFIRM  5

// Haptic event kinds emitted by screenInputStep
#define SCREEN_HAPTIC_NONE   0
#define SCREEN_HAPTIC_HINT   1
#define SCREEN_HAPTIC_DETENT 2
#define SCREEN_HAPTIC_LIMIT  3

// Internal hold states
#define SCREEN_HOLD_IDLE       0
#define SCREEN_HOLD_ON_SCREEN  1
#define SCREEN_HOLD_HINTED     2
#define SCREEN_HOLD_ADJUSTING  3
#define SCREEN_HOLD_CANCELLED  4
#define SCREEN_HOLD_CONTROL    5

#define SCREEN_GRIP_IDLE       0
#define SCREEN_GRIP_SPHERE     1
#define SCREEN_GRIP_ADJUSTING  2
#define SCREEN_GRIP_CANCELLED  3

#define SCREEN_STICK_INTENT_NONE        0
#define SCREEN_STICK_INTENT_SEEK        1
#define SCREEN_STICK_INTENT_PALETTE_ROW 2
#define SCREEN_STICK_INTENT_NOOP        3

// Coaxial curved strip layer geometry (transport bar in G1; reusable for
// curved subtitles/danmaku in G3/G4)
typedef struct {
    XrPosef axisPose;
    float radius;
    float centralAngle;
    float aspectRatio;
} CurvedStrip;

// Layer-budget scenario flags matching ux-spec.md §6.5.
// Per §6.5, the dimmer/room slot is always active (1 layer: passthrough dimmer
// OR 3D cinema room) in every playback state.
typedef struct {
    int inRoom;          // 1 = 3D room layer; 0 = passthrough/void dimmer layer (still 1 slot per §6.5)
    int stereo;          // 1 = 2 eyes, 0 = 1 mono eye
    int hasDanmaku;      // 2 layers (L/R parallax) when active (G4)
    int hasSubtitle;     // 1 shared layer when active (G3)
    int hasControlBar;   // 1 layer when visible and NOT adjusting screen
    int hasPalette;      // 1 layer when palette open (G2)
    int hasReadout;      // 1 layer while adjusting screen (shares toast slot, hides control bar)
    int hasToast;        // 1 layer when toast showing (shares readout slot)
    int hasCard;         // 1 layer when popup card showing
    int hasPointer;      // ray + dot = 2 layers
    int hasController;   // controller model = 1 layer (0 in hand tracking)
    int hasGlow;         // ambilight glow behind picture = 1 layer
} LayerBudgetScene;

// Per-frame inputs to the pure local-playback decision state machine
typedef struct {
    int64_t nowNs;
    float dt;
    int target;
    int paletteOpen;
    int cardKind;
    int triggerAdjustEnabled;
    int inRoom;
    int isHandTracking;
    int externalGrabActive; // 1 while bar-move (GRAB_MOVE) or corner-resize (GRAB_RESIZE) is active
    int triggerDown;
    int gripDown;
    float rStickX;
    float rStickY;
    int rStickClick;
    float lStickX;
    float lStickY;
    int btnA;
    int btnB;
    int btnY;
    int btnMenu;
    Vec3 aimDir;
    Vec3 handPos;
    Vec3 headRight;
    Vec3 headForward;
    float curFovDeg;
    float curDistM;
} ScreenInputFrame;

// Persistent state across frames for local-playback input decisions
typedef struct {
    int prevTrigger;
    int prevGrip;
    int prevA;
    int prevB;
    int prevY;
    int prevMenu;
    int prevRStickClick;

    int trigState;
    int64_t trigDownNs;
    int trigStickLeftDz;
    int trigPrePushed;
    int trigHandMode;
    Vec3 trigStartDir;
    float trigMaxDriftDeg;

    int gripState;
    int gripPrePushed;

    int adjustActive;
    int adjustByGrip;
    int adjustByHand;
    int adjustAxis;
    float adjustFovDeg;
    float adjustDistM;
    float preAdjustFovDeg;
    float preAdjustDistM;

    Vec3 handOrigin;
    float handAxisOriginFov;
    float handAxisOriginDist;
    Vec3 handStillAnchor;
    int64_t handStillSinceNs;

    int64_t detentHoldUntilNs;
    int detentHoldAxis;
    float detentHoldValue;
    int detentHoldDir;
    int hasLastDetent;
    int lastDetentAxis;
    float lastDetentValue;

    int limitHitAxis;
    int limitHitDir;

    int stickPostAdjustLock;
    int stickXIntent;
    int stickXDir;
    int64_t stickXStartNs;
    int64_t stickXNextRepeatNs;

    int lStickXLatched;
    int lStickYDir;
    int64_t lStickYNextNs;

    int64_t lastBExitPromptNs;
} ScreenInputState;

// Resolved per-frame actions and readout state
typedef struct {
    int playPause;
    int seekDeltaSec;
    int bAction;
    int yToggleDanmaku;
    int menuTogglePalette;
    int clickControl;
    int paletteRowStep;
    int paletteRowReset;
    int paletteTabStep;
    int palettePageStep;

    int adjustActive;
    int adjustStarted;
    int adjustSaved;
    int adjustUndone;
    int adjustAxis;
    int sphereDragActive;
    int showHintToast;
    int hideControlBar;
    int paletteDimmed;
    int hapticEvent;

    float fovDeg;
    float distM;
    int diagonalInches;
    int sizePresetIdx;
} ScreenInputResult;

// ---- Screen size, distance, inches & curvature math ----

extern const float SCREEN_FOV_DETENTS[SCREEN_FOV_DETENT_COUNT];
extern const float SCREEN_DIST_DETENTS[SCREEN_DIST_DETENT_COUNT];

float screenClampFov(float fovDeg);
float screenClampDist(float distM);
float screenFlatWidth(float fovDeg, float distM);
float screenFovFromFlatWidth(float flatW, float distM);
int screenDiagonalInches(float fovDeg, float distM);
int screenSizePresetIndex(float fovDeg);
float screenSizeNorm(float fovDeg);
float screenDistNorm(float distM);

// Distance from viewer reference point to screen centre (fallback SCREEN_DIST_DEFAULT_M)
float screenDistanceFrom(XrPosef screenPose, Vec3 refPos);

// Continuous curvature radius R = D / c (0 when flat, c <= SCREEN_CURVE_EPS)
float screenCurvatureRadius(float distM, float curve);
// Cylinder arc width that preserves horizontal angular width fovDeg from viewer
float screenCurvedArcWidth(float fovDeg, float distM, float curve);
// Exact inverse: horizontal angular width seen from viewer at distM
float screenFovFromPlacement(float width, float distM, float radius, int curved);
// Quantize continuous curvature [0..1] to 5% steps (0, 0.05, ..., 1.0)
float screenQuantizeCurve(float curve);

// Unified placement helper shared by adjust, corner resize, cog sliders, and updatePlacement:
// clamps horizontal FOV to [30, 90] deg and computes width + radius (R = D / c when curved).
void screenPlacementForFov(float fovDeg, float distM, float curve, int cylinderSupported,
                           float* outWidth, float* outRadius);

// Migrates a saved pose (potentially from the legacy R = D*(1+3*(1-c)) formula) to R = D / c
// while preserving the horizontal angular width the viewer originally saw.
void screenMigratePoseGeometry(XrPosef screenPose, Vec3 refPos,
                               float storedWidth, float storedRadius,
                               float curve, int cylinderSupported,
                               float* outWidth, float* outRadius);

// Coaxial curved strip geometry for bottom-aligned overlays (transport bar)
int screenCurvedStrip(XrPosef screenPose, float screenWidth, float screenHeight,
                      float screenRadius, float marginFrac, float texAspect,
                      float proudM, CurvedStrip* out);

// Chord sagitta offset so a flat overlay sits cleanly in front of curved edges
float screenCurvedChordZ(float radius, float halfAngle);

// Shared hit test matching TransportBar.java bounds
int screenTransportBarHit(float u, float v, float videoAspect);

// Grip-on-screen sphere drag preserving distance from headPos and facing viewer
XrPosef screenSphereDrag(XrPosef grabScreen, XrPosef grabAim, XrPosef curAim,
                         Vec3 headPos, float grabPitch, float grabRoll);

// ---- Shared xr_layers.c visibility predicates & §6.5 layer budget ----

int screenShowTransportLayer(int localMedia, int screenAdjustActive,
                             int transportVisible, int barArea, int hasContent);
int screenShowBarArea(int localMedia, int screenAdjustActive,
                      int modalOrFading, int hoverInBarArea);
int screenShowReadoutLayer(int localMedia, int screenAdjustActive,
                           int toastArtReady, int toastDrawnKind);
int screenShowNoticeToastLayer(int localMedia, int screenAdjustActive,
                               int toastArtReady, int toastDrawnKind,
                               int noticeShowing, int noticeMatches);

int screenLayerBudgetCount(const LayerBudgetScene* scene);
int screenLayerBudgetOk(int count, int maxLimit);

// ---- Shared xr_input.c glue helpers (tested directly on host) ----

int screenClassifyLocalTarget(int modalOpen, int hoverKind, int transportVisible,
                              float hitU, float hitV, float videoAspect);
int screenShouldSuppressLeft(const ScreenInputState* st, int externalGrabActive);
int screenAllowExternalGrab(int grabMode, const ScreenInputState* st);
int screenIsExternalGrabBusy(int grabMode);

// ---- Pure local-playback input state machine ----

void screenInputInit(ScreenInputState* st);
void screenInputReset(ScreenInputState* st);
void screenInputStep(ScreenInputState* st, const ScreenInputFrame* in,
                     ScreenInputResult* out);

#endif
