package com.limelight.local.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandlerTest {
    private val host = object : RendererHost {
        override fun open(media: CastMedia) {}
        override fun play(castId: Long) {}
        override fun pause(castId: Long) {}
        override fun seek(castId: Long, ms: Long) {}
        override fun close(castId: Long) {}
        override fun volume() = 50
        override fun setVolume(volume: Int) {}
        override fun muted() = false
        override fun setMuted(muted: Boolean) {}
    }
    private val renderer = MediaRenderer(host)
    private var n = 0
    private val subs = Subscriptions { "sub${++n}" }
    private val handler = DlnaHandler(renderer, subs) { Descriptions.device("depth3d", "udn-1", "1.0") }
    private val avt = "urn:schemas-upnp-org:service:AVTransport:1"

    private fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String = "") =
        HttpRequest(method, path, headers.mapKeys { it.key.lowercase() }, body.toByteArray(Charsets.UTF_8))

    private fun text(r: HttpResponse) = String(r.body, Charsets.UTF_8)
    private fun text(d: Delivery) = String(d.messages.single().bytes, Charsets.UTF_8)

    @Test
    fun servesDescriptions() {
        val d = handler.handle(request("GET", "/dlna/description.xml"), 0).response
        assertEquals(200, d.status)
        assertTrue(text(d).contains("<friendlyName>depth3d</friendlyName>"))
        assertEquals("text/xml; charset=\"utf-8\"", d.headers.toMap()["Content-Type"])
        val scpd = handler.handle(request("GET", "http://10.0.0.2:49494/dlna/AVTransport.xml"), 0).response
        assertEquals(200, scpd.status)
        assertTrue(text(scpd).contains("<name>SetAVTransportURI</name>"))
        assertEquals(404, handler.handle(request("GET", "/favicon.ico"), 0).response.status)
        assertEquals(405, handler.handle(request("PUT", "/"), 0).response.status)
    }

    @Test
    fun answersControlCalls() {
        val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:GetTransportInfo xmlns:u=\"$avt\"><InstanceID>0</InstanceID></u:GetTransportInfo></s:Body></s:Envelope>"
        val ok = handler.handle(request("POST", "/dlna/AVTransport/control",
            mapOf("SOAPACTION" to "\"$avt#GetTransportInfo\""), body), 0).response
        assertEquals(200, ok.status)
        assertTrue(text(ok).contains("<CurrentTransportState>NO_MEDIA_PRESENT</CurrentTransportState>"))
        assertTrue(ok.headers.any { it.first == "EXT" })
        val bad = handler.handle(request("POST", "/dlna/AVTransport/control",
            mapOf("SOAPACTION" to "\"$avt#Record\""), body.replace("GetTransportInfo", "Record")), 0).response
        assertEquals(500, bad.status)
        assertTrue(text(bad).contains("<errorCode>401</errorCode>"))
        // M-POST, with the header under its namespace number
        val m = handler.handle(request("M-POST", "/dlna/AVTransport/control",
            mapOf("MAN" to "\"http://schemas.xmlsoap.org/soap/envelope/\"; ns=01",
                "01-SOAPACTION" to "\"$avt#GetTransportInfo\""), body), 0).response
        assertEquals(200, m.status)
    }

    @Test
    fun subscribeRenewUnsubscribe() {
        val h = handler.handle(request("SUBSCRIBE", "/dlna/AVTransport/event",
            mapOf("CALLBACK" to "<http://10.0.0.9:7000/cb>", "NT" to "upnp:event", "TIMEOUT" to "Second-300")), 0)
        assertEquals(200, h.response.status)
        val headers = h.response.headers.toMap()
        assertEquals("uuid:sub1", headers["SID"])
        assertEquals("Second-300", headers["TIMEOUT"])
        val first = text(handler.initial(h.subscribed!!)!!)
        assertTrue(first.contains("SEQ: 0\r\n"))
        assertTrue(first.contains("&lt;TransportState val=\"NO_MEDIA_PRESENT\"/&gt;"))
        val change = listOf(EventVar("TransportState", "PLAYING"))
        assertTrue(text(handler.deliveries(UpnpService.AVTransport, change, 1).single()).contains("SEQ: 1\r\n"))

        val renew = handler.handle(request("SUBSCRIBE", "/dlna/AVTransport/event",
            mapOf("SID" to "uuid:sub1", "TIMEOUT" to "Second-600")), 1000)
        assertEquals(200, renew.response.status)
        assertNull(renew.subscribed)
        assertEquals("Second-600", renew.response.headers.toMap()["TIMEOUT"])
        assertEquals(412, handler.handle(request("SUBSCRIBE", "/dlna/AVTransport/event",
            mapOf("SID" to "uuid:nope")), 0).response.status)
        assertEquals(412, handler.handle(request("SUBSCRIBE", "/dlna/AVTransport/event",
            mapOf("NT" to "upnp:event")), 0).response.status)
        assertEquals(200, handler.handle(request("UNSUBSCRIBE", "/dlna/AVTransport/event",
            mapOf("SID" to "uuid:sub1")), 0).response.status)
        assertEquals(412, handler.handle(request("UNSUBSCRIBE", "/dlna/AVTransport/event",
            mapOf("SID" to "uuid:sub1")), 0).response.status)
        assertTrue(handler.deliveries(UpnpService.AVTransport, change, 1).isEmpty())
    }

    @Test
    fun connectionManagerEventsItsProtocols() {
        val h = handler.handle(request("SUBSCRIBE", "/dlna/ConnectionManager/event",
            mapOf("CALLBACK" to "<http://10.0.0.9:7000/cb>", "NT" to "upnp:event")), 0)
        val first = text(handler.initial(h.subscribed!!)!!)
        assertTrue(first.contains("<SinkProtocolInfo>http-get:*:video/mp4:*"))
        assertTrue(first.contains("<CurrentConnectionIDs>0</CurrentConnectionIDs>"))
    }
}
