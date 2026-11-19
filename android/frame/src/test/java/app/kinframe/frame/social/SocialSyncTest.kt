package app.kinframe.frame.social

import android.app.Application
import app.kinframe.core.api.FramePhotoRequest
import app.kinframe.core.api.FramePrompts
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.PlacementComments
import app.kinframe.frame.FakeServer.Companion.page
import app.kinframe.frame.FakeServer.Companion.placement
import app.kinframe.frame.SyncTestBase
import app.kinframe.frame.data.OutboxEntity
import app.kinframe.frame.data.SyncStateEntity
import app.kinframe.frame.sync.Outbox
import app.kinframe.frame.sync.SyncEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The family social change types on the frame (README §5.20), persisted in Room so they work offline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SocialSyncTest : SyncTestBase() {
    private fun up(seq: Int, p: String) = """{"seq":$seq,"type":"placement.upsert","placement":$p}"""
    private fun comments(seq: Int, id: String, vararg c: Pair<String, String>) =
        """{"seq":$seq,"type":"comments","id":"$id","comments":{"placement_id":"$id","comments":[${
            c.mapIndexed { i, (who, text) -> """{"id":"c$i","author_name":"$who","body":"$text","created_at":"2026-11-19T10:00:00Z"}""" }.joinToString(",")
        }]}}"""
    private fun request(seq: Int, state: String, by: String? = null) =
        """{"seq":$seq,"type":"photo_request","id":"f","photo_request":{"id":"r1","state":"$state","created_at":"2026-11-19T10:00:00Z",
            "next_allowed_at":"2026-11-20T10:00:00Z","answered_at":${if (by != null) "\"2026-11-19T12:00:00Z\"" else "null"},
            "answered_by_name":${by?.let { "\"$it\"" } ?: "null"}}}"""

    private fun sync() = SyncEngine(api, db, media, { now })

    private suspend fun state() = db.syncState().get()!!

    @Test
    fun commentsAreStoredPerPlacementAndDroppedByNullAndRemoval() = runBlocking {
        fake.pages[0] = page(3, false, up(1, placement("a", byteArrayOf(1))), up(2, placement("b", byteArrayOf(2))),
            comments(3, "a", "Anna" to "Wat een mooie dag!", "Ben" to "Prachtig"))
        fake.pages[3] = page(5, false, comments(4, "b", "Oma" to "Dag schat"), """{"seq":5,"type":"comments","id":"a","comments":null}""")
        val s = sync()
        s.pull()
        val a = PlacementComments.parse(KinJson.parseToJsonElement(db.social().comments("a")!!.json).jsonObject)!!
        assertEquals("Anna", a.newest!!.authorName)
        assertEquals("Wat een mooie dag!", a.newest!!.body)
        s.pull()
        assertNull(db.social().comments("a"))
        assertNotNull(db.social().comments("b"))
        fake.pages[5] = page(6, false, """{"seq":6,"type":"placement.remove","id":"b"}""")
        s.pull()
        assertNull(db.social().comments("b"))
    }

    @Test
    fun promptsAndThePromptOfAnAnswerArePersisted() = runBlocking {
        fake.pages[0] = page(2, false,
            """{"seq":1,"type":"prompts","id":"f","prompts":{"generated_at":"2026-11-16T00:00:00Z","prompts":[
                {"prompt_id":"q1","feed_id":"fd","feed_name":"Peeters","title":"Your view right now","week_of":"2026-11-16"}]}}""",
            up(2, placement("a", byteArrayOf(1), extra = ""","origin":"feed","origin_feed_id":"fd","prompt_id":"q1"""")),
        )
        fake.pages[2] = page(3, false, """{"seq":3,"type":"prompts","id":"f","prompts":null}""")
        val s = sync()
        s.pull()
        assertEquals("q1", db.placements().get("a")!!.promptId)
        val prompts = FramePrompts.parse(KinJson.parseToJsonElement(state().promptsJson!!).jsonObject)!!
        assertEquals("Your view right now", prompts.prompts.single().title)
        s.pull()
        assertNull(state().promptsJson)
    }

    @Test
    fun newPersonalPhotosRememberTheirSendersForTheNextWake() = runBlocking {
        fake.pages[0] = page(1, false, up(1, placement("old", byteArrayOf(1))))
        // Mailed to the frame's address: the person who mailed it is named, not the account behind the address.
        val mailed = """{"id":"n3","frame_id":"f","item":{"id":"i-n3","kind":"photo","sender_id":"u2","sender_name":"Owner",
            "via":{"service":"email","label":"Jan"}},"status":"ready","hidden":false,"trashed":false,"received_at":"2026-10-20T10:00:00Z"}"""
        fake.pages[1] = page(5, false,
            up(2, placement("n1", byteArrayOf(2))),
            // Feed history is not new: nobody is announced for it.
            up(3, placement("h", byteArrayOf(3), extra = ""","origin":"feed","backfill":true""")),
            up(4, placement("n2", byteArrayOf(4)).replace("\"sender_name\":\"Ann\"", "\"sender_name\":\"Ben\"")),
            up(5, mailed),
        )
        val s = sync()
        s.pull()
        // The snapshot is history, not news.
        assertNull(state().newPhotosFrom)
        s.pull()
        assertEquals(listOf("Ann", "Ben", "Jan"), SocialLogic.names(state().newPhotosFrom))
    }

    @Test
    fun anAnsweredRequestNamesWhoAnsweredFirst() = runBlocking {
        fake.pages[0] = page(1, false, request(1, "open"))
        fake.pages[1] = page(3, false, up(2, placement("n1", byteArrayOf(2))), request(3, "answered", by = "Ben"))
        val s = sync()
        s.pull()
        assertNull(state().newPhotosFrom)
        assertEquals("open", FramePhotoRequest.parse(KinJson.parseToJsonElement(state().photoRequestJson!!).jsonObject)!!.state)
        s.pull()
        assertEquals(listOf("Ben", "Ann"), SocialLogic.names(state().newPhotosFrom))
        // Repeating the answered state names nobody again.
        db.syncState().put(state().copy(newPhotosFrom = null))
        fake.pages[3] = page(4, false, request(4, "answered", by = "Ben"))
        s.pull()
        assertNull(state().newPhotosFrom)
    }

    @Test
    fun resetDropsCommentsPromptsAndTheRequest() = runBlocking {
        fake.pages[0] = page(3, false, up(1, placement("a", byteArrayOf(1))), comments(2, "a", "Anna" to "Mooi"), request(3, "open"))
        fake.pages[3] = page(4, false, """{"seq":4,"type":"reset"}""")
        val s = sync()
        s.pull()
        // After the reset the server's snapshot has none of them any more.
        fake.pages[0] = page(4, false)
        db.syncState().put(state().copy(promptsJson = """{"prompts":[]}"""))
        s.pull()
        assertNull(db.social().comments("a"))
        assertNull(state().promptsJson)
        assertNull(state().photoRequestJson)
    }

    @Test
    fun askingIsRememberedAtOnceQueuedOnceAndTheAnswerStored() = runBlocking {
        val outbox = Outbox(db) { System.currentTimeMillis() }
        assertTrue(outbox.photoRequest())
        assertFalse("a second tap queues nothing", outbox.photoRequest())
        assertNotNull(state().photoRequestAskedAt)
        assertEquals(AskState.Asked, SocialLogic.askState(null, state().photoRequestAskedAt, System.currentTimeMillis()))
        val job = async { outbox.run(api) }
        withTimeout(10_000) { while (db.outbox().ofKind(OutboxEntity.PHOTO_REQUEST).isNotEmpty()) delay(50) }
        job.cancel()
        assertEquals(1, fake.requests.count { it.url.encodedPath.endsWith("/device/photo-requests") && it.method == "POST" })
        val stored = FramePhotoRequest.parse(KinJson.parseToJsonElement(state().photoRequestJson!!).jsonObject)!!
        assertEquals("r1", stored.id)
    }

    @Test
    fun aButtonTurnedOffOnTheServerIsDroppedNotRetried() = runBlocking {
        db.syncState().put(SyncStateEntity())
        fake.status["/v1/device/photo-requests"] = 403
        val outbox = Outbox(db) { System.currentTimeMillis() }
        outbox.photoRequest()
        val job = async { outbox.run(api) }
        withTimeout(10_000) { while (db.outbox().ofKind(OutboxEntity.PHOTO_REQUEST).isNotEmpty()) delay(50) }
        job.cancel()
        assertNull(state().photoRequestJson)
        // Nobody was asked: the button must not keep saying "Asked today".
        assertNull(state().photoRequestAskedAt)
    }

    @Test
    fun aServerWithoutTheFeatureHidesTheButtonInsteadOfRetrying() = runBlocking {
        db.syncState().put(SyncStateEntity())
        fake.status["/v1/device/photo-requests"] = 501
        val outbox = Outbox(db) { System.currentTimeMillis() }
        var unavailable = 0
        outbox.onPhotoRequestUnavailable = { unavailable++ }
        outbox.photoRequest()
        val job = async { outbox.run(api) }
        withTimeout(10_000) { while (db.outbox().ofKind(OutboxEntity.PHOTO_REQUEST).isNotEmpty()) delay(50) }
        job.cancel()
        assertEquals(1, unavailable)
        assertNull(state().photoRequestAskedAt)
        assertEquals(1, fake.requests.count { it.url.encodedPath.endsWith("/device/photo-requests") })
    }
}
