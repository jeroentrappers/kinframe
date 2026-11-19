package app.kinframe.frame

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import app.kinframe.frame.FakeServer.Companion.page
import app.kinframe.frame.FakeServer.Companion.placement
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.data.PlacementEntity
import app.kinframe.frame.slideshow.Credit
import app.kinframe.frame.slideshow.SlideshowSource
import app.kinframe.frame.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Feed placements on the frame (docs/spec/feeds.md §3 "Frame", §6.7): credit, quiet backfill, feed mode, renames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FeedPlacementTest : SyncTestBase() {
    private val engine by lazy { SyncEngine(api, db, media, { now }) }
    private fun up(seq: Int, p: String) = """{"seq":$seq,"type":"placement.upsert","placement":$p}"""
    private fun feedExtra(feedName: String, backfill: Boolean, feeds: String = "\"fd1\"") =
        ""","origin":"feed","origin_feed_id":"fd1","origin_feed_name":"$feedName","feed_ids":[$feeds],"backfill":$backfill"""

    /** Without bidi controls, to compare the words. */
    private fun plain(s: String?) = s?.filterNot { it in "‎‏‪‫‬‭‮⁦⁧⁨⁩" }

    private fun ctx(lang: String): Context {
        val base: Context = RuntimeEnvironment.getApplication()
        val locale = Locales.toLocale(lang)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) })
    }

    @Test
    fun newFeedPostIsNewButBackfillIsQuiet() = runBlocking {
        fake.pages[0] = page(1, false, up(1, placement("p0", byteArrayOf(0))))
        engine.pull() // the snapshot: nothing is new
        fake.pages[1] = page(3, false,
            up(2, placement("p1", byteArrayOf(1), extra = feedExtra("Peeters kids", backfill = false))),
            up(3, placement("p2", byteArrayOf(2), received = "2025-06-01T10:00:00Z", extra = feedExtra("Peeters kids", backfill = true))),
        )
        val r = engine.pull()
        assertEquals("only the live post wakes the screen", 1, r.newItems)
        assertTrue(db.placements().get("p1")!!.isNew)
        val history = db.placements().get("p2")!!
        assertFalse("backfilled history is never new", history.isNew)
        assertTrue(history.backfill)
        assertEquals("received_at is the post time, so history interleaves", SyncEngine.parseTime("2025-06-01T10:00:00Z"), history.receivedAt)
    }

    @Test
    fun feedFieldsAreStoredAndRenamesUpdateTheCredit() = runBlocking {
        fake.pages[0] = page(1, false, up(1, placement("p1", byteArrayOf(1), extra = feedExtra("Peeters kids", false, "\"fd1\",\"fd2\""))))
        engine.pull()
        val p = db.placements().get("p1")!!
        assertEquals("feed", p.origin)
        assertEquals("fd1", p.originFeedId)
        assertEquals(listOf("fd1", "fd2"), p.feedIdList)
        assertEquals("Ann · Peeters kids", plain(Credit.of(ctx("en"), p)))
        // The server re-emits the placement after a rename.
        fake.pages[1] = page(2, false, up(2, placement("p1", byteArrayOf(1), extra = feedExtra("Kids", false))))
        engine.pull()
        val renamed = db.placements().get("p1")!!
        assertEquals("Kids", renamed.originFeedName)
        assertEquals("Ann · Kids", plain(Credit.of(ctx("en"), renamed)))
        assertEquals("the downloaded file survives the rename", p.localFile, renamed.localFile)
    }

    @Test
    fun creditRules() {
        val base = entity("p", senderName = "Anna")
        assertEquals("Anna · Peeters kids", plain(Credit.of(ctx("en"), base.copy(origin = "feed", originFeedName = "Peeters kids"))))
        // A direct send also held by a feed keeps the plain credit, and "shared by" is unchanged.
        assertEquals("Anna", plain(Credit.of(ctx("en"), base.copy(origin = "send", originFeedName = "Peeters kids", feedIds = "fd1"))))
        assertEquals("Anna · shared by Ben", plain(Credit.of(ctx("en"), base.copy(origin = "share", sharedByName = "Ben"))))
        // No sender name: the feed alone.
        assertEquals("Peeters kids", plain(Credit.of(ctx("en"), base.copy(senderName = null, origin = "feed", originFeedName = "Peeters kids"))))
        // Right to left: names are isolated, the separator stays between them.
        val ar = Credit.of(ctx("ar"), base.copy(origin = "feed", originFeedName = "Peeters kids"))!!
        assertTrue(ar.length > plain(ar)!!.length)
    }

    @Test
    fun feedModePlaysOneFeedAndFallsBackToEverything() {
        val a = entity("a", feedIds = "fd1", albumIds = "al1")
        val b = entity("b", feedIds = "fd1,fd2")
        val c = entity("c")
        val all = listOf(a, b, c)
        assertEquals(listOf("a", "b"), SlideshowSource.select(all, null, "fd1").map { it.id })
        assertEquals(listOf("b"), SlideshowSource.select(all, null, "fd2").map { it.id })
        assertEquals("unknown feed plays everything", all, SlideshowSource.select(all, null, "nope"))
        assertEquals(listOf("a"), SlideshowSource.select(all, "al1", null).map { it.id })
        assertEquals(all, SlideshowSource.select(all, null, null))
        assertEquals("feed mode wins if both arrive", listOf("a", "b"), SlideshowSource.select(all, "al1", "fd1").map { it.id })
    }

    @Test
    fun pausedFeedPlacementsComeBackRestoredNotNew() = runBlocking {
        fake.pages[0] = page(1, false, up(1, placement("p0", byteArrayOf(0))))
        engine.pull() // snapshot
        // A live feed post arrives (new), and the subscription is paused before it was ever shown.
        fake.pages[1] = page(2, false, up(2, placement("p1", byteArrayOf(1), extra = feedExtra("Peeters kids", false))))
        assertEquals(1, engine.pull().newItems)
        // Pause renders as placement.remove; resume as an upsert of the same placement.
        fake.pages[2] = page(3, false, """{"seq":3,"type":"placement.remove","id":"p1"}""")
        engine.pull()
        assertNull(db.placements().get("p1"))
        fake.pages[3] = page(5, false,
            up(4, placement("p1", byteArrayOf(1), extra = feedExtra("Peeters kids", false))),
            // Posted during the pause: the server places it as quiet history.
            up(5, placement("p2", byteArrayOf(2), extra = feedExtra("Peeters kids", true))),
        )
        val r = engine.pull()
        assertEquals("a restored photo doesn't wake the screen", 0, r.newItems)
        assertFalse(db.placements().get("p1")!!.isNew)
        assertFalse(db.placements().get("p2")!!.isNew)
        // The tombstone is used up: a later remove + upsert of a direct photo is new as before.
        fake.pages[5] = page(7, false, """{"seq":6,"type":"placement.remove","id":"p0"}""", up(7, placement("p0", byteArrayOf(0))))
        assertEquals("direct photos have no tombstone", 1, engine.pull().newItems)
    }

    @Test
    fun heartbeatAnnouncesFeedBackfill() = runBlocking {
        assertTrue(app.kinframe.core.api.FrameFeatures.FEED_BACKFILL in app.kinframe.core.api.FrameFeatures.SUPPORTED)
        api.heartbeat(app.kinframe.core.api.Heartbeat(appVersion = "t", uptimeS = 1, features = app.kinframe.core.api.FrameFeatures.SUPPORTED))
        val body = fake.requests.last { it.url.encodedPath.endsWith("/device/heartbeat") }.body!!.utf8()
        assertTrue(body, body.contains(""""features":["feed_backfill","birthdays"]"""))
    }

    /** v7 → v8 keeps every placement and adds the feed columns with their defaults. */
    @Test
    fun migrationFrom7KeepsPlacements() {
        val app: Application = RuntimeEnvironment.getApplication()
        val name = "migrate-test.db"
        app.deleteDatabase(name)
        val schema = Json.parseToJsonElement(File("schemas/app.kinframe.frame.data.FrameDatabase/7.json").readText()).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name).apply { parentFile!!.mkdirs() }, null).use { raw ->
            for (e in schema["entities"]!!.jsonArray) {
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                raw.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                o["indices"]?.jsonArray?.forEach { i -> raw.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]?.jsonArray?.forEach { raw.execSQL(it.jsonPrimitive.content) }
            raw.execSQL("""INSERT INTO placements (id, itemId, kind, status, senderId, senderName, caption, captionEdited, capturedAt, receivedAt,
                focalX, focalY, fit, rotation, hidden, trashed, pendingModeration, reaction, width, height, durationMs, hasAudio, mediaUrl,
                mediaSha256, mediaBytes, mediaMime, downloadAttempts, nextAttemptAt, isNew) VALUES ('old', 'i', 'photo', 'ready', 'u', 'Ann', NULL, 0,
                NULL, 5, NULL, NULL, NULL, 0, 0, 0, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, 0, 0, 0)""")
            raw.version = 7
        }
        val db = FrameDatabase.open(app, name)
        try {
            val p = runBlocking { db.placements().get("old") }!!
            assertEquals("Ann", p.senderName)
            assertEquals("send", p.origin)
            assertFalse(p.backfill)
            assertNull(p.originFeedName)
            assertTrue(p.feedIdList.isEmpty())
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    private fun entity(id: String, senderName: String? = "Ann", feedIds: String? = null, albumIds: String? = null) = PlacementEntity(
        id = id, itemId = "i-$id", kind = "photo", status = "ready", senderId = "u1", senderName = senderName, caption = null,
        captionEdited = false, capturedAt = null, receivedAt = 1, focalX = null, focalY = null, fit = null, rotation = 0, hidden = false,
        trashed = false, pendingModeration = false, reaction = null, width = null, height = null, durationMs = null, hasAudio = null,
        mediaUrl = null, mediaSha256 = null, mediaBytes = 0, mediaMime = null, feedIds = feedIds, albumIds = albumIds,
    )
}
