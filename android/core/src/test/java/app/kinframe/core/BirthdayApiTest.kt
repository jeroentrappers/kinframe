package app.kinframe.core

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.BirthdayDates
import app.kinframe.core.api.BirthdayPersonCreate
import app.kinframe.core.api.BirthdayPersonUpdate
import app.kinframe.core.api.BirthdayStatus
import app.kinframe.core.api.Change
import app.kinframe.core.api.ChangeType
import app.kinframe.core.api.ChangesPage
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.FeedPreferencesUpdate
import app.kinframe.core.api.FrameBirthdays
import app.kinframe.core.api.FrameFeatures
import app.kinframe.core.api.HttpKinApi
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.Subscription
import app.kinframe.core.api.SubscriptionUpdate
import app.kinframe.core.api.User
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class BirthdayApiTest {
    private val server = MockWebServer()
    private val tokens = object : AccessTokenSource {
        override suspend fun accessToken(forceRefresh: Boolean) = "t"
    }
    private lateinit var api: HttpKinApi

    @Before
    fun setUp() {
        server.start()
        api = HttpKinApi(OkHttpClient(), server.url("/v1"), tokens)
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    private val oma = """{"id":"b1","feed_id":"fd1","status":"approved","display_name":"Oma","day":12,"month":3,"birth_year":1946,
        "show_age":false,"photo_url":"https://k/avatars/b/x.webp","has_own_photo":true,"user_id":null,"created_by_name":"Anna",
        "suggested":false,"next_birthday":"2027-03-12","turns":81,"greetings_today":0,"can_edit":true,"can_decide":false,
        "created_at":"2026-11-19T10:00:00Z","updated_at":"2026-11-19T10:00:00Z","future_field":1}"""

    @Test
    fun listsBirthdaysAndDecodesEveryField() = runBlocking {
        server.enqueue(json("[$oma]"))
        val list = api.feedBirthdays("fd1", BirthdayStatus.PENDING)
        val b = list.single()
        assertEquals("Oma", b.displayName)
        assertEquals(1946, b.birthYear)
        assertTrue(b.isApproved && b.hasOwnPhoto && b.canEdit)
        assertEquals("2027-03-12", b.nextBirthday)
        assertEquals(81, b.turns)
        val r = server.takeRequest()
        assertEquals("/v1/feeds/fd1/birthdays", r.url.encodedPath)
        assertEquals("pending", r.url.queryParameter("status"))
    }

    @Test
    fun createSendsBodyWithIdempotencyKey() = runBlocking {
        server.enqueue(json(oma.replace("\"approved\"", "\"pending\""), 201))
        val b = api.createFeedBirthday("fd1", BirthdayPersonCreate("Oma", 12, 3, 1946, showAge = false), "k1")
        assertTrue(b.isPending)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("k1", r.headers["Idempotency-Key"])
        assertEquals("""{"display_name":"Oma","day":12,"month":3,"birth_year":1946,"show_age":false}""", r.body!!.utf8())
    }

    @Test
    fun updateSendsOnlyChangedFieldsAndExplicitNulls() = runBlocking {
        server.enqueue(json(oma))
        api.updateFeedBirthday("fd1", "b1", BirthdayPersonUpdate(showAge = true, clearYear = true, unlink = true))
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals("""{"birth_year":null,"show_age":true,"user_id":null}""", r.body!!.utf8())
        assertTrue(BirthdayPersonUpdate().isEmpty)
    }

    @Test
    fun duplicateBirthdayCarriesTheExistingId() = runBlocking {
        server.enqueue(json("""{"type":"x","title":"Duplicate","status":409,"code":"duplicate_birthday","detail":"b9"}""", 409))
        try {
            api.createFeedBirthday("fd1", BirthdayPersonCreate("Oma", 12, 3), "k2")
            fail()
        } catch (e: ApiException) {
            assertEquals(ProblemCode.DUPLICATE_BIRTHDAY, e.code)
            assertEquals("b9", e.problem?.detail)
        }
    }

    @Test
    fun decisionsPhotoGreetingsAndPreferences() = runBlocking {
        server.enqueue(json(oma)); server.enqueue(json(oma))
        api.approveFeedBirthday("fd1", "b1"); api.rejectFeedBirthday("fd1", "b1")
        assertEquals("/v1/feeds/fd1/birthdays/b1/approve", server.takeRequest().url.encodedPath)
        assertEquals("/v1/feeds/fd1/birthdays/b1/reject", server.takeRequest().url.encodedPath)

        server.enqueue(json(oma))
        api.putFeedBirthdayPhoto("fd1", "b1", byteArrayOf(1, 2), "image/jpeg")
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("image/jpeg", put.headers["Content-Type"])
        server.enqueue(json(oma.replace("\"has_own_photo\":true", "\"has_own_photo\":false")))
        assertFalse(api.deleteFeedBirthdayPhoto("fd1", "b1").hasOwnPhoto)
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(MockResponse.Builder().code(204).build())
        api.removeFeedBirthday("fd1", "b1")
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(json("""{"greetings":[{"id":"g1","feed_id":"fd1","birthday_id":"b1","birthday_name":"Oma","frame_id":"f1",
            "frame_name":"Opa's frame","frame_location":"Kitchen","date":"2027-03-12","created_at":"2027-03-12T09:14:00Z"}],"next_cursor":null}"""))
        val page = api.feedBirthdayGreetings("fd1", date = "2027-03-12")
        assertEquals("Opa's frame", page.greetings.single().frameName)
        assertNull(page.nextCursor)
        assertEquals("2027-03-12", server.takeRequest().url.queryParameter("date"))

        server.enqueue(json("""{"id":"fd1","name":"Peeters kids","role":"poster","birthday_notifications":false,"posters_can_add_birthdays":true,"birthday_count":4}"""))
        val feed = api.updateFeedPreferences("fd1", FeedPreferencesUpdate(birthdayNotifications = false))
        assertFalse(feed.birthdayNotifications)
        assertTrue("posters with the setting add directly", feed.canAddBirthdays)
        assertEquals(4, feed.birthdayCount)
        val pref = server.takeRequest()
        assertEquals("/v1/feeds/fd1/preferences", pref.url.encodedPath)
        assertEquals("""{"birthday_notifications":false}""", pref.body!!.utf8())
    }

    @Test
    fun updateMeSendsTheTimeZone() = runBlocking {
        server.enqueue(json("""{"id":"u1","display_name":"Anna","timezone":"Europe/Brussels"}"""))
        assertEquals("Europe/Brussels", api.updateMe(timezone = "Europe/Brussels").timezone)
        assertEquals("""{"timezone":"Europe/Brussels"}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun subscriptionBirthdayFieldsAndPatch() = runBlocking {
        val s = KinJson.decodeFromString(Subscription.serializer(), """{"id":"s1","status":"active","history":"none",
            "feed":{"id":"fd1","name":"P"},"frame":{"id":"f1","name":"Oma","birthdays_supported":false},"birthdays":false,"birthday_count":12}""")
        assertFalse(s.birthdays)
        assertEquals(12, s.birthdayCount)
        assertEquals(false, s.frame.birthdaysSupported)
        val old = KinJson.decodeFromString(Subscription.serializer(), """{"id":"s1","feed":{"id":"fd1","name":"P"},"frame":{"id":"f1","name":"Oma"}}""")
        assertTrue("default on", old.birthdays)
        assertNull("unknown on older servers", old.frame.birthdaysSupported)
        assertEquals("""{"birthdays":false}""", KinJson.encodeToString(SubscriptionUpdate.serializer(), SubscriptionUpdate(birthdays = false)))
    }

    @Test
    fun birthdaysChangeAndDiscovery() {
        val page = KinJson.decodeFromString(ChangesPage.serializer(), """{"changes":[
            {"seq":5,"type":"birthdays","birthdays":{"generated_at":"2026-11-19T10:00:00Z","people":[
              {"id":"b1","name":"Oma","day":29,"month":2,"birth_year":1948,"photo_url":"https://k/a.webp","feed_ids":["fd1","fd2"],"birthday_ids":["b1","b7"]},
              {"id":"b2","name":"Lotte","day":2,"month":6,"feed_ids":["fd1"],"birthday_ids":["b2"]}]}},
            {"seq":6,"type":"birthdays","birthdays":null}],"next_seq":7}""")
        val c: Change = page.changes[0]
        assertEquals(ChangeType.BIRTHDAYS, c.type)
        val b = FrameBirthdays.of(c.birthdays)!!
        assertEquals(listOf("Oma", "Lotte"), b.people.map { it.name })
        assertEquals(listOf("b1", "b7"), b.people[0].birthdayIds)
        assertNull(b.people[1].birthYear)
        assertNull(page.changes[1].birthdays)
        assertEquals(b, FrameBirthdays.parse(KinJson.encodeToString(FrameBirthdays.serializer(), b)))
        assertNull(FrameBirthdays.parse("not json"))

        val d = KinJson.decodeFromString(Discovery.serializer(), """{"name":"k","feeds":true,"birthdays":true,"limits":{"max_birthdays_per_feed":50}}""")
        assertTrue(d.birthdays)
        assertEquals(50, d.limits!!.maxBirthdaysPerFeed)
        assertTrue(FrameFeatures.BIRTHDAYS in FrameFeatures.SUPPORTED)
        assertNull(KinJson.decodeFromString(User.serializer(), """{"id":"u","display_name":"A"}""").timezone)
    }

    @Test
    fun dateRules() {
        assertEquals(LocalDate.of(2027, 2, 28), BirthdayDates.occurrence(2, 29, 2027))
        assertEquals(LocalDate.of(2028, 2, 29), BirthdayDates.occurrence(2, 29, 2028))
        assertEquals(LocalDate.of(2027, 3, 12), BirthdayDates.next(3, 12, LocalDate.of(2026, 11, 19)))
        assertEquals(LocalDate.of(2026, 11, 19), BirthdayDates.next(11, 19, LocalDate.of(2026, 11, 19)))
        assertTrue(BirthdayDates.isValid(29, 2))
        assertTrue(BirthdayDates.isValid(29, 2, 2000))
        assertFalse(BirthdayDates.isValid(29, 2, 1900))
        assertFalse(BirthdayDates.isValid(30, 2))
        assertFalse(BirthdayDates.isValid(31, 4))
        assertTrue(BirthdayDates.isValid(31, 12))
        assertFalse(BirthdayDates.isValidYear(1899, LocalDate.of(2026, 1, 1)))
        assertFalse(BirthdayDates.isValidYear(2027, LocalDate.of(2026, 1, 1)))
    }
}
