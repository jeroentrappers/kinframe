package app.kinframe.frame.plugins.js

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.Locale

/**
 * A plugin as the frame runs it (docs/plugins.md §3): the manifest, `main.js` and the translations in `locales/`.
 * Built-in bundles ship in the APK's assets (`plugins/<id>/`, copied from the repository's `plugins/` folder at build
 * time); downloaded, signed bundles come with the registry (step 4).
 */
class PluginBundle(
    val manifest: PluginManifest,
    val mainJs: String,
    /** Language tag ("nl", "zh-CN") → key → text. Keys starting with `@` translate the manifest (`@name`). */
    val locales: Map<String, Map<String, String>>,
) {
    init {
        if (mainJs.length > MAX_CODE) throw ManifestException("main.js is larger than ${MAX_CODE / 1024} KB")
    }

    /** The strings for [tag]: the exact tag, then its language, then English. */
    fun strings(tag: String): Map<String, String> {
        val lang = tag.substringBefore('-').lowercase(Locale.ROOT)
        val en = locales["en"].orEmpty()
        val exact = locales[tag] ?: locales.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.value
        return en + locales[lang].orEmpty() + exact.orEmpty()
    }

    fun name(tag: String): String = strings(tag)["@name"] ?: manifest.name

    companion object {
        const val MAX_CODE = 256 * 1024
        private val json = Json

        /** Reads a bundle from files: `plugin.json`, `main.js`, `locales/<tag>.json` (flat string maps). */
        fun read(files: (String) -> String?, localeTags: List<String>): PluginBundle {
            val manifest = PluginManifest.parse(files("plugin.json") ?: throw ManifestException("no plugin.json"))
            val main = files("main.js") ?: throw ManifestException("no main.js")
            val locales = localeTags.mapNotNull { tag ->
                val text = files("locales/$tag.json") ?: return@mapNotNull null
                val o = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrElse { throw ManifestException("locales/$tag.json is not an object") }
                tag to o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            }.toMap()
            return PluginBundle(manifest, main, locales)
        }

        fun fromDirectory(dir: File): PluginBundle {
            val tags = dir.resolve("locales").listFiles { f -> f.name.endsWith(".json") }?.map { it.name.removeSuffix(".json") }.orEmpty()
            return read({ p -> dir.resolve(p).takeIf { it.isFile }?.readText() }, tags)
        }

        fun fromAssets(assets: android.content.res.AssetManager, id: String): PluginBundle? {
            val root = "plugins/$id"
            if (assets.list(root).isNullOrEmpty()) return null
            val tags = assets.list("$root/locales")?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") }.orEmpty()
            return read({ p -> runCatching { assets.open("$root/$p").bufferedReader().use { it.readText() } }.getOrNull() }, tags)
        }
    }
}
