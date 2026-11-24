package com.limelight.local.dlna

import org.xmlpull.v1.XmlPullParser

/** One control call: the action, the service it named, and its arguments by local name. */
data class SoapRequest(val action: String, val serviceType: String?, val args: Map<String, String>) {
    /** An argument by name, ignoring case, since a few senders capitalise their own way. */
    fun arg(name: String): String? = args[name] ?: args.entries.firstOrNull { it.key.equals(name, true) }?.value
}

/** A control call's answer: the out arguments in order, or a UPnP error. */
sealed interface SoapResult {
    data class Ok(val out: List<Pair<String, String>> = emptyList()) : SoapResult
    data class Fault(val code: Int, val description: String) : SoapResult
}

/**
 * SOAP as UPnP uses it. The body is read with a parser first and, when a
 * sender wrote something no parser takes (raw DIDL inside an argument, an &
 * the parser could not place), with a pattern match over the text, so a
 * slightly broken cast still plays.
 */
object Soap {
    private const val ENVELOPE = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
    private const val END = "</s:Body></s:Envelope>"

    /** `"urn:schemas-upnp-org:service:AVTransport:1#Play"` into the service type and the action. */
    fun splitSoapAction(header: String?): Pair<String?, String?> {
        val h = header?.trim()?.trim('"')?.trim() ?: return null to null
        val hash = h.lastIndexOf('#')
        if (hash < 0) {
            return null to h.ifEmpty { null }
        }
        return h.substring(0, hash).ifEmpty { null } to h.substring(hash + 1).ifEmpty { null }
    }

    fun parse(body: String, soapAction: String?): SoapRequest? {
        val (headerType, headerAction) = splitSoapAction(soapAction)
        val parsed = try {
            parseXml(body)
        } catch (_: Exception) {
            null
        } ?: parseLoose(body)
        if (parsed == null) {
            return headerAction?.let { SoapRequest(it, headerType, emptyMap()) }
        }
        return SoapRequest(parsed.action.ifEmpty { headerAction ?: return null },
            parsed.serviceType ?: headerType, parsed.args)
    }

    private fun parseXml(body: String): SoapRequest? {
        val p = Xml.parser(body)
        var inBody = false
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> return null
                XmlPullParser.START_TAG -> {
                    val name = Xml.localName(p.name)
                    if (!inBody) {
                        inBody = name == "Body"
                        continue
                    }
                    var type: String? = null
                    for (i in 0 until p.attributeCount) {
                        if (p.getAttributeName(i).startsWith("xmlns")) {
                            type = p.getAttributeValue(i)
                        }
                    }
                    return SoapRequest(name, type, readArgs(p))
                }
            }
        }
    }

    // Children of the action element, up to its end tag
    private fun readArgs(p: XmlPullParser): Map<String, String> {
        val args = LinkedHashMap<String, String>()
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == depth)) {
                return args
            }
            if (ev == XmlPullParser.START_TAG) {
                val name = Xml.localName(p.name)
                args[name] = readInner(p)
            }
        }
    }

    // The element's text; where a sender put raw XML (DIDL) inside instead
    // of escaping it, that XML comes back as written
    private fun readInner(p: XmlPullParser): String {
        val depth = p.depth
        val text = StringBuilder()
        while (true) {
            when (p.next()) {
                XmlPullParser.TEXT -> text.append(if (p.depth > depth) Xml.escapeText(p.text) else p.text)
                XmlPullParser.START_TAG -> {
                    text.append('<').append(p.name)
                    for (i in 0 until p.attributeCount) {
                        text.append(' ').append(p.getAttributeName(i)).append("=\"")
                            .append(Xml.escape(p.getAttributeValue(i))).append('"')
                    }
                    text.append('>')
                }
                XmlPullParser.END_TAG -> {
                    if (p.depth == depth) {
                        return text.toString()
                    }
                    text.append("</").append(p.name).append('>')
                }
                XmlPullParser.END_DOCUMENT -> return text.toString()
            }
        }
    }

    private val bodyStart = Regex("<(?:[\\w.-]+:)?Body\\b[^>]*>\\s*<((?:[\\w.-]+:)?([\\w.-]+))([^>]*)>",
        RegexOption.IGNORE_CASE)
    private val xmlns = Regex("xmlns(?::[\\w.-]+)?\\s*=\\s*[\"']([^\"']*)[\"']")
    private val element = Regex("<((?:[\\w.-]+:)?([A-Za-z_][\\w.-]*))(?:\\s[^>]*)?>(.*?)</\\1\\s*>", RegexOption.DOT_MATCHES_ALL)

    private fun parseLoose(body: String): SoapRequest? {
        val m = bodyStart.find(body) ?: return null
        val qualified = m.groupValues[1]
        val action = m.groupValues[2]
        val type = xmlns.find(m.groupValues[3])?.groupValues?.get(1)
        val from = m.range.last + 1
        val close = body.indexOf("</$qualified", from)
        val inner = if (close >= 0) body.substring(from, close) else body.substring(from)
        val args = LinkedHashMap<String, String>()
        for (e in element.findAll(inner)) {
            val v = e.groupValues[3]
            args[e.groupValues[2]] = if (v.contains('<')) v else Xml.unescape(v)
        }
        return SoapRequest(action, type, args)
    }

    fun response(serviceType: String, action: String, out: List<Pair<String, String>>): String {
        val b = StringBuilder(ENVELOPE)
        b.append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">")
        for ((k, v) in out) {
            b.append('<').append(k).append('>').append(Xml.escapeText(v)).append("</").append(k).append('>')
        }
        b.append("</u:").append(action).append("Response>").append(END)
        return b.toString()
    }

    fun fault(code: Int, description: String): String = ENVELOPE +
        "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
        "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code +
        "</errorCode><errorDescription>" + Xml.escapeText(description) + "</errorDescription>" +
        "</UPnPError></detail></s:Fault>" + END
}
