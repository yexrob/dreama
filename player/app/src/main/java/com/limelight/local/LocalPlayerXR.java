package com.limelight.local;

import android.app.Activity;
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
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Locale;

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

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile XrRenderer renderer;
    private MediaPlayer player;
    private boolean finishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
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
        LimeLog.info(String.format(Locale.US, "depth3d: %s %dx%d @ %.2f", uri, size[0], size[1], fps));

        XrRenderer r = new XrRenderer();
        r.setInputListener(this);
        r.setSessionListener(this);
        if (!r.start(this, size[0], size[1], prefs)) {
            XrStartFailure failure = r.getStartFailure();
            onVrUnavailable(failure != null ? failure : XrStartFailure.unknown());
            return;
        }
        renderer = r;
        main.post(() -> startPlayback(uri));
    }

    private void startPlayback(Uri uri) {
        XrRenderer r = renderer;
        if (r == null || isFinishing() || isDestroyed()) {
            return;
        }
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(this, uri);
            mp.setSurface(r.getInputSurface());
            mp.setLooping(true);
            mp.setOnPreparedListener(MediaPlayer::start);
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
    }

    private int[] videoSize(Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
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
            ex.setDataSource(this, uri, null);
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
        main.post(() -> {
            MediaPlayer mp = player;
            XrRenderer r = renderer;
            if (mp == null || r == null) {
                return;
            }
            try {
                if (mp.isPlaying()) {
                    mp.pause();
                    r.showNotice("已暂停", clock(mp.getCurrentPosition()) + " / " + clock(mp.getDuration()));
                } else {
                    mp.start();
                }
            } catch (IllegalStateException ignored) {
            }
        });
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
                r.showNotice(clock(to) + " / " + clock(mp.getDuration()), null);
            } catch (IllegalStateException ignored) {
            }
        });
    }

    @Override
    public void onVrExit() {
        main.post(this::finish);
    }

    @Override public void onVrPointerMove(float u, float v) { }
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
            }
        });
    }

    // ---- lifecycle ----

    @Override
    protected void onStop() {
        super.onStop();
        // Before the first focus a boundary prompt can stop us without the
        // user having left; after it, a stop means they did
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
        if (player != null) {
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
