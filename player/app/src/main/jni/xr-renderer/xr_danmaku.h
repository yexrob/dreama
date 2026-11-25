// GPU-accelerated instanced danmaku compositor with 2048x2048 2-channel (RG8)
// glyph/stroke atlas, bounded incremental glTexSubImage2D uploads, smooth
// 72/90 Hz native timeline interpolation, and per-eye stereoscopic rendering.
// Strictly gated on ctx->localMedia so PC streaming allocates zero resources.

#ifndef XR_DANMAKU_H
#define XR_DANMAKU_H

#include <stdint.h>
#include <pthread.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include "xr_shared.h"
#include "xr_overlay.h"

// Bounded ring buffer for partial atlas uploads from the danmaku worker thread
#define DANMAKU_UPLOAD_QUEUE_CAP 32
// Max bytes for a single rasterized danmaku patch (e.g., 1024 x 96 x 2 B = 196 KB)
#define DANMAKU_PATCH_MAX_BYTES  204800

typedef struct {
    int used;
    int x, y, w, h;
    int byteCount;
    unsigned char* data; // lazily allocated up to DANMAKU_PATCH_MAX_BYTES, reused forever
} DanmakuAtlasPatch;

typedef struct {
    float x0;           // normalized [0..1] start X at spawnTimeSec (top-left of quad)
    float y0;           // normalized [0..1] top Y within active overlay area [0..areaBotFrac]
    float w;            // normalized width relative to screen width
    float h;            // normalized height relative to active overlay area height
    float velXPerSec;   // normalized screen-width units/sec (negative for scroll left)
    float u0, v0;       // atlas top-left UV [0..1]
    float du, dv;       // atlas UV span
    float r, g, b;      // linear/sRGB text color [0..1]
    float baseAlpha;    // 0..1 base opacity
    float spawnTimeSec; // item spawn time on native danmaku timeline
    float durationSec;  // item lifetime in real seconds
    float fadeSec;      // linear fade-in/fade-out duration (0.15s for top/bottom, 0 for scroll)
    int mode;           // DANMAKU_MODE_SCROLL / TOP / BOTTOM
    int layerBand;      // DANMAKU_LAYER_FAR / MID / NEAR
    float fixedPhiDeg;  // item's disparity at spawn (frozen unless user drags Float/Layer)
    float animFromPhi;  // start disparity for 200 ms user-drag transition
    float animToPhi;    // target disparity for 200 ms user-drag transition
} DanmakuNativeItem;

// Packed per-instance GPU vertex buffer struct (13 floats = 52 bytes)
typedef struct {
    float rect[4];      // x0, y0, w, h in [0..1] overlay area coords
    float uv[4];        // u0, v0, du, dv in [0..1] atlas coords
    float colorAlpha[4];// r, g, b, alpha
    float phiDeg;       // effective crossed disparity in degrees
} DanmakuGpuInstance;

typedef struct {
    pthread_mutex_t lock;
    int lockReady;

    // Pending atlas operations enqueued from worker, drained on render thread
    int clearAtlasPending;
    DanmakuAtlasPatch uploadQueue[DANMAKU_UPLOAD_QUEUE_CAP];
    int uploadHead;
    int uploadTail;
    int uploadCount;

    // Pending snapshot published by worker, swapped on render thread in O(N) memcpy (< 5 us)
    int snapshotDirty;
    int pendingEnabled;
    int pendingPaused;
    float pendingClockSec;
    float pendingAreaBotFrac;
    float pendingGlobalOpacity;
    int pendingCurveMode;
    float pendingCustomCurvePct;
    int pendingFloatAuto;
    int pendingFloatAutoOffset;
    int pendingFloatFixedSteps;
    float pendingLayerSpreadDeg;
    int pendingAnimateDepth;
    int pendingCount;
    DanmakuNativeItem pendingItems[DANMAKU_MAX_ITEMS];

    // Render-thread-only active playback state
    int enabled;
    int paused;
    float clockSec;
    float areaBotFrac;
    float globalOpacity;
    int curveMode;
    float customCurvePct;
    int floatAuto;
    int floatAutoOffset;
    int floatFixedSteps;
    float layerSpreadDeg;
    int itemCount;
    DanmakuNativeItem items[DANMAKU_MAX_ITEMS];

    // Smooth 200 ms depth transition when user drags Float/Layer in palette
    int depthAnimActive;
    float depthAnimElapsedS;

    // Render-thread GL & OpenXR resources (lazily created on first active danmaku)
    int glReady;
    int glFailed;
    GLuint program;
    GLuint vao;
    GLuint quadVbo;
    GLuint instVbo;
    GLuint atlasTex;
    GLuint fbo;
    GLint eyeSignUniform;
    GLint screenFovUniform;
    GLint globalOpacityUniform;
    GLint featherUUniform;
    GLint atlasUniform;

    XrSwapchain eyeSwapchain[2];
    uint32_t eyeImageCount[2];
    XrSwapchainImageOpenGLESKHR* eyeImages[2];

    // Pre-allocated CPU staging buffers (zero per-frame render-thread malloc)
    unsigned char* uploadScratch; // render-thread copy target for race-free glTexSubImage2D
    DanmakuGpuInstance gpuInst[DANMAKU_MAX_ITEMS];

    // Output state read by addDanmakuLayers() in xr_layers.c
    int drawnEyes;       // 0 = hidden (submit 0 layers), 1 = flat mono (1 layer), 2 = stereo (2 layers)
    int drawnTexH;       // cropped height in [1..DANMAKU_TEX_H] matching areaBotFrac
    float drawnAreaBotFrac;
    OverlayDepthLayers currentDepth;
} DanmakuState;

struct XrCtx;

void danmakuInit(struct XrCtx* ctx);
void danmakuDestroy(struct XrCtx* ctx);
void danmakuRenderEyes(struct XrCtx* ctx, float screenFovDeg, float screenDistM,
                       float dt, int stereoActive);

#endif
