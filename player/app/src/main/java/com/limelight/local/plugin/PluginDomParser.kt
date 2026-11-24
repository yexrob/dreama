package com.limelight.local.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.ParseSettings
import org.jsoup.parser.Parser
import org.jsoup.parser.StreamParser
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class DomLimitExceededException(message: String) : IllegalStateException(message)
class DomTimeoutException(message: String) : IllegalStateException(message)

/**
 * Lazy handle-backed HTML/XML DOM parser for `ctx.parse.html(text)` and
 * `ctx.parse.xml(text)` (§5, §11.1, S4):
 * - Capped at [MAX_LIVE_DOCS] (8) simultaneously live documents per VM, 4 MB per
 *   document, [MAX_DOC_NODES] (200,000) elements per document, and
 *   [MAX_LIVE_NODES] (400,000) total live elements across live documents
 *   (excluding `#document`); supports explicit `doc.close()` and automatic
 *   cleanup via [clear] when each plugin call finishes
 * - Per-document deduplicated node handle registry (the same [Element] always
 *   reuses the same handle ID; per-doc handle cap equals `elementCount + 1`)
 * - Counts elements DURING parsing via Jsoup [StreamParser] to abort HTML5
 *   amplification bombs immediately, then performs an exact post-parse element
 *   count so adoption-agency clones never bypass the node ceiling
 * - Uses an O(N * S) memoized CSS selector engine with hard syntax caps (4 KB
 *   text, 32 groups, 32 compounds, nesting depth 4, value length 256, `:nth-*`
 *   in `[-1_000_000, 1_000_000]`), lazy on-demand memo allocation under a 16 MB
 *   per-call memory budget, per-element cached `class` token sets, linear KMP
 *   budgeted attribute comparisons, a 2M step budget, and deadline/interrupt checks
 * - Iterative (O(1) call stack) canonical `text()` / `html()` / `outerHtml()`
 *   serialization aligned with `tools/plugin-kit/src/dom.js` (`parse5` / `linkedom`)
 */
class PluginDomParser(
    private val maxDocsPerCall: Int = MAX_LIVE_DOCS,
    private val maxDocBytes: Int = MAX_DOC_BYTES,
    private val maxTotalNodes: Int = MAX_DOC_NODES,
    private val maxSelectorSteps: Int = MAX_SELECTOR_STEPS,
    private val maxLiveNodes: Int = MAX_LIVE_NODES,
    private val maxSelectorMemoryBytes: Int = MAX_SELECTOR_MEMORY_BYTES,
) {
    private class DocEntry(
        val docId: Int,
        val doc: Document,
        val isXml: Boolean,
        val elementCount: Int,
        val elementToHandleId: IdentityHashMap<Element, Int>,
        val handleIds: ArrayList<Int>,
    )

    private data class NodeEntry(
        val docId: Int,
        val el: Element,
        val isXml: Boolean,
    )

    private val registryLock = Any()
    private val nextId = AtomicInteger(1)
    private val docs = HashMap<Int, DocEntry>()
    private val nodes = HashMap<Int, NodeEntry>()
    private var liveNodes: Int = 0

    @Volatile
    private var callDeadlineNanos: Long = Long.MAX_VALUE

    fun setCallDeadlineNanos(deadlineNanos: Long) {
        callDeadlineNanos = deadlineNanos
    }

    fun parseHtml(html: String): Int {
        val remaining = enforcePreParseLimits(html)
        val parser = Parser.htmlParser()
            .settings(ParseSettings.htmlDefault)
            .setMaxDepth(MAX_NESTING_DEPTH)
        val (doc, count) = parseWithStreamingAndExactCount(html, parser, remaining)
        doc.outputSettings().prettyPrint(false)
        return registerDocument(doc, isXml = false, elementCount = count)
    }

    fun parseXml(xml: String): Int {
        val remaining = enforcePreParseLimits(xml)
        val sanitized = escapeUndefinedXmlEntities(xml)
        val parser = Parser.xmlParser()
            .settings(ParseSettings.preserveCase)
            .setMaxDepth(MAX_NESTING_DEPTH)
        val (doc, count) = parseWithStreamingAndExactCount(sanitized, parser, remaining)
        doc.outputSettings().prettyPrint(false)
        return registerDocument(doc, isXml = true, elementCount = count)
    }

    fun closeDoc(docId: Int): Boolean = synchronized(registryLock) {
        val docEntry = docs.remove(docId) ?: return false
        for (hid in docEntry.handleIds) {
            nodes.remove(hid)
        }
        docEntry.handleIds.clear()
        docEntry.elementToHandleId.clear()
        liveNodes = (liveNodes - docEntry.elementCount).coerceAtLeast(0)
        true
    }

    fun selectFirstId(nodeId: Int, selector: String): Int {
        val entry = lookupNode(nodeId) ?: return 0
        val parsed = CssSelectorEngine.parse(selector)
        val found = CssSelectorEngine.queryFirst(
            scopeRoot = entry.el,
            selectors = parsed,
            isXml = entry.isXml,
            maxSteps = maxSelectorSteps,
            maxMemoryBytes = maxSelectorMemoryBytes,
            deadlineNanos = callDeadlineNanos,
        ) ?: return 0
        return registerElement(entry.docId, found)
    }

    fun selectAllIds(nodeId: Int, selector: String): JsonArray {
        val entry = lookupNode(nodeId) ?: return JsonArray(emptyList())
        val parsed = CssSelectorEngine.parse(selector)
        val found = CssSelectorEngine.queryAll(
            scopeRoot = entry.el,
            selectors = parsed,
            isXml = entry.isXml,
            maxSteps = maxSelectorSteps,
            maxMemoryBytes = maxSelectorMemoryBytes,
            deadlineNanos = callDeadlineNanos,
        )
        val ids = ArrayList<JsonPrimitive>(found.size)
        synchronized(registryLock) {
            val docEntry = docs[entry.docId] ?: return JsonArray(emptyList())
            for (child in found) {
                ids += JsonPrimitive(registerElementLocked(docEntry, child))
            }
        }
        return JsonArray(ids)
    }

    fun childrenIds(nodeId: Int): JsonArray {
        val entry = lookupNode(nodeId) ?: return JsonArray(emptyList())
        val children = entry.el.children()
        val ids = ArrayList<JsonPrimitive>(children.size)
        synchronized(registryLock) {
            val docEntry = docs[entry.docId] ?: return JsonArray(emptyList())
            for (child in children) {
                ids += JsonPrimitive(registerElementLocked(docEntry, child))
            }
        }
        return JsonArray(ids)
    }

    fun tag(nodeId: Int): String {
        val entry = lookupNode(nodeId) ?: return ""
        if (entry.el is Document) return "#document"
        val raw = entry.el.tagName()
        return if (entry.isXml) raw else raw.lowercase(Locale.ROOT)
    }

    fun text(nodeId: Int): String {
        val entry = lookupNode(nodeId) ?: return ""
        val sb = StringBuilder()
        val deadline = callDeadlineNanos
        if (entry.isXml) {
            collectXmlTextIterative(entry.el, sb, deadline)
        } else {
            collectHtmlTextIterative(entry.el, sb, deadline)
        }
        return collapseAsciiAndNbspWhitespace(sb.toString())
    }

    fun html(nodeId: Int): String {
        val entry = lookupNode(nodeId) ?: return ""
        val sb = StringBuilder()
        serializeChildrenIterative(entry.el, entry.isXml, sb, callDeadlineNanos)
        return sb.toString()
    }

    fun outerHtml(nodeId: Int): String {
        val entry = lookupNode(nodeId) ?: return ""
        val sb = StringBuilder()
        val deadline = callDeadlineNanos
        if (entry.el is Document) {
            serializeChildrenIterative(entry.el, entry.isXml, sb, deadline)
        } else {
            serializeNodeIterative(entry.el, entry.isXml, sb, deadline)
        }
        return sb.toString()
    }

    fun attr(nodeId: Int, name: String): String? {
        val entry = lookupNode(nodeId) ?: return null
        if (entry.el is Document) return null
        return getElementAttr(entry.el, name, entry.isXml)
    }

    fun attrsJson(nodeId: Int): String {
        val entry = lookupNode(nodeId) ?: return "{}"
        if (entry.el is Document) return "{}"
        return buildJsonObject {
            for (a in entry.el.attributes()) {
                val key = if (entry.isXml) a.key else a.key.lowercase(Locale.ROOT)
                put(key, a.value)
            }
        }.toString()
    }

    fun clear() {
        synchronized(registryLock) {
            nodes.clear()
            docs.clear()
            liveNodes = 0
        }
        callDeadlineNanos = Long.MAX_VALUE
    }

    fun currentTotalNodes(): Int = synchronized(registryLock) { liveNodes }

    fun currentLiveDocCount(): Int = synchronized(registryLock) { docs.size }

    fun currentHandleCount(): Int = synchronized(registryLock) { nodes.size }

    private fun lookupNode(nodeId: Int): NodeEntry? = synchronized(registryLock) { nodes[nodeId] }

    private fun registerDocument(doc: Document, isXml: Boolean, elementCount: Int): Int = synchronized(registryLock) {
        if (docs.size >= maxDocsPerCall) {
            throw DomLimitExceededException("同时存活的文档数超过上限（最多 $maxDocsPerCall 个）")
        }
        if (elementCount > maxTotalNodes) {
            throw DomLimitExceededException("单个文档解析的 DOM 节点总数超过上限（最多 $maxTotalNodes 个）")
        }
        if (liveNodes + elementCount > maxLiveNodes) {
            throw DomLimitExceededException("同时存活的 DOM 节点总数超过上限（最多 $maxLiveNodes 个）")
        }
        val docId = nextId.getAndIncrement()
        val initialCap = minOf(elementCount + 1, 1024).coerceAtLeast(16)
        val map = IdentityHashMap<Element, Int>(initialCap)
        val ids = ArrayList<Int>(initialCap)
        map[doc] = docId
        ids.add(docId)
        docs[docId] = DocEntry(
            docId = docId,
            doc = doc,
            isXml = isXml,
            elementCount = elementCount,
            elementToHandleId = map,
            handleIds = ids,
        )
        nodes[docId] = NodeEntry(docId, doc, isXml)
        liveNodes += elementCount
        docId
    }

    private fun registerElement(docId: Int, el: Element): Int = synchronized(registryLock) {
        val docEntry = docs[docId] ?: return 0
        registerElementLocked(docEntry, el)
    }

    private fun registerElementLocked(docEntry: DocEntry, el: Element): Int {
        docEntry.elementToHandleId[el]?.let { return it }
        val maxHandles = docEntry.elementCount + 1
        if (docEntry.elementToHandleId.size >= maxHandles) {
            throw DomLimitExceededException("单个文档句柄数超过上限（最多 $maxHandles 个）")
        }
        val id = nextId.getAndIncrement()
        docEntry.elementToHandleId[el] = id
        docEntry.handleIds.add(id)
        nodes[id] = NodeEntry(docEntry.docId, el, docEntry.isXml)
        return id
    }

    private fun enforcePreParseLimits(text: String): Int {
        val byteLen = text.toByteArray(Charsets.UTF_8).size
        if (byteLen > maxDocBytes) {
            throw DomLimitExceededException(
                "待解析文档超过大小上限（${maxDocBytes / (1024 * 1024)} MB）",
            )
        }
        val remainingBudget = synchronized(registryLock) {
            if (docs.size >= maxDocsPerCall) {
                throw DomLimitExceededException("同时存活的文档数超过上限（最多 $maxDocsPerCall 个）")
            }
            val liveRem = maxLiveNodes - liveNodes
            if (liveRem <= 0) {
                throw DomLimitExceededException("同时存活的 DOM 节点总数超过上限（最多 $maxLiveNodes 个）")
            }
            minOf(maxTotalNodes, liveRem)
        }
        // Fast O(n) zero-allocation pre-scan before invoking Jsoup
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
                            "解析的 DOM 节点总数超过上限（单文档最多 $maxTotalNodes 个，总计最多 $maxLiveNodes 个）",
                        )
                    }
                }
            }
            i += 1
        }
        return remainingBudget
    }

    /**
     * Step 1: Parses [input] incrementally via Jsoup [StreamParser], aborting
     * immediately if emitted element count crosses [remaining] (blocking OOM
     * amplification bombs during parsing).
     * Step 2: Performs an exact iterative DFS count over all `Element` nodes
     * (excluding `#document` itself, matching `tools/plugin-kit/src/dom.js`)
     * so adoption-agency clones that were not re-emitted still respect the cap.
     */
    private fun parseWithStreamingAndExactCount(input: String, parser: Parser, remaining: Int): Pair<Document, Int> {
        if (remaining <= 0) {
            throw DomLimitExceededException("同时存活的 DOM 节点总数超过上限（最多 $maxLiveNodes 个）")
        }
        val deadline = callDeadlineNanos
        val doc = StreamParser(parser).use { sp ->
            sp.parse(input, "")
            var streamed = 0
            val it = sp.iterator()
            while (it.hasNext()) {
                it.next()
                streamed += 1
                if ((streamed and 1023) == 0) checkDeadline(deadline)
                // StreamParser may emit `#document` itself (+1 tolerance); exact recount runs below
                if (streamed > remaining + 1) {
                    sp.stop()
                    throw DomLimitExceededException(
                        "解析的 DOM 节点总数超过上限（单文档最多 $maxTotalNodes 个，总计最多 $maxLiveNodes 个）",
                    )
                }
            }
            sp.document()
        }
        val actualCount = countElementsStrict(doc, remaining, deadline)
        return doc to actualCount
    }

    private fun countElementsStrict(doc: Document, remaining: Int, deadline: Long): Int {
        var count = 0
        val stack = ArrayDeque<Element>()
        val top = doc.children()
        for (i in top.size - 1 downTo 0) {
            stack.addLast(top[i])
        }
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            count += 1
            if ((count and 1023) == 0) checkDeadline(deadline)
            if (count > remaining) {
                throw DomLimitExceededException(
                    "解析的 DOM 节点总数超过上限（单文档最多 $maxTotalNodes 个，总计最多 $maxLiveNodes 个）",
                )
            }
            val ch = cur.children()
            for (i in ch.size - 1 downTo 0) {
                stack.addLast(ch[i])
            }
        }
        return count
    }

    companion object {
        const val MAX_LIVE_DOCS = 8
        const val MAX_DOCS_PER_CALL = MAX_LIVE_DOCS
        const val MAX_DOC_BYTES = 4 * 1024 * 1024
        const val MAX_DOC_NODES = 200_000
        const val MAX_TOTAL_NODES = MAX_DOC_NODES
        const val MAX_LIVE_NODES = 400_000
        const val MAX_SELECTOR_STEPS = 2_000_000
        const val MAX_SELECTOR_MEMORY_BYTES = 16 * 1024 * 1024
        const val MAX_SELECTOR_BYTES = 4 * 1024
        const val MAX_SELECTOR_GROUPS = 32
        const val MAX_COMPOUNDS_PER_SELECTOR = 32
        const val MAX_PSEUDO_NESTING = 4
        const val MAX_SELECTOR_VALUE_LENGTH = 256
        const val MAX_NTH_ABS = 1_000_000
        private const val MAX_NESTING_DEPTH = 512

        private val XML_BUILTIN_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
        private val HTML_SKIP_TEXT_TAGS = setOf("script", "style")
        private val HTML_RAW_TEXT_TAGS = setOf("script", "style", "xmp", "iframe", "noembed", "noframes", "plaintext")
        private val HTML_VOID_TAGS = setOf(
            "area", "base", "basefont", "bgsound", "br", "col", "embed", "frame",
            "hr", "img", "input", "keygen", "link", "meta", "param", "source", "track", "wbr",
        )
        private val HTML_BLOCK_TAGS = setOf(
            "address", "article", "aside", "blockquote", "body", "br", "caption", "center",
            "col", "colgroup", "dd", "details", "dialog", "dir", "div", "dl", "dt",
            "fieldset", "figcaption", "figure", "footer", "form", "frame", "frameset",
            "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup", "hr", "html",
            "li", "main", "menu", "nav", "noframes", "noscript", "ol", "optgroup", "option",
            "p", "pre", "section", "select", "table", "tbody", "td", "tfoot", "th",
            "thead", "title", "tr", "ul", "video",
        )

        private data class RawTextChunk(val text: String)

        internal fun checkDeadline(deadlineNanos: Long) {
            if (Thread.currentThread().isInterrupted) {
                throw DomTimeoutException("DOM 操作已中断")
            }
            if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() > deadlineNanos) {
                throw DomTimeoutException("DOM 操作执行超时")
            }
        }

        internal fun getElementAttr(el: Element, name: String, isXml: Boolean): String? {
            if (el is Document) return null
            if (isXml) {
                for (a in el.attributes()) {
                    if (a.key == name) return a.value
                }
                return null
            }
            val lower = name.lowercase(Locale.ROOT)
            for (a in el.attributes()) {
                if (a.key.lowercase(Locale.ROOT) == lower) return a.value
            }
            return null
        }

        private fun serializeChildrenIterative(el: Element, isXml: Boolean, out: StringBuilder, deadlineNanos: Long) {
            val stack = ArrayDeque<Any>()
            pushSerializedChildren(el, isXml, stack)
            drainSerializeStack(stack, isXml, out, deadlineNanos)
        }

        private fun serializeNodeIterative(node: Node, isXml: Boolean, out: StringBuilder, deadlineNanos: Long) {
            val stack = ArrayDeque<Any>()
            stack.addLast(node)
            drainSerializeStack(stack, isXml, out, deadlineNanos)
        }

        private fun pushSerializedChildren(el: Element, isXml: Boolean, stack: ArrayDeque<Any>) {
            val rawTextParent = !isXml && el !is Document && el.tagName().lowercase(Locale.ROOT) in HTML_RAW_TEXT_TAGS
            val children = el.childNodes()
            for (i in children.size - 1 downTo 0) {
                val child = children[i]
                if (rawTextParent && (child is TextNode || child is DataNode)) {
                    val raw = if (child is DataNode) child.wholeData else (child as TextNode).wholeText
                    stack.addLast(RawTextChunk(raw))
                } else {
                    stack.addLast(child)
                }
            }
        }

        private fun drainSerializeStack(stack: ArrayDeque<Any>, isXml: Boolean, out: StringBuilder, deadlineNanos: Long) {
            var ops = 0
            while (stack.isNotEmpty()) {
                if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                when (val item = stack.removeLast()) {
                    is String -> out.append(item)
                    is RawTextChunk -> out.append(item.text)
                    is CDataNode -> {
                        if (isXml) {
                            out.append("<![CDATA[").append(item.text()).append("]]>")
                        } else {
                            appendHtmlText(item.text(), out)
                        }
                    }
                    is TextNode -> {
                        if (isXml) appendXmlText(item.wholeText, out)
                        else appendHtmlText(item.wholeText, out)
                    }
                    is DataNode -> out.append(item.wholeData)
                    is Comment -> out.append("<!--").append(item.data).append("-->")
                    is Element -> {
                        val tag = if (isXml) item.tagName() else item.tagName().lowercase(Locale.ROOT)
                        out.append('<').append(tag)
                        for (a in item.attributes()) {
                            val key = if (isXml) a.key else a.key.lowercase(Locale.ROOT)
                            val v = a.value
                            if (!isXml && v.isEmpty()) {
                                out.append(' ').append(key)
                            } else {
                                out.append(' ').append(key).append("=\"")
                                if (isXml) appendXmlAttr(v, out) else appendHtmlAttr(v, out)
                                out.append('"')
                            }
                        }
                        if (isXml) {
                            if (item.childNodeSize() == 0) {
                                out.append("/>")
                            } else {
                                out.append('>')
                                stack.addLast("</$tag>")
                                pushSerializedChildren(item, true, stack)
                            }
                        } else {
                            out.append('>')
                            if (tag !in HTML_VOID_TAGS) {
                                stack.addLast("</$tag>")
                                pushSerializedChildren(item, false, stack)
                            }
                        }
                    }
                }
            }
        }

        private fun appendHtmlText(s: String, out: StringBuilder) {
            for (c in s) {
                when (c) {
                    '&' -> out.append("&amp;")
                    '\u00A0' -> out.append("&nbsp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    else -> out.append(c)
                }
            }
        }

        private fun appendHtmlAttr(s: String, out: StringBuilder) {
            for (c in s) {
                when (c) {
                    '&' -> out.append("&amp;")
                    '\u00A0' -> out.append("&nbsp;")
                    '"' -> out.append("&quot;")
                    else -> out.append(c)
                }
            }
        }

        private fun appendXmlText(s: String, out: StringBuilder) {
            for (c in s) {
                when (c) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    else -> out.append(c)
                }
            }
        }

        private fun appendXmlAttr(s: String, out: StringBuilder) {
            for (c in s) {
                when (c) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    '"' -> out.append("&quot;")
                    else -> out.append(c)
                }
            }
        }

        private fun collectXmlTextIterative(root: Node, out: StringBuilder, deadlineNanos: Long) {
            val stack = ArrayDeque<Node>()
            stack.addLast(root)
            var ops = 0
            while (stack.isNotEmpty()) {
                if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                when (val node = stack.removeLast()) {
                    is CDataNode -> out.append(node.text())
                    is TextNode -> out.append(node.wholeText)
                    is Element -> {
                        val children = node.childNodes()
                        for (i in children.size - 1 downTo 0) {
                            stack.addLast(children[i])
                        }
                    }
                }
            }
        }

        private fun collectHtmlTextIterative(root: Node, out: StringBuilder, deadlineNanos: Long) {
            val stack = ArrayDeque<Any>()
            stack.addLast(root)
            var ops = 0
            while (stack.isNotEmpty()) {
                if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                when (val item = stack.removeLast()) {
                    is Char -> out.append(item)
                    is CDataNode -> out.append(item.text())
                    is TextNode -> out.append(item.wholeText)
                    is Document -> {
                        val children = item.childNodes()
                        for (i in children.size - 1 downTo 0) {
                            stack.addLast(children[i])
                        }
                    }
                    is Element -> {
                        val tag = item.tagName().lowercase(Locale.ROOT)
                        if (tag in HTML_SKIP_TEXT_TAGS) continue
                        val isBlock = tag in HTML_BLOCK_TAGS
                        if (isBlock) {
                            out.append(' ')
                            stack.addLast(' ')
                        }
                        val children = item.childNodes()
                        for (i in children.size - 1 downTo 0) {
                            stack.addLast(children[i])
                        }
                    }
                }
            }
        }

        internal fun collapseAsciiAndNbspWhitespace(raw: String): String {
            val sb = StringBuilder(raw.length)
            var inSpace = true
            for (ch in raw) {
                val isSpace = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000C' || ch == '\u00A0'
                if (isSpace) {
                    if (!inSpace) {
                        sb.append(' ')
                        inSpace = true
                    }
                } else {
                    sb.append(ch)
                    inSpace = false
                }
            }
            if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
                sb.setLength(sb.length - 1)
            }
            return sb.toString()
        }

        internal fun escapeUndefinedXmlEntities(xml: String): String {
            if (!xml.contains('&')) return xml
            val len = xml.length
            val out = StringBuilder(len + 32)
            var i = 0
            while (i < len) {
                if (xml[i] == '<') {
                    if (xml.startsWith("<![CDATA[", i)) {
                        val end = xml.indexOf("]]>", i + 9)
                        val stop = if (end >= 0) end + 3 else len
                        out.append(xml, i, stop)
                        i = stop
                        continue
                    }
                    if (xml.startsWith("<!--", i)) {
                        val end = xml.indexOf("-->", i + 4)
                        val stop = if (end >= 0) end + 3 else len
                        out.append(xml, i, stop)
                        i = stop
                        continue
                    }
                    if (xml.startsWith("<?", i)) {
                        val end = xml.indexOf("?>", i + 2)
                        val stop = if (end >= 0) end + 2 else len
                        out.append(xml, i, stop)
                        i = stop
                        continue
                    }
                    if (xml.startsWith("<!DOCTYPE", i) || xml.startsWith("<!doctype", i)) {
                        var j = i + 9
                        var bracketDepth = 0
                        while (j < len) {
                            val c = xml[j]
                            if (c == '[') bracketDepth += 1
                            else if (c == ']' && bracketDepth > 0) bracketDepth -= 1
                            else if (c == '>' && bracketDepth == 0) {
                                j += 1
                                break
                            }
                            j += 1
                        }
                        out.append(xml, i, j)
                        i = j
                        continue
                    }
                }
                if (xml[i] == '&') {
                    val semi = xml.indexOf(';', i + 1)
                    if (semi > i + 1 && semi - i <= 64) {
                        val name = xml.substring(i + 1, semi)
                        if (name.isNotEmpty() && name[0] != '#' && isXmlEntityName(name)) {
                            if (name !in XML_BUILTIN_ENTITIES) {
                                out.append("&amp;").append(name).append(';')
                                i = semi + 1
                                continue
                            }
                        }
                    }
                }
                out.append(xml[i])
                i += 1
            }
            return out.toString()
        }

        private fun isXmlEntityName(s: String): Boolean {
            val c0 = s[0]
            if (!(c0 in 'A'..'Z' || c0 in 'a'..'z' || c0 == '_')) return false
            for (k in 1 until s.length) {
                val c = s[k]
                if (!(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-' || c == '.')) {
                    return false
                }
            }
            return true
        }
    }
}

/**
 * Memoized O(N * S) shared-subset CSS selector parser & evaluator used by
 * [PluginDomParser] (mirrored identically in `tools/plugin-kit/src/dom.js`).
 *
 * Hard bounds enforced across EVERY input dimension:
 * - Selector text <= 4 KB; comma groups <= 32; compounds per complex selector <= 32;
 *   `:not` / `:is` nesting <= 4; identifier / attribute value <= 256 chars;
 *   `:nth-*` parameters in `[-1_000_000, +1_000_000]`.
 * - Lazy on-demand memo allocation (0 bytes allocated for single-compound selectors
 *   or non-matching top-level steps; descendant DP keyed on parent) with a strict
 *   16 MB per-query memory budget throwing [DomLimitExceededException].
 * - Per-element cached `class` token set, linear KMP budgeted attribute matching
 *   with zero full-attribute lowercase copies, 2M step budget, and deadline checks
 *   every 1024 steps.
 */
internal object CssSelectorEngine {

    private const val MEMO_UNCOMPUTED: Byte = 0
    private const val MEMO_FALSE: Byte = 1
    private const val MEMO_TRUE: Byte = 2

    internal enum class Combinator { DESCENDANT, CHILD, ADJACENT_SIBLING, GENERAL_SIBLING }
    internal enum class AttrOp { EXISTS, EQUALS, INCLUDES, DASH_MATCH, PREFIX, SUFFIX, SUBSTRING }

    internal class AttrMatcher(
        val name: String,
        val op: AttrOp,
        val value: String?,
        val caseFlag: Char?,
        val kmpLps: IntArray? = null,
    )

    internal sealed interface PseudoMatcher {
        data object FirstChild : PseudoMatcher
        data object LastChild : PseudoMatcher
        data object OnlyChild : PseudoMatcher
        data object FirstOfType : PseudoMatcher
        data object LastOfType : PseudoMatcher
        data object OnlyOfType : PseudoMatcher
        data object Empty : PseudoMatcher
        data object Root : PseudoMatcher
        data class NthChild(val a: Int, val b: Int, val fromEnd: Boolean, val ofType: Boolean) : PseudoMatcher
        data class Not(val inner: List<ComplexSelector>) : PseudoMatcher
        data class Is(val inner: List<ComplexSelector>) : PseudoMatcher
    }

    internal data class CompoundSelector(
        val nsPrefix: String?,
        val tagName: String?,
        val ids: List<String>,
        val classes: List<String>,
        val attrs: List<AttrMatcher>,
        val pseudos: List<PseudoMatcher>,
    )

    internal class ComplexSelector(
        val compounds: Array<CompoundSelector>,
        val combinators: Array<Combinator>,
        val isNested: Boolean,
    )

    private class SiblingTable(
        val children: List<Element>,
        val index0Based: IdentityHashMap<Element, Int>,
        val typeIndex1Based: IntArray,
        val typeTotal: IntArray,
    ) {
        val totalChildren: Int get() = children.size

        fun prevSibling(el: Element): Element? {
            val i = index0Based[el] ?: return null
            return if (i > 0) children[i - 1] else null
        }
    }

    private class SelectorMemo(val stepCount: Int, val isNested: Boolean) {
        val matchRowLen: Int = if (isNested) stepCount else (stepCount - 1).coerceAtLeast(0)
        val combRowLen: Int = (stepCount - 1).coerceAtLeast(0)
        var matchStep: IdentityHashMap<Element, ByteArray>? = null
        var selfOrAncestor: IdentityHashMap<Element, ByteArray>? = null
        var anyPrevSibling: IdentityHashMap<Element, ByteArray>? = null

        fun getCached(map: IdentityHashMap<Element, ByteArray>?, el: Element, idx: Int): Byte {
            val row = map?.get(el) ?: return MEMO_UNCOMPUTED
            return row[idx]
        }

        fun setMatchStep(el: Element, idx: Int, value: Byte, ctx: QueryContext) {
            val map = matchStep ?: IdentityHashMap<Element, ByteArray>().also { matchStep = it }
            setInMap(map, el, matchRowLen, idx, value, ctx)
        }

        fun setSelfOrAncestor(el: Element, idx: Int, value: Byte, ctx: QueryContext) {
            val map = selfOrAncestor ?: IdentityHashMap<Element, ByteArray>().also { selfOrAncestor = it }
            setInMap(map, el, combRowLen, idx, value, ctx)
        }

        fun setAnyPrevSibling(el: Element, idx: Int, value: Byte, ctx: QueryContext) {
            val map = anyPrevSibling ?: IdentityHashMap<Element, ByteArray>().also { anyPrevSibling = it }
            setInMap(map, el, combRowLen, idx, value, ctx)
        }

        private fun setInMap(
            map: IdentityHashMap<Element, ByteArray>,
            el: Element,
            rowLen: Int,
            idx: Int,
            value: Byte,
            ctx: QueryContext,
        ) {
            var row = map[el]
            if (row == null) {
                val allocBytes = 32L + (((rowLen + 7) / 8) * 8).toLong()
                ctx.chargeMemory(allocBytes)
                row = ByteArray(rowLen)
                map[el] = row
            }
            row[idx] = value
        }
    }

    private class QueryContext(
        val isXml: Boolean,
        val maxSteps: Int,
        val maxMemoryBytes: Int,
        val deadlineNanos: Long,
    ) {
        private var steps: Int = 0
        private var memoryBytes: Long = 0L
        private val memos = IdentityHashMap<ComplexSelector, SelectorMemo>()
        private val siblingTables = IdentityHashMap<Element, SiblingTable>()
        private val classTokenCache = IdentityHashMap<Element, Set<String>>()

        fun tick(cost: Int = 1) {
            val prev = steps
            val next = prev + cost
            steps = next
            if (next > maxSteps) {
                throw DomLimitExceededException("CSS 选择器匹配步数超过上限（最多 $maxSteps 步）")
            }
            if ((prev ushr 10) != (next ushr 10)) {
                PluginDomParser.checkDeadline(deadlineNanos)
            }
        }

        fun chargeMemory(bytes: Long) {
            if (bytes <= 0L) return
            memoryBytes += bytes
            if (memoryBytes > maxMemoryBytes.toLong()) {
                val mb = (maxMemoryBytes / (1024 * 1024)).coerceAtLeast(1)
                throw DomLimitExceededException("CSS 选择器查询内存超过上限（最多 $mb MB）")
            }
        }

        fun memoFor(sel: ComplexSelector): SelectorMemo =
            memos.getOrPut(sel) { SelectorMemo(sel.compounds.size, sel.isNested) }

        fun siblingTableFor(parent: Element): SiblingTable {
            siblingTables[parent]?.let { return it }
            val children = parent.children()
            val n = children.size
            chargeMemory(44L * n.toLong() + 128L)
            val index0 = IdentityHashMap<Element, Int>(n)
            val typeIdx = IntArray(n)
            val typeCounts = HashMap<String, Int>()
            val tags = arrayOfNulls<String>(n)
            for (i in 0 until n) {
                val c = children[i]
                tick(1)
                index0[c] = i
                val tag = if (isXml) c.tagName() else c.tagName().lowercase(Locale.ROOT)
                val nextTypeCount = (typeCounts[tag] ?: 0) + 1
                typeCounts[tag] = nextTypeCount
                typeIdx[i] = nextTypeCount
                tags[i] = tag
            }
            val typeTot = IntArray(n)
            for (i in 0 until n) {
                typeTot[i] = typeCounts[tags[i]]!!
            }
            val table = SiblingTable(children, index0, typeIdx, typeTot)
            siblingTables[parent] = table
            return table
        }

        fun prevElementSiblingFast(el: Element): Element? {
            val p = el.parent() ?: return null
            return siblingTableFor(p).prevSibling(el)
        }

        fun getClassTokens(el: Element): Set<String> {
            classTokenCache[el]?.let { return it }
            val clsAttr = PluginDomParser.getElementAttr(el, "class", isXml)
            if (clsAttr.isNullOrEmpty()) {
                chargeMemory(32L)
                classTokenCache[el] = emptySet()
                return emptySet()
            }
            val tokens = HashSet<String>()
            val len = clsAttr.length
            var i = 0
            var budgetChunk = 0
            var uniqueTokenBytes = 64L
            while (i < len) {
                while (i < len && isAsciiWs(clsAttr[i])) {
                    i += 1
                    budgetChunk += 1
                    if (budgetChunk >= 256) {
                        tick(budgetChunk)
                        budgetChunk = 0
                    }
                }
                val start = i
                while (i < len && !isAsciiWs(clsAttr[i])) {
                    i += 1
                    budgetChunk += 1
                    if (budgetChunk >= 256) {
                        tick(budgetChunk)
                        budgetChunk = 0
                    }
                }
                if (i > start) {
                    val tok = clsAttr.substring(start, i)
                    if (tokens.add(tok)) {
                        val addedBytes = 32L + tok.length.toLong() * 2L
                        uniqueTokenBytes += addedBytes
                        if (uniqueTokenBytes >= 4096L) {
                            chargeMemory(uniqueTokenBytes)
                            uniqueTokenBytes = 0L
                        }
                    }
                }
            }
            if (budgetChunk > 0) tick(budgetChunk)
            if (uniqueTokenBytes > 0L) chargeMemory(uniqueTokenBytes)
            classTokenCache[el] = tokens
            return tokens
        }

        fun matchesAny(el: Element, selectors: List<ComplexSelector>, isTopLevel: Boolean): Boolean {
            for (sel in selectors) {
                if (evalMatchStep(el, sel, sel.compounds.size - 1, isTopLevel)) return true
            }
            return false
        }

        private fun evalMatchStep(el: Element, sel: ComplexSelector, idx: Int, isTopLevel: Boolean): Boolean {
            val stepCount = sel.compounds.size
            // Single-compound selectors & top-level final steps never need memo storage (zero alloc on mismatch!)
            val shouldMemo = stepCount > 1 && (!isTopLevel || idx < stepCount - 1)
            val memo = if (shouldMemo) memoFor(sel) else null
            if (memo != null) {
                val cached = memo.getCached(memo.matchStep, el, idx)
                if (cached != MEMO_UNCOMPUTED) return cached == MEMO_TRUE
            }

            tick(1)
            val result = if (!matchesCompound(el, sel.compounds[idx])) {
                false
            } else if (idx == 0) {
                true
            } else {
                when (sel.combinators[idx - 1]) {
                    Combinator.CHILD -> {
                        val p = el.parent()
                        p != null && p !is Document && evalMatchStep(p, sel, idx - 1, isTopLevel = false)
                    }
                    Combinator.DESCENDANT -> {
                        val p = el.parent()
                        p != null && p !is Document && evalSelfOrAncestorMatches(p, sel, idx - 1)
                    }
                    Combinator.ADJACENT_SIBLING -> {
                        val prev = prevElementSiblingFast(el)
                        prev != null && evalMatchStep(prev, sel, idx - 1, isTopLevel = false)
                    }
                    Combinator.GENERAL_SIBLING -> evalAnyPrevSiblingMatches(el, sel, idx - 1)
                }
            }
            if (memo != null) {
                memo.setMatchStep(el, idx, if (result) MEMO_TRUE else MEMO_FALSE, this)
            }
            return result
        }

        /**
         * Iterative O(1)-amortized DP for descendant combinator keyed by parent node:
         * `selfOrAnc(p, idx) = matchStep(p, idx) || selfOrAnc(p.parent, idx)`.
         * Avoids allocating rows for leaf/sibling elements that share the same parent.
         */
        private fun evalSelfOrAncestorMatches(parent: Element, sel: ComplexSelector, idx: Int): Boolean {
            val memo = memoFor(sel)
            val quick = memo.getCached(memo.selfOrAncestor, parent, idx)
            if (quick != MEMO_UNCOMPUTED) return quick == MEMO_TRUE

            val chain = ArrayList<Element>()
            var cur: Element = parent
            var baseResult = false
            while (true) {
                val cached = memo.getCached(memo.selfOrAncestor, cur, idx)
                if (cached != MEMO_UNCOMPUTED) {
                    baseResult = cached == MEMO_TRUE
                    break
                }
                tick(1)
                if (evalMatchStep(cur, sel, idx, isTopLevel = false)) {
                    memo.setSelfOrAncestor(cur, idx, MEMO_TRUE, this)
                    baseResult = true
                    break
                }
                chain.add(cur)
                val p = cur.parent()
                if (p == null || p is Document) {
                    baseResult = false
                    break
                }
                cur = p
            }
            val fill = if (baseResult) MEMO_TRUE else MEMO_FALSE
            for (i in chain.size - 1 downTo 0) {
                memo.setSelfOrAncestor(chain[i], idx, fill, this)
            }
            return baseResult
        }

        /**
         * Iterative O(1)-amortized DP for general sibling combinator (`~`):
         * `anyPrev(el, idx) = matchStep(prevSib, idx) || anyPrev(prevSib, idx)`.
         */
        private fun evalAnyPrevSiblingMatches(el: Element, sel: ComplexSelector, idx: Int): Boolean {
            val memo = memoFor(sel)
            val quick = memo.getCached(memo.anyPrevSibling, el, idx)
            if (quick != MEMO_UNCOMPUTED) return quick == MEMO_TRUE

            val chain = ArrayList<Element>()
            var cur: Element = el
            var baseResult = false
            while (true) {
                val cached = memo.getCached(memo.anyPrevSibling, cur, idx)
                if (cached != MEMO_UNCOMPUTED) {
                    baseResult = cached == MEMO_TRUE
                    break
                }
                tick(1)
                val prev = prevElementSiblingFast(cur)
                if (prev == null) {
                    memo.setAnyPrevSibling(cur, idx, MEMO_FALSE, this)
                    baseResult = false
                    break
                }
                if (evalMatchStep(prev, sel, idx, isTopLevel = false)) {
                    memo.setAnyPrevSibling(cur, idx, MEMO_TRUE, this)
                    baseResult = true
                    break
                }
                chain.add(cur)
                cur = prev
            }
            val fill = if (baseResult) MEMO_TRUE else MEMO_FALSE
            for (i in chain.size - 1 downTo 0) {
                memo.setAnyPrevSibling(chain[i], idx, fill, this)
            }
            return baseResult
        }

        private fun matchesCompound(el: Element, comp: CompoundSelector): Boolean {
            if (el is Document) return false
            if (comp.nsPrefix != null || comp.tagName != null) {
                if (!matchesTag(el.tagName(), comp.nsPrefix, comp.tagName, isXml)) return false
            }
            for (id in comp.ids) {
                tick(1)
                val actual = PluginDomParser.getElementAttr(el, "id", isXml) ?: return false
                if (!equalsBudgeted(actual, id, ci = false)) return false
            }
            if (comp.classes.isNotEmpty()) {
                val tokens = getClassTokens(el)
                for (c in comp.classes) {
                    tick(1)
                    if (c !in tokens) return false
                }
            }
            for (am in comp.attrs) {
                if (!matchesAttr(el, am)) return false
            }
            for (pm in comp.pseudos) {
                tick(1)
                if (!matchesPseudo(el, pm)) return false
            }
            return true
        }

        private fun matchesAttr(el: Element, am: AttrMatcher): Boolean {
            tick(1)
            val actual = PluginDomParser.getElementAttr(el, am.name, isXml) ?: return false
            if (am.op == AttrOp.EXISTS) return true
            val expected = am.value ?: ""
            val ci = am.caseFlag == 'i'
            return when (am.op) {
                AttrOp.EXISTS -> true
                AttrOp.EQUALS -> equalsBudgeted(actual, expected, ci)
                AttrOp.INCLUDES -> includesWordBudgeted(actual, expected, ci)
                AttrOp.DASH_MATCH -> dashMatchBudgeted(actual, expected, ci)
                AttrOp.PREFIX -> expected.isNotEmpty() && startsWithBudgeted(actual, expected, ci)
                AttrOp.SUFFIX -> expected.isNotEmpty() && endsWithBudgeted(actual, expected, ci)
                AttrOp.SUBSTRING -> expected.isNotEmpty() && kmpContainsBudgeted(actual, expected, am.kmpLps!!, ci)
            }
        }

        private fun equalsBudgeted(actual: String, expected: String, ci: Boolean): Boolean {
            if (actual.length != expected.length) return false
            var budgetChunk = 0
            for (i in 0 until expected.length) {
                budgetChunk += 1
                if (budgetChunk >= 64) {
                    tick(budgetChunk)
                    budgetChunk = 0
                }
                if (!charsEqual(actual[i], expected[i], ci)) {
                    if (budgetChunk > 0) tick(budgetChunk)
                    return false
                }
            }
            if (budgetChunk > 0) tick(budgetChunk)
            return true
        }

        private fun startsWithBudgeted(actual: String, prefix: String, ci: Boolean): Boolean {
            if (actual.length < prefix.length) return false
            var budgetChunk = 0
            for (i in 0 until prefix.length) {
                budgetChunk += 1
                if (budgetChunk >= 64) {
                    tick(budgetChunk)
                    budgetChunk = 0
                }
                if (!charsEqual(actual[i], prefix[i], ci)) {
                    if (budgetChunk > 0) tick(budgetChunk)
                    return false
                }
            }
            if (budgetChunk > 0) tick(budgetChunk)
            return true
        }

        private fun endsWithBudgeted(actual: String, suffix: String, ci: Boolean): Boolean {
            val offset = actual.length - suffix.length
            if (offset < 0) return false
            var budgetChunk = 0
            for (i in 0 until suffix.length) {
                budgetChunk += 1
                if (budgetChunk >= 64) {
                    tick(budgetChunk)
                    budgetChunk = 0
                }
                if (!charsEqual(actual[offset + i], suffix[i], ci)) {
                    if (budgetChunk > 0) tick(budgetChunk)
                    return false
                }
            }
            if (budgetChunk > 0) tick(budgetChunk)
            return true
        }

        private fun dashMatchBudgeted(actual: String, expected: String, ci: Boolean): Boolean {
            if (actual.length < expected.length) return false
            if (!startsWithBudgeted(actual, expected, ci)) return false
            return actual.length == expected.length || actual[expected.length] == '-'
        }

        private fun includesWordBudgeted(actual: String, word: String, ci: Boolean): Boolean {
            if (word.isEmpty()) return false
            for (c in word) {
                if (c.isWhitespace()) return false
            }
            val n = actual.length
            val m = word.length
            var i = 0
            var budgetChunk = 0
            while (i < n) {
                while (i < n && isAsciiWs(actual[i])) {
                    i += 1
                    budgetChunk += 1
                    if (budgetChunk >= 256) {
                        tick(budgetChunk)
                        budgetChunk = 0
                    }
                }
                val start = i
                while (i < n && !isAsciiWs(actual[i])) {
                    i += 1
                    budgetChunk += 1
                    if (budgetChunk >= 256) {
                        tick(budgetChunk)
                        budgetChunk = 0
                    }
                }
                if (i - start == m) {
                    var matched = true
                    for (k in 0 until m) {
                        if (!charsEqual(actual[start + k], word[k], ci)) {
                            matched = false
                            break
                        }
                    }
                    if (matched) {
                        if (budgetChunk > 0) tick(budgetChunk)
                        return true
                    }
                }
            }
            if (budgetChunk > 0) tick(budgetChunk)
            return false
        }

        private fun kmpContainsBudgeted(haystack: String, needle: String, lps: IntArray, ci: Boolean): Boolean {
            val n = haystack.length
            val m = needle.length
            if (m == 0 || n < m) return false
            var i = 0
            var j = 0
            var budgetChunk = 0
            while (i < n) {
                budgetChunk += 1
                if (budgetChunk >= 256) {
                    tick(budgetChunk)
                    budgetChunk = 0
                }
                if (charsEqual(haystack[i], needle[j], ci)) {
                    i += 1
                    j += 1
                    if (j == m) {
                        if (budgetChunk > 0) tick(budgetChunk)
                        return true
                    }
                } else if (j != 0) {
                    j = lps[j - 1]
                } else {
                    i += 1
                }
            }
            if (budgetChunk > 0) tick(budgetChunk)
            return false
        }

        private fun matchesPseudo(el: Element, pm: PseudoMatcher): Boolean = when (pm) {
            PseudoMatcher.FirstChild -> {
                val p = el.parent() ?: return false
                siblingTableFor(p).index0Based[el] == 0
            }
            PseudoMatcher.LastChild -> {
                val p = el.parent() ?: return false
                val t = siblingTableFor(p)
                t.index0Based[el] == t.totalChildren - 1
            }
            PseudoMatcher.OnlyChild -> {
                val p = el.parent() ?: return false
                siblingTableFor(p).totalChildren == 1
            }
            PseudoMatcher.FirstOfType -> {
                val p = el.parent() ?: return false
                val t = siblingTableFor(p)
                val pos0 = t.index0Based[el] ?: return false
                t.typeIndex1Based[pos0] == 1
            }
            PseudoMatcher.LastOfType -> {
                val p = el.parent() ?: return false
                val t = siblingTableFor(p)
                val pos0 = t.index0Based[el] ?: return false
                t.typeIndex1Based[pos0] == t.typeTotal[pos0]
            }
            PseudoMatcher.OnlyOfType -> {
                val p = el.parent() ?: return false
                val t = siblingTableFor(p)
                val pos0 = t.index0Based[el] ?: return false
                t.typeTotal[pos0] == 1
            }
            PseudoMatcher.Empty -> el.childNodes().none { n ->
                n is Element || (n is TextNode && n.wholeText.isNotEmpty()) || (n is CDataNode && n.text().isNotEmpty())
            }
            PseudoMatcher.Root -> el.parent() is Document
            is PseudoMatcher.NthChild -> {
                val p = el.parent() ?: return false
                val t = siblingTableFor(p)
                val pos0 = t.index0Based[el] ?: return false
                val idx = if (pm.ofType) {
                    val pos1 = t.typeIndex1Based[pos0]
                    val tot = t.typeTotal[pos0]
                    if (pm.fromEnd) (tot - pos1 + 1) else pos1
                } else {
                    val pos1 = pos0 + 1
                    if (pm.fromEnd) (t.totalChildren - pos1 + 1) else pos1
                }
                matchesAnPlusB(idx, pm.a, pm.b)
            }
            is PseudoMatcher.Not -> !matchesAny(el, pm.inner, isTopLevel = false)
            is PseudoMatcher.Is -> matchesAny(el, pm.inner, isTopLevel = false)
        }
    }

    fun parse(rawSelector: String): List<ComplexSelector> {
        if (rawSelector.length > PluginDomParser.MAX_SELECTOR_BYTES ||
            rawSelector.toByteArray(Charsets.UTF_8).size > PluginDomParser.MAX_SELECTOR_BYTES
        ) {
            throw DomLimitExceededException("CSS 选择器超过长度上限（最多 ${PluginDomParser.MAX_SELECTOR_BYTES} 字节）")
        }
        val p = SelectorParser(rawSelector, nestingDepth = 0)
        val list = p.parseSelectorList(eofExpected = true)
        if (list.isEmpty()) throw IllegalArgumentException("无效的 CSS 选择器：$rawSelector")
        return list
    }

    fun queryFirst(
        scopeRoot: Element,
        selectors: List<ComplexSelector>,
        isXml: Boolean,
        maxSteps: Int = PluginDomParser.MAX_SELECTOR_STEPS,
        maxMemoryBytes: Int = PluginDomParser.MAX_SELECTOR_MEMORY_BYTES,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Element? {
        val ctx = QueryContext(isXml, maxSteps, maxMemoryBytes, deadlineNanos)
        val stack = ArrayDeque<Element>()
        val topChildren = scopeRoot.children()
        for (i in topChildren.size - 1 downTo 0) {
            stack.addLast(topChildren[i])
        }
        while (stack.isNotEmpty()) {
            ctx.tick(1)
            val el = stack.removeLast()
            if (ctx.matchesAny(el, selectors, isTopLevel = true)) return el
            val ch = el.children()
            for (i in ch.size - 1 downTo 0) {
                stack.addLast(ch[i])
            }
        }
        return null
    }

    fun queryAll(
        scopeRoot: Element,
        selectors: List<ComplexSelector>,
        isXml: Boolean,
        maxSteps: Int = PluginDomParser.MAX_SELECTOR_STEPS,
        maxMemoryBytes: Int = PluginDomParser.MAX_SELECTOR_MEMORY_BYTES,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): List<Element> {
        val ctx = QueryContext(isXml, maxSteps, maxMemoryBytes, deadlineNanos)
        val out = ArrayList<Element>()
        val stack = ArrayDeque<Element>()
        val topChildren = scopeRoot.children()
        for (i in topChildren.size - 1 downTo 0) {
            stack.addLast(topChildren[i])
        }
        while (stack.isNotEmpty()) {
            ctx.tick(1)
            val el = stack.removeLast()
            if (ctx.matchesAny(el, selectors, isTopLevel = true)) {
                out.add(el)
            }
            val ch = el.children()
            for (i in ch.size - 1 downTo 0) {
                stack.addLast(ch[i])
            }
        }
        return out
    }

    private fun isAsciiWs(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    private fun charsEqual(a: Char, b: Char, ci: Boolean): Boolean {
        if (a == b) return true
        if (!ci) return false
        val ca = if (a in 'A'..'Z') (a.code + 32).toChar() else if (a.code >= 128) a.lowercaseChar() else a
        val cb = if (b in 'A'..'Z') (b.code + 32).toChar() else if (b.code >= 128) b.lowercaseChar() else b
        return ca == cb
    }

    private fun buildKmpLps(pattern: String, ci: Boolean): IntArray {
        val m = pattern.length
        val lps = IntArray(m)
        var len = 0
        var i = 1
        while (i < m) {
            if (charsEqual(pattern[i], pattern[len], ci)) {
                len += 1
                lps[i] = len
                i += 1
            } else if (len != 0) {
                len = lps[len - 1]
            } else {
                lps[i] = 0
                i += 1
            }
        }
        return lps
    }

    private fun matchesTag(actualRaw: String, nsPrefix: String?, wantedTag: String?, isXml: Boolean): Boolean {
        val actual = if (isXml) actualRaw else actualRaw.lowercase(Locale.ROOT)
        val wanted = wantedTag?.let { if (isXml) it else it.lowercase(Locale.ROOT) }
        val ns = nsPrefix?.let { if (isXml) it else it.lowercase(Locale.ROOT) }

        if (ns == null) {
            if (wanted == null) return true
            return actual == wanted
        }
        val colonIdx = actual.indexOf(':')
        val actualNs = if (colonIdx >= 0) actual.substring(0, colonIdx) else ""
        val actualLocal = if (colonIdx >= 0) actual.substring(colonIdx + 1) else actual
        val nsMatches = when (ns) {
            "*" -> true
            "" -> colonIdx < 0
            else -> actualNs == ns
        }
        if (!nsMatches) return false
        return wanted == null || actualLocal == wanted
    }

    private fun matchesAnPlusB(index1Based: Int, a: Int, b: Int): Boolean {
        if (a == 0) return index1Based == b
        val diff = index1Based - b
        if (diff % a != 0) return false
        return (diff / a) >= 0
    }

    private val SIGNED_INT_REGEX = Regex("^[+-]?\\d+$")
    private val EXPLICIT_SIGNED_INT_REGEX = Regex("^[+-]\\d+$")

    private class SelectorParser(
        private val src: String,
        private val nestingDepth: Int,
    ) {
        private var pos = 0

        fun parseSelectorList(eofExpected: Boolean): List<ComplexSelector> {
            val out = ArrayList<ComplexSelector>()
            skipWs()
            if (eof()) throw IllegalArgumentException("CSS 选择器不能为空")
            while (true) {
                if (out.size >= PluginDomParser.MAX_SELECTOR_GROUPS) {
                    throw DomLimitExceededException(
                        "CSS 选择器逗号分组数超过上限（最多 ${PluginDomParser.MAX_SELECTOR_GROUPS} 组）",
                    )
                }
                out.add(parseComplexSelector())
                skipWs()
                if (peek() == ',') {
                    pos += 1
                    skipWs()
                    continue
                }
                break
            }
            if (eofExpected && !eof()) {
                throw IllegalArgumentException("无效的 CSS 选择器语法：'$src'（位置 $pos）")
            }
            return out
        }

        private fun parseComplexSelector(): ComplexSelector {
            skipWs()
            val compounds = ArrayList<CompoundSelector>()
            val combinators = ArrayList<Combinator>()
            compounds.add(parseCompoundSelector())
            while (true) {
                val hadWs = skipWs()
                if (eof() || peek() == ',' || peek() == ')') break
                val comb = when (peek()) {
                    '>' -> { pos += 1; skipWs(); Combinator.CHILD }
                    '+' -> { pos += 1; skipWs(); Combinator.ADJACENT_SIBLING }
                    '~' -> { pos += 1; skipWs(); Combinator.GENERAL_SIBLING }
                    else -> if (hadWs) Combinator.DESCENDANT else break
                }
                if (compounds.size >= PluginDomParser.MAX_COMPOUNDS_PER_SELECTOR) {
                    throw DomLimitExceededException(
                        "单个 CSS 选择器复合段数超过上限（最多 ${PluginDomParser.MAX_COMPOUNDS_PER_SELECTOR} 段）",
                    )
                }
                combinators.add(comb)
                compounds.add(parseCompoundSelector())
            }
            return ComplexSelector(
                compounds = compounds.toTypedArray(),
                combinators = combinators.toTypedArray(),
                isNested = nestingDepth > 0,
            )
        }

        private fun parseCompoundSelector(): CompoundSelector {
            var nsPrefix: String? = null
            var tagName: String? = null
            val ids = ArrayList<String>()
            val classes = ArrayList<String>()
            val attrs = ArrayList<AttrMatcher>()
            val pseudos = ArrayList<PseudoMatcher>()
            var matchedAny = false

            if (!eof()) {
                val c = peek()
                if (c == '|') {
                    pos += 1
                    nsPrefix = ""
                    tagName = if (peek() == '*') { pos += 1; null } else readIdent()
                    matchedAny = true
                } else if (c == '*') {
                    pos += 1
                    if (peek() == '|') {
                        pos += 1
                        nsPrefix = "*"
                        tagName = if (peek() == '*') { pos += 1; null } else readIdent()
                    } else {
                        tagName = null
                    }
                    matchedAny = true
                } else if (isIdentStart(c)) {
                    val first = readIdent()
                    if (peek() == '|') {
                        pos += 1
                        nsPrefix = first
                        tagName = if (peek() == '*') { pos += 1; null } else readIdent()
                    } else {
                        tagName = first
                    }
                    matchedAny = true
                }
            }

            while (!eof()) {
                when (peek()) {
                    '#' -> {
                        pos += 1
                        ids.add(readIdent())
                        matchedAny = true
                    }
                    '.' -> {
                        pos += 1
                        classes.add(readIdent())
                        matchedAny = true
                    }
                    '[' -> {
                        attrs.add(parseAttrSelector())
                        matchedAny = true
                    }
                    ':' -> {
                        pseudos.add(parsePseudoSelector())
                        matchedAny = true
                    }
                    else -> break
                }
            }

            if (!matchedAny) {
                throw IllegalArgumentException("无效的 CSS 选择器：'$src'（位置 $pos）")
            }
            return CompoundSelector(nsPrefix, tagName, ids, classes, attrs, pseudos)
        }

        private fun parseAttrSelector(): AttrMatcher {
            pos += 1
            skipWs()
            val name = readIdent()
            skipWs()
            if (peek() == ']') {
                pos += 1
                return AttrMatcher(name, AttrOp.EXISTS, null, null, null)
            }
            val op = when {
                src.startsWith("~=", pos) -> { pos += 2; AttrOp.INCLUDES }
                src.startsWith("|=", pos) -> { pos += 2; AttrOp.DASH_MATCH }
                src.startsWith("^=", pos) -> { pos += 2; AttrOp.PREFIX }
                src.startsWith("$=", pos) -> { pos += 2; AttrOp.SUFFIX }
                src.startsWith("*=", pos) -> { pos += 2; AttrOp.SUBSTRING }
                peek() == '=' -> { pos += 1; AttrOp.EQUALS }
                else -> throw IllegalArgumentException("不支持的属性选择器操作符：'$src'（位置 $pos）")
            }
            skipWs()
            val value = if (peek() == '"' || peek() == '\'') readQuotedString() else readUnquotedAttrValue()
            skipWs()
            var flag: Char? = null
            if (peek() == 'i' || peek() == 'I' || peek() == 's' || peek() == 'S') {
                flag = peek().lowercaseChar()
                pos += 1
                skipWs()
            }
            if (peek() != ']') {
                throw IllegalArgumentException("属性选择器缺少右括号 ']'：'$src'")
            }
            pos += 1
            val lps = if (op == AttrOp.SUBSTRING && value.isNotEmpty()) {
                buildKmpLps(value, ci = flag == 'i')
            } else {
                null
            }
            return AttrMatcher(name, op, value, flag, lps)
        }

        private fun parsePseudoSelector(): PseudoMatcher {
            pos += 1
            if (peek() == ':') {
                pos += 1
                val pe = readIdent()
                throw IllegalArgumentException("不支持的 CSS 伪元素：::$pe")
            }
            val rawName = readIdent()
            val name = rawName.lowercase(Locale.ROOT)
            val hasParens = peek() == '('
            if (!hasParens) {
                return when (name) {
                    "first-child" -> PseudoMatcher.FirstChild
                    "last-child" -> PseudoMatcher.LastChild
                    "only-child" -> PseudoMatcher.OnlyChild
                    "first-of-type" -> PseudoMatcher.FirstOfType
                    "last-of-type" -> PseudoMatcher.LastOfType
                    "only-of-type" -> PseudoMatcher.OnlyOfType
                    "empty" -> PseudoMatcher.Empty
                    "root" -> PseudoMatcher.Root
                    else -> throw IllegalArgumentException("不支持的 CSS 选择器伪类：:$rawName")
                }
            }
            pos += 1
            return when (name) {
                "not", "is" -> {
                    if (nestingDepth >= PluginDomParser.MAX_PSEUDO_NESTING) {
                        throw DomLimitExceededException(
                            "CSS 伪类嵌套层数超过上限（最多 ${PluginDomParser.MAX_PSEUDO_NESTING} 层）",
                        )
                    }
                    val sub = SelectorParser(src, nestingDepth + 1)
                    sub.pos = pos
                    val inner = sub.parseSelectorList(eofExpected = false)
                    pos = sub.pos
                    skipWs()
                    if (peek() != ')') throw IllegalArgumentException(":$name() 缺少右括号：'$src'")
                    pos += 1
                    if (name == "not") PseudoMatcher.Not(inner) else PseudoMatcher.Is(inner)
                }
                "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type" -> {
                    val arg = readBalancedParenArg()
                    val (a, b) = parseAnPlusB(arg)
                    PseudoMatcher.NthChild(
                        a = a,
                        b = b,
                        fromEnd = name == "nth-last-child" || name == "nth-last-of-type",
                        ofType = name == "nth-of-type" || name == "nth-last-of-type",
                    )
                }
                else -> throw IllegalArgumentException("不支持的 CSS 选择器伪类：:$rawName(...)")
            }
        }

        private fun readBalancedParenArg(): String {
            val start = pos
            var depth = 1
            while (!eof()) {
                val c = src[pos]
                if (c == '(') depth += 1
                else if (c == ')') {
                    depth -= 1
                    if (depth == 0) {
                        val content = src.substring(start, pos)
                        pos += 1
                        return content
                    }
                }
                if (pos - start > PluginDomParser.MAX_SELECTOR_VALUE_LENGTH) {
                    throw DomLimitExceededException(
                        "CSS 伪类参数超过长度上限（最多 ${PluginDomParser.MAX_SELECTOR_VALUE_LENGTH} 字符）",
                    )
                }
                pos += 1
            }
            throw IllegalArgumentException("CSS 伪类缺少右括号：'$src'")
        }

        private fun parseBoundedNthInt(token: String, requireSign: Boolean, raw: String): Int {
            val regex = if (requireSign) EXPLICIT_SIGNED_INT_REGEX else SIGNED_INT_REGEX
            if (!regex.matches(token)) {
                throw IllegalArgumentException("无效的 :nth-* 参数：$raw")
            }
            val parsed = token.toLongOrNull()
                ?: throw IllegalArgumentException("无效或超范围的 :nth-* 参数：$raw")
            if (parsed < -PluginDomParser.MAX_NTH_ABS || parsed > PluginDomParser.MAX_NTH_ABS) {
                throw IllegalArgumentException(
                    ":nth-* 参数超出范围（允许 ±${PluginDomParser.MAX_NTH_ABS}）：$raw",
                )
            }
            return parsed.toInt()
        }

        private fun parseAnPlusB(raw: String): Pair<Int, Int> {
            val s = raw.replace(Regex("\\s+"), "").lowercase(Locale.ROOT)
            if (s == "odd") return 2 to 1
            if (s == "even") return 2 to 0
            val nIdx = s.indexOf('n')
            if (nIdx < 0) {
                val b = parseBoundedNthInt(s, requireSign = false, raw = raw)
                return 0 to b
            }
            val aPart = s.substring(0, nIdx)
            val a = when (aPart) {
                "", "+" -> 1
                "-" -> -1
                else -> parseBoundedNthInt(aPart, requireSign = false, raw = raw)
            }
            val bPart = s.substring(nIdx + 1)
            val b = if (bPart.isEmpty()) {
                0
            } else {
                parseBoundedNthInt(bPart, requireSign = true, raw = raw)
            }
            return a to b
        }

        private fun readIdent(): String {
            val sb = StringBuilder()
            while (!eof()) {
                val c = src[pos]
                if (c == '\\' && pos + 1 < src.length) {
                    pos += 1
                    sb.append(src[pos])
                    pos += 1
                } else if (isIdentChar(c)) {
                    sb.append(c)
                    pos += 1
                } else {
                    break
                }
                if (sb.length > PluginDomParser.MAX_SELECTOR_VALUE_LENGTH) {
                    throw DomLimitExceededException(
                        "CSS 选择器标识符超过长度上限（最多 ${PluginDomParser.MAX_SELECTOR_VALUE_LENGTH} 字符）",
                    )
                }
            }
            if (sb.isEmpty()) {
                throw IllegalArgumentException("无效的 CSS 标识符：'$src'（位置 $pos）")
            }
            return sb.toString()
        }

        private fun readQuotedString(): String {
            val quote = src[pos++]
            val sb = StringBuilder()
            while (!eof()) {
                val c = src[pos++]
                if (c == '\\' && !eof()) {
                    sb.append(src[pos++])
                } else if (c == quote) {
                    return sb.toString()
                } else {
                    sb.append(c)
                }
                if (sb.length > PluginDomParser.MAX_SELECTOR_VALUE_LENGTH) {
                    throw DomLimitExceededException(
                        "CSS 选择器属性值超过长度上限（最多 ${PluginDomParser.MAX_SELECTOR_VALUE_LENGTH} 字符）",
                    )
                }
            }
            throw IllegalArgumentException("CSS 选择器引号未闭合：'$src'")
        }

        private fun readUnquotedAttrValue(): String {
            val sb = StringBuilder()
            while (!eof()) {
                val c = src[pos]
                if (c == ']' || c.isWhitespace()) break
                if (c == '\\' && pos + 1 < src.length) {
                    pos += 1
                    sb.append(src[pos++])
                } else {
                    sb.append(c)
                    pos += 1
                }
                if (sb.length > PluginDomParser.MAX_SELECTOR_VALUE_LENGTH) {
                    throw DomLimitExceededException(
                        "CSS 选择器属性值超过长度上限（最多 ${PluginDomParser.MAX_SELECTOR_VALUE_LENGTH} 字符）",
                    )
                }
            }
            return sb.toString()
        }

        private fun isIdentStart(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '-' || c == '\\' || c.code >= 0x80

        private fun isIdentChar(c: Char): Boolean =
            isIdentStart(c) || c in '0'..'9'

        private fun skipWs(): Boolean {
            val start = pos
            while (!eof() && src[pos].isWhitespace()) pos += 1
            return pos > start
        }

        private fun eof(): Boolean = pos >= src.length
        private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'
    }
}
