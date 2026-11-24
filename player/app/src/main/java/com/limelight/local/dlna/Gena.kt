package com.limelight.local.dlna

import java.net.URI

/** One evented value in LastChange: `<Volume channel="Master" val="30"/>`. */
data class EventVar(val name: String, val value: String, val channel: String? = null)

/** Where a NOTIFY goes: the host and port to connect to, and the bytes to send. */
class NotifyMessage(val host: String, val port: Int, val bytes: ByteArray)

/**
 * GENA eventing: SUBSCRIBE headers in, LastChange and the NOTIFY request out.
 * LastChange is XML carried as the text of another XML element, so its values
 * are escaped once as attributes and the whole document once more as text.
 */
object Gena {
    const val DEFAULT_TIMEOUT = 1800
    private const val MIN_TIMEOUT = 60
    private const val MAX_TIMEOUT = 7200

    const val AVT_NS = "urn:schemas-upnp-org:metadata-1-0/AVT/"
    const val RCS_NS = "urn:schemas-upnp-org:metadata-1-0/RCS/"

    private val angle = Regex("<([^>]+)>")

    /** `<http://a/b><http://c/d>`; senders that leave out the brackets get read as one URL. */
    fun parseCallbacks(header: String?): List<String> {
        val h = header?.trim() ?: return emptyList()
        val urls = angle.findAll(h).map { it.groupValues[1].trim() }.toMutableList()
        if (urls.isEmpty() && h.isNotEmpty()) {
            urls += h
        }
        return urls.filter { it.startsWith("http://", ignoreCase = true) }
    }

    /** "Second-1800", "Second-infinite", or nothing; held to what the receiver keeps. */
    fun parseTimeout(header: String?): Int {
        val h = header?.trim()?.lowercase() ?: return DEFAULT_TIMEOUT
        if (h.contains("infinite")) {
            return MAX_TIMEOUT
        }
        val n = h.removePrefix("second-").trim().toIntOrNull() ?: return DEFAULT_TIMEOUT
        return n.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
    }

    fun timeoutHeader(seconds: Int) = "Second-$seconds"

    fun lastChange(namespace: String, vars: List<EventVar>): String {
        val b = StringBuilder("<Event xmlns=\"").append(namespace).append("\"><InstanceID val=\"0\">")
        for (v in vars) {
            b.append('<').append(v.name)
            if (v.channel != null) {
                b.append(" channel=\"").append(v.channel).append('"')
            }
            b.append(" val=\"").append(Xml.escape(v.value)).append("\"/>")
        }
        b.append("</InstanceID></Event>")
        return b.toString()
    }

    /** The NOTIFY body: one `<e:property>` per variable, values escaped as text. */
    fun propertySet(props: List<Pair<String, String>>): String {
        val b = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        b.append("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">")
        for ((k, v) in props) {
            b.append("<e:property><").append(k).append('>').append(Xml.escapeText(v))
                .append("</").append(k).append("></e:property>")
        }
        b.append("</e:propertyset>")
        return b.toString()
    }

    /** The request for [callback], or null when it is not an http URL with a host. */
    fun notify(callback: String, sid: String, seq: Long, body: String): NotifyMessage? {
        val uri = try {
            URI(callback.trim())
        } catch (_: Exception) {
            return null
        }
        if (!"http".equals(uri.scheme, ignoreCase = true) || uri.host.isNullOrEmpty()) {
            return null
        }
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.ifEmpty { null } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = "NOTIFY $path HTTP/1.1\r\n" +
            "HOST: ${uri.host}:$port\r\n" +
            "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
            "NT: upnp:event\r\n" +
            "NTS: upnp:propchange\r\n" +
            "SID: $sid\r\n" +
            "SEQ: $seq\r\n" +
            "CONTENT-LENGTH: ${payload.size}\r\n" +
            "CONNECTION: close\r\n\r\n"
        return NotifyMessage(uri.host, port, head.toByteArray(Charsets.ISO_8859_1) + payload)
    }
}

/** One control point listening to one service. */
class Subscription(val sid: String, val service: UpnpService, val callbacks: List<String>, var timeout: Int,
                   var expiresAt: Long) {
    internal var nextSeq = 0L
    /** False until its first NOTIFY (the full state) is on its way; nothing else goes out before that. */
    internal var ready = false
}

/**
 * Who listens to what, with an injected clock and SID source so expiry and
 * renewal can be tested. Thread safe: control calls, the event ticker and the
 * HTTP threads all reach it.
 */
class Subscriptions(private val newSid: () -> String) {
    private val subs = LinkedHashMap<String, Subscription>()

    /** Most a single service keeps; a phone that subscribes on every reconnect cannot pile them up. */
    private val perService = 16

    @Synchronized
    fun subscribe(service: UpnpService, callbacks: List<String>, timeout: Int, now: Long): Subscription {
        purge(now)
        val mine = subs.values.filter { it.service == service }
        if (mine.size >= perService) {
            subs.remove(mine.minByOrNull { it.expiresAt }!!.sid)
        }
        val s = Subscription("uuid:" + newSid(), service, callbacks, timeout, now + timeout * 1000L)
        subs[s.sid] = s
        return s
    }

    @Synchronized
    fun renew(sid: String, timeout: Int, now: Long): Subscription? {
        purge(now)
        val s = subs[sid.trim()] ?: return null
        s.timeout = timeout
        s.expiresAt = now + timeout * 1000L
        return s
    }

    @Synchronized
    fun unsubscribe(sid: String): Boolean = subs.remove(sid.trim()) != null

    /** Marks the subscription live and claims SEQ 0 for its first NOTIFY; null if it is already gone. */
    @Synchronized
    fun claimInitial(sid: String): Long? {
        val s = subs[sid] ?: return null
        s.ready = true
        s.nextSeq = 1
        return 0
    }

    /** The live subscriptions of [service], each with the SEQ its next NOTIFY carries. */
    @Synchronized
    fun forEvent(service: UpnpService, now: Long): List<Pair<Subscription, Long>> {
        purge(now)
        return subs.values.filter { it.service == service && it.ready }.map { s ->
            val seq = s.nextSeq
            // SEQ wraps to 1, never back to 0, which marks the first event
            s.nextSeq = if (seq >= 0xFFFFFFFFL) 1 else seq + 1
            s to seq
        }
    }

    @Synchronized
    fun drop(sid: String) {
        subs.remove(sid)
    }

    @Synchronized
    fun clear() = subs.clear()

    @Synchronized
    fun count(now: Long): Int {
        purge(now)
        return subs.size
    }

    private fun purge(now: Long) {
        subs.values.removeAll { it.expiresAt <= now }
    }
}
