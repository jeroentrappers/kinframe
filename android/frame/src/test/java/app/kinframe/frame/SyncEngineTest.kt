package app.kinframe.frame

import android.app.Application
import app.kinframe.core.api.ApiException
import app.kinframe.frame.FakeServer.Companion.page
import app.kinframe.frame.FakeServer.Companion.placement
import app.kinframe.frame.data.OutboxEntity
import app.kinframe.frame.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncEngineTest : SyncTestBase() {
    private val engine by lazy { SyncEngine(api, db, media, { now }) }
    private fun up(seq: Int, p: String) = """{"seq":$seq,"type":"placement.upsert","placement":$p}"""

    @Test
    fun snapshotThenIncrementalMarksOnlyLaterItemsNew() = runBlocking {
        fake.pages[0] = page(
            3, false,
            """{"seq":1,"type":"settings","settings":{"version":4,"slideshow":{"interval_s":15}}}""",
            """{"seq":2,"type":"frame","frame":{"name":"Oma","owner_name":"Jeroen"}}""",
            up(3, placement("p1", byteArrayOf(1))),
        )
        fake.pages[3] = page(4, false, up(4, placement("p2", byteArrayOf(2), received = "2026-10-21T10:00:00Z")))
        val r1 = engine.pull()
        assertEquals(3, r1.applied)
        assertEquals(3L, db.syncState().get()!!.nextSeq)
        val r2 = engine.pull() // e.g. after a websocket "changes" hint
        assertEquals(1, r2.newItems)
        val st = db.syncState().get()!!
        assertEquals(4L, st.nextSeq)
        assertEquals("Oma", st.frameName)
        assertTrue(st.settingsJson!!.contains("\"interval_s\":15"))
        assertFalse("snapshot items are not new", db.placements().get("p1")!!.isNew)
        assertTrue("items after the snapshot are new", db.placements().get("p2")!!.isNew)
        assertEquals(listOf(0L, 3L), fake.requests.filter { it.url.encodedPath.endsWith("changes") }.map { it.url.queryParameter("since")!!.toLong() })
    }

    @Test
    fun sharedPlacementKeepsWhoSharedIt() = runBlocking {
        fake.pages[0] = page(2, false,
            up(1, placement("p1", byteArrayOf(1), extra = ""","shared_by_user_id":"u2","shared_by_name":"Ben"""")),
            up(2, placement("p2", byteArrayOf(2))))
        engine.pull()
        assertEquals("Ann", db.placements().get("p1")!!.senderName)
        assertEquals("Ben", db.placements().get("p1")!!.sharedByName)
        assertNull(db.placements().get("p2")!!.sharedByName)
    }

    @Test
    fun weatherIsPersistedAndClearedByNull() = runBlocking {
        val weather = """{"fetched_at":"2026-10-22T12:05:00Z","location":{"name":"Gent"},"current":{"temp_c":14.6,"code":2,"is_day":true},"daily":[]}"""
        fake.pages[0] = page(1, false, """{"seq":1,"type":"weather","weather":$weather}""")
        engine.pull()
        val stored = app.kinframe.frame.weather.Weather.parse(db.syncState().get()!!.weatherJson)
        assertEquals("Gent", stored!!.location!!.name)
        assertEquals(14.6, stored.current.tempC, 1e-9)
        // Widget turned off or location removed on the server: `weather: null` drops it.
        fake.pages[1] = page(2, false, """{"seq":2,"type":"weather","weather":null}""")
        engine.pull()
        assertNull(db.syncState().get()!!.weatherJson)
        assertEquals(2L, db.syncState().get()!!.nextSeq)
    }

    @Test
    fun calendarIsPersistedAndClearedByNull() = runBlocking {
        val cal = """{"generated_at":"2026-10-22T12:00:00Z","timezone":"Europe/Brussels","window":{"from":"2026-09-22","to":"2026-11-21"},
            "sources":[{"id":"s1","kind":"ics","name":"Family","color":"coral","status":"ok"}],
            "events":[{"id":"ev-00000001","source_id":"s1","title":"Swimming","all_day":false,"start_at":"2026-10-22T14:30:00Z","end_at":"2026-10-22T15:30:00Z"}],
            "countdowns":[]}"""
        fake.pages[0] = page(1, false, """{"seq":1,"type":"calendar","calendar":$cal}""")
        engine.pull()
        val stored = app.kinframe.core.api.FrameCalendar.parse(db.syncState().get()!!.calendarJson)
        assertEquals("Swimming", stored!!.events.single().title)
        assertEquals("coral", stored.sources.single().color)
        // The last calendar was removed on the server: `calendar: null` drops it.
        fake.pages[1] = page(2, false, """{"seq":2,"type":"calendar","calendar":null}""")
        engine.pull()
        assertNull(db.syncState().get()!!.calendarJson)
    }

    @Test
    fun pagesAreFollowedUntilHasMoreIsFalse() = runBlocking {
        fake.pages[0] = page(1, true, up(1, placement("a", byteArrayOf(1))))
        fake.pages[1] = page(2, true, up(2, placement("b", byteArrayOf(2))))
        fake.pages[2] = page(3, false, up(3, placement("c", byteArrayOf(3))))
        engine.pull()
        assertEquals(setOf("a", "b", "c"), db.placements().all().map { it.id }.toSet())
        assertEquals(3L, db.syncState().get()!!.nextSeq)
    }

    @Test
    fun failedPageKeepsCursorAtLastCommittedPage() = runBlocking {
        fake.pages[0] = page(1, true, up(1, placement("a", byteArrayOf(1))))
        fake.failSince[1] = 500
        try {
            engine.pull(); fail()
        } catch (e: ApiException) {
            assertEquals(500, e.status)
        }
        assertEquals(1L, db.syncState().get()!!.nextSeq)
        assertTrue(db.placements().get("a") != null)
    }

    @Test
    fun removeDeletesRowAndOrphanedFile() = runBlocking {
        val bytes = byteArrayOf(9, 9, 9)
        fake.pages[0] = page(1, false, up(1, placement("a", bytes)))
        fake.pages[1] = page(2, false, """{"seq":2,"type":"placement.remove","id":"a"}""")
        engine.pull()
        val name = media.fileName(FakeServer.sha(bytes), "image/webp")
        media.file(name).writeBytes(bytes)
        db.placements().markStored("a", name, now)
        engine.pull()
        assertNull(db.placements().get("a"))
        assertFalse(media.file(name).exists())
    }

    @Test
    fun resetDiscardsStateAndResyncsFromZero() = runBlocking {
        fake.pages[0] = page(2, false, up(1, placement("old", byteArrayOf(1))), """{"seq":2,"type":"member.upsert","member":{"user_id":"u1","display_name":"Ann"}}""")
        engine.pull()
        // The server now says: reset. A fresh snapshot follows from since=0.
        fake.pages[2] = page(3, false, """{"seq":3,"type":"reset"}""")
        fake.pages[0] = page(10, false, up(10, placement("fresh", byteArrayOf(2))))
        val r = engine.pull()
        assertEquals(1, r.resets)
        assertEquals(listOf("fresh"), db.placements().all().map { it.id })
        assertEquals(10L, db.syncState().get()!!.nextSeq)
    }

    @Test
    fun pendingLocalActionsWinOverServerEcho() = runBlocking {
        fake.pages[0] = page(1, false, up(1, placement("a", byteArrayOf(1))))
        engine.pull()
        db.outbox().insert(OutboxEntity(kind = OutboxEntity.PLACEMENT_PATCH, placementId = "a", payload = """{"trashed":true}""", notBefore = now + 5000, createdAt = now))
        db.outbox().insert(OutboxEntity(kind = OutboxEntity.REACTION, placementId = "a", payload = """{"reaction":"love"}""", notBefore = now, createdAt = now))
        fake.pages[1] = page(2, false, up(2, placement("a", byteArrayOf(1))))
        engine.pull()
        val a = db.placements().get("a")!!
        assertTrue(a.trashed)
        assertEquals("love", a.reaction)
    }

    @Test
    fun localDownloadStateSurvivesUpsertWithSameMedia() = runBlocking {
        val bytes = byteArrayOf(4, 5)
        fake.pages[0] = page(1, false, up(1, placement("a", bytes)))
        engine.pull()
        db.placements().markStored("a", "x.webp", now)
        db.placements().markShown("a", now)
        fake.pages[1] = page(2, false, up(2, placement("a", bytes, extra = ""","hidden":true""".replace(",\"hidden\":true", "")).replace("\"caption\":\"hi a\"", "\"caption\":\"edited\"")))
        engine.pull()
        val a = db.placements().get("a")!!
        assertEquals("x.webp", a.localFile)
        assertEquals("edited", a.caption)
        assertEquals(now, a.firstShownAt)
        // New media (different sha) invalidates the local file.
        fake.pages[2] = page(3, false, up(3, placement("a", byteArrayOf(7, 7, 7))))
        engine.pull()
        assertNull(db.placements().get("a")!!.localFile)
    }
}
