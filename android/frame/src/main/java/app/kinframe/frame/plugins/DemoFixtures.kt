package app.kinframe.frame.plugins

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Debug builds only (see [FramePlugins]): a widget that shows the example views of `docs/api/plugin-views/examples`
 * (packaged as debug assets), to look at every component on a frame or an emulator without a meter. Setting
 * `fixture` pins one example ("flow-solar-home-grid"); otherwise it moves to the next one every [refreshSeconds] × 2.
 */
class DemoFixtures(private val context: Context) : PluginInstance {
    override val refreshSeconds = 5
    private var tick = 0

    private val fixtures: List<Pair<String, JsonElement>> by lazy {
        val dir = "examples"
        (context.assets.list(dir) ?: emptyArray()).sorted().filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching { name.removeSuffix(".json") to Json.parseToJsonElement(context.assets.open("$dir/$name").bufferedReader().readText()) }.getOrNull()
        }
    }

    override suspend fun render(ctx: PluginContext): JsonElement? {
        val pinned = (ctx.settings["fixture"] as? JsonPrimitive)?.contentOrNull
        if (fixtures.isEmpty()) return null
        pinned?.let { p -> fixtures.firstOrNull { it.first == p }?.let { return it.second } }
        val f = fixtures[(tick++ / 2) % fixtures.size]
        return f.second
    }

    override suspend fun detail(ctx: PluginContext): JsonElement? =
        fixtures.firstOrNull { it.first == "detail-p1" }?.second ?: render(ctx)
}
