package com.limelight.local.dlna

/** The three services a MediaRenderer:1 carries, and where each one lives on the HTTP server. */
enum class UpnpService(val id: String, val type: String) {
    AVTransport("AVTransport", "urn:schemas-upnp-org:service:AVTransport:1"),
    RenderingControl("RenderingControl", "urn:schemas-upnp-org:service:RenderingControl:1"),
    ConnectionManager("ConnectionManager", "urn:schemas-upnp-org:service:ConnectionManager:1");

    val scpdPath get() = "/dlna/$id.xml"
    val controlPath get() = "/dlna/$id/control"
    val eventPath get() = "/dlna/$id/event"

    companion object {
        /** The service a request path belongs to, matched loosely ("/dlna/AVTransport/control", "/AVTransport/ctrl"). */
        fun forPath(path: String): UpnpService? = entries.firstOrNull { path.contains(it.id, ignoreCase = true) }
    }
}

/**
 * The device and service descriptions. Control points built on Cling read the
 * service descriptions strictly and drop a device whose action names a state
 * variable that is not declared, so all three are generated from one table.
 */
object Descriptions {
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val DESCRIPTION_PATH = "/dlna/description.xml"

    /** What the receiver says it takes. Specific types first for senders that check, then anything. */
    val SINK_PROTOCOL_INFO = listOf(
        "video/mp4", "video/x-flv", "video/flv", "application/x-mpegURL", "application/vnd.apple.mpegurl",
        "video/x-mpegurl", "application/dash+xml", "video/x-matroska", "video/webm", "video/mp2t",
        "video/MP2T", "video/mpeg", "video/quicktime", "video/3gpp", "video/x-ms-wmv", "video/avi",
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-flac", "audio/flac", "audio/wav",
    ).joinToString(",") { "http-get:*:$it:*" } + ",http-get:*:*:*"

    fun device(name: String, udn: String, version: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>$DEVICE_TYPE</deviceType>
<friendlyName>${Xml.escape(name)}</friendlyName>
<manufacturer>Dreama</manufacturer>
<modelDescription>Dreama 2D to 3D media renderer</modelDescription>
<modelName>Dreama</modelName>
<modelNumber>${Xml.escape(version)}</modelNumber>
<serialNumber>${udn.takeLast(12)}</serialNumber>
<UDN>uuid:$udn</UDN>
<dlna:X_DLNADOC xmlns:dlna="urn:schemas-dlna-org:device-1-0">DMR-1.50</dlna:X_DLNADOC>
<serviceList>
${UpnpService.entries.joinToString("\n") { service(it) }}
</serviceList>
</device>
</root>
"""

    private fun service(s: UpnpService) = "<service><serviceType>${s.type}</serviceType>" +
        "<serviceId>urn:upnp-org:serviceId:${s.id}</serviceId><SCPDURL>${s.scpdPath}</SCPDURL>" +
        "<controlURL>${s.controlPath}</controlURL><eventSubURL>${s.eventPath}</eventSubURL></service>"

    class Var(val name: String, val type: String = "string", val events: Boolean = false,
              val allowed: List<String> = emptyList(), val range: IntRange? = null)

    /** An action: its in arguments, then its out arguments, each as (argument, state variable). */
    class Action(val name: String, val ins: List<Pair<String, String>> = emptyList(),
                 val outs: List<Pair<String, String>> = emptyList())

    class Scpd(val actions: List<Action>, val vars: List<Var>) {
        fun action(name: String) = actions.firstOrNull { it.name == name }
    }

    private const val IID = "A_ARG_TYPE_InstanceID"
    private val instance = listOf("InstanceID" to IID)

    val AVT = Scpd(
        actions = listOf(
            Action("SetAVTransportURI", instance + listOf("CurrentURI" to "AVTransportURI",
                "CurrentURIMetaData" to "AVTransportURIMetaData")),
            Action("SetNextAVTransportURI", instance + listOf("NextURI" to "NextAVTransportURI",
                "NextURIMetaData" to "NextAVTransportURIMetaData")),
            Action("GetMediaInfo", instance, listOf("NrTracks" to "NumberOfTracks",
                "MediaDuration" to "CurrentMediaDuration", "CurrentURI" to "AVTransportURI",
                "CurrentURIMetaData" to "AVTransportURIMetaData", "NextURI" to "NextAVTransportURI",
                "NextURIMetaData" to "NextAVTransportURIMetaData", "PlayMedium" to "PlaybackStorageMedium",
                "RecordMedium" to "RecordStorageMedium", "WriteStatus" to "RecordMediumWriteStatus")),
            Action("GetTransportInfo", instance, listOf("CurrentTransportState" to "TransportState",
                "CurrentTransportStatus" to "TransportStatus", "CurrentSpeed" to "TransportPlaySpeed")),
            Action("GetPositionInfo", instance, listOf("Track" to "CurrentTrack",
                "TrackDuration" to "CurrentTrackDuration", "TrackMetaData" to "CurrentTrackMetaData",
                "TrackURI" to "CurrentTrackURI", "RelTime" to "RelativeTimePosition",
                "AbsTime" to "AbsoluteTimePosition", "RelCount" to "RelativeCounterPosition",
                "AbsCount" to "AbsoluteCounterPosition")),
            Action("GetDeviceCapabilities", instance, listOf("PlayMedia" to "PossiblePlaybackStorageMedia",
                "RecMedia" to "PossibleRecordStorageMedia", "RecQualityModes" to "PossibleRecordQualityModes")),
            Action("GetTransportSettings", instance, listOf("PlayMode" to "CurrentPlayMode",
                "RecQualityMode" to "CurrentRecordQualityMode")),
            Action("Stop", instance),
            Action("Play", instance + listOf("Speed" to "TransportPlaySpeed")),
            Action("Pause", instance),
            Action("Seek", instance + listOf("Unit" to "A_ARG_TYPE_SeekMode", "Target" to "A_ARG_TYPE_SeekTarget")),
            Action("Next", instance),
            Action("Previous", instance),
            Action("SetPlayMode", instance + listOf("NewPlayMode" to "CurrentPlayMode")),
            Action("GetCurrentTransportActions", instance, listOf("Actions" to "CurrentTransportActions")),
        ),
        vars = listOf(
            Var("TransportState", allowed = listOf("STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK",
                "NO_MEDIA_PRESENT")),
            Var("TransportStatus", allowed = listOf("OK", "ERROR_OCCURRED")),
            Var("PlaybackStorageMedium", allowed = listOf("NONE", "NETWORK")),
            Var("RecordStorageMedium", allowed = listOf("NOT_IMPLEMENTED")),
            Var("PossiblePlaybackStorageMedia"),
            Var("PossibleRecordStorageMedia"),
            Var("CurrentPlayMode", allowed = listOf("NORMAL", "REPEAT_ONE", "REPEAT_ALL", "SHUFFLE")),
            Var("TransportPlaySpeed", allowed = listOf("1")),
            Var("RecordMediumWriteStatus", allowed = listOf("NOT_IMPLEMENTED")),
            Var("CurrentRecordQualityMode", allowed = listOf("NOT_IMPLEMENTED")),
            Var("PossibleRecordQualityModes"),
            Var("NumberOfTracks", "ui4"),
            Var("CurrentTrack", "ui4"),
            Var("CurrentTrackDuration"),
            Var("CurrentMediaDuration"),
            Var("CurrentTrackMetaData"),
            Var("CurrentTrackURI"),
            Var("AVTransportURI"),
            Var("AVTransportURIMetaData"),
            Var("NextAVTransportURI"),
            Var("NextAVTransportURIMetaData"),
            Var("RelativeTimePosition"),
            Var("AbsoluteTimePosition"),
            Var("RelativeCounterPosition", "i4"),
            Var("AbsoluteCounterPosition", "i4"),
            Var("CurrentTransportActions"),
            Var("LastChange", events = true),
            Var("A_ARG_TYPE_SeekMode", allowed = listOf("ABS_TIME", "REL_TIME", "ABS_COUNT", "REL_COUNT",
                "TRACK_NR")),
            Var("A_ARG_TYPE_SeekTarget"),
            Var(IID, "ui4"),
        ),
    )

    private const val CHANNEL = "A_ARG_TYPE_Channel"

    val RCS = Scpd(
        actions = listOf(
            Action("ListPresets", instance, listOf("CurrentPresetNameList" to "PresetNameList")),
            Action("SelectPreset", instance + listOf("PresetName" to "A_ARG_TYPE_PresetName")),
            Action("GetMute", instance + listOf("Channel" to CHANNEL), listOf("CurrentMute" to "Mute")),
            Action("SetMute", instance + listOf("Channel" to CHANNEL, "DesiredMute" to "Mute")),
            Action("GetVolume", instance + listOf("Channel" to CHANNEL), listOf("CurrentVolume" to "Volume")),
            Action("SetVolume", instance + listOf("Channel" to CHANNEL, "DesiredVolume" to "Volume")),
        ),
        vars = listOf(
            Var("PresetNameList"),
            Var("LastChange", events = true),
            Var("Mute", "boolean"),
            Var("Volume", "ui2", range = 0..100),
            Var(CHANNEL, allowed = listOf("Master")),
            Var(IID, "ui4"),
            Var("A_ARG_TYPE_PresetName", allowed = listOf("FactoryDefaults")),
        ),
    )

    val CMS = Scpd(
        actions = listOf(
            Action("GetProtocolInfo", outs = listOf("Source" to "SourceProtocolInfo", "Sink" to "SinkProtocolInfo")),
            Action("GetCurrentConnectionIDs", outs = listOf("ConnectionIDs" to "CurrentConnectionIDs")),
            Action("GetCurrentConnectionInfo", listOf("ConnectionID" to "A_ARG_TYPE_ConnectionID"), listOf(
                "RcsID" to "A_ARG_TYPE_RcsID", "AVTransportID" to "A_ARG_TYPE_AVTransportID",
                "ProtocolInfo" to "A_ARG_TYPE_ProtocolInfo", "PeerConnectionManager" to "A_ARG_TYPE_ConnectionManager",
                "PeerConnectionID" to "A_ARG_TYPE_ConnectionID", "Direction" to "A_ARG_TYPE_Direction",
                "Status" to "A_ARG_TYPE_ConnectionStatus")),
        ),
        vars = listOf(
            Var("SourceProtocolInfo", events = true),
            Var("SinkProtocolInfo", events = true),
            Var("CurrentConnectionIDs", events = true),
            Var("A_ARG_TYPE_ConnectionStatus", allowed = listOf("OK", "ContentFormatMismatch",
                "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
            Var("A_ARG_TYPE_ConnectionManager"),
            Var("A_ARG_TYPE_Direction", allowed = listOf("Input", "Output")),
            Var("A_ARG_TYPE_ProtocolInfo"),
            Var("A_ARG_TYPE_ConnectionID", "i4"),
            Var("A_ARG_TYPE_AVTransportID", "i4"),
            Var("A_ARG_TYPE_RcsID", "i4"),
        ),
    )

    fun scpd(service: UpnpService): Scpd = when (service) {
        UpnpService.AVTransport -> AVT
        UpnpService.RenderingControl -> RCS
        UpnpService.ConnectionManager -> CMS
    }

    fun scpdXml(service: UpnpService): String {
        val s = scpd(service)
        val b = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        b.append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">")
        b.append("<specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
        for (a in s.actions) {
            b.append("<action><name>").append(a.name).append("</name>")
            if (a.ins.isNotEmpty() || a.outs.isNotEmpty()) {
                b.append("<argumentList>")
                for ((arg, v) in a.ins) {
                    argument(b, arg, "in", v)
                }
                for ((arg, v) in a.outs) {
                    argument(b, arg, "out", v)
                }
                b.append("</argumentList>")
            }
            b.append("</action>")
        }
        b.append("</actionList><serviceStateTable>")
        for (v in s.vars) {
            b.append("<stateVariable sendEvents=\"").append(if (v.events) "yes" else "no").append("\">")
            b.append("<name>").append(v.name).append("</name><dataType>").append(v.type).append("</dataType>")
            if (v.allowed.isNotEmpty()) {
                b.append("<allowedValueList>")
                v.allowed.forEach { b.append("<allowedValue>").append(it).append("</allowedValue>") }
                b.append("</allowedValueList>")
            }
            v.range?.let {
                b.append("<allowedValueRange><minimum>").append(it.first).append("</minimum><maximum>")
                    .append(it.last).append("</maximum><step>1</step></allowedValueRange>")
            }
            b.append("</stateVariable>")
        }
        b.append("</serviceStateTable></scpd>\n")
        return b.toString()
    }

    private fun argument(b: StringBuilder, name: String, dir: String, v: String) {
        b.append("<argument><name>").append(name).append("</name><direction>").append(dir)
            .append("</direction><relatedStateVariable>").append(v).append("</relatedStateVariable></argument>")
    }
}
