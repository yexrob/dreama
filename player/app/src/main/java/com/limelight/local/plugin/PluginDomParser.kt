package com.limelight.local.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DomLimitExceededException(message: String) : IllegalStateException(message)

/**
 * Lazy handle-backed HTML/XML DOM parser for `ctx.parse.html(text)` and
 * `ctx.parse.xml(text)` (§5, §11.1, S4):
 * - Capped at 8 parsed documents per invocation, 4 MB per document, and
 *   [MAX_TOTAL_NODES] (200,000) total element nodes across the invocation
 * - Unified semantics with `tools/plugin-kit/src/dom.js`:
 *   - Document root always reports `tag == "#document"`
 *   - HTML mode lowercases tag names; XML mode preserves exact tag & attribute case
 *   - `text` collapses whitespace runs to a single space and trims
 *   - `attr(name)` returns `null` when absent and `""` for present boolean attributes
 * - All handles and counters are cleared as soon as the invocation ends
 */
class PluginDomParser(
    private val maxDocsPerCall: Int = MAX_DOCS_PER_CALL,
    private val maxDocBytes: Int = MAX_DOC_BYTES,
    private val maxTotalNodes: Int = MAX_TOTAL_NODES,
) {
    private data class NodeEntry(
        val el: Element,
        val isXml: Boolean,
    )

    private val nextId = AtomicInteger(1)
    private val docCount = AtomicInteger(0)
    private val totalNodes = AtomicInteger(0)
    private val nodes = ConcurrentHashMap<Int, NodeEntry>()

    fun parseHtml(html: String): Int {
        enforcePreParseLimits(html)
        val doc = Jsoup.parse(html)
        val count = countElementsWithinLimit(doc)
        docCount.incrementAndGet()
        totalNodes.addAndGet(count)
        return register(doc, isXml = false)
    }

    fun parseXml(xml: String): Int {
        enforcePreParseLimits(xml)
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val count = countElementsWithinLimit(doc)
        docCount.incrementAndGet()
        totalNodes.addAndGet(count)
        return register(doc, isXml = true)
    }

    fun selectFirstId(nodeId: Int, selector: String): Int {
        val entry = nodes[nodeId] ?: return 0
        val found = entry.el.selectFirst(selector) ?: return 0
        return register(found, entry.isXml)
    }

    fun selectAllIds(nodeId: Int, selector: String): JsonArray {
        val entry = nodes[nodeId] ?: return JsonArray(emptyList())
        val found = entry.el.select(selector)
        val ids = ArrayList<JsonPrimitive>(found.size)
        for (child in found) {
            ids += JsonPrimitive(register(child, entry.isXml))
        }
        return JsonArray(ids)
    }

    fun childrenIds(nodeId: Int): JsonArray {
        val entry = nodes[nodeId] ?: return JsonArray(emptyList())
        val children = entry.el.children()
        val ids = ArrayList<JsonPrimitive>(children.size)
        for (child in children) {
            ids += JsonPrimitive(register(child, entry.isXml))
        }
        return JsonArray(ids)
    }

    fun tag(nodeId: Int): String {
        val entry = nodes[nodeId] ?: return ""
        if (entry.el is Document) return "#document"
        val raw = entry.el.tagName()
        return if (entry.isXml) raw else raw.lowercase(Locale.ROOT)
    }

    fun text(nodeId: Int): String =
        nodes[nodeId]?.el?.text()?.replace(WHITESPACE_REGEX, " ")?.trim() ?: ""
    fun html(nodeId: Int): String = nodes[nodeId]?.el?.html() ?: ""
    fun outerHtml(nodeId: Int): String = nodes[nodeId]?.el?.outerHtml() ?: ""

    fun attr(nodeId: Int, name: String): String? {
        val entry = nodes[nodeId] ?: return null
        if (entry.el is Document) return null
        return if (entry.el.hasAttr(name)) entry.el.attr(name) else null
    }

    fun attrsJson(nodeId: Int): String {
        val entry = nodes[nodeId] ?: return "{}"
        if (entry.el is Document) return "{}"
        return buildJsonObject {
            for (a in entry.el.attributes()) {
                put(a.key, a.value)
            }
        }.toString()
    }

    fun clear() {
        nodes.clear()
        docCount.set(0)
        totalNodes.set(0)
    }

    fun currentTotalNodes(): Int = totalNodes.get()

    private fun enforcePreParseLimits(text: String) {
        val byteLen = text.toByteArray(Charsets.UTF_8).size
        if (byteLen > maxDocBytes) {
            throw DomLimitExceededException(
                "待解析文档超过大小上限（${maxDocBytes / (1024 * 1024)} MB）",
            )
        }
        if (docCount.get() >= maxDocsPerCall) {
            throw DomLimitExceededException("单次调用最多解析 $maxDocsPerCall 个文档")
        }
        val remainingBudget = maxTotalNodes - totalNodes.get()
        if (remainingBudget <= 0) {
            throw DomLimitExceededException("单次调用解析的 DOM 节点总数超过上限（最多 $maxTotalNodes 个）")
        }
        // O(n) zero-allocation pre-scan: count element-opening '<' tokens (excluding '</', '<!', '<?')
        // so hostile payloads like 4 MB of "<a></a>" are rejected BEFORE building a huge Jsoup tree.
        var openTags = 0
        val len = text.length
        var i = 0
        while (i < len - 1) {
            if (text[i] == '<') {
                val next = text[i + 1]
                if (next != '/' && next != '!' && next != '?' && !next.isWhitespace()) {
                    openTags += 1
                    if (openTags > remainingBudget) {
                        throw DomLimitExceededException(
                            "单次调用解析的 DOM 节点总数超过上限（最多 $maxTotalNodes 个）",
                        )
                    }
                }
            }
            i += 1
        }
    }

    private fun countElementsWithinLimit(root: Document): Int {
        val remaining = maxTotalNodes - totalNodes.get()
        var count = 0
        val stack = ArrayDeque<Element>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            count += 1
            if (count > remaining) {
                throw DomLimitExceededException(
                    "单次调用解析的 DOM 节点总数超过上限（最多 $maxTotalNodes 个）",
                )
            }
            for (child in cur.children()) {
                stack.addLast(child)
            }
        }
        return count
    }

    private fun register(el: Element, isXml: Boolean): Int {
        val id = nextId.getAndIncrement()
        nodes[id] = NodeEntry(el, isXml)
        return id
    }

    companion object {
        const val MAX_DOCS_PER_CALL = 8
        const val MAX_DOC_BYTES = 4 * 1024 * 1024
        const val MAX_TOTAL_NODES = 200_000
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
