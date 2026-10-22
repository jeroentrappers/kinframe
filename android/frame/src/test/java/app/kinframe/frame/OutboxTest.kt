package app.kinframe.frame

import android.app.Application
import app.kinframe.frame.FakeServer.Companion.page
import app.kinframe.frame.FakeServer.Companion.placement
import app.kinframe.frame.data.OutboxEntity
import app.kinframe.frame.sync.Outbox
import app.kinframe.frame.sync.SyncEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutboxTest : SyncTestBase() {
    private val outbox by lazy { Outbox(db) { System.currentTimeMillis() } }

    @Test
    fun deleteIsLocalImmediatelyAndUndoBeforeTheDeadlineSendsNothing() = runBlocking {
        fake.pages[0] = page(1, false, """{"seq":1,"type":"placement.upsert","placement":${placement("a", byteArrayOf(1))}}""")
        SyncEngine(api, db, media, { now }).pull()
        val id = outbox.placementPatch("a", trashed = true, delayMs = 60_000)
        assertTrue(db.placements().get("a")!!.trashed)
        assertTrue(outbox.cancel(id, "a", restoreTrashed = false))
        assertFalse(db.placements().get("a")!!.trashed)
        assertEquals(0, db.outbox().ofKind(OutboxEntity.PLACEMENT_PATCH).size)
    }

    @Test
    fun settingsEditsMergeIntoOnePatchSentWithIfMatch() = runBlocking {
        db.syncState().put(app.kinframe.frame.data.SyncStateEntity(settingsJson = """{"version":8}"""))
        outbox.settingsPatch(buildJsonObject { put("slideshow", buildJsonObject { put("interval_s", JsonPrimitive(10)) }) })
        outbox.settingsPatch(buildJsonObject { put("slideshow", buildJsonObject { put("order", JsonPrimitive("shuffle")) }) })
        assertEquals(1, db.outbox().ofKind(OutboxEntity.SETTINGS_PATCH).size)
        val job = async { outbox.run(api) }
        withTimeout(10_000) {
            while (fake.requests.none { it.url.encodedPath.endsWith("device/settings") }) delay(50)
            while (db.outbox().ofKind(OutboxEntity.SETTINGS_PATCH).isNotEmpty()) delay(50)
        }
        job.cancel()
        val r = fake.requests.first { it.url.encodedPath.endsWith("device/settings") }
        assertEquals("\"8\"", r.headers["If-Match"])
        assertEquals("""{"slideshow":{"interval_s":10,"order":"shuffle"}}""", r.body!!.utf8())
        // The server's merged document is stored right away.
        assertTrue(db.syncState().get()!!.settingsJson!!.contains("\"version\":9"))
    }

    @Test
    fun versionConflictPullsAndRetries() = runBlocking {
        db.syncState().put(app.kinframe.frame.data.SyncStateEntity(settingsJson = """{"version":1}"""))
        fake.settingsStatus = 412
        var conflicts = 0
        outbox.onVersionConflict = { conflicts++; fake.settingsStatus = 200 }
        outbox.settingsPatch(buildJsonObject { put("display", buildJsonObject { put("brightness", JsonPrimitive(120)) }) })
        val job = async { outbox.run(api) }
        withTimeout(20_000) { while (db.outbox().ofKind(OutboxEntity.SETTINGS_PATCH).isNotEmpty()) delay(50) }
        job.cancel()
        assertEquals(1, conflicts)
        assertEquals(2, fake.requests.count { it.url.encodedPath.endsWith("device/settings") })
    }
}
