package app.kinframe.frame

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** In-app language (English and Dutch for v0.1; more via resources). */
object Locales {
    val supported = listOf("en" to "English", "nl" to "Nederlands")

    fun stored(context: Context): String? =
        context.getSharedPreferences("frame", Context.MODE_PRIVATE).getString("language", null)

    fun effective(context: Context): String =
        stored(context) ?: Locale.getDefault().language.takeIf { l -> supported.any { it.first == l } } ?: "en"

    fun wrap(base: Context): Context {
        val lang = stored(base) ?: return base
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
