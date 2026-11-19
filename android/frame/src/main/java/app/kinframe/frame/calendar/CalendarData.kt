package app.kinframe.frame.calendar

import app.kinframe.core.api.CalendarColors
import app.kinframe.core.api.CalendarEvent
import app.kinframe.core.api.CalendarSourceInfo
import app.kinframe.core.api.Countdown
import app.kinframe.core.api.FrameCalendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One event occurrence resolved for display in the frame's time zone. [firstDay]..[lastDay] are the local days it
 * covers (inclusive): a timed event ending exactly at midnight does not cover the next day.
 */
data class CalEvent(
    val id: String,
    val sourceId: String,
    val title: String,
    val allDay: Boolean,
    val start: ZonedDateTime?,
    val end: ZonedDateTime?,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val location: String? = null,
    val notes: String? = null,
    val recurring: Boolean = false,
    val countdown: Boolean = false,
    val senderName: String? = null,
    val invitationId: String? = null,
    val hasPhoto: Boolean = false,
    val photoPlacement: String? = null,
    /** Palette key of the event's source. */
    val color: String = CalendarColors.DEFAULT,
    /** The source's display name; empty for the received-events calendar (shown localised). */
    val sourceName: String = "",
    val local: Boolean = false,
    /** A birthday computed on the frame (birthdays.md §6.6): not editable, no countdown toggle. */
    val birthday: app.kinframe.frame.birthdays.BirthdayOccurrence? = null,
) {
    val multiDay: Boolean get() = lastDay.isAfter(firstDay)

    /** Shown as a bar (month rows, the all-day strip of the week view) rather than at a time. */
    val spans: Boolean get() = allDay || multiDay

    /** Sort key: start instant, all-day events at local midnight. */
    val sortKey: Long get() = start?.toInstant()?.toEpochMilli() ?: firstDay.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    fun covers(day: LocalDate): Boolean = !day.isBefore(firstDay) && !day.isAfter(lastDay)

    /** Ended before [nowMs] (all-day: the day after its last day has begun). */
    fun isPast(nowMs: Long, zone: ZoneId): Boolean =
        end?.let { it.toInstant().toEpochMilli() <= nowMs } ?: Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().isAfter(lastDay)
}

/**
 * A countdown resolved for display. A birthday tile (docs/spec/birthdays.md §6.6) carries the day's [birthdays] and is
 * worded by the view ("Oma turns 80"); event countdowns leave it empty.
 */
data class CountdownItem(
    val eventId: String,
    val title: String,
    val day: LocalDate,
    val start: ZonedDateTime?,
    val color: String,
    val birthdays: List<app.kinframe.frame.birthdays.BirthdayOccurrence> = emptyList(),
)

/**
 * The merged calendar ready for display: events resolved in [zone] and indexed per day once, so views only look
 * things up while composing. Build it off the main thread when the payload changes ([from]).
 */
class CalendarData(
    val events: List<CalEvent>,
    val sources: List<CalendarSourceInfo>,
    val countdowns: List<CountdownItem>,
    val zone: ZoneId,
    val windowFrom: LocalDate?,
    val windowTo: LocalDate?,
) {
    private val byDay: Map<LocalDate, List<CalEvent>> = buildMap<LocalDate, MutableList<CalEvent>> {
        for (e in events) {
            var d = e.firstDay
            // Guard against absurd spans (a broken feed): at most a year per event.
            var n = 0
            while (!d.isAfter(e.lastDay) && n++ < 370) {
                getOrPut(d) { ArrayList() }.add(e)
                d = d.plusDays(1)
            }
        }
    }.mapValues { (_, list) -> list.sortedWith(DAY_ORDER) }

    /** Events on [day]: spanning ones (all-day and multi-day) first, then timed events by start. */
    fun on(day: LocalDate): List<CalEvent> = byDay[day] ?: emptyList()

    val isEmpty: Boolean get() = events.isEmpty()

    fun event(id: String): CalEvent? = events.firstOrNull { it.id == id }

    fun source(id: String): CalendarSourceInfo? = sources.firstOrNull { it.id == id }

    /** Countdowns that have not passed, nearest first. */
    fun upcomingCountdowns(nowMs: Long, max: Int = 3): List<CountdownItem> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return countdowns.filter { c -> c.start?.let { it.toInstant().toEpochMilli() > nowMs } ?: !c.day.isBefore(today) }
            .sortedBy { it.start?.toInstant()?.toEpochMilli() ?: it.day.atStartOfDay(zone).toInstant().toEpochMilli() }
            .take(max)
    }

    /** The same data with one event's countdown flag changed locally (until the server echoes it). */
    fun withCountdown(eventId: String, on: Boolean): CalendarData {
        val ev = event(eventId) ?: return this
        val list = countdowns.filter { it.eventId != eventId } + if (on) listOf(CountdownItem(ev.id, ev.title, ev.firstDay, ev.start, ev.color)) else emptyList()
        return CalendarData(events.map { if (it.id == eventId) it.copy(countdown = on) else it }, sources, list, zone, windowFrom, windowTo)
    }

    companion object {
        val DAY_ORDER: Comparator<CalEvent> = compareBy<CalEvent>({ if (it.spans) 0 else 1 }, { if (it.birthday != null) 0 else 1 }, { it.firstDay }, { -(it.lastDay.toEpochDay() - it.firstDay.toEpochDay()) })
            .thenBy { it.sortKey }.thenBy { it.title }.thenBy { it.id }

        /** Birthday entries are computed for this many years around today (the calendar rarely looks further). */
        private const val BIRTHDAY_YEARS_BACK = 1L
        private const val BIRTHDAY_YEARS_AHEAD = 2L
        const val BIRTHDAY_SOURCE = "birthdays"

        /**
         * [base] (or an empty calendar) with every birthday from [people] as an all-day entry "🎈 Oma (80)" in `rose`
         * around [today]. Null when there is neither a calendar nor a birthday.
         */
        fun withBirthdays(base: CalendarData?, people: List<app.kinframe.core.api.FrameBirthday>, today: LocalDate?, zone: ZoneId): CalendarData? {
            if (people.isEmpty() || today == null) return base
            val from = LocalDate.of(today.year, 1, 1).minusYears(BIRTHDAY_YEARS_BACK)
            val to = LocalDate.of(today.year, 12, 31).plusYears(BIRTHDAY_YEARS_AHEAD)
            val extra = app.kinframe.frame.birthdays.BirthdayLogic.occurrences(people, from, to).map { o ->
                CalEvent(
                    id = "birthday:${o.id}:${o.date}", sourceId = BIRTHDAY_SOURCE,
                    title = "\uD83C\uDF88 ${o.name}" + (o.age?.let { " ($it)" } ?: ""),
                    allDay = true, start = null, end = null, firstDay = o.date, lastDay = o.date,
                    color = app.kinframe.frame.birthdays.BirthdayLogic.COLOR, birthday = o,
                )
            }
            if (extra.isEmpty()) return base
            val b = base ?: CalendarData(emptyList(), emptyList(), emptyList(), zone, null, null)
            val events = (b.events + extra).sortedWith(compareBy<CalEvent>({ it.firstDay }, { if (it.spans) 0 else 1 }, { if (it.birthday != null) 0 else 1 }, { it.sortKey }))
            return CalendarData(events, b.sources, b.countdowns, b.zone, b.windowFrom, b.windowTo)
        }

        val EMPTY = CalendarData(emptyList(), emptyList(), emptyList(), ZoneId.of("UTC"), null, null)

        fun from(cal: FrameCalendar, zone: ZoneId): CalendarData {
            val sources = cal.sources
            val byId = sources.associateBy { it.id }
            val events = cal.events.mapNotNull { resolve(it, byId[it.sourceId], zone) }.sortedWith(compareBy<CalEvent>({ it.firstDay }, { if (it.spans) 0 else 1 }, { it.sortKey }))
            val countdowns = cal.countdowns.mapNotNull { c -> resolveCountdown(c, zone) }
            return CalendarData(
                events, sources, countdowns, zone,
                cal.window?.from?.let { parseDate(it) }, cal.window?.to?.let { parseDate(it) },
            )
        }

        fun resolve(e: CalendarEvent, source: CalendarSourceInfo?, zone: ZoneId): CalEvent? {
            val color = source?.color ?: CalendarColors.DEFAULT
            val base = CalEvent(
                id = e.id, sourceId = e.sourceId, title = e.title, allDay = e.allDay, start = null, end = null,
                firstDay = LocalDate.MIN, lastDay = LocalDate.MIN, location = e.location?.takeIf { it.isNotBlank() },
                notes = e.notes?.takeIf { it.isNotBlank() }, recurring = e.recurring, countdown = e.countdown,
                senderName = e.senderName, invitationId = e.invitationId, hasPhoto = e.hasPhoto, photoPlacement = e.photoPlacement,
                color = color, sourceName = source?.name.orEmpty(), local = source?.isLocal == true,
            )
            return if (e.allDay) {
                val s = parseDate(e.startDate) ?: return null
                val t = parseDate(e.endDate)?.takeIf { !it.isBefore(s) } ?: s
                base.copy(firstDay = s, lastDay = t)
            } else {
                val s = parseInstant(e.startAt)?.atZone(zone) ?: return null
                val t = parseInstant(e.endAt)?.atZone(zone)?.takeIf { it.isAfter(s) } ?: s
                // The last covered day is the day of the last moment before the end.
                val last = if (t.isAfter(s)) t.minusNanos(1).toLocalDate() else s.toLocalDate()
                base.copy(start = s, end = t, firstDay = s.toLocalDate(), lastDay = last)
            }
        }

        private fun resolveCountdown(c: Countdown, zone: ZoneId): CountdownItem? {
            val start = if (c.allDay) null else parseInstant(c.startAt)?.atZone(zone)
            val day = start?.toLocalDate() ?: parseDate(c.startDate) ?: return null
            return CountdownItem(c.eventId, c.title, day, start, c.color)
        }

        fun parseDate(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

        fun parseInstant(s: String?): Instant? = s?.let {
            runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull()
        }
    }
}
