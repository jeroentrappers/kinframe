package app.kinframe.family.ui.calendar

import app.kinframe.core.api.CalendarColors
import app.kinframe.core.api.CalendarEvent
import app.kinframe.core.api.Countdown
import app.kinframe.core.api.EventInvitationCreate
import app.kinframe.core.api.WeatherDay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * Pure helpers for a frame's Calendar tab (docs/api/README.md §5.8), the same rules as the web app's
 * `web/src/lib/calendar.ts`. No Android types, so they are unit tested directly.
 */
object CalendarLogic {
    /** The palette keys in order: new calendars take the first one not used yet. */
    val COLORS: List<String> = CalendarColors.palette.keys.toList()

    /** The first palette colour not used yet (the server's rule for new sources), cycling when all are taken. */
    fun nextColor(used: List<String?>): String = COLORS.firstOrNull { it !in used } ?: COLORS[used.size % COLORS.size]

    private val URL_RE = Regex("""^(https?|webcal)://[^\s/]+\S*$""", RegexOption.IGNORE_CASE)

    /** An ICS/webcal or CalDAV address the server accepts. */
    fun urlOk(url: String): Boolean = URL_RE.matches(url.trim())

    // ------------------------------------------------------------------ zones and days

    fun zone(tz: String?): ZoneId = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")

    fun parseInstant(s: String?): Instant? = s?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull()
    }

    fun parseDate(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    fun today(zone: ZoneId, now: Instant = Instant.now()): LocalDate = now.atZone(zone).toLocalDate()

    /** First and last local day (inclusive) an event covers. A timed event ending at midnight doesn't touch the next day. */
    fun span(ev: CalendarEvent, zone: ZoneId): Pair<LocalDate, LocalDate> {
        if (ev.allDay) {
            val s = parseDate(ev.startDate) ?: parseInstant(ev.startAt)?.atZone(zone)?.toLocalDate() ?: LocalDate.EPOCH
            val e = parseDate(ev.endDate)?.takeIf { !it.isBefore(s) } ?: s
            return s to e
        }
        val start = parseInstant(ev.startAt) ?: return LocalDate.EPOCH to LocalDate.EPOCH
        val s = start.atZone(zone).toLocalDate()
        val end = parseInstant(ev.endAt)?.takeIf { it.isAfter(start) } ?: return s to s
        val e = end.minusMillis(1).atZone(zone).toLocalDate()
        return s to (if (e.isBefore(s)) s else e)
    }

    fun isMultiDay(ev: CalendarEvent, zone: ZoneId): Boolean = span(ev, zone).let { it.first != it.second }

    /** All-day and multi-day events first (earlier start, then longer), then timed events by start, then title. */
    fun comparator(zone: ZoneId): Comparator<CalendarEvent> = Comparator { a, b ->
        val topA = a.allDay || isMultiDay(a, zone)
        val topB = b.allDay || isMultiDay(b, zone)
        if (topA != topB) return@Comparator if (topA) -1 else 1
        val (sa, ea) = span(a, zone)
        val (sb, eb) = span(b, zone)
        if (topA) {
            if (sa != sb) return@Comparator sa.compareTo(sb)
            if (ea != eb) return@Comparator eb.compareTo(ea)
        } else if (a.startAt != b.startAt) {
            val ia = parseInstant(a.startAt) ?: Instant.EPOCH
            val ib = parseInstant(b.startAt) ?: Instant.EPOCH
            return@Comparator ia.compareTo(ib)
        }
        a.title.compareTo(b.title)
    }

    /** Events per local day between [from] and [to] (inclusive), each day sorted. */
    fun eventsByDay(events: List<CalendarEvent>, zone: ZoneId, from: LocalDate, to: LocalDate): Map<LocalDate, List<CalendarEvent>> {
        val out = HashMap<LocalDate, MutableList<CalendarEvent>>()
        for (ev in events) {
            val (s, e) = span(ev, zone)
            var d = if (s.isBefore(from)) from else s
            val end = if (e.isAfter(to)) to else e
            var guard = 0
            while (!d.isAfter(end) && guard++ < 400) {
                out.getOrPut(d) { ArrayList() }.add(ev)
                d = d.plusDays(1)
            }
        }
        val cmp = comparator(zone)
        return out.mapValues { (_, l) -> l.sortedWith(cmp) }
    }

    data class AgendaDay(val day: LocalDate, val events: List<CalendarEvent>)

    /**
     * Agenda: today up to the end of the window, days with events (today always). Multi-day events show once, on their
     * first day in the list.
     */
    fun agenda(events: List<CalendarEvent>, zone: ZoneId, today: LocalDate, windowFrom: LocalDate, windowTo: LocalDate, maxDays: Int = 60): List<AgendaDay> {
        val byDay = eventsByDay(events, zone, windowFrom.minusDays(7), windowTo.plusDays(7))
        val out = ArrayList<AgendaDay>()
        var d = today
        while (!d.isAfter(windowTo) && out.size < maxDays) {
            val list = byDay[d].orEmpty().filter { ev ->
                val (s, e) = span(ev, zone)
                s == e || d == (if (s.isAfter(today)) s else today)
            }
            if (d == today || list.isNotEmpty()) out += AgendaDay(d, list)
            d = d.plusDays(1)
        }
        return out
    }

    /** Over at [now]. */
    fun isPast(ev: CalendarEvent, zone: ZoneId, now: Instant = Instant.now()): Boolean {
        if (ev.allDay) return span(ev, zone).second.isBefore(today(zone, now))
        val end = parseInstant(ev.endAt) ?: parseInstant(ev.startAt) ?: return false
        return !end.isAfter(now)
    }

    /** Days left until a countdown's day (0 = today), in the frame's time zone. */
    fun daysUntil(c: Countdown, zone: ZoneId, now: Instant = Instant.now()): Long {
        val day = if (c.allDay) parseDate(c.startDate) ?: today(zone, now) else parseInstant(c.startAt)?.atZone(zone)?.toLocalDate() ?: today(zone, now)
        return ChronoUnit.DAYS.between(today(zone, now), day)
    }

    // ------------------------------------------------------------------ month grid

    fun firstDay(key: String?): DayOfWeek = when (key) {
        "sun" -> DayOfWeek.SUNDAY
        "sat" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.MONDAY
    }

    /** Full weeks covering a month: 4 to 6 rows of 7 dates. */
    fun monthGrid(month: YearMonth, first: DayOfWeek): List<List<LocalDate>> {
        var d = month.atDay(1).with(TemporalAdjusters.previousOrSame(first))
        val last = month.atEndOfMonth()
        val weeks = ArrayList<List<LocalDate>>()
        while (!d.isAfter(last)) {
            weeks += List(7) { d.plusDays(it.toLong()) }
            d = d.plusDays(7)
        }
        return weeks
    }

    /** `day.*` keys (mon…sun) starting at [first]. */
    fun weekdayOrder(first: DayOfWeek): List<DayOfWeek> = List(7) { first.plus(it.toLong()) }

    fun isoWeek(d: LocalDate): Int = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    // ------------------------------------------------------------------ weather

    enum class WeatherKind { Clear, Partly, Cloudy, Fog, Drizzle, Rain, Snow, Showers, Storm }

    /** A coarse kind for a WMO weather code. */
    fun weatherKind(code: Int): WeatherKind = when {
        code <= 1 -> WeatherKind.Clear
        code == 2 -> WeatherKind.Partly
        code == 3 -> WeatherKind.Cloudy
        code == 45 || code == 48 -> WeatherKind.Fog
        code in 51..57 -> WeatherKind.Drizzle
        code in 61..67 -> WeatherKind.Rain
        code in 80..82 -> WeatherKind.Showers
        code in 71..77 || code == 85 || code == 86 -> WeatherKind.Snow
        code >= 95 -> WeatherKind.Storm
        else -> WeatherKind.Cloudy
    }

    fun formatTemp(c: Double?, unit: String = "c"): String =
        if (c == null) "–" else "${(if (unit == "f") c * 1.8 + 32 else c).roundToInt()}°"

    fun weatherByDate(days: List<WeatherDay>?): Map<LocalDate, WeatherDay> =
        days.orEmpty().mapNotNull { d -> parseDate(d.date)?.let { it to d } }.toMap()

    // ------------------------------------------------------------------ sending an event

    enum class FormError { Title, Start, End, Order }

    data class EventForm(
        val title: String = "",
        val allDay: Boolean = false,
        /** Local date-times in the sender's own time zone. */
        val start: LocalDateTime? = null,
        val end: LocalDateTime? = null,
        val startDate: LocalDate? = null,
        val endDate: LocalDate? = null,
        val location: String = "",
        val notes: String = "",
    )

    /** A sensible default: the next full hour, one hour long. */
    fun defaultForm(now: LocalDateTime = LocalDateTime.now()): EventForm {
        val s = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        return EventForm(start = s, end = s.plusHours(1), startDate = s.toLocalDate(), endDate = s.toLocalDate())
    }

    /** Moving the start keeps the duration; an end date before the start date follows it. */
    fun moveStart(f: EventForm, start: LocalDateTime): EventForm {
        val delta = if (f.start != null) java.time.Duration.between(f.start, start) else java.time.Duration.ZERO
        return f.copy(start = start, end = f.end?.plus(delta))
    }

    fun moveStartDate(f: EventForm, d: LocalDate): EventForm =
        f.copy(startDate = d, endDate = f.endDate?.takeIf { !it.isBefore(d) } ?: d)

    /** Validates the form; the first problem, or the request body (without the photo). */
    fun build(f: EventForm, zone: ZoneId = ZoneId.systemDefault()): Pair<FormError?, EventInvitationCreate?> {
        val title = f.title.trim()
        if (title.isEmpty()) return FormError.Title to null
        val location = f.location.trim().ifEmpty { null }
        val notes = f.notes.trim().ifEmpty { null }
        if (f.allDay) {
            val s = f.startDate ?: return FormError.Start to null
            val e = f.endDate ?: s
            if (e.isBefore(s)) return FormError.Order to null
            return null to EventInvitationCreate(title, allDay = true, startDate = s.toString(), endDate = e.toString(), location = location, notes = notes)
        }
        val s = f.start ?: return FormError.Start to null
        val e = f.end ?: return FormError.End to null
        if (!e.isAfter(s)) return FormError.Order to null
        return null to EventInvitationCreate(
            title, allDay = false, startAt = s.atZone(zone).toInstant().toString(), endAt = e.atZone(zone).toInstant().toString(),
            location = location, notes = notes,
        )
    }
}
