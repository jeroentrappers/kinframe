package app.kinframe.frame.calendar

import app.kinframe.core.api.CalendarEvent
import app.kinframe.core.api.CalendarScheduleDto
import app.kinframe.core.api.CalendarSourceInfo
import app.kinframe.core.api.FrameCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class CalendarDataTest {
    private val zone = CalendarFixtures.zone
    private val today = CalendarFixtures.today

    @Test fun resolvesTimesInTheFrameZoneAndCoveredDays() {
        val d = CalendarFixtures.data()
        val train = d.events.single { it.title == "Night train to Vienna" }
        assertEquals(today.plusDays(2), train.firstDay)
        assertEquals(today.plusDays(3), train.lastDay)
        assertTrue(train.multiDay && train.spans)
        assertEquals(21, train.start!!.hour)
        val trip = d.events.single { it.title == "Field trip to Brugge" }
        assertEquals(3, (trip.firstDay..trip.lastDay).let { (it.endInclusive.toEpochDay() - it.start.toEpochDay() + 1).toInt() })
        // Colour and name come from the source; the local calendar is flagged.
        val lunch = d.events.single { it.title == "Lunch at Oma's" }
        assertEquals("rose", lunch.color)
        assertTrue(lunch.local)
        assertEquals("Oma", lunch.senderName)
    }

    @Test fun eventEndingAtMidnightDoesNotCoverTheNextDay() {
        val e = CalendarEvent(
            "ev-00000001", "s", "Party", startAt = today.atTime(20, 0).atZone(zone).toInstant().toString(),
            endAt = today.plusDays(1).atStartOfDay(zone).toInstant().toString(),
        )
        val r = CalendarData.resolve(e, CalendarSourceInfo("s"), zone)!!
        assertEquals(today, r.lastDay)
        assertFalse(r.multiDay)
    }

    @Test fun dayListPutsSpanningEventsFirstThenTimeOrder() {
        val d = CalendarFixtures.data()
        val list = d.on(today)
        assertEquals("Field trip to Brugge", list.first().title)
        val timed = list.filter { !it.spans }.map { it.title }
        assertEquals(listOf("Stand-up", "Review", "1:1 with Sam", "Planning", "Swimming lesson", "Parent meeting", "Dinner with Anna & Tom"), timed)
    }

    @Test fun upcomingCountdownsSkipPastAndKeepThree() {
        val d = CalendarFixtures.data()
        val list = d.upcomingCountdowns(CalendarFixtures.nowMs)
        assertEquals(listOf("Grandpa's birthday", "Autumn holiday", "Christmas"), list.map { it.title })
    }

    @Test fun localCountdownToggle() {
        val d = CalendarFixtures.data()
        val ev = d.events.single { it.title == "Cinema" }
        val on = d.withCountdown(ev.id, true)
        assertTrue(on.event(ev.id)!!.countdown)
        assertTrue(on.countdowns.any { it.eventId == ev.id })
        assertFalse(on.withCountdown(ev.id, false).countdowns.any { it.eventId == ev.id })
    }

    @Test fun brokenEventsAreSkipped() {
        val cal = FrameCalendar(events = listOf(CalendarEvent("ev-00000009", "x", "No start"), CalendarEvent("ev-00000010", "x", "Bad date", allDay = true, startDate = "soon")))
        assertTrue(CalendarData.from(cal, ZoneId.of("UTC")).isEmpty)
    }
}

class CalendarLayoutTest {
    private val today = CalendarFixtures.today

    @Test fun weekStartFollowsLocaleSetting() {
        assertEquals(LocalDate.of(2026, 10, 19), CalendarLayout.weekStart(today, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 10, 18), CalendarLayout.weekStart(today, CalendarLayout.firstDayOfWeek("sun")))
        assertEquals(LocalDate.of(2026, 10, 17), CalendarLayout.weekStart(today, CalendarLayout.firstDayOfWeek("sat")))
        assertEquals(43, CalendarLayout.weekNumber(today, DayOfWeek.MONDAY))
    }

    @Test fun rangeStartsOnTodayOnlyForTheCurrentWeek() {
        val v = CalendarView.Week
        assertEquals(today, CalendarLayout.rangeStart(v, today, DayOfWeek.MONDAY, startOnToday = true, today = today))
        assertEquals(LocalDate.of(2026, 10, 19), CalendarLayout.rangeStart(v, today, DayOfWeek.MONDAY, startOnToday = false, today = today))
        val next = CalendarLayout.step(v, today, 1)
        assertEquals(LocalDate.of(2026, 10, 26), CalendarLayout.rangeStart(v, next, DayOfWeek.MONDAY, startOnToday = true, today = today))
        assertEquals(LocalDate.of(2026, 11, 1), CalendarLayout.step(CalendarView.Month, today, 1))
        assertEquals(today.plusDays(4), CalendarLayout.step(CalendarView.FourDay, today, 1))
    }

    @Test fun monthGridCoversWholeWeeks() {
        val weeks = CalendarLayout.monthWeeks(YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        assertEquals(5, weeks.size)
        assertEquals(LocalDate.of(2026, 9, 28), weeks.first().first())
        assertEquals(LocalDate.of(2026, 11, 1), weeks.last().last())
        assertEquals(6, CalendarLayout.monthWeeks(YearMonth.of(2026, 8), DayOfWeek.MONDAY).size)
        assertEquals(4, CalendarLayout.monthWeeks(YearMonth.of(2027, 2), DayOfWeek.MONDAY).size)
    }

    @Test fun barsPackIntoLanesAndSpanAcrossTheRow() {
        val d = CalendarFixtures.data()
        val week = List(7) { CalendarLayout.weekStart(today, DayOfWeek.MONDAY).plusDays(it.toLong()) }
        val bars = CalendarLayout.packBars(week, week.flatMap { d.on(it) }.distinct())
        val trip = bars.single { it.event.title == "Field trip to Brugge" }
        assertEquals(2 to 4, trip.startCol to trip.endCol) // Wed..Fri
        assertEquals(0, trip.lane)
        val train = bars.single { it.event.title == "Night train to Vienna" }
        assertEquals(5 to 6, train.startCol to train.endCol) // Sat night into Sunday
        // No two bars share a lane on the same column.
        for (a in bars) for (b in bars) if (a !== b && a.lane == b.lane) {
            assertTrue("${a.event.title} / ${b.event.title}", a.endCol < b.startCol || b.endCol < a.startCol)
        }
    }

    @Test fun barsClippedAtTheRowEdgesAreMarked() {
        val d = CalendarFixtures.data()
        val nextWeek = List(7) { LocalDate.of(2026, 10, 26).plusDays(it.toLong()) }
        val bars = CalendarLayout.packBars(nextWeek, nextWeek.flatMap { d.on(it) }.distinct())
        val holiday = bars.single { it.event.title == "Autumn holiday" }
        assertEquals(5, holiday.startCol) // starts Sat 31 Oct
        assertTrue(holiday.continuesAfter)
        assertFalse(holiday.continuesBefore)
    }

    @Test fun clampLanesCountsWhatItHides() {
        val d = CalendarFixtures.data()
        val week = List(7) { CalendarLayout.weekStart(today, DayOfWeek.MONDAY).plusDays(it.toLong()) }
        val bars = CalendarLayout.packBars(week, week.flatMap { d.on(it) }.distinct())
        val r = CalendarLayout.clampLanes(bars, 7, maxLanes = 3)
        // Thursday has the trip plus seven timed events: two visible lines plus "+N".
        assertTrue(r.visible.filter { 3 in it.startCol..it.endCol }.size <= 2)
        val shownOnThu = r.visible.count { 3 in it.startCol..it.endCol }
        assertEquals(d.on(today).size - shownOnThu, r.hiddenPerCol[3])
        assertTrue(r.visible.all { it.lane < 3 })
    }

    @Test fun overlappingTimedEventsSitSideBySide() {
        val d = CalendarFixtures.data()
        val blocks = CalendarLayout.timedBlocks(today, d.on(today)).associateBy { it.event.title }
        // 11:00–12:00, 11:30–12:00, 11:30–12:30: one cluster of three columns.
        val review = blocks.getValue("Review")
        val oneOnOne = blocks.getValue("1:1 with Sam")
        val planning = blocks.getValue("Planning")
        assertEquals(setOf(3), setOf(review.cols, oneOnOne.cols, planning.cols))
        assertEquals(setOf(0, 1, 2), setOf(review.col, oneOnOne.col, planning.col))
        // 16:30 swim and 16:45 meeting overlap: two columns. Dinner at 19:00 stands alone.
        assertEquals(2, blocks.getValue("Swimming lesson").cols)
        assertEquals(1, blocks.getValue("Parent meeting").col)
        assertEquals(1, blocks.getValue("Dinner with Anna & Tom").cols)
        // A 15-minute stand-up is drawn 30 minutes tall.
        val standUp = blocks.getValue("Stand-up")
        assertEquals(30, standUp.endMin - standUp.startMin)
        assertEquals(1, standUp.cols)
        // The multi-day trip is not a timed block.
        assertNull(blocks["Field trip to Brugge"])
    }

    @Test fun columnsAreReusedWithinACluster() {
        val zone = ZoneId.of("UTC")
        fun ev(id: Int, from: Int, to: Int) = CalendarData.resolve(
            CalendarEvent(
                "ev-%08d".format(id), "s", "E$id",
                startAt = today.atTime(from, 0).atZone(zone).toInstant().toString(), endAt = today.atTime(to, 0).atZone(zone).toInstant().toString(),
            ),
            CalendarSourceInfo("s"), zone,
        )!!
        // A long 9–13 event with 9–10 and 10–11 beside it: two columns, the second reused.
        val blocks = CalendarLayout.timedBlocks(today, listOf(ev(1, 9, 13), ev(2, 9, 10), ev(3, 10, 11)))
        assertTrue(blocks.all { it.cols == 2 })
        assertEquals(1, blocks.single { it.event.title == "E3" }.col)
    }

    @Test fun hourRangeWidensToFitEvents() {
        val d = CalendarFixtures.data()
        val week = List(7) { CalendarLayout.weekStart(today, DayOfWeek.MONDAY).plusDays(it.toLong()) }
        val r = CalendarLayout.hourRange(week, d)
        assertEquals(7, r.first)
        assertEquals(22, r.last) // dinner ends at 21:30
        val next = List(7) { LocalDate.of(2026, 10, 26).plusDays(it.toLong()) }
        assertEquals(23, CalendarLayout.hourRange(next, d).last) // the cinema ends at 22:15
        val quiet = CalendarLayout.hourRange(listOf(LocalDate.of(2030, 1, 1)), d)
        assertEquals(7..21, quiet)
    }

    @Test fun upcomingListsTodayThenBusyDays() {
        val d = CalendarFixtures.data()
        val list = CalendarLayout.upcoming(d, today, CalendarFixtures.nowMs)
        assertEquals(today, list.first().date)
        // Past timed events of today are dropped (it is 14:20).
        assertFalse(list.first().timed.any { it.title == "Stand-up" })
        assertTrue(list.first().timed.any { it.title == "Swimming lesson" })
        assertTrue(list.drop(1).none { it.isEmpty })
        // The trip that started yesterday is not repeated on later days.
        assertTrue(list.drop(1).none { day -> day.spanning.any { it.title == "Field trip to Brugge" } })
    }
}

class TimedViewTest {
    private val weekdays = CalendarScheduleDto("t", "07:00", "09:00", listOf("mon", "tue", "wed", "thu", "fri"), true)
    private val thu = LocalDate.of(2026, 10, 22)

    @Test fun windowsFollowDaysAndTimes() {
        assertTrue(TimedView.active(listOf(weekdays), thu.atTime(7, 30)) != null)
        assertNull(TimedView.active(listOf(weekdays), thu.atTime(9, 0)))
        assertNull(TimedView.active(listOf(weekdays), LocalDate.of(2026, 10, 24).atTime(8, 0))) // Saturday
        assertNull(TimedView.active(listOf(weekdays.copy(enabled = false)), thu.atTime(8, 0)))
        assertEquals(LocalDateTime.of(thu, java.time.LocalTime.of(9, 0)), TimedView.nextBoundary(listOf(weekdays), thu.atTime(8, 0)))
        // Past midnight.
        val night = CalendarScheduleDto("n", "22:00", "01:00", listOf("thu"), true)
        assertTrue(TimedView.active(listOf(night), thu.plusDays(1).atTime(0, 30)) != null)
    }

    @Test fun decisionsDeferAfterTouchAndRespectDismissal() {
        assertEquals(TimedView.Action.Open, TimedView.decide(inWindow = true, openByTimer = false, calendarOpen = false, sinceTouchMs = null))
        assertEquals(TimedView.Action.None, TimedView.decide(inWindow = true, openByTimer = false, calendarOpen = false, sinceTouchMs = 20_000))
        assertEquals(TimedView.Action.Open, TimedView.decide(inWindow = true, openByTimer = false, calendarOpen = false, sinceTouchMs = 61_000))
        assertEquals(TimedView.Action.None, TimedView.decide(inWindow = true, openByTimer = false, calendarOpen = false, sinceTouchMs = null, dismissed = true))
        assertEquals(TimedView.Action.Close, TimedView.decide(inWindow = false, openByTimer = true, calendarOpen = true, sinceTouchMs = 90_000))
        // Opened by hand: the end of a window does not close it.
        assertEquals(TimedView.Action.None, TimedView.decide(inWindow = false, openByTimer = false, calendarOpen = true, sinceTouchMs = null))
    }
}
