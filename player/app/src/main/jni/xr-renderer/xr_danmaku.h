// GPU-accelerated instanced danmaku compositor with 2048x2048 premultiplied RGBA8
// glyph/emoji/stroke atlas, slot-generation gating, anchor-based monotonic 72/90 Hz
// clock, bounded incremental glTexSubImage2D uploads, staggered 4-stage lazy GL init,
// and per-eye stereoscopic rendering.
// Strictly gated on ctx->localMedia AND active enabled danmaku so PC streaming and
// videos without active danmaku allocate zero GPU resources.

#ifndef XR_DANMAKU_H
#define XR_DANMAKU_H

#include <stdint.h>
#include <stdatomic.h>
#include <pthread.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include "xr_shared.h"
#include "xr_overlay.h"

// Bounded ring buffer for partial atlas uploads from the danmaku worker thread
#define DANMAKU_UPLOAD_QUEUE_CAP 32
// Max bytes for a single rasterized RGBA8 danmaku patch (1024 x 96 x 4 B = 393,216 B)
#define DANMAKU_PATCH_MAX_BYTES  409600

typedef struct {
    int used;
    int x, y, w, h;
    int byteCount;
    int slotIndex;
    uint32_t slotGen;
    unsigned char* data; // lazily allocated up to DANMAKU_PATCH_MAX_BYTES, reused forever
} DanmakuAtlasPatch;

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
    int64_t pendingAnchorTimelineMs;
    int64_t pendingAnchorMonoNs;
    int pendingClockReset;
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

    // Render-thread-only active playback state & shared pure-C frame pipeline
    DanmakuFramePipeline pipe;
    float areaBotFrac;
    float globalOpacity;
    int curveMode;
    float customCurvePct;
    int floatAuto;
    int floatAutoOffset;
    int floatFixedSteps;
    float layerSpreadDeg;

    // Render-thread GL & OpenXR resources (staggered 4-stage lazy init only when enabled)
    int glInitStage; // 0: shaders/VAO, 1: atlas/FBO, 2: left eye chain, 3: right eye chain+scratch, 4: ready
    int glReady;
    atomic_int glFailed; // latched 1 on any GL/swapchain init error; never retries per frame
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
    DanmakuNativeItem snapScratch[DANMAKU_MAX_ITEMS];
    DanmakuGpuInstance gpuInst[DANMAKU_MAX_ITEMS];

    // Output state read by addDanmakuLayers() in xr_layers.c
    int drawnEyes;       // 0 = hidden (submit 0 layers), 1 = flat mono (1 layer), 2 = stereo (2 layers)
    int drawnTexH;       // cropped height in [1..DANMAKU_TEX_H] matching areaBotFrac
    float drawnAreaBotFrac;
} DanmakuState;

struct XrCtx;

void danmakuInit(struct XrCtx* ctx);
void danmakuDestroy(struct XrCtx* ctx);
void danmakuRenderEyes(struct XrCtx* ctx, float screenFovDeg, float screenDistM,
                       float dt, int64_t frameMonoNs, int stereoActive);

#endif
