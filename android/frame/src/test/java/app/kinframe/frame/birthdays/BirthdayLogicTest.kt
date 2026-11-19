package app.kinframe.frame.birthdays

import app.kinframe.core.api.FrameBirthday
import app.kinframe.frame.calendar.CalendarData
import app.kinframe.frame.calendar.CountdownItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BirthdayLogicTest {
    private val brussels = ZoneId.of("Europe/Brussels")
    private val oma = FrameBirthday("b1", "Oma", day = 12, month = 3, birthYear = 1946, photoUrl = "https://k/oma.webp")
    private val lotte = FrameBirthday("b2", "Lotte", day = 2, month = 6, birthYear = 2026)
    private val leap = FrameBirthday("b3", "Leo", day = 29, month = 2, birthYear = 1948)
    private val noYear = FrameBirthday("b4", "Ann", day = 12, month = 3)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun todayIsTheFramesLocalDate() {
        // 23:30 UTC on 11 March is already 12 March in Brussels, still 11 March in UTC and New York.
        val now = ms("2027-03-11T23:30:00Z")
        assertEquals(LocalDate.of(2027, 3, 12), BirthdayLogic.localDate(now, brussels))
        assertEquals(LocalDate.of(2027, 3, 11), BirthdayLogic.localDate(now, ZoneId.of("UTC")))
        assertEquals(listOf("Oma"), BirthdayLogic.today(listOf(oma, lotte), BirthdayLogic.localDate(now, brussels)).map { it.name })
        assertTrue(BirthdayLogic.today(listOf(oma), BirthdayLogic.localDate(now, ZoneId.of("America/New_York"))).isEmpty())
    }

    @Test
    fun midnightRollover() {
        val before = BirthdayLogic.localDate(ms("2027-03-11T22:59:59Z"), brussels) // 23:59:59 local
        val after = BirthdayLogic.localDate(ms("2027-03-11T23:00:00Z"), brussels) // 00:00 local
        assertTrue(BirthdayLogic.today(listOf(oma), before).isEmpty())
        assertEquals(81, BirthdayLogic.today(listOf(oma), after).single().age)
        // …and it leaves at 24:00.
        assertTrue(BirthdayLogic.today(listOf(oma), BirthdayLogic.localDate(ms("2027-03-12T23:00:00Z"), brussels)).isEmpty())
    }

    @Test
    fun leapDayFallsOnThe28thInOtherYears() {
        assertEquals(79, BirthdayLogic.on(leap, LocalDate.of(2027, 2, 28))!!.age)
        assertNull(BirthdayLogic.on(leap, LocalDate.of(2027, 3, 1)))
        assertEquals(80, BirthdayLogic.on(leap, LocalDate.of(2028, 2, 29))!!.age)
        assertNull("in leap years only on the 29th", BirthdayLogic.on(leap, LocalDate.of(2028, 2, 28)))
        // The countdown too: on 21 February 2027 Leo's birthday is 7 days ahead.
        assertEquals(LocalDate.of(2027, 2, 28), BirthdayLogic.upcoming(listOf(leap), LocalDate.of(2027, 2, 21)).single().date)
    }

    @Test
    fun theDateOfBirthItselfIsNoBirthday() {
        assertNull(BirthdayLogic.on(lotte, LocalDate.of(2026, 6, 2)))
        assertEquals(1, BirthdayLogic.on(lotte, LocalDate.of(2027, 6, 2))!!.age)
        assertNull("no year, no age", BirthdayLogic.on(noYear, LocalDate.of(2027, 3, 12))!!.age)
    }

    @Test
    fun cardOrderPhotosFirstThenName() {
        val zoe = FrameBirthday("b5", "Zoë", 12, 3, photoUrl = "https://k/z.webp")
        val order = BirthdayLogic.today(listOf(noYear, zoe, oma), LocalDate.of(2027, 3, 12)).map { it.name }
        assertEquals(listOf("Oma", "Zoë", "Ann"), order)
    }

    @Test
    fun sevenDayWindow() {
        val today = LocalDate.of(2027, 3, 5)
        val days = BirthdayLogic.upcoming(listOf(oma, noYear, lotte), today)
        assertEquals(listOf(LocalDate.of(2027, 3, 12)), days.map { it.date })
        assertEquals(listOf("Oma", "Ann"), days.single().people.map { it.name })
        assertTrue("8 days ahead is too far", BirthdayLogic.upcoming(listOf(oma), LocalDate.of(2027, 3, 4)).isEmpty())
        assertTrue("today is the card, not a tile", BirthdayLogic.upcoming(listOf(oma), LocalDate.of(2027, 3, 12)).isEmpty())
        assertEquals(1, BirthdayLogic.upcoming(listOf(oma), LocalDate.of(2027, 3, 11)).size)
        // Across the new year.
        val nye = FrameBirthday("b6", "Jan", 2, 1)
        assertEquals(LocalDate.of(2028, 1, 2), BirthdayLogic.upcoming(listOf(nye), LocalDate.of(2027, 12, 30)).single().date)
    }

    @Test
    fun nothingWhileTheClockIsWrong() {
        val now = ms("2027-03-12T09:00:00Z")
        assertNull(BirthdayLogic.trustedDate(now, brussels, clockWrong = true))
        assertTrue(BirthdayLogic.card(listOf(oma), BirthdayLogic.trustedDate(now, brussels, true)).isEmpty())
        assertTrue(BirthdayLogic.countdowns(listOf(oma), BirthdayLogic.trustedDate(ms("2027-03-10T09:00:00Z"), brussels, true)).isEmpty())
        assertEquals(1, BirthdayLogic.card(listOf(oma), BirthdayLogic.trustedDate(now, brussels, false)).size)
    }

    private fun event(id: String, day: LocalDate, hour: Int? = null, title: String = id) =
        CountdownItem(id, title, day, hour?.let { ZonedDateTime.of(day.atTime(it, 0), brussels) }, "blue")

    private fun bday(date: LocalDate, vararg names: String) =
        BirthdayDay(date, names.map { BirthdayOccurrence(FrameBirthday(it, it, date.dayOfMonth, date.monthValue), date, null) })

    @Test
    fun mergeOrdersByDateBirthdaysFirstOnADay() {
        val d = LocalDate.of(2027, 3, 14)
        val merged = BirthdayLogic.mergeCountdowns(
            listOf(event("timed", d, 9), event("allday", d), event("later", d.plusDays(2))),
            listOf(bday(d, "Oma")), card = false, max = 4,
        )
        assertEquals(listOf("birthday:$d", "allday", "timed", "later"), merged.map { it.eventId })
    }

    @Test
    fun atMostThreeEntriesWithTheCard() {
        val d = LocalDate.of(2027, 3, 13)
        val events = listOf(event("e1", d.plusDays(1)), event("e2", d.plusDays(2)), event("e3", d.plusDays(3)))
        assertEquals(3, BirthdayLogic.mergeCountdowns(events, emptyList(), card = false).size)
        assertEquals(listOf("e1", "e2"), BirthdayLogic.mergeCountdowns(events, emptyList(), card = true).map { it.eventId })
        assertEquals(listOf("birthday:$d", "e1", "e2"), BirthdayLogic.mergeCountdowns(events, listOf(bday(d, "Oma")), card = false).map { it.eventId })
    }

    @Test
    fun aMarkedEventAlwaysKeepsASlot() {
        val d = LocalDate.of(2027, 3, 13)
        val birthdays = (0..3).map { bday(d.plusDays(it.toLong()), "P$it") }
        val far = event("party", d.plusDays(30))
        val merged = BirthdayLogic.mergeCountdowns(listOf(far), birthdays, card = false)
        assertEquals(listOf("birthday:$d", "birthday:${d.plusDays(1)}", "party"), merged.map { it.eventId })
        // With the card: one birthday tile and the event.
        assertEquals(listOf("birthday:$d", "party"), BirthdayLogic.mergeCountdowns(listOf(far), birthdays, card = true).map { it.eventId })
        // Without events, birthdays fill every slot.
        assertEquals(3, BirthdayLogic.mergeCountdowns(emptyList(), birthdays, card = false).size)
    }

    @Test
    fun tiesOnADayGoByTitle() {
        val d = LocalDate.of(2027, 3, 14)
        val merged = BirthdayLogic.mergeCountdowns(listOf(event("b", d, title = "Zoo"), event("a", d, title = "Art")), emptyList(), card = false)
        assertEquals(listOf("a", "b"), merged.map { it.eventId })
    }

    @Test
    fun calendarEntriesAroundToday() {
        val today = LocalDate.of(2027, 3, 1)
        val cal = CalendarData.withBirthdays(null, listOf(oma, leap, lotte), today, brussels)!!
        val e = cal.on(LocalDate.of(2027, 3, 12)).single()
        assertEquals("🎈 Oma (81)", e.title)
        assertTrue(e.allDay && e.birthday != null && e.color == "rose")
        assertEquals("🎈 Leo (79)", cal.on(LocalDate.of(2027, 2, 28)).single().title)
        assertTrue("born 2026: no entry on the date of birth", cal.on(LocalDate.of(2026, 6, 2)).isEmpty())
        assertEquals(1, cal.on(LocalDate.of(2027, 6, 2)).size)
        assertNull(CalendarData.withBirthdays(null, emptyList(), today, brussels))
        assertNull("clock not trusted", CalendarData.withBirthdays(null, listOf(oma), null, brussels))
    }
}
