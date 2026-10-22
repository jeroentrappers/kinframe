package app.kinframe.family

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.ByteArraySource
import app.kinframe.core.api.HttpKinApi
import app.kinframe.core.api.TusUploader
import app.kinframe.family.send.OutboxItem
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.send.OutboxState
import app.kinframe.family.send.OutboxStore
import app.kinframe.family.send.SendRunner
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The outbox pipeline: tus uploads that resume across runs, then one idempotent POST /v1/sends. */
class SendRunnerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var store: OutboxStore
    private lateinit var runner: SendRunner
    private val tokens = object : AccessTokenSource { override suspend fun accessToken(forceRefresh: Boolean) = "t" }
    private val bytes = ByteArray(10) { it.toByte() }

    @Before
    fun setUp() {
        server.start()
        store = OutboxStore(tmp.newFile("outbox.json").also { it.delete() })
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val api = HttpKinApi(http, server.url("/v1"), tokens)
        val tus = TusUploader(http, server.url("/v1/uploads"), tokens, chunkSize = 6, retryDelaysMs = emptyList())
        runner = SendRunner(store, api, tus, sourceFor = { ByteArraySource(bytes) }, hash = { "abc123" })
        store.add(
            OutboxSend(
                id = "idem-1", server = "x", frameIds = listOf("f1"), frameNames = listOf("Oma"), createdAt = 0,
                items = listOf(OutboxItem("i1", "content://1", mime = "image/jpeg", name = "a.jpg", size = 10, caption = "Hi", fit = "fill", focalX = 0.3, focalY = 0.4)),
            ),
        )
    }

    @After fun tearDown() = server.close()

    private fun created() = MockResponse.Builder().code(201).addHeader("Location", "/v1/uploads/up1").build()
    private fun patched(o: Int) = MockResponse.Builder().code(204).addHeader("Upload-Offset", "$o").build()
    private val sendJson = """{"id":"send1","created_at":"2026-10-22T10:00:00Z","items":[],"frames":[]}"""

    @Test
    fun resumesAfterAFailedRunAndSendsOnce() = runBlocking {
        // Run 1: the first chunk goes through, then the server falls over.
        server.enqueue(created())
        server.enqueue(patched(6))
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(SendRunner.Outcome.Retry, runner.run("idem-1"))
        val saved = store.get("idem-1")!!.items.single()
        assertEquals(6L, saved.uploadedBytes)
        assertTrue(saved.uploadUrl!!.endsWith("/v1/uploads/up1"))
        assertEquals("abc123", saved.sha256)
        assertEquals(OutboxState.QUEUED, store.get("idem-1")!!.state)

        // Run 2: HEAD finds the offset, the rest is uploaded, then the send is created.
        server.enqueue(MockResponse.Builder().code(200).addHeader("Upload-Offset", "6").build())
        server.enqueue(patched(10))
        server.enqueue(MockResponse.Builder().code(202).body(sendJson).build())
        val out = runner.run("idem-1")
        assertEquals(SendRunner.Outcome.Sent("send1", listOf("Oma")), out)
        assertNull(store.get("idem-1"))

        val reqs = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("POST", "PATCH", "PATCH", "HEAD", "PATCH", "POST"), reqs.map { it.method })
        assertTrue(reqs[0].headers["Upload-Metadata"]!!.contains("sha256 "))
        assertEquals("6", reqs[4].headers["Upload-Offset"])
        val send = reqs[5]
        assertEquals("/v1/sends", send.url.encodedPath)
        assertEquals("idem-1", send.headers["Idempotency-Key"])
        assertEquals(
            """{"frame_ids":["f1"],"items":[{"upload_id":"up1","caption":"Hi","focal_point":{"x":0.3,"y":0.4},"fit":"fill"}]}""",
            send.body!!.utf8(),
        )
    }

    @Test
    fun aFailedCreateIsRetriedWithoutUploadingAgain() = runBlocking {
        server.enqueue(created()); server.enqueue(patched(6)); server.enqueue(patched(10))
        server.enqueue(MockResponse.Builder().code(502).build())
        assertEquals(SendRunner.Outcome.Retry, runner.run("idem-1"))
        assertEquals("up1", store.get("idem-1")!!.items.single().uploadId)
        server.enqueue(MockResponse.Builder().code(202).body(sendJson).build())
        assertTrue(runner.run("idem-1") is SendRunner.Outcome.Sent)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun refusedSendNeedsThePerson() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).addHeader("Location", "/v1/uploads/x").addHeader("Kinframe-Existing-Item", "item9").build())
        server.enqueue(MockResponse.Builder().code(403).addHeader("Content-Type", "application/problem+json")
            .body("""{"type":"t","title":"No","status":403,"code":"missing_permission"}""").build())
        assertEquals(SendRunner.Outcome.Failed("missing_permission"), runner.run("idem-1"))
        val s = store.get("idem-1")!!
        assertEquals(OutboxState.FAILED, s.state)
        assertEquals("item9", s.items.single().uploadId)
    }
}
