package app.kinframe.frame

import android.app.Application
import app.kinframe.frame.FakeServer.Companion.page
import app.kinframe.frame.FakeServer.Companion.placement
import app.kinframe.frame.data.AckEntity
import app.kinframe.frame.sync.AckQueue
import app.kinframe.frame.sync.Downloader
import app.kinframe.frame.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloaderTest : SyncTestBase() {
    private val acks by lazy { AckQueue(db, { now }) }
    private val downloader by lazy { Downloader(api, db, media, acks, { now }, freeBytes = { Long.MAX_VALUE }) }
    private val engine by lazy { SyncEngine(api, db, media, { now }) }
    private val photo = Random(3).nextBytes(300_000)

    private suspend fun feed(vararg placements: String) {
        fake.pages[0] = page(placements.size.toLong(), false, *placements.mapIndexed { i, p -> """{"seq":${i + 1},"type":"placement.upsert","placement":$p}""" }.toTypedArray())
        engine.pull()
    }

    @Test
    fun downloadsVerifiesStoresAndQueuesDeliveredAck() = runBlocking {
        fake.media["p1"] = photo
        feed(placement("p1", photo))
        assertTrue(downloader.downloadOne(db.placements().get("p1")!!))
        val p = db.placements().get("p1")!!
        assertArrayEquals(photo, media.file(p.localFile!!).readBytes())
        assertTrue(media.partialDir.listFiles()!!.isEmpty())
        assertEquals(listOf("p1" to AckEntity.DELIVERED), db.acks().batch(10).map { it.placementId to it.kind })
        assertEquals("Bearer tok", fake.requests.last { it.url.encodedPath.contains("media") }.headers["Authorization"])
    }

    @Test
    fun resumesAPartialDownloadWithRange() = runBlocking {
        fake.media["p1"] = photo
        feed(placement("p1", photo))
        val sha = FakeServer.sha(photo)
        media.partial(sha).writeBytes(photo.copyOfRange(0, 120_000))
        assertTrue(downloader.downloadOne(db.placements().get("p1")!!))
        val req = fake.requests.last { it.url.encodedPath.contains("media") }
        assertEquals("bytes=120000-", req.headers["Range"])
        assertArrayEquals(photo, media.file(db.placements().get("p1")!!.localFile!!).readBytes())
    }

    @Test
    fun checksumMismatchIsRejectedAndBacksOff() = runBlocking {
        fake.media["p1"] = photo
        feed(placement("p1", photo, sha = "00".repeat(32)))
        assertFalse(downloader.downloadOne(db.placements().get("p1")!!))
        val p = db.placements().get("p1")!!
        assertNull(p.localFile)
        assertEquals(1, p.downloadAttempts)
        assertTrue(p.nextAttemptAt >= now + 24_000)
        assertEquals("sha256 mismatch", p.lastError)
        assertTrue(media.partialDir.listFiles()!!.isEmpty())
        assertTrue(db.placements().pendingDownloads(now, 10).isEmpty())
        assertEquals(1, db.placements().pendingDownloads(p.nextAttemptAt, 10).size)
        assertEquals(0, db.acks().count())
    }

    @Test
    fun identicalContentIsDownloadedOnceAndLinkedToEveryPlacement() = runBlocking {
        fake.media["p1"] = photo
        fake.media["p2"] = photo
        feed(placement("p1", photo), placement("p2", photo))
        assertTrue(downloader.downloadOne(db.placements().get("p1")!!))
        assertTrue(downloader.downloadOne(db.placements().get("p2")!!))
        assertEquals(1, fake.requests.count { it.url.encodedPath.contains("media") })
        assertEquals(db.placements().get("p1")!!.localFile, db.placements().get("p2")!!.localFile)
        assertEquals(setOf("p1", "p2"), db.acks().batch(10).map { it.placementId }.toSet())
    }

    @Test
    fun acksAreBatchedAndRemovedAfterTheServerAcceptedThem() = runBlocking {
        fake.media["p1"] = photo
        feed(placement("p1", photo))
        downloader.downloadOne(db.placements().get("p1")!!)
        acks.enqueueSeen("p1")
        acks.flush(api)
        val body = fake.requests.last { it.url.encodedPath.endsWith("acks") }.body!!.utf8()
        assertTrue(body, body.contains(""""delivered":["p1"]"""))
        assertTrue(body, body.contains(""""seen":[{"placement_id":"p1","at":""""))
        assertEquals(0, db.acks().count())
    }
}
