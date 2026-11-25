package com.limelight.local.danmaku

import com.limelight.local.plugin.DanmakuItem
import com.limelight.local.plugin.DanmakuMode
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/**
 * Streaming, XXE-hardened SAX parser for Bilibili-format sidecar danmaku XML files
 * (`<d p="timeSec,mode,fontSize,decColor,...">text</d>`) for local and WebDAV video sources
 * (docs/specs/07-palette-danmaku-screen.md G4).
 *
 * Hardening guarantees:
 * - Hard byte limit ([MAX_XML_BYTES] = 8 MiB) enforced via bounded stream wrapper.
 * - Hard item cap ([MAX_XML_ITEMS] = 50,000) stops streaming immediately on reaching the cap.
 * - Rejects `<!DOCTYPE` / `<!ENTITY` declarations via both pre-scan and SAX disallow-doctype
 *   features (zero external entity / billion-laughs exposure).
 * - Malformed `<d>` attributes or truncated XML skip bad entries without crashing or blocking playback.
 * - Uses standard `javax.xml.parsers.SAXParserFactory` so it runs identically on Android and pure JVM.
 */
object DanmakuXmlParser {
    const val MAX_XML_BYTES = 8 * 1024 * 1024
    const val MAX_XML_ITEMS = 50_000
    private const val DOCTYPE_SCAN_BYTES = 4096

    private class CapReachedException : RuntimeException()

    @JvmStatic
    @JvmOverloads
    fun parse(
        input: InputStream,
        maxBytes: Int = MAX_XML_BYTES,
        maxItems: Int = MAX_XML_ITEMS,
    ): List<DanmakuItem> {
        if (maxBytes <= 0 || maxItems <= 0) return emptyList()
        val buffered = BufferedInputStream(BoundedInputStream(input, maxBytes), 8192)
        buffered.mark(DOCTYPE_SCAN_BYTES)
        val headerBuf = ByteArray(DOCTYPE_SCAN_BYTES)
        val headerRead = readUpTo(buffered, headerBuf)
        if (headerRead <= 0) return emptyList()
        val headerUpper = String(headerBuf, 0, headerRead, StandardCharsets.UTF_8).uppercase(Locale.US)
        if (headerUpper.contains("<!DOCTYPE") || headerUpper.contains("<!ENTITY")) {
            // Strictly refuse external DTD / entity declarations (XXE & billion-laughs defense)
            return emptyList()
        }
        buffered.reset()

        val result = ArrayList<DanmakuItem>(256)
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            val parser = factory.newSAXParser()

            val handler = object : DefaultHandler() {
                private var inDTag = false
                private var currentPAttr: String? = null
                private val textBuf = StringBuilder(64)

                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes?,
                ) {
                    val tag = qName ?: localName
                    if (tag == "d") {
                        inDTag = true
                        currentPAttr = attributes?.getValue("p")
                        textBuf.setLength(0)
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inDTag && textBuf.length < 512) {
                        val take = minOf(length, 512 - textBuf.length)
                        textBuf.append(ch, start, take)
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val tag = qName ?: localName
                    if (tag == "d" && inDTag) {
                        val pAttr = currentPAttr
                        val rawText = textBuf.toString()
                        inDTag = false
                        currentPAttr = null
                        textBuf.setLength(0)
                        if (!pAttr.isNullOrBlank() && rawText.isNotBlank()) {
                            parseEntry(pAttr, rawText)?.let {
                                result.add(it)
                                if (result.size >= maxItems) {
                                    throw CapReachedException()
                                }
                            }
                        }
                    }
                }
            }

            val source = InputSource(InputStreamReader(buffered, StandardCharsets.UTF_8))
            parser.parse(source, handler)
        } catch (_: CapReachedException) {
            // Reached maxItems cap cleanly
        } catch (_: Exception) {
            // Truncated or malformed trailing XML: return all valid entries parsed so far
        }

        if (result.size > 1) {
            result.sortBy { it.timeMs }
        }
        return result
    }

    internal fun parseEntry(pAttr: String, rawText: String): DanmakuItem? {
        val parts = pAttr.split(',')
        if (parts.size < 4) return null
        val timeSec = parts[0].trim().toDoubleOrNull() ?: return null
        if (!timeSec.isFinite() || timeSec < 0.0 || timeSec > 86_400.0 * 7.0) return null
        val modeCode = parts[1].trim().toIntOrNull() ?: return null
        val mode = when (modeCode) {
            1, 2, 3, 6 -> DanmakuMode.SCROLL
            5 -> DanmakuMode.TOP
            4 -> DanmakuMode.BOTTOM
            else -> return null // Skip special/script/BAS modes
        }
        val decColor = parts[3].trim().toLongOrNull()
        val rgb = if (decColor != null && decColor in 0L..0xFFFFFFL) {
            decColor.toInt()
        } else {
            0xFFFFFF
        }
        val sanitized = DanmakuDepthMath.sanitizeText(rawText)
        if (sanitized.isEmpty()) return null
        val hexColor = String.format(Locale.US, "#%06X", rgb and 0xFFFFFF)
        return DanmakuItem(
            timeMs = (timeSec * 1000.0).toLong().coerceAtLeast(0L),
            text = sanitized,
            color = hexColor,
            mode = mode,
        )
    }

    /**
     * Derives the sidecar `.xml` URI/path beside a local or WebDAV video URI (preserving query
     * parameters if present, replacing only the media file extension with `.xml`).
     */
    @JvmStatic
    fun sidecarXmlUrlFor(videoUrl: String?): String? {
        if (videoUrl.isNullOrBlank()) return null
        val qIdx = videoUrl.indexOf('?')
        val base = if (qIdx >= 0) videoUrl.substring(0, qIdx) else videoUrl
        val query = if (qIdx >= 0) videoUrl.substring(qIdx) else ""
        val slash = base.lastIndexOf('/')
        val dot = base.lastIndexOf('.')
        if (dot <= slash || dot <= 0) return null
        val ext = base.substring(dot + 1).lowercase(Locale.US)
        if (ext == "xml" || ext.isEmpty() || ext.length > 6) return null
        return base.substring(0, dot) + ".xml" + query
    }

    private fun readUpTo(stream: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = stream.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private val limitBytes: Int,
    ) : InputStream() {
        private var readCount = 0

        override fun read(): Int {
            if (readCount >= limitBytes) return -1
            val b = delegate.read()
            if (b >= 0) readCount++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val remaining = limitBytes - readCount
            if (remaining <= 0) return -1
            val n = delegate.read(b, off, minOf(len, remaining))
            if (n > 0) readCount += n
            return n
        }
    }
}
