package com.limelight.local.dlna

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * The little XML the receiver needs. Parsing is deliberately not namespace
 * aware: phone apps send prefixes they never declared (`<u:Play>` with no
 * xmlns:u), and a strict parser would turn those casts away. Names are
 * compared by their local part instead.
 */
internal object Xml {
    fun escape(s: String): String {
        val b = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> b.append("&amp;")
                '<' -> b.append("&lt;")
                '>' -> b.append("&gt;")
                '"' -> b.append("&quot;")
                '\'' -> b.append("&apos;")
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    /** Element text: only what XML requires, which naive readers on the phone side also undo. */
    fun escapeText(s: String): String {
        val b = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> b.append("&amp;")
                '<' -> b.append("&lt;")
                '>' -> b.append("&gt;")
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

    /** One level of entity decoding, for text pulled out without a parser. */
    fun unescape(s: String): String = entity.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
            }
        }
    }

    private const val RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"

    fun localName(name: String): String = name.substringAfter(':')

    /** A parser over [text], with the BOM and anything before the first tag dropped. */
    fun parser(text: String): XmlPullParser {
        val start = text.indexOf('<')
        val body = if (start > 0) text.substring(start) else text
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // kXML's relaxed mode lets a bare & in a URL through, which senders
        // write more often than they should
        try {
            p.setFeature(RELAXED, true)
        } catch (_: Exception) {
        }
        p.setInput(StringReader(body))
        return p
    }
}
