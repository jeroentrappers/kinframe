package app.kinframe.frame.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

/** The full-screen calendar's views (`calendar.view`). */
enum class CalendarView(val key: String) {
    Month("month"), Week("week"), FourDay("four_day");

    companion object {
        fun of(key: String?): CalendarView = entries.firstOrNull { it.key == key } ?: Week
    }
}

/**
 * Pure layout math for the calendar views. Everything here runs once per (data, range) change, outside drawing,
 * so composition only places precomputed boxes: cheap on the RK3126.
 */
object CalendarLayout {
    fun firstDayOfWeek(key: String?): DayOfWeek = when (key) {
        "sun" -> DayOfWeek.SUNDAY
        "sat" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.MONDAY
    }

    fun weekStart(date: LocalDate, first: DayOfWeek): LocalDate = date.with(TemporalAdjusters.previousOrSame(first))

    /** Week number: ISO 8601 when weeks start on Monday, otherwise the US-style count for that first day. */
    fun weekNumber(date: LocalDate, first: DayOfWeek): Int =
        if (first == DayOfWeek.MONDAY) date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) else date.get(WeekFields.of(first, 1).weekOfWeekBasedYear())

    /** The first day shown for [view] around [anchor]: the week start, or the anchor itself with "start on today". */
    fun rangeStart(view: CalendarView, anchor: LocalDate, first: DayOfWeek, startOnToday: Boolean, today: LocalDate): LocalDate = when (view) {
        CalendarView.Month -> anchor.withDayOfMonth(1)
        CalendarView.FourDay -> anchor
        CalendarView.Week -> if (startOnToday && anchor == today) anchor else weekStart(anchor, first)
    }

    fun dayCount(view: CalendarView): Int = when (view) {
        CalendarView.Week -> 7
        CalendarView.FourDay -> 4
        CalendarView.Month -> 0
    }

    /** The next or previous page's anchor. */
    fun step(view: CalendarView, anchor: LocalDate, dir: Int): LocalDate = when (view) {
        CalendarView.Month -> anchor.withDayOfMonth(1).plusMonths(dir.toLong())
        CalendarView.Week -> anchor.plusWeeks(dir.toLong())
        CalendarView.FourDay -> anchor.plusDays(4L * dir)
    }

    /** Whole weeks covering [month] (5 or 6 rows; 4 only for a February that fits exactly). */
    fun monthWeeks(month: YearMonth, first: DayOfWeek): List<List<LocalDate>> {
        val start = weekStart(month.atDay(1), first)
        val out = ArrayList<List<LocalDate>>()
        var w = start
        while (!w.isAfter(month.atEndOfMonth())) {
            out += List(7) { w.plusDays(it.toLong()) }
            w = w.plusWeeks(1)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------- bars

    /** An event drawn as a bar across columns [startCol]..[endCol] (inclusive) on [lane]. */
    data class Bar(
        val event: CalEvent,
        val startCol: Int,
        val endCol: Int,
        val lane: Int,
        /** The event started before the first column / continues after the last (drawn without the rounded end). */
        val continuesBefore: Boolean,
        val continuesAfter: Boolean,
    ) {
        val span: Int get() = endCol - startCol + 1
    }

    /**
     * Packs [events] that touch [days] (consecutive) into lanes, longest first so multi-day bars stay on top and
     * keep their lane across the row. Greedy first-fit: stable and good enough for a family calendar.
     */
    fun packBars(days: List<LocalDate>, events: List<CalEvent>): List<Bar> {
        if (days.isEmpty()) return emptyList()
        val first = days.first()
        val last = days.last()
        val touching = events.filter { !it.lastDay.isBefore(first) && !it.firstDay.isAfter(last) }
            .sortedWith(
                compareBy<CalEvent>({ maxOf(it.firstDay, first) })
                    .thenByDescending { minOf(it.lastDay, last).toEpochDay() - maxOf(it.firstDay, first).toEpochDay() }
                    .thenBy { if (it.spans) 0 else 1 }
                    .thenBy { it.sortKey }.thenBy { it.title },
            )
        val laneEnds = ArrayList<Int>() // last occupied column per lane
        val out = ArrayList<Bar>(touching.size)
        for (e in touching) {
            val s = (maxOf(e.firstDay, first).toEpochDay() - first.toEpochDay()).toInt()
            val t = (minOf(e.lastDay, last).toEpochDay() - first.toEpochDay()).toInt()
            var lane = laneEnds.indexOfFirst { it < s }
            if (lane < 0) { lane = laneEnds.size; laneEnds += t } else laneEnds[lane] = t
            out += Bar(e, s, t, lane, e.firstDay.isBefore(first), e.lastDay.isAfter(last))
        }
        return out
    }

    /**
     * Month cells show at most [maxLanes] lanes; bars on lanes beyond that are hidden and each column counts what it
     * hides ("+2"). When anything overflows in a column, its last visible lane is given to the "+N" label.
     */
    data class RowLayout(val visible: List<Bar>, val hiddenPerCol: IntArray)

    fun clampLanes(bars: List<Bar>, columns: Int, maxLanes: Int): RowLayout {
        val hidden = IntArray(columns)
        // Columns whose events need more lanes than fit keep one lane for the "+N" line.
        val needed = IntArray(columns)
        for (b in bars) for (c in b.startCol..b.endCol) needed[c] = maxOf(needed[c], b.lane + 1)
        val visible = ArrayList<Bar>()
        for (b in bars) {
            val fits = (b.startCol..b.endCol).all { c -> b.lane < (if (needed[c] > maxLanes) maxLanes - 1 else maxLanes) }
            if (fits) visible += b else for (c in b.startCol..b.endCol) hidden[c]++
        }
        return RowLayout(visible, hidden)
    }

    // ------------------------------------------------------------------------------------------- timed

    /** A timed event placed in a day column: minutes from midnight, and its column within an overlap group. */
    data class TimedBlock(val event: CalEvent, val startMin: Int, val endMin: Int, val col: Int, val cols: Int)

    /**
     * Timed events of [day] (multi-day ones are in the all-day strip instead) laid out side by side where they
     * overlap: classic column packing per cluster of transitively overlapping events. Very short events are given
     * [minMinutes] of height so their title stays readable.
     */
    fun timedBlocks(day: LocalDate, events: List<CalEvent>, minMinutes: Int = 30): List<TimedBlock> {
        val items = events.filter { !it.spans && it.start != null && it.covers(day) }
            .map { e ->
                val s = e.start!!.toLocalTime().let { it.hour * 60 + it.minute }
                val endRaw = e.end?.let { if (it.toLocalDate().isAfter(day)) 24 * 60 else it.toLocalTime().let { t -> t.hour * 60 + t.minute } } ?: s
                Triple(e, s, maxOf(endRaw, s + minMinutes).coerceAtMost(24 * 60))
            }
            .sortedWith(compareBy({ it.second }, { -it.third }, { it.first.title }))
        val out = ArrayList<TimedBlock>()
        var cluster = ArrayList<Pair<Triple<CalEvent, Int, Int>, Int>>()
        var clusterEnd = -1
        val colEnds = ArrayList<Int>()
        fun flush() {
            val n = colEnds.size
            cluster.forEach { (it, col) -> out += TimedBlock(it.first, it.second, it.third, col, n) }
            cluster = ArrayList()
            colEnds.clear()
            clusterEnd = -1
        }
        for (it in items) {
            // A new cluster starts when nothing placed so far is still running.
            if (cluster.isNotEmpty() && it.second >= clusterEnd) flush()
            var col = colEnds.indexOfFirst { end -> end <= it.second }
            if (col < 0) { col = colEnds.size; colEnds += it.third } else colEnds[col] = it.third
            cluster += it to col
            clusterEnd = maxOf(clusterEnd, it.third)
        }
        if (cluster.isNotEmpty()) flush()
        return out
    }

    /**
     * Hours the week view shows: 07:00–21:00 by default, widened to fit the timed events of [days] (whole hours),
     * so a frame never needs scrolling to see what is on.
     */
    fun hourRange(days: List<LocalDate>, data: CalendarData, defaultStart: Int = 7, defaultEnd: Int = 21): IntRange {
        var lo = defaultStart
        var hi = defaultEnd
        for (d in days) for (b in timedBlocks(d, data.on(d), minMinutes = 0)) {
            lo = minOf(lo, b.startMin / 60)
            hi = maxOf(hi, (b.endMin + 59) / 60)
        }
        return lo.coerceIn(0, 23)..hi.coerceIn(lo + 1, 24)
    }

    // ------------------------------------------------------------------------------------------- agenda

    data class AgendaDay(val date: LocalDate, val spanning: List<CalEvent>, val timed: List<CalEvent>) {
        val isEmpty: Boolean get() = spanning.isEmpty() && timed.isEmpty()
        val all: List<CalEvent> get() = spanning + timed
    }

    fun agendaDay(data: CalendarData, day: LocalDate): AgendaDay {
        val list = data.on(day)
        return AgendaDay(day, list.filter { it.spans }, list.filter { !it.spans })
    }

    /**
     * The hybrid sidebar's list: today in full (finished events dropped when [hidePastToday]), then the next days
     * that have something on, until [maxDays] days with events or [maxEvents] events.
     */
    fun upcoming(data: CalendarData, today: LocalDate, nowMs: Long, horizonDays: Int = 14, maxDays: Int = 5, maxEvents: Int = 12, hidePastToday: Boolean = true): List<AgendaDay> {
        val out = ArrayList<AgendaDay>()
        var count = 0
        for (i in 0 until horizonDays) {
            val d = today.plusDays(i.toLong())
            var a = agendaDay(data, d)
            if (i == 0 && hidePastToday) a = a.copy(timed = a.timed.filter { !it.isPast(nowMs, data.zone) })
            // Multi-day events show on their first day in the list (and on today when they are under way).
            if (i > 0) a = a.copy(spanning = a.spanning.filter { it.firstDay == d })
            if (i == 0 || !a.isEmpty) {
                out += a
                count += a.all.size
                if (out.count { !it.isEmpty } >= maxDays || count >= maxEvents) break
            }
        }
        return out
    }
}
