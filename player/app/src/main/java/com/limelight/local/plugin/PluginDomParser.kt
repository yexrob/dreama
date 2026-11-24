package com.limelight.local.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backing store for `ctx.parse.html(text)` and `ctx.parse.xml(text)` (§5).
 * Parses HTML/XML into read-only Jsoup DOM trees and answers CSS selector
 * queries by handle id. Handles are cleared after each plugin invocation to
 * guarantee bounded memory usage.
 */
class PluginDomParser {
    private val nextId = AtomicInteger(1)
    private val nodes = ConcurrentHashMap<Int, Element>()

    fun parseHtml(html: String): Int {
        val doc = Jsoup.parse(html)
        return register(doc)
    }

    fun parseXml(xml: String): Int {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return register(doc)
    }

    fun selectFirst(nodeId: Int, selector: String): JsonObject? {
        val el = nodes[nodeId] ?: return null
        val found = el.selectFirst(selector) ?: return null
        return summarize(register(found), found)
    }

    fun selectAll(nodeId: Int, selector: String): JsonArray {
        val el = nodes[nodeId] ?: return JsonArray(emptyList())
        val found = el.select(selector)
        return buildJsonArray {
            for (child in found) {
                add(summarize(register(child), child))
            }
        }
    }

    fun children(nodeId: Int): JsonArray {
        val el = nodes[nodeId] ?: return JsonArray(emptyList())
        return buildJsonArray {
            for (child in el.children()) {
                add(summarize(register(child), child))
            }
        }
    }

    fun nodeInfo(nodeId: Int): JsonObject? {
        val el = nodes[nodeId] ?: return null
        return summarize(nodeId, el)
    }

    fun clear() {
        nodes.clear()
    }

    private fun register(el: Element): Int {
        val id = nextId.getAndIncrement()
        nodes[id] = el
        return id
    }

    private fun summarize(id: Int, el: Element): JsonObject = buildJsonObject {
        put("id", id)
        put("tag", el.tagName())
        put("text", el.text())
        put("html", el.html())
        put("outerHtml", el.outerHtml())
        put(
            "attrs",
            buildJsonObject {
                for (attr in el.attributes()) {
                    put(attr.key, attr.value)
                }
            },
        )
    }
}
