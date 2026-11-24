package com.limelight.local.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererTest {
    private class FakeHost : RendererHost {
        val calls = ArrayList<String>()
        val opened = ArrayList<CastMedia>()
        var vol = 40
        var mute = false

        override fun open(media: CastMedia) {
            opened += media
            calls += "open ${media.castId} ${media.startMs}"
        }
        override fun play(castId: Long) { calls += "play $castId" }
        override fun pause(castId: Long) { calls += "pause $castId" }
        override fun seek(castId: Long, ms: Long) { calls += "seek $castId $ms" }
        override fun close(castId: Long) { calls += "close $castId" }
        override fun volume() = vol
        override fun setVolume(volume: Int) { vol = volume }
        override fun muted() = mute
        override fun setMuted(muted: Boolean) { mute = muted }
    }

    private val host = FakeHost()
    private val r = MediaRenderer(host)
    private val events = ArrayList<Pair<UpnpService, List<EventVar>>>()

    init {
        r.events = { s, v -> events += s to v }
    }

    private val avt = UpnpService.AVTransport
    private val rcs = UpnpService.RenderingControl
    private val cms = UpnpService.ConnectionManager

    private val didl = "<DIDL-Lite><item><dc:title>电影</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
        "<res protocolInfo=\"http-get:*:application/vnd.apple.mpegurl:*\" duration=\"0:10:00\">x</res></item></DIDL-Lite>"

    private fun call(service: UpnpService, action: String, now: Long = 0, vararg args: Pair<String, String>) =
        r.control(service, SoapRequest(action, service.type, linkedMapOf("InstanceID" to "0", *args)), now)

    private fun out(result: SoapResult): Map<String, String> {
        assertTrue("$result", result is SoapResult.Ok)
        return (result as SoapResult.Ok).out.toMap()
    }

    private fun code(result: SoapResult) = (result as SoapResult.Fault).code

    private fun state(now: Long = 0) = out(call(avt, "GetTransportInfo", now))["CurrentTransportState"]

    private fun cast(uri: String = "http://cdn/a.m3u8", meta: String = didl, now: Long = 0) =
        call(avt, "SetAVTransportURI", now, "CurrentURI" to uri, "CurrentURIMetaData" to meta)

    @Test
    fun startsEmpty() {
        assertEquals(MediaRenderer.NO_MEDIA, state())
        val pos = out(call(avt, "GetPositionInfo"))
        assertEquals("0", pos["Track"])
        assertEquals("00:00:00", pos["RelTime"])
        assertEquals(701, code(call(avt, "Play")))
        assertEquals(701, code(call(avt, "Pause")))
        out(call(avt, "Stop"))
    }

    @Test
    fun setUriOpensAndAutoPlays() {
        out(cast())
        val m = host.opened.single()
        assertEquals(1L, m.castId)
        assertEquals("电影", m.title)
        assertEquals(MediaHint.HLS, m.mimeType)
        assertEquals(0L, m.startMs)
        assertEquals(MediaRenderer.TRANSITIONING, state())
        val media = out(call(avt, "GetMediaInfo"))
        assertEquals("00:10:00", media["MediaDuration"])
        assertEquals("http://cdn/a.m3u8", media["CurrentURI"])
        assertEquals(didl, media["CurrentURIMetaData"])
        assertEquals("NETWORK", media["PlayMedium"])
        // Play right after, as most apps send it: the open already plays
        out(call(avt, "Play"))
        assertEquals(listOf("open 1 0"), host.calls)
        // The same URI again while it plays opens nothing new
        out(cast())
        assertEquals(1, host.opened.size)
    }

    @Test
    fun refusesWhatItCannotPlay() {
        assertEquals(402, code(cast(uri = " ")))
        assertEquals(716, code(cast(uri = "file:///sdcard/a.mp4")))
        val photo = "<DIDL-Lite><item><dc:title>p</dc:title><upnp:class>object.item.imageItem.photo</upnp:class>" +
            "</item></DIDL-Lite>"
        assertEquals(714, code(cast(uri = "http://a/p.jpg", meta = photo)))
        assertEquals(718, code(r.control(avt, SoapRequest("Play", null, mapOf("InstanceID" to "3")), 0)))
        assertEquals(401, code(call(avt, "Record")))
        assertTrue(host.opened.isEmpty())
    }

    @Test
    fun commandsBeforeThePlayerWaitForIt() {
        cast()
        out(call(avt, "Seek", 0, "Unit" to "REL_TIME", "Target" to "00:01:00"))
        out(call(avt, "Pause"))
        assertEquals(listOf("open 1 0"), host.calls)
        assertEquals("00:01:00", out(call(avt, "GetPositionInfo"))["RelTime"])
        r.onPlayer(1, PlayerState.LOADING, 0, 0, 100)
        assertEquals(listOf("open 1 0", "seek 1 60000", "pause 1"), host.calls)
        assertEquals(MediaRenderer.TRANSITIONING, state())
        r.onPlayer(1, PlayerState.PAUSED, 60_000, 600_000, 2000)
        assertEquals(MediaRenderer.PAUSED, state())
    }

    @Test
    fun reportsPositionAndDuration() {
        cast()
        r.onPlayer(1, PlayerState.PLAYING, 83_400, 5_400_000, 100)
        assertEquals(MediaRenderer.PLAYING, state())
        val pos = out(call(avt, "GetPositionInfo"))
        assertEquals("1", pos["Track"])
        assertEquals("00:01:23", pos["RelTime"])
        assertEquals("00:01:23", pos["AbsTime"])
        assertEquals("01:30:00", pos["TrackDuration"])
        assertEquals("http://cdn/a.m3u8", pos["TrackURI"])
        assertEquals("2147483647", pos["RelCount"])
        // A stale player (an earlier cast) does not move anything
        r.onPlayer(7, PlayerState.PAUSED, 1, 1, 200)
        assertEquals(MediaRenderer.PLAYING, state())
    }

    @Test
    fun pauseAndPlayHoldAgainstStaleReports() {
        cast()
        r.onPlayer(1, PlayerState.PLAYING, 1000, 600_000, 0)
        out(call(avt, "Pause", 1000))
        assertEquals("pause 1", host.calls.last())
        assertEquals(MediaRenderer.PAUSED, state())
        // The player's report from before it took the pause
        r.onPlayer(1, PlayerState.PLAYING, 1200, 600_000, 1100)
        assertEquals(MediaRenderer.PAUSED, state())
        r.onPlayer(1, PlayerState.PAUSED, 1200, 600_000, 1300)
        out(call(avt, "Play", 2000))
        assertEquals("play 1", host.calls.last())
        assertEquals(MediaRenderer.PLAYING, state())
        r.onPlayer(1, PlayerState.PAUSED, 1200, 600_000, 2100)
        assertEquals(MediaRenderer.PLAYING, state())
        // After the hold, the player's word stands
        r.onPlayer(1, PlayerState.PAUSED, 1200, 600_000, 2000 + MediaRenderer.EXPECT_MS)
        assertEquals(MediaRenderer.PAUSED, state())
    }

    @Test
    fun seekRules() {
        cast()
        r.onPlayer(1, PlayerState.PLAYING, 0, 600_000, 0)
        out(call(avt, "Seek", 0, "Unit" to "ABS_TIME", "Target" to "0:05:00.5"))
        assertEquals("seek 1 300500", host.calls.last())
        // Past the end goes to the end
        out(call(avt, "Seek", 0, "Unit" to "REL_TIME", "Target" to "02:00:00"))
        assertEquals("seek 1 600000", host.calls.last())
        out(call(avt, "Seek", 0, "Unit" to "TRACK_NR", "Target" to "1"))
        assertEquals(711, code(call(avt, "Seek", 0, "Unit" to "REL_TIME", "Target" to "NOT_IMPLEMENTED")))
        assertEquals(710, code(call(avt, "Seek", 0, "Unit" to "ABS_COUNT", "Target" to "100")))
        // The position shows the target until the player catches up
        r.onPlayer(1, PlayerState.PLAYING, 1000, 600_000, 500)
        assertEquals("00:10:00", out(call(avt, "GetPositionInfo", 500))["RelTime"])
        r.onPlayer(1, PlayerState.PLAYING, 600_000, 600_000, 1500)
        assertEquals("00:10:00", out(call(avt, "GetPositionInfo", 1500))["RelTime"])
    }

    @Test
    fun stopWaitsBeforeClosing() {
        cast()
        r.onPlayer(1, PlayerState.PLAYING, 5000, 600_000, 0)
        out(call(avt, "Stop", 1000))
        assertEquals("pause 1", host.calls.last())
        assertEquals(MediaRenderer.STOPPED, state())
        r.onPlayer(1, PlayerState.PLAYING, 5000, 600_000, 1100)
        assertEquals(MediaRenderer.STOPPED, state())
        r.tick(1000 + MediaRenderer.STOP_GRACE_MS - 1)
        assertTrue(host.calls.none { it.startsWith("close") })
        r.tick(1000 + MediaRenderer.STOP_GRACE_MS)
        assertEquals("close 1", host.calls.last())
        // The closed player's late reports change nothing
        r.onPlayer(1, PlayerState.PAUSED, 5000, 600_000, 3100)
        assertEquals(MediaRenderer.STOPPED, state())
        // Play opens it again where it stopped
        out(call(avt, "Play", 4000))
        assertEquals("open 2 5000", host.calls.last())
        assertEquals(MediaRenderer.TRANSITIONING, state())
    }

    @Test
    fun nextEpisodeInsideTheGracePlaysInPlace() {
        cast()
        r.onPlayer(1, PlayerState.PLAYING, 5000, 600_000, 0)
        out(call(avt, "Stop", 1000))
        out(cast(uri = "http://cdn/ep2.mp4", meta = "", now = 1200))
        assertEquals("open 2 0", host.calls.last())
        assertNull(host.opened.last().title)
        assertEquals(MediaRenderer.TRANSITIONING, state())
        out(call(avt, "Play", 1300))
        r.tick(10_000)
        assertTrue(host.calls.none { it.startsWith("close") })
    }

    @Test
    fun playInsideTheGraceCarriesOn() {
        cast()
        r.onPlayer(1, PlayerState.PLAYING, 5000, 600_000, 0)
        out(call(avt, "Stop", 1000))
        out(call(avt, "Play", 1500))
        assertEquals("play 1", host.calls.last())
        assertEquals(MediaRenderer.PLAYING, state())
        r.tick(10_000)
        assertTrue(host.calls.none { it.startsWith("close") })
    }

    @Test
    fun stopBeforeThePlayerIsUp() {
        cast()
        out(call(avt, "Stop", 100))
        r.onPlayer(1, PlayerState.LOADING, 0, 0, 500)
        assertEquals("pause 1", host.calls.last())
        assertEquals(MediaRenderer.STOPPED, state())
        r.tick(100 + MediaRenderer.STOP_GRACE_MS)
        assertEquals("close 1", host.calls.last())
    }

    @Test
    fun endThenPlayStartsOver() {
        cast()
        r.onPlayer(1, PlayerState.PLAYING, 599_000, 600_000, 0)
        r.onPlayer(1, PlayerState.ENDED, 600_000, 600_000, 1000)
        assertEquals(MediaRenderer.STOPPED, state())
        r.onPlayer(1, PlayerState.PAUSED, 600_000, 600_000, 1200)
        assertEquals(MediaRenderer.STOPPED, state())
        out(call(avt, "Play", 2000))
        assertEquals(listOf("seek 1 0", "play 1"), host.calls.takeLast(2))
        r.onPlayer(1, PlayerState.ENDED, 600_000, 600_000, 2100)
        assertEquals(MediaRenderer.PLAYING, state())
        r.onPlayer(1, PlayerState.PLAYING, 100, 600_000, 2300)
        assertEquals(MediaRenderer.PLAYING, state())
    }

    @Test
    fun queuedNextUriPlaysAtTheEnd() {
        cast()
        out(call(avt, "SetNextAVTransportURI", 0, "NextURI" to "http://cdn/ep2.mp4", "NextURIMetaData" to ""))
        assertEquals("http://cdn/ep2.mp4", out(call(avt, "GetMediaInfo"))["NextURI"])
        r.onPlayer(1, PlayerState.PLAYING, 0, 600_000, 0)
        r.onPlayer(1, PlayerState.ENDED, 600_000, 600_000, 1000)
        assertEquals("open 2 0", host.calls.last())
        assertEquals("http://cdn/ep2.mp4", out(call(avt, "GetMediaInfo"))["CurrentURI"])
        assertEquals("", out(call(avt, "GetMediaInfo"))["NextURI"])
    }

    @Test
    fun errorThenPlayRetries() {
        cast()
        r.onPlayer(1, PlayerState.ERROR, 0, 0, 1000)
        val info = out(call(avt, "GetTransportInfo"))
        assertEquals(MediaRenderer.STOPPED, info["CurrentTransportState"])
        assertEquals("ERROR_OCCURRED", info["CurrentTransportStatus"])
        out(call(avt, "Play", 2000))
        assertEquals("open 2 0", host.calls.last())
        assertEquals("OK", out(call(avt, "GetTransportInfo"))["CurrentTransportStatus"])
        // An errored cast of the same URI is opened again, not kept
        r.onPlayer(2, PlayerState.ERROR, 0, 0, 3000)
        out(cast(now = 4000))
        assertEquals("open 3 0", host.calls.last())
    }

    @Test
    fun closedInTheHeadset() {
        cast()
        r.onPlayer(1, PlayerState.PLAYING, 42_000, 600_000, 0)
        r.onPlayer(1, PlayerState.CLOSED, 42_000, 600_000, 1000)
        assertEquals(MediaRenderer.STOPPED, state())
        out(call(avt, "Pause", 1100))
        out(call(avt, "Play", 1200))
        assertEquals("open 2 42000", host.calls.last())
    }

    @Test
    fun aPlayerThatNeverComesUpTimesOut() {
        cast(now = 0)
        r.tick(MediaRenderer.OPEN_TIMEOUT_MS - 1)
        assertEquals(MediaRenderer.TRANSITIONING, state())
        r.tick(MediaRenderer.OPEN_TIMEOUT_MS)
        assertEquals(MediaRenderer.STOPPED, state())
        assertEquals("ERROR_OCCURRED", out(call(avt, "GetTransportInfo"))["CurrentTransportStatus"])
    }

    @Test
    fun volumeAndMute() {
        assertEquals("40", out(call(rcs, "GetVolume", 0, "Channel" to "Master"))["CurrentVolume"])
        out(call(rcs, "SetVolume", 0, "Channel" to "Master", "DesiredVolume" to "73"))
        assertEquals(73, host.vol)
        out(call(rcs, "SetVolume", 0, "Channel" to "Master", "DesiredVolume" to "150"))
        assertEquals(100, host.vol)
        assertEquals(402, code(call(rcs, "SetVolume", 0, "DesiredVolume" to "loud")))
        out(call(rcs, "SetMute", 0, "DesiredMute" to "true"))
        assertEquals("1", out(call(rcs, "GetMute"))["CurrentMute"])
        out(call(rcs, "SetMute", 0, "DesiredMute" to "0"))
        assertEquals("0", out(call(rcs, "GetMute"))["CurrentMute"])
        assertEquals(402, code(call(rcs, "SetMute", 0, "DesiredMute" to "maybe")))
        assertEquals("FactoryDefaults", out(call(rcs, "ListPresets"))["CurrentPresetNameList"])
    }

    @Test
    fun connectionManager() {
        val info = out(r.control(cms, SoapRequest("GetProtocolInfo", null, emptyMap()), 0))
        assertEquals("", info["Source"])
        assertTrue(info["Sink"]!!.contains("http-get:*:video/mp4:*"))
        assertEquals("0", out(r.control(cms, SoapRequest("GetCurrentConnectionIDs", null, emptyMap()), 0))["ConnectionIDs"])
        val conn = out(r.control(cms, SoapRequest("GetCurrentConnectionInfo", null, mapOf("ConnectionID" to "0")), 0))
        assertEquals("Input", conn["Direction"])
        assertEquals(706, code(r.control(cms, SoapRequest("GetCurrentConnectionInfo", null,
            mapOf("ConnectionID" to "5")), 0)))
    }

    @Test
    fun everyDescribedActionIsHandled() {
        for (service in UpnpService.entries) {
            for (a in Descriptions.scpd(service).actions) {
                val args = HashMap<String, String>()
                a.ins.forEach { (name, _) -> args[name] = "" }
                args["InstanceID"] = "0"
                args["ConnectionID"] = "0"
                val result = r.control(service, SoapRequest(a.name, service.type, args), 0)
                if (result is SoapResult.Fault) {
                    assertTrue("${service.id}#${a.name} -> ${result.code}", result.code != 401)
                } else {
                    val outs = (result as SoapResult.Ok).out.map { it.first }
                    assertEquals("${service.id}#${a.name}", a.outs.map { it.first }, outs)
                }
            }
        }
    }

    @Test
    fun eventsCarryWhatChangedAtMostFiveTimesASecond() {
        cast(now = 1000)
        val first = events.single()
        assertEquals(avt, first.first)
        val vars = first.second.associate { it.name to it.value }
        assertEquals(MediaRenderer.TRANSITIONING, vars["TransportState"])
        assertEquals("http://cdn/a.m3u8", vars["AVTransportURI"])
        assertEquals(didl, vars["CurrentTrackMetaData"])
        assertNull(vars["Volume"])
        // A change inside the 200 ms waits for the next tick
        r.onPlayer(1, PlayerState.PLAYING, 0, 5_400_000, 1100)
        assertEquals(1, events.size)
        r.tick(1150)
        assertEquals(1, events.size)
        r.tick(1200)
        val second = events.last().second.associate { it.name to it.value }
        assertEquals(MediaRenderer.PLAYING, second["TransportState"])
        assertEquals("01:30:00", second["CurrentTrackDuration"])
        assertNull(second["AVTransportURI"])
        // Nothing new, nothing sent; a volume change on the headset is
        r.tick(2000)
        assertEquals(2, events.size)
        host.vol = 12
        r.tick(3000)
        val vol = events.last()
        assertEquals(rcs, vol.first)
        assertEquals(EventVar("Volume", "12", "Master"), vol.second.single())
    }

    @Test
    fun fullStateForANewSubscriber() {
        val avtState = r.eventState(avt).associate { it.name to it.value }
        assertEquals(MediaRenderer.NO_MEDIA, avtState["TransportState"])
        assertEquals("NONE", avtState["PlaybackStorageMedium"])
        val rcsState = r.eventState(rcs)
        assertNotNull(rcsState.firstOrNull { it.name == "Volume" && it.channel == "Master" && it.value == "40" })
    }
}
