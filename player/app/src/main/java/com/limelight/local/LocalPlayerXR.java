package com.limelight.local;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.binding.video.XrRenderer;
import com.limelight.binding.video.XrStartFailure;
import com.limelight.local.media.WatchHistory;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Plays a local video on MoonlightXR's virtual screen. The renderer hands out
 * the Surface its SurfaceTexture is fed from; here MediaPlayer decodes into it
 * instead of the stream decoder, and everything after that — the depth model,
 * the warp per eye, the screen, the pointer — is the renderer's own.
 *
 * Controls: trigger on the screen plays and pauses, the thumbstick seeks ten
 * seconds a click, and the bar's exit button leaves.
 */
public class LocalPlayerXR extends Activity
        implements XrRenderer.InputListener, XrRenderer.SessionListener {

    private static final int SEEK_STEP_MS = 10_000;
    // An Authorization header for an http source (the WebDAV share)
    public static final String EXTRA_AUTH = "auth";
    public static final String EXTRA_TITLE = "title";
    // Where to start, and what the wall files the watch progress under
    public static final String EXTRA_START_MS = "start_ms";
    public static final String EXTRA_SOURCE_ID = "source_id";
    public static final String EXTRA_ITEM_ID = "item_id";

    private Map<String, String> headers = Collections.emptyMap();

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile XrRenderer renderer;
    private MediaPlayer player;
    private boolean prepared;
    private boolean finishing;

    // The transport strip and where the pointer is on the picture. The
    // pointer fields are written on the frame loop thread.
    private TransportBar bar;
    private volatile float pointerU = -1f, pointerV = -1f;
    private volatile long pointerMovedMs;
    private float lastU, lastV;
    private static final long BAR_LINGER_MS = 3000;
    private static final long BAR_TICK_MS = 200;
    private final Runnable barTick = this::tickBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }

        String auth = getIntent().getStringExtra(EXTRA_AUTH);
        if (auth != null) {
            headers = new HashMap<>();
            headers.put("Authorization", auth);
        }
        new Thread(() -> startSession(uri), "depth3d-start").start();
    }

    // Off the main thread: start() waits on the OpenXR session's init
    private void startSession(Uri uri) {
        int[] size = videoSize(uri);
        float fps = frameRate(uri);

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
        prefs.enableVrMode = true;
        prefs.width = size[0];
        prefs.height = size[1];
        // The display rate is picked from the content's rate: 24 and 30 land
        // on 72 and 90 Hz rather than a rate that judders against the video
        prefs.fps = Math.max(1, Math.round(fps));
        // The Gen 1 profile's 12 maps a second is a stream's budget; a film
        // held 20 on a Pico 4 with no missed refreshes
        prefs.vrDepthRate = Math.max(prefs.vrDepthRate, com.limelight.binding.video.XrShared.DEPTH_RATE_DEFAULT);
        LimeLog.info(String.format(Locale.US, "depth3d: %s %dx%d @ %.2f", uri, size[0], size[1], fps));

        XrRenderer r = new XrRenderer();
        r.setLocalMedia(true);
        r.setInputListener(this);
        r.setSessionListener(this);
        if (!r.start(this, size[0], size[1], prefs)) {
            XrStartFailure failure = r.getStartFailure();
            onVrUnavailable(failure != null ? failure : XrStartFailure.unknown());
            return;
        }
        renderer = r;
        bar = new TransportBar(size[0], size[1]);
        main.post(() -> startPlayback(uri));
    }

    private void startPlayback(Uri uri) {
        XrRenderer r = renderer;
        if (r == null || isFinishing() || isDestroyed()) {
            return;
        }
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(this, uri, headers);
            mp.setSurface(r.getInputSurface());
            mp.setLooping(true);
            if (isHttp(uri)) {
                r.showNotice("正在从网盘缓冲…", getIntent().getStringExtra(EXTRA_TITLE));
                mp.setOnInfoListener((p, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        r.showNotice("缓冲中…", null);
                    }
                    return false;
                });
            }
            mp.setOnPreparedListener(p -> {
                prepared = true;
                long start = getIntent().getLongExtra(EXTRA_START_MS, 0);
                // A resume point in the last seconds would only loop round to the start
                if (start > 0 && start < p.getDuration() - 5000) {
                    p.seekTo((int) start);
                }
                p.start();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                r.showNotice("播放出错", "what=" + what + " extra=" + extra);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            LimeLog.warning("depth3d: cannot play " + uri + ": " + e);
            r.showNotice("无法播放这个视频", e.getMessage());
            mp.release();
            return;
        }
        player = mp;
        main.post(barTick);
    }

    private int[] videoSize(Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if (isHttp(uri)) {
                mmr.setDataSource(uri.toString(), headers);
            } else {
                mmr.setDataSource(this, uri);
            }
            int w = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 1920);
            int h = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 1080);
            int rot = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            return (rot == 90 || rot == 270) ? new int[]{h, w} : new int[]{w, h};
        } catch (RuntimeException e) {
            return new int[]{1920, 1080};
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
    }

    private float frameRate(Uri uri) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(this, uri, headers);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    return f.getInteger(MediaFormat.KEY_FRAME_RATE);
                }
            }
        } catch (Exception ignored) {
        } finally {
            ex.release();
        }
        return 30f;
    }

    private static boolean isHttp(Uri uri) {
        String s = uri.getScheme();
        return "http".equals(s) || "https".equals(s);
    }

    private static int parse(String s, int fallback) {
        try {
            return s != null ? Integer.parseInt(s) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String clock(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---- input from the session, on the frame loop thread ----

    @Override
    public void onVrButton(int button, boolean down) {
        if (button != 0 || down) {
            return;
        }
        final float u = pointerU, v = pointerV;
        main.post(() -> {
            MediaPlayer mp = player;
            TransportBar b = bar;
            if (mp == null || b == null) {
                return;
            }
            pointerMovedMs = System.currentTimeMillis();
            try {
                float x = b.texX(u, v);
                float f = Float.isNaN(x) || TransportBar.onButton(x)
                        ? Float.NaN : TransportBar.trackFraction(x);
                if (!Float.isNaN(f)) {
                    mp.seekTo((int) (f * mp.getDuration()));
                } else if (mp.isPlaying()) {
                    mp.pause();
                    saveProgress();
                } else {
                    mp.start();
                }
            } catch (IllegalStateException ignored) {
            }
            tickBar();
        });
    }

    // Shown while paused, or for a while after the pointer moves; redrawn a
    // few times a second while it shows
    private void tickBar() {
        main.removeCallbacks(barTick);
        MediaPlayer mp = player;
        XrRenderer r = renderer;
        TransportBar b = bar;
        if (mp == null || r == null || b == null) {
            return;
        }
        try {
            boolean playing = mp.isPlaying();
            long since = System.currentTimeMillis() - pointerMovedMs;
            boolean show = !playing || since < BAR_LINGER_MS;
            r.setTransportVisible(show);
            if (show) {
                float x = since < BAR_LINGER_MS ? b.texX(pointerU, pointerV) : Float.NaN;
                boolean onBtn = !Float.isNaN(x) && TransportBar.onButton(x);
                float hover = Float.isNaN(x) || onBtn ? Float.NaN : TransportBar.trackFraction(x);
                r.postTransport(b.draw(playing, mp.getCurrentPosition(), mp.getDuration(),
                        hover, onBtn, getIntent().getStringExtra(EXTRA_TITLE)));
            }
        } catch (IllegalStateException ignored) {
        }
        main.postDelayed(barTick, BAR_TICK_MS);
    }

    @Override
    public void onVrScroll(int clicks) {
        main.post(() -> {
            MediaPlayer mp = player;
            XrRenderer r = renderer;
            if (mp == null || r == null) {
                return;
            }
            try {
                int to = Math.max(0, Math.min(mp.getDuration() - 1000,
                        mp.getCurrentPosition() - clicks * SEEK_STEP_MS));
                mp.seekTo(to);
                pointerMovedMs = System.currentTimeMillis();
                tickBar();
            } catch (IllegalStateException ignored) {
            }
        });
    }

    @Override
    public void onVrExit() {
        main.post(this::finish);
    }

    @Override
    public void onVrPointerMove(float u, float v) {
        // Every frame while the ray is on the picture; only a real move
        // brings the strip up, not the hand's own tremor
        if (Math.abs(u - lastU) > 0.004f || Math.abs(v - lastV) > 0.004f) {
            lastU = u;
            lastV = v;
            pointerMovedMs = System.currentTimeMillis();
        }
        pointerU = u;
        pointerV = v;
    }

    @Override public void onVrMouseMove(int dx, int dy) { }
    @Override public void onVrGamepadPlugged(boolean plugged) { }
    @Override public void onVrGamepadState(int buttons, int lt, int rt, int lx, int ly, int rx, int ry) { }
    @Override public void onVrKey(int code, int modifiers) { }
    @Override public void onVrModifiers(int modifiers) { }

    // ---- session ----

    @Override
    public void onVrUnavailable(XrStartFailure failure) {
        LimeLog.warning("depth3d: VR unavailable: " + failure);
        main.post(() -> {
            Toast.makeText(this, "无法进入 VR：" + failure, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    @Override
    public void onVrSessionEnded(String reason) {
        LimeLog.info("depth3d: session ended: " + reason);
        main.post(this::finish);
    }

    @Override
    public void onVrSessionAway() {
        main.post(() -> {
            if (player != null && player.isPlaying()) {
                player.pause();
                saveProgress();
            }
        });
    }

    // For 继续观看. Only when the wall said which item this is: an adb launch
    // with a bare uri has nothing to file it under.
    private void saveProgress() {
        MediaPlayer mp = player;
        Intent in = getIntent();
        String source = in.getStringExtra(EXTRA_SOURCE_ID);
        String item = in.getStringExtra(EXTRA_ITEM_ID);
        if (mp == null || !prepared || source == null || item == null) {
            return;
        }
        try {
            WatchHistory.save(this, source, item, in.getStringExtra(EXTRA_TITLE),
                    mp.getCurrentPosition(), mp.getDuration());
        } catch (IllegalStateException ignored) {
        }
    }

    // ---- lifecycle ----

    @Override
    protected void onStop() {
        super.onStop();
        // Before the first focus a boundary prompt can stop us without the
        // user having left; after it, a stop means they did
        saveProgress();
        XrRenderer r = renderer;
        if (r != null && r.hasBeenFocused()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (finishing) {
            return;
        }
        finishing = true;
        main.removeCallbacks(barTick);
        if (player != null) {
            saveProgress();
            player.release();
            player = null;
        }
        XrRenderer r = renderer;
        renderer = null;
        if (r != null) {
            r.prepareForStop();
            r.cleanup();
        }
    }
}
