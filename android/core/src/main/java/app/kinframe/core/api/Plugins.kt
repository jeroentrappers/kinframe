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

/**
 * A plugin the frame can run: its `plugin.json` as-is and the manifest's translations by language tag. [builtin] is
 * false for a plugin from the community registry (its newest version, docs/plugins.md §7); an entry that doesn't say
 * is one of the frame's own.
 */
@Serializable
data class CatalogPlugin(
    val manifest: JsonObject = JsonObject(emptyMap()),
    val locales: Map<String, Map<String, String>> = emptyMap(),
    val builtin: Boolean = true,
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
    /** The plugin version the frame runs (a registry plugin may run a newer one than the instance names). */
    val version: String? = null,
    /** A newer registry version that asks for other permissions and waits for the owner's approval. */
    val update: PluginUpdate? = null,
)

/** A registry version waiting for approval: it runs once the owner sets the instance's `version` to it (§7). */
@Serializable
data class PluginUpdate(
    val version: String,
    /** The newer version's `permissions`, as in its manifest. */
    val permissions: JsonObject = JsonObject(emptyMap()),
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
