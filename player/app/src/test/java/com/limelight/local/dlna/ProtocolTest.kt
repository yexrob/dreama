package com.limelight.local.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException

class ProtocolTest {
    private val avt = "urn:schemas-upnp-org:service:AVTransport:1"

    private fun envelope(inner: String, prefixDecl: Boolean = true): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            inner.replace("XMLNS", if (prefixDecl) " xmlns:u=\"$avt\"" else "") +
            "</s:Body></s:Envelope>"

    private val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item id=\"123\" parentID=\"-1\" restricted=\"1\"><dc:title>第 3 集 · 测试 &amp; 标题</dc:title>" +
        "<upnp:class>object.item.videoItem</upnp:class>" +
        "<res protocolInfo=\"http-get:*:video/mp4:*\" duration=\"00:24:01.000\">http://cdn.example/v.mp4?a=1&amp;b=2</res>" +
        "</item></DIDL-Lite>"

    // ---- time

    @Test
    fun formatsTwoDigitHours() {
        assertEquals("00:00:00", UpnpTime.format(0))
        assertEquals("00:00:00", UpnpTime.format(-5000))
        assertEquals("01:02:03", UpnpTime.format(3_723_999))
        assertEquals("120:00:00", UpnpTime.format(120L * 3600 * 1000))
    }

    @Test
    fun parsesTimesLeniently() {
        assertEquals(3_723_000L, UpnpTime.parse("01:02:03"))
        assertEquals(3_723_000L, UpnpTime.parse("1:02:03"))
        assertEquals(3_723_500L, UpnpTime.parse("01:02:03.500"))
        assertEquals(3_723_000L, UpnpTime.parse("62:03"))
        assertEquals(3_723_000L, UpnpTime.parse("3723"))
        assertEquals(5_500L, UpnpTime.parse("0:00:05.1/2"))
        assertEquals(10_000L, UpnpTime.parse(" +00:00:10 "))
        assertEquals(1500L, UpnpTime.parse("1.5"))
    }

    @Test
    fun rejectsWhatIsNotATime() {
        assertNull(UpnpTime.parse(null))
        assertNull(UpnpTime.parse(""))
        assertNull(UpnpTime.parse("NOT_IMPLEMENTED"))
        assertNull(UpnpTime.parse("00:60:00"))
        assertNull(UpnpTime.parse("1:2:3:4"))
        assertNull(UpnpTime.parse("00:00:05.3/2"))
        assertNull(UpnpTime.parse("-00:00:05"))
    }

    // ---- SOAP

    @Test
    fun parsesAStandardCall() {
        val body = envelope("<u:PlayXMLNS><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>")
        val r = Soap.parse(body, "\"$avt#Play\"")!!
        assertEquals("Play", r.action)
        assertEquals(avt, r.serviceType)
        assertEquals("0", r.arg("InstanceID"))
        assertEquals("1", r.arg("speed"))
    }

    @Test
    fun takesUndeclaredPrefixesAndABom() {
        val body = "﻿\n  " + envelope("<u:PauseXMLNS><InstanceID>0</InstanceID></u:Pause>", prefixDecl = false)
        val r = Soap.parse(body, null)!!
        assertEquals("Pause", r.action)
        assertNull(r.serviceType)
    }

    @Test
    fun unescapesMetadataOnce() {
        val body = envelope("<u:SetAVTransportURIXMLNS><InstanceID>0</InstanceID>" +
            "<CurrentURI>http://cdn.example/v.mp4?a=1&amp;b=2</CurrentURI>" +
            "<CurrentURIMetaData>${Xml.escape(didl)}</CurrentURIMetaData></u:SetAVTransportURI>")
        val r = Soap.parse(body, "$avt#SetAVTransportURI")!!
        assertEquals("http://cdn.example/v.mp4?a=1&b=2", r.arg("CurrentURI"))
        assertEquals(didl, r.arg("CurrentURIMetaData"))
        assertEquals("第 3 集 · 测试 & 标题", Didl.parse(r.arg("CurrentURIMetaData"))!!.title)
    }

    @Test
    fun keepsRawDidlInsideAnArgument() {
        val body = envelope("<u:SetAVTransportURIXMLNS><InstanceID>0</InstanceID>" +
            "<CurrentURI>http://cdn.example/v.mp4</CurrentURI><CurrentURIMetaData>$didl</CurrentURIMetaData>" +
            "</u:SetAVTransportURI>")
        val r = Soap.parse(body, null)!!
        val item = Didl.parse(r.arg("CurrentURIMetaData"))!!
        assertEquals("第 3 集 · 测试 & 标题", item.title)
        assertEquals("http://cdn.example/v.mp4?a=1&b=2", item.resUrl)
        assertEquals("http://cdn.example/v.mp4", r.arg("CurrentURI"))
    }

    @Test
    fun survivesABareAmpersandInTheUrl() {
        val body = envelope("<u:SetAVTransportURIXMLNS><InstanceID>0</InstanceID>" +
            "<CurrentURI>http://cdn.example/live.m3u8?token=x&expires=9</CurrentURI>" +
            "<CurrentURIMetaData></CurrentURIMetaData></u:SetAVTransportURI>")
        val r = Soap.parse(body, null)!!
        assertEquals("SetAVTransportURI", r.action)
        assertEquals("http://cdn.example/live.m3u8?token=x&expires=9", r.arg("CurrentURI"))
        assertEquals("", r.arg("CurrentURIMetaData"))
    }

    @Test
    fun fallsBackToTheHeader() {
        val r = Soap.parse("not xml at all", "urn:schemas-upnp-org:service:RenderingControl:1#GetVolume")!!
        assertEquals("GetVolume", r.action)
        assertEquals("urn:schemas-upnp-org:service:RenderingControl:1", r.serviceType)
        assertTrue(r.args.isEmpty())
        assertNull(Soap.parse("", null))
    }

    @Test
    fun splitsSoapAction() {
        assertEquals(avt to "Seek", Soap.splitSoapAction("\"$avt#Seek\""))
        assertEquals(avt to "Seek", Soap.splitSoapAction("  $avt#Seek "))
        assertEquals(null to "Seek", Soap.splitSoapAction("Seek"))
        assertEquals(null to null, Soap.splitSoapAction(null))
    }

    @Test
    fun answersParseBack() {
        val xml = Soap.response(avt, "GetPositionInfo", listOf("Track" to "1", "TrackURI" to "http://a/b?c=1&d=2",
            "TrackMetaData" to didl))
        assertTrue(xml.contains("<u:GetPositionInfoResponse xmlns:u=\"$avt\">"))
        val back = Soap.parse(xml, null)!!
        assertEquals("GetPositionInfoResponse", back.action)
        assertEquals("http://a/b?c=1&d=2", back.arg("TrackURI"))
        assertEquals(didl, back.arg("TrackMetaData"))
    }

    @Test
    fun faultCarriesTheUpnpError() {
        val xml = Soap.fault(718, "Invalid InstanceID")
        assertTrue(xml.contains("<faultstring>UPnPError</faultstring>"))
        assertTrue(xml.contains("<errorCode>718</errorCode>"))
        val p = Xml.parser(xml)
        var depthOk = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "errorDescription") {
                assertEquals("Invalid InstanceID", p.nextText())
                depthOk = true
            }
        }
        assertTrue(depthOk)
    }

    // ---- DIDL

    @Test
    fun readsDidl() {
        val item = Didl.parse(didl)!!
        assertEquals("第 3 集 · 测试 & 标题", item.title)
        assertEquals("object.item.videoItem", item.upnpClass)
        assertEquals("http-get:*:video/mp4:*", item.protocolInfo)
        assertEquals("video/mp4", item.mimeType)
        assertEquals(1_441_000L, item.durationMs)
        assertFalse(item.isImage)
    }

    @Test
    fun readsDoublyEscapedDidl() {
        assertEquals("第 3 集 · 测试 & 标题", Didl.parse(Xml.escape(didl))!!.title)
    }

    @Test
    fun ignoresMissingMetadata() {
        assertNull(Didl.parse(null))
        assertNull(Didl.parse(""))
        assertNull(Didl.parse("NOT_IMPLEMENTED"))
        assertNull(Didl.parse("<DIDL-Lite></DIDL-Lite>"))
    }

    @Test
    fun readsBrokenDidlByPattern() {
        val broken = "<DIDL-Lite><item><dc:title>Broken &amp; ok</dc:title><upnp:class>object.item.videoItem" +
            "</upnp:class><res protocolInfo=\"http-get:*:video/x-flv:*\">http://a/b.flv?x=1&y=2</res></item>"
        val item = Didl.parse(broken)!!
        assertEquals("Broken & ok", item.title)
        assertEquals("http://a/b.flv?x=1&y=2", item.resUrl)
        assertEquals("video/x-flv", item.mimeType)
    }

    @Test
    fun knowsAnImage() {
        val img = "<DIDL-Lite><item><dc:title>p</dc:title><upnp:class>object.item.imageItem.photo</upnp:class>" +
            "</item></DIDL-Lite>"
        assertTrue(Didl.parse(img)!!.isImage)
    }

    @Test
    fun hintsTheStreamType() {
        assertEquals(MediaHint.HLS, MediaHint.guess("http://a/b/index.m3u8?t=1", null))
        assertEquals(MediaHint.HLS, MediaHint.guess("http://a/play?id=1", "application/vnd.apple.mpegurl"))
        assertEquals(MediaHint.HLS, MediaHint.guess("http://a/proxy?u=http%3A%2F%2Fx%2Fy.m3u8", null))
        assertEquals(MediaHint.DASH, MediaHint.guess("http://a/manifest.mpd", "video/mp4"))
        assertEquals(MediaHint.DASH, MediaHint.guess("http://a/x", "application/dash+xml"))
        assertNull(MediaHint.guess("http://a/v.mp4", "video/mp4"))
        assertNull(MediaHint.guess("http://a/v.flv", null))
    }

    // ---- SSDP

    private val udn = "3f8b2a10-1111-4222-8333-444455556666"
    private val ssdp = Ssdp(udn, "Android/14 UPnP/1.0 depth3d/1.0")

    @Test
    fun readsASearch() {
        val m = Ssdp.parse("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
            "MX: 9\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")!!
        assertTrue(m.isSearch)
        assertEquals(5, m.maxWaitSeconds)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", m.header("st"))
        val notify = Ssdp.parse("NOTIFY * HTTP/1.1\nNT: upnp:rootdevice\nNTS: ssdp:alive\n\n")!!
        assertFalse(notify.isSearch)
        assertNull(Ssdp.parse(""))
    }

    @Test
    fun answersTheRightTargets() {
        assertEquals(6, ssdp.matches("ssdp:all").size)
        assertEquals(listOf("upnp:rootdevice" to "uuid:$udn::upnp:rootdevice"), ssdp.matches("upnp:rootdevice"))
        assertEquals(listOf("uuid:$udn" to "uuid:$udn"), ssdp.matches("uuid:$udn"))
        val mr = "urn:schemas-upnp-org:device:MediaRenderer:1"
        assertEquals(listOf(mr to "uuid:$udn::$mr"), ssdp.matches(mr))
        val mr2 = "urn:schemas-upnp-org:device:MediaRenderer:2"
        assertEquals(listOf(mr2 to "uuid:$udn::$mr2"), ssdp.matches(mr2))
        assertEquals(1, ssdp.matches("urn:schemas-upnp-org:service:AVTransport:1").size)
        assertTrue(ssdp.matches("urn:schemas-upnp-org:device:MediaServer:1").isEmpty())
        assertTrue(ssdp.matches("uuid:someone-else").isEmpty())
        assertTrue(ssdp.matches(null).isEmpty())
    }

    @Test
    fun buildsTheMessages() {
        val loc = "http://192.168.1.20:49494/dlna/description.xml"
        val r = ssdp.searchResponse("upnp:rootdevice", "uuid:$udn::upnp:rootdevice", loc, 0)
        assertTrue(r.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(r.endsWith("\r\n\r\n"))
        val parsed = Ssdp.parse(r)!!
        assertEquals(loc, parsed.header("LOCATION"))
        assertEquals("", parsed.header("EXT"))
        assertEquals("max-age=1800", parsed.header("CACHE-CONTROL"))
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", parsed.header("DATE"))
        val alive = Ssdp.parse(ssdp.alive("upnp:rootdevice", "uuid:$udn::upnp:rootdevice", loc))!!
        assertEquals("NOTIFY * HTTP/1.1", alive.startLine)
        assertEquals("ssdp:alive", alive.header("NTS"))
        assertEquals("239.255.255.250:1900", alive.header("HOST"))
        val bye = Ssdp.parse(ssdp.byebye("upnp:rootdevice", "uuid:$udn::upnp:rootdevice"))!!
        assertEquals("ssdp:byebye", bye.header("NTS"))
        assertNull(bye.header("LOCATION"))
    }

    // ---- descriptions

    private fun texts(xml: String, tag: String): List<String> {
        val p = Xml.parser(xml)
        val out = ArrayList<String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && Xml.localName(p.name) == tag) {
                out += p.nextText()
            }
        }
        return out
    }

    @Test
    fun deviceDescriptionIsWellFormed() {
        val xml = Descriptions.device("客厅的 depth3d & <test>", udn, "1.0")
        assertEquals(listOf("客厅的 depth3d & <test>"), texts(xml, "friendlyName"))
        assertEquals(listOf(Descriptions.DEVICE_TYPE), texts(xml, "deviceType"))
        assertEquals(listOf("uuid:$udn"), texts(xml, "UDN"))
        assertEquals(listOf("DMR-1.50"), texts(xml, "X_DLNADOC"))
        assertEquals(UpnpService.entries.map { it.type }, texts(xml, "serviceType"))
        assertEquals(UpnpService.entries.map { it.controlPath }, texts(xml, "controlURL"))
        assertTrue(texts(xml, "SCPDURL").all { it.startsWith("/") })
    }

    @Test
    fun everyArgumentNamesADeclaredVariable() {
        for (service in UpnpService.entries) {
            val xml = Descriptions.scpdXml(service)
            val related = texts(xml, "relatedStateVariable")
            val declared = Descriptions.scpd(service).vars.map { it.name }.toSet()
            assertEquals(declared.size, Regex("<stateVariable ").findAll(xml).count())
            assertTrue(related.isNotEmpty())
            for (v in related) {
                assertTrue("$service: $v", declared.contains(v))
            }
            val scpd = Descriptions.scpd(service)
            assertEquals(scpd.actions.size, scpd.actions.map { it.name }.toSet().size)
            assertEquals(scpd.vars.size, scpd.vars.map { it.name }.toSet().size)
        }
    }

    @Test
    fun avTransportCarriesWhatPhonesCall() {
        val names = Descriptions.AVT.actions.map { it.name }
        for (a in listOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetPositionInfo",
                "GetTransportInfo", "GetMediaInfo")) {
            assertTrue(a, names.contains(a))
        }
        assertTrue(Descriptions.SINK_PROTOCOL_INFO.contains("http-get:*:video/x-flv:*"))
        assertTrue(Descriptions.SINK_PROTOCOL_INFO.endsWith("http-get:*:*:*"))
        assertEquals(UpnpService.AVTransport, UpnpService.forPath("/dlna/AVTransport/control"))
        assertEquals(UpnpService.RenderingControl, UpnpService.forPath("/dlna/RenderingControl/event"))
        assertNull(UpnpService.forPath("/favicon.ico"))
    }

    // ---- HTTP

    private fun req(text: String) = Http.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    @Test
    fun readsARequest() {
        val body = "<x>中</x>"
        val n = body.toByteArray(Charsets.UTF_8).size
        val r = req("POST /dlna/AVTransport/control HTTP/1.1\r\nHOST: a\r\nSOAPACTION: \"x#Play\"\r\n" +
            "Content-Length: $n\r\n\r\n$body")!!
        assertEquals("POST", r.method)
        assertEquals("/dlna/AVTransport/control", r.path)
        assertEquals("\"x#Play\"", r.header("SoapAction"))
        assertEquals(body, r.bodyText)
    }

    @Test
    fun readsBareLineFeedsAndChunks() {
        val r = req("SUBSCRIBE /dlna/AVTransport/event HTTP/1.1\nCALLBACK: <http://a/>\nNT: upnp:event\n\n")!!
        assertEquals("SUBSCRIBE", r.method)
        assertEquals("<http://a/>", r.header("callback"))
        assertEquals(0, r.body.size)
        val c = req("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n3;x=y\r\nefg\r\n0\r\n\r\n")!!
        assertEquals("abcdefg", c.bodyText)
        assertNull(req(""))
    }

    @Test
    fun refusesAnOversizedBody() {
        try {
            req("POST / HTTP/1.1\r\nContent-Length: ${Http.MAX_BODY + 1}\r\n\r\n")
            fail("too large")
        } catch (_: IOException) {
        }
    }

    @Test
    fun findsThePath() {
        assertEquals("/dlna/description.xml", Http.pathOf("http://192.168.1.2:49494/dlna/description.xml?x=1"))
        assertEquals("/", Http.pathOf("http://host"))
        assertEquals("/a", Http.pathOf("/a#b"))
    }

    @Test
    fun writesAResponse() {
        val bytes = Http.encode(HttpResponse.xml("<a/>"), "srv", "date")
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 4\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.endsWith("\r\n\r\n<a/>"))
    }

    // ---- GENA

    @Test
    fun readsSubscribeHeaders() {
        assertEquals(listOf("http://192.168.1.5:5000/notify", "http://192.168.1.5:5001/"),
            Gena.parseCallbacks("<http://192.168.1.5:5000/notify><http://192.168.1.5:5001/>"))
        assertEquals(listOf("http://a/b"), Gena.parseCallbacks("http://a/b"))
        assertTrue(Gena.parseCallbacks("<ftp://a/b>").isEmpty())
        assertTrue(Gena.parseCallbacks(null).isEmpty())
        assertEquals(300, Gena.parseTimeout("Second-300"))
        assertEquals(7200, Gena.parseTimeout("Second-infinite"))
        assertEquals(60, Gena.parseTimeout("Second-10"))
        assertEquals(1800, Gena.parseTimeout(null))
        assertEquals(1800, Gena.parseTimeout("whenever"))
    }

    @Test
    fun lastChangeRoundTrips() {
        val vars = listOf(EventVar("TransportState", "PLAYING"), EventVar("AVTransportURIMetaData", didl),
            EventVar("Volume", "30", "Master"))
        val body = Gena.propertySet(listOf("LastChange" to Gena.lastChange(Gena.AVT_NS, vars)))
        val lastChange = texts(body, "LastChange").single()
        val p = Xml.parser(lastChange)
        val seen = HashMap<String, String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.getAttributeValue(null, "val") != null) {
                seen[p.name] = p.getAttributeValue(null, "val")
                if (p.name == "Volume") {
                    assertEquals("Master", p.getAttributeValue(null, "channel"))
                }
            }
        }
        assertEquals("PLAYING", seen["TransportState"])
        assertEquals(didl, seen["AVTransportURIMetaData"])
        assertEquals("30", seen["Volume"])
    }

    @Test
    fun buildsTheNotify() {
        val m = Gena.notify("http://192.168.1.5:5000/cb?x=1", "uuid:s1", 3, "<b>中</b>")!!
        assertEquals("192.168.1.5", m.host)
        assertEquals(5000, m.port)
        val text = String(m.bytes, Charsets.UTF_8)
        assertTrue(text.startsWith("NOTIFY /cb?x=1 HTTP/1.1\r\nHOST: 192.168.1.5:5000\r\n"))
        assertTrue(text.contains("SID: uuid:s1\r\nSEQ: 3\r\n"))
        assertTrue(text.contains("NTS: upnp:propchange\r\n"))
        assertTrue(text.contains("CONTENT-LENGTH: ${"<b>中</b>".toByteArray(Charsets.UTF_8).size}\r\n"))
        assertEquals(80, Gena.notify("http://h/", "s", 0, "")!!.port)
        assertNull(Gena.notify("https://h/", "s", 0, ""))
        assertNull(Gena.notify("not a url", "s", 0, ""))
    }

    @Test
    fun subscriptionsExpireRenewAndCount() {
        var n = 0
        val subs = Subscriptions { "sid${++n}" }
        val s = subs.subscribe(UpnpService.AVTransport, listOf("http://a/"), 300, 0)
        assertEquals("uuid:sid1", s.sid)
        // Not ready until its first NOTIFY is claimed
        assertTrue(subs.forEvent(UpnpService.AVTransport, 1).isEmpty())
        assertEquals(0L, subs.claimInitial(s.sid))
        assertEquals(1L, subs.forEvent(UpnpService.AVTransport, 1).single().second)
        assertEquals(2L, subs.forEvent(UpnpService.AVTransport, 1).single().second)
        assertTrue(subs.forEvent(UpnpService.RenderingControl, 1).isEmpty())
        assertNotNull(subs.renew(" uuid:sid1 ", 600, 299_000))
        assertEquals(1, subs.count(800_000))
        assertEquals(0, subs.count(899_001))
        assertNull(subs.renew("uuid:sid1", 600, 900_000))
        val t = subs.subscribe(UpnpService.AVTransport, listOf("http://a/"), 300, 0)
        assertTrue(subs.unsubscribe(t.sid))
        assertFalse(subs.unsubscribe(t.sid))
        assertNull(subs.claimInitial(t.sid))
    }

    @Test
    fun subscriptionsAreCappedPerService() {
        var n = 0
        val subs = Subscriptions { "s${++n}" }
        repeat(20) { subs.subscribe(UpnpService.AVTransport, listOf("http://a/"), 300, it.toLong()) }
        assertEquals(16, subs.count(100))
    }

    @Test
    fun seqWrapsToOne() {
        val subs = Subscriptions { "x" }
        val s = subs.subscribe(UpnpService.AVTransport, listOf("http://a/"), 300, 0)
        subs.claimInitial(s.sid)
        s.nextSeq = 0xFFFFFFFFL
        assertEquals(0xFFFFFFFFL, subs.forEvent(UpnpService.AVTransport, 0).single().second)
        assertEquals(1L, subs.forEvent(UpnpService.AVTransport, 0).single().second)
    }
}
