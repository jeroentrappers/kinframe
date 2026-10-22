package app.kinframe.core

import app.kinframe.core.api.CalendarColors
import app.kinframe.core.api.ChangeType
import app.kinframe.core.api.ChangesPage
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.FrameCalendar
import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.KinJson
import app.kinframe.core.auth.DeviceCredential
import app.kinframe.core.auth.MemoryCredentialStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalendarApiTest {
    private val server = MockWebServer()
    private lateinit var api: DeviceApi

    @Before
    fun setUp() {
        server.start()
        api = DeviceApi(OkHttpClient(), server.url("/v1"), MemoryCredentialStore(DeviceCredential("dev1", "s3cret")), "test/1", { 0L })
        server.enqueue(MockResponse.Builder().code(200).body("""{"access_token":"t1","expires_in":3600}""").build())
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    private val change = """
        {"changes":[{"seq":7,"type":"calendar","calendar":{
          "generated_at":"2026-10-22T12:00:00Z","timezone":"Europe/Brussels",
          "window":{"from":"2026-09-22","to":"2026-11-21"},
          "sources":[{"id":"s1","kind":"ics","name":"Family","color":"coral","status":"ok","last_synced_at":"2026-10-22T11:58:00Z"},
                     {"id":"s2","kind":"local","name":"","color":"rose","status":"ok","future_field":1}],
          "events":[
            {"id":"ev-aaaaaaaa","source_id":"s1","title":"Swimming","all_day":false,"start_at":"2026-10-22T15:00:00Z","end_at":"2026-10-22T16:00:00Z","recurring":true,"countdown":false},
            {"id":"ev-bbbbbbbb","source_id":"s2","title":"Lunch at Oma's","all_day":true,"start_date":"2026-10-24","end_date":"2026-10-24","sender_name":"Oma","invitation_id":"i1","has_photo":true,"photo_placement":"background"}
          ],
          "countdowns":[{"event_id":"ev-cccccccc","source_id":"s1","title":"Holiday","all_day":true,"start_date":"2026-12-20","color":"coral"}]
        }},{"seq":8,"type":"calendar","calendar":null}],"next_seq":8,"has_more":false}
    """.trimIndent()

    @Test
    fun parsesCalendarChange() {
        val page = KinJson.decodeFromString(ChangesPage.serializer(), change)
        val c = page.changes[0]
        assertEquals(ChangeType.CALENDAR, c.type)
        val cal = FrameCalendar.of(c.calendar)!!
        assertEquals("Europe/Brussels", cal.timezone)
        assertEquals("2026-09-22", cal.window!!.from)
        assertEquals(2, cal.sources.size)
        assertTrue(cal.sources[1].isLocal)
        assertEquals("Swimming", cal.events[0].title)
        assertTrue(cal.events[0].recurring)
        assertTrue(cal.events[1].allDay)
        assertEquals("Oma", cal.events[1].senderName)
        assertEquals("background", cal.events[1].photoPlacement)
        assertEquals("Holiday", cal.countdowns.single().title)
        // `calendar: null` stays null (drop the calendar).
        assertNull(page.changes[1].calendar)
        // Round trip through the stored string form.
        val stored = KinJson.encodeToString(FrameCalendar.serializer(), cal)
        assertEquals(cal, FrameCalendar.parse(stored))
        assertNull(FrameCalendar.parse("not json"))
    }

    @Test
    fun calendarSettingsDefaultsAndValues() {
        val d = FrameSettings.DEFAULT
        assertEquals("week", d.calendar.view)
        assertTrue(d.calendar.startOnToday)
        assertFalse(d.widgets.calendarSidebar)
        assertTrue(d.widgets.countdowns)
        val raw = KinJson.parseToJsonElement(
            """{"version":3,"widgets":{"calendar_sidebar":true,"countdowns":false},
               "calendar":{"view":"month","week_numbers":true,"timed_views":[{"id":"t1","from":"07:00","to":"09:00","days":["mon","tue"],"enabled":true}]}}""",
        ).jsonObject
        val s = FrameSettings.parse(raw)
        assertTrue(s.widgets.calendarSidebar)
        assertFalse(s.widgets.countdowns)
        assertEquals("month", s.calendar.view)
        assertTrue(s.calendar.weekNumbers)
        assertEquals(listOf("mon", "tue"), s.calendar.timedViews.single().days)
    }

    @Test
    fun paletteHasTwelveColoursAndFallsBack() {
        assertEquals(12, CalendarColors.palette.size)
        assertEquals(CalendarColors.palette.getValue("sky"), CalendarColors.swatch("ultraviolet"))
        assertEquals(0xFFE5726A, CalendarColors.swatch("coral").light)
    }

    @Test
    fun invitationsAndDecisions() = runBlocking {
        server.enqueue(json("""[{"id":"i1","sender_name":"Oma","status":"pending","title":"Lunch","all_day":true,"start_date":"2026-10-24","has_photo":true,"photo_placement":"top","created_at":"2026-10-22T10:00:00Z"}]"""))
        server.enqueue(json("", 204))
        val list = api.invitations()
        assertEquals("Lunch", list.single().title)
        assertTrue(list.single().hasPhoto)
        api.decideInvitation("i1", accept = true)
        server.takeRequest() // token
        assertEquals("/v1/device/invitations", server.takeRequest().url.encodedPath)
        val decide = server.takeRequest()
        assertEquals("POST", decide.method)
        assertEquals("/v1/device/invitations/i1", decide.url.encodedPath)
        assertEquals("""{"decision":"accept"}""", decide.body!!.utf8())
        assertEquals("/v1/device/invitations/i1/photo", api.invitationPhotoUrl("i1").encodedPath)
    }

    @Test
    fun countdownsAndSync() = runBlocking {
        server.enqueue(json("", 204))
        server.enqueue(json("", 204))
        server.enqueue(json("", 202))
        api.setCountdown("ev-aaaaaaaa", on = true)
        api.setCountdown("ev-aaaaaaaa", on = false)
        api.syncCalendars()
        server.takeRequest()
        val put = server.takeRequest()
        assertEquals("PUT" to "/v1/device/countdowns/ev-aaaaaaaa", put.method to put.url.encodedPath)
        val del = server.takeRequest()
        assertEquals("DELETE" to "/v1/device/countdowns/ev-aaaaaaaa", del.method to del.url.encodedPath)
        val sync = server.takeRequest()
        assertEquals("POST" to "/v1/device/calendar/sync", sync.method to sync.url.encodedPath)
    }
}
