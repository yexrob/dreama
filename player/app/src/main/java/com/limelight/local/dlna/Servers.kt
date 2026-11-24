package com.limelight.local.dlna

import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val TAG = "depth3d-dlna"

private fun httpDate(): String {
    val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
    f.timeZone = TimeZone.getTimeZone("GMT")
    return f.format(Date())
}

/**
 * Discovery on the Wi-Fi: answers M-SEARCH and announces the device, at
 * start and every 30 seconds, since some phone apps only listen for
 * announcements instead of searching.
 */
class SsdpServer(private val ssdp: Ssdp, private val iface: NetworkInterface, private val location: String) {
    private val random = Random()
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "depth3d-ssdp-send").apply { isDaemon = true }
    }
    private val group = InetAddress.getByName(Ssdp.ADDRESS)
    @Volatile
    private var socket: MulticastSocket? = null

    @Throws(IOException::class)
    fun start() {
        val s = MulticastSocket(null as SocketAddress?)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(Ssdp.PORT))
            s.timeToLive = 4
            s.networkInterface = iface
            s.joinGroup(InetSocketAddress(group, Ssdp.PORT), iface)
        } catch (e: IOException) {
            s.close()
            throw e
        }
        socket = s
        Thread({ listen(s) }, "depth3d-ssdp").apply { isDaemon = true }.start()
        timer.schedule({ announce() }, 100, TimeUnit.MILLISECONDS)
        timer.schedule({ announce() }, 1500, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay({ announce() }, 30, 30, TimeUnit.SECONDS)
    }

    private fun listen(s: MulticastSocket) {
        val buf = ByteArray(4096)
        while (!s.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: IOException) {
                if (!s.isClosed) {
                    Log.w(TAG, "ssdp receive", e)
                }
                continue
            }
            val msg = Ssdp.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
            if (!msg.isSearch) {
                continue
            }
            val targets = ssdp.matches(msg.header("ST"))
            if (targets.isEmpty()) {
                continue
            }
            val to = packet.socketAddress
            // Spread the answers inside the searcher's MX, but answer fast: phones list what comes first
            val delay = 20L + random.nextInt((msg.maxWaitSeconds * 1000).coerceAtMost(200))
            try {
                timer.schedule({
                    for ((st, usn) in targets) {
                        send(ssdp.searchResponse(st, usn, location, System.currentTimeMillis()), to)
                    }
                }, delay, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
            }
        }
    }

    private fun announce() {
        val to = InetSocketAddress(group, Ssdp.PORT)
        for ((nt, usn) in ssdp.targets()) {
            send(ssdp.alive(nt, usn, location), to)
        }
    }

    private fun send(text: String, to: SocketAddress) {
        val s = socket ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            s.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: IOException) {
            Log.w(TAG, "ssdp send", e)
        }
    }

    fun stop() {
        timer.shutdownNow()
        val s = socket ?: return
        val to = InetSocketAddress(group, Ssdp.PORT)
        // Twice, as UDP may drop one; phones then take the headset off their list at once
        repeat(2) {
            for ((nt, usn) in ssdp.targets()) {
                send(ssdp.byebye(nt, usn), to)
            }
        }
        socket = null
        try {
            s.leaveGroup(InetSocketAddress(group, Ssdp.PORT), iface)
        } catch (_: IOException) {
        }
        s.close()
    }
}

/** The receiver's HTTP server: one short request per connection, on a small pool. */
class HttpServer(
    private val handler: DlnaHandler,
    private val serverHeader: String,
    private val onSubscribed: (Subscription) -> Unit,
) {
    private val pool = ThreadPoolExecutor(2, 16, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "depth3d-http").apply { isDaemon = true }
    }
    @Volatile
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    @Throws(IOException::class)
    fun start(address: Inet4Address, preferredPort: Int) {
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, preferredPort))
            }
        } catch (_: IOException) {
            // Taken: any port does, as phones read it from the description's location
            ServerSocket().apply { bind(InetSocketAddress(address, 0)) }
        }
        server = ss
        Thread({ accept(ss) }, "depth3d-http-accept").apply { isDaemon = true }.start()
    }

    private fun accept(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (!ss.isClosed) {
                    Log.w(TAG, "http accept", e)
                }
                continue
            }
            try {
                pool.execute { serve(socket) }
            } catch (_: RejectedExecutionException) {
                close(socket)
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            socket.tcpNoDelay = true
            val req = Http.read(BufferedInputStream(socket.getInputStream())) ?: return
            val handled = handler.handle(req, SystemClock.elapsedRealtime())
            Http.write(socket.getOutputStream(), handled.response, serverHeader, httpDate())
            if (req.method != "GET") {
                Log.d(TAG, "${req.method} ${req.path} ${req.header("soapaction") ?: ""} -> ${handled.response.status}")
            }
            handled.subscribed?.let(onSubscribed)
        } catch (e: Exception) {
            Log.w(TAG, "http request", e)
        } finally {
            close(socket)
        }
    }

    private fun close(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        pool.shutdownNow()
    }
}

/**
 * Sends NOTIFYs one at a time, so each subscriber gets them in SEQ order.
 * A subscriber that stops answering (its app was closed) is dropped after a
 * few tries instead of holding up the others until it expires.
 */
class EventSender(private val drop: (String) -> Unit) {
    private val exec = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(256),
        { r -> Thread(r, "depth3d-gena").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardOldestPolicy())
    private val failures = ConcurrentHashMap<String, Int>()

    fun send(d: Delivery) {
        try {
            exec.execute { deliver(d) }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun deliver(d: Delivery) {
        for (m in d.messages) {
            if (post(m)) {
                failures.remove(d.sid)
                return
            }
        }
        val n = (failures[d.sid] ?: 0) + 1
        if (n >= 3) {
            failures.remove(d.sid)
            drop(d.sid)
        } else {
            failures[d.sid] = n
        }
    }

    private fun post(m: NotifyMessage): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(m.host, m.port), 2000)
            s.soTimeout = 3000
            s.getOutputStream().apply {
                write(m.bytes)
                flush()
            }
            // Any answer means it arrived; some control points answer 412 to a SEQ they did not expect
            s.getInputStream().read() >= 0
        }
    } catch (_: IOException) {
        false
    }

    fun stop() {
        exec.shutdownNow()
    }
}
