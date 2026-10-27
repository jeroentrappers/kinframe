package app.kinframe.frame.plugins

import app.kinframe.core.plugin.RelativeWords
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The frame's own string resources read straight from `res/values-*`, for JVM tests that need the real translations
 * (Android's resource system isn't there).
 */
object FrameStrings {
    private val res = File("src/main/res")

    /** The resource folder suffixes the frame ships: "" (English), "nl", "zh-rCN", … */
    val locales: List<String> = res.listFiles { f -> f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) && f.resolve("strings_plugins.xml").isFile }!!
        .map { it.name.removePrefix("values").removePrefix("-") }.sorted()

    /** All `<string>`s of one folder, apostrophe escapes undone. */
    fun strings(locale: String): Map<String, String> {
        val dir = res.resolve(if (locale.isEmpty()) "values" else "values-$locale")
        return dir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!.flatMap { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.getElementsByTagName("string")
            (0 until nodes.length).map { i -> (nodes.item(i) as Element).let { it.getAttribute("name") to it.textContent.replace("\\'", "'") } }
        }.toMap()
    }

    /** [RelativeWords] in a language tag ("nl", "zh-CN"), English where the frame has no translation. */
    fun relativeWords(languageTag: String): RelativeWords {
        val folder = languageTag.replace("-", "-r").takeIf { it in locales } ?: languageTag.substringBefore('-').takeIf { it in locales } ?: ""
        val s = strings("") + strings(folder)
        fun w(k: String) = s.getValue("plugin_rel_$k")
        return RelativeWords(w("today"), w("tomorrow"), w("yesterday"), w("just_now"), w("now"), w("min_ago"), w("h_ago"), w("in_min"), w("in_h"))
    }
}
