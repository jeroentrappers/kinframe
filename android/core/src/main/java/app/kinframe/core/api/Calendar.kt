package app.kinframe.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Calendar models (openapi `Calendar`, `CalendarEvent`, `Countdown`, `CalendarSourceInfo`, `EventInvitation`;
 * README §5.8). The server syncs every source, expands recurrences and merges them; the frame only displays.
 */

/** Payload of the `calendar` change. Named `FrameCalendar` to stay clear of `java.util.Calendar`. */
@Serializable
data class FrameCalendar(
    val generatedAt: String? = null,
    val timezone: String? = null,
    val window: CalendarWindow? = null,
    val sources: List<CalendarSourceInfo> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val countdowns: List<Countdown> = emptyList(),
) {
    companion object {
        /** Parses a stored or received payload; null when absent or not understood. */
        fun parse(json: String?): FrameCalendar? =
            json?.let { runCatching { KinJson.decodeFromString(serializer(), it) }.getOrNull() }

        fun of(obj: JsonObject?): FrameCalendar? =
            obj?.let { runCatching { KinJson.decodeFromJsonElement(serializer(), it) }.getOrNull() }
    }
}

/** Inclusive date range (`YYYY-MM-DD`) the server expanded events for. */
@Serializable
data class CalendarWindow(val from: String, val to: String)

@Serializable
data class CalendarSourceInfo(
    val id: String,
    /** `ics`, `caldav` or `local` (the received-events calendar). */
    val kind: String = "ics",
    /** Empty for the local calendar until renamed: show "Received events". */
    val name: String = "",
    /** A [CalendarColors] palette key. */
    val color: String = CalendarColors.DEFAULT,
    /** `ok`, `syncing`, `failed`, `auth_failed`, `disabled`. */
    val status: String = "ok",
    val lastSyncedAt: String? = null,
) {
    val isLocal: Boolean get() = kind == "local"
}

/**
 * One occurrence. Timed events carry [startAt]/[endAt] (UTC instants); all-day events carry [startDate]/[endDate]
 * (inclusive local dates).
 */
@Serializable
data class CalendarEvent(
    val id: String,
    val sourceId: String,
    val title: String = "",
    val allDay: Boolean = false,
    val startAt: String? = null,
    val endAt: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val recurring: Boolean = false,
    val countdown: Boolean = false,
    val senderName: String? = null,
    val invitationId: String? = null,
    val hasPhoto: Boolean = false,
    /** `top` or `background`. */
    val photoPlacement: String? = null,
)

/** A marked event, also beyond the window. */
@Serializable
data class Countdown(
    val eventId: String,
    val sourceId: String? = null,
    val title: String = "",
    val allDay: Boolean = false,
    val startAt: String? = null,
    val startDate: String? = null,
    val color: String = CalendarColors.DEFAULT,
)

/** An event someone sent to the frame (`GET /device/invitations`, websocket `event_invitation`). */
@Serializable
data class EventInvitation(
    val id: String,
    val frameId: String? = null,
    val senderId: String? = null,
    val senderName: String = "",
    /** `pending`, `accepted`, `declined` or `withdrawn`. */
    val status: String = "pending",
    val title: String = "",
    val allDay: Boolean = false,
    val startAt: String? = null,
    val endAt: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val hasPhoto: Boolean = false,
    val photoPlacement: String? = null,
    val createdAt: String? = null,
    val decidedAt: String? = null,
)

/**
 * The calendar palette (`CalendarColor`, README §5.8): 12 keys, each with a light variant (for light backgrounds,
 * like the frame's paper style) and a dark one. ARGB values, so the module stays UI-toolkit neutral.
 */
object CalendarColors {
    const val DEFAULT = "sky"

    data class Swatch(val light: Long, val dark: Long)

    val palette: Map<String, Swatch> = linkedMapOf(
        "coral" to Swatch(0xFFE5726A, 0xFFF0958E),
        "tangerine" to Swatch(0xFFEE8F4F, 0xFFF4AE7E),
        "amber" to Swatch(0xFFDDA731, 0xFFE9C26A),
        "olive" to Swatch(0xFF9AA63E, 0xFFBBC575),
        "mint" to Swatch(0xFF4FB28A, 0xFF82CDAE),
        "sage" to Swatch(0xFF7F9C77, 0xFFA8C0A1),
        "teal" to Swatch(0xFF349E9A, 0xFF6EC1BD),
        "sky" to Swatch(0xFF4F9FDF, 0xFF87BFEB),
        "ocean" to Swatch(0xFF4767C8, 0xFF8197DD),
        "lavender" to Swatch(0xFF8E7BD0, 0xFFB3A6E2),
        "plum" to Swatch(0xFFAC5F9F, 0xFFCA92C1),
        "rose" to Swatch(0xFFDA6C92, 0xFFE79AB5),
    )

    /** Unknown keys (a newer server) fall back to the default swatch. */
    fun swatch(key: String?): Swatch = palette[key] ?: palette.getValue(DEFAULT)
}
