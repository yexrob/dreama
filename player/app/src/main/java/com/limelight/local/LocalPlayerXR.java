package com.limelight.local;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.limelight.LimeLog;
import com.limelight.binding.video.XrRenderer;
import com.limelight.binding.video.XrStartFailure;
import com.limelight.local.dlna.CastReceiver;
import com.limelight.local.dlna.PlayerLink;
import com.limelight.local.dlna.PlayerState;
import com.limelight.local.media.SourceKind;
import com.limelight.local.media.WatchHistory;
import com.limelight.local.plugin.PluginHostGuards;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Plays a video on MoonlightXR's virtual screen. The renderer hands out the
 * Surface its SurfaceTexture is fed from; here ExoPlayer decodes into it
 * instead of the stream decoder, and everything after that — the depth model,
 * the warp per eye, the screen, the pointer — is the renderer's own.
 *
 * The video is a local file, one on the WebDAV share, or what a phone casts
 * over DLNA (MP4, FLV, HLS, DASH). A cast is driven from the phone as well,
 * through PlayerLink, and a new cast while this is up replaces what plays.
 *
 * Controls: trigger on the screen plays and pauses, the thumbstick seeks ten
 * seconds a click, and the bar's exit button leaves.
 */
@OptIn(markerClass = UnstableApi.class)
public class LocalPlayerXR extends Activity
        implements XrRenderer.InputListener, XrRenderer.SessionListener, PlayerLink.CastPlayer {

    private static final int SEEK_STEP_MS = 10_000;
    // An Authorization header for an http source (the WebDAV share)
    public static final String EXTRA_AUTH = "auth";
    public static final String EXTRA_TITLE = "title";
    // Where to start, and what the wall files the watch progress under
    public static final String EXTRA_START_MS = "start_ms";
    public static final String EXTRA_SOURCE_ID = "source_id";
    public static final String EXTRA_ITEM_ID = "item_id";
    // Plugin playback: opaque instance ID + optional known duration (never raw URL or headers)
    public static final String EXTRA_PLUGIN_INSTANCE_ID = "plugin_instance_id";
    public static final String EXTRA_DURATION_MS = "duration_ms";
    // A DLNA cast: which one, so the phone's commands reach only the player
    // showing it, and the stream type where the url does not say
    public static final String EXTRA_CAST_ID = "cast_id";
    public static final String EXTRA_MIME = "mime";
    // Started again by itself for a picture of another shape, so it does not do so twice
    public static final String EXTRA_RELAUNCHED = "relaunched";

    // One OpenXR session at a time: a player started again waits for the last one's to be gone
    private static final Semaphore SESSION = new Semaphore(1);
    private static final long SESSION_WAIT_S = 5;
    // How long a cast's own stream gets to say how big its picture is
    private static final long PROBE_WAIT_MS = 8000;
    // The session's screen keeps its shape; a picture further off than this starts it again
    private static final float SHAPE_SLACK = 0.03f;

    private Map<String, String> headers = Collections.emptyMap();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object life = new Object();
    private volatile XrRenderer renderer;
    // Under life
    private boolean destroyed;
    private boolean gated;

    // Main thread
    private ExoPlayer player;
    private DefaultHttpDataSource.Factory http;
    private SourceKind sourceKind = SourceKind.LOCAL;
    private PluginPlayerSession pluginSession;
    private long fallbackDurationMs;
    private boolean refreshInFlight;
    private boolean held;
    private long castId;
    private long startMs;
    private long lastKnownPositionMs;
    // The current item has been ready once; the session's surface is on the player
    private boolean ready;
    private boolean surfaceSet;
    private boolean wantPlay = true;
    private boolean errored;
    private String errorReason;
    private boolean errorCanRetry = true;
    private boolean checkShape;
    private int sessionW, sessionH;
    private Intent relaunch;
    private String pendingNotice, pendingMore;

    // What a cast's stream says its picture is, for the session's size
    private final CountDownLatch probed = new CountDownLatch(1);
    private volatile int probeW, probeH;
    private volatile float probeFps;

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

        final PluginLaunchArgs pluginArgs = PluginPlayerLaunch.parseIntent(getIntent());
        final Uri uri = getIntent().getData();
        sourceKind = pluginArgs != null
                ? SourceKind.PLUGIN
                : (isHttp(uri) ? SourceKind.WEBDAV : SourceKind.LOCAL);
        final boolean pluginMode = PluginHostGuards.isPluginSource(sourceKind);

        if (!pluginMode && uri == null) {
            finish();
            return;
        }
        // The cast receiver stays up while a film plays, for a phone to cast to
        CastReceiver.hold(this);
        held = true;

        DefaultTrackSelector trackSelector = createTrackSelector(this);

        if (pluginMode) {
            // Plugin sources NEVER use DefaultHttpDataSource: every manifest, segment,
            // backup URL, and subtitle goes strictly through PluginPlayback + NetworkPolicy.
            player = new ExoPlayer.Builder(this)
                    .setTrackSelector(trackSelector)
                    .build();
            pluginSession = PluginPlayerSession.create(this);
        } else {
            // The user agent Android's own MediaPlayer sends, which the WebDAV
            // share always got and which the phones' CDNs take from TV boxes
            http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("stagefright/1.2 (Linux;Android " + Build.VERSION.RELEASE + ")")
                    .setAllowCrossProtocolRedirects(true);
            player = new ExoPlayer.Builder(this)
                    .setTrackSelector(trackSelector)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                    .build();
        }
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), false);
        // The display rate is the session's to pick, from the probe below
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF);
        player.addListener(events);
        // Buffers while the session starts
        if (pluginMode) {
            loadPlugin(pluginArgs);
        } else {
            load(getIntent());
        }
        guardCastAttachment(sourceKind, castId);
        PlayerLink.attach(this, castId);
        main.post(barTick);

        final boolean cast = castId != 0;
        final String logLabel = pluginMode ? ("plugin:" + pluginArgs.getItemId()) : String.valueOf(uri);
        new Thread(() -> startSession(uri, cast, pluginMode, logLabel), "depth3d-start").start();
    }

    /** Creates the shared track selector preferring Chinese subtitle tracks across all sources. */
    static DefaultTrackSelector createTrackSelector(Context context) {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setPreferredTextLanguage("zh")
                .setSelectUndeterminedTextLanguage(true));
        return trackSelector;
    }

    /** Enforces §1.4/§2.4: plugin playback may never attach to an active DLNA cast session. */
    static void guardCastAttachment(SourceKind kind, long activeCastId) {
        if (activeCastId != 0) {
            PluginHostGuards.requireNonPluginCast(kind, null, null);
        }
    }

    private void loadPlugin(PluginLaunchArgs args) {
        if (args == null || pluginSession == null || player == null) {
            finish();
            return;
        }
        castId = 0;
        startMs = args.getStartMs();
        lastKnownPositionMs = startMs;
        fallbackDurationMs = args.getDurationMs() != null ? args.getDurationMs() : 0L;
        ready = false;
        errored = false;
        errorReason = null;
        errorCanRetry = true;
        refreshInFlight = false;
        wantPlay = true;
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.setPlayWhenReady(false);
        notice(getString(com.limelight.R.string.plugin_player_opening), title());

        pluginSession.loadAsync(
                args.getInstanceId(),
                args.getItemId(),
                startMs,
                args.getDurationMs(),
                new PluginPlayerSession.LoadCallback() {
                    @Override
                    public void onLoaded(LoadedPluginMedia media) {
                        ExoPlayer p = player;
                        if (p == null || isDestroyedOrFinishing()) {
                            return;
                        }
                        if (media.getDurationMs() != null && media.getDurationMs() > 0L) {
                            fallbackDurationMs = media.getDurationMs();
                        }
                        startMs = media.getResumePositionMs();
                        p.setMediaSource(media.getMediaSource());
                        p.prepare();
                        tickBar();
                    }

                    @Override
                    public void onFailed(Throwable error) {
                        if (isDestroyedOrFinishing()) {
                            return;
                        }
                        LimeLog.warning("depth3d: plugin load failed: " + error.getClass().getSimpleName());
                        handlePlaybackFailure(error);
                    }
                });
    }

    private boolean isDestroyedOrFinishing() {
        synchronized (life) {
            return destroyed || isFinishing();
        }
    }

    private void load(Intent in) {
        Uri uri = in.getData();
        castId = in.getLongExtra(EXTRA_CAST_ID, 0);
        startMs = in.getLongExtra(EXTRA_START_MS, 0);
        lastKnownPositionMs = startMs;
        fallbackDurationMs = in.getLongExtra(EXTRA_DURATION_MS, 0);
        String auth = in.getStringExtra(EXTRA_AUTH);
        Map<String, String> h = Collections.emptyMap();
        if (auth != null) {
            h = new HashMap<>();
            h.put("Authorization", auth);
        }
        headers = h;
        http.setDefaultRequestProperties(h);

        MediaItem.Builder item = new MediaItem.Builder().setUri(uri);
        String mime = in.getStringExtra(EXTRA_MIME);
        if (mime != null) {
            item.setMimeType(mime);
        }
        ready = false;
        errored = false;
        errorReason = null;
        errorCanRetry = true;
        wantPlay = true;
        // A film of its own loops as it always did; a cast ends, for the phone to send the next
        player.setRepeatMode(castId != 0 ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
        player.setWakeMode(isHttp(uri) ? C.WAKE_MODE_NETWORK : C.WAKE_MODE_NONE);
        player.setPlayWhenReady(false);
        player.setMediaItem(item.build());
        player.prepare();
    }

    // Off the main thread: start() waits on the OpenXR session's init
    private void startSession(Uri uri, boolean cast, boolean pluginMode, String logLabel) {
        int[] size;
        float fps;
        boolean guessed = false;
        if (cast || pluginMode) {
            // A phone's stream or plugin stream may be HLS/DASH/proxied, and plugin URLs
            // must NEVER be probed with MediaMetadataRetriever / MediaExtractor (§1.3).
            try {
                probed.await(PROBE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (probeW > 0 && probeH > 0) {
                size = new int[]{probeW, probeH};
            } else {
                size = new int[]{1920, 1080};
                guessed = true;
            }
            fps = probeFps > 0 ? probeFps : 30f;
        } else {
            size = videoSize(uri);
            fps = frameRate(uri);
        }

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
        LimeLog.info(String.format(Locale.US, "depth3d: %s %dx%d @ %.2f%s", logLabel, size[0], size[1], fps,
                guessed ? " (guessed)" : ""));

        synchronized (life) {
            if (destroyed) {
                return;
            }
        }
        boolean gate = false;
        try {
            gate = SESSION.tryAcquire(SESSION_WAIT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!gate) {
            LimeLog.warning("depth3d: the last session is still going; starting anyway");
        }
        synchronized (life) {
            if (destroyed) {
                if (gate) {
                    SESSION.release();
                }
                return;
            }
        }

        XrRenderer r = new XrRenderer();
        r.setLocalMedia(true);
        r.setInputListener(this);
        r.setSessionListener(this);
        if (!r.start(this, size[0], size[1], prefs)) {
            if (gate) {
                SESSION.release();
            }
            XrStartFailure failure = r.getStartFailure();
            onVrUnavailable(failure != null ? failure : XrStartFailure.unknown());
            return;
        }
        boolean kept;
        synchronized (life) {
            kept = !destroyed;
            if (kept) {
                renderer = r;
                gated = gate;
            }
        }
        if (!kept) {
            // Gone while the session started: nothing else would stop it
            r.prepareForStop();
            r.cleanup();
            if (gate) {
                SESSION.release();
            }
            return;
        }
        final TransportBar b = new TransportBar(size[0], size[1]);
        final int w = size[0], h = size[1];
        final boolean check = guessed || cast || pluginMode;
        main.post(() -> sessionUp(r, b, w, h, check));
    }

    private void sessionUp(XrRenderer r, TransportBar b, int w, int h, boolean check) {
        ExoPlayer p = player;
        if (renderer != r || p == null) {
            return;
        }
        bar = b;
        sessionW = w;
        sessionH = h;
        checkShape = check;
        p.setVideoSurface(r.getInputSurface());
        surfaceSet = true;
        if (pendingNotice != null) {
            r.showNotice(pendingNotice, pendingMore);
            pendingNotice = null;
            pendingMore = null;
        } else if (!ready) {
            if (castId != 0) {
                r.showNotice(getString(com.limelight.R.string.player_opening_cast), title());
            } else if (PluginHostGuards.isPluginSource(sourceKind)) {
                r.showNotice(getString(com.limelight.R.string.plugin_player_opening), title());
            } else if (isHttp(getIntent().getData())) {
                r.showNotice(getString(com.limelight.R.string.player_buffering_webdav), title());
            }
        }
        if (ready && !errored) {
            p.setPlayWhenReady(wantPlay);
            if (wantPlay && PluginHostGuards.isPluginSource(sourceKind) && pluginSession != null) {
                pluginSession.reportStartAsync(position(), duration());
            }
        }
        tickBar();
    }

    private void handlePlaybackFailure(Throwable error) {
        String srcName = pluginSession != null ? pluginSession.getSourceName() : "";
        PlayerErrorUi ui = PluginPlayerSession.formatPlayerError(this, error, sourceKind, srcName);
        errored = true;
        errorReason = ui.getReason();
        errorCanRetry = ui.getCanRetry();
        notice(errorReason, null);
        probed.countDown();
        tickBar();
    }

    private void triggerPluginRefresh(long resumePos, Integer httpCode, Throwable fallbackError) {
        if (pluginSession == null || refreshInFlight) {
            if (fallbackError != null) {
                handlePlaybackFailure(fallbackError);
            }
            return;
        }
        refreshInFlight = true;
        notice(getString(com.limelight.R.string.plugin_player_refreshing), null);
        pluginSession.refreshAsync(resumePos, httpCode, new PluginPlayerSession.RefreshCallback() {
            @Override
            public void onRefreshed(LoadedPluginMedia media) {
                refreshInFlight = false;
                ExoPlayer p = player;
                if (p == null || isDestroyedOrFinishing()) {
                    return;
                }
                if (media.getDurationMs() != null && media.getDurationMs() > 0L) {
                    fallbackDurationMs = media.getDurationMs();
                }
                errored = false;
                errorReason = null;
                ready = false;
                startMs = media.getResumePositionMs();
                p.setMediaSource(media.getMediaSource());
                p.prepare();
                tickBar();
            }

            @Override
            public void onNoRefreshNeeded() {
                refreshInFlight = false;
                if (fallbackError != null && !isDestroyedOrFinishing()) {
                    handlePlaybackFailure(fallbackError);
                }
            }

            @Override
            public void onFailed(Throwable error) {
                refreshInFlight = false;
                if (!isDestroyedOrFinishing()) {
                    LimeLog.warning("depth3d: plugin refresh failed: " + error.getClass().getSimpleName());
                    handlePlaybackFailure(error);
                }
            }
        });
    }

    private void retryCurrentPlayback() {
        ExoPlayer p = player;
        if (p == null) {
            return;
        }
        long resumePos = PluginPlayerLaunch.computeRetryPositionMs(position(), lastKnownPositionMs, startMs);
        if (PluginHostGuards.isPluginSource(sourceKind)) {
            PluginLaunchArgs parsed = PluginPlayerLaunch.parseIntent(getIntent());
            loadPlugin(parsed != null ? PluginPlayerLaunch.withResumePosition(parsed, resumePos) : null);
        } else {
            startMs = resumePos;
            lastKnownPositionMs = resumePos;
            errored = false;
            errorReason = null;
            wantPlay = true;
            if (resumePos > 0) {
                p.seekTo(resumePos);
            }
            p.prepare();
            if (ready && surfaceSet) {
                p.setPlayWhenReady(true);
            }
        }
        tickBar();
    }

    private final Player.Listener events = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            ExoPlayer p = player;
            if (p == null) {
                return;
            }
            if (state == Player.STATE_READY && !ready) {
                ready = true;
                if (pluginSession != null) {
                    pluginSession.markPlaybackReady();
                }
                long d = duration();
                // A resume point in the last seconds would only loop round to the start
                boolean nearEnd = castId == 0 && d > 0 && startMs >= d - 5000;
                if (startMs > 0 && !nearEnd) {
                    p.seekTo(startMs);
                }
                if (surfaceSet) {
                    p.setPlayWhenReady(wantPlay);
                    if (wantPlay && PluginHostGuards.isPluginSource(sourceKind) && pluginSession != null) {
                        pluginSession.reportStartAsync(position(), duration());
                    }
                }
            } else if (state == Player.STATE_BUFFERING && ready
                    && (PluginHostGuards.isPluginSource(sourceKind) || isHttp(getIntent().getData()))) {
                notice(getString(com.limelight.R.string.player_buffering), null);
            } else if (state == Player.STATE_ENDED) {
                saveProgress();
                if (PluginHostGuards.isPluginSource(sourceKind) && pluginSession != null) {
                    long d = duration();
                    pluginSession.reportFinishAsync(d > 0 ? d : position(), d);
                }
            }
            tickBar();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (!PluginHostGuards.isPluginSource(sourceKind) || pluginSession == null || !ready) {
                return;
            }
            ExoPlayer p = player;
            if (p == null) {
                return;
            }
            if (isPlaying) {
                pluginSession.reportStartAsync(position(), duration());
            } else if (p.getPlaybackState() != Player.STATE_ENDED && !errored && !refreshInFlight) {
                pluginSession.reportPauseAsync(position(), duration());
            }
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            if (tracks.isEmpty() || probed.getCount() == 0) {
                return;
            }
            Format f = videoFormat(tracks);
            if (f != null && f.width > 0 && f.height > 0) {
                boolean turned = f.rotationDegrees == 90 || f.rotationDegrees == 270;
                probeW = turned ? f.height : f.width;
                probeH = turned ? f.width : f.height;
                probeFps = f.frameRate;
            }
            probed.countDown();
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            if (!checkShape || size.width <= 0 || size.height <= 0 || sessionW <= 0 || sessionH <= 0) {
                return;
            }
            checkShape = false;
            float off = (size.width / (float) size.height) / (sessionW / (float) sessionH);
            if (Math.abs(off - 1f) > SHAPE_SLACK && !getIntent().getBooleanExtra(EXTRA_RELAUNCHED, false)) {
                relaunch();
            }
        }

        @Override
        public void onPlayerError(PlaybackException e) {
            long curPos = position();
            if (curPos > 0) {
                lastKnownPositionMs = curPos;
            }
            if (PluginHostGuards.isPluginSource(sourceKind) && pluginSession != null) {
                Integer httpCode = PluginPlayerSession.extractHttpStatusCode(e);
                long resumePos = PluginPlayerLaunch.computeRetryPositionMs(curPos, lastKnownPositionMs, startMs);
                if (!refreshInFlight && pluginSession.canAttemptAutoRefresh(httpCode)) {
                    LimeLog.info("depth3d: refreshing plugin stream after HTTP " + httpCode);
                    triggerPluginRefresh(resumePos, httpCode, e);
                    return;
                }
                LimeLog.warning("depth3d: plugin playback error: " + e.getErrorCodeName());
                handlePlaybackFailure(e);
                return;
            }
            LimeLog.warning("depth3d: cannot play " + getIntent().getData() + ": " + e);
            handlePlaybackFailure(e);
        }
    };

    private static Format videoFormat(Tracks tracks) {
        Format any = null;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) {
                continue;
            }
            for (int i = 0; i < g.length; i++) {
                if (g.isTrackSelected(i)) {
                    return g.getTrackFormat(i);
                }
                if (any == null) {
                    any = g.getTrackFormat(i);
                }
            }
        }
        return any;
    }

    // A notice now, or once the session is up to show it
    private void notice(String text, String more) {
        XrRenderer r = renderer;
        if (r != null && surfaceSet) {
            r.showNotice(text, more);
        } else {
            pendingNotice = text;
            pendingMore = more;
        }
    }

    // The session's screen has the shape it started with: a cast or plugin stream of another
    // shape gets a session of its own, at the same place in the video
    private void relaunch() {
        LimeLog.info("depth3d: the picture is another shape, starting the session again");
        Intent nextIntent = new Intent(this, LocalPlayerXR.class)
                .putExtras(getIntent())
                .putExtra(EXTRA_RELAUNCHED, true)
                .putExtra(EXTRA_START_MS, position())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getIntent().getData() != null) {
            nextIntent.setData(getIntent().getData());
        }
        relaunch = nextIntent;
        finish();
    }

    // A new film or cast while this is up plays here, in place of the last
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ExoPlayer p = player;
        if (p == null || relaunch != null) {
            return;
        }
        PluginLaunchArgs incomingPlugin = PluginPlayerLaunch.parseIntent(intent);
        boolean incomingIsPlugin = incomingPlugin != null;
        if (!incomingIsPlugin && intent.getData() == null) {
            return;
        }
        saveProgress();
        if (PluginHostGuards.isPluginSource(sourceKind) && pluginSession != null) {
            pluginSession.reportStopFireAndForget(position(), duration());
        }
        // Switching between plugin and non-plugin mode requires rebuilding ExoPlayer's data source
        if (incomingIsPlugin != PluginHostGuards.isPluginSource(sourceKind)) {
            relaunch = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            finish();
            return;
        }
        long old = castId;
        long next = intent.getLongExtra(EXTRA_CAST_ID, 0);
        if (old != next) {
            PlayerLink.detach(this, old, position(), duration(), true);
        }
        setIntent(intent);
        if (incomingIsPlugin) {
            loadPlugin(incomingPlugin);
        } else {
            load(intent);
        }
        if (old != next) {
            guardCastAttachment(sourceKind, castId);
            PlayerLink.attach(this, castId);
        }
        checkShape = surfaceSet;
        XrRenderer r = renderer;
        if (r != null && surfaceSet) {
            r.showNotice(getString(castId != 0
                    ? com.limelight.R.string.player_opening_cast
                    : com.limelight.R.string.player_opening), title());
        }
        tickBar();
    }

    private int[] videoSize(Uri uri) {
        PluginHostGuards.requireNonPluginFrameProbe(sourceKind, null, null);
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
        PluginHostGuards.requireNonPluginFrameProbe(sourceKind, null, null);
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
        String s = uri != null ? uri.getScheme() : null;
        return "http".equals(s) || "https".equals(s);
    }

    private static int parse(String s, int fallback) {
        try {
            return s != null ? Integer.parseInt(s) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String title() {
        return getIntent().getStringExtra(EXTRA_TITLE);
    }

    private long position() {
        ExoPlayer p = player;
        return p != null ? Math.max(0, p.getCurrentPosition()) : 0;
    }

    // 0 while not known, as for a live stream
    private long duration() {
        ExoPlayer p = player;
        long d = p != null ? p.getDuration() : C.TIME_UNSET;
        if (d != C.TIME_UNSET && d > 0) {
            return d;
        }
        return Math.max(0, fallbackDurationMs);
    }

    private boolean playing() {
        ExoPlayer p = player;
        return p != null && p.getPlayWhenReady() && p.getPlaybackState() != Player.STATE_ENDED;
    }

    private void setPlaying(boolean on) {
        ExoPlayer p = player;
        if (p == null) {
            return;
        }
        wantPlay = on;
        if (on && errored) {
            retryCurrentPlayback();
            return;
        }
        if (on && p.getPlaybackState() == Player.STATE_ENDED) {
            p.seekTo(0);
        }
        if (ready && surfaceSet) {
            p.setPlayWhenReady(on);
        }
        if (!on) {
            saveProgress();
        }
    }

    // What the phone sees through GetPositionInfo and its events
    private void report() {
        ExoPlayer p = player;
        if (p == null || castId == 0) {
            return;
        }
        PlayerState st;
        if (errored) {
            st = PlayerState.ERROR;
        } else if (!ready || !surfaceSet) {
            st = PlayerState.LOADING;
        } else if (p.getPlaybackState() == Player.STATE_ENDED) {
            st = PlayerState.ENDED;
        } else {
            st = p.getPlayWhenReady() ? PlayerState.PLAYING : PlayerState.PAUSED;
        }
        PlayerLink.report(castId, st, position(), duration());
    }

    // ---- the phone, through PlayerLink, on the main thread ----

    @Override
    public void castPlay() {
        setPlaying(true);
        tickBar();
    }

    @Override
    public void castPause() {
        setPlaying(false);
        tickBar();
    }

    @Override
    public void castSeek(long ms) {
        ExoPlayer p = player;
        if (p == null) {
            return;
        }
        long clamped = Math.max(0, ms);
        lastKnownPositionMs = clamped;
        if (!ready) {
            startMs = clamped;
        } else {
            p.seekTo(clamped);
        }
        tickBar();
    }

    @Override
    public void castClose() {
        finish();
    }

    // ---- input from the session, on the frame loop thread ----

    @Override
    public void onVrButton(int button, boolean down) {
        if (button != 0 || down) {
            return;
        }
        final float u = pointerU, v = pointerV;
        main.post(() -> {
            ExoPlayer p = player;
            TransportBar b = bar;
            if (p == null || b == null) {
                return;
            }
            pointerMovedMs = System.currentTimeMillis();
            float x = b.texX(u, v);
            if (errored) {
                if (errorCanRetry && TransportBar.onErrorRetry(x)) {
                    retryCurrentPlayback();
                } else if (TransportBar.onErrorBack(x)) {
                    finish();
                }
                return;
            }
            float f = Float.isNaN(x) || TransportBar.onButton(x)
                    ? Float.NaN : TransportBar.trackFraction(x);
            if (!Float.isNaN(f)) {
                long d = duration();
                if (d > 0 && p.isCurrentMediaItemSeekable()) {
                    p.seekTo((long) (f * d));
                }
            } else {
                setPlaying(!playing());
            }
            tickBar();
        });
    }

    // Shown while paused, or for a while after the pointer moves; redrawn a
    // few times a second while it shows. Each round also tells a casting
    // phone where the player is, and ticks plugin expiry refresh / progress reports.
    private void tickBar() {
        main.removeCallbacks(barTick);
        ExoPlayer p = player;
        if (p == null) {
            return;
        }
        report();
        boolean on = playing();
        if (ready && !errored && !refreshInFlight) {
            long curPos = position();
            if (curPos > 0) {
                lastKnownPositionMs = curPos;
            }
        }
        if (PluginHostGuards.isPluginSource(sourceKind) && pluginSession != null && ready && !errored) {
            if (!refreshInFlight && pluginSession.shouldProactiveRefresh()) {
                LimeLog.info("depth3d: proactively refreshing expiring plugin stream");
                long resumePos = PluginPlayerLaunch.computeRetryPositionMs(position(), lastKnownPositionMs, startMs);
                triggerPluginRefresh(resumePos, null, null);
            }
            if (on) {
                pluginSession.reportPeriodicIfDueAsync(position(), duration());
            }
        }
        XrRenderer r = renderer;
        TransportBar b = bar;
        if (r != null && b != null) {
            long since = System.currentTimeMillis() - pointerMovedMs;
            if (errored) {
                r.setTransportVisible(true);
                float x = since < BAR_LINGER_MS ? b.texX(pointerU, pointerV) : Float.NaN;
                boolean retryHot = errorCanRetry && TransportBar.onErrorRetry(x);
                boolean backHot = TransportBar.onErrorBack(x);
                String msg = errorReason != null
                        ? errorReason
                        : getString(com.limelight.R.string.plugin_player_error_generic);
                r.postTransport(b.drawError(
                        msg,
                        getString(com.limelight.R.string.action_retry),
                        getString(com.limelight.R.string.player_action_back),
                        errorCanRetry,
                        retryHot,
                        backHot));
            } else {
                boolean show = !on || since < BAR_LINGER_MS;
                r.setTransportVisible(show);
                if (show) {
                    float x = since < BAR_LINGER_MS ? b.texX(pointerU, pointerV) : Float.NaN;
                    boolean onBtn = !Float.isNaN(x) && TransportBar.onButton(x);
                    float hover = Float.isNaN(x) || onBtn ? Float.NaN : TransportBar.trackFraction(x);
                    r.postTransport(b.draw(on, (int) position(), (int) duration(), hover, onBtn, title()));
                }
            }
        }
        main.postDelayed(barTick, BAR_TICK_MS);
    }

    @Override
    public void onVrScroll(int clicks) {
        main.post(() -> {
            ExoPlayer p = player;
            XrRenderer r = renderer;
            if (p == null || r == null || !p.isCurrentMediaItemSeekable()) {
                return;
            }
            long to = position() - (long) clicks * SEEK_STEP_MS;
            long d = duration();
            if (d > 0) {
                to = Math.min(d - 1000, to);
            }
            long clamped = Math.max(0, to);
            lastKnownPositionMs = clamped;
            p.seekTo(clamped);
            pointerMovedMs = System.currentTimeMillis();
            tickBar();
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
            Toast.makeText(this, getString(com.limelight.R.string.player_vr_unavailable), Toast.LENGTH_LONG).show();
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
            if (playing()) {
                setPlaying(false);
                tickBar();
            }
        });
    }

    // For 继续观看. Only when the wall said which item this is: an adb launch
    // with a bare uri, or a cast, has nothing to file it under.
    private void saveProgress() {
        ExoPlayer p = player;
        Intent in = getIntent();
        String source = in.getStringExtra(EXTRA_SOURCE_ID);
        String item = in.getStringExtra(EXTRA_ITEM_ID);
        if (p == null || !ready || source == null || item == null) {
            return;
        }
        WatchHistory.save(this, source, item, in.getStringExtra(EXTRA_TITLE), position(), duration());
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
        XrRenderer r;
        boolean gate;
        synchronized (life) {
            if (destroyed) {
                return;
            }
            destroyed = true;
            r = renderer;
            renderer = null;
            gate = gated;
            gated = false;
        }
        // A start still waiting on the probe goes on, to find it is gone
        probed.countDown();
        main.removeCallbacks(barTick);
        ExoPlayer p = player;
        long pos = position(), dur = duration();
        if (pluginSession != null) {
            if (relaunch == null) {
                pluginSession.reportStopFireAndForget(pos, dur);
            }
            pluginSession.close();
            pluginSession = null;
        }
        if (p != null) {
            saveProgress();
            // Before the renderer lets the surface go, which the codec draws into
            p.release();
            player = null;
        }
        // Started again for the same cast: the phone is not told it ended
        PlayerLink.detach(this, castId, pos, dur, relaunch == null);
        if (r != null) {
            r.prepareForStop();
            r.cleanup();
        }
        if (gate) {
            SESSION.release();
        }
        if (held) {
            held = false;
            CastReceiver.release(this);
        }
        final Intent again = relaunch;
        if (again != null) {
            final Context app = getApplicationContext();
            main.post(() -> {
                try {
                    app.startActivity(again);
                } catch (RuntimeException e) {
                    LimeLog.warning("depth3d: could not start the player again: " + e);
                }
            });
        }
    }
}
