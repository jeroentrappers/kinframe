package app.kinframe.frame

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.kinframe.core.api.LivePhotos
import app.kinframe.frame.FakeServer.Companion.page
import app.kinframe.frame.FakeServer.Companion.placement
import app.kinframe.frame.FakeServer.Companion.sha
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.slideshow.LivePolicy
import app.kinframe.frame.sync.AckQueue
import app.kinframe.frame.sync.Downloader
import app.kinframe.frame.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
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
import kotlin.random.Random

/** Live/Motion Photos on the frame: feed mapping, clip download, the v3 → v4 migration and the playback rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LivePhotoTest : SyncTestBase() {
    private val engine by lazy { SyncEngine(api, db, media, { now }) }
    private val downloader by lazy { Downloader(api, db, media, AckQueue(db, { now }), { now }, freeBytes = { Long.MAX_VALUE }) }
    private val still = Random(5).nextBytes(200_000)
    private val clip = Random(6).nextBytes(90_000)

    private fun live(id: String, clipSha: String = sha(clip)) = placement(id, still).replace("\"kind\":\"photo\"", "\"kind\":\"live\"")
        .replace("\"received_at\"", """"motion":{"url":"/v1/device/media/$id?variant=motion","sha256":"$clipSha","bytes":${clip.size},"mime":"video/mp4","width":1440,"height":1080,"duration_ms":2900,"has_audio":false},"received_at"""")

    private suspend fun feed(vararg placements: String) {
        fake.pages[0] = page(placements.size.toLong(), false, *placements.mapIndexed { i, p -> """{"seq":${i + 1},"type":"placement.upsert","placement":$p}""" }.toTypedArray())
        engine.pull()
    }

    @Test
    fun theFeedCarriesTheClipAndTheStillDownloadsFirst() = runBlocking {
        fake.media["p1"] = still
        fake.media["p1?motion"] = clip
        feed(live("p1"))
        val p = db.placements().get("p1")!!
        assertTrue(p.isLive)
        assertFalse(p.isVideo) // older code paths treat it as a photo
        assertEquals(sha(clip), p.motionSha256)
        assertEquals(2900L, p.motionDurationMs)
        assertTrue(downloader.downloadOne(p))
        val stored = db.placements().get("p1")!!
        assertArrayEquals(still, media.file(stored.localFile!!).readBytes())
        assertArrayEquals(clip, media.file(stored.motionLocalFile!!).readBytes())
        assertTrue(stored.hasMotionFile)
        assertTrue(stored.motionLocalFile!!.endsWith(".mp4"))
    }

    @Test
    fun aBrokenClipNeverBlocksTheStill() = runBlocking {
        fake.media["p1"] = still
        fake.media["p1?motion"] = clip
        feed(live("p1", clipSha = "00".repeat(32)))
        assertTrue(downloader.downloadOne(db.placements().get("p1")!!))
        val p = db.placements().get("p1")!!
        assertTrue(p.localFile != null && p.isVisible)
        assertNull(p.motionLocalFile)
        assertEquals(1, p.motionAttempts)
        assertTrue(db.placements().pendingMotion(now, 10).isEmpty())
        assertEquals(listOf("p1"), db.placements().pendingMotion(p.motionNextAttemptAt, 10).map { it.id })
        // The clip is fixed on the server: the retry stores it on its own.
        fake.pages[1] = page(2, false, """{"seq":2,"type":"placement.upsert","placement":${live("p1")}}""")
        engine.pull()
        assertTrue(db.placements().get("p1")!!.localFile != null) // the still stays
        assertTrue(downloader.downloadMotion(db.placements().get("p1")!!))
        assertTrue(db.placements().get("p1")!!.hasMotionFile)
    }

    @Test
    fun garbageCollectionKeepsClips() = runBlocking {
        fake.media["p1"] = still
        fake.media["p1?motion"] = clip
        feed(live("p1"))
        downloader.downloadOne(db.placements().get("p1")!!)
        engine.garbageCollect()
        val p = db.placements().get("p1")!!
        assertTrue(media.file(p.motionLocalFile!!).exists())
        assertTrue(p.motionLocalFile in db.placements().allLocalFiles())
    }

    @Test
    fun migratesVersion3() {
        val app: Application = RuntimeEnvironment.getApplication()
        val name = "migrate-test.db"
        app.deleteDatabase(name)
        // Build the v3 database from its exported schema, with one placement.
        val schema = JSONObject(File("schemas/app.kinframe.frame.data.FrameDatabase/3.json").readText()).getJSONObject("database")
        val path = app.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { raw ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val idx = e.optJSONArray("indices") ?: continue
                for (j in 0 until idx.length()) raw.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            raw.insert("placements", null, ContentValues().apply {
                put("id", "p1"); put("itemId", "i1"); put("kind", "photo"); put("status", "ready"); put("captionEdited", 0)
                put("receivedAt", 1L); put("rotation", 0); put("hidden", 0); put("trashed", 0); put("pendingModeration", 0)
                put("mediaBytes", 10L); put("downloadAttempts", 0); put("nextAttemptAt", 0L); put("isNew", 0); put("localFile", "a.webp")
            })
            raw.version = 3
        }
        val migrated = FrameDatabase.open(app, name)
        try {
            val p = runBlocking { migrated.placements().get("p1") }!!
            assertEquals("a.webp", p.localFile) // not a destructive re-sync
            assertNull(p.motionUrl)
            assertEquals(0, p.motionAttempts)
        } finally {
            migrated.close()
            app.deleteDatabase(name)
        }
    }

    @Test
    fun playbackRules() {
        // on: plays by itself when alone on screen and the clip is here.
        assertTrue(LivePolicy.autoPlay(LivePhotos.ON, hasClip = true, alone = true))
        assertFalse(LivePolicy.autoPlay(LivePhotos.ON, hasClip = false, alone = true))
        assertFalse(LivePolicy.autoPlay(LivePhotos.ON, hasClip = true, alone = false)) // in a collage
        assertFalse(LivePolicy.autoPlay(LivePhotos.TAP, hasClip = true, alone = true))
        assertTrue(LivePolicy.autoPlay("something-new", hasClip = true, alone = true)) // unknown → default on
        // Taps replay unless it is off or already playing.
        assertTrue(LivePolicy.playOnTap(LivePhotos.TAP, hasClip = true, alone = true, playing = false))
        assertTrue(LivePolicy.playOnTap(LivePhotos.ON, hasClip = true, alone = true, playing = false))
        assertFalse(LivePolicy.playOnTap(LivePhotos.ON, hasClip = true, alone = true, playing = true))
        assertFalse(LivePolicy.playOnTap(LivePhotos.OFF, hasClip = true, alone = true, playing = false))
        // The mark: hidden while playing and when off; "tap to play" only in tap mode.
        assertTrue(LivePolicy.showMark(LivePhotos.ON, hasClip = true, alone = true, playing = false))
        assertFalse(LivePolicy.showMark(LivePhotos.ON, hasClip = true, alone = true, playing = true))
        assertFalse(LivePolicy.showMark(LivePhotos.OFF, hasClip = true, alone = true, playing = false))
        assertTrue(LivePolicy.tapHint(LivePhotos.TAP))
        assertFalse(LivePolicy.tapHint(LivePhotos.ON))
        // Pre-buffer the next clip only when it will play by itself and no video holds the decoder.
        assertTrue(LivePolicy.prebuffer(LivePhotos.ON, hasClip = true, currentIsVideo = false))
        assertFalse(LivePolicy.prebuffer(LivePhotos.ON, hasClip = true, currentIsVideo = true))
        assertFalse(LivePolicy.prebuffer(LivePhotos.TAP, hasClip = true, currentIsVideo = false))
        // The interval starts after the clip.
        assertFalse(LivePolicy.due(playing = true, elapsedMs = 60_000, intervalMs = 30_000))
        assertTrue(LivePolicy.due(playing = false, elapsedMs = 30_000, intervalMs = 30_000))
    }
}
