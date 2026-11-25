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
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.video.XrRenderer;
import com.limelight.binding.video.XrShared;
import com.limelight.binding.video.XrStartFailure;
import com.limelight.local.dlna.CastReceiver;
import com.limelight.local.dlna.PlayerLink;
import com.limelight.local.dlna.PlayerState;
import com.limelight.local.media.SourceKind;
import com.limelight.local.media.StereoHint;
import com.limelight.local.media.WatchHistory;
import com.limelight.local.plugin.PluginHostGuards;
import com.limelight.local.stream.DepthStrength;
import com.limelight.local.stream.PrefsStore;
import com.limelight.preferences.PreferenceConfiguration;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
    public static final String EXTRA_PARENT_ID = "parent_id";
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
    private DefaultTrackSelector trackSelector;
    private DefaultHttpDataSource.Factory http;
    private SourceKind sourceKind = SourceKind.LOCAL;
    private PluginPlayerSession pluginSession;

    // G2 VR Quick Palette state, renderer & stores (synchronized under paletteLock)
    private final Object paletteLock = new Object();
    private PaletteController palette;
    private PaletteRenderer paletteRenderer;
    private PrefsStore globalPaletteStore;
    private PrefsStore videoPaletteStore;
    private String currentVideoKey = "anonymous";
    private volatile String activeDepthModel;
    private volatile boolean paletteOpenUi;
    private volatile boolean danmakuSupportedUi;
    private volatile boolean danmakuOnUi = true;
    private int lastPaletteRowMask = -1;
    private Tracks latestTracks = Tracks.EMPTY;
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
    private volatile boolean screenAdjusting;
    private float lastU, lastV;
    private static final long BAR_LINGER_MS = 3000;
    private static final long BAR_TICK_MS = 200;
    private final Runnable barTick = this::tickBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final PluginLaunchArgs pluginArgs;
        try {
            pluginArgs = resolvePluginIntentOrThrow(getIntent());
        } catch (UnsupportedOperationException e) {
            LimeLog.warning("depth3d: rejected cast attachment on plugin playback intent");
            Toast.makeText(this, getString(com.limelight.R.string.plugin_cast_unsupported), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
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

        globalPaletteStore = PaletteStore.defaultGlobalStore(this);
        videoPaletteStore = PaletteStore.defaultVideoStore(this);
        initPaletteForIntent(getIntent(), false);

        trackSelector = createTrackSelector(this);

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

    /**
     * Parses a plugin launch [Intent] and rejects any non-zero [EXTRA_CAST_ID] BEFORE [loadPlugin]
     * clears `castId` (§1.4/§2.4), throwing [UnsupportedOperationException] via [PluginHostGuards].
     */
    static PluginLaunchArgs resolvePluginIntentOrThrow(Intent intent) {
        PluginLaunchArgs args = PluginPlayerLaunch.parseIntent(intent);
        if (args != null && intent != null) {
            long incomingCastId = intent.getLongExtra(EXTRA_CAST_ID, 0L);
            guardCastAttachment(SourceKind.PLUGIN, incomingCastId);
        }
        return args;
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
                        boolean supportsDm = pluginSession != null && pluginSession.getSupportsDanmaku();
                        synchronized (paletteLock) {
                            if (palette != null) {
                                palette.setDanmakuSupported(supportsDm);
                                if (media.getStereoHint() == StereoHint.SBS) {
                                    palette.seedDefaultSourceFormat(StereoSourceFormat.SBS);
                                } else if (media.getStereoHint() == StereoHint.OU) {
                                    palette.seedDefaultSourceFormat(StereoSourceFormat.OU);
                                }
                            }
                        }
                        danmakuSupportedUi = supportsDm;
                        XrRenderer xr = renderer;
                        if (xr != null) {
                            applyPaletteStereoToRenderer(xr);
                            redrawPaletteIfReady(xr);
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
        // Keep stereo pipeline ready in local playback even if 3D starts OFF so
        // toggling 3D ON from the VR Quick Palette works immediately without restarting.
        if (prefs.vrDepthMode == XrShared.DEPTH_MODE_OFF) {
            prefs.vrDepthMode = XrShared.DEPTH_MODE_MODEL;
            prefs.vrDepthModel = PreferenceConfiguration.VR_DEPTH_SOURCE_ZIPDEPTH;
        }
        activeDepthModel = prefs.vrDepthModel;
        boolean startStereoLive;
        synchronized (paletteLock) {
            startStereoLive = palette == null || palette.getEffectiveStrength() != DepthStrength.OFF;
        }
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
        r.setInitialStereoLive(startStereoLive);
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
        synchronized (paletteLock) {
            if (paletteRenderer == null) {
                paletteRenderer = new PaletteRenderer();
            } else {
                paletteRenderer.invalidate();
            }
            if (palette != null) {
                palette.setCylinderSupported(r.isCylinderSupported());
            }
            lastPaletteRowMask = -1;
        }
        applyPaletteStereoToRenderer(r);
        redrawPaletteIfReady(r);
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
        boolean needsPermReconfirm = pluginSession != null && pluginSession.getNeedsPermissionReconfirm();
        PlayerErrorUi ui = PluginPlayerSession.formatPlayerError(
                this, error, sourceKind, srcName, needsPermReconfirm);
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
            latestTracks = tracks;
            updatePaletteTracksFromExo(tracks);
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
        final PluginLaunchArgs incomingPlugin;
        try {
            incomingPlugin = resolvePluginIntentOrThrow(intent);
        } catch (UnsupportedOperationException e) {
            LimeLog.warning("depth3d: rejected cast attachment on incoming plugin intent");
            notice(getString(com.limelight.R.string.plugin_cast_unsupported), null);
            Toast.makeText(this, getString(com.limelight.R.string.plugin_cast_unsupported), Toast.LENGTH_LONG).show();
            return;
        }
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
        initPaletteForIntent(intent, false);
        latestTracks = Tracks.EMPTY;
        if (incomingIsPlugin) {
            loadPlugin(incomingPlugin);
        } else {
            load(intent);
        }
        XrRenderer curXr = renderer;
        if (curXr != null) {
            applyPaletteStereoToRenderer(curXr);
            redrawPaletteIfReady(curXr);
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
            XrRenderer xr = renderer;
            if (!Float.isNaN(x) && TransportBar.onAdjustButton(x)) {
                if (xr != null) {
                    if (paletteOpenUi) {
                        xr.closePalette();
                    } else {
                        xr.openPaletteFromBar();
                    }
                    xr.playClickSound();
                }
                tickBar();
                return;
            }
            if (!Float.isNaN(x) && TransportBar.onDanmakuButton(x, danmakuSupportedUi)) {
                toggleDanmakuFromUi();
                if (xr != null) {
                    xr.playClickSound();
                }
                tickBar();
                return;
            }
            float f = Float.isNaN(x) || TransportBar.onButton(x)
                    ? Float.NaN : TransportBar.trackFraction(x, danmakuSupportedUi);
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
            } else if (screenAdjusting) {
                r.setTransportVisible(false);
            } else {
                boolean show = !on || since < BAR_LINGER_MS;
                r.setTransportVisible(show);
                if (show) {
                    float x = since < BAR_LINGER_MS ? b.texX(pointerU, pointerV) : Float.NaN;
                    boolean onBtn = !Float.isNaN(x) && TransportBar.onButton(x);
                    boolean onAdj = !Float.isNaN(x) && TransportBar.onAdjustButton(x);
                    boolean onDm = !Float.isNaN(x) && TransportBar.onDanmakuButton(x, danmakuSupportedUi);
                    float hover = Float.isNaN(x) || onBtn || onAdj || onDm
                            ? Float.NaN : TransportBar.trackFraction(x, danmakuSupportedUi);
                    r.postTransport(b.draw(
                            on, (int) position(), (int) duration(), hover, onBtn, title(),
                            getString(R.string.vr_bar_adjust),
                            getString(R.string.vr_bar_danmaku),
                            danmakuSupportedUi, danmakuOnUi, paletteOpenUi, onAdj, onDm));
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
    public void onVrLocalPlayPause() {
        main.post(() -> {
            ExoPlayer p = player;
            if (p == null || errored) {
                return;
            }
            pointerMovedMs = System.currentTimeMillis();
            setPlaying(!playing());
            tickBar();
        });
    }

    @Override
    public void onVrLocalSeek(int deltaSec) {
        if (deltaSec == 0) {
            return;
        }
        main.post(() -> {
            ExoPlayer p = player;
            XrRenderer r = renderer;
            if (p == null || r == null || errored || !p.isCurrentMediaItemSeekable()) {
                return;
            }
            long to = position() + (long) deltaSec * 1000L;
            long d = duration();
            if (d > 1000) {
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
    public void onVrScreenAdjustChanged(boolean active) {
        screenAdjusting = active;
        XrRenderer r = renderer;
        if (active && r != null) {
            r.setTransportVisible(false);
        }
        main.post(this::tickBar);
    }

    @Override
    public void onVrLocalToggleDanmaku() {
        main.post(() -> {
            toggleDanmakuFromUi();
            tickBar();
        });
    }

    @Override
    public void onVrScreenDimensionsSaved(float fovDeg, float distM) {
        XrRenderer r = renderer;
        synchronized (paletteLock) {
            if (palette != null) {
                palette.syncExternalScreen(fovDeg, distM);
            }
        }
        if (r != null) {
            redrawPaletteIfReady(r);
        }
    }

    @Override
    public void onVrPaletteFrame(boolean open, boolean pinned, boolean handTrackingMode,
                                 boolean dimFallback, float hoverX, float hoverY,
                                 int clickState, int rowStep, boolean rowReset,
                                 int tabStep, int pageStep) {
        XrRenderer r = renderer;
        if (r == null) {
            return;
        }
        boolean wasOpen = paletteOpenUi;
        PaletteActionResult res;
        int rowMask;
        ByteBuffer sheet;
        GlobalPaletteSettings globalToSave = null;
        VideoPaletteOverrides videoToSave = null;
        String videoKeyToSave = null;
        synchronized (paletteLock) {
            if (palette == null) {
                return;
            }
            res = palette.processFrameInput(
                    open, pinned, handTrackingMode, dimFallback,
                    hoverX, hoverY, clickState, rowStep, rowReset, tabStep, pageStep);
            paletteOpenUi = palette.isOpen();
            danmakuOnUi = palette.getGlobal().getDanmakuOn();
            rowMask = palette.activeRowMask();
            sheet = paletteRenderer != null ? paletteRenderer.drawIfChanged(palette) : null;
            if (res.getSaveGlobalSettings()) {
                globalToSave = palette.getGlobal();
            }
            if (res.getSaveVideoOverrides()) {
                videoToSave = palette.getVideo();
                videoKeyToSave = currentVideoKey;
            }
        }
        if (rowMask != lastPaletteRowMask) {
            lastPaletteRowMask = rowMask;
            r.setPaletteRowMask(rowMask);
        }
        if (sheet != null) {
            r.postPalette(sheet);
        }
        if (res.getStereoChanged() || res.getSourceFormatChanged()) {
            applyPaletteStereoToRenderer(r);
        }
        if (res.getScreenChanged()) {
            GlobalPaletteSettings g;
            synchronized (paletteLock) {
                g = palette != null ? palette.getGlobal() : null;
            }
            if (g != null) {
                r.setLocalScreenDimensions(
                        g.getScreenFovDeg(), g.getScreenDistM(), g.getScreenCurvaturePct() / 100f);
            }
        }
        if (res.getEnvironmentChanged() && globalPaletteStore != null) {
            PaletteEnvironment env;
            synchronized (paletteLock) {
                env = palette != null ? palette.getGlobal().getEnvironment() : PaletteEnvironment.PASSTHROUGH;
            }
            int envCell = PaletteStore.envCellFor(globalPaletteStore, env);
            r.setLocalEnvironment(envCell, env == PaletteEnvironment.DIM);
        }
        if (res.getHapticKind() > XrShared.PALETTE_HAPTIC_NONE) {
            r.triggerPaletteHaptic(res.getHapticKind());
        }
        if (res.getClickSound()) {
            r.playClickSound();
        }
        if (res.getToastMessage() != null) {
            r.showNotice(res.getToastMessage(), null);
        }

        final GlobalPaletteSettings finalGlobal = globalToSave;
        final VideoPaletteOverrides finalVideo = videoToSave;
        final String finalVideoKey = videoKeyToSave;
        final boolean applySub = res.getSubtitleTrackChanged();
        final boolean applyAud = res.getAudioTrackChanged();
        final boolean barRefresh = (wasOpen != paletteOpenUi) || res.getDanmakuChanged();
        if (finalGlobal != null || finalVideo != null || applySub || applyAud || barRefresh) {
            main.post(() -> {
                if (finalGlobal != null && globalPaletteStore != null) {
                    PaletteStore.saveGlobal(globalPaletteStore, finalGlobal);
                }
                if (finalVideo != null && videoPaletteStore != null && finalVideoKey != null) {
                    PaletteStore.saveVideoOverrides(videoPaletteStore, finalVideoKey, finalVideo);
                }
                if (applySub || applyAud) {
                    applyExoTrackSelections(latestTracks);
                }
                if (barRefresh) {
                    tickBar();
                }
            });
        }
    }

    // ---- G2 VR Quick Palette helpers ----

    private PaletteStrings buildPaletteStrings() {
        return new PaletteStrings(
                getString(R.string.vr_palette_tab_3d),
                getString(R.string.vr_palette_tab_screen),
                getString(R.string.vr_palette_tab_danmaku),
                getString(R.string.vr_palette_tab_sub_audio),
                getString(R.string.vr_palette_row_strength),
                getString(R.string.vr_palette_row_source),
                getString(R.string.vr_palette_row_float),
                getString(R.string.vr_palette_row_layer),
                getString(R.string.settings_depth_off),
                getString(R.string.settings_depth_soft),
                getString(R.string.settings_depth_standard),
                getString(R.string.settings_depth_strong),
                getString(R.string.vr_palette_source_auto),
                getString(R.string.vr_palette_source_sbs),
                getString(R.string.vr_palette_source_ou),
                getString(R.string.vr_palette_float_auto),
                getString(R.string.vr_palette_float_flat),
                getString(R.string.vr_palette_layer_off),
                getString(R.string.vr_palette_layer_shallow),
                getString(R.string.vr_palette_layer_medium),
                getString(R.string.vr_palette_layer_deep),
                getString(R.string.vr_palette_row_size),
                getString(R.string.vr_palette_row_dist),
                getString(R.string.vr_palette_row_curve),
                getString(R.string.vr_palette_row_env),
                getString(R.string.vr_screen_preset_small),
                getString(R.string.vr_screen_preset_medium),
                getString(R.string.vr_screen_preset_large),
                getString(R.string.vr_screen_preset_giant),
                getString(R.string.settings_curved_flat_label),
                getString(R.string.vr_curve_disabled_in_room),
                getString(R.string.vr_curve_unsupported_headset),
                getString(R.string.vr_palette_env_passthrough),
                getString(R.string.vr_palette_env_dim),
                getString(R.string.vr_palette_env_theater),
                getString(R.string.vr_palette_env_void),
                getString(R.string.vr_palette_row_danmaku),
                getString(R.string.vr_palette_row_area),
                getString(R.string.vr_palette_row_opacity),
                getString(R.string.vr_palette_danmaku_more),
                getString(R.string.vr_palette_danmaku_back),
                getString(R.string.vr_palette_toggle_on),
                getString(R.string.vr_palette_toggle_off),
                getString(R.string.vr_palette_area_full),
                getString(R.string.vr_palette_area_half),
                getString(R.string.vr_palette_area_quarter),
                getString(R.string.vr_palette_row_font_size),
                getString(R.string.vr_palette_row_speed),
                getString(R.string.vr_palette_row_block),
                getString(R.string.vr_palette_speed_slow),
                getString(R.string.vr_palette_speed_normal),
                getString(R.string.vr_palette_speed_fast),
                getString(R.string.vr_palette_block_scroll),
                getString(R.string.vr_palette_block_top),
                getString(R.string.vr_palette_block_bottom),
                getString(R.string.vr_palette_block_color),
                getString(R.string.vr_palette_danmaku_unsupported_title),
                getString(R.string.vr_palette_danmaku_unsupported_sub),
                getString(R.string.vr_palette_danmaku_failed_title),
                getString(R.string.action_retry),
                getString(R.string.vr_palette_row_subtitle),
                getString(R.string.vr_palette_row_audio),
                getString(R.string.vr_palette_row_font_size),
                getString(R.string.vr_palette_row_sub_scale),
                getString(R.string.vr_palette_row_sub_pos),
                getString(R.string.vr_palette_subtitle_off),
                getString(R.string.vr_palette_subtitle_none),
                getString(R.string.vr_palette_audio_single),
                getString(R.string.vr_toast_video_only),
                getString(R.string.vr_toast_no_danmaku),
                getString(R.string.vr_toast_danmaku_on),
                getString(R.string.vr_toast_danmaku_off));
    }

    private void initPaletteForIntent(Intent intent, boolean danmakuSupported) {
        String srcId = intent != null ? intent.getStringExtra(EXTRA_SOURCE_ID) : null;
        String itemId = intent != null ? intent.getStringExtra(EXTRA_ITEM_ID) : null;
        String uriStr = intent != null && intent.getData() != null ? intent.getData().toString() : null;
        String key = PaletteStore.videoKey(srcId, itemId, uriStr);
        GlobalPaletteSettings global = PaletteStore.readGlobal(
                globalPaletteStore, PreferenceConfiguration.isXr2Gen1Headset());
        VideoPaletteOverrides video = PaletteStore.readVideoOverrides(videoPaletteStore, key);
        synchronized (paletteLock) {
            currentVideoKey = key;
            boolean cylOk = palette == null || palette.getCylinderSupported();
            palette = new PaletteController(
                    global, video, buildPaletteStrings(), cylOk, danmakuSupported, false);
            danmakuSupportedUi = danmakuSupported;
            danmakuOnUi = global.getDanmakuOn();
            if (paletteRenderer != null) {
                paletteRenderer.invalidate();
            }
            lastPaletteRowMask = -1;
        }
    }

    private void applyPaletteStereoToRenderer(XrRenderer r) {
        StereoRenderParams params;
        synchronized (paletteLock) {
            if (palette == null) {
                return;
            }
            params = palette.computeStereoParams(activeDepthModel);
        }
        r.setStereoSourceFormat(params.getSourceFormat().getNativeCode());
        r.setStereoStrength(params.getOn(), params.getSeparation(),
                params.getConvergence(), params.getDepthLocal());
    }

    private void redrawPaletteIfReady(XrRenderer r) {
        ByteBuffer sheet;
        int mask;
        synchronized (paletteLock) {
            if (palette == null || paletteRenderer == null) {
                return;
            }
            mask = palette.activeRowMask();
            sheet = paletteRenderer.drawIfChanged(palette);
        }
        if (mask != lastPaletteRowMask) {
            lastPaletteRowMask = mask;
            r.setPaletteRowMask(mask);
        }
        if (sheet != null) {
            r.postPalette(sheet);
        }
    }

    private void toggleDanmakuFromUi() {
        XrRenderer r = renderer;
        PaletteActionResult res;
        GlobalPaletteSettings toSave = null;
        synchronized (paletteLock) {
            if (palette == null) {
                return;
            }
            res = palette.toggleDanmakuQuick();
            danmakuOnUi = palette.getGlobal().getDanmakuOn();
            if (res.getSaveGlobalSettings()) {
                toSave = palette.getGlobal();
            }
        }
        if (toSave != null && globalPaletteStore != null) {
            PaletteStore.saveGlobal(globalPaletteStore, toSave);
        }
        if (r != null) {
            if (res.getHapticKind() > XrShared.PALETTE_HAPTIC_NONE) {
                r.triggerPaletteHaptic(res.getHapticKind());
            }
            if (res.getToastMessage() != null) {
                r.showNotice(res.getToastMessage(), null);
            }
            redrawPaletteIfReady(r);
        }
    }

    private void updatePaletteTracksFromExo(Tracks tracks) {
        List<PaletteSubtitleTrack> subs = new ArrayList<>();
        List<PaletteAudioTrack> auds = new ArrayList<>();
        String defaultSubId = null;
        String defaultAudId = null;
        List<Tracks.Group> groups = tracks.getGroups();
        for (int gIdx = 0; gIdx < groups.size(); gIdx++) {
            Tracks.Group g = groups.get(gIdx);
            if (g.getType() == C.TRACK_TYPE_TEXT) {
                for (int tIdx = 0; tIdx < g.length; tIdx++) {
                    if (!g.isTrackSupported(tIdx)) {
                        continue;
                    }
                    Format fmt = g.getTrackFormat(tIdx);
                    String id = "sub:" + gIdx + ":" + tIdx;
                    boolean bitmap = isBitmapSubtitleMime(fmt.sampleMimeType);
                    String label = formatSubtitleTrackLabel(fmt, subs.size() + 1);
                    subs.add(new PaletteSubtitleTrack(id, label, bitmap, gIdx, tIdx));
                    if (g.isTrackSelected(tIdx) && defaultSubId == null) {
                        defaultSubId = id;
                    }
                }
            } else if (g.getType() == C.TRACK_TYPE_AUDIO) {
                for (int tIdx = 0; tIdx < g.length; tIdx++) {
                    if (!g.isTrackSupported(tIdx)) {
                        continue;
                    }
                    Format fmt = g.getTrackFormat(tIdx);
                    String id = "aud:" + gIdx + ":" + tIdx;
                    String label = formatAudioTrackLabel(fmt, auds.size() + 1);
                    auds.add(new PaletteAudioTrack(id, label, gIdx, tIdx));
                    if (g.isTrackSelected(tIdx) && defaultAudId == null) {
                        defaultAudId = id;
                    }
                }
            }
        }
        boolean hasOverride;
        synchronized (paletteLock) {
            if (palette == null) {
                return;
            }
            palette.setTracks(subs, auds, defaultSubId, defaultAudId);
            VideoPaletteOverrides v = palette.getVideo();
            hasOverride = v.getSubtitleId() != null || v.getAudioId() != null;
        }
        if (hasOverride) {
            applyExoTrackSelections(tracks);
        }
        XrRenderer r = renderer;
        if (r != null) {
            redrawPaletteIfReady(r);
        }
    }

    private void applyExoTrackSelections(Tracks tracks) {
        DefaultTrackSelector ts = trackSelector;
        if (ts == null) {
            return;
        }
        PaletteSubtitleTrack wantedSub;
        PaletteAudioTrack wantedAud;
        boolean hasSubs;
        synchronized (paletteLock) {
            if (palette == null) {
                return;
            }
            hasSubs = !palette.getSubtitleTracks().isEmpty();
            wantedSub = palette.getSelectedSubtitleTrack();
            wantedAud = palette.getSelectedAudioTrack();
        }
        DefaultTrackSelector.Parameters.Builder builder = ts.buildUponParameters();
        List<Tracks.Group> groups = tracks.getGroups();
        if (hasSubs) {
            if (wantedSub == null) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT);
            } else if (wantedSub.getGroupIndex() >= 0 && wantedSub.getGroupIndex() < groups.size()) {
                Tracks.Group g = groups.get(wantedSub.getGroupIndex());
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(
                                g.getMediaTrackGroup(), wantedSub.getTrackIndex()));
            }
        }
        if (wantedAud != null && wantedAud.getGroupIndex() >= 0 && wantedAud.getGroupIndex() < groups.size()) {
            Tracks.Group g = groups.get(wantedAud.getGroupIndex());
            builder.setOverrideForType(new TrackSelectionOverride(
                    g.getMediaTrackGroup(), wantedAud.getTrackIndex()));
        }
        ts.setParameters(builder);
    }

    static boolean isBitmapSubtitleMime(String mime) {
        if (mime == null) {
            return false;
        }
        return MimeTypes.APPLICATION_PGS.equals(mime)
                || MimeTypes.APPLICATION_VOBSUB.equals(mime)
                || MimeTypes.APPLICATION_DVBSUBS.equals(mime)
                || mime.startsWith("image/");
    }

    static String formatSubtitleTrackLabel(Format fmt, int ordinal) {
        String lang = friendlyLanguageName(fmt.language);
        String label = fmt.label != null ? fmt.label.trim() : "";
        if (!label.isEmpty() && !lang.isEmpty() && !label.contains(lang)) {
            return lang + " · " + label;
        }
        if (!label.isEmpty()) {
            return label;
        }
        if (!lang.isEmpty()) {
            return lang;
        }
        return "字幕 " + ordinal;
    }

    static String formatAudioTrackLabel(Format fmt, int ordinal) {
        String lang = friendlyLanguageName(fmt.language);
        String label = fmt.label != null ? fmt.label.trim() : "";
        String base = !label.isEmpty() ? label : (!lang.isEmpty() ? lang : ("音轨 " + ordinal));
        String ch = "";
        if (fmt.channelCount >= 8) {
            ch = "7.1";
        } else if (fmt.channelCount >= 6) {
            ch = "5.1";
        } else if (fmt.channelCount == 2) {
            ch = "2.0";
        }
        if (!ch.isEmpty() && !base.contains(ch)) {
            return base + " · " + ch;
        }
        return base;
    }

    static String friendlyLanguageName(String code) {
        if (code == null || code.trim().isEmpty() || "und".equalsIgnoreCase(code.trim())) {
            return "";
        }
        String c = code.trim().toLowerCase(Locale.US);
        if (c.startsWith("zh-tw") || c.startsWith("zh-hk") || c.startsWith("zh-hant") || c.equals("cht")) {
            return "繁體中文";
        }
        if (c.startsWith("zh") || c.equals("chi") || c.equals("zho") || c.equals("chs")) {
            return "简体中文";
        }
        if (c.startsWith("en") || c.equals("eng")) return "英语";
        if (c.startsWith("ja") || c.equals("jpn")) return "日语";
        if (c.startsWith("ko") || c.equals("kor")) return "韩语";
        if (c.startsWith("fr") || c.equals("fra") || c.equals("fre")) return "法语";
        if (c.startsWith("de") || c.equals("deu") || c.equals("ger")) return "德语";
        if (c.startsWith("es") || c.equals("spa")) return "西班牙语";
        if (c.startsWith("ru") || c.equals("rus")) return "俄语";
        return code.trim();
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
        WatchHistory.save(
                this,
                source,
                item,
                in.getStringExtra(EXTRA_TITLE),
                position(),
                duration(),
                in.getStringExtra(EXTRA_PARENT_ID));
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
