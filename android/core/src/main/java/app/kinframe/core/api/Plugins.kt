package app.kinframe.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /frames/{id}/plugins` (docs/plugins.md §6): the plugins the frame can run and how its instances are doing.
 * The instances themselves live in the settings (`plugins.instances`). [catalogAt] is null for a frame app that
 * doesn't report a catalog yet ("Update the frame app").
 */
@Serializable
data class FramePlugins(
    val catalog: List<CatalogPlugin> = emptyList(),
    val catalogAt: String? = null,
    val instances: List<PluginInstanceStatus> = emptyList(),
    /** mDNS service type (a `discover` value) → what the frame found on its network. */
    val services: Map<String, List<DiscoveredService>> = emptyMap(),
    val statusAt: String? = null,
)

/** A plugin the frame can run: its `plugin.json` as-is and the manifest's translations by language tag. */
@Serializable
data class CatalogPlugin(
    val manifest: JsonObject = JsonObject(emptyMap()),
    val locales: Map<String, Map<String, String>> = emptyMap(),
    val builtin: Boolean = false,
)

@Serializable
data class PluginInstanceStatus(
    val instanceId: String,
    val running: Boolean = false,
    val error: String? = null,
    val errorAt: String? = null,
    val updatedAt: String? = null,
    /** Home Assistant entities the frame sees through this instance (entity picker); null when it has none. */
    val entities: List<DiscoveredEntity>? = null,
)

@Serializable
data class DiscoveredEntity(
    val entityId: String,
    val name: String? = null,
    val unit: String? = null,
    val deviceClass: String? = null,
)

@Serializable
data class DiscoveredService(val name: String, val url: String)
