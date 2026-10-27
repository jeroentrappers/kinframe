package app.kinframe.frame.plugins

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
 * One plugin instance, in the shape the server will later send in the frame settings (docs/plugins.md §10:
 * `plugins: [{instance_id, plugin_id, version, slot, order, visibility, settings, secret_refs}]`).
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
)

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
 * Frame-local plugin settings (step 1 of docs/plugins.md §10: there is no server API or app UI yet). Kept in their
 * own SharedPreferences file and edited in the frame's Settings screen behind the owner PIN.
 */
class PluginPrefs(context: Context) {
    private val sp = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _layout = MutableStateFlow(readLayout())
    val layout: StateFlow<PluginLayoutConfig> = _layout
    private val _instances = MutableStateFlow(readInstances())
    val instances: StateFlow<List<InstanceConfig>> = _instances

    private fun readLayout(): PluginLayoutConfig =
        sp.getString("layout", null)?.let { runCatching { json.decodeFromString(PluginLayoutConfig.serializer(), it) }.getOrNull() } ?: PluginLayoutConfig()

    private fun readInstances(): List<InstanceConfig> =
        sp.getString("instances", null)?.let { runCatching { json.decodeFromString(ListSerializer(InstanceConfig.serializer()), it) }.getOrNull() }
            ?: emptyList()

    fun setLayout(l: PluginLayoutConfig) {
        sp.edit { putString("layout", json.encodeToString(PluginLayoutConfig.serializer(), l)) }
        _layout.value = l
    }

    fun upsert(i: InstanceConfig) {
        val list = _instances.value.filter { it.instanceId != i.instanceId } + i
        sp.edit { putString("instances", json.encodeToString(ListSerializer(InstanceConfig.serializer()), list)) }
        _instances.value = list
    }

    fun remove(instanceId: String) {
        val list = _instances.value.filter { it.instanceId != instanceId }
        sp.edit { putString("instances", json.encodeToString(ListSerializer(InstanceConfig.serializer()), list)) }
        _instances.value = list
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

interface PluginStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)

    companion object { const val MAX_BYTES = 64 * 1024 }
}
