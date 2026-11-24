package com.limelight.local.dlna

/** What the headset's player says it is doing, once it has the cast. */
enum class PlayerState { LOADING, PLAYING, PAUSED, ENDED, ERROR, CLOSED }

/** A cast for the player to open: [castId] ties its later reports back to this cast. */
data class CastMedia(
    val castId: Long,
    val uri: String,
    val metadata: String,
    val title: String?,
    /** HLS or DASH when the cast says so or the URL shows it; null leaves it to the player to sniff. */
    val mimeType: String?,
    val startMs: Long,
)

/**
 * What the renderer drives. Calls come from network threads and must return
 * at once (post to the player's thread) and never call back into the
 * renderer on the same stack.
 */
interface RendererHost {
    fun open(media: CastMedia)
    fun play(castId: Long)
    fun pause(castId: Long)
    fun seek(castId: Long, ms: Long)
    /** Closes the player if it is still showing [castId]. */
    fun close(castId: Long)
    /** 0..100 */
    fun volume(): Int
    fun setVolume(volume: Int)
    fun muted(): Boolean
    fun setMuted(muted: Boolean)
}

/** What the cast page shows. */
data class RendererSnapshot(val castId: Long, val transportState: String, val title: String?, val uri: String)

/**
 * AVTransport, RenderingControl and ConnectionManager for one instance,
 * independent of Android so it can be tested on the JVM.
 *
 * Phone apps are loose about the order of their calls, so the renderer is
 * forgiving where the spec is strict:
 * - SetAVTransportURI starts playing; a later Play finds it playing already.
 *   The same URI again while it plays changes nothing.
 * - Pause and Seek that arrive before the player is up wait for it.
 * - Stop pauses at once but closes the player only after [STOP_GRACE_MS], as
 *   apps switching episodes send Stop, then the next URI, then Play; the next
 *   URI then plays in the same session instead of a new one.
 * - After a command, a player report that still says the old state is not
 *   taken for [EXPECT_MS], so a phone polling right after Play sees PLAYING.
 */
class MediaRenderer(private val host: RendererHost, firstCastId: Long = 1) {
    companion object {
        const val NO_MEDIA = "NO_MEDIA_PRESENT"
        const val STOPPED = "STOPPED"
        const val PLAYING = "PLAYING"
        const val PAUSED = "PAUSED_PLAYBACK"
        const val TRANSITIONING = "TRANSITIONING"

        const val STOP_GRACE_MS = 2000L
        const val OPEN_TIMEOUT_MS = 30_000L
        const val EXPECT_MS = 1500L
        const val SEEK_HOLD_MS = 1000L
        const val EVENT_INTERVAL_MS = 200L

        private const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
        private const val MAX_COUNT = "2147483647"

        private fun ok(vararg out: Pair<String, String>) = SoapResult.Ok(out.toList())
        private fun fault(code: Int, text: String) = SoapResult.Fault(code, text)
        private val INVALID_ARGS = fault(402, "Invalid Args")
        private val NOT_AVAILABLE = fault(701, "Transition not available")

        fun parseBool(text: String?): Boolean? = when (text?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    /** Changed LastChange values of a service; called outside the renderer's lock. */
    @Volatile
    var events: ((UpnpService, List<EventVar>) -> Unit)? = null

    private val lock = Any()
    private var nextId = firstCastId
    private var castId = 0L
    private var uri = ""
    private var meta = ""
    private var title: String? = null
    private var mime: String? = null
    private var nextUri = ""
    private var nextMeta = ""
    private var state = NO_MEDIA
    private var status = "OK"
    private var posMs = 0L
    private var durMs = 0L
    private var attached = false
    private var ended = false
    private var errored = false
    private var openedAt = 0L
    private var stopAt: Long? = null
    /** The cast whose player was told to close; its late reports are dropped. */
    private var closedCast = 0L
    private var pendingPause = false
    private var pendingSeek: Long? = null
    private var expected: String? = null
    private var expectedUntil = 0L
    private var posHoldUntil = 0L
    private var lastEventAt: Long? = null
    // What subscribers were last told, from the state at start
    private var sentAvt: Map<String, EventVar> = avtVars().associateBy { it.name }
    private var sentRcs: Map<String, EventVar> = rcsVars().associateBy { it.name }

    fun snapshot(): RendererSnapshot = synchronized(lock) { RendererSnapshot(castId, state, title, uri) }

    fun control(service: UpnpService, req: SoapRequest, now: Long): SoapResult {
        val iid = req.arg("InstanceID")?.trim()
        if (service != UpnpService.ConnectionManager && !iid.isNullOrEmpty() && iid != "0") {
            return fault(718, "Invalid InstanceID")
        }
        val result = when (service) {
            UpnpService.AVTransport -> synchronized(lock) { transport(req, now) }
            UpnpService.RenderingControl -> rendering(req)
            UpnpService.ConnectionManager -> connection(req)
        }
        flushEvents(now)
        return result
    }

    private fun transport(req: SoapRequest, now: Long): SoapResult = when (req.action) {
        "SetAVTransportURI" -> setUri(req.arg("CurrentURI").orEmpty(), req.arg("CurrentURIMetaData").orEmpty(), now)
        "SetNextAVTransportURI" -> {
            nextUri = req.arg("NextURI").orEmpty().trim()
            nextMeta = req.arg("NextURIMetaData").orEmpty()
            ok()
        }
        "Play" -> play(now)
        "Pause" -> pause(now)
        "Stop" -> stop(now)
        "Seek" -> seek(req.arg("Unit"), req.arg("Target"), now)
        "Next", "Previous" -> NOT_AVAILABLE
        "SetPlayMode" -> if (req.arg("NewPlayMode")?.trim()?.uppercase() in setOf(null, "", "NORMAL")) ok()
            else fault(712, "Play mode not supported")
        "GetTransportInfo" -> ok("CurrentTransportState" to state, "CurrentTransportStatus" to status,
            "CurrentSpeed" to "1")
        "GetPositionInfo" -> {
            val pos = UpnpTime.format(posMs)
            ok("Track" to track(), "TrackDuration" to UpnpTime.format(durMs), "TrackMetaData" to meta,
                "TrackURI" to uri, "RelTime" to pos, "AbsTime" to pos, "RelCount" to MAX_COUNT, "AbsCount" to MAX_COUNT)
        }
        "GetMediaInfo" -> ok("NrTracks" to track(), "MediaDuration" to UpnpTime.format(durMs), "CurrentURI" to uri,
            "CurrentURIMetaData" to meta, "NextURI" to nextUri, "NextURIMetaData" to nextMeta,
            "PlayMedium" to medium(), "RecordMedium" to NOT_IMPLEMENTED, "WriteStatus" to NOT_IMPLEMENTED)
        "GetDeviceCapabilities" -> ok("PlayMedia" to "NETWORK", "RecMedia" to NOT_IMPLEMENTED,
            "RecQualityModes" to NOT_IMPLEMENTED)
        "GetTransportSettings" -> ok("PlayMode" to "NORMAL", "RecQualityMode" to NOT_IMPLEMENTED)
        "GetCurrentTransportActions" -> ok("Actions" to actions())
        else -> fault(401, "Invalid Action")
    }

    private fun track() = if (uri.isEmpty()) "0" else "1"
    private fun medium() = if (uri.isEmpty()) "NONE" else "NETWORK"
    private fun actions() = if (uri.isEmpty()) "" else "Play,Pause,Stop,Seek"

    private fun setUri(newUri: String, newMeta: String, now: Long): SoapResult {
        val u = newUri.trim()
        if (u.isEmpty()) {
            return INVALID_ARGS
        }
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            return fault(716, "Resource not found")
        }
        val item = Didl.parse(newMeta)
        if (item?.isImage == true) {
            return fault(714, "Illegal MIME-type")
        }
        val live = attached || state == TRANSITIONING
        if (u == uri && live && stopAt == null && !errored) {
            // The same video again, as some apps send on every reconnect: keep it playing
            if (newMeta.isNotBlank()) {
                meta = newMeta
                title = item?.title ?: title
            }
            return ok()
        }
        open(u, newMeta, item, 0, now)
        return ok()
    }

    private fun open(u: String, newMeta: String, item: DidlItem?, startMs: Long, now: Long) {
        val id = nextId++
        castId = id
        uri = u
        meta = newMeta
        title = item?.title
        mime = MediaHint.guess(u, item?.mimeType)
        durMs = item?.durationMs ?: 0
        posMs = startMs
        state = TRANSITIONING
        status = "OK"
        attached = false
        ended = false
        errored = false
        openedAt = now
        stopAt = null
        pendingPause = false
        pendingSeek = null
        expected = null
        nextUri = ""
        nextMeta = ""
        host.open(CastMedia(id, u, newMeta, title, mime, startMs))
    }

    private fun expect(s: String, now: Long) {
        state = s
        expected = s
        expectedUntil = now + EXPECT_MS
    }

    private fun play(now: Long): SoapResult {
        if (uri.isEmpty()) {
            return NOT_AVAILABLE
        }
        if (stopAt != null) {
            // Play inside the grace after Stop: the same player carries on
            stopAt = null
            if (attached) {
                host.play(castId)
                expect(PLAYING, now)
            } else {
                pendingPause = false
                state = TRANSITIONING
            }
            return ok()
        }
        when {
            !attached && state != TRANSITIONING || errored ->
                open(uri, meta, Didl.parse(meta), if (ended) 0 else posMs, now)
            ended -> {
                ended = false
                posMs = 0
                host.seek(castId, 0)
                host.play(castId)
                expect(PLAYING, now)
            }
            state == TRANSITIONING -> {
                pendingPause = false
                if (attached) {
                    host.play(castId)
                }
            }
            state != PLAYING -> {
                host.play(castId)
                expect(PLAYING, now)
            }
        }
        return ok()
    }

    private fun pause(now: Long): SoapResult {
        if (uri.isEmpty()) {
            return NOT_AVAILABLE
        }
        if (stopAt != null || state == STOPPED) {
            return ok()
        }
        if (!attached) {
            pendingPause = true
            return ok()
        }
        host.pause(castId)
        if (state == PLAYING) {
            expect(PAUSED, now)
        }
        return ok()
    }

    private fun stop(now: Long): SoapResult {
        if (uri.isEmpty() || stopAt != null) {
            return ok()
        }
        if (attached || state == TRANSITIONING) {
            if (attached) {
                host.pause(castId)
            } else {
                pendingPause = true
            }
            stopAt = now + STOP_GRACE_MS
        }
        state = STOPPED
        expected = null
        pendingSeek = null
        return ok()
    }

    private fun seek(unit: String?, target: String?, now: Long): SoapResult {
        if (uri.isEmpty()) {
            return NOT_AVAILABLE
        }
        when (unit?.trim()?.uppercase()) {
            null, "", "REL_TIME", "ABS_TIME" -> {}
            "TRACK_NR" -> return ok()
            else -> return fault(710, "Seek mode not supported")
        }
        var ms = UpnpTime.parse(target) ?: return fault(711, "Illegal seek target")
        if (durMs > 0) {
            ms = ms.coerceAtMost(durMs)
        }
        posMs = ms
        posHoldUntil = now + SEEK_HOLD_MS
        ended = false
        if (attached) {
            host.seek(castId, ms)
        } else {
            pendingSeek = ms
        }
        return ok()
    }

    private fun rendering(req: SoapRequest): SoapResult = when (req.action) {
        "GetVolume" -> ok("CurrentVolume" to host.volume().coerceIn(0, 100).toString())
        "SetVolume" -> {
            val v = req.arg("DesiredVolume")?.trim()?.toDoubleOrNull()
            if (v == null || v.isNaN()) {
                INVALID_ARGS
            } else {
                host.setVolume(Math.round(v).toInt().coerceIn(0, 100))
                ok()
            }
        }
        "GetMute" -> ok("CurrentMute" to if (host.muted()) "1" else "0")
        "SetMute" -> when (val m = parseBool(req.arg("DesiredMute"))) {
            null -> INVALID_ARGS
            else -> {
                host.setMuted(m)
                ok()
            }
        }
        "ListPresets" -> ok("CurrentPresetNameList" to "FactoryDefaults")
        "SelectPreset" -> ok()
        else -> fault(401, "Invalid Action")
    }

    private fun connection(req: SoapRequest): SoapResult = when (req.action) {
        "GetProtocolInfo" -> ok("Source" to "", "Sink" to Descriptions.SINK_PROTOCOL_INFO)
        "GetCurrentConnectionIDs" -> ok("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> if (req.arg("ConnectionID")?.trim().let { it.isNullOrEmpty() || it == "0" }) {
            ok("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
        } else {
            fault(706, "Invalid connection reference")
        }
        else -> fault(401, "Invalid Action")
    }

    /** A report from the player; reports for any cast but the current one are stale and dropped. */
    fun onPlayer(id: Long, st: PlayerState, pos: Long, dur: Long, now: Long) {
        synchronized(lock) {
            if (id == 0L || id != castId || id == closedCast) {
                return@synchronized
            }
            if (!attached && st != PlayerState.CLOSED) {
                attached = true
                pendingSeek?.let { host.seek(castId, it) }
                if (pendingPause || stopAt != null) {
                    host.pause(castId)
                }
                pendingSeek = null
                pendingPause = false
            }
            if (dur > 0) {
                durMs = dur
            }
            if (now >= posHoldUntil) {
                posMs = pos.coerceAtLeast(0)
            }
            report(st, now)
        }
        flushEvents(now)
    }

    private fun report(st: PlayerState, now: Long) {
        if (st == PlayerState.CLOSED) {
            attached = false
            stopAt = null
            expected = null
            ended = false
            errored = false
            state = STOPPED
            return
        }
        if (stopAt != null) {
            return
        }
        when (st) {
            PlayerState.ENDED -> {
                if (expected != null && now < expectedUntil) {
                    // Play after the end: the player has not yet taken the seek back to the start
                    return
                }
                expected = null
                if (nextUri.isNotEmpty()) {
                    // The app queued the next episode: play it in the same session
                    open(nextUri, nextMeta, Didl.parse(nextMeta), 0, now)
                    return
                }
                ended = true
                state = STOPPED
            }
            PlayerState.ERROR -> {
                expected = null
                errored = true
                status = "ERROR_OCCURRED"
                state = STOPPED
            }
            else -> {
                val s = when (st) {
                    PlayerState.LOADING -> TRANSITIONING
                    PlayerState.PLAYING -> PLAYING
                    else -> PAUSED
                }
                val e = expected
                if (e != null) {
                    if (s != e && now < expectedUntil) {
                        return
                    }
                    expected = null
                }
                if (ended && s == PAUSED) {
                    return
                }
                ended = false
                errored = false
                status = "OK"
                state = s
            }
        }
    }

    /** Runs the timers (Stop's grace, a player that never came up) and sends what changed. */
    fun tick(now: Long) {
        synchronized(lock) {
            stopAt?.let {
                if (now >= it) {
                    stopAt = null
                    attached = false
                    closedCast = castId
                    host.close(castId)
                }
            }
            if (state == TRANSITIONING && !attached && now - openedAt >= OPEN_TIMEOUT_MS) {
                state = STOPPED
                status = "ERROR_OCCURRED"
                errored = true
            }
        }
        flushEvents(now)
    }

    /** The full evented state of [service], for a new subscriber's first NOTIFY. */
    fun eventState(service: UpnpService): List<EventVar> = when (service) {
        UpnpService.AVTransport -> synchronized(lock) { avtVars() }
        UpnpService.RenderingControl -> rcsVars()
        UpnpService.ConnectionManager -> emptyList()
    }

    private fun avtVars(): List<EventVar> = listOf(
        EventVar("TransportState", state),
        EventVar("TransportStatus", status),
        EventVar("PlaybackStorageMedium", medium()),
        EventVar("PossiblePlaybackStorageMedia", "NETWORK"),
        EventVar("PossibleRecordStorageMedia", NOT_IMPLEMENTED),
        EventVar("RecordStorageMedium", NOT_IMPLEMENTED),
        EventVar("RecordMediumWriteStatus", NOT_IMPLEMENTED),
        EventVar("CurrentRecordQualityMode", NOT_IMPLEMENTED),
        EventVar("PossibleRecordQualityModes", NOT_IMPLEMENTED),
        EventVar("CurrentPlayMode", "NORMAL"),
        EventVar("TransportPlaySpeed", "1"),
        EventVar("NumberOfTracks", track()),
        EventVar("CurrentTrack", track()),
        EventVar("CurrentTrackDuration", UpnpTime.format(durMs)),
        EventVar("CurrentMediaDuration", UpnpTime.format(durMs)),
        EventVar("CurrentTrackURI", uri),
        EventVar("AVTransportURI", uri),
        EventVar("CurrentTrackMetaData", meta),
        EventVar("AVTransportURIMetaData", meta),
        EventVar("NextAVTransportURI", nextUri),
        EventVar("NextAVTransportURIMetaData", nextMeta),
        EventVar("CurrentTransportActions", actions()),
    )

    private fun rcsVars(): List<EventVar> = listOf(
        EventVar("Volume", host.volume().coerceIn(0, 100).toString(), "Master"),
        EventVar("Mute", if (host.muted()) "1" else "0", "Master"),
        EventVar("PresetNameList", "FactoryDefaults"),
    )

    /**
     * Sends the values that changed since the last event, at most every
     * [EVENT_INTERVAL_MS] (LastChange's moderation); what is held back goes
     * out with the next tick.
     */
    fun flushEvents(now: Long) {
        val rcs = rcsVars()
        val out = ArrayList<Pair<UpnpService, List<EventVar>>>(2)
        synchronized(lock) {
            val avt = avtVars()
            val last = lastEventAt
            if (last != null && now - last < EVENT_INTERVAL_MS) {
                return
            }
            val changedAvt = avt.filter { sentAvt[it.name] != it }
            val changedRcs = rcs.filter { sentRcs[it.name] != it }
            if (changedAvt.isEmpty() && changedRcs.isEmpty()) {
                return
            }
            lastEventAt = now
            if (changedAvt.isNotEmpty()) {
                sentAvt = avt.associateBy { it.name }
                out += UpnpService.AVTransport to changedAvt
            }
            if (changedRcs.isNotEmpty()) {
                sentRcs = rcs.associateBy { it.name }
                out += UpnpService.RenderingControl to changedRcs
            }
        }
        val sink = events ?: return
        for ((service, vars) in out) {
            sink(service, vars)
        }
    }
}
