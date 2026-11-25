// GPU-accelerated instanced danmaku compositor & JNI bridge (ux-spec.md §6.10, §7).
// Render thread never allocates heap memory, never blocks on Java rasterization,
// uploads bounded partial RG8 atlas patches (<= 6 patches & <= 192 KB / frame),
// and renders all active danmaku in 1 instanced draw per eye (0 draws & 0 layers
// when no danmaku are visible).

#include "xr_renderer.h"
#include "xr_shaders.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

static float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static float phiForBand(const OverlayDepthLayers* d, int band) {
    if (band == DANMAKU_LAYER_NEAR) return d->nearPhiDeg;
    if (band == DANMAKU_LAYER_MID)  return d->midPhiDeg;
    return d->farPhiDeg;
}

static int initDanmakuGl(XrCtx* ctx) {
    DanmakuState* dm = &ctx->danmaku;
    if (dm->glReady) {
        return 1;
    }
    if (dm->glFailed) {
        return 0;
    }

    GLuint vs = compileShader(GL_VERTEX_SHADER, DANMAKU_VERTEX_SRC);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, DANMAKU_FRAGMENT_SRC);
    if (vs == 0 || fs == 0) {
        if (vs != 0) glDeleteShader(vs);
        if (fs != 0) glDeleteShader(fs);
        dm->glFailed = 1;
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
        dm->glFailed = 1;
        return 0;
    }
    dm->program = prog;
    dm->eyeSignUniform = glGetUniformLocation(prog, "u_eyeSign");
    dm->screenFovUniform = glGetUniformLocation(prog, "u_screenFovDeg");
    dm->globalOpacityUniform = glGetUniformLocation(prog, "u_globalOpacity");
    dm->featherUUniform = glGetUniformLocation(prog, "u_featherU");
    dm->atlasUniform = glGetUniformLocation(prog, "u_atlas");

    // 2048x2048 2-channel GL_RG8 atlas (8 MB VRAM): R = fill alpha, G = stroke+shadow alpha
    glGenTextures(1, &dm->atlasTex);
    glBindTexture(GL_TEXTURE_2D, dm->atlasTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, DANMAKU_ATLAS_W, DANMAKU_ATLAS_H, 0,
                 GL_RG, GL_UNSIGNED_BYTE, NULL);

    // Static unit-quad VBO + dynamic instanced VBO inside one VAO
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

    glGenFramebuffers(1, &dm->fbo);

    for (int eye = 0; eye < 2; eye++) {
        if (!createArtSwapchain(ctx, DANMAKU_TEX_W, DANMAKU_TEX_H,
                                eye == 0 ? "danmaku left swapchain" : "danmaku right swapchain",
                                &dm->eyeSwapchain[eye], &dm->eyeImages[eye],
                                &dm->eyeImageCount[eye])) {
            LOGW("depth3d: danmaku eye %d swapchain creation failed", eye);
            dm->glFailed = 1;
            return 0;
        }
    }

    dm->uploadScratch = (unsigned char*)malloc(DANMAKU_PATCH_MAX_BYTES);
    if (dm->uploadScratch == NULL) {
        dm->glFailed = 1;
        return 0;
    }

    dm->glReady = 1;
    LOGEV("depth3d: danmaku GPU atlas (%dx%d RG8) and eye swapchains (%dx%d x2) ready",
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
    }
    free(dm->uploadScratch);
    dm->uploadScratch = NULL;
    for (int i = 0; i < DANMAKU_UPLOAD_QUEUE_CAP; i++) {
        free(dm->uploadQueue[i].data);
        dm->uploadQueue[i].data = NULL;
    }
    if (dm->lockReady) {
        pthread_mutex_destroy(&dm->lock);
        dm->lockReady = 0;
    }
}

static void drainAtlasUploads(DanmakuState* dm) {
    if (!dm->glReady || dm->uploadScratch == NULL) {
        return;
    }
    int didClear = 0;
    int uploadedPatches = 0;
    int uploadedBytes = 0;

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, dm->atlasTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    for (;;) {
        int px = 0, py = 0, pw = 0, ph = 0, pbytes = 0;
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
                havePatch = 1;
            }
            slot->used = 0;
            dm->uploadHead = (dm->uploadHead + 1) % DANMAKU_UPLOAD_QUEUE_CAP;
            dm->uploadCount--;
        }
        pthread_mutex_unlock(&dm->lock);

        if (wantClear) {
            didClear = 1;
            // Clear atlas texture to zero via FBO clear (instant GPU clear, zero CPU buffer!)
            glBindFramebuffer(GL_FRAMEBUFFER, dm->fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                                   dm->atlasTex, 0);
            glViewport(0, 0, DANMAKU_ATLAS_W, DANMAKU_ATLAS_H);
            glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            continue;
        }
        if (!havePatch) {
            break;
        }
        if (pw > 0 && ph > 0 && px >= 0 && py >= 0
                && px + pw <= DANMAKU_ATLAS_W && py + ph <= DANMAKU_ATLAS_H) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, px, py, pw, ph,
                            GL_RG, GL_UNSIGNED_BYTE, dm->uploadScratch);
            uploadedBytes += pbytes;
        }
        uploadedPatches++;
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
}

static void absorbSnapshot(DanmakuState* dm, float smoothedContentPhi, float screenDistM,
                           OverlayDepthLayers* outDepthNow) {
    if (!dm->lockReady) {
        return;
    }
    pthread_mutex_lock(&dm->lock);
    if (!dm->snapshotDirty) {
        pthread_mutex_unlock(&dm->lock);
        return;
    }
    dm->snapshotDirty = 0;
    dm->enabled = dm->pendingEnabled;
    dm->paused = dm->pendingPaused;
    dm->clockSec = dm->pendingClockSec;
    dm->areaBotFrac = clampf(dm->pendingAreaBotFrac, 0.10f, 1.0f);
    dm->globalOpacity = clampf(dm->pendingGlobalOpacity, 0.0f, 1.0f);
    dm->curveMode = dm->pendingCurveMode;
    dm->customCurvePct = clampf(dm->pendingCustomCurvePct, 0.0f, 100.0f);
    dm->floatAuto = dm->pendingFloatAuto;
    dm->floatAutoOffset = dm->pendingFloatAutoOffset;
    dm->floatFixedSteps = dm->pendingFloatFixedSteps;
    dm->layerSpreadDeg = dm->pendingLayerSpreadDeg;
    int animateDepth = dm->pendingAnimateDepth;
    int count = dm->pendingCount;
    if (count < 0) count = 0;
    if (count > DANMAKU_MAX_ITEMS) count = DANMAKU_MAX_ITEMS;

    // Preserve previous item disparities for smooth 200 ms animation if requested
    float prevFar = dm->currentDepth.farPhiDeg;
    float prevMid = dm->currentDepth.midPhiDeg;
    float prevNear = dm->currentDepth.nearPhiDeg;

    dm->itemCount = count;
    if (count > 0) {
        memcpy(dm->items, dm->pendingItems, (size_t)count * sizeof(DanmakuNativeItem));
    }
    pthread_mutex_unlock(&dm->lock);

    // Recompute target depth layers using the NEWLY absorbed float/layer settings
    overlayComputeDepthLayers(smoothedContentPhi, dm->floatAuto,
                              dm->floatAutoOffset, dm->floatFixedSteps,
                              dm->layerSpreadDeg, screenDistM, outDepthNow);

    if (animateDepth && count > 0) {
        dm->depthAnimActive = 1;
        dm->depthAnimElapsedS = 0.0f;
        for (int i = 0; i < dm->itemCount; i++) {
            float from = dm->items[i].layerBand == DANMAKU_LAYER_NEAR ? prevNear
                    : (dm->items[i].layerBand == DANMAKU_LAYER_MID ? prevMid : prevFar);
            dm->items[i].animFromPhi = from;
            dm->items[i].animToPhi = phiForBand(outDepthNow, dm->items[i].layerBand);
        }
    }
    else {
        dm->depthAnimActive = 0;
        dm->depthAnimElapsedS = 0.0f;
    }
}

void danmakuRenderEyes(XrCtx* ctx, float screenFovDeg, float screenDistM,
                       float dt, int stereoActive) {
    if (ctx == NULL || !ctx->localMedia) {
        return;
    }
    DanmakuState* dm = &ctx->danmaku;

    // Step smoothed foreground content depth (0 in 2D, 0.25 deg in native SBS/OU,
    // 64-bin 98th-percentile histogram in AI 2D-to-3D)
    uint32_t rawBits = atomic_load_explicit(&ctx->depthContentPhiBits, memory_order_relaxed);
    float measuredPhi = 0.0f;
    memcpy(&measuredPhi, &rawBits, sizeof(measuredPhi));
    float targetContentPhi = overlayResolveContentPhi(stereoActive, ctx->stereoSourceFormat,
                                                      measuredPhi);
    ctx->danmakuContentPhiSmoothed = overlayStepContentDepth(ctx->danmakuContentPhiSmoothed,
                                                             targetContentPhi, dt);

    OverlayDepthLayers depthNow;
    overlayComputeDepthLayers(ctx->danmakuContentPhiSmoothed, dm->floatAuto,
                              dm->floatAutoOffset, dm->floatFixedSteps,
                              dm->layerSpreadDeg, screenDistM, &depthNow);
    absorbSnapshot(dm, ctx->danmakuContentPhiSmoothed, screenDistM, &depthNow);
    dm->currentDepth = depthNow;

    if (!dm->enabled || dm->itemCount <= 0) {
        // Still drain pending uploads lazily if GL is already initialized so queue stays fresh
        if (dm->glReady) {
            drainAtlasUploads(dm);
        }
        dm->drawnEyes = 0;
        return;
    }

    if (!initDanmakuGl(ctx)) {
        dm->drawnEyes = 0;
        return;
    }

    drainAtlasUploads(dm);

    // Advance smooth real-time display clock when playing (§6.10: transit is real-time)
    if (!dm->paused && dt > 0.0f && dt < 0.25f) {
        dm->clockSec += dt;
    }

    float animT = 1.0f;
    if (dm->depthAnimActive) {
        dm->depthAnimElapsedS += (dt > 0.0f ? dt : 0.0f);
        if (dm->depthAnimElapsedS >= OVERLAY_DEPTH_ANIM_S) {
            dm->depthAnimActive = 0;
            animT = 1.0f;
        }
        else {
            float t = dm->depthAnimElapsedS / OVERLAY_DEPTH_ANIM_S;
            float u = 1.0f - t;
            animT = 1.0f - u * u * u; // cubic ease-out (§5.6)
        }
    }

    // Build GPU instance array on render thread (zero allocations)
    int visible = 0;
    int anyParallax = 0;
    for (int i = 0; i < dm->itemCount && visible < DANMAKU_MAX_ITEMS; i++) {
        DanmakuNativeItem* it = &dm->items[i];
        float elapsed = dm->clockSec - it->spawnTimeSec;
        if (elapsed < -0.05f || (it->durationSec > 0.0f && elapsed > it->durationSec)) {
            continue;
        }
        if (elapsed < 0.0f) {
            elapsed = 0.0f;
        }

        float x = it->x0 + it->velXPerSec * elapsed;
        // Cull scrolled items completely off either side (allow 0.05 margin for disparity shift)
        if (x + it->w < -0.05f || x > 1.05f) {
            continue;
        }

        float alpha = it->baseAlpha;
        if (it->fadeSec > 0.001f && it->durationSec > 0.0f) {
            float fadeIn = clampf(elapsed / it->fadeSec, 0.0f, 1.0f);
            float fadeOut = clampf((it->durationSec - elapsed) / it->fadeSec, 0.0f, 1.0f);
            alpha *= (fadeIn < fadeOut ? fadeIn : fadeOut);
        }
        if (alpha <= 0.004f) {
            continue;
        }

        float phi = it->fixedPhiDeg;
        if (dm->depthAnimActive) {
            phi = it->animFromPhi + (it->animToPhi - it->animFromPhi) * animT;
        }
        // Always enforce comfort ceiling at current distance
        if (phi > depthNow.maxPhiDeg) {
            phi = depthNow.maxPhiDeg;
        }
        if (phi < 0.0f) {
            phi = 0.0f;
        }
        if (phi > 0.002f) {
            anyParallax = 1;
        }

        DanmakuGpuInstance* dst = &dm->gpuInst[visible++];
        dst->rect[0] = x;
        dst->rect[1] = it->y0;
        dst->rect[2] = it->w;
        dst->rect[3] = it->h;
        dst->uv[0] = it->u0;
        dst->uv[1] = it->v0;
        dst->uv[2] = it->du;
        dst->uv[3] = it->dv;
        dst->colorAlpha[0] = it->r;
        dst->colorAlpha[1] = it->g;
        dst->colorAlpha[2] = it->b;
        dst->colorAlpha[3] = alpha;
        dst->phiDeg = phi;
    }

    if (visible == 0) {
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
        xrWaitSwapchainImage(dm->eyeSwapchain[eye], &wait);

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

JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_video_XrRenderer_nativeUploadDanmakuAtlasPatch(
        JNIEnv* env, jobject thiz, jlong handle, jint x, jint y, jint w, jint h,
        jobject rgPixels) {
    XrCtx* ctx = (XrCtx*)(intptr_t)handle;
    if (ctx == NULL || !ctx->localMedia || !ctx->danmaku.lockReady || rgPixels == NULL) {
        return JNI_FALSE;
    }
    int bytes = w * h * 2;
    if (w <= 0 || h <= 0 || bytes <= 0 || bytes > DANMAKU_PATCH_MAX_BYTES
            || x < 0 || y < 0 || x + w > DANMAKU_ATLAS_W || y + h > DANMAKU_ATLAS_H) {
        return JNI_FALSE;
    }
    void* src = (*env)->GetDirectBufferAddress(env, rgPixels);
    jlong cap = (*env)->GetDirectBufferCapacity(env, rgPixels);
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
        jfloat clockSec, jfloat areaBotFrac, jfloat globalOpacity,
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
    dm->pendingClockSec = clockSec;
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
    }
    dm->snapshotDirty = 1;
    pthread_mutex_unlock(&dm->lock);
}
