package app.kinframe.core

import app.kinframe.core.api.ApiException
import app.kinframe.core.api.ChangeType
import app.kinframe.core.api.ChangesPage
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.FramePhotoRequest
import app.kinframe.core.api.FramePrompts
import app.kinframe.core.api.FrameFeatures
import app.kinframe.core.api.FrameSocialSettings
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.PlacementComments
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The device side of family social (README §5.20): `deviceRequestPhotos`, the three change types, settings. */
class DeviceSocialApiTest {
    private val server = MockWebServer()
    private lateinit var api: DeviceApi

    @Before
    fun setUp() {
        server.start()
        api = DeviceApi(OkHttpClient(), server.url("/v1"), MemoryCredentialStore(DeviceCredential("dev1", "s3cret")), "test/1")
    }

    @After
    fun tearDown() = server.close()

    private fun token() = MockResponse.Builder().code(200).body("""{"access_token":"t","expires_in":3600}""").build()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    private val request = """{"id":"r1","state":"open","created_at":"2026-11-19T10:00:00Z","next_allowed_at":"2026-11-20T10:00:00Z","answered_at":null,"answered_by_name":null}"""

    @Test
    fun requestPhotosPostsAndReadsTheRequest() = runBlocking {
        server.enqueue(token())
        server.enqueue(json(request, 201))
        val r = api.requestPhotos()
        assertEquals("r1", r.id)
        assertEquals("2026-11-20T10:00:00Z", r.nextAllowedAt)
        assertFalse(r.isAnswered)
        server.takeRequest()
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/device/photo-requests", sent.url.encodedPath)
    }

    @Test
    fun alreadyAskedIsAnOrdinaryAnswer() = runBlocking {
        server.enqueue(token())
        server.enqueue(json(request, 200))
        assertEquals("r1", api.requestPhotos().id)
    }

    @Test
    fun buttonTurnedOffIs403() = runBlocking {
        server.enqueue(token())
        server.enqueue(json("""{"type":"x","title":"Forbidden","status":403,"code":"forbidden"}""", 403))
        try {
            api.requestPhotos(); fail("expected 403")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
        }
    }

    @Test
    fun parsesTheThreeChangeTypesAndThePromptOfAPlacement() {
        val page = KinJson.decodeFromString(
            ChangesPage.serializer(),
            """{"changes":[
              {"seq":1,"type":"comments","id":"p1","comments":{"placement_id":"p1","comments":[
                {"id":"c2","author_name":"Anna","body":"Wat een mooie dag!","created_at":"2026-11-19T10:00:00Z"},
                {"id":"c1","author_name":"Ben","body":"Prachtig","created_at":"2026-11-18T10:00:00Z"}]}},
              {"seq":2,"type":"comments","id":"p2","comments":null},
              {"seq":3,"type":"photo_request","id":"f","photo_request":{"id":"r1","state":"answered","created_at":"2026-11-19T10:00:00Z",
                "next_allowed_at":"2026-11-20T10:00:00Z","answered_at":"2026-11-19T12:00:00Z","answered_by_name":"Ben"}},
              {"seq":4,"type":"prompts","id":"f","prompts":{"generated_at":"2026-11-17T00:00:00Z","prompts":[
                {"prompt_id":"q1","feed_id":"fd","feed_name":"Peeters","title":"Your view right now","week_of":"2026-11-16"}]}},
              {"seq":5,"type":"placement.upsert","placement":{"id":"p3","item":{"id":"i3","kind":"photo"},"status":"ready",
                "hidden":false,"trashed":false,"prompt_id":"q1"}}
            ],"next_seq":5,"has_more":false}""",
        )
        val c = page.changes
        assertEquals(ChangeType.COMMENTS, c[0].type)
        val comments = PlacementComments.parse(c[0].comments)!!
        assertEquals("Anna", comments.newest!!.authorName)
        assertEquals(2, comments.comments.size)
        assertNull(c[1].comments)
        val req = FramePhotoRequest.parse(c[2].photoRequest)!!
        assertTrue(req.isAnswered)
        assertEquals("Ben", req.answeredByName)
        val prompts = FramePrompts.parse(c[3].prompts)!!
        assertEquals("Your view right now", prompts.prompts.single().title)
        assertEquals("q1", c[4].placement!!.promptId)
    }

    @Test
    fun socialSettingsDefaultsAndValues() {
        assertEquals(FrameSocialSettings.DEFAULT, FrameSocialSettings.of(null))
        assertFalse(FrameSocialSettings.DEFAULT.showComments)
        assertTrue(FrameSocialSettings.DEFAULT.askForPhotos)
        assertTrue(FrameSocialSettings.DEFAULT.promptCards)
        assertTrue(FrameSocialSettings.DEFAULT.newPhotosFrom)
        val raw = KinJson.parseToJsonElement("""{"version":3,"social":{"show_comments":true,"ask_for_photos":false}}""").jsonObject
        val s = FrameSocialSettings.of(raw)
        assertTrue(s.showComments)
        assertFalse(s.askForPhotos)
        assertTrue(s.promptCards)
    }

    @Test
    fun heartbeatAdvertisesTheSocialFeatures() {
        for (f in listOf("comments", "photo_requests", "prompts")) {
            assertTrue(f, f in FrameFeatures.forDistribution("github"))
            assertTrue(f, f in FrameFeatures.forDistribution("play"))
        }
    }
}
