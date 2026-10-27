package app.kinframe.frame.plugins

import app.kinframe.frame.plugins.js.PluginManifest
import app.kinframe.frame.plugins.js.PluginNet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant

/**
 * What the frame tells the server about its plugins, for the apps' settings forms (docs/plugins.md §6): the catalog of
 * plugins it can run, and per instance whether it runs, its last error and what it discovered (§6.2).
 */
object PluginReports {
    /** A plugin folder: `plugin.json` and `locales/<tag>.json` (the manifest's `@` keys are all the apps need). */
    class Source(val manifest: String, val locales: Map<String, String>)

    /**
     * The catalog (`PluginCatalog`): every plugin whose manifest is valid, as-is, with its manifest translations.
     * A broken one is left out, so the apps never offer what the frame would refuse.
     */
    fun catalog(sources: List<Source>, builtin: Boolean = true): JsonObject = buildJsonObject {
        put("plugins", JsonArray(sources.mapNotNull { s ->
            val manifest = runCatching { PluginManifest.parse(s.manifest) }.getOrNull() ?: return@mapNotNull null
            val raw = Json.parseToJsonElement(s.manifest) as JsonObject
            val locales = s.locales.mapNotNull { (tag, text) ->
                val o = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return@mapNotNull null
                val keys = o.filterKeys { it.startsWith("@") }.filterValues { (it as? JsonPrimitive)?.isString == true }
                if (keys.isEmpty()) null else tag to JsonObject(keys)
            }.toMap()
            buildJsonObject {
                put("manifest", raw)
                if (locales.isNotEmpty()) put("locales", JsonObject(locales.toSortedMap()))
                put("builtin", builtin)
            }.takeIf { manifest.id.isNotEmpty() }
        }))
    }

    /** The service types the catalog's `url` settings ask to discover (`"discover": "_hwenergy._tcp"`). */
    fun serviceTypes(manifests: List<PluginManifest>): Set<String> =
        manifests.flatMap { m -> m.settings.mapNotNull { s -> s.discover?.takeIf { s.type == "url" && SERVICE_TYPE.matches(it) } } }.toSortedSet()

    private val SERVICE_TYPE = Regex("^_[a-z0-9-]{1,40}\\._(tcp|udp)$")

    // ------------------------------------------------------------------------------------------- Home Assistant

    /** A Home Assistant entity, as the entity picker lists it. */
    data class Entity(val id: String, val name: String, val unit: String, val deviceClass: String)

    /**
     * Rendered by Home Assistant (`POST /api/template`): one line per entity with its id, friendly name, unit and
     * device class, tab-separated. Far smaller than `/api/states`, which carries every attribute.
     */
    const val ENTITY_TEMPLATE =
        "{% for s in states %}{{ s.entity_id }}\t{{ s.name | replace('\\t', ' ') | replace('\\n', ' ') }}\t" +
            "{{ s.attributes.unit_of_measurement | default('', true) }}\t{{ s.attributes.device_class | default('', true) }}\n{% endfor %}"

    const val MAX_ENTITIES = 3000

    /** Home Assistant trims the rendered text, so the last line may lack its empty trailing fields. */
    fun parseEntities(text: String): List<Entity> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t').map { it.trim() }
        if (!ENTITY_ID.matches(p[0])) null else Entity(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
    }.take(MAX_ENTITIES).toList()

    private val ENTITY_ID = Regex("^[a-z_]+\\.[a-z0-9_]+$")

    /** Whether [manifest] has entity settings, and [values] the connection to list them: `base_url` and `token`. */
    fun entityConnection(manifest: PluginManifest, values: JsonObject): Pair<String, String>? {
        if (manifest.settings.none { it.type == "entity" }) return null
        val base = (values["base_url"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val token = (values["token"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return base to token
    }

    /** Lists the entities through the instance's own network permissions ([net]: its `local_only` rule applies). */
    suspend fun fetchEntities(net: PluginNet, base: String, token: String): List<Entity> {
        val url = LocalAddress.baseUrl(base)?.resolve("api/template")?.toString() ?: throw IOException("bad address")
        val body = buildJsonObject { put("template", ENTITY_TEMPLATE) }.toString()
        val r = net.request("POST", url, mapOf("Authorization" to "Bearer $token"), body, "application/json")
        if (r.status != 200) throw IOException("HTTP ${r.status}")
        return parseEntities(r.body)
    }

    /** A short fingerprint of a connection, so a changed address or token lists the entities again. */
    fun connectionKey(base: String, token: String): String =
        MessageDigest.getInstance("SHA-256").digest("$base\n$token".toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------------------------------- status

    /** One instance's line in the status report. */
    data class InstanceReport(
        val instanceId: String,
        val running: Boolean,
        val error: String?,
        val updatedAtMs: Long?,
        val entities: List<Entity>?,
        /** The plugin version it runs; [update]: a newer registry version that waits for the owner's approval. */
        val version: String? = null,
        val update: app.kinframe.frame.plugins.registry.PluginRegistry.Version? = null,
    )

    /** The status report (`PluginStatusReport`). [withTimes] false leaves out what changes every update. */
    fun status(instances: List<InstanceReport>, services: Map<String, List<FoundService>>, withTimes: Boolean = true): JsonObject = buildJsonObject {
        put("instances", JsonArray(instances.map { i ->
            buildJsonObject {
                put("instance_id", i.instanceId)
                put("running", i.running)
                put("error", i.error?.take(300)?.let(::JsonPrimitive) ?: JsonNull)
                if (withTimes) put("updated_at", i.updatedAtMs?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
                put("version", i.version?.let(::JsonPrimitive) ?: JsonNull)
                put("update", i.update?.let { u -> buildJsonObject { put("version", u.version); u.permissions?.let { p -> put("permissions", p) } } } ?: JsonNull)
                i.entities?.let { list ->
                    put("entities", JsonArray(list.map { e ->
                        buildJsonObject {
                            put("entity_id", e.id)
                            if (e.name.isNotEmpty()) put("name", e.name)
                            if (e.unit.isNotEmpty()) put("unit", e.unit)
                            if (e.deviceClass.isNotEmpty()) put("device_class", e.deviceClass)
                        }
                    }))
                }
            }
        }))
        put("services", JsonObject(services.toSortedMap().mapValues { (_, list) ->
            JsonArray(list.take(50).map { s -> buildJsonObject { put("name", s.name.take(100)); put("url", s.url) } })
        }))
    }
}
