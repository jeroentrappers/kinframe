package app.kinframe.core.plugin.settings

import app.kinframe.core.api.FieldError
import app.kinframe.core.api.FramePlugins
import app.kinframe.core.api.PluginInstanceStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/** `FrameSettings.plugins.layout`: off, the photo's top corners, or a band beside or above the photo. */
data class PluginLayout(val mode: String = MODE_OFF, val side: String = SIDE_RIGHT, val background: String = BACKGROUND_PAPER) {
    companion object {
        const val MODE_OFF = "off"
        const val MODE_OVERLAY = "overlay"
        const val MODE_BAND = "band"
        const val MODE_SPLIT = "split"
        const val SIDE_LEFT = "left"
        const val SIDE_RIGHT = "right"
        const val BACKGROUND_PAPER = "paper"
        const val BACKGROUND_PHOTO = "photo"
    }
}

/**
 * One plugin instance as the form edits it. [secretWrites] are the secrets the owner changed in this session
 * (a value sets, null removes); only those are sent. [extra] keeps fields this app doesn't know (e.g. `order`).
 */
data class PluginInstanceDraft(
    val instanceId: String,
    val pluginId: String,
    val version: String? = null,
    val slot: String = Slots.TOP_RIGHT,
    val visibility: String = Visibility.ALWAYS,
    val enabled: Boolean = true,
    val settings: JsonObject = JsonObject(emptyMap()),
    /** Read-only: the secrets that have a value on the server. */
    val secretsSet: Set<String> = emptySet(),
    val secretWrites: Map<String, String?> = emptyMap(),
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    /** Whether secret [key] has a value after this session's writes are applied. */
    fun secretSet(key: String): Boolean = if (key in secretWrites) !secretWrites[key].isNullOrEmpty() else key in secretsSet

    /** The instance as sent: no `secrets_set`, and `secrets` only for the secrets changed. */
    fun toJson(): JsonObject = buildJsonObject {
        extra.forEach { (k, v) -> put(k, v) }
        put("instance_id", JsonPrimitive(instanceId))
        put("plugin_id", JsonPrimitive(pluginId))
        version?.let { put("version", JsonPrimitive(it)) }
        put("slot", JsonPrimitive(slot))
        put("visibility", JsonPrimitive(visibility))
        put("enabled", JsonPrimitive(enabled))
        put("settings", settings)
        if (secretWrites.isNotEmpty()) {
            put("secrets", JsonObject(secretWrites.mapValues { (_, v) -> if (v.isNullOrEmpty()) JsonNull else JsonPrimitive(v) }))
        }
    }

    companion object {
        private val KNOWN = setOf("instance_id", "plugin_id", "version", "slot", "visibility", "enabled", "settings", "secrets", "secrets_set")

        fun fromJson(o: JsonObject): PluginInstanceDraft? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = str("instance_id") ?: return null
            return PluginInstanceDraft(
                instanceId = id, pluginId = str("plugin_id").orEmpty(), version = str("version"),
                slot = str("slot") ?: Slots.TOP_RIGHT, visibility = str("visibility") ?: Visibility.ALWAYS,
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                settings = o["settings"] as? JsonObject ?: JsonObject(emptyMap()),
                secretsSet = (o["secrets_set"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty(),
                extra = JsonObject(o.filterKeys { it !in KNOWN }),
            )
        }
    }
}

/** A field problem of one instance: [key] is the setting (null for the instance itself), [code] the server's reason. */
data class InstanceFieldError(val key: String?, val field: String, val code: String) {
    companion object {
        const val REQUIRED = "required"
        const val TYPE = "type"
        /** A `timezone` setting holding an id that isn't a known time zone. */
        const val TIMEZONE = "timezone"
    }
}

/** How an instance is doing, for its status line. */
sealed interface InstanceStatus {
    data object Off : InstanceStatus
    data object Waiting : InstanceStatus
    data object Running : InstanceStatus
    data object NotOnFrame : InstanceStatus
    data class Failed(val message: String) : InstanceStatus
    /** Its plugin was withdrawn from the community registry, for [reason] (§7); best removed. */
    data class Withdrawn(val reason: String) : InstanceStatus
}

/**
 * A newer registry version waiting for the owner's approval. [asks] are the changes when the catalog holds the version
 * the frame runs ([diffed]), else everything the new version may do; [fewer]: diffed, and it only drops permissions.
 */
data class PendingUpdate(val version: String, val asks: List<Permission>, val diffed: Boolean, val fewer: Boolean)

/** The plugin settings form's logic (docs/plugins.md §6, §6.1), shared by the family app's screens. */
object PluginSettings {
    const val MAX_INSTANCES = 8
    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun layout(settings: JsonObject?): PluginLayout {
        val l = (settings?.get("plugins") as? JsonObject)?.get("layout") as? JsonObject ?: return PluginLayout()
        fun str(k: String) = (l[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val d = PluginLayout()
        return PluginLayout(str("mode") ?: d.mode, str("side") ?: d.side, str("background") ?: d.background)
    }

    fun instances(settings: JsonObject?): List<PluginInstanceDraft> =
        ((settings?.get("plugins") as? JsonObject)?.get("instances") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(PluginInstanceDraft::fromJson) }

    /** A new instance id: `p-` and 10 random lowercase letters or digits, not one of [taken]. */
    fun newInstanceId(taken: Collection<String> = emptyList(), random: Random = Random.Default): String {
        while (true) {
            val id = "p-" + (1..10).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }.joinToString("")
            if (id !in taken) return id
        }
    }

    /** A new, disabled instance of [plugin]: top-right when allowed, the manifest's `default_visibility`. */
    fun newInstance(plugin: FormPlugin, taken: Collection<String> = emptyList(), random: Random = Random.Default) = PluginInstanceDraft(
        instanceId = newInstanceId(taken, random), pluginId = plugin.id, version = plugin.version,
        slot = plugin.defaultSlot, visibility = plugin.defaultVisibility, enabled = false,
    )

    /** What a field shows: the value, else the manifest's default. */
    fun displayValue(s: FormSetting, d: PluginInstanceDraft): JsonElement? = d.settings[s.key]?.takeIf { it !is JsonNull } ?: s.default

    /** Sets [key]; an empty value (null, blank text, empty list) is left out of `settings`. */
    fun withValue(d: PluginInstanceDraft, key: String, value: JsonElement?): PluginInstanceDraft {
        val out = LinkedHashMap(d.settings)
        if (isEmpty(value)) out.remove(key) else out[key] = value!!
        return d.copy(settings = JsonObject(out))
    }

    /** The value typed into a text-like field of [s]: a number for `number` (left as text when it isn't one). */
    fun parseText(s: FormSetting, text: String): JsonElement? {
        val t = if (s.type == SettingType.TEXT) text else text.trim()
        if (t.isBlank()) return null
        if (s.type == SettingType.NUMBER) {
            t.toLongOrNull()?.let { return JsonPrimitive(it) }
            t.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return JsonPrimitive(it) }
        }
        return JsonPrimitive(t)
    }

    /** The text a text-like field shows for [value]. */
    fun text(value: JsonElement?): String = when (value) {
        null, is JsonNull -> ""
        is JsonPrimitive -> value.content
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
        else -> value.toString()
    }

    /** The entity ids of an entity field (one, or a list for `multiple`). */
    fun entityIds(value: JsonElement?): List<String> = when (value) {
        is JsonPrimitive -> value.contentOrNull?.takeIf { value.isString && it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        else -> emptyList()
    }

    fun entityValue(s: FormSetting, ids: List<String>): JsonElement? = when {
        ids.isEmpty() -> null
        s.multiple -> JsonArray(ids.distinct().map { JsonPrimitive(it) })
        else -> JsonPrimitive(ids.last())
    }

    fun isEmpty(v: JsonElement?): Boolean = when (v) {
        null, is JsonNull -> true
        is JsonPrimitive -> v.isString && v.content.isBlank()
        is JsonArray -> v.isEmpty()
        else -> false
    }

    /**
     * The client-side check, the same as the server's: an enabled instance needs every `required` setting without a
     * `default` filled in, and those secrets set (or being set). Also flags a number field holding text and a time zone
     * field holding an id that isn't a known zone (empty is fine unless required).
     */
    fun check(plugin: FormPlugin, d: PluginInstanceDraft): List<InstanceFieldError> {
        val out = mutableListOf<InstanceFieldError>()
        plugin.settings.forEach { s ->
            val v = d.settings[s.key]
            if (s.type == SettingType.NUMBER && v is JsonPrimitive && v.isString) out += InstanceFieldError(s.key, "settings", InstanceFieldError.TYPE)
            if (s.type == SettingType.TIMEZONE && !isEmpty(v) && !(v is JsonPrimitive && v.isString && TimeZones.known(v.content))) {
                out += InstanceFieldError(s.key, "settings", InstanceFieldError.TIMEZONE)
            }
        }
        if (!d.enabled) return out
        plugin.settings.filter { it.mustFill }.forEach { s ->
            if (s.type == SettingType.SECRET) {
                if (!d.secretSet(s.key)) out += InstanceFieldError(s.key, "secrets", InstanceFieldError.REQUIRED)
            } else if (isEmpty(d.settings[s.key])) {
                out += InstanceFieldError(s.key, "settings", InstanceFieldError.REQUIRED)
            }
        }
        return out
    }

    /** Whether [d] could be switched on: [check] passes with `enabled`. */
    fun complete(plugin: FormPlugin, d: PluginInstanceDraft): Boolean = check(plugin, d.copy(enabled = true)).isEmpty()

    /** The merge patch that sets the whole instance list (an array, so it's always sent in full). */
    fun instancesPatch(instances: List<PluginInstanceDraft>): JsonObject = buildJsonObject {
        put("plugins", buildJsonObject { put("instances", JsonArray(instances.map { it.toJson() })) })
    }

    fun layoutPatch(field: String, value: String): JsonObject = buildJsonObject {
        put("plugins", buildJsonObject { put("layout", buildJsonObject { put(field, JsonPrimitive(value)) }) })
    }

    private val FIELD = Regex("^plugins\\.instances\\.(\\d+)\\.([a-z_]+)(?:\\.(.+))?$")

    /**
     * The 422 field errors of a save of [sent], by instance id: `plugins.instances.0.settings.grid` → (grid,
     * settings, code); `…secrets.token` → (token, secrets, code); `…slot` → (null, slot, code).
     */
    fun fieldErrors(errors: List<FieldError>, sent: List<PluginInstanceDraft>): Map<String, List<InstanceFieldError>> {
        val out = LinkedHashMap<String, MutableList<InstanceFieldError>>()
        errors.forEach { e ->
            val m = FIELD.matchEntire(e.field) ?: return@forEach
            val inst = sent.getOrNull(m.groupValues[1].toInt()) ?: return@forEach
            val field = m.groupValues[2]
            val key = m.groupValues[3].takeIf { it.isNotEmpty() && (field == "settings" || field == "secrets") }
            out.getOrPut(inst.instanceId) { mutableListOf() } += InstanceFieldError(key, field, e.code)
        }
        return out
    }

    /** The status line of [d]: withdrawn, the frame's error, "waiting" before its first report, nothing when it runs. */
    fun status(d: PluginInstanceDraft, plugins: FramePlugins?, onFrame: Boolean): InstanceStatus {
        withdrawnReason(plugins?.instances?.firstOrNull { it.instanceId == d.instanceId }?.error)?.let { return InstanceStatus.Withdrawn(it) }
        if (plugins != null && plugins.catalogAt != null && !onFrame) return InstanceStatus.NotOnFrame
        if (!d.enabled) return InstanceStatus.Off
        val s = plugins?.instances?.firstOrNull { it.instanceId == d.instanceId } ?: return InstanceStatus.Waiting
        return when {
            !s.error.isNullOrBlank() -> InstanceStatus.Failed(s.error)
            s.running -> InstanceStatus.Running
            else -> InstanceStatus.Waiting
        }
    }

    // ---------------------------------------------------------------- community plugins (§7)

    private const val WITHDRAWN = "withdrawn:"

    /** The registry's reason when the frame reports the plugin as withdrawn (`withdrawn: …`), else null. */
    fun withdrawnReason(error: String?): String? =
        error?.takeIf { it.startsWith(WITHDRAWN, ignoreCase = true) }?.substring(WITHDRAWN.length)?.trim()

    /** What [to] allows that [from] doesn't ([Pair.first]), and what it no longer does ([Pair.second]). */
    fun permissionChanges(from: PluginPermissions, to: PluginPermissions): Pair<List<Permission>, List<Permission>> =
        (to - from).items to (from - to).items

    /**
     * The update of [d] waiting for approval, or null (none, or [d] already names that version). Diffed against the
     * catalog's manifest when that is the version the frame runs, else the new version's permissions in full.
     */
    fun pendingUpdate(plugin: FormPlugin?, d: PluginInstanceDraft, st: PluginInstanceStatus?): PendingUpdate? {
        val u = st?.update ?: return null
        if (u.version.isBlank() || d.version == u.version) return null
        val next = PluginPermissions.read(u.permissions)
        val current = st.version?.takeIf { it.isNotBlank() } ?: d.version
        if (plugin != null && current != null && plugin.version == current) {
            val (added, removed) = permissionChanges(plugin.permissions, next)
            if (added.isNotEmpty() || removed.isNotEmpty()) return PendingUpdate(u.version, added, diffed = true, fewer = added.isEmpty())
        }
        return PendingUpdate(u.version, next.items, diffed = false, fewer = false)
    }

    /** The instance list with [version] approved for instance [instanceId]: saved like any other change. */
    fun approveUpdate(list: List<PluginInstanceDraft>, instanceId: String, version: String): List<PluginInstanceDraft> =
        list.map { if (it.instanceId == instanceId) it.copy(version = version) else it }
}
