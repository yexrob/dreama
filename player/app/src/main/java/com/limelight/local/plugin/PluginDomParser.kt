package com.limelight.local.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.ParseSettings
import org.jsoup.parser.Parser
import org.jsoup.parser.StreamParser
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DomLimitExceededException(message: String) : IllegalStateException(message)

/**
 * Lazy handle-backed HTML/XML DOM parser for `ctx.parse.html(text)` and
 * `ctx.parse.xml(text)` (§5, §11.1, S4):
 * - Capped at 8 parsed documents per invocation, 4 MB per document, and
 *   [MAX_TOTAL_NODES] (200,000) total element nodes across the invocation
 * - Counts elements DURING parsing via Jsoup [StreamParser] and aborts
 *   immediately on overflow so HTML5 formatting-element reconstruction bombs
 *   cannot amplify memory before the cap trips
 * - Unified semantics with `tools/plugin-kit/src/dom.js`:
 *   - Document root always reports `tag == "#document"`
 *   - `querySelector` / `selectFirst` and `querySelectorAll` / `select` NEVER
 *     match the receiver element itself (browser-standard descendant scoping)
 *   - HTML tags & attribute names are case-insensitive; XML tags & attribute
 *     names are strictly case-sensitive
 *   - Supports namespace-prefixed selectors (`dvb|x`, `*|x`, `|x`)
 *   - Rejects Jsoup-proprietary pseudo-classes (`:eq()`, `:contains()`, etc.)
 *   - `text` skips `<script>`/`<style>` in HTML, uses identical block spacing,
 *     collapses only ASCII whitespace + U+00A0 (preserving Unicode spaces such
 *     as U+2003), and preserves undefined HTML entities (e.g. `&eacute;`) in XML
 *   - `attr(name)` returns `null` when absent and `""` for present boolean attrs
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
        val parser = Parser.htmlParser()
            .settings(ParseSettings.htmlDefault)
            .setMaxDepth(MAX_NESTING_DEPTH)
        val (doc, count) = parseWithStreamingNodeLimit(html, parser)
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
        val (doc, count) = parseWithStreamingNodeLimit(sanitized, parser)
        docCount.incrementAndGet()
        totalNodes.addAndGet(count)
        return register(doc, isXml = true)
    }

    fun selectFirstId(nodeId: Int, selector: String): Int {
        val entry = nodes[nodeId] ?: return 0
        val parsed = CssSelectorEngine.parse(selector)
        val found = CssSelectorEngine.queryFirst(entry.el, parsed, entry.isXml) ?: return 0
        return register(found, entry.isXml)
    }

    fun selectAllIds(nodeId: Int, selector: String): JsonArray {
        val entry = nodes[nodeId] ?: return JsonArray(emptyList())
        val parsed = CssSelectorEngine.parse(selector)
        val found = CssSelectorEngine.queryAll(entry.el, parsed, entry.isXml)
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

    fun html(nodeId: Int): String = nodes[nodeId]?.el?.html() ?: ""
    fun outerHtml(nodeId: Int): String = nodes[nodeId]?.el?.outerHtml() ?: ""

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
     * Parses [input] incrementally via Jsoup [StreamParser], counting elements
     * as they are built and aborting IMMEDIATELY via `stop()` once the element
     * cap is exceeded (preventing HTML5 active-formatting-element amplification
     * from exhausting host heap before parsing finishes).
     */
    private fun parseWithStreamingNodeLimit(input: String, parser: Parser): Pair<Document, Int> {
        val remaining = maxTotalNodes - totalNodes.get()
        if (remaining <= 0) {
            throw DomLimitExceededException("单次调用解析的 DOM 节点总数超过上限（最多 $maxTotalNodes 个）")
        }
        StreamParser(parser).use { sp ->
            sp.parse(input, "")
            var count = 0
            val it = sp.iterator()
            while (it.hasNext()) {
                it.next()
                count += 1
                if (count > remaining) {
                    sp.stop()
                    throw DomLimitExceededException(
                        "单次调用解析的 DOM 节点总数超过上限（最多 $maxTotalNodes 个）",
                    )
                }
            }
            val doc = sp.document()
            return doc to count.coerceAtLeast(1)
        }
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
        private const val MAX_NESTING_DEPTH = 512

        private val XML_BUILTIN_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

        private val HTML_SKIP_TEXT_TAGS = setOf("script", "style")

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

        /**
         * Collapses ONLY ASCII whitespace (`[ \t\n\r\f]`) and NBSP (`\u00A0`)
         * into a single ASCII space and trims leading/trailing ASCII/NBSP spaces,
         * preserving Unicode whitespace such as U+2003 (EM SPACE) identically to
         * `tools/plugin-kit/src/dom.js`.
         */
        internal fun collapseAsciiAndNbspWhitespace(raw: String): String {
            val sb = StringBuilder(raw.length)
            var inSpace = true // trim leading
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

        /**
         * Preserves undefined named entities (such as HTML `&eacute;` or custom
         * `&e;`) literally in XML mode by escaping `&name;` to `&amp;name;` outside
         * CDATA, comments, processing instructions, and DOCTYPE declarations.
         * Built-in XML entities (`amp`, `lt`, `gt`, `quot`, `apos`) and numeric
         * references (`&#...;`, `&#x...;`) are untouched.
         */
        internal fun escapeUndefinedXmlEntities(xml: String): String {
            if (!xml.contains('&')) return xml
            val len = xml.length
            val out = StringBuilder(len + 32)
            var i = 0
            while (i < len) {
                if (xml[i] == '<') {
                    // Skip CDATA, comments, processing instructions, and DOCTYPE intact
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
 * Deterministic, shared-subset CSS selector parser & evaluator used by
 * [PluginDomParser] (mirrored identically in `tools/plugin-kit/src/dom.js`).
 *
 * Supported subset (documented in `docs/plugin-dev.md`):
 * - Selector lists (`A, B`) and combinators (`A B`, `A > B`, `A + B`, `A ~ B`)
 * - Type (`div`, `Representation`), universal (`*`), and namespace-prefixed
 *   type selectors (`dvb|x`, `*|x`, `|x`)
 * - `#id`, `.class`
 * - Attribute selectors (`[attr]`, `=`, `~=`, `|=`, `^=`, `$=`, `*=`, optional `i`/`s` flags)
 * - Standard CSS pseudo-classes: `:first-child`, `:last-child`, `:only-child`,
 *   `:nth-child(...)`, `:nth-last-child(...)`, `:first-of-type`, `:last-of-type`,
 *   `:only-of-type`, `:nth-of-type(...)`, `:nth-last-of-type(...)`,
 *   `:empty`, `:root`, `:not(...)`, `:is(...)`
 *
 * Explicitly rejects Jsoup-proprietary pseudo-classes (`:eq()`, `:lt()`, `:gt()`,
 * `:contains()`, `:matches()`, etc.) so plugins behave identically on Android
 * and `d3plugin`.
 */
internal object CssSelectorEngine {

    internal enum class Combinator { DESCENDANT, CHILD, ADJACENT_SIBLING, GENERAL_SIBLING }
    internal enum class AttrOp { EXISTS, EQUALS, INCLUDES, DASH_MATCH, PREFIX, SUFFIX, SUBSTRING }

    internal data class AttrMatcher(
        val name: String,
        val op: AttrOp,
        val value: String?,
        val caseFlag: Char?, // 'i', 's', or null
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
        val nsPrefix: String?, // null = any/unprefixed, "*" = any ns, "" = no ns, "dvb" = prefix
        val tagName: String?, // null = "*" (any)
        val ids: List<String>,
        val classes: List<String>,
        val attrs: List<AttrMatcher>,
        val pseudos: List<PseudoMatcher>,
    )

    internal data class ComplexSelector(
        val head: CompoundSelector,
        val steps: List<Pair<Combinator, CompoundSelector>>,
    )

    fun parse(rawSelector: String): List<ComplexSelector> {
        val p = SelectorParser(rawSelector)
        val list = p.parseSelectorList(eofExpected = true)
        if (list.isEmpty()) throw IllegalArgumentException("无效的 CSS 选择器：$rawSelector")
        return list
    }

    fun queryFirst(scopeRoot: Element, selectors: List<ComplexSelector>, isXml: Boolean): Element? {
        val docRoot = findDocumentRoot(scopeRoot)
        val stack = ArrayDeque<Element>()
        val topChildren = scopeRoot.children()
        for (i in topChildren.size - 1 downTo 0) {
            stack.addLast(topChildren[i])
        }
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            if (matchesAny(el, docRoot, selectors, isXml)) return el
            val ch = el.children()
            for (i in ch.size - 1 downTo 0) {
                stack.addLast(ch[i])
            }
        }
        return null
    }

    fun queryAll(scopeRoot: Element, selectors: List<ComplexSelector>, isXml: Boolean): List<Element> {
        val docRoot = findDocumentRoot(scopeRoot)
        val out = ArrayList<Element>()
        val stack = ArrayDeque<Element>()
        val topChildren = scopeRoot.children()
        for (i in topChildren.size - 1 downTo 0) {
            stack.addLast(topChildren[i])
        }
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            if (matchesAny(el, docRoot, selectors, isXml)) {
                out.add(el)
            }
            val ch = el.children()
            for (i in ch.size - 1 downTo 0) {
                stack.addLast(ch[i])
            }
        }
        return out
    }

    private fun findDocumentRoot(el: Element): Element {
        var cur: Element = el
        while (cur.parent() != null) {
            cur = cur.parent()!!
        }
        return cur
    }

    private fun matchesAny(
        el: Element,
        docRoot: Element,
        selectors: List<ComplexSelector>,
        isXml: Boolean,
    ): Boolean = selectors.any { matchesComplex(el, docRoot, it, isXml) }

    private fun matchesComplex(
        el: Element,
        docRoot: Element,
        sel: ComplexSelector,
        isXml: Boolean,
    ): Boolean {
        // Match right-to-left: last compound matches el, then follow combinators
        val compounds = ArrayList<CompoundSelector>(sel.steps.size + 1)
        val combinators = ArrayList<Combinator>(sel.steps.size)
        compounds.add(sel.head)
        for ((comb, comp) in sel.steps) {
            combinators.add(comb)
            compounds.add(comp)
        }
        return matchesStep(el, docRoot, compounds, combinators, compounds.size - 1, isXml)
    }

    private fun matchesStep(
        el: Element,
        docRoot: Element,
        compounds: List<CompoundSelector>,
        combinators: List<Combinator>,
        idx: Int,
        isXml: Boolean,
    ): Boolean {
        if (!matchesCompound(el, docRoot, compounds[idx], isXml)) return false
        if (idx == 0) return true
        val comb = combinators[idx - 1]
        return when (comb) {
            Combinator.CHILD -> {
                val p = el.parent()
                p != null && p !is Document && matchesStep(p, docRoot, compounds, combinators, idx - 1, isXml)
            }
            Combinator.DESCENDANT -> {
                var p = el.parent()
                while (p != null && p !is Document) {
                    if (matchesStep(p, docRoot, compounds, combinators, idx - 1, isXml)) return true
                    p = p.parent()
                }
                false
            }
            Combinator.ADJACENT_SIBLING -> {
                val prev = el.previousElementSibling()
                prev != null && matchesStep(prev, docRoot, compounds, combinators, idx - 1, isXml)
            }
            Combinator.GENERAL_SIBLING -> {
                var prev = el.previousElementSibling()
                while (prev != null) {
                    if (matchesStep(prev, docRoot, compounds, combinators, idx - 1, isXml)) return true
                    prev = prev.previousElementSibling()
                }
                false
            }
        }
    }

    private fun matchesCompound(
        el: Element,
        docRoot: Element,
        comp: CompoundSelector,
        isXml: Boolean,
    ): Boolean {
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
            val tokens = clsAttr.trim().split(Regex("[ \\t\\n\\r\\f]+")).filter { it.isNotEmpty() }
            for (c in comp.classes) {
                if (c !in tokens) return false
            }
        }
        for (am in comp.attrs) {
            if (!matchesAttr(el, am, isXml)) return false
        }
        for (pm in comp.pseudos) {
            if (!matchesPseudo(el, docRoot, pm, isXml)) return false
        }
        return true
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
                a.trim().split(Regex("[ \\t\\n\\r\\f]+")).any { it == e }
            AttrOp.DASH_MATCH -> a == e || a.startsWith("$e-")
            AttrOp.PREFIX -> e.isNotEmpty() && a.startsWith(e)
            AttrOp.SUFFIX -> e.isNotEmpty() && a.endsWith(e)
            AttrOp.SUBSTRING -> e.isNotEmpty() && a.contains(e)
        }
    }

    private fun matchesPseudo(
        el: Element,
        docRoot: Element,
        pm: PseudoMatcher,
        isXml: Boolean,
    ): Boolean = when (pm) {
        PseudoMatcher.FirstChild -> childIndex(el, fromEnd = false, ofType = false, isXml = isXml) == 1
        PseudoMatcher.LastChild -> childIndex(el, fromEnd = true, ofType = false, isXml = isXml) == 1
        PseudoMatcher.OnlyChild -> {
            val sibs = parentElementChildren(el)
            sibs != null && sibs.size == 1
        }
        PseudoMatcher.FirstOfType -> childIndex(el, fromEnd = false, ofType = true, isXml = isXml) == 1
        PseudoMatcher.LastOfType -> childIndex(el, fromEnd = true, ofType = true, isXml = isXml) == 1
        PseudoMatcher.OnlyOfType -> {
            val sibs = sameTypeSiblings(el, isXml)
            sibs != null && sibs.size == 1
        }
        PseudoMatcher.Empty -> el.childNodes().none { n ->
            n is Element || (n is TextNode && n.wholeText.isNotEmpty()) || (n is CDataNode && n.text().isNotEmpty())
        }
        PseudoMatcher.Root -> el.parent() is Document
        is PseudoMatcher.NthChild -> {
            val idx = childIndex(el, pm.fromEnd, pm.ofType, isXml)
            idx > 0 && matchesAnPlusB(idx, pm.a, pm.b)
        }
        is PseudoMatcher.Not -> !matchesAny(el, docRoot, pm.inner, isXml)
        is PseudoMatcher.Is -> matchesAny(el, docRoot, pm.inner, isXml)
    }

    private fun parentElementChildren(el: Element): List<Element>? = el.parent()?.children()

    private fun sameTypeSiblings(el: Element, isXml: Boolean): List<Element>? {
        val sibs = parentElementChildren(el) ?: return null
        val myTag = if (isXml) el.tagName() else el.tagName().lowercase(Locale.ROOT)
        return sibs.filter { s ->
            val t = if (isXml) s.tagName() else s.tagName().lowercase(Locale.ROOT)
            t == myTag
        }
    }

    private fun childIndex(el: Element, fromEnd: Boolean, ofType: Boolean, isXml: Boolean): Int {
        val list = if (ofType) sameTypeSiblings(el, isXml) else parentElementChildren(el)
        if (list == null) return -1
        val pos = list.indexOfFirst { it === el }
        if (pos < 0) return -1
        return if (fromEnd) list.size - pos else pos + 1
    }

    private fun matchesAnPlusB(index1Based: Int, a: Int, b: Int): Boolean {
        if (a == 0) return index1Based == b
        val diff = index1Based - b
        if (diff % a != 0) return false
        return (diff / a) >= 0
    }

    private class SelectorParser(private val src: String) {
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
            val head = parseCompoundSelector()
            val steps = ArrayList<Pair<Combinator, CompoundSelector>>()
            while (true) {
                val hadWs = skipWs()
                if (eof() || peek() == ',' || peek() == ')') break
                val comb = when (peek()) {
                    '>' -> { pos += 1; skipWs(); Combinator.CHILD }
                    '+' -> { pos += 1; skipWs(); Combinator.ADJACENT_SIBLING }
                    '~' -> { pos += 1; skipWs(); Combinator.GENERAL_SIBLING }
                    else -> if (hadWs) Combinator.DESCENDANT else break
                }
                steps.add(comb to parseCompoundSelector())
            }
            return ComplexSelector(head, steps)
        }

        private fun parseCompoundSelector(): CompoundSelector {
            var nsPrefix: String? = null
            var tagName: String? = null
            val ids = ArrayList<String>()
            val classes = ArrayList<String>()
            val attrs = ArrayList<AttrMatcher>()
            val pseudos = ArrayList<PseudoMatcher>()
            var matchedAny = false

            // Optional type / universal / namespace-prefixed type selector
            if (!eof()) {
                val c = peek()
                if (c == '|') {
                    // |local or |*
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

            // Simple selector sequence (#id, .cls, [attr...], :pseudo)
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
            pos += 1 // '['
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
            pos += 1 // ':'
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
            pos += 1 // '('
            return when (name) {
                "not" -> {
                    val inner = parseSelectorList(eofExpected = false)
                    skipWs()
                    if (peek() != ')') throw IllegalArgumentException(":not() 缺少右括号：'$src'")
                    pos += 1
                    PseudoMatcher.Not(inner)
                }
                "is" -> {
                    val inner = parseSelectorList(eofExpected = false)
                    skipWs()
                    if (peek() != ')') throw IllegalArgumentException(":is() 缺少右括号：'$src'")
                    pos += 1
                    PseudoMatcher.Is(inner)
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
