package app.kinframe.frame.birthdays

import app.kinframe.core.api.BirthdayDates
import app.kinframe.core.api.FrameBirthday
import app.kinframe.frame.calendar.CountdownItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One person's birthday on [date]; [age] only when the frame may show it (`birth_year` was sent). */
data class BirthdayOccurrence(val person: FrameBirthday, val date: LocalDate, val age: Int?) {
    val id: String get() = person.id
    val name: String get() = person.name
    val hasPhoto: Boolean get() = !person.photoUrl.isNullOrBlank()
}

/** Everyone whose birthday falls on [date]: one countdown tile, or the day's card. */
data class BirthdayDay(val date: LocalDate, val people: List<BirthdayOccurrence>)

/**
 * The frame's birthday date maths (docs/spec/birthdays.md §6.2, §6.6, §10). The server sends the list once; "today",
 * "in 3 days" and ages are worked out here in the frame's own time zone, so nothing depends on the network on the day.
 */
object BirthdayLogic {
    /** Countdown tiles from tomorrow up to this many days ahead. */
    const val COUNTDOWN_DAYS = 7
    /** Top-right column: the card plus countdown tiles, at most this many entries. */
    const val MAX_ENTRIES = 3
    /** Card lines before "and N more". */
    const val CARD_LINES = 3
    const val COLOR = "rose"

    fun localDate(nowMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    /**
     * The date birthdays are evaluated for, or null while the clock is not trusted (§6.2): then there is no card, no
     * countdown tile, no balloons and no greeting button.
     */
    fun trustedDate(nowMs: Long, zone: ZoneId, clockWrong: Boolean): LocalDate? = if (clockWrong) null else localDate(nowMs, zone)

    /** Today's people for the card; empty without a trusted date. */
    fun card(people: List<FrameBirthday>, date: LocalDate?): List<BirthdayOccurrence> = if (date == null) emptyList() else today(people, date)

    /** Birthday countdown days; empty without a trusted date. */
    fun countdowns(people: List<FrameBirthday>, date: LocalDate?): List<BirthdayDay> = if (date == null) emptyList() else upcoming(people, date)

    /**
     * The occurrence in [date]'s year if it falls on [date], else null. 29 February counts on 28 February in non-leap
     * years. With a year, the date of birth itself is not a birthday (Lotte, born this year, has no card yet).
     */
    fun on(p: FrameBirthday, date: LocalDate): BirthdayOccurrence? {
        if (!BirthdayDates.isValid(p.day, p.month)) return null
        if (BirthdayDates.occurrence(p.month, p.day, date.year) != date) return null
        val year = p.birthYear
        if (year != null && date.year <= year) return null
        return BirthdayOccurrence(p, date, year?.let { date.year - it })
    }

    /** Card order: people with photos first, then by name. */
    private val CARD_ORDER = compareBy<BirthdayOccurrence>({ if (it.hasPhoto) 0 else 1 }, { it.name.lowercase() }, { it.id })

    /** Today's people, in card order. */
    fun today(people: List<FrameBirthday>, date: LocalDate): List<BirthdayOccurrence> =
        people.mapNotNull { on(it, date) }.sortedWith(CARD_ORDER)

    /** Birthdays 1 to [days] days after [today], nearest first; people sharing a day share one entry. */
    fun upcoming(people: List<FrameBirthday>, today: LocalDate, days: Int = COUNTDOWN_DAYS): List<BirthdayDay> =
        (1..days).mapNotNull { n ->
            val d = today.plusDays(n.toLong())
            today(people, d).takeIf { it.isNotEmpty() }?.let { BirthdayDay(d, it) }
        }

    /** Every occurrence from [from] to [to] (inclusive), for the calendar. */
    fun occurrences(people: List<FrameBirthday>, from: LocalDate, to: LocalDate): List<BirthdayOccurrence> {
        if (to.isBefore(from)) return emptyList()
        val out = ArrayList<BirthdayOccurrence>()
        for (year in from.year..to.year) for (p in people) {
            if (!BirthdayDates.isValid(p.day, p.month)) continue
            val d = BirthdayDates.occurrence(p.month, p.day, year)
            if (d.isBefore(from) || d.isAfter(to)) continue
            on(p, d)?.let { out += it }
        }
        return out.sortedWith(compareBy<BirthdayOccurrence> { it.date }.then(CARD_ORDER))
    }

    /** A countdown tile for a birthday day ([CountdownItem.birthdays] set; titles are worded by the view). */
    fun tile(day: BirthdayDay): CountdownItem =
        CountdownItem("birthday:${day.date}", day.people.joinToString(", ") { it.name }, day.date, null, COLOR, birthdays = day.people)

    /**
     * The countdown tiles under the card (§6.6): event countdowns ([events], not yet passed, nearest first) and birthday
     * days merged by date, birthdays first on a day (all-day), then all-day events, then timed ones, ties by title.
     * At most [MAX_ENTRIES] entries with the card, so one fewer tile when [card] shows. When the cut leaves out every
     * event countdown while one exists, the last birthday tile gives way to the nearest event: a countdown the owner
     * marked by hand always gets a slot.
     */
    fun mergeCountdowns(events: List<CountdownItem>, birthdays: List<BirthdayDay>, card: Boolean, max: Int = MAX_ENTRIES): List<CountdownItem> {
        val slots = (max - if (card) 1 else 0).coerceAtLeast(0)
        if (slots == 0) return emptyList()
        val all = (events + birthdays.map { tile(it) }).sortedWith(TILE_ORDER)
        val cut = all.take(slots)
        if (events.isNotEmpty() && cut.none { it.birthdays.isEmpty() }) {
            val nearest = events.sortedWith(TILE_ORDER).first()
            return cut.dropLast(1) + nearest
        }
        return cut
    }

    private val TILE_ORDER = compareBy<CountdownItem>(
        { it.day },
        { when { it.birthdays.isNotEmpty() -> 0; it.start == null -> 1; else -> 2 } },
        { it.start?.toInstant()?.toEpochMilli() ?: 0L },
        { it.title.lowercase() },
    )

    /** Whole days from [today] to [day]. */
    fun daysUntil(today: LocalDate, day: LocalDate): Int = ChronoUnit.DAYS.between(today, day).toInt()
}
