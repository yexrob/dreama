package com.limelight.local.airplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.local.wall.WallActivity;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The AirPlay screen mirroring receiver, running for as long as the wall
 * is open. It starts UxPlay's server (libairplay), announces it on the
 * Wi-Fi as _airplay._tcp and _raop._tcp through NsdManager, and when an
 * iPad or iPhone starts mirroring brings up AirPlayPlayerXR, which shows
 * the picture on the renderer's screen. When the device stops, or goes
 * away, the player closes and the wall is back.
 *
 * NsdManager rather than a responder of our own (jmDNS is in the app):
 * the system's mDNS daemon already answers on every interface, keeps
 * answering while this process is busy or in the background, handles the
 * probing and renaming a name clash needs, and does not fight the other
 * responders in the process (the Moonlight host search) for port 5353.
 * The multicast lock keeps the Wi-Fi chip from filtering out the iPad's
 * queries while the headset idles.
 *
 * Threads: the state below lives on the main thread. Starting, resetting
 * and stopping the server join UxPlay's threads, so they run on one
 * control thread shared by every instance of this service, never on a
 * callback thread. Video and audio go straight from UxPlay's threads into
 * MirrorVideo and MirrorAudio.
 *
 * Every device that asks is let in: there is no PIN and no first time
 * confirmation yet (ux-spec 3.6 wants a card for that in VR, which does
 * not exist), so anyone on the same Wi-Fi can mirror to the headset.
 */
public class AirPlayService extends Service implements AirPlayNative.Listener {
    public static final String DEFAULT_NAME = "depth3d";
    // UxPlay's default as well: H.265 is offered only when asked for. The
    // decoding side handles it; turning this on offers it to devices that
    // can send it, where the headset has a hardware decoder.
    static final boolean OFFER_H265 = false;
    // The display offered to the device, and the most frames a second it
    // should send. 30 is UxPlay's default and what the depth model keeps
    // up with.
    static final int OFFER_WIDTH = 1920;
    static final int OFFER_HEIGHT = 1080;
    static final int OFFER_FPS = 30;
    // AirPlay's usual port; another is taken when it is in use
    private static final int PORT = 7000;
    private static final int LOG_LEVEL = 6;      // LOGGER_INFO

    // A stream ending is final once this passes without a new one: a
    // device taking over from another stops one and starts the next
    private static final long END_GRACE_MS = 1000;
    private static final long TAKEOVER_GRACE_MS = 6000;
    // iOS reports in every two seconds while mirroring; UxPlay's limit
    private static final long FEEDBACK_LIMIT_MS = 15000;
    private static final long WATCHDOG_MS = 2000;
    // Asked to mirror but no picture: back to waiting after this
    private static final long CONNECT_TIMEOUT_MS = 20000;
    // A record the system would not take is tried again this often
    private static final long ANNOUNCE_RETRY_MS = 3000;
    private static final int ANNOUNCE_TRIES = 3;

    private static final String CHANNEL = "airplay";
    private static final int NOTIFICATION_ID = 0xA1A1;
    private static final String PREFS = "airplay";
    private static final String PREF_DEVICE_ID = "device_id";

    private static final String[] TYPES = {"_raop._tcp", "_airplay._tcp"};

    private static volatile AirPlayService instance;

    private static Handler ctl;

    private static synchronized Handler control() {
        if (ctl == null) {
            HandlerThread t = new HandlerThread("airplay-ctl");
            t.start();
            ctl = new Handler(t.getLooper());
        }
        return ctl;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final MirrorAudio audio = new MirrorAudio();
    private volatile MirrorVideo video;
    private volatile boolean offeredH265;
    private volatile long lastFeedbackMs;
    private volatile float reportedW, reportedH;

    // Control thread
    private NsdManager nsd;
    private final NsdManager.RegistrationListener[] registrations = new NsdManager.RegistrationListener[2];
    private int announcedPort;
    private int announceFailures;
    private boolean serverUp;

    // Main thread
    private static int session;
    private AirPlayState.Phase phase = AirPlayState.Phase.STARTING;
    private String problem;
    private String client;
    private int connections;
    private boolean mirroring;
    private long graceMs = END_GRACE_MS;
    private final Set<Network> networks = new HashSet<>();
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private WifiManager.MulticastLock multicast;

    private final Runnable endRunnable = () -> endSession("the stream ended");
    private final Runnable connectTimeout = this::connectTimedOut;
    private final Runnable watchdog = this::checkFeedback;

    // ---- for the wall and the player ----

    /** Starts the receiver, if it is not running. From a visible activity. */
    public static void start(Context c) {
        Intent i = new Intent(c, AirPlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (RuntimeException e) {
            LimeLog.warning("AirPlay: service did not start: " + e);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, AirPlayService.class));
    }

    /** Restarts the AirPlay receiver when the user renames the headset in Settings. */
    public static void reloadName(Context c) {
        if (instance != null) {
            stop(c);
            start(c);
        }
    }

    private String advertisedName() {
        return com.limelight.local.dlna.CastReceiver.name(this);
    }

    /** Ends the mirroring here: the device is let go and told it ended. */
    public static void disconnect() {
        AirPlayService s = instance;
        if (s != null) {
            s.main.post(s::dropClients);
        }
    }

    static MirrorVideo video() {
        AirPlayService s = instance;
        return s == null ? null : s.video;
    }

    /** The picture size the device last reported for this session, or null. */
    static float[] reportedSize() {
        AirPlayService s = instance;
        if (s == null || s.reportedW <= 0 || s.reportedH <= 0) {
            return null;
        }
        return new float[]{s.reportedW, s.reportedH};
    }

    // ---- service ----

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        goForeground();
        publish();

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            try {
                multicast = wifi.createMulticastLock("depth3d-airplay");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            } catch (RuntimeException e) {
                // API 34 and later: the permission is not asked for there,
                // and NsdManager's advertiser does not need it
                multicast = null;
                LimeLog.info("AirPlay: no multicast lock: " + e);
            }
        }
        watchNetworks();
        control().post(this::startServer);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped away from recents: the wall is gone, so is the receiver
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        main.removeCallbacksAndMessages(null);
        if (networkCallback != null && connectivity != null) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
        }
        mirroring = false;
        client = null;
        phase = AirPlayState.Phase.OFF;
        publish();
        control().post(this::stopServer);
        if (multicast != null && multicast.isHeld()) {
            multicast.release();
        }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    // ---- control thread ----

    private void startServer() {
        if (!AirPlayNative.available()) {
            main.post(() -> fail("投屏组件没有装好"));
            return;
        }
        video = MirrorVideo.create();
        if (video == null) {
            main.post(() -> fail("画面解码没有准备好"));
            return;
        }
        boolean h265 = OFFER_H265 && MirrorVideo.hasHevcDecoder();
        offeredH265 = h265;
        File key = new File(new File(getFilesDir(), "airplay"), "key.pem");
        File dir = key.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            LimeLog.warning("AirPlay: cannot make " + dir);
        }
        String receiverName = advertisedName();
        AirPlayNative.setListener(this);
        int port = AirPlayNative.nativeStart(receiverName.getBytes(StandardCharsets.UTF_8), deviceId(),
                OFFER_WIDTH, OFFER_HEIGHT, OFFER_FPS, h265, key.getAbsolutePath(), PORT, LOG_LEVEL);
        if (port <= 0) {
            AirPlayNative.clearListener(this);
            final int code = port;
            main.post(() -> fail("启动失败（" + code + "）"));
            return;
        }
        serverUp = true;
        LimeLog.info("depth3d: AirPlay receiver \"" + receiverName + "\" on port " + port + (h265 ? ", H.265 offered" : ""));
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        announce(port);
        main.post(() -> {
            if (phase == AirPlayState.Phase.STARTING) {
                phase = idlePhase();
                publish();
            }
        });
    }

    private void stopServer() {
        unannounce();
        if (serverUp) {
            AirPlayNative.nativeStop();
            serverUp = false;
        }
        AirPlayNative.clearListener(this);
        MirrorVideo v = video;
        video = null;
        if (v != null) {
            v.release();
        }
        audio.release();
        LimeLog.info("AirPlay: receiver stopped");
    }

    private void resetServer() {
        if (!serverUp) {
            return;
        }
        int port = AirPlayNative.nativeReset();
        if (port <= 0) {
            LimeLog.warning("AirPlay: reset failed: " + port);
            serverUp = false;
            unannounce();
            main.post(() -> fail("重新开始接收失败（" + port + "）"));
            return;
        }
        announce(port);
    }

    private void announce(int port) {
        if (nsd == null || (port == announcedPort && registrations[0] != null && registrations[1] != null)) {
            return;
        }
        unannounce();
        for (int which = AirPlayNative.RAOP; which <= AirPlayNative.AIRPLAY; which++) {
            byte[] name = AirPlayNative.nativeServiceName(which);
            int servicePort = AirPlayNative.nativeServicePort(which);
            String[] txt = AirPlayNative.nativeTxt(which);
            if (name == null || txt == null || servicePort <= 0) {
                LimeLog.warning("AirPlay: no " + TYPES[which] + " record");
                continue;
            }
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(new String(name, StandardCharsets.UTF_8));
            info.setServiceType(TYPES[which]);
            info.setPort(servicePort);
            for (int i = 0; i + 1 < txt.length; i += 2) {
                try {
                    info.setAttribute(txt[i], txt[i + 1]);
                } catch (IllegalArgumentException e) {
                    LimeLog.warning("AirPlay: TXT " + txt[i] + " left out: " + e.getMessage());
                }
            }
            NsdManager.RegistrationListener l = new Registration(which);
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
                registrations[which] = l;
            } catch (RuntimeException e) {
                LimeLog.warning("AirPlay: " + TYPES[which] + " not announced: " + e);
            }
        }
        announcedPort = port;
    }

    private void unannounce() {
        for (int i = 0; i < registrations.length; i++) {
            if (registrations[i] != null && nsd != null) {
                try {
                    nsd.unregisterService(registrations[i]);
                } catch (RuntimeException ignored) {
                }
            }
            registrations[i] = null;
        }
        announcedPort = 0;
    }

    // Control thread: a record the system did not take
    private void announceFailed(Registration r, int errorCode) {
        if (registrations[r.which] != r) {
            return;
        }
        registrations[r.which] = null;
        if (!serverUp) {
            return;
        }
        if (++announceFailures > ANNOUNCE_TRIES) {
            main.post(() -> fail("没能在 Wi-Fi 里公布这个名字（" + errorCode + "）"));
            return;
        }
        control().postDelayed(() -> {
            if (serverUp && (registrations[0] == null || registrations[1] == null)) {
                announcedPort = 0;
                announce(AirPlayNative.nativeServicePort(AirPlayNative.AIRPLAY));
            }
        }, ANNOUNCE_RETRY_MS);
    }

    // Its callbacks come on NsdManager's thread
    private final class Registration implements NsdManager.RegistrationListener {
        final int which;

        Registration(int which) {
            this.which = which;
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo info) {
            LimeLog.info("AirPlay: announced " + TYPES[which] + " as \"" + info.getServiceName() + "\"");
            control().post(() -> {
                if (registrations[which] == this) {
                    announceFailures = 0;
                }
            });
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
            LimeLog.warning("AirPlay: " + TYPES[which] + " announcement failed: " + errorCode);
            control().post(() -> announceFailed(this, errorCode));
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo info) {
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
        }
    }

    // The six byte id the records and the pairing go by: random once, then
    // kept, locally administered so it cannot clash with real hardware
    private byte[] deviceId() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String hex = p.getString(PREF_DEVICE_ID, null);
        if (hex != null && hex.length() == 12) {
            byte[] id = new byte[6];
            try {
                for (int i = 0; i < 6; i++) {
                    id[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
                }
                return id;
            } catch (NumberFormatException ignored) {
            }
        }
        byte[] id = new byte[6];
        new SecureRandom().nextBytes(id);
        id[0] = (byte) ((id[0] & 0xFC) | 0x02);
        StringBuilder sb = new StringBuilder();
        for (byte b : id) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        }
        p.edit().putString(PREF_DEVICE_ID, sb.toString()).apply();
        return id;
    }

    // ---- main thread ----

    private void publish() {
        AirPlayState.publish(new AirPlayState.Status(phase, advertisedName(), client, session, problem));
    }

    private AirPlayState.Phase idlePhase() {
        return networks.isEmpty() ? AirPlayState.Phase.NO_WIFI : AirPlayState.Phase.WAITING;
    }

    private void fail(String why) {
        if (phase == AirPlayState.Phase.OFF) {
            return;
        }
        endSession("failed: " + why);
        problem = why;
        phase = AirPlayState.Phase.FAILED;
        publish();
    }

    private void watchNetworks() {
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            return;
        }
        Network active = connectivity.getActiveNetwork();
        if (active != null && isLan(connectivity.getNetworkCapabilities(active))) {
            networks.add(active);
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                main.post(() -> {
                    networks.add(network);
                    networksChanged();
                });
            }

            @Override
            public void onLost(Network network) {
                main.post(() -> {
                    networks.remove(network);
                    networksChanged();
                });
            }
        };
        try {
            connectivity.registerNetworkCallback(request, networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;
            LimeLog.warning("AirPlay: cannot watch the Wi-Fi: " + e);
        }
    }

    private static boolean isLan(NetworkCapabilities caps) {
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private void networksChanged() {
        if (phase == AirPlayState.Phase.WAITING && networks.isEmpty()) {
            phase = AirPlayState.Phase.NO_WIFI;
            publish();
        } else if (phase == AirPlayState.Phase.NO_WIFI && !networks.isEmpty()) {
            phase = AirPlayState.Phase.WAITING;
            publish();
            // The system's daemon keeps the records across a reconnect; only
            // ones that never made it are tried again
            control().post(() -> {
                if (serverUp && (registrations[0] == null || registrations[1] == null)) {
                    int port = AirPlayNative.nativeServicePort(AirPlayNative.AIRPLAY);
                    announcedPort = 0;
                    announce(port);
                }
            });
        }
    }

    private void clientAsked(String who) {
        client = who;
        if (mirroring) {
            publish();
            return;
        }
        if (phase == AirPlayState.Phase.WAITING || phase == AirPlayState.Phase.NO_WIFI
                || phase == AirPlayState.Phase.CONNECTING) {
            phase = AirPlayState.Phase.CONNECTING;
            publish();
            main.removeCallbacks(connectTimeout);
            main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        }
    }

    private void connectTimedOut() {
        if (phase == AirPlayState.Phase.CONNECTING && !mirroring) {
            client = null;
            phase = idlePhase();
            publish();
        }
    }

    private void mirrorStarted() {
        if (phase == AirPlayState.Phase.OFF || phase == AirPlayState.Phase.FAILED) {
            return;
        }
        main.removeCallbacks(endRunnable);
        main.removeCallbacks(connectTimeout);
        graceMs = END_GRACE_MS;
        lastFeedbackMs = SystemClock.elapsedRealtime();
        if (!mirroring) {
            mirroring = true;
            session++;
            LimeLog.info("AirPlay: mirroring from " + (client != null ? client : "a device"));
        }
        phase = AirPlayState.Phase.MIRRORING;
        publish();
        updateNotification();
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, WATCHDOG_MS);
        launchPlayer();
    }

    private void launchPlayer() {
        Intent i = new Intent(this, AirPlayPlayerXR.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AirPlayPlayerXR.EXTRA_SESSION, session);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            // Not allowed from the background on this system: the
            // notification opens it instead
            LimeLog.warning("AirPlay: player did not start: " + e);
        }
    }

    private void scheduleEnd(long ms) {
        if (!mirroring && phase != AirPlayState.Phase.CONNECTING) {
            return;
        }
        main.removeCallbacks(endRunnable);
        main.postDelayed(endRunnable, ms);
    }

    private void endSession(String why) {
        main.removeCallbacks(endRunnable);
        main.removeCallbacks(connectTimeout);
        main.removeCallbacks(watchdog);
        boolean was = mirroring || phase == AirPlayState.Phase.CONNECTING;
        mirroring = false;
        client = null;
        graceMs = END_GRACE_MS;
        reportedW = 0;
        reportedH = 0;
        MirrorVideo v = video;
        if (v != null) {
            v.reset();
        }
        audio.reset();
        if (phase == AirPlayState.Phase.MIRRORING || phase == AirPlayState.Phase.CONNECTING) {
            phase = idlePhase();
            publish();
            updateNotification();
        }
        if (was) {
            LimeLog.info("AirPlay: session over: " + why);
        }
    }

    private void dropClients() {
        endSession("closed on the headset");
        control().post(this::resetServer);
    }

    private void checkFeedback() {
        if (!mirroring) {
            return;
        }
        if (SystemClock.elapsedRealtime() - lastFeedbackMs > FEEDBACK_LIMIT_MS) {
            LimeLog.warning("AirPlay: nothing from the device for " + FEEDBACK_LIMIT_MS / 1000 + " s");
            dropClients();
            return;
        }
        main.postDelayed(watchdog, WATCHDOG_MS);
    }

    // ---- notification ----

    private void goForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (RuntimeException e) {
            LimeLog.warning("AirPlay: not in the foreground: " + e);
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, buildNotification());
            } catch (RuntimeException ignored) {
            }
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "投屏接收",
                        NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open;
        if (mirroring) {
            b.setContentTitle(AirPlayState.phrase("正在显示", client, "的屏幕"));
            b.setContentText("点这里回到 3D 画面");
            open = new Intent(this, AirPlayPlayerXR.class)
                    .putExtra(AirPlayPlayerXR.EXTRA_SESSION, session);
        } else {
            b.setContentTitle("可以接收投屏");
            b.setContentText("在 iPad 或 iPhone 的「屏幕镜像」里选「" + advertisedName() + "」");
            open = new Intent(this, WallActivity.class);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        b.setContentIntent(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        b.setSmallIcon(R.drawable.ic_play);
        b.setOngoing(true);
        b.setShowWhen(false);
        return b.build();
    }

    // ---- UxPlay callbacks, on its threads ----

    @Override
    public void onConnInit() {
        main.post(() -> connections++);
    }

    @Override
    public void onConnDestroy() {
        main.post(() -> {
            connections = Math.max(0, connections - 1);
            if (connections == 0) {
                if (mirroring) {
                    scheduleEnd(END_GRACE_MS);
                } else if (phase == AirPlayState.Phase.CONNECTING) {
                    endSession("the device went away");
                }
            }
        });
    }

    @Override
    public void onConnReset(int reason) {
        LimeLog.info("AirPlay: connection reset (" + reason + ")");
        main.post(() -> {
            endSession("connection reset");
            control().post(this::resetServer);
        });
    }

    @Override
    public void onConnFeedback() {
        lastFeedbackMs = SystemClock.elapsedRealtime();
    }

    @Override
    public boolean onClientRequest(String deviceId, String model, String name) {
        String who = name != null && !name.isEmpty() ? name : deviceKind(model);
        LimeLog.info("AirPlay: " + (model != null ? model : "a device") + " asks to connect");
        main.post(() -> clientAsked(who));
        // Let in without asking: there is no confirmation card yet
        return true;
    }

    // "iPad13,4" -> "iPad"
    private static String deviceKind(String model) {
        if (model == null) {
            return null;
        }
        int i = 0;
        while (i < model.length() && Character.isLetter(model.charAt(i))) {
            i++;
        }
        return i > 0 ? model.substring(0, i) : null;
    }

    @Override
    public void onMirrorRunning(boolean running) {
        main.post(() -> {
            if (running) {
                mirrorStarted();
            } else {
                scheduleEnd(graceMs);
            }
        });
    }

    @Override
    public int onVideoCodec(boolean h265) {
        if (h265 && !offeredH265) {
            LimeLog.warning("AirPlay: H.265 sent but not offered");
            return -1;
        }
        return 0;
    }

    @Override
    public void onVideoSize(float srcW, float srcH, float w, float h) {
        LimeLog.info(String.format(Locale.ROOT, "AirPlay: screen %.0fx%.0f, stream %.0fx%.0f", srcW, srcH, w, h));
        reportedW = w;
        reportedH = h;
        MirrorVideo v = video;
        if (v != null) {
            v.setStreamSize((int) w, (int) h);
        }
    }

    @Override
    public void onVideoFrame(ByteBuffer frame, boolean h265) {
        MirrorVideo v = video;
        if (v != null) {
            v.feed(frame, h265);
        }
    }

    @Override
    public void onVideoReset(int type) {
        if (type == AirPlayNative.RESET_NOHOLD) {
            // Another device takes over: its stream starts from scratch,
            // and the player stays up if it does so soon
            MirrorVideo v = video;
            if (v != null) {
                v.reset();
            }
            audio.reset();
            main.post(() -> graceMs = TAKEOVER_GRACE_MS);
        } else if (type == AirPlayNative.RESET_RTP_SHUTDOWN) {
            main.post(() -> scheduleEnd(graceMs));
        }
    }

    @Override
    public void onVideoFlush() {
        // UxPlay calls this whenever any of the device's connections
        // closes, mirroring or not, so it is not a reason to drop the
        // picture; the end of the stream is seen above
    }

    @Override
    public void onAudioFormat(int ct, int spf, boolean usingScreen, boolean isMedia) {
        LimeLog.info("AirPlay: audio type " + ct + ", " + spf + " samples a frame"
                + (usingScreen ? ", with the screen" : ", on its own"));
        audio.format(ct, spf);
    }

    @Override
    public void onAudioFrame(ByteBuffer frame, int ct) {
        audio.feed(frame, ct);
    }

    @Override
    public void onAudioFlush() {
        audio.flush();
    }

    @Override
    public void onAudioVolume(float db) {
        audio.setVolume(db);
    }
}
