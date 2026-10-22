package app.kinframe.frame

import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.FrameUpdate
import app.kinframe.core.auth.DeviceCredential
import app.kinframe.core.auth.MemoryCredentialStore
import app.kinframe.frame.update.ApkDownloader
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val apk = Random(5).nextBytes(700_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var etag = "\"$sha\""
    private lateinit var api: DeviceApi

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.url.encodedPath.endsWith("/devices/token")) {
                    return MockResponse.Builder().code(200).body("""{"access_token":"tok","expires_in":3600}""").build()
                }
                val range = request.headers["Range"]
                val ifRange = request.headers["If-Range"]
                if (range != null && (ifRange == null || ifRange == etag)) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    return MockResponse.Builder().code(206).addHeader("ETag", etag)
                        .addHeader("Content-Range", "bytes $from-${apk.size - 1}/${apk.size}")
                        .body(Buffer().write(apk, from, apk.size - from)).build()
                }
                return MockResponse.Builder().code(200).addHeader("ETag", etag).body(Buffer().write(apk)).build()
            }
        }
        server.start()
        api = DeviceApi(OkHttpClient(), server.url("/v1"), MemoryCredentialStore(DeviceCredential("d", "s")), "kinframe-frame/0.1.0 (1)")
    }

    @After
    fun tearDown() = server.close()

    private val offer get() = FrameUpdate("0.2.0", 20099, 23, "/v1/device/update/apk/20099", sha, apk.size.toLong())

    @Test
    fun downloadsWithTheDeviceToken() = runBlocking {
        val dir = tmp.newFolder()
        val f = ApkDownloader(api, dir).fetch(offer)
        assertArrayEquals(apk, f.readBytes())
        assertEquals(sha, ApkDownloader.sha256(f))
        val get = requests.last()
        assertEquals("/v1/device/update/apk/20099", get.url.encodedPath)
        assertEquals("Bearer tok", get.headers["Authorization"])
        // Second call: already complete, no request.
        val n = requests.size
        ApkDownloader(api, dir).fetch(offer)
        assertEquals(n, requests.size)
    }

    @Test
    fun resumesAPartialDownload() = runBlocking {
        val dir = tmp.newFolder()
        java.io.File(dir, "kinframe-frame-20099.apk.part").writeBytes(apk.copyOf(250_000))
        java.io.File(dir, "kinframe-frame-20099.apk.etag").writeText(etag)
        val f = ApkDownloader(api, dir).fetch(offer)
        assertArrayEquals(apk, f.readBytes())
        val get = requests.last()
        assertEquals("bytes=250000-", get.headers["Range"])
        assertEquals(etag, get.headers["If-Range"])
    }

    @Test
    fun restartsWhenTheFileChangedOnTheServer() = runBlocking {
        val dir = tmp.newFolder()
        java.io.File(dir, "kinframe-frame-20099.apk.part").writeBytes(ByteArray(250_000))
        java.io.File(dir, "kinframe-frame-20099.apk.etag").writeText("\"old\"")
        val f = ApkDownloader(api, dir).fetch(offer)
        assertArrayEquals(apk, f.readBytes())
    }

    @Test
    fun dropsOtherVersionsAndRejectsOversizedBodies() = runBlocking {
        val dir = tmp.newFolder()
        val stale = java.io.File(dir, "kinframe-frame-10099.apk").apply { writeText("old") }
        val small = offer.copy(size = 1000)
        val d = ApkDownloader(api, dir)
        val r = runCatching { d.fetch(small) }
        assertTrue(r.isFailure)
        assertFalse(stale.exists())
        d.discard(small)
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
