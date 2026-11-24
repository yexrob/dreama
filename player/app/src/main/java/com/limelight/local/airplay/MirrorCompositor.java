package com.limelight.local.airplay;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sits between the decoder and the renderer. The decoder draws into a
 * texture of our own, and each picture is copied from there onto the
 * renderer's input surface, fitted into it with its shape kept.
 *
 * That way the renderer's screen keeps one size for the whole session
 * while the iPad turns (each turn is a new picture size), and the decoder
 * can start before the player is up, so the first key frame is not lost
 * while the VR session starts; the last picture is drawn again as soon as
 * the renderer's surface arrives.
 *
 * Everything GL runs on one thread of its own.
 */
final class MirrorCompositor implements SurfaceTexture.OnFrameAvailableListener {
    private static final String VERTEX =
            "attribute vec4 aPos;\n"
            + "attribute vec4 aUv;\n"
            + "uniform mat4 uTex;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  gl_Position = aPos;\n"
            + "  vUv = (uTex * aUv).xy;\n"
            + "}\n";
    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vUv;\n"
            + "uniform samplerExternalOES sTex;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(sTex, vUv);\n"
            + "}\n";
    // x, y, u, v for a strip over the whole viewport
    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
    };

    private final HandlerThread thread;
    private final Handler gl;

    // GL thread only from here on
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int program, texture, aPos, aUv, uTex;
    private FloatBuffer quad;
    private SurfaceTexture decoderTexture;
    private final float[] texMatrix = new float[16];
    private int picW, picH, outW, outH;
    private boolean haveFrame;

    // Written once on the GL thread before create() returns
    private volatile Surface decoderSurface;

    private MirrorCompositor() {
        thread = new HandlerThread("airplay-gl");
        thread.start();
        gl = new Handler(thread.getLooper());
    }

    /** A compositor with its GL set up, or null where that failed. */
    static MirrorCompositor create() {
        MirrorCompositor c = new MirrorCompositor();
        final boolean[] ok = new boolean[1];
        c.runBlocking(() -> ok[0] = c.setUp());
        if (!ok[0]) {
            c.release();
            return null;
        }
        return c;
    }

    /** Where the decoder draws. */
    Surface decoderSurface() {
        return decoderSurface;
    }

    /** Starts drawing onto out, which is w x h; the last picture at once. */
    void attach(final Surface out, final int w, final int h) {
        gl.post(() -> {
            dropWindow();
            int[] attrs = {EGL14.EGL_NONE};
            EGLSurface s = EGL14.eglCreateWindowSurface(display, config, out, attrs, 0);
            if (s == null || s == EGL14.EGL_NO_SURFACE) {
                LimeLog.warning("AirPlay: no window surface, EGL error 0x"
                        + Integer.toHexString(EGL14.eglGetError()));
                return;
            }
            window = s;
            outW = w;
            outH = h;
            if (EGL14.eglMakeCurrent(display, window, window, context)) {
                // The renderer reads its surface once a frame and may not
                // be reading at all while its session starts or ends, so a
                // new picture replaces a waiting one rather than wait
                EGL14.eglSwapInterval(display, 0);
            }
            draw();
        });
    }

    /** Stops drawing onto the surface attach() was given, before it goes. */
    void detach() {
        runBlocking(this::dropWindow);
    }

    /** The decoded picture's size, from the decoder's output format. */
    void setPictureSize(final int w, final int h) {
        gl.post(() -> {
            picW = w;
            picH = h;
        });
    }

    /** Black until the next picture: the stream ended or another took over. */
    void clearPicture() {
        gl.post(() -> {
            haveFrame = false;
            draw();
        });
    }

    void release() {
        runBlocking(this::tearDown);
        thread.quitSafely();
    }

    // ---- GL thread ----

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (decoderTexture == null || !makeCurrent()) {
            return;
        }
        try {
            decoderTexture.updateTexImage();
        } catch (RuntimeException e) {
            LimeLog.warning("AirPlay: updateTexImage: " + e);
            return;
        }
        decoderTexture.getTransformMatrix(texMatrix);
        haveFrame = true;
        draw();
    }

    private boolean setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
            LimeLog.warning("AirPlay: no EGL display");
            return false;
        }
        int[] attrs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] < 1) {
            LimeLog.warning("AirPlay: no EGL config");
            return false;
        }
        config = configs[0];
        int[] ctxAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            context = EGL14.EGL_NO_CONTEXT;
            LimeLog.warning("AirPlay: no EGL context");
            return false;
        }
        int[] pbAttrs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, pbAttrs, 0);
        if (pbuffer == null || pbuffer == EGL14.EGL_NO_SURFACE) {
            pbuffer = EGL14.EGL_NO_SURFACE;
            LimeLog.warning("AirPlay: no pbuffer");
            return false;
        }
        if (!makeCurrent()) {
            return false;
        }

        program = link(VERTEX, FRAGMENT);
        if (program == 0) {
            return false;
        }
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aUv = GLES20.glGetAttribLocation(program, "aUv");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texture = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        decoderTexture = new SurfaceTexture(texture);
        decoderTexture.setOnFrameAvailableListener(this, gl);
        decoderSurface = new Surface(decoderTexture);
        return true;
    }

    private boolean makeCurrent() {
        EGLSurface s = window != EGL14.EGL_NO_SURFACE ? window : pbuffer;
        if (s == EGL14.EGL_NO_SURFACE || context == EGL14.EGL_NO_CONTEXT) {
            return false;
        }
        return EGL14.eglMakeCurrent(display, s, s, context);
    }

    private void draw() {
        if (window == EGL14.EGL_NO_SURFACE || !makeCurrent()) {
            return;
        }
        GLES20.glViewport(0, 0, outW, outH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (haveFrame) {
            int[] r = MirrorGeometry.fit(picW, picH, outW, outH);
            GLES20.glViewport(r[0], r[1], r[2], r[3]);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aUv);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        if (!EGL14.eglSwapBuffers(display, window)) {
            int err = EGL14.eglGetError();
            // The renderer's surface went away under us; the player is
            // on its way out and detaches
            if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) {
                dropWindow();
            }
        }
    }

    private void dropWindow() {
        if (window == EGL14.EGL_NO_SURFACE) {
            return;
        }
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
        EGL14.eglDestroySurface(display, window);
        window = EGL14.EGL_NO_SURFACE;
    }

    private void tearDown() {
        dropWindow();
        if (decoderSurface != null) {
            decoderSurface.release();
            decoderSurface = null;
        }
        if (decoderTexture != null) {
            decoderTexture.setOnFrameAvailableListener(null);
            decoderTexture.release();
            decoderTexture = null;
        }
        if (context != EGL14.EGL_NO_CONTEXT && makeCurrent()) {
            if (texture != 0) {
                GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
        }
        texture = 0;
        program = 0;
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, pbuffer);
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context);
            }
            // No eglTerminate: the display is the process's, which the
            // renderer's own context is on
            EGL14.eglReleaseThread();
        }
        pbuffer = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
        display = EGL14.EGL_NO_DISPLAY;
    }

    private static int link(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) {
            return 0;
        }
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            LimeLog.warning("AirPlay: link: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int compile(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.warning("AirPlay: shader: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private void runBlocking(final Runnable r) {
        if (Thread.currentThread() == thread) {
            r.run();
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        if (!gl.post(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        })) {
            return;
        }
        try {
            if (!done.await(2, TimeUnit.SECONDS)) {
                LimeLog.warning("AirPlay: GL thread did not answer in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
