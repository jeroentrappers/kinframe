package app.kinframe.frame.plugins.js

import java.io.File

/**
 * The host's JavaScript libraries (docs/plugins.md §13.1): ES modules a plugin may import as `kinframe/<name>`, so
 * integrations share one tested copy of a connection or pattern instead of each pasting its own. They ship in the APK
 * (assets `plugin-host/lib/<name>.js`) and belong to the plugin API version: within API 1 they only grow. A library
 * runs inside the importing plugin's runtime and reaches the world only through the `ctx` the plugin passes it, so it
 * has exactly that plugin's permissions. Nothing else can be imported.
 */
class PluginLibraries(private val sources: Map<String, String>) {
    /** The importable names, e.g. `kinframe/ha`. */
    val names: List<String> get() = sources.keys.sorted()

    fun source(name: String): String? = sources[name]

    companion object {
        const val PREFIX = "kinframe/"
        val NONE = PluginLibraries(emptyMap())

        private fun named(files: Map<String, String>) =
            PluginLibraries(files.filterKeys { it.endsWith(".js") }.mapKeys { (f, _) -> PREFIX + f.removeSuffix(".js") })

        fun fromDirectory(dir: File): PluginLibraries =
            named(dir.listFiles { f -> f.isFile }.orEmpty().associate { it.name to it.readText() })

        fun fromAssets(assets: android.content.res.AssetManager, dir: String = "plugin-host/lib"): PluginLibraries =
            named(assets.list(dir).orEmpty().associateWith { f -> assets.open("$dir/$f").bufferedReader().use { it.readText() } })
    }
}
