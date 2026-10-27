package app.kinframe.frame.plugins.js

import app.kinframe.frame.plugins.Slot
import app.kinframe.frame.plugins.Visibility
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
    /** The visibility a new instance starts with (`default_visibility`), e.g. *only when it changes* for a car. */
    val defaultVisibility: Visibility? = null,
    /** Named groups of setting values the owner can pick in the settings form (`presets`). */
    val presets: List<Preset> = emptyList(),
) {
    /**
     * A preset: [settings] fills in several settings at once. A string value may hold `{key}` placeholders for other
     * settings the owner types ([inputs]), e.g. the Kia/Hyundai entity names from a prefix:
     * `"battery_level": "sensor.{prefix}_ev_battery_level"`.
     */
    data class Preset(val id: String, val label: String, val settings: JsonObject) {
        val inputs: List<String> = settings.values.flatMap { v ->
            when (v) {
                is JsonPrimitive -> if (v.isString) PLACEHOLDER.findAll(v.content).map { it.groupValues[1] }.toList() else emptyList()
                is JsonArray -> v.flatMap { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.let { p -> PLACEHOLDER.findAll(p.content).map { it.groupValues[1] }.toList() } ?: emptyList() }
                else -> emptyList()
            }
        }.distinct()
    }

    fun preset(id: String): Preset? = presets.firstOrNull { it.id == id }

    /**
     * The owner's [values] with preset [id] applied: its settings set, placeholders filled from [values]. Null when an
     * input the preset needs is empty (the form asks for it first).
     */
    fun applyPreset(id: String, values: JsonObject): JsonObject? {
        val p = preset(id) ?: return null
        val inputs = p.inputs.associateWith { k -> (values[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty() }
        if (inputs.values.any { it.isEmpty() }) return null
        fun fill(s: String) = PLACEHOLDER.replace(s) { inputs.getValue(it.groupValues[1]) }
        val out = LinkedHashMap<String, JsonElement>(values)
        p.settings.forEach { (k, v) ->
            out[k] = when (v) {
                is JsonPrimitive -> if (v.isString) JsonPrimitive(fill(v.content)) else v
                is JsonArray -> JsonArray(v.map { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.let { JsonPrimitive(fill(it.content)) } ?: e })
                else -> v
            }
        }
        return JsonObject(out)
    }

    data class Refresh(val minSeconds: Int, val defaultSeconds: Int, val staleSeconds: Int?)

    /**
     * Each network entry names the setting that holds the address (`settings.base_url`) or a fixed https origin
     * (`https://api.example.org`). [actions]: the plugin may be asked to run its `action(ctx, id)` handler from a
     * button the owner taps (§4.2); off by default so a widget can't look interactive without saying so. [location]:
     * `ctx.location`, the frame's place (coarse), shown to the owner before they install the plugin.
     */
    data class Permissions(val http: List<String>, val websocket: List<String>, val actions: Boolean, val location: Boolean = false)

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
        /** An `entity` setting that takes a list (several solar inverters summed into one node). */
        val multiple: Boolean = false,
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
        const val LOCAL_MIN_SECONDS = 5
        private val ORIGIN = Regex("^https://[a-z0-9.-]+(:\\d{1,5})?$")
        private val json = Json { ignoreUnknownKeys = false }
        private val PLACEHOLDER = Regex("\\{([a-z][a-z0-9_]{0,31})\\}")
        const val MAX_PRESETS = 12
        private val VISIBILITY = mapOf("always" to Visibility.Always, "on_change" to Visibility.OnChange, "on_tap" to Visibility.OnTap)

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
            if (min < 1 || def < min || def > 86_400) throw ManifestException("refresh: need 1 ≤ min_seconds ≤ default_seconds ≤ 86400")

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
                    multiple = ((s["multiple"] as? JsonPrimitive)?.booleanOrNull ?: false).also { m ->
                        if (m && type != "entity") throw ManifestException("settings[$i]: only entity settings can be multiple")
                    },
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
            val location = p["location"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: throw ManifestException("permissions.location must be true or false") } ?: false
            val http = net("http")
            // Every few seconds is for devices in the house (a P1 meter updates every second), never for an internet
            // API that every frame would then call that often.
            if (min < LOCAL_MIN_SECONDS && http.any { v -> !v.startsWith("settings.") || settings.none { it.key == v.removePrefix("settings.") && it.localOnly } }) {
                throw ManifestException("refresh: min_seconds below $LOCAL_MIN_SECONDS only for plugins whose http goes to local_only settings")
            }

            val defaultVisibility = str("default_visibility", required = false)?.let { v ->
                VISIBILITY[v] ?: throw ManifestException("default_visibility must be one of ${VISIBILITY.keys.joinToString()}")
            }
            val presets = parsePresets(o["presets"], settings)

            return PluginManifest(
                id = id, name = str("name")!!, version = version, api = api, description = str("description", required = false, max = 400),
                slots = slots, refresh = Refresh(min, def, stale), permissions = Permissions(http, net("websocket"), actions, location), settings = settings,
                defaultVisibility = defaultVisibility, presets = presets,
            )
        }

        private fun parsePresets(e: JsonElement?, settings: List<SettingSpec>): List<Preset> {
            if (e == null) return emptyList()
            val arr = e as? JsonArray ?: throw ManifestException("presets must be an array")
            if (arr.size > MAX_PRESETS) throw ManifestException("at most $MAX_PRESETS presets")
            val presets = arr.mapIndexed { i, pe ->
                val p = pe as? JsonObject ?: throw ManifestException("presets[$i] must be an object")
                fun s(key: String, max: Int): String {
                    val v = (p[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ManifestException("presets[$i].$key must be a string")
                    if (v.isEmpty() || v.length > max) throw ManifestException("presets[$i].$key must be 1–$max characters")
                    return v
                }
                val id = s("id", 32)
                if (!KEY.matches(id)) throw ManifestException("presets[$i].id must be snake_case")
                val label = s("label", 60)
                val values = p["settings"] as? JsonObject ?: throw ManifestException("presets[$i].settings must be an object")
                if (values.isEmpty()) throw ManifestException("presets[$i].settings is empty")
                values.forEach { (k, v) ->
                    val spec = settings.firstOrNull { it.key == k } ?: throw ManifestException("presets[$i]: no setting $k")
                    if (spec.type == "secret") throw ManifestException("presets[$i]: $k is a secret")
                    if (!fits(spec, v)) throw ManifestException("presets[$i]: $k doesn't fit a ${spec.type} setting")
                }
                Preset(id, label, values).also { pr ->
                    pr.inputs.forEach { k ->
                        val spec = settings.firstOrNull { it.key == k } ?: throw ManifestException("presets[$i]: placeholder {$k} names no setting")
                        if (spec.type != "text") throw ManifestException("presets[$i]: placeholder {$k} must be a text setting")
                        if (k in values) throw ManifestException("presets[$i]: {$k} is set by the preset itself")
                    }
                }
            }
            if (presets.map { it.id }.toSet().size != presets.size) throw ManifestException("duplicate preset ids")
            return presets
        }

        private fun fits(spec: SettingSpec, v: JsonElement): Boolean {
            fun str(e: JsonElement) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
            return when (spec.type) {
                "number" -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull() != null
                "boolean" -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != null
                "choice" -> str(v)?.let { it in spec.options } ?: false
                "entity" -> str(v) != null || (spec.multiple && v is JsonArray && v.all { str(it) != null })
                else -> str(v) != null
            }
        }
    }
}

class ManifestException(message: String) : IllegalArgumentException(message)
