package app.kinframe.frame.plugins.js

import app.kinframe.frame.plugins.Slot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A plugin's `plugin.json` (docs/plugins.md §3.1), validated. */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val api: Int,
    val description: String?,
    val slots: List<Slot>,
    val refresh: Refresh,
    val permissions: Permissions,
    val settings: List<SettingSpec>,
) {
    data class Refresh(val minSeconds: Int, val defaultSeconds: Int, val staleSeconds: Int?)

    /**
     * Each network entry names the setting that holds the address (`settings.base_url`) or a fixed https origin
     * (`https://api.example.org`). [actions]: the plugin may be asked to run its `action(ctx, id)` handler from a
     * button the owner taps (§4.2); off by default so a widget can't look interactive without saying so.
     */
    data class Permissions(val http: List<String>, val websocket: List<String>, val actions: Boolean)

    data class SettingSpec(
        val key: String,
        val type: String,
        val label: String,
        val required: Boolean,
        val default: JsonElement?,
        val localOnly: Boolean,
        val options: List<String>,
        val discover: String?,
        val help: String?,
    )

    fun setting(key: String): SettingSpec? = settings.firstOrNull { it.key == key }

    /** The owner's settings with the manifest's defaults filled in, as `ctx.settings` sees them. */
    fun withDefaults(values: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(values)
        settings.forEach { s -> if (s.default != null && (out[s.key] == null || out[s.key] is JsonNull)) out[s.key] = s.default }
        return JsonObject(out)
    }

    companion object {
        const val API = 1
        val SETTING_TYPES = setOf("text", "number", "boolean", "choice", "url", "secret", "entity", "color", "duration")
        private val ID = Regex("^[a-z0-9][a-z0-9-]*(\\.[a-z0-9][a-z0-9-]*)+$")
        private val KEY = Regex("^[a-z][a-z0-9_]{0,31}$")
        private val VERSION = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$")
        private val ORIGIN = Regex("^https://[a-z0-9.-]+(:\\d{1,5})?$")
        private val json = Json { ignoreUnknownKeys = false }

        fun parse(text: String): PluginManifest {
            val o = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrElse { throw ManifestException("not a JSON object") }
            fun str(key: String, from: JsonObject = o, required: Boolean = true, max: Int = 200): String? {
                val v = from[key] ?: return if (required) throw ManifestException("missing $key") else null
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManifestException("$key must be a string")
                if (s.length > max) throw ManifestException("$key too long")
                return s
            }
            fun int(key: String, from: JsonObject): Int? = from[key]?.let { (it as? JsonPrimitive)?.intOrNull ?: throw ManifestException("$key must be an integer") }

            val id = str("id")!!
            if (id.length > 64 || !ID.matches(id)) throw ManifestException("id must look like community.my-plugin")
            val version = str("version")!!
            if (!VERSION.matches(version)) throw ManifestException("version must be semver")
            val api = int("api", o) ?: throw ManifestException("missing api")
            if (api != API) throw ManifestException("unsupported api $api")

            val slots = (o["slots"] as? JsonArray)?.map { e ->
                when ((e as? JsonPrimitive)?.contentOrNull) { "top-left" -> Slot.TopLeft; "top-right" -> Slot.TopRight; else -> throw ManifestException("unknown slot $e") }
            } ?: listOf(Slot.TopLeft, Slot.TopRight)

            val r = o["refresh"] as? JsonObject ?: JsonObject(emptyMap())
            val min = int("min_seconds", r) ?: 5
            val def = int("default_seconds", r) ?: maxOf(min, 30)
            val stale = int("stale_seconds", r)
            if (min < 2 || def < min || def > 86_400) throw ManifestException("refresh: need 2 ≤ min_seconds ≤ default_seconds ≤ 86400")

            val settings = (o["settings"] as? JsonArray ?: JsonArray(emptyList())).mapIndexed { i, e ->
                val s = e as? JsonObject ?: throw ManifestException("settings[$i] must be an object")
                val key = str("key", s)!!
                if (!KEY.matches(key)) throw ManifestException("settings[$i].key must be snake_case")
                val type = str("type", s)!!
                if (type !in SETTING_TYPES) throw ManifestException("settings[$i].type unknown: $type")
                SettingSpec(
                    key = key, type = type, label = str("label", s, required = false) ?: key,
                    required = (s["required"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    default = s["default"],
                    localOnly = (s["local_only"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    options = (s["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                    discover = str("discover", s, required = false),
                    help = str("help", s, required = false, max = 400),
                )
            }
            if (settings.map { it.key }.toSet().size != settings.size) throw ManifestException("duplicate setting keys")

            val p = o["permissions"] as? JsonObject ?: JsonObject(emptyMap())
            fun net(kind: String): List<String> = (p[kind] as? JsonArray)?.map { e ->
                val v = (e as? JsonPrimitive)?.contentOrNull ?: throw ManifestException("permissions.$kind entries are strings")
                if (v.startsWith("settings.")) {
                    val s = settings.firstOrNull { it.key == v.removePrefix("settings.") } ?: throw ManifestException("permissions.$kind: no setting $v")
                    if (s.type != "url") throw ManifestException("permissions.$kind: $v is not a url setting")
                } else if (!ORIGIN.matches(v)) {
                    throw ManifestException("permissions.$kind: $v must be settings.<key> or an https origin")
                }
                v
            } ?: emptyList()
            val actions = (p["actions"] as? JsonPrimitive)?.booleanOrNull ?: false

            return PluginManifest(
                id = id, name = str("name")!!, version = version, api = api, description = str("description", required = false, max = 400),
                slots = slots, refresh = Refresh(min, def, stale), permissions = Permissions(net("http"), net("websocket"), actions), settings = settings,
            )
        }
    }
}

class ManifestException(message: String) : IllegalArgumentException(message)
