package com.limelight.local.dlna

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A datagram on 239.255.255.250:1900, its start line and headers (names upper-cased). */
data class SsdpMessage(val startLine: String, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.uppercase(Locale.US)]

    val isSearch: Boolean get() = startLine.startsWith("M-SEARCH", ignoreCase = true) &&
        header("MAN")?.contains("ssdp:discover", ignoreCase = true) == true

    /** MX, the most seconds the searcher waits for an answer; senders write 1..5, clamp the rest. */
    val maxWaitSeconds: Int get() = (header("MX")?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 5)
}

/**
 * SSDP for one root device with the three services. Answers carry what
 * every control point checks (ST, USN, LOCATION, EXT, CACHE-CONTROL) and a
 * Content-Length, which some Android senders wait for before reading on.
 */
class Ssdp(private val udn: String, private val server: String) {
    companion object {
        const val ADDRESS = "239.255.255.250"
        const val PORT = 1900
        const val MAX_AGE = 1800

        fun parse(datagram: String): SsdpMessage? {
            val lines = datagram.split("\r\n", "\n")
            val start = lines.firstOrNull()?.trim()
            if (start.isNullOrEmpty()) {
                return null
            }
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) {
                    continue
                }
                headers[line.substring(0, colon).trim().uppercase(Locale.US)] = line.substring(colon + 1).trim()
            }
            return SsdpMessage(start, headers)
        }

        private fun httpDate(now: Long): String {
            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("GMT")
            return f.format(Date(now))
        }
    }

    private val uuid = "uuid:$udn"

    /** Every (NT, USN) the device announces: root, the device itself, its type, and each service. */
    fun targets(): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        list += "upnp:rootdevice" to "$uuid::upnp:rootdevice"
        list += uuid to uuid
        list += Descriptions.DEVICE_TYPE to "$uuid::${Descriptions.DEVICE_TYPE}"
        for (s in UpnpService.entries) {
            list += s.type to "$uuid::${s.type}"
        }
        return list
    }

    /**
     * The (ST, USN) pairs a search for [st] gets answered with. A search for a
     * later version of a type we carry is answered with the version asked
     * for, as the spec has a newer device do; some senders only look for :2.
     */
    fun matches(st: String?): List<Pair<String, String>> {
        val t = st?.trim() ?: return emptyList()
        if (t.equals("ssdp:all", ignoreCase = true)) {
            return targets()
        }
        if (t.equals(uuid, ignoreCase = true)) {
            return listOf(uuid to uuid)
        }
        if (t.equals("upnp:rootdevice", ignoreCase = true)) {
            return listOf(t to "$uuid::upnp:rootdevice")
        }
        val types = listOf(Descriptions.DEVICE_TYPE) + UpnpService.entries.map { it.type }
        for (type in types) {
            val base = type.substringBeforeLast(':')
            if (t.startsWith("$base:", ignoreCase = true) && t.substringAfterLast(':').toIntOrNull()?.let { it >= 1 } == true) {
                return listOf(t to "$uuid::$t")
            }
        }
        return emptyList()
    }

    fun searchResponse(st: String, usn: String, location: String, now: Long): String =
        "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=$MAX_AGE\r\n" +
            "DATE: ${httpDate(now)}\r\n" +
            "EXT:\r\n" +
            "LOCATION: $location\r\n" +
            "SERVER: $server\r\n" +
            "ST: $st\r\n" +
            "USN: $usn\r\n" +
            "BOOTID.UPNP.ORG: 1\r\n" +
            "CONFIGID.UPNP.ORG: 1\r\n" +
            "Content-Length: 0\r\n\r\n"

    fun alive(nt: String, usn: String, location: String): String =
        "NOTIFY * HTTP/1.1\r\n" +
            "HOST: $ADDRESS:$PORT\r\n" +
            "CACHE-CONTROL: max-age=$MAX_AGE\r\n" +
            "LOCATION: $location\r\n" +
            "NT: $nt\r\n" +
            "NTS: ssdp:alive\r\n" +
            "SERVER: $server\r\n" +
            "USN: $usn\r\n" +
            "BOOTID.UPNP.ORG: 1\r\n" +
            "CONFIGID.UPNP.ORG: 1\r\n" +
            "Content-Length: 0\r\n\r\n"

    fun byebye(nt: String, usn: String): String =
        "NOTIFY * HTTP/1.1\r\n" +
            "HOST: $ADDRESS:$PORT\r\n" +
            "NT: $nt\r\n" +
            "NTS: ssdp:byebye\r\n" +
            "USN: $usn\r\n" +
            "BOOTID.UPNP.ORG: 1\r\n" +
            "CONFIGID.UPNP.ORG: 1\r\n" +
            "Content-Length: 0\r\n\r\n"
}
