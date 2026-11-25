package com.limelight.local.dlna

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.limelight.R
import com.limelight.local.LocalPlayerXR
import com.limelight.local.wall.WallActivity
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The DLNA receiver (a UPnP MediaRenderer) as a foreground service, so it
 * keeps answering the phone while the immersive player is in front of the
 * wall. It runs only while [CastReceiver] is held, holds a Wi-Fi multicast
 * lock for discovery, follows the Wi-Fi address, and opens what a phone
 * casts in LocalPlayerXR.
 */
class DlnaService : Service(), RendererHost {
    companion object {
        private const val TAG = "depth3d-dlna"
        private const val CHANNEL = "cast_receiver"
        private const val NOTIFICATION_ID = 0xD1A
        private const val HTTP_PORT = 49494
        private const val TICK_MS = 250L
        private const val NET_CHECK_MS = 3000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val subs = Subscriptions { UUID.randomUUID().toString() }
    private lateinit var audio: AudioManager
    private lateinit var renderer: MediaRenderer
    private lateinit var handler: DlnaHandler
    private lateinit var events: EventSender
    private lateinit var name: String
    private lateinit var udn: String
    private lateinit var serverHeader: String
    private var lock: WifiManager.MulticastLock? = null
    private var worker: ScheduledExecutorService? = null
    private var listener: PlayerLink.Listener? = null

    // Worker thread only
    private var http: HttpServer? = null
    private var ssdp: SsdpServer? = null
    private var bound: String? = null
    private var checked = false

    // The volume a phone last set and the step it became, so reading it back gives the phone its own number
    @Volatile
    private var phoneVolume: Pair<Int, Int>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!startInForeground()) {
            stopSelf()
            return
        }
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        name = CastReceiver.name(this)
        udn = CastReceiver.udn(this)
        serverHeader = "Android/${Build.VERSION.RELEASE} UPnP/1.0 Dreama/1.0"
        // Cast ids from the clock, so a player left from an earlier run never matches a new cast
        renderer = MediaRenderer(this, System.currentTimeMillis())
        handler = DlnaHandler(renderer, subs) { Descriptions.device(name, udn, version()) }
        events = EventSender { sid -> subs.drop(sid) }
        renderer.events = { service, vars ->
            handler.deliveries(service, vars, SystemClock.elapsedRealtime()).forEach(events::send)
        }
        val l = PlayerLink.Listener { id, st, pos, dur -> renderer.onPlayer(id, st, pos, dur, SystemClock.elapsedRealtime()) }
        listener = l
        PlayerLink.listener = l
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("depth3d-dlna").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock", e)
        }
        CastReceiver.publish(ReceiverStatus(name, ReceiverStatus.Phase.STARTING))
        worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "depth3d-dlna").apply { isDaemon = true } }
            .also {
                it.scheduleWithFixedDelay({ guarded { checkNetwork() } }, 0, NET_CHECK_MS, TimeUnit.MILLISECONDS)
                it.scheduleWithFixedDelay({ guarded { tick() } }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        worker?.let {
            // Byebye goes out on the network, so off the main thread
            it.execute { guarded { stopServers() } }
            it.shutdown()
        }
        worker = null
        if (::events.isInitialized) {
            events.stop()
        }
        subs.clear()
        listener?.let { if (PlayerLink.listener === it) PlayerLink.listener = null }
        try {
            lock?.release()
        } catch (_: Exception) {
        }
        lock = null
        if (::name.isInitialized) {
            CastReceiver.publish(ReceiverStatus(name, ReceiverStatus.Phase.OFF))
        }
        super.onDestroy()
    }

    private fun startInForeground(): Boolean {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.airplay_notif_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val open = PendingIntent.getActivity(this, 0,
                Intent(this, WallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast_receiver)
                .setContentTitle(getString(R.string.cast_waiting))
                .setContentText(getString(R.string.dlna_notif_text))
                .setOngoing(true)
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "foreground refused", e)
            CastReceiver.publish(ReceiverStatus(CastReceiver.name(this), ReceiverStatus.Phase.FAILED))
            return false
        }
    }

    private fun version(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "receiver", e)
        }
    }

    private fun tick() {
        renderer.tick(SystemClock.elapsedRealtime())
        val snap = renderer.snapshot()
        val live = snap.transportState == MediaRenderer.PLAYING || snap.transportState == MediaRenderer.PAUSED ||
            snap.transportState == MediaRenderer.TRANSITIONING
        val casting = if (live) snap.title ?: getString(R.string.dlna_untitled) else null
        CastReceiver.update { if (it.casting == casting) it else it.copy(casting = casting) }
    }

    private fun interfaces(): List<NetIface> {
        val all = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            return emptyList()
        }
        // One interface going away while it is read does not hide the others
        return all.mapNotNull { i ->
            try {
                NetIface(i.name, i.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress },
                    i.isUp, i.isLoopback)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun checkNetwork() {
        val pick = NetPick.choose(interfaces())
        val ip = pick?.second
        if (checked && ip == bound && (ip == null || http != null)) {
            return
        }
        checked = true
        stopServers()
        bound = ip
        if (pick == null) {
            CastReceiver.update { it.copy(name = name, phase = ReceiverStatus.Phase.NO_NETWORK, address = null) }
            return
        }
        try {
            val address = InetAddress.getByName(pick.second) as Inet4Address
            val iface = NetworkInterface.getByName(pick.first) ?: throw IllegalStateException("no ${pick.first}")
            val h = HttpServer(handler, serverHeader) { s -> handler.initial(s)?.let(events::send) }
            h.start(address, HTTP_PORT)
            http = h
            val s = SsdpServer(Ssdp(udn, serverHeader), iface, "http://${pick.second}:${h.port}${Descriptions.DESCRIPTION_PATH}")
            s.start()
            ssdp = s
            Log.i(TAG, "receiver \"$name\" on ${pick.first} ${pick.second}:${h.port}")
            CastReceiver.update { it.copy(name = name, phase = ReceiverStatus.Phase.WAITING, address = pick.second) }
        } catch (e: Exception) {
            Log.w(TAG, "receiver start on ${pick.first}", e)
            stopServers()
            // Tried again at the next check
            bound = null
            CastReceiver.update { it.copy(name = name, phase = ReceiverStatus.Phase.FAILED, address = null) }
        }
    }

    private fun stopServers() {
        ssdp?.stop()
        ssdp = null
        http?.stop()
        http = null
    }

    // ---- RendererHost: network threads in, the player's main thread out

    override fun open(media: CastMedia) {
        main.post {
            val intent = Intent(this, LocalPlayerXR::class.java)
                .setData(Uri.parse(media.uri))
                .putExtra(LocalPlayerXR.EXTRA_TITLE, media.title ?: getString(R.string.dlna_untitled))
                .putExtra(LocalPlayerXR.EXTRA_START_MS, media.startMs)
                .putExtra(LocalPlayerXR.EXTRA_CAST_ID, media.castId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            media.mimeType?.let { intent.putExtra(LocalPlayerXR.EXTRA_MIME, it) }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "cast did not open", e)
            }
        }
    }

    override fun play(castId: Long) = PlayerLink.play(castId)
    override fun pause(castId: Long) = PlayerLink.pause(castId)
    override fun seek(castId: Long, ms: Long) = PlayerLink.seek(castId, ms)
    override fun close(castId: Long) = PlayerLink.close(castId)

    private fun maxVolume() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    override fun volume(): Int {
        val step = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        phoneVolume?.let { (percent, set) -> if (set == step) return percent }
        return Math.round(step * 100f / maxVolume())
    }

    override fun setVolume(volume: Int) {
        val step = Math.round(volume * maxVolume() / 100f)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
            phoneVolume = volume to step
        } catch (e: SecurityException) {
            // Do Not Disturb holds the volume
            Log.w(TAG, "volume", e)
        }
    }

    override fun muted(): Boolean = audio.isStreamMute(AudioManager.STREAM_MUSIC)

    override fun setMuted(muted: Boolean) {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "mute", e)
        }
    }
}
