package com.limelight.local.airplay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.binding.video.XrRenderer;
import com.limelight.binding.video.XrShared;
import com.limelight.binding.video.XrStartFailure;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Locale;

/**
 * The mirrored screen in VR, the way LocalPlayerXR shows a film: the
 * renderer's input surface takes the picture and everything after it, the
 * depth model, the warp per eye, the screen, is the renderer's. There is
 * nothing to seek, so there is no transport strip.
 *
 * The renderer's screen keeps the shape the session started with (the
 * first picture's, turned landscape); MirrorCompositor fits each picture
 * into it, so the iPad turning shows as bars rather than a restart.
 *
 * AirPlayService starts this when a device begins mirroring and the
 * activity closes when the mirroring ends. Leaving it, by the bar's exit or
 * by taking the headset elsewhere, ends the mirroring too.
 */
public class AirPlayPlayerXR extends Activity
        implements XrRenderer.InputListener, XrRenderer.SessionListener, AirPlayState.Listener {
    static final String EXTRA_SESSION = "session";
    // The largest screen the renderer is given; the device is offered the
    // same (AirPlayService.OFFER_WIDTH x OFFER_HEIGHT)
    private static final int CANVAS_MAX_W = AirPlayService.OFFER_WIDTH;
    private static final int CANVAS_MAX_H = AirPlayService.OFFER_HEIGHT;
    // How long the start waits for the device to say how big its picture is
    private static final long SIZE_WAIT_MS = 1500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    // Under lock
    private XrRenderer renderer;
    private MirrorVideo attached;
    private boolean destroyed;

    private volatile int session;
    // Closing because the mirroring ended, not because the user left
    private volatile boolean ended;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        session = getIntent().getIntExtra(EXTRA_SESSION, -1);
        AirPlayState.Status now = AirPlayState.current();
        if (now.getPhase() != AirPlayState.Phase.MIRRORING) {
            // From a stale notification, or the stream already ended
            ended = true;
            finish();
            return;
        }
        session = now.getSession();
        AirPlayState.addListener(this);
        new Thread(this::startSession, "airplay-start").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    // Off the main thread: start() waits for the OpenXR session
    private void startSession() {
        float[] size = waitForSize();
        int[] canvas = MirrorGeometry.canvasFor(size[0], size[1], CANVAS_MAX_W, CANVAS_MAX_H);
        int w = canvas[0];
        int h = canvas[1];

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
        prefs.enableVrMode = true;
        prefs.width = w;
        prefs.height = h;
        // The rate the device is asked for; the display rate is picked from it
        prefs.fps = AirPlayService.OFFER_FPS;
        // A mirrored screen runs like a film, not a game stream
        prefs.vrDepthRate = Math.max(prefs.vrDepthRate, XrShared.DEPTH_RATE_DEFAULT);
        LimeLog.info(String.format(Locale.ROOT, "AirPlay: player %dx%d for a %.0fx%.0f picture",
                w, h, size[0], size[1]));

        XrRenderer r = new XrRenderer();
        r.setLocalMedia(true);
        r.setInputListener(this);
        r.setSessionListener(this);
        synchronized (lock) {
            if (destroyed) {
                return;
            }
        }
        if (!r.start(this, w, h, prefs)) {
            XrStartFailure failure = r.getStartFailure();
            onVrUnavailable(failure != null ? failure : XrStartFailure.unknown());
            return;
        }
        boolean late;
        MirrorVideo v = AirPlayService.video();
        synchronized (lock) {
            late = destroyed;
            if (!late) {
                renderer = r;
                if (v != null) {
                    v.attach(r.getInputSurface(), w, h);
                    attached = v;
                }
            }
        }
        if (late) {
            // Closed while the session started
            r.prepareForStop();
            r.cleanup();
            return;
        }
        if (v == null) {
            r.showNotice("画面解码没有准备好", null);
            return;
        }
        String who = AirPlayState.current().getClient();
        r.showNotice(AirPlayState.phrase("正在显示", who, "的屏幕"), null);
    }

    private static float[] waitForSize() {
        long until = System.currentTimeMillis() + SIZE_WAIT_MS;
        while (true) {
            float[] s = AirPlayService.reportedSize();
            if (s != null) {
                return s;
            }
            if (System.currentTimeMillis() >= until) {
                return new float[]{0f, 0f};
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new float[]{0f, 0f};
            }
        }
    }

    // ---- the receiver ----

    @Override
    public void onStatus(AirPlayState.Status status) {
        if (status.getPhase() != AirPlayState.Phase.MIRRORING) {
            ended = true;
            main.post(this::finish);
        } else if (!ended) {
            // Another device took over within the grace: same screen
            session = status.getSession();
        }
    }

    // ---- input: only leaving does anything ----

    @Override
    public void onVrExit() {
        main.post(this::finish);
    }

    @Override public void onVrPointerMove(float u, float v) { }
    @Override public void onVrMouseMove(int dx, int dy) { }
    @Override public void onVrButton(int button, boolean down) { }
    @Override public void onVrScroll(int clicks) { }
    @Override public void onVrGamepadPlugged(boolean plugged) { }
    @Override public void onVrGamepadState(int buttons, int lt, int rt, int lx, int ly, int rx, int ry) { }
    @Override public void onVrKey(int code, int modifiers) { }
    @Override public void onVrModifiers(int modifiers) { }

    // ---- session ----

    @Override
    public void onVrUnavailable(XrStartFailure failure) {
        LimeLog.warning("AirPlay: VR unavailable: " + failure);
        main.post(() -> {
            Toast.makeText(this, "无法进入沉浸式 3D 画面，请重试", Toast.LENGTH_LONG).show();
            finish();
        });
    }

    @Override
    public void onVrSessionEnded(String reason) {
        LimeLog.info("AirPlay: VR session ended: " + reason);
        main.post(this::finish);
    }

    // ---- lifecycle ----

    @Override
    protected void onStop() {
        super.onStop();
        XrRenderer r;
        synchronized (lock) {
            r = renderer;
        }
        // Before the first focus a boundary prompt can stop us without the
        // user having left; after it, a stop means they did
        if (r != null && r.hasBeenFocused()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AirPlayState.removeListener(this);
        XrRenderer r;
        MirrorVideo v;
        synchronized (lock) {
            destroyed = true;
            r = renderer;
            v = attached;
            renderer = null;
            attached = null;
        }
        // The compositor lets go of the renderer's surface before the
        // renderer frees it
        if (v != null) {
            v.detach();
        }
        if (r != null) {
            r.prepareForStop();
            r.cleanup();
        }
        AirPlayState.Status now = AirPlayState.current();
        if (!ended && now.getPhase() == AirPlayState.Phase.MIRRORING && now.getSession() == session) {
            AirPlayService.disconnect();
        }
    }
}
