package app.kinframe.core.plugin.settings

import app.kinframe.core.api.CatalogPlugin
import app.kinframe.core.api.DiscoveredEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** Setting types of a manifest (docs/plugins.md §3.1). */
object SettingType {
    const val TEXT = "text"
    const val NUMBER = "number"
    const val BOOLEAN = "boolean"
    const val CHOICE = "choice"
    const val URL = "url"
    const val SECRET = "secret"
    const val ENTITY = "entity"
    const val COLOR = "color"
    const val DURATION = "duration"
    /** An IANA time zone id, "Asia/Tokyo" ([TimeZones]). */
    const val TIMEZONE = "timezone"
}

object Slots {
    const val TOP_LEFT = "top-left"
    const val TOP_RIGHT = "top-right"
    val all = listOf(TOP_LEFT, TOP_RIGHT)
}

object Visibility {
    const val ALWAYS = "always"
    const val ON_CHANGE = "on_change"
    const val ON_TAP = "on_tap"
    val all = listOf(ALWAYS, ON_CHANGE, ON_TAP)
}

/** One field of the settings form, read from a manifest setting. */
data class FormSetting(
    val key: String,
    val type: String,
    val label: String,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val placeholder: String? = null,
    val help: String? = null,
    val options: List<String> = emptyList(),
    /** mDNS service type whose finds the form offers as suggestions (`url` settings). */
    val discover: String? = null,
    /** Entity filters: the part of the entity id before the dot, and the unit. */
    val domain: String? = null,
    val unit: String? = null,
    val multiple: Boolean = false,
    val localOnly: Boolean = false,
) {
    /** Required for an enabled instance: `required` without a `default` (the server's rule). */
    val mustFill: Boolean get() = required && (default == null || default is JsonNull)
}

/** A named group of setting values; string values may hold `{key}` placeholders for text settings ([inputs]). */
data class FormPreset(val id: String, val label: String, val settings: JsonObject) {
    val inputs: List<String> = settings.values.flatMap { v ->
        when (v) {
            is JsonPrimitive -> placeholders(v)
            is JsonArray -> v.flatMap { e -> (e as? JsonPrimitive)?.let(::placeholders).orEmpty() }
            else -> emptyList()
        }
    }.distinct()

    private fun placeholders(p: JsonPrimitive): List<String> =
        if (p.isString) PLACEHOLDER.findAll(p.content).map { it.groupValues[1] }.toList() else emptyList()

    companion object {
        internal val PLACEHOLDER = Regex("\\{([a-z][a-z0-9_]{0,31})\\}")
    }
}

/**
 * A catalog plugin as the settings form needs it (docs/plugins.md §6.1). The reader is lenient: the frame validated
 * the manifest before it reported it, and checks again before it runs anything.
 */
data class FormPlugin(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val slots: List<String> = Slots.all,
    val defaultVisibility: String = Visibility.ALWAYS,
    val settings: List<FormSetting> = emptyList(),
    val presets: List<FormPreset> = emptyList(),
    /** Fixed internet origins from `permissions.http` (not `settings.<key>`): shown before installing. */
    val hosts: List<String> = emptyList(),
    /** `permissions.location`: reads the frame's (coarse) place. */
    val location: Boolean = false,
    val builtin: Boolean = false,
    /** Language tag → `@name`, `@setting.<key>` … */
    val locales: Map<String, Map<String, String>> = emptyMap(),
) {
    fun setting(key: String): FormSetting? = settings.firstOrNull { it.key == key }
    fun preset(id: String): FormPreset? = presets.firstOrNull { it.id == id }

    /** The translations for [tag]: the exact tag, then its language, then English (as the frame resolves them). */
    fun strings(tag: String): Map<String, String> {
        val lang = tag.substringBefore('-').lowercase(Locale.ROOT)
        val exact = locales[tag] ?: locales.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.value
        return locales["en"].orEmpty() + locales[lang].orEmpty() + exact.orEmpty()
    }

    private fun t(tag: String, key: String): String? = strings(tag)[key]?.takeIf { it.isNotBlank() }

    fun name(tag: String): String = t(tag, "@name") ?: name
    fun description(tag: String): String? = t(tag, "@description") ?: description
    fun label(s: FormSetting, tag: String): String = t(tag, "@setting.${s.key}") ?: s.label
    fun help(s: FormSetting, tag: String): String? = t(tag, "@setting.${s.key}.help") ?: s.help
    fun placeholder(s: FormSetting, tag: String): String? = t(tag, "@setting.${s.key}.placeholder") ?: s.placeholder
    fun optionLabel(s: FormSetting, value: String, tag: String): String = t(tag, "@option.${s.key}.$value") ?: value
    fun presetLabel(p: FormPreset, tag: String): String = t(tag, "@preset.${p.id}") ?: p.label

    /** The corner a new instance gets: top-right when the plugin allows it. */
    val defaultSlot: String get() = if (Slots.TOP_RIGHT in slots || slots.isEmpty()) Slots.TOP_RIGHT else slots.first()

    /**
     * [values] with preset [presetId] applied: its settings set (overwriting), `{placeholders}` filled from [values].
     * Null when the preset is unknown or a placeholder's text setting is still empty (the form asks for it first).
     */
    fun applyPreset(presetId: String, values: JsonObject): JsonObject? {
        val p = preset(presetId) ?: return null
        val inputs = p.inputs.associateWith { k -> (values[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty() }
        if (inputs.values.any { it.isEmpty() }) return null
        fun fill(s: String) = FormPreset.PLACEHOLDER.replace(s) { m -> inputs[m.groupValues[1]] ?: m.value }
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

    companion object {
        /** Reads a catalog entry; null when the manifest has no id. */
        fun read(c: CatalogPlugin): FormPlugin? {
            val m = c.manifest
            fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun bool(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
            fun strings(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
            val id = str(m, "id")?.takeIf { it.isNotBlank() } ?: return null
            val settings = (m["settings"] as? JsonArray).orEmpty().mapNotNull { e ->
                val s = e as? JsonObject ?: return@mapNotNull null
                val key = str(s, "key") ?: return@mapNotNull null
                FormSetting(
                    key = key, type = str(s, "type") ?: SettingType.TEXT, label = str(s, "label") ?: key,
                    required = bool(s, "required"), default = s["default"]?.takeIf { it !is JsonNull },
                    placeholder = str(s, "placeholder"), help = str(s, "help"), options = strings(s["options"]),
                    discover = str(s, "discover"), domain = str(s, "domain"), unit = str(s, "unit"),
                    multiple = bool(s, "multiple"), localOnly = bool(s, "local_only"),
                )
            }
            val presets = (m["presets"] as? JsonArray).orEmpty().mapNotNull { e ->
                val p = e as? JsonObject ?: return@mapNotNull null
                val pid = str(p, "id") ?: return@mapNotNull null
                FormPreset(pid, str(p, "label") ?: pid, p["settings"] as? JsonObject ?: return@mapNotNull null)
            }
            val perms = m["permissions"] as? JsonObject ?: JsonObject(emptyMap())
            return FormPlugin(
                id = id, name = str(m, "name") ?: id, version = str(m, "version"), description = str(m, "description"),
                slots = strings(m["slots"]).filter { it in Slots.all }.ifEmpty { Slots.all },
                defaultVisibility = str(m, "default_visibility")?.takeIf { it in Visibility.all } ?: Visibility.ALWAYS,
                settings = settings, presets = presets,
                hosts = strings(perms["http"]).filter { !it.startsWith("settings.") },
                location = bool(perms, "location"), builtin = c.builtin, locales = c.locales,
            )
        }

        /** The entities [s] can pick from: its `domain` and `unit` when the manifest names them, matching [query]. */
        fun entities(all: List<DiscoveredEntity>, s: FormSetting, query: String = ""): List<DiscoveredEntity> {
            val q = query.trim().lowercase(Locale.ROOT)
            return all.filter { e ->
                (s.domain == null || e.entityId.substringBefore('.') == s.domain) &&
                    (s.unit == null || e.unit == s.unit) &&
                    (q.isEmpty() || e.entityId.lowercase(Locale.ROOT).contains(q) || e.name?.lowercase(Locale.ROOT)?.contains(q) == true)
            }
        }
    }
}
