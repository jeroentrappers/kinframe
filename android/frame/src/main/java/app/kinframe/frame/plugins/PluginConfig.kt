package app.kinframe.frame.plugins

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Off: no widgets. Overlay: top corners of the photo. Band: a 3:2 photo and a band beside or above it (§2.1). */
@Serializable
enum class PluginLayoutMode { @SerialName("off") Off, @SerialName("overlay") Overlay, @SerialName("band") Band }

@Serializable
enum class BandSide { @SerialName("left") Left, @SerialName("right") Right }

@Serializable
enum class BandBackground { @SerialName("paper") Paper, @SerialName("photo") Photo }

@Serializable
enum class Slot { @SerialName("top-left") TopLeft, @SerialName("top-right") TopRight }

/** Per instance (§2): always, only for 30 s after the view changes, or only after a tap on the screen. */
@Serializable
enum class Visibility { @SerialName("always") Always, @SerialName("on_change") OnChange, @SerialName("on_tap") OnTap }

/** The frame's layout choice; per frame, set by the owner. */
@Serializable
data class PluginLayoutConfig(
    val mode: PluginLayoutMode = PluginLayoutMode.Off,
    val side: BandSide = BandSide.Right,
    /** Read for compatibility with stored layouts, no longer used: whenever the band shows, it holds the clock. */
    @SerialName("clock_in_band") val clockInBand: Boolean = false,
    val background: BandBackground = BandBackground.Paper,
)

/**
 * One plugin instance as the frame settings hold it (`plugins.instances[]`, docs/plugins.md §6). [secrets] are the
 * values of the plugin's `secret` settings: people can only write them, the frame gets them back in full.
 */
@Serializable
data class InstanceConfig(
    @SerialName("instance_id") val instanceId: String,
    @SerialName("plugin_id") val pluginId: String,
    val version: String = "builtin",
    val slot: Slot = Slot.TopRight,
    val order: Int = 0,
    val visibility: Visibility = Visibility.Always,
    val enabled: Boolean = true,
    val settings: JsonObject = JsonObject(emptyMap()),
    val secrets: JsonObject = JsonObject(emptyMap()),
) {
    /** What the plugin reads as `ctx.settings`: its settings with the secrets. */
    val values: JsonObject get() = if (secrets.isEmpty()) settings else JsonObject(settings + secrets)
}

/**
 * A new instance of a plugin with its manifest's defaults (docs/plugins.md §3.1): top right unless the plugin only
 * allows the other corner, and its `default_visibility`.
 */
fun newInstanceConfig(
    instanceId: String,
    manifest: app.kinframe.frame.plugins.js.PluginManifest,
    enabled: Boolean = true,
    settings: JsonObject = JsonObject(emptyMap()),
): InstanceConfig = InstanceConfig(
    instanceId = instanceId, pluginId = manifest.id, version = manifest.version,
    slot = if (Slot.TopRight in manifest.slots) Slot.TopRight else manifest.slots.first(),
    visibility = manifest.defaultVisibility ?: Visibility.Always, enabled = enabled, settings = settings,
)

/**
 * The frame's plugin settings: `plugins.layout` and `plugins.instances` of the frame settings ([raw], the server's
 * document with local edits on top), so the apps and the frame edit the same thing (docs/plugins.md §6). A change
 * made on the frame goes into the settings through the outbox ([edit]). Frames that ran plugins before (step 1 kept
 * them in this SharedPreferences file) and frames without a session use the local copy until the settings have a
 * `plugins` part; [migrate] moves it there.
 */
class PluginPrefs(
    context: Context,
    private val raw: StateFlow<JsonObject>,
    scope: CoroutineScope,
    /** Queues a settings merge patch; false when there is no session to send it with. */
    private val edit: (JsonObject) -> Boolean,
) {
    private val sp = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)

    private val localLayout = MutableStateFlow(readLayout())
    private val localInstances = MutableStateFlow(readInstances())

    val layout: StateFlow<PluginLayoutConfig> = combine(raw, localLayout) { r, l -> PluginSettingsDoc.layout(r) ?: l }
        .stateIn(scope, SharingStarted.Eagerly, PluginSettingsDoc.layout(raw.value) ?: localLayout.value)
    val instances: StateFlow<List<InstanceConfig>> = combine(raw, localInstances) { r, l -> PluginSettingsDoc.instances(r) ?: l }
        .stateIn(scope, SharingStarted.Eagerly, PluginSettingsDoc.instances(raw.value) ?: localInstances.value)

    private fun readLayout(): PluginLayoutConfig =
        sp.getString("layout", null)?.let { runCatching { PluginSettingsDoc.json.decodeFromString(PluginLayoutConfig.serializer(), it) }.getOrNull() } ?: PluginLayoutConfig()

    private fun readInstances(): List<InstanceConfig> =
        sp.getString("instances", null)?.let { runCatching { PluginSettingsDoc.json.decodeFromString(ListSerializer(InstanceConfig.serializer()), it) }.getOrNull() }
            ?: emptyList()

    fun setLayout(l: PluginLayoutConfig) = save(l, instances.value)

    fun upsert(i: InstanceConfig) = save(layout.value, instances.value.filter { it.instanceId != i.instanceId } + i)

    fun remove(instanceId: String) = save(layout.value, instances.value.filter { it.instanceId != instanceId })

    private fun save(l: PluginLayoutConfig, list: List<InstanceConfig>) {
        if (edit(PluginSettingsDoc.patch(l, list))) return
        sp.edit {
            putString("layout", PluginSettingsDoc.json.encodeToString(PluginLayoutConfig.serializer(), l))
            putString("instances", PluginSettingsDoc.json.encodeToString(ListSerializer(InstanceConfig.serializer()), list))
        }
        localLayout.value = l
        localInstances.value = list
    }

    /**
     * Puts the local plugin settings into the frame settings when those have none yet: once per session start, so a
     * patch the server refused is tried again. [raw] must be the loaded document.
     */
    fun migrate() {
        if (PluginSettingsDoc.layout(raw.value) != null) return
        if (localInstances.value.isEmpty() && localLayout.value == PluginLayoutConfig()) return
        edit(PluginSettingsDoc.patch(localLayout.value, localInstances.value))
    }

    /** `ctx.store`: small persistent key-value storage per instance (docs/plugins.md §3.2), 64 KB each. */
    fun store(instanceId: String): PluginStore = object : PluginStore {
        override fun get(key: String): String? = sp.getString("store.$instanceId.$key", null)
        override fun put(key: String, value: String?) {
            val total = sp.all.filterKeys { it.startsWith("store.$instanceId.") && it != "store.$instanceId.$key" }.values.sumOf { (it as? String)?.length ?: 0 }
            require(value == null || total + key.length + value.length <= PluginStore.MAX_BYTES) { "store full" }
            sp.edit { if (value == null) remove("store.$instanceId.$key") else putString("store.$instanceId.$key", value) }
        }
    }
}

/** Reading and writing the `plugins` part of the frame settings document. */
object PluginSettingsDoc {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun plugins(raw: JsonObject): JsonObject? = raw["plugins"] as? JsonObject

    /** The layout, or null when the settings have no `plugins` part yet. */
    fun layout(raw: JsonObject): PluginLayoutConfig? {
        val p = plugins(raw) ?: return null
        return (p["layout"] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement(PluginLayoutConfig.serializer(), it) }.getOrNull() }
            ?: PluginLayoutConfig()
    }

    /** The instances, or null when the settings have no `plugins` part yet. One the frame can't read is left out. */
    fun instances(raw: JsonObject): List<InstanceConfig>? {
        val p = plugins(raw) ?: return null
        return (p["instances"] as? JsonArray)?.mapNotNull { e ->
            runCatching { json.decodeFromJsonElement(InstanceConfig.serializer(), e) }.getOrNull()
        } ?: emptyList()
    }

    /**
     * The merge patch that sets the whole `plugins` part. Instances carry their secrets, so the frame's own view
     * keeps them while the patch waits in the outbox (the server seals them again).
     */
    fun patch(layout: PluginLayoutConfig, instances: List<InstanceConfig>): JsonObject = buildJsonObject {
        put("plugins", buildJsonObject {
            put("layout", buildJsonObject {
                put("mode", json.encodeToJsonElement(PluginLayoutMode.serializer(), layout.mode))
                put("side", json.encodeToJsonElement(BandSide.serializer(), layout.side))
                put("background", json.encodeToJsonElement(BandBackground.serializer(), layout.background))
            })
            put("instances", JsonArray(instances.map { json.encodeToJsonElement(InstanceConfig.serializer(), it) }))
        })
    }
}

interface PluginStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)

    companion object { const val MAX_BYTES = 64 * 1024 }
}
