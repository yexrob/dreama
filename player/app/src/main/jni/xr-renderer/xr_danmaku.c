// GPU-accelerated instanced danmaku compositor & JNI bridge (ux-spec.md §6.10, §7).
// - Staggered 4-stage lazy GL init (1 lightweight stage per frame) triggered ONLY once an active
//   danmaku source is enabled: videos with danmaku OFF or no danmaku source pay 0 VRAM, and
//   enabling danmaku mid-playback never stalls the render thread for a full frame.
// - Any GL/swapchain init failure cleans up partial resources and latches glFailed = 1 permanently
//   (no per-frame retry loop), surfacing an unavailable state in the VR Quick Palette.
// - Premultiplied GL_RGBA8 2048x2048 atlas preserves full-color Android emoji + crisp CJK bold
//   fill + 0.08 em #0E0F12 stroke + 1 px soft drop shadow in one pass.
// - Delegates upload-before-snapshot ordering, monotonic anchor clock sampling, and slot-generation
//   instance gating directly to danmakuPipelineStepFrame() in xr_overlay.c (host-tested in C).

#include "xr_renderer.h"
#include "xr_shaders.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

static float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static void cleanupDanmakuGlResources(XrCtx* ctx) {
    DanmakuState* dm = &ctx->danmaku;
    if (dm->program != 0) {
        glDeleteProgram(dm->program);
        dm->program = 0;
    }
    if (dm->vao != 0) {
        glDeleteVertexArrays(1, &dm->vao);
        dm->vao = 0;
    }
    if (dm->quadVbo != 0) {
        glDeleteBuffers(1, &dm->quadVbo);
        dm->quadVbo = 0;
    }
    if (dm->instVbo != 0) {
        glDeleteBuffers(1, &dm->instVbo);
        dm->instVbo = 0;
    }
    if (dm->atlasTex != 0) {
        glDeleteTextures(1, &dm->atlasTex);
        dm->atlasTex = 0;
    }
    if (dm->fbo != 0) {
        glDeleteFramebuffers(1, &dm->fbo);
        dm->fbo = 0;
    }
    for (int eye = 0; eye < 2; eye++) {
        destroyArtSwapchain(ctx, &dm->eyeSwapchain[eye], &dm->eyeImages[eye]);
        dm->eyeImageCount[eye] = 0;
    }
    free(dm->uploadScratch);
    dm->uploadScratch = NULL;
    dm->glReady = 0;
    dm->glInitStage = 0;
    dm->drawnEyes = 0;
}

static void latchDanmakuGlFailed(XrCtx* ctx, const char* reason) {
    LOGW("depth3d: danmaku GPU init failed (%s); latching unavailable for session", reason);
    cleanupDanmakuGlResources(ctx);
    atomic_store_explicit(&ctx->danmaku.glFailed, 1, memory_order_relaxed);
}

// Advances danmaku GL & swapchain initialization by AT MOST ONE lightweight stage per frame
// (4 frames total: ~44-55 ms at 72-90 Hz, <= 1-2 ms work per frame) so enabling danmaku either
// at startup or mid-playback never causes a full-frame hitch.
// Returns 1 once all 4 stages are complete (dm->glReady == 1), 0 while still stepping or failed.
static int danmakuStepInitGl(XrCtx* ctx) {
    DanmakuState* dm = &ctx->danmaku;
    if (dm->glReady) {
        return 1;
    }
    if (atomic_load_explicit(&dm->glFailed, memory_order_relaxed)) {
        return 0;
    }

    if (dm->glInitStage == 0) {
        // Stage 0: shader compile/link + instanced quad VAO/VBOs
        GLuint vs = compileShader(GL_VERTEX_SHADER, DANMAKU_VERTEX_SRC);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, DANMAKU_FRAGMENT_SRC);
        if (vs == 0 || fs == 0) {
            if (vs != 0) glDeleteShader(vs);
            if (fs != 0) glDeleteShader(fs);
            latchDanmakuGlFailed(ctx, "shader compile");
            return 0;
        }
        GLuint prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        glDeleteShader(vs);
        glDeleteShader(fs);

        GLint linked = 0;
        glGetProgramiv(prog, GL_LINK_STATUS, &linked);
        if (!linked) {
            char log[512];
            glGetProgramInfoLog(prog, sizeof(log), NULL, log);
            LOGE("depth3d: danmaku program link failed: %s", log);
            glDeleteProgram(prog);
            latchDanmakuGlFailed(ctx, "program link");
            return 0;
        }
        dm->program = prog;
        dm->eyeSignUniform = glGetUniformLocation(prog, "u_eyeSign");
        dm->screenFovUniform = glGetUniformLocation(prog, "u_screenFovDeg");
        dm->globalOpacityUniform = glGetUniformLocation(prog, "u_globalOpacity");
        dm->featherUUniform = glGetUniformLocation(prog, "u_featherU");
        dm->atlasUniform = glGetUniformLocation(prog, "u_atlas");

        static const float QUAD_CORNERS[8] = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        };
        glGenVertexArrays(1, &dm->vao);
        glBindVertexArray(dm->vao);

        glGenBuffers(1, &dm->quadVbo);
        glBindBuffer(GL_ARRAY_BUFFER, dm->quadVbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(QUAD_CORNERS), QUAD_CORNERS, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), (void*)0);
        glVertexAttribDivisor(0, 0);

        glGenBuffers(1, &dm->instVbo);
        glBindBuffer(GL_ARRAY_BUFFER, dm->instVbo);
        glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(DANMAKU_MAX_ITEMS * sizeof(DanmakuGpuInstance)),
                     NULL, GL_STREAM_DRAW);
        GLsizei stride = (GLsizei)sizeof(DanmakuGpuInstance);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride,
                              (void*)offsetof(DanmakuGpuInstance, rect));
        glVertexAttribDivisor(1, 1);

        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, stride,
                              (void*)offsetof(DanmakuGpuInstance, uv));
        glVertexAttribDivisor(2, 1);

        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 4, GL_FLOAT, GL_FALSE, stride,
                              (void*)offsetof(DanmakuGpuInstance, colorAlpha));
        glVertexAttribDivisor(3, 1);

        glEnableVertexAttribArray(4);
        glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, stride,
                              (void*)offsetof(DanmakuGpuInstance, phiDeg));
        glVertexAttribDivisor(4, 1);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        dm->glInitStage = 1;
        return 0;
    }

    if (dm->glInitStage == 1) {
        // Stage 1: 2048x2048 premultiplied GL_RGBA8 atlas (16 MB VRAM) + FBO + GPU clear
        while (glGetError() != GL_NO_ERROR) {
            // Drain any unrelated prior error before checking atlas texture allocation
        }
        glGenTextures(1, &dm->atlasTex);
        glBindTexture(GL_TEXTURE_2D, dm->atlasTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, DANMAKU_ATLAS_W, DANMAKU_ATLAS_H, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        GLenum err = glGetError();
        if (dm->atlasTex == 0 || err != GL_NO_ERROR) {
            latchDanmakuGlFailed(ctx, "atlas texture allocation");
            return 0;
        }

        glGenFramebuffers(1, &dm->fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, dm->fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               dm->atlasTex, 0);
        glViewport(0, 0, DANMAKU_ATLAS_W, DANMAKU_ATLAS_H);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        dm->glInitStage = 2;
        return 0;
    }

    if (dm->glInitStage == 2) {
        // Stage 2: left eye OpenXR swapchain
        if (!createArtSwapchain(ctx, DANMAKU_TEX_W, DANMAKU_TEX_H,
                                "danmaku left swapchain",
                                &dm->eyeSwapchain[0], &dm->eyeImages[0],
                                &dm->eyeImageCount[0])) {
            latchDanmakuGlFailed(ctx, "left eye swapchain");
            return 0;
        }
        dm->glInitStage = 3;
        return 0;
    }

    // Stage 3: right eye OpenXR swapchain + CPU staging scratch -> ready
    if (!createArtSwapchain(ctx, DANMAKU_TEX_W, DANMAKU_TEX_H,
                            "danmaku right swapchain",
                            &dm->eyeSwapchain[1], &dm->eyeImages[1],
                            &dm->eyeImageCount[1])) {
        latchDanmakuGlFailed(ctx, "right eye swapchain");
        return 0;
    }
    dm->uploadScratch = (unsigned char*)malloc(DANMAKU_PATCH_MAX_BYTES);
    if (dm->uploadScratch == NULL) {
        latchDanmakuGlFailed(ctx, "uploadScratch malloc");
        return 0;
    }

    danmakuSlotTableClear(&dm->pipe.slotTable);
    dm->glInitStage = 4;
    dm->glReady = 1;
    LOGEV("depth3d: danmaku GPU atlas (%dx%d RGBA8) and eye swapchains (%dx%d x2) ready (4-stage lazy init)",
          DANMAKU_ATLAS_W, DANMAKU_ATLAS_H, DANMAKU_TEX_W, DANMAKU_TEX_H);
    return 1;
}

void danmakuInit(XrCtx* ctx) {
    if (ctx == NULL) {
        return;
    }
    DanmakuState* dm = &ctx->danmaku;
    memset(dm, 0, sizeof(*dm));
    pthread_mutex_init(&dm->lock, NULL);
    dm->lockReady = 1;
    atomic_init(&dm->glFailed, 0);
    danmakuPipelineInit(&dm->pipe);
    dm->areaBotFrac = 0.5f;
    dm->globalOpacity = 0.8f;
    dm->floatAuto = 1;
    dm->layerSpreadDeg = 0.30f;
    dm->drawnTexH = DANMAKU_TEX_H / 2;
    dm->drawnAreaBotFrac = 0.5f;
}

void danmakuDestroy(XrCtx* ctx) {
    if (ctx == NULL) {
        return;
    }
    DanmakuState* dm = &ctx->danmaku;
    cleanupDanmakuGlResources(ctx);
    for (int i = 0; i < DANMAKU_UPLOAD_QUEUE_CAP; i++) {
        free(dm->uploadQueue[i].data);
        dm->uploadQueue[i].data = NULL;
    }
    if (dm->lockReady) {
        pthread_mutex_destroy(&dm->lock);
        dm->lockReady = 0;
    }
}

static int drainAtlasUploads(DanmakuState* dm, DanmakuUploadSlotEvent* events, int maxEvents) {
    if (!dm->glReady || dm->uploadScratch == NULL) {
        return 0;
    }
    int didClear = 0;
    int uploadedPatches = 0;
    int uploadedBytes = 0;
    int eventCount = 0;

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, dm->atlasTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

    for (;;) {
        int px = 0, py = 0, pw = 0, ph = 0, pbytes = 0, pSlotIndex = -1;
        uint32_t pSlotGen = 0;
        int havePatch = 0;
        int wantClear = 0;

        pthread_mutex_lock(&dm->lock);
        if (dm->clearAtlasPending && !didClear) {
            wantClear = 1;
            dm->clearAtlasPending = 0;
        }
        else if (dm->uploadCount > 0 && uploadedPatches < DANMAKU_MAX_UPLOADS_PER_FRAME) {
            DanmakuAtlasPatch* slot = &dm->uploadQueue[dm->uploadHead];
            if (uploadedPatches > 0
                    && uploadedBytes + slot->byteCount > DANMAKU_UPLOAD_BYTES_PER_FRAME) {
                pthread_mutex_unlock(&dm->lock);
                break;
            }
            if (slot->data != NULL && slot->byteCount > 0
                    && slot->byteCount <= DANMAKU_PATCH_MAX_BYTES) {
                memcpy(dm->uploadScratch, slot->data, (size_t)slot->byteCount);
                px = slot->x;
                py = slot->y;
                pw = slot->w;
                ph = slot->h;
                pbytes = slot->byteCount;
                pSlotIndex = slot->slotIndex;
                pSlotGen = slot->slotGen;
                havePatch = 1;
            }
            slot->used = 0;
            dm->uploadHead = (dm->uploadHead + 1) % DANMAKU_UPLOAD_QUEUE_CAP;
            dm->uploadCount--;
        }
        pthread_mutex_unlock(&dm->lock);

        if (wantClear) {
            didClear = 1;
            glBindFramebuffer(GL_FRAMEBUFFER, dm->fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                                   dm->atlasTex, 0);
            glViewport(0, 0, DANMAKU_ATLAS_W, DANMAKU_ATLAS_H);
            glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (eventCount < maxEvents) {
                events[eventCount].isClear = 1;
                events[eventCount].slotIndex = -1;
                events[eventCount].slotGen = 0;
                eventCount++;
            }
            continue;
        }
        if (!havePatch) {
            break;
        }
        if (pw > 0 && ph > 0 && px >= 0 && py >= 0
                && px + pw <= DANMAKU_ATLAS_W && py + ph <= DANMAKU_ATLAS_H) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, px, py, pw, ph,
                            GL_RGBA, GL_UNSIGNED_BYTE, dm->uploadScratch);
            if (eventCount < maxEvents) {
                events[eventCount].isClear = 0;
                events[eventCount].slotIndex = pSlotIndex;
                events[eventCount].slotGen = pSlotGen;
                eventCount++;
            }
            uploadedBytes += pbytes;
        }
        uploadedPatches++;
    }
    return eventCount;
}

static void takePendingSnapshot(DanmakuState* dm, DanmakuSnapshotInput* outSnap) {
    memset(outSnap, 0, sizeof(*outSnap));
    if (!dm->lockReady) {
        return;
    }
    pthread_mutex_lock(&dm->lock);
    if (!dm->snapshotDirty) {
        pthread_mutex_unlock(&dm->lock);
        return;
    }
    dm->snapshotDirty = 0;
    outSnap->hasSnapshot = 1;
    outSnap->enabled = dm->pendingEnabled;
    outSnap->paused = dm->pendingPaused;
    outSnap->anchorTimelineMs = dm->pendingAnchorTimelineMs;
    outSnap->anchorMonoNs = dm->pendingAnchorMonoNs;
    outSnap->clockReset = dm->pendingClockReset;
    dm->pendingClockReset = 0;
    outSnap->animateDepth = dm->pendingAnimateDepth;

    dm->areaBotFrac = clampf(dm->pendingAreaBotFrac, 0.10f, 1.0f);
    dm->globalOpacity = clampf(dm->pendingGlobalOpacity, 0.0f, 1.0f);
    dm->curveMode = dm->pendingCurveMode;
    dm->customCurvePct = clampf(dm->pendingCustomCurvePct, 0.0f, 100.0f);
    dm->floatAuto = dm->pendingFloatAuto;
    dm->floatAutoOffset = dm->pendingFloatAutoOffset;
    dm->floatFixedSteps = dm->pendingFloatFixedSteps;
    dm->layerSpreadDeg = dm->pendingLayerSpreadDeg;

    int count = dm->pendingCount;
    if (count < 0) count = 0;
    if (count > DANMAKU_MAX_ITEMS) count = DANMAKU_MAX_ITEMS;
    outSnap->itemCount = count;
    if (count > 0) {
        memcpy(dm->snapScratch, dm->pendingItems, (size_t)count * sizeof(DanmakuNativeItem));
        outSnap->items = dm->snapScratch;
    }
    pthread_mutex_unlock(&dm->lock);
}

void danmakuRenderEyes(XrCtx* ctx, float screenFovDeg, float screenDistM,
                       float dt, int64_t frameMonoNs, int stereoActive) {
    if (ctx == NULL || !ctx->localMedia) {
        return;
    }
    DanmakuState* dm = &ctx->danmaku;

    // 1. Drain atlas uploads FIRST when GL is already initialized so newly uploaded slot
    //    generations are recorded BEFORE the incoming snapshot is absorbed.
    DanmakuUploadSlotEvent uploadEvents[DANMAKU_MAX_UPLOADS_PER_FRAME + 2];
    int uploadEventCount = dm->glReady
            ? drainAtlasUploads(dm, uploadEvents, DANMAKU_MAX_UPLOADS_PER_FRAME + 2)
            : 0;

    // 2. Pop pending snapshot metadata and step smoothed foreground content depth
    DanmakuSnapshotInput snap;
    takePendingSnapshot(dm, &snap);

    uint32_t rawBits = atomic_load_explicit(&ctx->depthContentPhiBits, memory_order_relaxed);
    float measuredPhi = 0.0f;
    memcpy(&measuredPhi, &rawBits, sizeof(measuredPhi));
    float targetContentPhi = overlayResolveContentPhi(stereoActive, ctx->stereoSourceFormat,
                                                      measuredPhi);
    ctx->danmakuContentPhiSmoothed = overlayStepContentDepth(ctx->danmakuContentPhiSmoothed,
                                                             targetContentPhi, dt);
    overlayComputeDepthLayers(ctx->danmakuContentPhiSmoothed, dm->floatAuto,
                              dm->floatAutoOffset, dm->floatFixedSteps,
                              dm->layerSpreadDeg, screenDistM, &snap.nextDepth);

    // 3. Staggered 4-stage lazy GL init: allocates 0 bytes when danmaku is OFF or unsupported.
    //    Runs 1 lightweight stage/frame once enabled; on completion immediately drains any
    //    patches queued during the 4 warmup frames before pipeline absorption.
    int wantEnabled = snap.hasSnapshot ? snap.enabled : dm->pipe.enabled;
    if (wantEnabled && !dm->glReady
            && !atomic_load_explicit(&dm->glFailed, memory_order_relaxed)) {
        if (danmakuStepInitGl(ctx)) {
            uploadEventCount = drainAtlasUploads(dm, uploadEvents,
                                                 DANMAKU_MAX_UPLOADS_PER_FRAME + 2);
        }
    }

    // 4. Execute shared pure-C render-frame pipeline:
    //    (1) land upload slot generations -> (2) absorb snapshot -> (3) gate & pack visible items
    int anyParallax = 0;
    int visible = danmakuPipelineStepFrame(&dm->pipe,
                                           uploadEvents, uploadEventCount,
                                           &snap,
                                           frameMonoNs, dt,
                                           dm->gpuInst, DANMAKU_MAX_ITEMS,
                                           &anyParallax);

    if (!dm->glReady || visible == 0) {
        dm->drawnEyes = 0;
        return;
    }

    int texH = (int)roundf((float)DANMAKU_TEX_H * dm->areaBotFrac);
    if (texH < 64) texH = 64;
    if (texH > DANMAKU_TEX_H) texH = DANMAKU_TEX_H;
    int eyeCount = anyParallax ? 2 : 1;

    glBindBuffer(GL_ARRAY_BUFFER, dm->instVbo);
    glBufferSubData(GL_ARRAY_BUFFER, 0, (GLsizeiptr)((size_t)visible * sizeof(DanmakuGpuInstance)),
                    dm->gpuInst);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    glUseProgram(dm->program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, dm->atlasTex);
    glUniform1i(dm->atlasUniform, 0);
    glUniform1f(dm->screenFovUniform, screenFovDeg > 1.0f ? screenFovDeg : 50.0f);
    glUniform1f(dm->globalOpacityUniform, dm->globalOpacity);
    glUniform1f(dm->featherUUniform,
                screenFovDeg > 1.0f ? (OVERLAY_EDGE_FEATHER_DEG / screenFovDeg) : 0.03f);

    if (ctx->srgbWriteControl) {
        glDisable(GL_FRAMEBUFFER_SRGB_EXT);
    }
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glBindVertexArray(dm->vao);
    glBindFramebuffer(GL_FRAMEBUFFER, dm->fbo);

    int drawn = 0;
    for (int eye = 0; eye < eyeCount; eye++) {
        uint32_t imgIdx = 0;
        XrSwapchainImageAcquireInfo acq = { XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO };
        if (!checkXr(xrAcquireSwapchainImage(dm->eyeSwapchain[eye], &acq, &imgIdx),
                     "acquire danmaku image")) {
            break;
        }
        XrSwapchainImageWaitInfo wait = { XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO };
        wait.timeout = XR_INFINITE_DURATION;
        if (!checkXr(xrWaitSwapchainImage(dm->eyeSwapchain[eye], &wait),
                     "wait danmaku image")) {
            XrSwapchainImageReleaseInfo rel = { XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO };
            xrReleaseSwapchainImage(dm->eyeSwapchain[eye], &rel);
            break;
        }

        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               dm->eyeImages[eye][imgIdx].image, 0);
        glViewport(0, 0, DANMAKU_TEX_W, texH);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        float eyeSign = eyeCount == 1 ? 0.0f : (eye == 0 ? 1.0f : -1.0f);
        glUniform1f(dm->eyeSignUniform, eyeSign);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, visible);

        XrSwapchainImageReleaseInfo rel = { XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO };
        xrReleaseSwapchainImage(dm->eyeSwapchain[eye], &rel);
        drawn++;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindVertexArray(0);
    glDisable(GL_BLEND);

    dm->drawnEyes = drawn;
    dm->drawnTexH = texH;
    dm->drawnAreaBotFrac = dm->areaBotFrac;
}

// ---- JNI bridge called strictly off render thread (from dedicated danmaku worker) ----

JNIEXPORT jint JNICALL
Java_com_limelight_binding_video_XrRenderer_nativeGetDanmakuUploadQueueFree(
        JNIEnv* env, jobject thiz, jlong handle) {
    XrCtx* ctx = (XrCtx*)(intptr_t)handle;
    if (ctx == NULL || !ctx->localMedia || !ctx->danmaku.lockReady
            || atomic_load_explicit(&ctx->danmaku.glFailed, memory_order_relaxed)) {
        return 0;
    }
    DanmakuState* dm = &ctx->danmaku;
    pthread_mutex_lock(&dm->lock);
    int freeSlots = DANMAKU_UPLOAD_QUEUE_CAP - dm->uploadCount;
    pthread_mutex_unlock(&dm->lock);
    return freeSlots > 0 ? freeSlots : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_video_XrRenderer_nativeUploadDanmakuAtlasPatch(
        JNIEnv* env, jobject thiz, jlong handle, jint x, jint y, jint w, jint h,
        jint slotIndex, jint slotGen, jobject rgbaPixels) {
    XrCtx* ctx = (XrCtx*)(intptr_t)handle;
    if (ctx == NULL || !ctx->localMedia || !ctx->danmaku.lockReady || rgbaPixels == NULL
            || atomic_load_explicit(&ctx->danmaku.glFailed, memory_order_relaxed)) {
        return JNI_FALSE;
    }
    if (slotIndex < 0 || slotIndex >= DANMAKU_MAX_SLOTS || slotGen <= 0) {
        return JNI_FALSE;
    }
    int bytes = w * h * 4;
    if (w <= 0 || h <= 0 || bytes <= 0 || bytes > DANMAKU_PATCH_MAX_BYTES
            || x < 0 || y < 0 || x + w > DANMAKU_ATLAS_W || y + h > DANMAKU_ATLAS_H) {
        return JNI_FALSE;
    }
    void* src = (*env)->GetDirectBufferAddress(env, rgbaPixels);
    jlong cap = (*env)->GetDirectBufferCapacity(env, rgbaPixels);
    if (src == NULL || cap < bytes) {
        return JNI_FALSE;
    }

    DanmakuState* dm = &ctx->danmaku;
    pthread_mutex_lock(&dm->lock);
    if (dm->uploadCount >= DANMAKU_UPLOAD_QUEUE_CAP) {
        pthread_mutex_unlock(&dm->lock);
        return JNI_FALSE;
    }
    DanmakuAtlasPatch* slot = &dm->uploadQueue[dm->uploadTail];
    if (slot->data == NULL) {
        slot->data = (unsigned char*)malloc(DANMAKU_PATCH_MAX_BYTES);
        if (slot->data == NULL) {
            pthread_mutex_unlock(&dm->lock);
            return JNI_FALSE;
        }
    }
    memcpy(slot->data, src, (size_t)bytes);
    slot->used = 1;
    slot->x = x;
    slot->y = y;
    slot->w = w;
    slot->h = h;
    slot->byteCount = bytes;
    slot->slotIndex = slotIndex;
    slot->slotGen = (uint32_t)slotGen;
    dm->uploadTail = (dm->uploadTail + 1) % DANMAKU_UPLOAD_QUEUE_CAP;
    dm->uploadCount++;
    pthread_mutex_unlock(&dm->lock);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_XrRenderer_nativeClearDanmakuAtlas(
        JNIEnv* env, jobject thiz, jlong handle) {
    XrCtx* ctx = (XrCtx*)(intptr_t)handle;
    if (ctx == NULL || !ctx->localMedia || !ctx->danmaku.lockReady) {
        return;
    }
    DanmakuState* dm = &ctx->danmaku;
    pthread_mutex_lock(&dm->lock);
    dm->clearAtlasPending = 1;
    dm->uploadHead = 0;
    dm->uploadTail = 0;
    dm->uploadCount = 0;
    pthread_mutex_unlock(&dm->lock);
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_XrRenderer_nativeSetDanmakuSnapshot(
        JNIEnv* env, jobject thiz, jlong handle, jboolean enabled, jboolean paused,
        jlong anchorTimelineMs, jlong anchorMonoNs, jboolean clockReset,
        jfloat areaBotFrac, jfloat globalOpacity,
        jint curveMode, jfloat customCurvePct, jboolean floatAuto,
        jint floatAutoOffset, jint floatFixedSteps, jfloat layerSpreadDeg,
        jboolean animateDepth, jfloatArray instancesArr, jint count) {
    XrCtx* ctx = (XrCtx*)(intptr_t)handle;
    if (ctx == NULL || !ctx->localMedia || !ctx->danmaku.lockReady) {
        return;
    }
    int n = count;
    if (n < 0) n = 0;
    if (n > DANMAKU_MAX_ITEMS) n = DANMAKU_MAX_ITEMS;

    float raw[DANMAKU_MAX_ITEMS * DANMAKU_INSTANCE_FLOATS];
    if (n > 0 && instancesArr != NULL) {
        jsize len = (*env)->GetArrayLength(env, instancesArr);
        if (len < n * DANMAKU_INSTANCE_FLOATS) {
            n = len / DANMAKU_INSTANCE_FLOATS;
        }
        if (n > 0) {
            (*env)->GetFloatArrayRegion(env, instancesArr, 0, n * DANMAKU_INSTANCE_FLOATS, raw);
        }
    }
    else {
        n = 0;
    }

    DanmakuState* dm = &ctx->danmaku;
    pthread_mutex_lock(&dm->lock);
    dm->pendingEnabled = enabled ? 1 : 0;
    dm->pendingPaused = paused ? 1 : 0;
    dm->pendingAnchorTimelineMs = (int64_t)anchorTimelineMs;
    dm->pendingAnchorMonoNs = (int64_t)anchorMonoNs;
    if (clockReset) {
        dm->pendingClockReset = 1;
    }
    dm->pendingAreaBotFrac = areaBotFrac;
    dm->pendingGlobalOpacity = globalOpacity;
    dm->pendingCurveMode = curveMode;
    dm->pendingCustomCurvePct = customCurvePct;
    dm->pendingFloatAuto = floatAuto ? 1 : 0;
    dm->pendingFloatAutoOffset = floatAutoOffset;
    dm->pendingFloatFixedSteps = floatFixedSteps;
    dm->pendingLayerSpreadDeg = layerSpreadDeg;
    dm->pendingAnimateDepth = animateDepth ? 1 : 0;
    dm->pendingCount = n;
    for (int i = 0; i < n; i++) {
        const float* f = raw + i * DANMAKU_INSTANCE_FLOATS;
        DanmakuNativeItem* dst = &dm->pendingItems[i];
        dst->x0 = f[0];
        dst->y0 = f[1];
        dst->w = f[2];
        dst->h = f[3];
        dst->velXPerSec = f[4];
        dst->u0 = f[5];
        dst->v0 = f[6];
        dst->du = f[7];
        dst->dv = f[8];
        dst->r = f[9];
        dst->g = f[10];
        dst->b = f[11];
        dst->baseAlpha = f[12];
        dst->spawnTimeSec = f[13];
        dst->durationSec = f[14];
        dst->fadeSec = f[15];
        int packedModeLayer = (int)f[16];
        dst->mode = packedModeLayer & 0xFF;
        dst->layerBand = (packedModeLayer >> 8) & 0xFF;
        dst->fixedPhiDeg = f[17];
        dst->animFromPhi = dst->fixedPhiDeg;
        dst->animToPhi = dst->fixedPhiDeg;
        dst->slotIndex = (int)f[18];
        dst->slotGen = f[19] > 0.0f ? (uint32_t)f[19] : 0u;
    }
    dm->snapshotDirty = 1;
    pthread_mutex_unlock(&dm->lock);
}
