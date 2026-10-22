package app.kinframe.frame.storage

import android.app.Application
import app.kinframe.frame.FakeServer
import app.kinframe.frame.FakeServer.Companion.page
import app.kinframe.frame.FakeServer.Companion.placement
import app.kinframe.frame.SyncTestBase
import app.kinframe.frame.data.AckEntity
import app.kinframe.frame.data.MediaCache
import app.kinframe.frame.data.PlacementEntity
import app.kinframe.frame.sync.AckQueue
import app.kinframe.frame.sync.Downloader
import app.kinframe.frame.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

class EvictionPolicyTest {
    private fun p(id: String, file: String?, bytes: Long, shown: Long?, stored: Long? = 0) = PlacementEntity(
        id = id, itemId = id, kind = "photo", status = "ready", senderId = null, senderName = null, caption = null,
        captionEdited = false, capturedAt = null, receivedAt = 0, focalX = null, focalY = null, fit = null, rotation = 0,
        hidden = false, trashed = false, pendingModeration = false, reaction = null, width = null, height = null,
        durationMs = null, hasAudio = null, mediaUrl = "u", mediaSha256 = file, mediaBytes = bytes, mediaMime = null,
        localFile = file, storedAt = stored, lastShownAt = shown,
    )

    @Test fun leastRecentlyShownFirstUntilEnoughIsFree() {
        val c = EvictionPolicy.candidates(
            listOf(p("a", "fa", 100, shown = 50), p("b", "fb", 100, shown = 10), p("c", "fc", 100, shown = 30), p("d", "fd", 100, shown = 90)),
        ) { 0 }
        assertEquals(listOf("fb", "fc"), EvictionPolicy.select(c, emptySet(), 150).map { it.file })
        assertEquals(emptyList<String>(), EvictionPolicy.select(c, emptySet(), 0).map { it.file })
        assertEquals(4, EvictionPolicy.select(c, emptySet(), 10_000).size)
    }

    @Test fun neverShownCountsFromWhenStored() {
        val c = EvictionPolicy.candidates(listOf(p("new", "fn", 100, shown = null, stored = 5), p("old", "fo", 100, shown = 20))) { 0 }
        assertEquals("fn", EvictionPolicy.select(c, emptySet(), 1).single().file)
    }

    @Test fun protectedItemsAndSharedFilesAreKept() {
        // "x" and "y" share one content-addressed file; "y" is on screen, so the file stays.
        val c = EvictionPolicy.candidates(listOf(p("x", "shared", 100, shown = 1), p("y", "shared", 100, shown = 2), p("z", "fz", 100, shown = 3))) { 0 }
        assertEquals(2, c.size)
        assertEquals(listOf("fz"), EvictionPolicy.select(c, setOf("y"), 1_000).map { it.file })
        assertEquals(listOf("shared", "fz"), EvictionPolicy.select(c, emptySet(), 1_000).map { it.file })
    }

    @Test fun missingSizeFallsBackToTheFile() {
        val c = EvictionPolicy.candidates(listOf(p("a", "fa", 0, shown = 1))) { 4_096 }
        assertEquals(4_096L, c.single().bytes)
    }

    @Test fun thresholdsScaleWithTheDisk() {
        val mb = 1024L * 1024
        val big = StoragePolicy.forTotal(12_800 * mb)
        assertEquals(StoragePolicy(512 * mb, 768 * mb, 256 * mb), big)
        val small = StoragePolicy.forTotal(1_000 * mb)
        assertTrue(small.criticalBytes > MediaCache.MIN_FREE_BYTES && small.lowBytes > small.criticalBytes && small.targetBytes > small.lowBytes)
        assertEquals(StoragePolicy.Level.Ok, big.level(600 * mb))
        assertEquals(StoragePolicy.Level.Low, big.level(400 * mb))
        assertEquals(StoragePolicy.Level.Critical, big.level(100 * mb))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StorageManagerTest : SyncTestBase() {
    private val mb = 1024L * 1024
    private var free = 10_000 * mb
    private var protect = emptySet<String>()
    private val storage by lazy { StorageManager(db, media, { protect }, { now }, freeBytes = { free }, totalBytes = { 12_800 * mb }) }
    private val acks by lazy { AckQueue(db, { now }) }
    private val engine by lazy { SyncEngine(api, db, media, { now }) }
    private val downloader by lazy { Downloader(api, db, media, acks, { now }, freeBytes = { free }, makeRoom = { storage.ensureSpace(it) }) }
    private val photos = (1..4).map { Random(it).nextBytes(200_000) }

    private suspend fun stored() {
        fake.pages[0] = page(4, false, *photos.mapIndexed { i, b -> """{"seq":${i + 1},"type":"placement.upsert","placement":${placement("p$i", b)}}""" }.toTypedArray())
        photos.forEachIndexed { i, b -> fake.media["p$i"] = b }
        engine.pull()
        for (i in photos.indices) assertTrue(downloader.downloadOne(db.placements().get("p$i")!!))
        db.acks().deleteAll()
        // p0 was shown longest ago, p3 most recently.
        photos.indices.forEach { db.placements().markShown("p$it", 1_000L + it) }
    }

    @Test fun nothingHappensWithPlentyOfSpace() = runBlocking {
        stored()
        assertTrue(storage.ensureSpace())
        assertEquals(0, db.placements().countEvicted())
        assertEquals(StoragePolicy.Level.Ok, storage.state.value.level)
    }

    @Test fun evictsLeastRecentlyShownAndSkipsProtectedAndPendingAcks() = runBlocking {
        stored()
        protect = setOf("p0") // on screen
        acks.enqueueSeen("p1") // seen ack not sent yet
        free = 300 * mb // below the 512 MB low mark: free 468 MB more
        storage.ensureSpace()
        val p = (0..3).map { db.placements().get("p$it")!! }
        assertNotNull(p[0].localFile)
        assertNotNull(p[1].localFile)
        assertNull(p[2].localFile)
        assertNull(p[3].localFile)
        assertNotNull(p[2].evictedAt)
        assertEquals(2, media.dir.listFiles()!!.count { it.isFile })
        // Evicted items are not downloaded again eagerly...
        assertTrue(db.placements().pendingDownloads(now, 10).isEmpty())
        assertEquals(0, db.placements().countMissing())
        assertEquals(2, storage.state.value.evicted)
        // ...only on demand, and then without a new "delivered" problem (the server ignores repeats).
        free = 10_000 * mb
        storage.requestOnDemand("p2")
        assertEquals(listOf("p2"), db.placements().pendingDownloads(now, 10).map { it.id })
        assertTrue(downloader.downloadOne(db.placements().get("p2")!!))
        assertNull(db.placements().get("p2")!!.evictedAt)
    }

    @Test fun criticalWhenNothingCanBeEvicted() = runBlocking {
        stored()
        protect = setOf("p0", "p1", "p2", "p3")
        free = 100 * mb
        assertFalse(storage.ensureSpace())
        assertEquals(StoragePolicy.Level.Critical, storage.state.value.level)
        assertEquals(0, db.placements().countEvicted())
    }

    @Test fun downloaderMakesRoomInsteadOfFailing() = runBlocking {
        stored()
        free = 129 * mb // just above the hard minimum: a new download needs room
        val extra = Random(9).nextBytes(2 * 1024 * 1024)
        fake.media["p9"] = extra
        fake.pages[4] = page(5, false, """{"seq":5,"type":"placement.upsert","placement":${placement("p9", extra)}}""")
        engine.pull()
        val ok = downloader.downloadOne(db.placements().get("p9")!!)
        // The fake disk doesn't grow, but eviction ran before giving up.
        assertTrue(db.placements().countEvicted() > 0)
        assertFalse(ok)
        assertEquals("storage_full", db.placements().get("p9")!!.lastError)
    }

    @Suppress("unused") private val keep = listOf(AckEntity.SEEN, FakeServer::class)
}
