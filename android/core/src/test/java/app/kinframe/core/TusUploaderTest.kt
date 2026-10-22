package app.kinframe.core

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.ByteArraySource
import app.kinframe.core.api.TusResult
import app.kinframe.core.api.TusState
import app.kinframe.core.api.TusUploader
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TusUploaderTest {
    private val server = MockWebServer()
    private val tokens = object : AccessTokenSource {
        var refreshes = 0
        override suspend fun accessToken(forceRefresh: Boolean): String {
            if (forceRefresh) refreshes++
            return "tok$refreshes"
        }
    }
    private val sleeps = mutableListOf<Long>()
    private lateinit var tus: TusUploader
    private val bytes = ByteArray(10) { it.toByte() }

    @Before
    fun setUp() {
        server.start()
        tus = TusUploader(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), server.url("/v1/uploads"), tokens, chunkSize = 4, sleep = { sleeps += it })
    }

    @After
    fun tearDown() = server.close()

    private fun created(location: String = "/v1/uploads/up1") =
        MockResponse.Builder().code(201).addHeader("Location", location).addHeader("Tus-Resumable", "1.0.0").build()

    private fun patched(offset: Long) = MockResponse.Builder().code(204).addHeader("Upload-Offset", offset.toString()).build()

    private fun head(offset: Long) = MockResponse.Builder().code(200).addHeader("Upload-Offset", offset.toString()).build()

    @Test
    fun createsThenPatchesInChunksAndReportsState() = runBlocking {
        server.enqueue(created())
        server.enqueue(patched(4))
        server.enqueue(patched(8))
        server.enqueue(patched(10))
        val states = mutableListOf<TusState>()
        val result = tus.upload(ByteArraySource(bytes), mapOf("filename" to "a.jpg", "filetype" to "image/jpeg"), onState = { states += it })
        assertEquals(TusResult.Uploaded("up1"), result)

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("10", create.headers["Upload-Length"])
        assertEquals("1.0.0", create.headers["Tus-Resumable"])
        assertEquals("filename YS5qcGc=,filetype aW1hZ2UvanBlZw==", create.headers["Upload-Metadata"])
        assertEquals("Bearer tok0", create.headers["Authorization"])

        val p1 = server.takeRequest()
        assertEquals("PATCH", p1.method)
        assertEquals("0", p1.headers["Upload-Offset"])
        assertEquals("application/offset+octet-stream", p1.headers["Content-Type"])
        assertEquals(4L, p1.bodySize)
        assertEquals("4", server.takeRequest().headers["Upload-Offset"])
        val p3 = server.takeRequest()
        assertEquals("8", p3.headers["Upload-Offset"])
        assertEquals(2L, p3.bodySize)
        assertEquals(listOf(0L, 4L, 8L, 10L), states.map { it.offset })
        assertTrue(states.all { it.uploadUrl!!.endsWith("/v1/uploads/up1") })
    }

    @Test
    fun resumesFromSavedStateWithHead() = runBlocking {
        server.enqueue(head(6))
        server.enqueue(patched(10))
        val saved = TusState(server.url("/v1/uploads/up9").toString(), 4)
        val result = tus.upload(ByteArraySource(bytes), mapOf("filename" to "a"), resume = saved)
        assertEquals(TusResult.Uploaded("up9"), result)
        assertEquals("HEAD", server.takeRequest().method)
        val p = server.takeRequest()
        // The server's offset wins over the saved one.
        assertEquals("6", p.headers["Upload-Offset"])
        assertEquals(4L, p.bodySize)
    }

    @Test
    fun startsOverWhenSavedUploadIsGone() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(created("/v1/uploads/fresh"))
        server.enqueue(patched(4))
        server.enqueue(patched(8))
        server.enqueue(patched(10))
        val result = tus.upload(ByteArraySource(bytes), emptyMap(), resume = TusState(server.url("/v1/uploads/old").toString(), 8))
        assertEquals(TusResult.Uploaded("fresh"), result)
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun retriesAfterDroppedConnectionFromServerOffset() = runBlocking {
        server.enqueue(created())
        server.enqueue(patched(4))
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(head(6)) // the server got part of the failed chunk
        server.enqueue(patched(10))
        val result = tus.upload(ByteArraySource(bytes), emptyMap())
        assertEquals(TusResult.Uploaded("up1"), result)
        assertEquals(listOf("POST", "PATCH", "PATCH", "HEAD", "PATCH"), List(5) { server.takeRequest().method })
        assertEquals(listOf(0L), sleeps)
    }

    @Test
    fun conflictTriggersHeadAndContinues() = runBlocking {
        server.enqueue(created())
        server.enqueue(MockResponse.Builder().code(409).build())
        server.enqueue(head(4))
        server.enqueue(patched(8))
        server.enqueue(patched(10))
        assertEquals(TusResult.Uploaded("up1"), tus.upload(ByteArraySource(bytes), emptyMap()))
    }

    @Test
    fun duplicateSkipsTheBytes() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).addHeader("Location", "/v1/uploads/x").addHeader("Kinframe-Existing-Item", "item42").build())
        var progress = 0L
        val r = tus.upload(ByteArraySource(bytes), mapOf("sha256" to "ab"), onProgress = { sent, _ -> progress = sent })
        assertEquals(TusResult.Existing("item42"), r)
        assertEquals(10L, progress)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun refreshesTokenOnceOn401() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(created())
        server.enqueue(patched(4)); server.enqueue(patched(8)); server.enqueue(patched(10))
        tus.upload(ByteArraySource(bytes), emptyMap())
        assertEquals("Bearer tok0", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer tok1", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun tooLargeIsNotRetried() = runBlocking {
        server.enqueue(MockResponse.Builder().code(413).build())
        try {
            tus.upload(ByteArraySource(bytes), emptyMap())
            fail()
        } catch (e: ApiException) {
            assertEquals(413, e.status)
            assertEquals("upload_too_large", e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun givesUpAfterRepeatedServerErrors() = runBlocking {
        server.enqueue(created())
        repeat(12) { server.enqueue(MockResponse.Builder().code(503).build()) }
        try {
            tus.upload(ByteArraySource(bytes), emptyMap())
            fail()
        } catch (e: ApiException) {
            assertEquals(503, e.status)
        }
        assertEquals(5, sleeps.size)
    }

    @Test
    fun uploadIdIsTheLastSegment() {
        assertEquals("abc", TusUploader.uploadIdFromUrl("https://x/v1/uploads/abc/?t=1"))
        assertEquals("a b", TusUploader.uploadIdFromUrl("/v1/uploads/a%20b"))
    }
}
