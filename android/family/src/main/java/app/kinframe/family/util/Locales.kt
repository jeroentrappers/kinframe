package app.kinframe.family.util

/** The supported languages (same list as the web app, the frame app and the server, see docs/i18n.md). */
object Locales {
    /** Tag to the language's own name. */
    val NATIVE: Map<String, String> = linkedMapOf(
        "en" to "English", "nl" to "Nederlands", "ar" to "العربية", "bg" to "Български", "cs" to "Čeština", "da" to "Dansk",
        "de" to "Deutsch", "el" to "Ελληνικά", "es" to "Español", "fi" to "Suomi", "fr" to "Français", "he" to "עברית",
        "hi" to "हिन्दी", "hr" to "Hrvatski", "hu" to "Magyar", "id" to "Bahasa Indonesia", "it" to "Italiano", "ja" to "日本語",
        "ko" to "한국어", "lt" to "Lietuvių", "lv" to "Latviešu", "nb" to "Norsk bokmål", "pl" to "Polski", "pt" to "Português",
        "ro" to "Română", "ru" to "Русский", "sk" to "Slovenčina", "sl" to "Slovenščina", "sr" to "Српски", "sv" to "Svenska",
        "th" to "ไทย", "tr" to "Türkçe", "uk" to "Українська", "vi" to "Tiếng Việt", "zh-CN" to "简体中文", "zh-TW" to "繁體中文",
    )

    /** The supported tag closest to a BCP 47 tag ("nl-BE" → "nl", "zh-Hant-TW" → "zh-TW"), or null. */
    fun match(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val t = tag.replace('_', '-')
        NATIVE.keys.firstOrNull { it.equals(t, ignoreCase = true) }?.let { return it }
        val lang = t.substringBefore('-').lowercase()
        if (lang == "zh") return if (t.contains("TW", true) || t.contains("Hant", true) || t.contains("HK", true)) "zh-TW" else "zh-CN"
        if (lang == "no" || lang == "nn") return "nb"
        return NATIVE.keys.firstOrNull { it == lang }
    }
}
