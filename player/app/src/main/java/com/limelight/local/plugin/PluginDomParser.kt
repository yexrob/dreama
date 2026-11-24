package com.limelight.local.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DomLimitExceededException(message: String) : IllegalStateException(message)

/**
 * Lazy handle-backed HTML/XML DOM parser for `ctx.parse.html(text)` and
 * `ctx.parse.xml(text)` (§5, §11.1, S4):
 * - Capped at 8 parsed documents per invocation and 4 MB per document
 * - `selectAllIds` / `selectFirstId` / `childrenIds` return ONLY integer handle
 *   IDs without eagerly serializing `html`/`outerHtml`/`text` so large pages
 *   (e.g. 500 KB+ with thousands of `<div>` nodes) never exhaust QuickJS heap
 * - All handles and counters are cleared as soon as the invocation ends
 */
class PluginDomParser(
    private val maxDocsPerCall: Int = MAX_DOCS_PER_CALL,
    private val maxDocBytes: Int = MAX_DOC_BYTES,
) {
    private val nextId = AtomicInteger(1)
    private val docCount = AtomicInteger(0)
    private val nodes = ConcurrentHashMap<Int, Element>()

    fun parseHtml(html: String): Int {
        enforceDocLimits(html)
        val doc = Jsoup.parse(html)
        return register(doc)
    }

    fun parseXml(xml: String): Int {
        enforceDocLimits(xml)
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return register(doc)
    }

    fun selectFirstId(nodeId: Int, selector: String): Int {
        val el = nodes[nodeId] ?: return 0
        val found = el.selectFirst(selector) ?: return 0
        return register(found)
    }

    fun selectAllIds(nodeId: Int, selector: String): JsonArray {
        val el = nodes[nodeId] ?: return JsonArray(emptyList())
        val found = el.select(selector)
        val ids = ArrayList<JsonPrimitive>(found.size)
        for (child in found) {
            ids += JsonPrimitive(register(child))
        }
        return JsonArray(ids)
    }

    fun childrenIds(nodeId: Int): JsonArray {
        val el = nodes[nodeId] ?: return JsonArray(emptyList())
        val children = el.children()
        val ids = ArrayList<JsonPrimitive>(children.size)
        for (child in children) {
            ids += JsonPrimitive(register(child))
        }
        return JsonArray(ids)
    }

    fun tag(nodeId: Int): String = nodes[nodeId]?.tagName() ?: ""
    fun text(nodeId: Int): String = nodes[nodeId]?.text() ?: ""
    fun html(nodeId: Int): String = nodes[nodeId]?.html() ?: ""
    fun outerHtml(nodeId: Int): String = nodes[nodeId]?.outerHtml() ?: ""

    fun attr(nodeId: Int, name: String): String? {
        val el = nodes[nodeId] ?: return null
        return if (el.hasAttr(name)) el.attr(name) else null
    }

    fun attrsJson(nodeId: Int): String {
        val el = nodes[nodeId] ?: return "{}"
        return buildJsonObject {
            for (a in el.attributes()) {
                put(a.key, a.value)
            }
        }.toString()
    }

    fun clear() {
        nodes.clear()
        docCount.set(0)
    }

    private fun enforceDocLimits(text: String) {
        val byteLen = text.toByteArray(Charsets.UTF_8).size
        if (byteLen > maxDocBytes) {
            throw DomLimitExceededException(
                "待解析文档超过大小上限（${maxDocBytes / (1024 * 1024)} MB）",
            )
        }
        if (docCount.incrementAndGet() > maxDocsPerCall) {
            docCount.decrementAndGet()
            throw DomLimitExceededException("单次调用最多解析 $maxDocsPerCall 个文档")
        }
    }

    private fun register(el: Element): Int {
        val id = nextId.getAndIncrement()
        nodes[id] = el
        return id
    }

    companion object {
        const val MAX_DOCS_PER_CALL = 8
        const val MAX_DOC_BYTES = 4 * 1024 * 1024
    }
}
