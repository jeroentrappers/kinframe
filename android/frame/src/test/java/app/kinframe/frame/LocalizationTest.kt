package app.kinframe.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every language the frame offers has a complete, well-formed translation (docs/i18n.md): the same string, plurals
 * and array names as English, the same format arguments, and the plural quantities its language uses (CLDR).
 * Reads the resource files directly, so it runs as a plain JVM test in CI.
 */
class LocalizationTest {
    private val res = File("src/main/res")

    private class Strings(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val arrays: Map<String, Int>,
    )

    /** Every strings*.xml file in values/ (features may keep their strings in a file of their own). */
    private val stringFiles = res.resolve("values").list()!!.filter { it.startsWith("strings") && it.endsWith(".xml") }.sorted()

    /**
     * New feature files that ship in English and Dutch first (marked `tools:ignore="MissingTranslation"`); the
     * translation pass adds the other languages and removes them from this list.
     */
    private val pendingTranslation = setOf("strings_media.xml", "strings_export.xml")

    private fun parse(dir: String, name: String = "strings.xml"): Strings {
        val file = File(res, "$dir/$name")
        assertTrue("missing $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        fun elements(tag: String) = doc.getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
        return Strings(
            strings = elements("string").filter { it.getAttribute("translatable") != "false" }.associate { it.getAttribute("name") to it.textContent },
            plurals = elements("plurals").associate { p ->
                val items = p.getElementsByTagName("item")
                p.getAttribute("name") to (0 until items.length).associate { i ->
                    val e = items.item(i) as Element
                    e.getAttribute("quantity") to e.textContent
                }
            },
            arrays = elements("string-array").associate { it.getAttribute("name") to it.getElementsByTagName("item").length },
        )
    }

    private val formatArg = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfx]""")
    private fun args(s: String) = formatArg.findAll(s).map { it.value }.sorted().toList()

    /** Plural categories per language (CLDR, as Intl.PluralRules reports them); lint requires all of them. */
    private val quantities = mapOf(
        "nl" to setOf("one", "other"),
        "ar" to setOf("zero", "one", "two", "few", "many", "other"),
        "bg" to setOf("one", "other"),
        "cs" to setOf("one", "few", "many", "other"),
        "da" to setOf("one", "other"),
        "de" to setOf("one", "other"),
        "el" to setOf("one", "other"),
        "es" to setOf("one", "many", "other"),
        "fi" to setOf("one", "other"),
        "fr" to setOf("one", "many", "other"),
        "he" to setOf("one", "two", "other"),
        "hi" to setOf("one", "other"),
        "hr" to setOf("one", "few", "other"),
        "hu" to setOf("one", "other"),
        "id" to setOf("other"),
        "it" to setOf("one", "many", "other"),
        "ja" to setOf("other"),
        "ko" to setOf("other"),
        "lt" to setOf("one", "few", "many", "other"),
        "lv" to setOf("zero", "one", "other"),
        "nb" to setOf("one", "other"),
        "pl" to setOf("one", "few", "many", "other"),
        "pt" to setOf("one", "many", "other"),
        "ro" to setOf("one", "few", "other"),
        "ru" to setOf("one", "few", "many", "other"),
        "sk" to setOf("one", "few", "many", "other"),
        "sl" to setOf("one", "two", "few", "other"),
        "sr" to setOf("one", "few", "other"),
        "sv" to setOf("one", "other"),
        "th" to setOf("other"),
        "tr" to setOf("one", "other"),
        "uk" to setOf("one", "few", "many", "other"),
        "vi" to setOf("other"),
        "zh-CN" to setOf("other"),
        "zh-TW" to setOf("other"),
    )

    private fun dirOf(code: String) = if ('-' in code) code.split('-').let { (l, r) -> "values-$l-r$r" } else "values-$code"

    @Test fun everyLanguageHasEveryString() {
        val problems = mutableListOf<String>()
        for (name in stringFiles) for (lang in Locales.supported.map { it.code }.filter { it != "en" && (name !in pendingTranslation || it == "nl") }) {
            val en = parse("values", name)
            val dir = "${dirOf(lang)}/$name"
            val t = parse(dirOf(lang), name)
            for ((name, text) in en.strings) {
                val tr = t.strings[name]
                when {
                    tr == null -> problems += "$dir: missing string $name"
                    tr.isBlank() -> problems += "$dir: empty string $name"
                    args(tr) != args(text) -> problems += "$dir: $name has ${args(tr)}, English has ${args(text)}"
                }
            }
            (t.strings.keys - en.strings.keys).forEach { problems += "$dir: string $it is not in English" }
            for (name in en.plurals.keys) {
                val p = t.plurals[name] ?: run { problems += "$dir: missing plurals $name"; continue }
                val want = quantities.getValue(lang)
                if (p.keys != want) problems += "$dir: plurals $name has ${p.keys}, $lang needs $want"
                p.forEach { (q, v) -> if (v.isBlank()) problems += "$dir: plurals $name/$q is empty" }
            }
            for ((name, n) in en.arrays) if (t.arrays[name] != n) problems += "$dir: array $name has ${t.arrays[name]} items, want $n"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun legacyCodeCopiesAreIdentical() {
        // Android 6 resolves Hebrew and Indonesian only through the old codes iw / in.
        for (name in stringFiles - pendingTranslation) for ((modern, legacy) in listOf("he" to "iw", "id" to "in")) {
            assertEquals(
                "values-$legacy/$name must be a copy of values-$modern/$name",
                File(res, "values-$modern/$name").readText(),
                File(res, "values-$legacy/$name").readText(),
            )
        }
    }

    @Test fun noUnknownLanguageFolders() {
        val known = Locales.supported.map { dirOf(it.code) }.toSet() + setOf("values-iw", "values-in")
        val dirs = res.listFiles()!!.map { it.name }.filter { it.startsWith("values-") && File(res, "$it/strings.xml").isFile }
        assertEquals(emptyList<String>(), dirs.filter { it !in known })
    }

    @Test fun thirtySixLanguagesSortedForThePicker() {
        val names = Locales.supported.map { it.nativeName }
        assertEquals(36, names.size)
        assertEquals(36, quantities.size + 1)
        // Latin scripts first (alphabetically), then Greek, Cyrillic, … CJK.
        assertTrue(names.indexOf("Deutsch") < names.indexOf("English"))
        assertTrue(names.indexOf("Tiếng Việt") < names.indexOf("Ελληνικά"))
        assertTrue(names.indexOf("Ελληνικά") < names.indexOf("Русский"))
        assertTrue(names.indexOf("Русский") < names.indexOf("العربية"))
        assertTrue(names.indexOf("العربية") < names.indexOf("日本語"))
    }

    @Test fun matchesSystemLocales() {
        assertEquals("nl", Locales.match("nl-BE"))
        assertEquals("he", Locales.match("iw"))
        assertEquals("id", Locales.match("in_ID"))
        assertEquals("nb", Locales.match("no"))
        assertEquals("zh-TW", Locales.match(Locale.TRADITIONAL_CHINESE))
        assertEquals("zh-TW", Locales.match("zh-Hant-HK"))
        assertEquals("zh-CN", Locales.match(Locale.SIMPLIFIED_CHINESE))
        assertEquals("pt", Locales.match("pt-BR"))
        assertEquals(null, Locales.match("xx"))
        assertEquals("zh_TW", Locales.toLocale("zh-TW").toString())
    }
}
