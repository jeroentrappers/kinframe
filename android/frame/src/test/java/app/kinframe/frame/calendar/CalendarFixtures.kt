package app.kinframe.frame.calendar

import app.kinframe.core.api.CalendarEvent
import app.kinframe.core.api.CalendarSourceInfo
import app.kinframe.core.api.CalendarWindow
import app.kinframe.core.api.Countdown
import app.kinframe.core.api.FrameCalendar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A lively family week around Thursday 22 October 2026 (Gent), merged from four calendars. */
object CalendarFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Brussels")
    val today: LocalDate = LocalDate.of(2026, 10, 22)
    /** 14:20 local, as in the weather fixtures. */
    val nowMs: Long = today.atTime(14, 20).atZone(zone).toInstant().toEpochMilli()

    private var n = 0
    private fun id() = "ev-%08d".format(++n)

    private fun timed(src: String, title: String, day: LocalDate, from: String, to: String, endDay: LocalDate = day, location: String? = null, recurring: Boolean = false, notes: String? = null) =
        CalendarEvent(
            id = id(), sourceId = src, title = title,
            startAt = LocalDateTime.of(day, java.time.LocalTime.parse(from)).atZone(zone).toInstant().toString(),
            endAt = LocalDateTime.of(endDay, java.time.LocalTime.parse(to)).atZone(zone).toInstant().toString(),
            location = location, recurring = recurring, notes = notes,
        )

    private fun allDay(src: String, title: String, from: LocalDate, to: LocalDate = from, sender: String? = null, invitation: String? = null) =
        CalendarEvent(id = id(), sourceId = src, title = title, allDay = true, startDate = from.toString(), endDate = to.toString(), senderName = sender, invitationId = invitation)

    fun calendar(): FrameCalendar {
        n = 0
        val d = { off: Long -> today.plusDays(off) }
        val events = mutableListOf(
            allDay("fam", "Autumn holiday", d(9), d(17)),
            allDay("fam", "Grandpa's birthday", d(2)),
            allDay("rcv", "Lunch at Oma's", d(3), sender = "Oma", invitation = "inv1"),
            allDay("school", "Field trip to Brugge", d(-1), d(1)),
            timed("work", "Stand-up", d(0), "09:00", "09:15", recurring = true),
            timed("fam", "Swimming lesson", d(0), "16:30", "17:30", location = "Zwembad Rooigem", recurring = true),
            timed("school", "Parent meeting", d(0), "16:45", "17:45", location = "Classroom 2B"),
            timed("fam", "Dinner with Anna & Tom", d(0), "19:00", "21:30", location = "De Vitrine", notes = "Bring the photo book."),
            timed("work", "Dentist", d(1), "08:30", "09:00"),
            timed("fam", "Football training", d(1), "17:00", "18:30", recurring = true),
            timed("school", "Piano", d(-2), "15:30", "16:15", recurring = true),
            timed("fam", "Market", d(2), "09:00", "11:00", location = "Vrijdagmarkt"),
            timed("fam", "Night train to Vienna", d(2), "21:40", "09:10", endDay = d(3)),
            timed("work", "Team lunch", d(4), "12:00", "13:30"),
            timed("fam", "Cinema", d(5), "20:00", "22:15"),
            timed("fam", "Brunch", d(6), "10:30", "12:00"),
            timed("work", "Review", d(0), "11:00", "12:00"),
            timed("work", "1:1 with Sam", d(0), "11:30", "12:00"),
            timed("work", "Planning", d(0), "11:30", "12:30"),
            timed("fam", "Vet: Pluis", d(-6), "10:00", "10:30"),
            timed("school", "Swimming", d(-5), "13:00", "14:00"),
            timed("fam", "Book club", d(-9), "20:00", "22:00"),
            timed("work", "Workshop", d(12), "09:00", "17:00"),
            allDay("fam", "Halloween party", d(9)),
            allDay("school", "Report cards", d(15)),
        )
        // A few months back and forth for the month views.
        for (k in listOf(-20L, -13L, -12L, 18L, 19L, 22L, 26L)) events += timed("fam", "Running club", d(k), "07:30", "08:30", recurring = true)
        return FrameCalendar(
            generatedAt = "2026-10-22T12:00:00Z", timezone = zone.id,
            window = CalendarWindow(today.minusDays(30).toString(), today.plusDays(30).toString()),
            sources = listOf(
                CalendarSourceInfo("rcv", "local", "", "rose"),
                CalendarSourceInfo("fam", "ics", "Family", "coral"),
                CalendarSourceInfo("school", "caldav", "School", "teal"),
                CalendarSourceInfo("work", "ics", "Work", "ocean"),
            ),
            events = events,
            countdowns = listOf(
                Countdown("ev-cd1", "fam", "Autumn holiday", true, startDate = today.plusDays(9).toString(), color = "coral"),
                Countdown("ev-cd2", "fam", "Grandpa's birthday", true, startDate = today.plusDays(2).toString(), color = "amber"),
                Countdown("ev-cd3", "fam", "Christmas", true, startDate = "2026-12-25", color = "mint"),
                Countdown("ev-cd4", "fam", "Ski trip", true, startDate = "2027-02-13", color = "sky"),
                Countdown("ev-cd0", "fam", "Already over", true, startDate = "2026-10-01", color = "sky"),
            ),
        )
    }

    fun data(): CalendarData = CalendarData.from(calendar(), zone)
}
