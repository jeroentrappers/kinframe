package app.kinframe.frame

import android.content.Context
import android.content.res.Configuration
import java.text.Collator
import java.util.Locale

/**
 * In-app language. The codes are the ones the server and web app use (`locale.language` in the frame settings,
 * docs/i18n.md): plain ISO 639-1 codes plus `zh-CN` / `zh-TW`. Resources live in `values-<qualifier>`; Hebrew and
 * Indonesian also ship as `values-iw` / `values-in`, the legacy codes Android 6 resolves.
 */
object Locales {
    data class Language(val code: String, val nativeName: String) {
        val locale: Locale get() = toLocale(code)
        val isRtl: Boolean get() = code == "ar" || code == "he"
    }

    private val all = listOf(
        "en" to "English", "nl" to "Nederlands", "ar" to "العربية", "bg" to "Български", "cs" to "Čeština",
        "da" to "Dansk", "de" to "Deutsch", "el" to "Ελληνικά", "es" to "Español", "fi" to "Suomi",
        "fr" to "Français", "he" to "עברית", "hi" to "हिन्दी", "hr" to "Hrvatski", "hu" to "Magyar",
        "id" to "Bahasa Indonesia", "it" to "Italiano", "ja" to "日本語", "ko" to "한국어", "lt" to "Lietuvių",
        "lv" to "Latviešu", "nb" to "Norsk bokmål", "pl" to "Polski", "pt" to "Português", "ro" to "Română",
        "ru" to "Русский", "sk" to "Slovenčina", "sl" to "Slovenščina", "sr" to "Српски", "sv" to "Svenska",
        "th" to "ไทย", "tr" to "Türkçe", "uk" to "Українська", "vi" to "Tiếng Việt", "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
    ).map { (c, n) -> Language(c, n) }

    /**
     * Every language, sorted for the picker by native name with the root collation: Latin scripts first
     * (alphabetically), then Greek, Cyrillic, Hebrew, Arabic, Devanagari, Thai and the CJK languages.
     */
    val supported: List<Language> = run {
        val collator = Collator.getInstance(Locale.ROOT)
        all.sortedWith { a, b -> collator.compare(a.nativeName, b.nativeName) }
    }

    fun isSupported(code: String?) = code != null && all.any { it.code == code }

    fun language(code: String): Language? = all.firstOrNull { it.code == code }

    /**
     * Maps any locale or BCP 47 tag to a supported code, or null. Handles the legacy codes (`iw`, `in`),
     * Norwegian (`no`/`nn` → `nb`) and Chinese scripts/regions.
     */
    fun match(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val parts = tag.replace('_', '-').lowercase(Locale.ROOT).split('-')
        val lang = parts[0]
        val rest = parts.drop(1)
        if (lang == "zh") {
            return if ("hant" in rest || rest.any { it == "tw" || it == "hk" || it == "mo" }) "zh-TW" else "zh-CN"
        }
        val code = when (lang) { "iw" -> "he"; "in" -> "id"; "no", "nn" -> "nb"; else -> lang }
        return code.takeIf { isSupported(it) }
    }

    fun match(locale: Locale): String? {
        val tag = buildString {
            append(locale.language)
            if (locale.script.isNotEmpty()) append('-').append(locale.script)
            if (locale.country.isNotEmpty()) append('-').append(locale.country)
        }
        return match(tag)
    }

    fun toLocale(code: String): Locale = when (code) {
        "zh-CN" -> Locale.SIMPLIFIED_CHINESE
        "zh-TW" -> Locale.TRADITIONAL_CHINESE
        // Locale normalises he/id to the legacy iw/in on older Android; values-iw/values-in cover that.
        else -> Locale(code)
    }

    fun stored(context: Context): String? =
        context.getSharedPreferences("frame", Context.MODE_PRIVATE).getString("language", null)?.let { match(it) }

    /** The chosen language, else the system language when we support it, else English. */
    fun effective(context: Context): String = stored(context) ?: match(Locale.getDefault()) ?: "en"

    fun wrap(base: Context): Context {
        val lang = stored(base) ?: return base
        val locale = toLocale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
