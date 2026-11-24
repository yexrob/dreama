package com.limelight.local.dlna

import org.xmlpull.v1.XmlPullParser

/** What a cast's DIDL-Lite says about the video: the title the phone shows, and its res. */
data class DidlItem(
    val title: String?,
    val upnpClass: String?,
    val resUrl: String?,
    val protocolInfo: String?,
    val durationMs: Long?,
) {
    val mimeType: String? get() = protocolInfo?.split(':')?.getOrNull(2)?.trim()?.ifEmpty { null }
    val isImage: Boolean get() = upnpClass?.startsWith("object.item.imageItem") == true ||
        mimeType?.startsWith("image/") == true
}

/**
 * DIDL-Lite as phone apps send it in CurrentURIMetaData. Some escape it once
 * (the spec), some twice, some send it raw inside the SOAP argument, some
 * leave a bare & in the res URL, and many send nothing or "NOT_IMPLEMENTED".
 */
object Didl {
    fun parse(metadata: String?): DidlItem? {
        var s = metadata?.trim() ?: return null
        if (s.startsWith("&lt;")) {
            s = Xml.unescape(s).trim()
        }
        if (!s.startsWith("<")) {
            return null
        }
        val strict = try {
            parseXml(s)
        } catch (_: Exception) {
            null
        }
        return strict ?: parseLoose(s)
    }

    private fun parseXml(s: String): DidlItem? {
        val p = Xml.parser(s)
        var inItem = false
        var title: String? = null
        var cls: String? = null
        var res: String? = null
        var info: String? = null
        var duration: Long? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (Xml.localName(p.name)) {
                    "item", "container" -> inItem = true
                    "title" -> if (inItem && title == null) title = p.nextText().trim()
                    "class" -> if (inItem && cls == null) cls = p.nextText().trim()
                    "res" -> if (inItem && res == null) {
                        info = p.getAttributeValue(null, "protocolInfo")
                        duration = UpnpTime.parse(p.getAttributeValue(null, "duration"))
                        res = p.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> if (Xml.localName(p.name) == "item") break
            }
        }
        if (!inItem) {
            return null
        }
        return DidlItem(title?.ifEmpty { null }, cls, res?.ifEmpty { null }, info, duration)
    }

    private fun tag(name: String) =
        Regex("<(?:[\\w.-]+:)?$name\\b([^>]*)>(.*?)</(?:[\\w.-]+:)?$name\\s*>", RegexOption.DOT_MATCHES_ALL)

    private val titleTag = tag("title")
    private val classTag = tag("class")
    private val resTag = tag("res")

    private fun attr(attrs: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?.let(Xml::unescape)

    private fun parseLoose(s: String): DidlItem? {
        val title = titleTag.find(s)?.groupValues?.get(2)?.let(Xml::unescape)?.trim()
        val cls = classTag.find(s)?.groupValues?.get(2)?.let(Xml::unescape)?.trim()
        val res = resTag.find(s)
        if (title == null && cls == null && res == null) {
            return null
        }
        return DidlItem(title?.ifEmpty { null }, cls,
            res?.groupValues?.get(2)?.let(Xml::unescape)?.trim()?.ifEmpty { null },
            res?.groupValues?.get(1)?.let { attr(it, "protocolInfo") },
            res?.groupValues?.get(1)?.let { UpnpTime.parse(attr(it, "duration")) })
    }
}

/**
 * Which kind of stream a cast is, where the URL alone would not tell the
 * player: phone apps hand out HLS behind URLs with no .m3u8 in the path.
 */
object MediaHint {
    const val HLS = "application/x-mpegURL"
    const val DASH = "application/dash+xml"

    @JvmStatic
    fun guess(url: String, mimeType: String?): String? {
        val mime = mimeType?.lowercase()
        if (mime != null) {
            if (mime.contains("mpegurl")) {
                return HLS
            }
            if (mime.contains("dash")) {
                return DASH
            }
        }
        val path = url.substringBefore('#').lowercase()
        val bare = path.substringBefore('?')
        return when {
            bare.endsWith(".m3u8") || bare.endsWith(".m3u") -> HLS
            bare.endsWith(".mpd") -> DASH
            // A playlist named in the query, as some CDNs and proxies do
            path.contains(".m3u8") -> HLS
            else -> null
        }
    }
}
