package app.kinframe.core.api

import kotlinx.serialization.Serializable

/*
 * Person-side models for albums, calendars, sent events and TV links (openapi `Album`, `CalendarSource`,
 * `CalendarTestResult`, `CalendarView`, `EventInvitationCreate`, `TvLink`, `TvLinkCreate`). The frame-side calendar
 * payload types (`FrameCalendar`, `CalendarEvent`, `Countdown`, `EventInvitation`) live in Calendar.kt.
 */

/** Play an album automatically between two dates each year (`MM-DD`, inclusive). */
@Serializable
data class AlbumSchedule(val fromMonthDay: String, val toMonthDay: String)

@Serializable
data class Album(
    val id: String,
    val name: String,
    val version: Int = 0,
    val placementCount: Int = 0,
    /** Ordered. */
    val placementIds: List<String> = emptyList(),
    val coverPlacementId: String? = null,
    val schedule: AlbumSchedule? = null,
)

/** A calendar on a frame: an ICS/webcal link, a CalDAV calendar, or the local received-events calendar. */
@Serializable
data class CalendarSource(
    val id: String,
    /** `ics`, `caldav` or `local`. */
    val kind: String = CalendarKind.ICS,
    /** Empty for the local calendar until renamed: clients show "Received events". */
    val name: String = "",
    val color: String = CalendarColors.DEFAULT,
    val enabled: Boolean = true,
    /** `ok`, `syncing`, `failed`, `auth_failed` or `disabled`. */
    val status: String = CalendarStatus.OK,
    val eventCount: Int? = null,
    val urlHint: String? = null,
    val username: String? = null,
    val hasPassword: Boolean = false,
    val lastSyncedAt: String? = null,
    val lastAttemptAt: String? = null,
    val lastError: String? = null,
    val nextSyncAt: String? = null,
    val createdAt: String? = null,
) {
    val isLocal: Boolean get() = kind == CalendarKind.LOCAL
}

object CalendarKind {
    const val ICS = "ics"
    const val CALDAV = "caldav"
    const val LOCAL = "local"
}

object CalendarStatus {
    const val OK = "ok"
    const val SYNCING = "syncing"
    const val FAILED = "failed"
    const val AUTH_FAILED = "auth_failed"
    const val DISABLED = "disabled"
}

/**
 * `CalendarSourceCreate` (also the body of `calendars:test`). Null fields are left out. The password is write-only:
 * the server never returns it.
 */
@Serializable
data class CalendarSourceCreate(
    val kind: String,
    val url: String,
    val name: String? = null,
    val color: String? = null,
    val username: String? = null,
    val password: String? = null,
    val calendarUrl: String? = null,
)

@Serializable
data class CalDavCalendar(val url: String, val name: String, val color: String? = null)

@Serializable
data class CalendarTestResult(
    val ok: Boolean,
    /** `ok`, `failed` or `auth_failed`. */
    val status: String = CalendarStatus.OK,
    val calendarName: String? = null,
    /** Events in the next 30 days. */
    val eventCount: Int? = null,
    val error: String? = null,
    /** CalDAV: the calendars found on the account. */
    val calendars: List<CalDavCalendar> = emptyList(),
)

/** One day of the frame's forecast (metric; convert for `locale.temperature_unit`). */
@Serializable
data class WeatherDay(
    val date: String,
    val code: Int = 0,
    val maxC: Double? = null,
    val minC: Double? = null,
    val precipProb: Int? = null,
)

@Serializable
data class WeatherNow(val tempC: Double = 0.0, val code: Int = 0, val isDay: Boolean = true)

@Serializable
data class Weather(val current: WeatherNow? = null, val daily: List<WeatherDay> = emptyList())

/** `GET /frames/{id}/calendar`: what the frame shows, plus its weather (null when it has none). */
@Serializable
data class CalendarView(
    val generatedAt: String? = null,
    val timezone: String? = null,
    val window: CalendarWindow? = null,
    val sources: List<CalendarSourceInfo> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val countdowns: List<Countdown> = emptyList(),
    val weather: Weather? = null,
)

/**
 * `EventInvitationCreate`: timed events need [startAt] and [endAt]; all-day events need [startDate] ([endDate] is
 * inclusive and defaults to it).
 */
@Serializable
data class EventInvitationCreate(
    val title: String,
    val allDay: Boolean = false,
    val startAt: String? = null,
    val endAt: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val photoUploadId: String? = null,
    /** `top` or `background`. */
    val photoPlacement: String? = null,
)

/** A revocable link that shows a frame's library (or the frame itself, live) on a TV (README §5.9). */
@Serializable
data class TvLink(
    val id: String,
    val frameId: String,
    val token: String,
    /** The TV page: `<public URL>/tv/<frameId>?t=<token>` (mirror links add `&mode=mirror`). */
    val url: String,
    /** `tv`, `cast` or `mirror`. */
    val purpose: String = TvPurpose.TV,
    val intervalSeconds: Int = 10,
    val expiresAt: String,
    val createdAt: String? = null,
    val createdByName: String? = null,
    val albumId: String? = null,
    val kind: String? = null,
    val placementCount: Int? = null,
)

object TvPurpose {
    const val TV = "tv"
    const val CAST = "cast"
    const val MIRROR = "mirror"
}

@Serializable
data class TvLinkCreate(
    val intervalSeconds: Int = 10,
    val purpose: String = TvPurpose.TV,
    val expiresInHours: Int = 12,
    val albumId: String? = null,
    val placementIds: List<String>? = null,
)
