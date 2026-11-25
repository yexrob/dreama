package com.limelight.local.stream

import com.limelight.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-JVM [StringLookup] backed by the real production `src/main/res/values/strings_depth3d.xml`.
 * Maps generated `R.string.*` integer IDs via reflection to the exact XML text so unit tests
 * verify the actual shipped resource copy.
 */
object XmlStringLookup : StringLookup {
    private val byId: Map<Int, String> by lazy { loadStrings() }

    private fun loadStrings(): Map<Int, String> {
        val baseDirs = listOf(
            File("src/main/res/values"),
            File("app/src/main/res/values"),
            File("player/app/src/main/res/values"),
        )
        val valuesDir = baseDirs.firstOrNull { File(it, "strings_depth3d.xml").isFile }
            ?: error("Could not locate res/values/strings_depth3d.xml from ${File(".").absolutePath}")

        val byName = HashMap<String, String>()
        val xmlFiles = listOf("strings.xml", "strings_depth3d.xml", "strings_plugin.xml")
            .map { File(valuesDir, it) }
            .filter { it.isFile }
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (xmlFile in xmlFiles) {
            val doc = builder.parse(xmlFile)
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                byName[name] = unescapeAndroidString(node.textContent ?: "")
            }
        }

        val result = HashMap<Int, String>()
        for (field in R.string::class.java.fields) {
            val value = byName[field.name] ?: continue
            result[field.getInt(null)] = value
        }
        return result
    }

    private fun unescapeAndroidString(raw: String): String =
        raw.replace("\\n", "\n")
            .replace("\\'", "'")
            .replace("\\\"", "\"")

    override fun get(resId: Int): String =
        byId[resId] ?: error("Missing string resource id $resId in strings_depth3d.xml")

    override fun get(resId: Int, vararg args: Any): String =
        String.format(get(resId), *args)

    fun phoneInputCopy(mode: PhoneInputServer.Mode): PhoneInputCopy =
        PhoneInputCopy.from(this, mode)
}
