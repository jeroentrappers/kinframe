package app.kinframe.family

import android.net.Uri
import app.kinframe.core.api.CalDavCalendar
import app.kinframe.core.api.CalendarEvent
import app.kinframe.core.api.CalendarSource
import app.kinframe.core.api.CalendarSourceCreate
import app.kinframe.core.api.CalendarTestResult
import app.kinframe.core.api.CalendarView
import app.kinframe.core.api.Countdown
import app.kinframe.core.api.EventInvitation
import app.kinframe.core.api.EventInvitationCreate
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.ui.calendar.CalendarLogic
import app.kinframe.family.ui.calendar.CalendarViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** The Calendar tab: the same rules as web/src/lib/calendar.ts, and the view model against a fake API. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CalendarTest {
    private val bxl = ZoneId.of("Europe/Brussels")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- pure rules

    @Test fun nextColorTakesTheFirstUnusedThenCycles() {
        assertEquals("coral", CalendarLogic.nextColor(emptyList()))
        assertEquals("amber", CalendarLogic.nextColor(listOf("coral", "tangerine")))
        assertEquals("tangerine", CalendarLogic.nextColor(CalendarLogic.COLORS + "x"))
    }

    @Test fun calendarAddresses() {
        assertTrue(CalendarLogic.urlOk("webcal://p01-caldav.icloud.com/x.ics"))
        assertTrue(CalendarLogic.urlOk(" https://calendar.google.com/calendar/ical/a/basic.ics "))
        assertFalse(CalendarLogic.urlOk("calendar.google.com"))
        assertFalse(CalendarLogic.urlOk("ftp://x.org/a.ics"))
    }

    @Test fun timedEventsEndingAtMidnightStayOnOneDay() {
        val ev = CalendarEvent("e", "s", "Party", startAt = "2026-11-03T19:00:00Z", endAt = "2026-11-03T23:00:00Z") // 20:00–00:00 local
        assertEquals(LocalDate.of(2026, 11, 3) to LocalDate.of(2026, 11, 3), CalendarLogic.span(ev, bxl))
        val allDay = CalendarEvent("a", "s", "Trip", allDay = true, startDate = "2026-11-03", endDate = "2026-11-05")
        assertEquals(LocalDate.of(2026, 11, 5), CalendarLogic.span(allDay, bxl).second)
        assertTrue(CalendarLogic.isMultiDay(allDay, bxl))
    }

    @Test fun agendaShowsTodayAndMultiDayEventsOnce() {
        val today = LocalDate.of(2026, 11, 3)
        val trip = CalendarEvent("a", "s", "Trip", allDay = true, startDate = "2026-11-02", endDate = "2026-11-06")
        val lunch = CalendarEvent("b", "s", "Lunch", startAt = "2026-11-05T11:00:00Z", endAt = "2026-11-05T12:00:00Z")
        val days = CalendarLogic.agenda(listOf(trip, lunch), bxl, today, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 1))
        assertEquals(listOf(today, LocalDate.of(2026, 11, 5)), days.map { it.day })
        assertEquals(listOf("Trip"), days[0].events.map { it.title }) // shown once, on today
        assertEquals(listOf("Lunch"), days[1].events.map { it.title })
    }

    @Test fun countdownsMonthGridAndWeather() {
        val now = Instant.parse("2026-11-03T10:00:00Z")
        assertEquals(4, CalendarLogic.daysUntil(Countdown("e", allDay = true, startDate = "2026-11-07"), bxl, now))
        assertEquals(0, CalendarLogic.daysUntil(Countdown("e", startAt = "2026-11-03T20:00:00Z"), bxl, now))
        val weeks = CalendarLogic.monthGrid(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 10, 26), weeks.first().first())
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(DayOfWeek.SUNDAY, CalendarLogic.monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY).first().first().dayOfWeek)
        assertEquals(CalendarLogic.WeatherKind.Showers, CalendarLogic.weatherKind(81))
        assertEquals(CalendarLogic.WeatherKind.Rain, CalendarLogic.weatherKind(63))
        assertEquals("68°", CalendarLogic.formatTemp(20.0, "f"))
    }

    @Test fun sendEventFormValidatesLikeTheWeb() {
        val base = CalendarLogic.defaultForm(LocalDateTime.of(2026, 11, 3, 10, 20))
        assertEquals(LocalDateTime.of(2026, 11, 3, 11, 0), base.start)
        assertEquals(CalendarLogic.FormError.Title, CalendarLogic.build(base).first)
        val timed = base.copy(title = " Lunch ", location = " ")
        val (err, body) = CalendarLogic.build(timed, ZoneId.of("UTC"))
        assertNull(err)
        assertEquals(EventInvitationCreate("Lunch", startAt = "2026-11-03T11:00:00Z", endAt = "2026-11-03T12:00:00Z"), body)
        assertEquals(CalendarLogic.FormError.Order, CalendarLogic.build(timed.copy(end = timed.start)).first)
        // Moving the start keeps the length.
        assertEquals(LocalDateTime.of(2026, 11, 4, 12, 0), CalendarLogic.moveStart(timed, LocalDateTime.of(2026, 11, 4, 11, 0)).end)
        val day = timed.copy(allDay = true, startDate = LocalDate.of(2026, 12, 24), endDate = null)
        assertEquals("2026-12-24", CalendarLogic.build(day).second!!.endDate)
        assertEquals(LocalDate.of(2026, 12, 25), CalendarLogic.moveStartDate(day.copy(endDate = LocalDate.of(2026, 12, 20)), LocalDate.of(2026, 12, 25)).endDate)
    }

    // ---------------------------------------------------------------- view model

    private val local = CalendarSource("loc", kind = "local", color = "rose")

    @Test fun addsEachPickedCalDavCalendarWithItsOwnColour() = runTest {
        val created = mutableListOf<CalendarSourceCreate>()
        val api = object : FakeApi() {
            override suspend fun calendars(frameId: String) = listOf(local)
            override suspend fun frameCalendar(frameId: String) = CalendarView()
            override suspend fun eventInvitations(frameId: String) = emptyList<EventInvitation>()
            override suspend fun testCalendar(frameId: String, request: CalendarSourceCreate) = CalendarTestResult(
                ok = true, calendars = listOf(CalDavCalendar("https://c/1", "Home"), CalDavCalendar("https://c/2", "Work")),
            )
            override suspend fun createCalendar(frameId: String, request: CalendarSourceCreate, idempotencyKey: String): CalendarSource {
                created += request
                return CalendarSource("n${created.size}", kind = "caldav", name = request.name!!, color = request.color!!, status = "syncing")
            }
        }
        val vm = CalendarViewModel(api, "f1", manage = true, mayPut = true, live = emptyFlow()) { _, _ -> "up" }
        vm.openAdd()
        assertEquals("coral", vm.form.value!!.color)
        vm.editForm { it.copy(kind = "caldav", url = "https://cloud.example.org", username = "anna", password = "app-pass") }
        vm.test()
        assertEquals(listOf("https://c/1"), vm.form.value!!.picked)
        vm.togglePick("https://c/2")
        vm.save()
        assertNull(vm.form.value)
        assertEquals(listOf("Home", "Work"), created.map { it.name })
        assertEquals(listOf("coral", "tangerine"), created.map { it.color })
        assertEquals(listOf("https://c/1", "https://c/2"), created.map { it.calendarUrl })
        assertTrue(created.all { it.password == "app-pass" && it.username == "anna" })
        assertEquals(3, vm.state.value.sources!!.size)
        // Changing the address again needs a new test.
        vm.openAdd(); vm.editForm { it.copy(url = "https://a.org/x.ics") }; vm.test()
        vm.editForm { it.copy(url = "https://b.org/x.ics") }
        assertNull(vm.form.value!!.result)
    }

    @Test fun sendsAnEventWithItsPhotoAndFollowsTheAnswer() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 4)
        var sent: EventInvitationCreate? = null
        val api = object : FakeApi() {
            override suspend fun eventInvitations(frameId: String) = emptyList<EventInvitation>()
            override suspend fun sendEvent(frameId: String, request: EventInvitationCreate, idempotencyKey: String): EventInvitation {
                sent = request
                return EventInvitation("i1", senderId = "me", title = request.title)
            }
            override suspend fun withdrawEvent(frameId: String, invitationId: String) = Unit
        }
        val vm = CalendarViewModel(api, "f1", manage = false, mayPut = true, live = live) { _, p -> p(1f); "photo-up" }
        var closed = false
        vm.send(CalendarLogic.defaultForm().copy(title = "Lunch"), Uri.parse("content://p/1"), "background", "Oma") { closed = true }
        assertTrue(closed)
        assertEquals("photo-up", sent!!.photoUploadId)
        assertEquals("background", sent!!.photoPlacement)
        assertEquals("pending", vm.state.value.invitations!!.single().status)
        live.emit(LiveEvent.InvitationDecided("f1", EventInvitation("i1", status = "accepted", title = "Lunch")))
        assertEquals("accepted", vm.state.value.invitations!!.single().status)
        vm.withdraw(vm.state.value.invitations!!.single())
        assertEquals("withdrawn", vm.state.value.invitations!!.single().status)
    }

    @Test fun countdownToggleUpdatesThePreview() = runTest {
        val marked = mutableListOf<String>()
        val ev = CalendarEvent("e1", "s", "Birthday", allDay = true, startDate = "2026-12-01")
        val api = object : FakeApi() {
            override suspend fun calendars(frameId: String) = emptyList<CalendarSource>()
            override suspend fun frameCalendar(frameId: String) = CalendarView(events = listOf(ev.copy(countdown = "e1" in marked)))
            override suspend fun eventInvitations(frameId: String) = emptyList<EventInvitation>()
            override suspend fun setCountdown(frameId: String, eventId: String) { marked += eventId }
            override suspend fun updateCalendar(frameId: String, calendarId: String, patch: JsonObject) = local
        }
        val vm = CalendarViewModel(api, "f1", manage = true, mayPut = false, live = emptyFlow()) { _, _ -> "" }
        var after: CalendarEvent? = null
        vm.toggleCountdown(ev) { after = it }
        assertEquals(listOf("e1"), marked)
        assertTrue(after!!.countdown)
        assertTrue(vm.state.value.view!!.events.single().countdown)
    }
}
