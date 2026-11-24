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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DomLimitExceededException(message: String) : IllegalStateException(message)
class DomTimeoutException(message: String) : IllegalStateException(message)

/**
 * Lazy handle-backed HTML/XML DOM parser for `ctx.parse.html(text)` and
 * `ctx.parse.xml(text)` (§5, §11.1, S4):
 * - Capped at 8 parsed documents per invocation, 4 MB per document, and
 *   [MAX_TOTAL_NODES] (200,000) total element nodes (excluding `#document`)
 * - Counts elements DURING parsing via Jsoup [StreamParser] to abort HTML5
 *   amplification bombs immediately, then performs an exact post-parse element
 *   count so adoption-agency clones never bypass the 200k ceiling
 * - Uses an O(N * S) memoized CSS selector engine with precomputed O(1) sibling
 *   indices, a strict step budget ([MAX_SELECTOR_STEPS]), and periodic thread
 *   interrupt + call deadline checks so host-side selector evaluation can never
 *   hang the plugin thread
 * - Canonical `html()` / `outerHtml()` serialization with `prettyPrint(false)`
 *   aligned bit-for-bit with `tools/plugin-kit/src/dom.js` (which uses `parse5`)
 */
class PluginDomParser(
    private val maxDocsPerCall: Int = MAX_DOCS_PER_CALL,
    private val maxDocBytes: Int = MAX_DOC_BYTES,
    private val maxTotalNodes: Int = MAX_TOTAL_NODES,
    private val maxSelectorSteps: Int = MAX_SELECTOR_STEPS,
) {
    private data class NodeEntry(
        val el: Element,
        val isXml: Boolean,
    )

    private val nextId = AtomicInteger(1)
    private val docCount = AtomicInteger(0)
    private val totalNodes = AtomicInteger(0)
    private val nodes = ConcurrentHashMap<Int, NodeEntry>()

    @Volatile
    private var callDeadlineNanos: Long = Long.MAX_VALUE

    fun setCallDeadlineNanos(deadlineNanos: Long) {
        callDeadlineNanos = deadlineNanos
    }

    fun parseHtml(html: String): Int {
        enforcePreParseLimits(html)
        val parser = Parser.htmlParser()
            .settings(ParseSettings.htmlDefault)
            .setMaxDepth(MAX_NESTING_DEPTH)
        val (doc, count) = parseWithStreamingAndExactCount(html, parser)
        doc.outputSettings().prettyPrint(false)
        docCount.incrementAndGet()
        totalNodes.addAndGet(count)
        return register(doc, isXml = false)
    }

    fun parseXml(xml: String): Int {
        enforcePreParseLimits(xml)
        val sanitized = escapeUndefinedXmlEntities(xml)
        val parser = Parser.xmlParser()
            .settings(ParseSettings.preserveCase)
            .setMaxDepth(MAX_NESTING_DEPTH)
        val (doc, count) = parseWithStreamingAndExactCount(sanitized, parser)
        doc.outputSettings().prettyPrint(false)
        docCount.incrementAndGet()
        totalNodes.addAndGet(count)
        return register(doc, isXml = true)
    }

    fun selectFirstId(nodeId: Int, selector: String): Int {
        val entry = nodes[nodeId] ?: return 0
        val parsed = CssSelectorEngine.parse(selector)
        val found = CssSelectorEngine.queryFirst(
            scopeRoot = entry.el,
            selectors = parsed,
            isXml = entry.isXml,
            maxSteps = maxSelectorSteps,
            deadlineNanos = callDeadlineNanos,
        ) ?: return 0
        return register(found, entry.isXml)
    }

    fun selectAllIds(nodeId: Int, selector: String): JsonArray {
        val entry = nodes[nodeId] ?: return JsonArray(emptyList())
        val parsed = CssSelectorEngine.parse(selector)
        val found = CssSelectorEngine.queryAll(
            scopeRoot = entry.el,
            selectors = parsed,
            isXml = entry.isXml,
            maxSteps = maxSelectorSteps,
            deadlineNanos = callDeadlineNanos,
        )
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

    fun text(nodeId: Int): String {
        val entry = nodes[nodeId] ?: return ""
        val sb = StringBuilder()
        if (entry.isXml) {
            collectXmlText(entry.el, sb)
        } else {
            collectHtmlText(entry.el, sb)
        }
        return collapseAsciiAndNbspWhitespace(sb.toString())
    }

    fun html(nodeId: Int): String {
        val entry = nodes[nodeId] ?: return ""
        val sb = StringBuilder()
        serializeChildren(entry.el, entry.isXml, sb)
        return sb.toString()
    }

    fun outerHtml(nodeId: Int): String {
        val entry = nodes[nodeId] ?: return ""
        val sb = StringBuilder()
        if (entry.el is Document) {
            serializeChildren(entry.el, entry.isXml, sb)
        } else {
            serializeNode(entry.el, entry.isXml, sb)
        }
        return sb.toString()
    }

    fun attr(nodeId: Int, name: String): String? {
        val entry = nodes[nodeId] ?: return null
        if (entry.el is Document) return null
        return getElementAttr(entry.el, name, entry.isXml)
    }

    fun attrsJson(nodeId: Int): String {
        val entry = nodes[nodeId] ?: return "{}"
        if (entry.el is Document) return "{}"
        return buildJsonObject {
            for (a in entry.el.attributes()) {
                val key = if (entry.isXml) a.key else a.key.lowercase(Locale.ROOT)
                put(key, a.value)
            }
        }.toString()
    }

    fun clear() {
        nodes.clear()
        docCount.set(0)
        totalNodes.set(0)
        callDeadlineNanos = Long.MAX_VALUE
    }

    fun currentTotalNodes(): Int = totalNodes.get()

    private fun checkDeadline(deadlineNanos: Long) {
        if (Thread.currentThread().isInterrupted) {
            throw DomTimeoutException("DOM 操作已中断")
        }
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() > deadlineNanos) {
            throw DomTimeoutException("DOM 操作执行超时")
        }
    }

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
                            "单次调用解析的 DOM 节点总数超过上限（最多 $maxTotalNodes 个）",
                        )
                    }
                }
            }
            i += 1
        }
    }

    /**
     * Step 1: Parses [input] incrementally via Jsoup [StreamParser], aborting
     * immediately if emitted element count crosses [remaining] (blocking OOM
     * amplification bombs during parsing).
     * Step 2: Performs an exact iterative DFS count over all `Element` nodes
     * (excluding `#document` itself, matching `tools/plugin-kit/src/dom.js`)
     * so adoption-agency clones that were not re-emitted still respect the cap.
     */
    private fun parseWithStreamingAndExactCount(input: String, parser: Parser): Pair<Document, Int> {
        val remaining = maxTotalNodes - totalNodes.get()
        if (remaining <= 0) {
            throw DomLimitExceededException("单次调用解析的 DOM 节点总数超过上限（最多 $maxTotalNodes 个）")
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
                        "单次调用解析的 DOM 节点总数超过上限（最多 $maxTotalNodes 个）",
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
                    "单次调用解析的 DOM 节点总数超过上限（最多 $maxTotalNodes 个）",
                )
            }
            val ch = cur.children()
            for (i in ch.size - 1 downTo 0) {
                stack.addLast(ch[i])
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
        const val MAX_SELECTOR_STEPS = 2_000_000
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

        private fun serializeChildren(el: Element, isXml: Boolean, out: StringBuilder) {
            val rawTextParent = !isXml && el !is Document && el.tagName().lowercase(Locale.ROOT) in HTML_RAW_TEXT_TAGS
            for (child in el.childNodes()) {
                if (rawTextParent && (child is TextNode || child is DataNode)) {
                    out.append(if (child is DataNode) child.wholeData else (child as TextNode).wholeText)
                } else {
                    serializeNode(child, isXml, out)
                }
            }
        }

        private fun serializeNode(node: Node, isXml: Boolean, out: StringBuilder) {
            when (node) {
                is CDataNode -> {
                    if (isXml) {
                        out.append("<![CDATA[").append(node.text()).append("]]>")
                    } else {
                        appendHtmlText(node.text(), out)
                    }
                }
                is TextNode -> {
                    if (isXml) appendXmlText(node.wholeText, out)
                    else appendHtmlText(node.wholeText, out)
                }
                is DataNode -> out.append(node.wholeData)
                is Comment -> out.append("<!--").append(node.data).append("-->")
                is Element -> {
                    val tag = if (isXml) node.tagName() else node.tagName().lowercase(Locale.ROOT)
                    out.append('<').append(tag)
                    for (a in node.attributes()) {
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
                        if (node.childNodeSize() == 0) {
                            out.append("/>")
                        } else {
                            out.append('>')
                            serializeChildren(node, true, out)
                            out.append("</").append(tag).append('>')
                        }
                    } else {
                        out.append('>')
                        if (tag !in HTML_VOID_TAGS) {
                            serializeChildren(node, false, out)
                            out.append("</").append(tag).append('>')
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

        private fun collectXmlText(node: Node, out: StringBuilder) {
            when (node) {
                is CDataNode -> out.append(node.text())
                is TextNode -> out.append(node.wholeText)
                is Element -> {
                    for (child in node.childNodes()) {
                        collectXmlText(child, out)
                    }
                }
            }
        }

        private fun collectHtmlText(node: Node, out: StringBuilder) {
            when (node) {
                is CDataNode -> out.append(node.text())
                is TextNode -> out.append(node.wholeText)
                is Document -> {
                    for (child in node.childNodes()) {
                        collectHtmlText(child, out)
                    }
                }
                is Element -> {
                    val tag = node.tagName().lowercase(Locale.ROOT)
                    if (tag in HTML_SKIP_TEXT_TAGS) return
                    val isBlock = tag in HTML_BLOCK_TAGS
                    if (isBlock) out.append(' ')
                    for (child in node.childNodes()) {
                        collectHtmlText(child, out)
                    }
                    if (isBlock) out.append(' ')
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
 * Eliminates exponential backtracking on chained descendant (` `) and general
 * sibling (`~`) combinators via per-`(Element, step)` DP tables, eliminates
 * O(N^2) sibling scans via lazy O(1) parent sibling-index tables, enforces a
 * strict step budget ([PluginDomParser.MAX_SELECTOR_STEPS]), and checks thread
 * interruption / call deadline periodically.
 */
internal object CssSelectorEngine {

    private const val MAX_PSEUDO_NESTING = 8
    private const val MEMO_UNCOMPUTED: Byte = 0
    private const val MEMO_FALSE: Byte = 1
    private const val MEMO_TRUE: Byte = 2

    internal enum class Combinator { DESCENDANT, CHILD, ADJACENT_SIBLING, GENERAL_SIBLING }
    internal enum class AttrOp { EXISTS, EQUALS, INCLUDES, DASH_MATCH, PREFIX, SUFFIX, SUBSTRING }

    internal data class AttrMatcher(
        val name: String,
        val op: AttrOp,
        val value: String?,
        val caseFlag: Char?,
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
    )

    private class SiblingTable(
        val totalChildren: Int,
        val childIndex1Based: IdentityHashMap<Element, Int>,
        val typeIndex1Based: IdentityHashMap<Element, Int>,
        val typeTotal: IdentityHashMap<Element, Int>,
        val prevSibling: IdentityHashMap<Element, Element?>,
    )

    private class SelectorMemo(val stepCount: Int) {
        val matchStep = IdentityHashMap<Element, ByteArray>()
        val anyAncestor = IdentityHashMap<Element, ByteArray>()
        val anyPrevSibling = IdentityHashMap<Element, ByteArray>()

        fun getRow(map: IdentityHashMap<Element, ByteArray>, el: Element): ByteArray =
            map.getOrPut(el) { ByteArray(stepCount) }
    }

    private class QueryContext(
        val isXml: Boolean,
        val maxSteps: Int,
        val deadlineNanos: Long,
    ) {
        private var steps: Int = 0
        private val memos = IdentityHashMap<ComplexSelector, SelectorMemo>()
        private val siblingTables = IdentityHashMap<Element, SiblingTable>()

        fun tick() {
            val s = ++steps
            if (s > maxSteps) {
                throw DomLimitExceededException("CSS 选择器匹配步数超过上限（最多 $maxSteps 步）")
            }
            if ((s and 1023) == 0) {
                if (Thread.currentThread().isInterrupted) {
                    throw DomTimeoutException("CSS 选择器执行已中断")
                }
                if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() > deadlineNanos) {
                    throw DomTimeoutException("CSS 选择器执行超时")
                }
            }
        }

        fun memoFor(sel: ComplexSelector): SelectorMemo =
            memos.getOrPut(sel) { SelectorMemo(sel.compounds.size) }

        fun siblingTableFor(parent: Element): SiblingTable {
            siblingTables[parent]?.let { return it }
            val children = parent.children()
            val n = children.size
            val childIdx = IdentityHashMap<Element, Int>(n)
            val typeIdx = IdentityHashMap<Element, Int>(n)
            val typeCounts = HashMap<String, Int>()
            val tags = ArrayList<String>(n)
            val prevSib = IdentityHashMap<Element, Element?>(n)
            var prev: Element? = null
            for (i in 0 until n) {
                val c = children[i]
                tick()
                childIdx[c] = i + 1
                prevSib[c] = prev
                prev = c
                val tag = if (isXml) c.tagName() else c.tagName().lowercase(Locale.ROOT)
                val nextTypeCount = (typeCounts[tag] ?: 0) + 1
                typeCounts[tag] = nextTypeCount
                typeIdx[c] = nextTypeCount
                tags.add(tag)
            }
            val typeTot = IdentityHashMap<Element, Int>(n)
            for (i in 0 until n) {
                typeTot[children[i]] = typeCounts[tags[i]]!!
            }
            val table = SiblingTable(n, childIdx, typeIdx, typeTot, prevSib)
            siblingTables[parent] = table
            return table
        }

        fun prevElementSiblingFast(el: Element): Element? {
            val p = el.parent() ?: return null
            return siblingTableFor(p).prevSibling[el]
        }

        fun matchesAny(el: Element, selectors: List<ComplexSelector>): Boolean {
            for (sel in selectors) {
                if (evalMatchStep(el, sel, sel.compounds.size - 1)) return true
            }
            return false
        }

        private fun evalMatchStep(el: Element, sel: ComplexSelector, idx: Int): Boolean {
            val memo = memoFor(sel)
            val row = memo.getRow(memo.matchStep, el)
            val cached = row[idx]
            if (cached != MEMO_UNCOMPUTED) return cached == MEMO_TRUE

            tick()
            val result = if (!matchesCompound(el, sel.compounds[idx])) {
                false
            } else if (idx == 0) {
                true
            } else {
                when (sel.combinators[idx - 1]) {
                    Combinator.CHILD -> {
                        val p = el.parent()
                        p != null && p !is Document && evalMatchStep(p, sel, idx - 1)
                    }
                    Combinator.DESCENDANT -> evalAnyAncestorMatches(el, sel, idx - 1)
                    Combinator.ADJACENT_SIBLING -> {
                        val prev = prevElementSiblingFast(el)
                        prev != null && evalMatchStep(prev, sel, idx - 1)
                    }
                    Combinator.GENERAL_SIBLING -> evalAnyPrevSiblingMatches(el, sel, idx - 1)
                }
            }
            row[idx] = if (result) MEMO_TRUE else MEMO_FALSE
            return result
        }

        /**
         * Iterative O(1)-amortized DP for descendant combinator:
         * `anyAnc(el, idx) = matchStep(parent, idx) || anyAnc(parent, idx)`.
         */
        private fun evalAnyAncestorMatches(el: Element, sel: ComplexSelector, idx: Int): Boolean {
            val memo = memoFor(sel)
            val chain = ArrayList<Element>()
            var cur: Element = el
            var baseResult = false
            while (true) {
                val ancRow = memo.getRow(memo.anyAncestor, cur)
                val cached = ancRow[idx]
                if (cached != MEMO_UNCOMPUTED) {
                    baseResult = cached == MEMO_TRUE
                    break
                }
                tick()
                val p = cur.parent()
                if (p == null || p is Document) {
                    ancRow[idx] = MEMO_FALSE
                    baseResult = false
                    break
                }
                if (evalMatchStep(p, sel, idx)) {
                    ancRow[idx] = MEMO_TRUE
                    baseResult = true
                    break
                }
                chain.add(cur)
                cur = p
            }
            val fill = if (baseResult) MEMO_TRUE else MEMO_FALSE
            for (i in chain.size - 1 downTo 0) {
                memo.getRow(memo.anyAncestor, chain[i])[idx] = fill
            }
            return baseResult
        }

        /**
         * Iterative O(1)-amortized DP for general sibling combinator (`~`):
         * `anyPrev(el, idx) = matchStep(prevSib, idx) || anyPrev(prevSib, idx)`.
         */
        private fun evalAnyPrevSiblingMatches(el: Element, sel: ComplexSelector, idx: Int): Boolean {
            val memo = memoFor(sel)
            val chain = ArrayList<Element>()
            var cur: Element = el
            var baseResult = false
            while (true) {
                val sibRow = memo.getRow(memo.anyPrevSibling, cur)
                val cached = sibRow[idx]
                if (cached != MEMO_UNCOMPUTED) {
                    baseResult = cached == MEMO_TRUE
                    break
                }
                tick()
                val prev = prevElementSiblingFast(cur)
                if (prev == null) {
                    sibRow[idx] = MEMO_FALSE
                    baseResult = false
                    break
                }
                if (evalMatchStep(prev, sel, idx)) {
                    sibRow[idx] = MEMO_TRUE
                    baseResult = true
                    break
                }
                chain.add(cur)
                cur = prev
            }
            val fill = if (baseResult) MEMO_TRUE else MEMO_FALSE
            for (i in chain.size - 1 downTo 0) {
                memo.getRow(memo.anyPrevSibling, chain[i])[idx] = fill
            }
            return baseResult
        }

        private fun matchesCompound(el: Element, comp: CompoundSelector): Boolean {
            if (el is Document) return false
            if (comp.nsPrefix != null || comp.tagName != null) {
                if (!matchesTag(el.tagName(), comp.nsPrefix, comp.tagName, isXml)) return false
            }
            for (id in comp.ids) {
                val actual = PluginDomParser.getElementAttr(el, "id", isXml) ?: return false
                if (actual != id) return false
            }
            if (comp.classes.isNotEmpty()) {
                val clsAttr = PluginDomParser.getElementAttr(el, "class", isXml) ?: return false
                val tokens = clsAttr.trim().split(ASCII_WS_REGEX).filter { it.isNotEmpty() }
                for (c in comp.classes) {
                    if (c !in tokens) return false
                }
            }
            for (am in comp.attrs) {
                if (!matchesAttr(el, am, isXml)) return false
            }
            for (pm in comp.pseudos) {
                if (!matchesPseudo(el, pm)) return false
            }
            return true
        }

        private fun matchesPseudo(el: Element, pm: PseudoMatcher): Boolean = when (pm) {
            PseudoMatcher.FirstChild -> {
                val p = el.parent() ?: return false
                siblingTableFor(p).childIndex1Based[el] == 1
            }
            PseudoMatcher.LastChild -> {
                val p = el.parent() ?: return false
                val t = siblingTableFor(p)
                t.childIndex1Based[el] == t.totalChildren
            }
            PseudoMatcher.OnlyChild -> {
                val p = el.parent() ?: return false
                siblingTableFor(p).totalChildren == 1
            }
            PseudoMatcher.FirstOfType -> {
                val p = el.parent() ?: return false
                siblingTableFor(p).typeIndex1Based[el] == 1
            }
            PseudoMatcher.LastOfType -> {
                val p = el.parent() ?: return false
                val t = siblingTableFor(p)
                t.typeIndex1Based[el] == t.typeTotal[el]
            }
            PseudoMatcher.OnlyOfType -> {
                val p = el.parent() ?: return false
                siblingTableFor(p).typeTotal[el] == 1
            }
            PseudoMatcher.Empty -> el.childNodes().none { n ->
                n is Element || (n is TextNode && n.wholeText.isNotEmpty()) || (n is CDataNode && n.text().isNotEmpty())
            }
            PseudoMatcher.Root -> el.parent() is Document
            is PseudoMatcher.NthChild -> {
                val p = el.parent() ?: return false
                val t = siblingTableFor(p)
                val idx = if (pm.ofType) {
                    val pos = t.typeIndex1Based[el] ?: return false
                    val tot = t.typeTotal[el] ?: return false
                    if (pm.fromEnd) (tot - pos + 1) else pos
                } else {
                    val pos = t.childIndex1Based[el] ?: return false
                    if (pm.fromEnd) (t.totalChildren - pos + 1) else pos
                }
                matchesAnPlusB(idx, pm.a, pm.b)
            }
            is PseudoMatcher.Not -> !matchesAny(el, pm.inner)
            is PseudoMatcher.Is -> matchesAny(el, pm.inner)
        }
    }

    private val ASCII_WS_REGEX = Regex("[ \\t\\n\\r\\f]+")

    fun parse(rawSelector: String): List<ComplexSelector> {
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
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Element? {
        val ctx = QueryContext(isXml, maxSteps, deadlineNanos)
        val stack = ArrayDeque<Element>()
        val topChildren = scopeRoot.children()
        for (i in topChildren.size - 1 downTo 0) {
            stack.addLast(topChildren[i])
        }
        while (stack.isNotEmpty()) {
            ctx.tick()
            val el = stack.removeLast()
            if (ctx.matchesAny(el, selectors)) return el
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
        deadlineNanos: Long = Long.MAX_VALUE,
    ): List<Element> {
        val ctx = QueryContext(isXml, maxSteps, deadlineNanos)
        val out = ArrayList<Element>()
        val stack = ArrayDeque<Element>()
        val topChildren = scopeRoot.children()
        for (i in topChildren.size - 1 downTo 0) {
            stack.addLast(topChildren[i])
        }
        while (stack.isNotEmpty()) {
            ctx.tick()
            val el = stack.removeLast()
            if (ctx.matchesAny(el, selectors)) {
                out.add(el)
            }
            val ch = el.children()
            for (i in ch.size - 1 downTo 0) {
                stack.addLast(ch[i])
            }
        }
        return out
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

    private fun matchesAttr(el: Element, am: AttrMatcher, isXml: Boolean): Boolean {
        val actual = PluginDomParser.getElementAttr(el, am.name, isXml) ?: return false
        if (am.op == AttrOp.EXISTS) return true
        val expected = am.value ?: ""
        val ci = am.caseFlag == 'i'
        val a = if (ci) actual.lowercase(Locale.ROOT) else actual
        val e = if (ci) expected.lowercase(Locale.ROOT) else expected
        return when (am.op) {
            AttrOp.EXISTS -> true
            AttrOp.EQUALS -> a == e
            AttrOp.INCLUDES -> e.isNotEmpty() && !e.any { it.isWhitespace() } &&
                a.trim().split(ASCII_WS_REGEX).any { it == e }
            AttrOp.DASH_MATCH -> a == e || a.startsWith("$e-")
            AttrOp.PREFIX -> e.isNotEmpty() && a.startsWith(e)
            AttrOp.SUFFIX -> e.isNotEmpty() && a.endsWith(e)
            AttrOp.SUBSTRING -> e.isNotEmpty() && a.contains(e)
        }
    }

    private fun matchesAnPlusB(index1Based: Int, a: Int, b: Int): Boolean {
        if (a == 0) return index1Based == b
        val diff = index1Based - b
        if (diff % a != 0) return false
        return (diff / a) >= 0
    }

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
                combinators.add(comb)
                compounds.add(parseCompoundSelector())
            }
            return ComplexSelector(compounds.toTypedArray(), combinators.toTypedArray())
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
                return AttrMatcher(name, AttrOp.EXISTS, null, null)
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
            return AttrMatcher(name, op, value, flag)
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
                    if (nestingDepth >= MAX_PSEUDO_NESTING) {
                        throw IllegalArgumentException("CSS 伪类嵌套层数超过上限（最多 $MAX_PSEUDO_NESTING 层）")
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
                pos += 1
            }
            throw IllegalArgumentException("CSS 伪类缺少右括号：'$src'")
        }

        private fun parseAnPlusB(raw: String): Pair<Int, Int> {
            val s = raw.replace(Regex("\\s+"), "").lowercase(Locale.ROOT)
            if (s == "odd") return 2 to 1
            if (s == "even") return 2 to 0
            val nIdx = s.indexOf('n')
            if (nIdx < 0) {
                val b = s.toIntOrNull()
                    ?: throw IllegalArgumentException("无效的 :nth-* 参数：$raw")
                return 0 to b
            }
            val aPart = s.substring(0, nIdx)
            val a = when (aPart) {
                "", "+" -> 1
                "-" -> -1
                else -> aPart.toIntOrNull()
                    ?: throw IllegalArgumentException("无效的 :nth-* 参数：$raw")
            }
            val bPart = s.substring(nIdx + 1)
            val b = if (bPart.isEmpty()) {
                0
            } else {
                bPart.toIntOrNull()
                    ?: throw IllegalArgumentException("无效的 :nth-* 参数：$raw")
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
