// Screen manipulation, continuous curvature geometry, and local-playback input
// decision state machine (ux-spec.md §6.4, §6.5, §6.12).
// Pure C with no GL, Android, or OpenXR runtime calls so host unit tests
// exercise every rule, boundary, and table cell deterministically.

#ifndef XR_SCREEN_CTRL_H
#define XR_SCREEN_CTRL_H

#include <stdint.h>
#include "xr_math.h"
#include "xr_shared.h"

// ============================================================================
// Tunable interaction & geometry constants (待头显验证)
// Centralized here so headset tuning touches one place.
// ============================================================================

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
#define SCREEN_HAND_DRIFT_MAX_DEG 1.5f
#define SCREEN_HAND_DEADZONE_M    0.015f
#define SCREEN_HAND_DIST_STEP_M   0.015f
#define SCREEN_HAND_DIST_FACTOR   1.05f
#define SCREEN_HAND_FOV_DEG_PER_M 100.0f

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
// 24 deg wide, 18 deg below gaze at TOAST_DISTANCE_M (0.80 m)
#define SCREEN_READOUT_DIST_M      0.80f
#define SCREEN_READOUT_FOV_W_DEG   24.0f
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

// Layer-budget scenario flags matching ux-spec.md §6.5
typedef struct {
    int inRoom;
    int stereo;
    int hasDanmaku;
    int hasSubtitle;
    int hasControlBar;
    int hasPalette;
    int hasReadout;
    int hasToast;
    int hasCard;
    int hasPointer;      // ray + dot = 2 layers
    int hasController;   // controller model = 1 layer
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

    int64_t detentHoldUntilNs;
    int detentHoldAxis;
    float detentHoldValue;
    int detentHoldDir;
    int hasLastDetent;
    int lastDetentAxis;
    float lastDetentValue;

    int limitHitAxis;
    int limitHitDir;

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

// Continuous curvature radius R = D / c (0 when flat, c <= SCREEN_CURVE_EPS)
float screenCurvatureRadius(float distM, float curve);
// Cylinder arc width that preserves horizontal angular width fovDeg from viewer
float screenCurvedArcWidth(float fovDeg, float distM, float curve);
// Exact inverse: horizontal angular width seen from viewer at distM
float screenFovFromPlacement(float width, float distM, float radius, int curved);
// Quantize continuous curvature [0..1] to 5% steps (0, 0.05, ..., 1.0)
float screenQuantizeCurve(float curve);

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

// Layer budget counter matching ux-spec.md §6.5
int screenLayerBudgetCount(const LayerBudgetScene* scene);
int screenLayerBudgetOk(int count, int maxLimit);

// ---- Pure local-playback input state machine ----

void screenInputInit(ScreenInputState* st);
void screenInputReset(ScreenInputState* st);
void screenInputStep(ScreenInputState* st, const ScreenInputFrame* in,
                     ScreenInputResult* out);

#endif
