package com.limelight.local.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.nodes.XmlDeclaration
import org.jsoup.parser.ParseSettings
import org.jsoup.parser.Parser
import org.jsoup.parser.StreamParser
import java.io.Reader
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class DomLimitExceededException(message: String) : IllegalStateException(message)
class DomTimeoutException(message: String) : IllegalStateException(message)

/**
 * Lazy handle-backed HTML/XML DOM parser for `ctx.parse.html(text)` and
 * `ctx.parse.xml(text)` (§5, §11.1, S4):
 *
 * Byte-budgeted DOM memory accounting (calibrated against real JVM retained heap):
 * - Max input text: [MAX_DOC_BYTES] (2 MB UTF-8)
 * - Max single document estimated memory: [MAX_DOC_MEMORY_BYTES] (24 MB)
 * - Max VM-wide live DOM memory (all live docs + handle table + active class cache):
 *   [MAX_VM_MEMORY_BYTES] (48 MB)
 * - Max simultaneously live documents per VM: [MAX_LIVE_DOCS] (8)
 * - Mid-parse streaming byte accounting counts ALL node kinds (Elements, TextNodes,
 *   Comments, PIs/XmlDeclarations, Doctypes, CDATA, DataNodes, Attributes) via
 *   [StreamParser] + chunked reader inspection, aborting immediately on overflow,
 *   followed by an exact full-tree byte recount
 * - Per-document deduplicated monotonic `Long` handle registry (never reset on [clear]),
 *   charged into the 48 MB VM budget and freed immediately via [closeDoc] or [clear]
 * - Bounded [MAX_OUTPUT_BYTES] (8 MB) streaming serialization for [html], [outerHtml], [text]
 * - O(N * S) memoized CSS selector engine with hard syntax caps (4 KB text, 32 groups,
 *   32 compounds, nesting 4, value 256, `:nth-*` in `±1_000_000`), lazy 16 MB query DP
 *   memory budget, cached `:empty`, in-place short-class scanning (no false-positive cache
 *   overflow on 200k classed elements), budgeted loops everywhere (childNodes, attributes,
 *   class scanning, KMP attribute matching), and deadline checks at every entry point
 */
class PluginDomParser(
    private val maxDocsPerCall: Int = MAX_LIVE_DOCS,
    private val maxDocBytes: Int = MAX_DOC_BYTES,
    private val maxDocMemoryBytes: Long = MAX_DOC_MEMORY_BYTES,
    private val maxVmMemoryBytes: Long = MAX_VM_MEMORY_BYTES,
    private val maxSelectorSteps: Int = MAX_SELECTOR_STEPS,
    private val maxSelectorMemoryBytes: Int = MAX_SELECTOR_MEMORY_BYTES,
    private val maxOutputBytes: Int = MAX_OUTPUT_BYTES,
) {
    private class DocEntry(
        val docId: Long,
        val doc: Document,
        val isXml: Boolean,
        val elementCount: Int,
        val estimatedBytes: Long,
        val elementToHandleId: IdentityHashMap<Element, Long>,
        val handleIds: ArrayList<Long>,
    ) {
        var handleTableBytes: Long = HANDLE_ENTRY_BYTES
    }

    private data class NodeEntry(
        val docId: Long,
        val el: Element,
        val isXml: Boolean,
    )

    private val registryLock = Any()
    private val nextId = AtomicLong(1L)
    private val docs = HashMap<Long, DocEntry>()
    private val nodes = HashMap<Long, NodeEntry>()
    private var liveElements: Int = 0
    private var vmDocBytes: Long = 0L
    private var vmHandleBytes: Long = 0L

    @Volatile
    private var callDeadlineNanos: Long = Long.MAX_VALUE

    fun setCallDeadlineNanos(deadlineNanos: Long) {
        callDeadlineNanos = deadlineNanos
    }

    fun parseHtml(html: String): Long {
        checkDeadline(callDeadlineNanos)
        val budgetBytes = enforcePreParseLimits(html)
        val parser = Parser.htmlParser()
            .settings(ParseSettings.htmlDefault)
            .setMaxDepth(MAX_NESTING_DEPTH)
        val parsed = parseWithStreamingByteBudget(html, parser, budgetBytes)
        parsed.doc.outputSettings().prettyPrint(false)
        return registerDocument(
            doc = parsed.doc,
            isXml = false,
            elementCount = parsed.elementCount,
            estimatedBytes = parsed.estimatedBytes,
        )
    }

    fun parseXml(xml: String): Long {
        checkDeadline(callDeadlineNanos)
        val budgetBytes = enforcePreParseLimits(xml)
        val sanitized = escapeUndefinedXmlEntities(xml, callDeadlineNanos)
        val parser = Parser.xmlParser()
            .settings(ParseSettings.preserveCase)
            .setMaxDepth(MAX_NESTING_DEPTH)
        val parsed = parseWithStreamingByteBudget(sanitized, parser, budgetBytes)
        parsed.doc.outputSettings().prettyPrint(false)
        return registerDocument(
            doc = parsed.doc,
            isXml = true,
            elementCount = parsed.elementCount,
            estimatedBytes = parsed.estimatedBytes,
        )
    }

    fun closeDoc(docId: Long): Boolean = synchronized(registryLock) {
        val docEntry = docs.remove(docId) ?: return false
        for (hid in docEntry.handleIds) {
            nodes.remove(hid)
        }
        docEntry.handleIds.clear()
        docEntry.elementToHandleId.clear()
        liveElements = (liveElements - docEntry.elementCount).coerceAtLeast(0)
        vmDocBytes = (vmDocBytes - docEntry.estimatedBytes).coerceAtLeast(0L)
        vmHandleBytes = (vmHandleBytes - docEntry.handleTableBytes).coerceAtLeast(0L)
        true
    }

    fun selectFirstId(nodeId: Long, selector: String): Long {
        checkDeadline(callDeadlineNanos)
        val entry = lookupNode(nodeId) ?: return 0L
        val parsed = CssSelectorEngine.parse(selector)
        val remainingVmBytes = remainingVmMemoryBytes()
        val found = CssSelectorEngine.queryFirst(
            scopeRoot = entry.el,
            selectors = parsed,
            isXml = entry.isXml,
            maxSteps = maxSelectorSteps,
            maxMemoryBytes = maxSelectorMemoryBytes,
            maxClassCacheBytes = remainingVmBytes,
            deadlineNanos = callDeadlineNanos,
        ) ?: return 0L
        return registerElement(entry.docId, found)
    }

    fun selectAllIds(nodeId: Long, selector: String): JsonArray {
        checkDeadline(callDeadlineNanos)
        val entry = lookupNode(nodeId) ?: return JsonArray(emptyList())
        val parsed = CssSelectorEngine.parse(selector)
        val remainingVmBytes = remainingVmMemoryBytes()
        val found = CssSelectorEngine.queryAll(
            scopeRoot = entry.el,
            selectors = parsed,
            isXml = entry.isXml,
            maxSteps = maxSelectorSteps,
            maxMemoryBytes = maxSelectorMemoryBytes,
            maxClassCacheBytes = remainingVmBytes,
            deadlineNanos = callDeadlineNanos,
        )
        val deadline = callDeadlineNanos
        val ids = ArrayList<JsonPrimitive>(found.size)
        synchronized(registryLock) {
            val docEntry = docs[entry.docId] ?: return JsonArray(emptyList())
            var ops = 0
            for (child in found) {
                if ((++ops and 1023) == 0) checkDeadline(deadline)
                ids += JsonPrimitive(registerElementLocked(docEntry, child))
            }
        }
        return JsonArray(ids)
    }

    fun childrenIds(nodeId: Long): JsonArray {
        checkDeadline(callDeadlineNanos)
        val entry = lookupNode(nodeId) ?: return JsonArray(emptyList())
        val deadline = callDeadlineNanos
        val rawChildren = entry.el.childNodes()
        val elems = ArrayList<Element>()
        var ops = 0
        for (i in 0 until rawChildren.size) {
            if ((++ops and 1023) == 0) checkDeadline(deadline)
            val c = rawChildren[i]
            if (c is Element) elems.add(c)
        }
        val ids = ArrayList<JsonPrimitive>(elems.size)
        synchronized(registryLock) {
            val docEntry = docs[entry.docId] ?: return JsonArray(emptyList())
            for (child in elems) {
                if ((++ops and 1023) == 0) checkDeadline(deadline)
                ids += JsonPrimitive(registerElementLocked(docEntry, child))
            }
        }
        return JsonArray(ids)
    }

    fun tag(nodeId: Long): String {
        checkDeadline(callDeadlineNanos)
        val entry = lookupNode(nodeId) ?: return ""
        if (entry.el is Document) return "#document"
        val raw = entry.el.tagName()
        return if (entry.isXml) raw else raw.lowercase(Locale.ROOT)
    }

    fun text(nodeId: Long): String {
        checkDeadline(callDeadlineNanos)
        val entry = lookupNode(nodeId) ?: return ""
        val deadline = callDeadlineNanos
        val out = BoundedOutputBuffer(maxOutputBytes, deadline)
        if (entry.isXml) {
            collectXmlTextIterative(entry.el, out, deadline)
        } else {
            collectHtmlTextIterative(entry.el, out, deadline)
        }
        return collapseAsciiAndNbspWhitespace(out.toString(), maxOutputBytes, deadline)
    }

    fun html(nodeId: Long): String {
        checkDeadline(callDeadlineNanos)
        val entry = lookupNode(nodeId) ?: return ""
        val out = BoundedOutputBuffer(maxOutputBytes, callDeadlineNanos)
        serializeChildrenIterative(entry.el, entry.isXml, out, callDeadlineNanos)
        return out.toString()
    }

    fun outerHtml(nodeId: Long): String {
        checkDeadline(callDeadlineNanos)
        val entry = lookupNode(nodeId) ?: return ""
        val deadline = callDeadlineNanos
        val out = BoundedOutputBuffer(maxOutputBytes, deadline)
        if (entry.el is Document) {
            serializeChildrenIterative(entry.el, entry.isXml, out, deadline)
        } else {
            serializeNodeIterative(entry.el, entry.isXml, out, deadline)
        }
        return out.toString()
    }

    fun attr(nodeId: Long, name: String): String? {
        checkDeadline(callDeadlineNanos)
        val entry = lookupNode(nodeId) ?: return null
        if (entry.el is Document) return null
        return getElementAttrWithDeadline(entry.el, name, entry.isXml, callDeadlineNanos)
    }

    fun attrsJson(nodeId: Long): String {
        checkDeadline(callDeadlineNanos)
        val entry = lookupNode(nodeId) ?: return "{}"
        if (entry.el is Document) return "{}"
        val deadline = callDeadlineNanos
        var bytes = 2
        var ops = 0
        return buildJsonObject {
            for (a in entry.el.attributes()) {
                if ((++ops and 255) == 0) checkDeadline(deadline)
                val key = if (entry.isXml) a.key else a.key.lowercase(Locale.ROOT)
                bytes += utf8ByteLength(key) + utf8ByteLength(a.value) + 6
                if (bytes > maxOutputBytes) {
                    throw DomLimitExceededException("DOM 属性序列化输出超过上限（最多 ${maxOutputBytes / (1024 * 1024)} MB）")
                }
                put(key, a.value)
            }
        }.toString()
    }

    /**
     * Releases all live documents and handles.
     * Deliberately does NOT reset [nextId] so stale handles from earlier calls
     * can never collide with newly issued handle IDs.
     */
    fun clear() {
        synchronized(registryLock) {
            nodes.clear()
            docs.clear()
            liveElements = 0
            vmDocBytes = 0L
            vmHandleBytes = 0L
        }
        callDeadlineNanos = Long.MAX_VALUE
    }

    fun currentTotalNodes(): Int = synchronized(registryLock) { liveElements }

    fun currentLiveDocCount(): Int = synchronized(registryLock) { docs.size }

    fun currentHandleCount(): Int = synchronized(registryLock) { nodes.size }

    fun currentEstimatedDomBytes(): Long = synchronized(registryLock) { vmDocBytes }

    fun currentVmMemoryBytes(): Long = synchronized(registryLock) { vmDocBytes + vmHandleBytes }

    fun lastAllocatedHandleId(): Long = nextId.get() - 1L

    private fun remainingVmMemoryBytes(): Long = synchronized(registryLock) {
        (maxVmMemoryBytes - (vmDocBytes + vmHandleBytes)).coerceAtLeast(0L)
    }

    private fun allocateHandleId(): Long {
        val id = nextId.getAndIncrement()
        if (id <= 0L || id > MAX_SAFE_JS_HANDLE_ID) {
            throw DomLimitExceededException("DOM 句柄编号超过安全范围")
        }
        return id
    }

    private fun lookupNode(nodeId: Long): NodeEntry? = synchronized(registryLock) { nodes[nodeId] }

    private fun registerDocument(
        doc: Document,
        isXml: Boolean,
        elementCount: Int,
        estimatedBytes: Long,
    ): Long = synchronized(registryLock) {
        if (docs.size >= maxDocsPerCall) {
            throw DomLimitExceededException("同时存活的文档数超过上限（最多 $maxDocsPerCall 个）")
        }
        if (estimatedBytes > maxDocMemoryBytes) {
            throw DomLimitExceededException(
                "单个文档估算内存超过上限（${estimatedBytes / (1024 * 1024)} MB > ${maxDocMemoryBytes / (1024 * 1024)} MB）",
            )
        }
        val nextVmTotal = vmDocBytes + vmHandleBytes + estimatedBytes + HANDLE_ENTRY_BYTES
        if (nextVmTotal > maxVmMemoryBytes) {
            throw DomLimitExceededException(
                "VM 存活 DOM 总内存超过上限（最多 ${maxVmMemoryBytes / (1024 * 1024)} MB）",
            )
        }
        val docId = allocateHandleId()
        val initialCap = minOf(elementCount + 1, 1024).coerceAtLeast(16)
        val map = IdentityHashMap<Element, Long>(initialCap)
        val ids = ArrayList<Long>(initialCap)
        map[doc] = docId
        ids.add(docId)
        docs[docId] = DocEntry(
            docId = docId,
            doc = doc,
            isXml = isXml,
            elementCount = elementCount,
            estimatedBytes = estimatedBytes,
            elementToHandleId = map,
            handleIds = ids,
        )
        nodes[docId] = NodeEntry(docId, doc, isXml)
        liveElements += elementCount
        vmDocBytes += estimatedBytes
        vmHandleBytes += HANDLE_ENTRY_BYTES
        docId
    }

    private fun registerElement(docId: Long, el: Element): Long = synchronized(registryLock) {
        val docEntry = docs[docId] ?: return 0L
        registerElementLocked(docEntry, el)
    }

    private fun registerElementLocked(docEntry: DocEntry, el: Element): Long {
        docEntry.elementToHandleId[el]?.let { return it }
        val maxHandles = docEntry.elementCount + 1
        if (docEntry.elementToHandleId.size >= maxHandles) {
            throw DomLimitExceededException("单个文档句柄数超过上限（最多 $maxHandles 个）")
        }
        if (vmDocBytes + vmHandleBytes + HANDLE_ENTRY_BYTES > maxVmMemoryBytes) {
            throw DomLimitExceededException(
                "VM 存活 DOM 与句柄表总内存超过上限（最多 ${maxVmMemoryBytes / (1024 * 1024)} MB）",
            )
        }
        val id = allocateHandleId()
        docEntry.elementToHandleId[el] = id
        docEntry.handleIds.add(id)
        docEntry.handleTableBytes += HANDLE_ENTRY_BYTES
        vmHandleBytes += HANDLE_ENTRY_BYTES
        nodes[id] = NodeEntry(docEntry.docId, el, docEntry.isXml)
        return id
    }

    private fun enforcePreParseLimits(text: String): Long {
        val byteLen = utf8ByteLength(text)
        if (byteLen > maxDocBytes) {
            throw DomLimitExceededException(
                "待解析文档超过大小上限（最多 ${maxDocBytes / (1024 * 1024)} MB）",
            )
        }
        return synchronized(registryLock) {
            if (docs.size >= maxDocsPerCall) {
                throw DomLimitExceededException("同时存活的文档数超过上限（最多 $maxDocsPerCall 个）")
            }
            val vmRemaining = maxVmMemoryBytes - (vmDocBytes + vmHandleBytes) - HANDLE_ENTRY_BYTES
            if (vmRemaining < DOC_BASE_BYTES) {
                throw DomLimitExceededException(
                    "VM 存活 DOM 总内存超过上限（最多 ${maxVmMemoryBytes / (1024 * 1024)} MB）",
                )
            }
            minOf(maxDocMemoryBytes, vmRemaining)
        }
    }

    private data class ParsedDocStats(
        val doc: Document,
        val elementCount: Int,
        val estimatedBytes: Long,
    )

    /**
     * Chunked [Reader] feeding Jsoup [StreamParser] at most 4 KB at a time.
     * Between chunks, inspects newly appended child nodes along the active open
     * rightmost element chain (`doc -> ... -> openEl`) so non-element node floods
     * (`<?>`, `<!>`, `x<!>`, `<!--...-->`, XML PIs/CDATA) AND call deadlines abort
     * mid-parse BEFORE allocating unbounded heap.
     */
    private class BudgetCheckingReader(
        private val input: String,
        private val budgetBytes: Long,
        private val deadlineNanos: Long,
        private val liveState: LiveParseBudgetState,
    ) : Reader() {
        private var pos: Int = 0
        var docRef: Document? = null

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            try {
                checkDeadline(deadlineNanos)
                docRef?.let { liveState.inspectOpenRightmostChain(it, budgetBytes, deadlineNanos) }
            } catch (e: RuntimeException) {
                liveState.abortError = e
                throw e
            }
            if (pos >= input.length) return -1
            val n = minOf(len, CHUNK_CHARS, input.length - pos)
            input.toCharArray(cbuf, off, pos, pos + n)
            pos += n
            return n
        }

        override fun close() {}

        companion object {
            private const val CHUNK_CHARS = 4096
        }
    }

    private class LiveParseBudgetState {
        var emittedBytes: Long = DOC_BASE_BYTES
        var openChainNonElemBytes: Long = 0L
        var abortError: RuntimeException? = null
        val inspectedChildIndex = IdentityHashMap<Element, Int>()
        val lastElemChild = IdentityHashMap<Element, Element>()

        fun checkOverBudget(budgetBytes: Long) {
            if (emittedBytes + openChainNonElemBytes > budgetBytes) {
                throw DomLimitExceededException(
                    "解析中的 DOM 估算内存超过上限（上限 ${budgetBytes / (1024 * 1024)} MB）",
                )
            }
        }

        fun inspectOpenRightmostChain(doc: Document, budgetBytes: Long, deadlineNanos: Long) {
            var cur: Element = doc
            var depth = 0
            var ops = 0
            while (depth <= MAX_NESTING_DEPTH + 8) {
                val prev = inspectedChildIndex[cur] ?: 0
                if (prev < 0) break // Already closed & accounted by accountEmittedElement
                val childCount = cur.childNodeSize()
                if (childCount > prev) {
                    val children = cur.childNodes()
                    var i = prev
                    while (i < childCount) {
                        if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                        val child = children[i]
                        if (child is Element) {
                            if ((inspectedChildIndex[child] ?: 0) >= 0) {
                                lastElemChild[cur] = child
                            }
                        } else {
                            openChainNonElemBytes += CHILD_SLOT_BYTES + estimateNonElementNodeBytes(child)
                            checkOverBudget(budgetBytes)
                        }
                        i += 1
                    }
                    inspectedChildIndex[cur] = childCount
                } else if (childCount < prev) {
                    inspectedChildIndex[cur] = childCount
                }
                val nextElem = lastElemChild[cur] ?: break
                if ((inspectedChildIndex[nextElem] ?: 0) < 0 || nextElem.parent() !== cur) {
                    lastElemChild.remove(cur)
                    break
                }
                cur = nextElem
                depth += 1
            }
        }

        fun accountEmittedElement(el: Element, budgetBytes: Long, deadlineNanos: Long) {
            val prev = inspectedChildIndex[el] ?: 0
            if (prev < 0) return
            lastElemChild.remove(el)
            val parentEl = el.parent() as? Element
            if (parentEl != null && lastElemChild[parentEl] === el) {
                lastElemChild.remove(parentEl)
            }
            emittedBytes += ELEMENT_BASE_BYTES
            val attrCount = el.attributesSize()
            if (attrCount > 0) {
                emittedBytes += ATTRS_CONTAINER_BYTES
                var ops = 0
                for (a in el.attributes()) {
                    if ((++ops and 255) == 0) checkDeadline(deadlineNanos)
                    emittedBytes += ATTR_ENTRY_BYTES + 2L * (a.key.length + a.value.length)
                    checkOverBudget(budgetBytes)
                }
            }
            val childCount = el.childNodeSize()
            if (childCount > 0) {
                emittedBytes += CHILD_LIST_BASE_BYTES
                val children = el.childNodes()
                var ops = 0
                for (i in 0 until childCount) {
                    if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                    val child = children[i]
                    if (child is Element) {
                        emittedBytes += CHILD_SLOT_BYTES
                    } else if (i >= prev) {
                        emittedBytes += CHILD_SLOT_BYTES + estimateNonElementNodeBytes(child)
                    }
                    // If child !is Element and i < prev, its bytes are already accounted
                    // inside openChainNonElemBytes (never double-counted and never dropped).
                }
                // Mark as emitted so inspectOpenRightmostChain never rescans its children
                inspectedChildIndex[el] = -1
            } else {
                inspectedChildIndex.remove(el)
            }
            checkOverBudget(budgetBytes)
        }
    }

    /**
     * Step 1: Parses [input] incrementally via Jsoup [StreamParser] + [BudgetCheckingReader],
     * accounting ALL node types (Elements, TextNodes, Comments, PIs, Doctypes, CDATA,
     * DataNodes, Attributes) live during parsing and aborting immediately if bytes cross
     * [budgetBytes].
     * Step 2: Performs an exact iterative full-tree byte & element recount so adoption-agency
     * clones and reparented nodes are accounted with calibrated accuracy.
     */
    private fun parseWithStreamingByteBudget(input: String, parser: Parser, budgetBytes: Long): ParsedDocStats {
        val deadline = callDeadlineNanos
        val liveState = LiveParseBudgetState()
        val reader = BudgetCheckingReader(input, budgetBytes, deadline, liveState)
        val doc = try {
            StreamParser(parser).use { sp ->
                sp.parse(reader, "")
                reader.docRef = sp.document()
                var streamed = 0
                val it = sp.iterator()
                while (it.hasNext()) {
                    val el = it.next()
                    streamed += 1
                    if ((streamed and 255) == 0) checkDeadline(deadline)
                    if (el !is Document) {
                        try {
                            liveState.accountEmittedElement(el, budgetBytes, deadline)
                        } catch (e: DomLimitExceededException) {
                            sp.stop()
                            throw e
                        }
                    }
                }
                sp.document()
            }
        } catch (t: Throwable) {
            liveState.abortError?.let { throw it }
            throw t
        }
        return estimateDocumentExact(doc, budgetBytes, deadline)
    }

    companion object {
        const val MAX_LIVE_DOCS = 8
        const val MAX_DOCS_PER_CALL = MAX_LIVE_DOCS
        const val MAX_DOC_BYTES = 2 * 1024 * 1024
        const val MAX_DOC_MEMORY_BYTES = 24L * 1024 * 1024
        const val MAX_VM_MEMORY_BYTES = 48L * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024
        const val MAX_SELECTOR_STEPS = 2_000_000
        const val MAX_SELECTOR_MEMORY_BYTES = 16 * 1024 * 1024
        const val MAX_SELECTOR_BYTES = 4 * 1024
        const val MAX_SELECTOR_GROUPS = 32
        const val MAX_COMPOUNDS_PER_SELECTOR = 32
        const val MAX_PSEUDO_NESTING = 4
        const val MAX_SELECTOR_VALUE_LENGTH = 256
        const val MAX_NTH_ABS = 1_000_000
        const val MAX_SAFE_JS_HANDLE_ID = 9_007_199_254_740_991L
        private const val MAX_NESTING_DEPTH = 512

        // Calibrated per-node byte estimates (empirically >= 100% of real JVM retained heap
        // across pure elements, elements+text, PIs, comments, many attributes, long text/attr,
        // deep nesting, and XML CDATA/PIs).
        internal const val DOC_BASE_BYTES = 1024L
        internal const val ELEMENT_BASE_BYTES = 56L
        internal const val CHILD_LIST_BASE_BYTES = 64L
        internal const val CHILD_SLOT_BYTES = 8L
        internal const val ATTRS_CONTAINER_BYTES = 48L
        internal const val ATTR_ENTRY_BYTES = 40L
        internal const val TEXT_NODE_BASE_BYTES = 88L
        internal const val COMMENT_NODE_BASE_BYTES = 88L
        internal const val OTHER_NODE_BASE_BYTES = 160L
        internal const val HANDLE_ENTRY_BYTES = 96L

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

        internal fun estimateNonElementNodeBytes(node: Node): Long = when (node) {
            is CDataNode -> TEXT_NODE_BASE_BYTES + 2L * node.text().length
            is TextNode -> TEXT_NODE_BASE_BYTES + 2L * node.wholeText.length
            is DataNode -> TEXT_NODE_BASE_BYTES + 2L * node.wholeData.length
            is Comment -> COMMENT_NODE_BASE_BYTES + 2L * node.data.length
            is XmlDeclaration -> {
                var b = OTHER_NODE_BASE_BYTES + 2L * node.name().length
                val attrs = node.attributes()
                if (attrs.size() > 0) {
                    b += ATTRS_CONTAINER_BYTES
                    for (a in attrs) {
                        b += ATTR_ENTRY_BYTES + 2L * (a.key.length + a.value.length)
                    }
                }
                b
            }
            is DocumentType -> {
                OTHER_NODE_BASE_BYTES + 2L * (node.name().length + node.publicId().length + node.systemId().length)
            }
            else -> OTHER_NODE_BASE_BYTES
        }

        /**
         * Exact iterative full-tree byte & element recount (excluding `#document` from
         * [ParsedDocStats.elementCount]). Publicly accessible to tests for calibration verification.
         */
        internal fun estimateDocumentBytes(doc: Document): Long =
            estimateDocumentExact(doc, Long.MAX_VALUE, Long.MAX_VALUE).estimatedBytes

        private fun estimateDocumentExact(doc: Document, budgetBytes: Long, deadlineNanos: Long): ParsedDocStats {
            var bytes = DOC_BASE_BYTES
            var elemCount = 0
            val stack = ArrayDeque<Node>()
            val rootChildren = doc.childNodes()
            if (rootChildren.isNotEmpty()) {
                bytes += CHILD_LIST_BASE_BYTES + CHILD_SLOT_BYTES * rootChildren.size
                for (i in rootChildren.size - 1 downTo 0) {
                    stack.addLast(rootChildren[i])
                }
            }
            var ops = 0
            while (stack.isNotEmpty()) {
                if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                val node = stack.removeLast()
                if (node is Element) {
                    elemCount += 1
                    bytes += ELEMENT_BASE_BYTES
                    val attrCount = node.attributesSize()
                    if (attrCount > 0) {
                        bytes += ATTRS_CONTAINER_BYTES
                        for (a in node.attributes()) {
                            if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                            bytes += ATTR_ENTRY_BYTES + 2L * (a.key.length + a.value.length)
                        }
                    }
                    val childCount = node.childNodeSize()
                    if (childCount > 0) {
                        bytes += CHILD_LIST_BASE_BYTES + CHILD_SLOT_BYTES * childCount
                        val children = node.childNodes()
                        for (i in childCount - 1 downTo 0) {
                            stack.addLast(children[i])
                        }
                    }
                } else {
                    bytes += estimateNonElementNodeBytes(node)
                }
                if (bytes > budgetBytes) {
                    throw DomLimitExceededException(
                        "解析后的 DOM 估算内存超过上限（上限 ${budgetBytes / (1024 * 1024)} MB）",
                    )
                }
            }
            return ParsedDocStats(doc = doc, elementCount = elemCount, estimatedBytes = bytes)
        }

        internal fun checkDeadline(deadlineNanos: Long) {
            if (Thread.currentThread().isInterrupted) {
                throw DomTimeoutException("DOM 操作已中断")
            }
            if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() > deadlineNanos) {
                throw DomTimeoutException("DOM 操作执行超时")
            }
        }

        internal fun utf8ByteLength(s: String): Int {
            var n = 0
            var i = 0
            val len = s.length
            while (i < len) {
                val c = s[i].code
                if (c < 0x80) {
                    n += 1
                } else if (c < 0x800) {
                    n += 2
                } else if (c in 0xD800..0xDBFF && i + 1 < len) {
                    n += 4
                    i += 1
                } else {
                    n += 3
                }
                i += 1
            }
            return n
        }

        internal class BoundedOutputBuffer(
            private val maxBytes: Int,
            private val deadlineNanos: Long,
        ) {
            private val sb = StringBuilder()
            private var byteCount: Int = 0
            private var ops: Int = 0

            fun appendAscii(s: String) {
                val next = byteCount + s.length
                if (next > maxBytes) {
                    throw DomLimitExceededException(
                        "DOM 序列化输出超过上限（最多 ${maxBytes / (1024 * 1024)} MB）",
                    )
                }
                byteCount = next
                sb.append(s)
                if ((++ops and 4095) == 0) checkDeadline(deadlineNanos)
            }

            fun appendAsciiChar(c: Char) {
                val next = byteCount + 1
                if (next > maxBytes) {
                    throw DomLimitExceededException(
                        "DOM 序列化输出超过上限（最多 ${maxBytes / (1024 * 1024)} MB）",
                    )
                }
                byteCount = next
                sb.append(c)
                if ((++ops and 4095) == 0) checkDeadline(deadlineNanos)
            }

            fun appendChar(c: Char) {
                val add = when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    c.code in 0xD800..0xDFFF -> 2
                    else -> 3
                }
                val next = byteCount + add
                if (next > maxBytes) {
                    throw DomLimitExceededException(
                        "DOM 序列化输出超过上限（最多 ${maxBytes / (1024 * 1024)} MB）",
                    )
                }
                byteCount = next
                sb.append(c)
                if ((++ops and 4095) == 0) checkDeadline(deadlineNanos)
            }

            fun appendString(s: String) {
                var i = 0
                val len = s.length
                while (i < len) {
                    val c = s[i]
                    val add = when {
                        c.code < 0x80 -> 1
                        c.code < 0x800 -> 2
                        c.code in 0xD800..0xDBFF && i + 1 < len -> {
                            val next = byteCount + 4
                            if (next > maxBytes) {
                                throw DomLimitExceededException(
                                    "DOM 序列化输出超过上限（最多 ${maxBytes / (1024 * 1024)} MB）",
                                )
                            }
                            byteCount = next
                            sb.append(c).append(s[i + 1])
                            i += 2
                            if ((++ops and 4095) == 0) checkDeadline(deadlineNanos)
                            continue
                        }
                        else -> 3
                    }
                    val next = byteCount + add
                    if (next > maxBytes) {
                        throw DomLimitExceededException(
                            "DOM 序列化输出超过上限（最多 ${maxBytes / (1024 * 1024)} MB）",
                        )
                    }
                    byteCount = next
                    sb.append(c)
                    i += 1
                    if ((++ops and 4095) == 0) checkDeadline(deadlineNanos)
                }
            }

            override fun toString(): String = sb.toString()
        }

        internal fun getElementAttrWithDeadline(el: Element, name: String, isXml: Boolean, deadlineNanos: Long): String? {
            if (el is Document) return null
            var ops = 0
            if (isXml) {
                for (a in el.attributes()) {
                    if ((++ops and 255) == 0) checkDeadline(deadlineNanos)
                    if (a.key == name) return a.value
                }
                return null
            }
            val lower = name.lowercase(Locale.ROOT)
            for (a in el.attributes()) {
                if ((++ops and 255) == 0) checkDeadline(deadlineNanos)
                if (a.key.lowercase(Locale.ROOT) == lower) return a.value
            }
            return null
        }

        private fun serializeChildrenIterative(
            el: Element,
            isXml: Boolean,
            out: BoundedOutputBuffer,
            deadlineNanos: Long,
        ) {
            val stack = ArrayDeque<Any>()
            pushSerializedChildren(el, isXml, stack, deadlineNanos)
            drainSerializeStack(stack, isXml, out, deadlineNanos)
        }

        private fun serializeNodeIterative(
            node: Node,
            isXml: Boolean,
            out: BoundedOutputBuffer,
            deadlineNanos: Long,
        ) {
            val stack = ArrayDeque<Any>()
            stack.addLast(node)
            drainSerializeStack(stack, isXml, out, deadlineNanos)
        }

        private fun pushSerializedChildren(
            el: Element,
            isXml: Boolean,
            stack: ArrayDeque<Any>,
            deadlineNanos: Long,
        ) {
            val rawTextParent = !isXml && el !is Document && el.tagName().lowercase(Locale.ROOT) in HTML_RAW_TEXT_TAGS
            val children = el.childNodes()
            var ops = 0
            for (i in children.size - 1 downTo 0) {
                if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                val child = children[i]
                if (rawTextParent && (child is TextNode || child is DataNode)) {
                    val raw = if (child is DataNode) child.wholeData else (child as TextNode).wholeText
                    stack.addLast(RawTextChunk(raw))
                } else {
                    stack.addLast(child)
                }
            }
        }

        private fun drainSerializeStack(
            stack: ArrayDeque<Any>,
            isXml: Boolean,
            out: BoundedOutputBuffer,
            deadlineNanos: Long,
        ) {
            var ops = 0
            while (stack.isNotEmpty()) {
                if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                when (val item = stack.removeLast()) {
                    is String -> out.appendString(item)
                    is RawTextChunk -> out.appendString(item.text)
                    is CDataNode -> {
                        if (isXml) {
                            out.appendAscii("<![CDATA[")
                            out.appendString(item.text())
                            out.appendAscii("]]>")
                        } else {
                            appendHtmlText(item.text(), out)
                        }
                    }
                    is TextNode -> {
                        if (isXml) appendXmlText(item.wholeText, out)
                        else appendHtmlText(item.wholeText, out)
                    }
                    is DataNode -> out.appendString(item.wholeData)
                    is Comment -> {
                        out.appendAscii("<!--")
                        out.appendString(item.data)
                        out.appendAscii("-->")
                    }
                    is Element -> {
                        val tag = if (isXml) item.tagName() else item.tagName().lowercase(Locale.ROOT)
                        out.appendAsciiChar('<')
                        out.appendString(tag)
                        var attrOps = 0
                        for (a in item.attributes()) {
                            if ((++attrOps and 255) == 0) checkDeadline(deadlineNanos)
                            val key = if (isXml) a.key else a.key.lowercase(Locale.ROOT)
                            val v = a.value
                            if (!isXml && v.isEmpty()) {
                                out.appendAsciiChar(' ')
                                out.appendString(key)
                            } else {
                                out.appendAsciiChar(' ')
                                out.appendString(key)
                                out.appendAscii("=\"")
                                if (isXml) appendXmlAttr(v, out) else appendHtmlAttr(v, out)
                                out.appendAsciiChar('"')
                            }
                        }
                        if (isXml) {
                            if (item.childNodeSize() == 0) {
                                out.appendAscii("/>")
                            } else {
                                out.appendAsciiChar('>')
                                stack.addLast("</$tag>")
                                pushSerializedChildren(item, true, stack, deadlineNanos)
                            }
                        } else {
                            out.appendAsciiChar('>')
                            if (tag !in HTML_VOID_TAGS) {
                                stack.addLast("</$tag>")
                                pushSerializedChildren(item, false, stack, deadlineNanos)
                            }
                        }
                    }
                }
            }
        }

        private fun appendHtmlText(s: String, out: BoundedOutputBuffer) {
            for (c in s) {
                when (c) {
                    '&' -> out.appendAscii("&amp;")
                    '\u00A0' -> out.appendAscii("&nbsp;")
                    '<' -> out.appendAscii("&lt;")
                    '>' -> out.appendAscii("&gt;")
                    else -> out.appendChar(c)
                }
            }
        }

        private fun appendHtmlAttr(s: String, out: BoundedOutputBuffer) {
            for (c in s) {
                when (c) {
                    '&' -> out.appendAscii("&amp;")
                    '\u00A0' -> out.appendAscii("&nbsp;")
                    '"' -> out.appendAscii("&quot;")
                    else -> out.appendChar(c)
                }
            }
        }

        private fun appendXmlText(s: String, out: BoundedOutputBuffer) {
            for (c in s) {
                when (c) {
                    '&' -> out.appendAscii("&amp;")
                    '<' -> out.appendAscii("&lt;")
                    '>' -> out.appendAscii("&gt;")
                    else -> out.appendChar(c)
                }
            }
        }

        private fun appendXmlAttr(s: String, out: BoundedOutputBuffer) {
            for (c in s) {
                when (c) {
                    '&' -> out.appendAscii("&amp;")
                    '<' -> out.appendAscii("&lt;")
                    '>' -> out.appendAscii("&gt;")
                    '"' -> out.appendAscii("&quot;")
                    else -> out.appendChar(c)
                }
            }
        }

        private fun collectXmlTextIterative(root: Node, out: BoundedOutputBuffer, deadlineNanos: Long) {
            val stack = ArrayDeque<Node>()
            stack.addLast(root)
            var ops = 0
            while (stack.isNotEmpty()) {
                if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                when (val node = stack.removeLast()) {
                    is CDataNode -> out.appendString(node.text())
                    is TextNode -> out.appendString(node.wholeText)
                    is Element -> {
                        val children = node.childNodes()
                        for (i in children.size - 1 downTo 0) {
                            if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                            stack.addLast(children[i])
                        }
                    }
                }
            }
        }

        private fun collectHtmlTextIterative(root: Node, out: BoundedOutputBuffer, deadlineNanos: Long) {
            val stack = ArrayDeque<Any>()
            stack.addLast(root)
            var ops = 0
            while (stack.isNotEmpty()) {
                if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                when (val item = stack.removeLast()) {
                    is Char -> out.appendAsciiChar(item)
                    is CDataNode -> out.appendString(item.text())
                    is TextNode -> out.appendString(item.wholeText)
                    is Document -> {
                        val children = item.childNodes()
                        for (i in children.size - 1 downTo 0) {
                            if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                            stack.addLast(children[i])
                        }
                    }
                    is Element -> {
                        val tag = item.tagName().lowercase(Locale.ROOT)
                        if (tag in HTML_SKIP_TEXT_TAGS) continue
                        val isBlock = tag in HTML_BLOCK_TAGS
                        if (isBlock) {
                            out.appendAsciiChar(' ')
                            stack.addLast(' ')
                        }
                        val children = item.childNodes()
                        for (i in children.size - 1 downTo 0) {
                            if ((++ops and 1023) == 0) checkDeadline(deadlineNanos)
                            stack.addLast(children[i])
                        }
                    }
                }
            }
        }

        internal fun collapseAsciiAndNbspWhitespace(
            raw: String,
            maxOutputBytes: Int = MAX_OUTPUT_BYTES,
            deadlineNanos: Long = Long.MAX_VALUE,
        ): String {
            val sb = StringBuilder(minOf(raw.length, 4096))
            var inSpace = true
            var bytes = 0
            var ops = 0
            for (ch in raw) {
                if ((++ops and 4095) == 0) checkDeadline(deadlineNanos)
                val isSpace = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000C' || ch == '\u00A0'
                if (isSpace) {
                    if (!inSpace) {
                        bytes += 1
                        if (bytes > maxOutputBytes) {
                            throw DomLimitExceededException(
                                "DOM 文本输出超过上限（最多 ${maxOutputBytes / (1024 * 1024)} MB）",
                            )
                        }
                        sb.append(' ')
                        inSpace = true
                    }
                } else {
                    val add = when {
                        ch.code < 0x80 -> 1
                        ch.code < 0x800 -> 2
                        ch.code in 0xD800..0xDFFF -> 2
                        else -> 3
                    }
                    bytes += add
                    if (bytes > maxOutputBytes) {
                        throw DomLimitExceededException(
                            "DOM 文本输出超过上限（最多 ${maxOutputBytes / (1024 * 1024)} MB）",
                        )
                    }
                    sb.append(ch)
                    inSpace = false
                }
            }
            if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
                sb.setLength(sb.length - 1)
            }
            return sb.toString()
        }

        internal fun escapeUndefinedXmlEntities(xml: String, deadlineNanos: Long = Long.MAX_VALUE): String {
            if (!xml.contains('&')) return xml
            val len = xml.length
            val out = StringBuilder(len + 32)
            var i = 0
            var ops = 0
            while (i < len) {
                if ((++ops and 4095) == 0) checkDeadline(deadlineNanos)
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
 */
internal object CssSelectorEngine {

    private const val MEMO_UNCOMPUTED: Byte = 0
    private const val MEMO_FALSE: Byte = 1
    private const val MEMO_TRUE: Byte = 2
    private const val LONG_CLASS_CACHE_THRESHOLD = 128

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
        val maxClassCacheBytes: Long,
        val deadlineNanos: Long,
    ) {
        private var steps: Int = 0
        private var memoryBytes: Long = 0L
        private var classCacheBytes: Long = 0L
        private val memos = IdentityHashMap<ComplexSelector, SelectorMemo>()
        private val siblingTables = IdentityHashMap<Element, SiblingTable>()
        private val classTokenCache = IdentityHashMap<Element, Set<String>>()
        private val emptyCache = IdentityHashMap<Element, Boolean>()

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

        private fun chargeClassCacheMemory(bytes: Long) {
            if (bytes <= 0L) return
            chargeMemory(bytes)
            classCacheBytes += bytes
            if (classCacheBytes > maxClassCacheBytes) {
                throw DomLimitExceededException("VM 存活 DOM 与类名缓存总内存超过上限")
            }
        }

        fun memoFor(sel: ComplexSelector): SelectorMemo =
            memos.getOrPut(sel) { SelectorMemo(sel.compounds.size, sel.isNested) }

        fun getElementAttrBudgeted(el: Element, name: String): String? {
            if (el is Document) return null
            if (el.attributesSize() == 0) {
                tick(1)
                return null
            }
            var budgetChunk = 0
            if (isXml) {
                for (a in el.attributes()) {
                    budgetChunk += 1
                    if (budgetChunk >= 64) {
                        tick(budgetChunk)
                        budgetChunk = 0
                    }
                    if (a.key == name) {
                        if (budgetChunk > 0) tick(budgetChunk)
                        return a.value
                    }
                }
                if (budgetChunk > 0) tick(budgetChunk)
                return null
            }
            val lower = name.lowercase(Locale.ROOT)
            for (a in el.attributes()) {
                budgetChunk += 1
                if (budgetChunk >= 64) {
                    tick(budgetChunk)
                    budgetChunk = 0
                }
                if (a.key.lowercase(Locale.ROOT) == lower) {
                    if (budgetChunk > 0) tick(budgetChunk)
                    return a.value
                }
            }
            if (budgetChunk > 0) tick(budgetChunk)
            return null
        }

        fun siblingTableFor(parent: Element): SiblingTable {
            siblingTables[parent]?.let { return it }
            val rawChildren = parent.childNodes()
            val children = ArrayList<Element>()
            var scanChunk = 0
            for (i in 0 until rawChildren.size) {
                scanChunk += 1
                if (scanChunk >= 256) {
                    tick(scanChunk)
                    scanChunk = 0
                }
                val c = rawChildren[i]
                if (c is Element) children.add(c)
            }
            if (scanChunk > 0) tick(scanChunk)

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

        /**
         * Matches all required class names on [el]:
         * - For normal/short `class` strings (`<= 128` chars) or single-token strings, scans
         *   the original string directly in-place with ZERO cache allocation (preventing false-positive
         *   16 MB memory budget errors on 200k+ classed elements for `.a`, `i.a`, `.a.b`, `.a .b`).
         * - Only builds a per-element cached token `Set<String>` when `class` exceeds 128 chars
         *   AND contains > 1 token, charging both query memory and VM memory budgets.
         */
        fun matchesClasses(el: Element, wantedClasses: List<String>): Boolean {
            classTokenCache[el]?.let { cached ->
                for (c in wantedClasses) {
                    tick(1)
                    if (c !in cached) return false
                }
                return true
            }
            val clsAttr = getElementAttrBudgeted(el, "class") ?: return false
            if (clsAttr.isEmpty()) return false
            if (clsAttr.length <= LONG_CLASS_CACHE_THRESHOLD) {
                // Single in-place pass with zero allocations for normal short class lists
                tick(1)
                return matchesAllClassesInPlace(clsAttr, wantedClasses)
            }
            // Long class attribute (> 128 chars): scan once while charging every char into step budget
            val tokens = HashSet<String>()
            val len = clsAttr.length
            var i = 0
            var budgetChunk = 0
            var uniqueTokenBytes = 64L
            var tokenCount = 0
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
                    tokenCount += 1
                    val tok = clsAttr.substring(start, i)
                    if (tokens.add(tok)) {
                        val addedBytes = 32L + tok.length.toLong() * 2L
                        uniqueTokenBytes += addedBytes
                        if (uniqueTokenBytes >= 4096L) {
                            chargeClassCacheMemory(uniqueTokenBytes)
                            uniqueTokenBytes = 0L
                        }
                    }
                }
            }
            if (budgetChunk > 0) tick(budgetChunk)
            if (tokenCount > 1) {
                if (uniqueTokenBytes > 0L) chargeClassCacheMemory(uniqueTokenBytes)
                classTokenCache[el] = tokens
            }
            for (c in wantedClasses) {
                tick(1)
                if (c !in tokens) return false
            }
            return true
        }

        private fun matchesAllClassesInPlace(clsAttr: String, wantedClasses: List<String>): Boolean {
            val n = clsAttr.length
            if (wantedClasses.size == 1) {
                val target = wantedClasses[0]
                val m = target.length
                var i = 0
                while (i < n) {
                    while (i < n && isAsciiWs(clsAttr[i])) i += 1
                    val start = i
                    while (i < n && !isAsciiWs(clsAttr[i])) i += 1
                    if (i - start == m && clsAttr.regionMatches(start, target, 0, m)) {
                        return true
                    }
                }
                return false
            }
            // Multiple wanted classes in one compound (e.g. `.a.b`): verify every wanted token
            // Charge 1 step per wanted class plus 1 step per 32 scanned chars so adversarial
            // `.a.a...x2000` on 128-char class strings remains strictly step-budgeted.
            var charsScanned = 0
            for (k in 0 until wantedClasses.size) {
                tick(1)
                val target = wantedClasses[k]
                val m = target.length
                var found = false
                var i = 0
                while (i < n) {
                    while (i < n && isAsciiWs(clsAttr[i])) i += 1
                    val start = i
                    while (i < n && !isAsciiWs(clsAttr[i])) i += 1
                    if (i - start == m && clsAttr.regionMatches(start, target, 0, m)) {
                        found = true
                        break
                    }
                }
                charsScanned += i
                if (charsScanned >= 32) {
                    tick(charsScanned ushr 5)
                    charsScanned = charsScanned and 31
                }
                if (!found) return false
            }
            return true
        }

        fun matchesEmptyCached(el: Element): Boolean {
            val childCount = el.childNodeSize()
            if (childCount == 0) return true
            emptyCache[el]?.let { return it }
            val children = el.childNodes()
            var isEmpty = true
            var budgetChunk = 0
            for (i in 0 until childCount) {
                budgetChunk += 1
                if (budgetChunk >= 256) {
                    tick(budgetChunk)
                    budgetChunk = 0
                }
                val n = children[i]
                if (n is Element ||
                    (n is TextNode && n.wholeText.isNotEmpty()) ||
                    (n is CDataNode && n.text().isNotEmpty())
                ) {
                    isEmpty = false
                    break
                }
            }
            if (budgetChunk > 0) tick(budgetChunk)
            chargeMemory(32L)
            emptyCache[el] = isEmpty
            return isEmpty
        }

        fun matchesAny(el: Element, selectors: List<ComplexSelector>, isTopLevel: Boolean): Boolean {
            for (sel in selectors) {
                if (evalMatchStep(el, sel, sel.compounds.size - 1, isTopLevel)) return true
            }
            return false
        }

        private fun evalMatchStep(el: Element, sel: ComplexSelector, idx: Int, isTopLevel: Boolean): Boolean {
            val stepCount = sel.compounds.size
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
                val actual = getElementAttrBudgeted(el, "id") ?: return false
                if (!equalsBudgeted(actual, id, ci = false)) return false
            }
            if (comp.classes.isNotEmpty()) {
                if (!matchesClasses(el, comp.classes)) return false
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
            val actual = getElementAttrBudgeted(el, am.name) ?: return false
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
            PseudoMatcher.Empty -> matchesEmptyCached(el)
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
            PluginDomParser.utf8ByteLength(rawSelector) > PluginDomParser.MAX_SELECTOR_BYTES
        ) {
            throw DomLimitExceededException("CSS 选择器超过长度上限（最多 ${PluginDomParser.MAX_SELECTOR_BYTES} 字节）")
        }
        val p = SelectorParser(rawSelector, nestingDepth = 0)
        val list = p.parseSelectorList(eofExpected = true)
        if (list.isEmpty()) throw IllegalArgumentException("无效的 CSS 选择器：$rawSelector")
        return list
    }

    private fun pushChildElementsBudgeted(parent: Element, stack: ArrayDeque<Element>, ctx: QueryContext) {
        val raw = parent.childNodes()
        var nonElemChunk = 0
        for (i in raw.size - 1 downTo 0) {
            val child = raw[i]
            if (child is Element) {
                stack.addLast(child)
            } else {
                nonElemChunk += 1
                if (nonElemChunk >= 256) {
                    ctx.tick(nonElemChunk)
                    nonElemChunk = 0
                }
            }
        }
        if (nonElemChunk > 0) ctx.tick(nonElemChunk)
    }

    fun queryFirst(
        scopeRoot: Element,
        selectors: List<ComplexSelector>,
        isXml: Boolean,
        maxSteps: Int = PluginDomParser.MAX_SELECTOR_STEPS,
        maxMemoryBytes: Int = PluginDomParser.MAX_SELECTOR_MEMORY_BYTES,
        maxClassCacheBytes: Long = PluginDomParser.MAX_VM_MEMORY_BYTES,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Element? {
        PluginDomParser.checkDeadline(deadlineNanos)
        val ctx = QueryContext(isXml, maxSteps, maxMemoryBytes, maxClassCacheBytes, deadlineNanos)
        val stack = ArrayDeque<Element>()
        pushChildElementsBudgeted(scopeRoot, stack, ctx)
        while (stack.isNotEmpty()) {
            ctx.tick(1)
            val el = stack.removeLast()
            if (ctx.matchesAny(el, selectors, isTopLevel = true)) return el
            pushChildElementsBudgeted(el, stack, ctx)
        }
        return null
    }

    fun queryAll(
        scopeRoot: Element,
        selectors: List<ComplexSelector>,
        isXml: Boolean,
        maxSteps: Int = PluginDomParser.MAX_SELECTOR_STEPS,
        maxMemoryBytes: Int = PluginDomParser.MAX_SELECTOR_MEMORY_BYTES,
        maxClassCacheBytes: Long = PluginDomParser.MAX_VM_MEMORY_BYTES,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): List<Element> {
        PluginDomParser.checkDeadline(deadlineNanos)
        val ctx = QueryContext(isXml, maxSteps, maxMemoryBytes, maxClassCacheBytes, deadlineNanos)
        val out = ArrayList<Element>()
        val stack = ArrayDeque<Element>()
        pushChildElementsBudgeted(scopeRoot, stack, ctx)
        while (stack.isNotEmpty()) {
            ctx.tick(1)
            val el = stack.removeLast()
            if (ctx.matchesAny(el, selectors, isTopLevel = true)) {
                out.add(el)
            }
            pushChildElementsBudgeted(el, stack, ctx)
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
