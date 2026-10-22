package app.kinframe.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Typed view of the `FrameSettings` document. Defaults follow docs/spec/product-spec.md §6.
 * The raw [JsonObject] is the source of truth (unknown fields must be preserved); this is derived from it.
 */
@Serializable
data class FrameSettings(
    val version: Int = 0,
    val slideshow: SlideshowSettings = SlideshowSettings(),
    val widgets: WidgetSettings = WidgetSettings(),
    val video: VideoSettings = VideoSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val locale: LocaleSettings = LocaleSettings(),
    val location: LocationSettings? = null,
    val notifications: NotificationSettings = NotificationSettings(),
) {
    companion object {
        val DEFAULT = FrameSettings()

        fun parse(raw: JsonObject?): FrameSettings =
            if (raw == null) DEFAULT else runCatching { KinJson.decodeFromJsonElement<FrameSettings>(raw) }.getOrDefault(DEFAULT)
    }
}

@Serializable
data class SlideshowSettings(
    val intervalS: Int = 30,
    val transition: String = "crossfade",
    val transitionMs: Int = 800,
    val order: String = "received",
    val reverse: Boolean = false,
    val captionDate: String = "received",
    val fill: Boolean = true,
    val background: String = "blur",
    val pairPortraits: Boolean = true,
    val collage: Boolean = false,
    val collageMax: Int = 3,
    val albumId: String? = null,
    val onThisDay: Boolean = false,
)

@Serializable
data class WidgetSettings(
    val caption: Boolean = true,
    val avatar: Boolean = true,
    val clock: Boolean = true,
    val date: Boolean = true,
    val weather: Boolean = false,
    val clock24h: Boolean = true,
)

@Serializable
data class VideoSettings(
    val autoplay: Boolean = true,
    val playback: String = "once_hold",
    val volume: Int = 60,
    val autoMuteMin: Int? = null,
)

@Serializable
data class SleepScheduleDto(
    val id: String = "",
    val sleepAt: String = "23:00",
    val wakeAt: String = "07:00",
    val days: List<String> = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
    val enabled: Boolean = true,
    val mode: String = "off",
)

@Serializable
data class BrightnessPoint(val at: String = "00:00", val brightness: Int = 204)

@Serializable
data class DisplaySettings(
    val brightness: Int = 204,
    val brightnessMode: String = "manual",
    val brightnessSchedule: List<BrightnessPoint> = emptyList(),
    val sleepSchedules: List<SleepScheduleDto> = listOf(SleepScheduleDto(id = "default")),
    val motionTimeoutMin: Int? = 30,
    val textScale: Double = 1.0,
)

@Serializable
data class LocaleSettings(
    val language: String = "en",
    val timezone: String = "Europe/Brussels",
    val temperatureUnit: String = "c",
    val firstDayOfWeek: String = "mon",
)

@Serializable
data class LocationSettings(val name: String? = null, val lat: Double? = null, val lon: Double? = null)

@Serializable
data class NotificationSettings(val sound: Boolean = true, val volume: Int = 40)

/** RFC 7396 merge-patch helpers. */
object MergePatch {
    /** Applies [patch] onto [target] (RFC 7396): objects merge recursively, null removes, everything else replaces. */
    fun apply(target: JsonElement?, patch: JsonElement): JsonElement {
        if (patch !is JsonObject) return patch
        val base = (target as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        for ((k, v) in patch) {
            if (v is JsonNull) base.remove(k) else base[k] = apply(base[k], v)
        }
        return JsonObject(base)
    }

    /** Builds `{section: {field: value}}`. */
    fun field(section: String, field: String, value: Any?): JsonObject = buildJsonObject {
        put(section, buildJsonObject { put(field, value.toJson()) })
    }

    fun Any?.toJson(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }
}
