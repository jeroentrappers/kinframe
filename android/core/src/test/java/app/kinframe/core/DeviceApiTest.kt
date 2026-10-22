package app.kinframe.core

import app.kinframe.core.api.ApiException
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.DeviceRevokedException
import app.kinframe.core.auth.DeviceCredential
import app.kinframe.core.auth.MemoryCredentialStore
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DeviceApiTest {
    private val server = MockWebServer()
    private var now = 0L
    private lateinit var api: DeviceApi

    @Before
    fun setUp() {
        server.start()
        api = DeviceApi(OkHttpClient(), server.url("/v1"), MemoryCredentialStore(DeviceCredential("dev1", "s3cret")), "test/1", { now })
    }

    @After
    fun tearDown() = server.close()

    private fun token(t: String, exp: Int = 3600) =
        MockResponse.Builder().code(200).body("""{"access_token":"$t","expires_in":$exp}""").build()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    private val state = """{"device_id":"dev1","claimed":true,"latest_seq":3}"""

    @Test
    fun exchangesSecretThenSendsBearerAndAppHeader() = runBlocking {
        server.enqueue(token("t1"))
        server.enqueue(json(state))
        assertTrue(api.state().claimed)
        val tokenReq = server.takeRequest()
        assertEquals("/v1/devices/token", tokenReq.url.encodedPath)
        assertTrue(tokenReq.body!!.utf8().contains("\"device_secret\":\"s3cret\""))
        val r = server.takeRequest()
        assertEquals("Bearer t1", r.headers["Authorization"])
        assertEquals("test/1", r.headers["X-Kinframe-App"])
    }

    @Test
    fun refreshesWhenLessThanFiveMinutesRemain() = runBlocking {
        server.enqueue(token("t1", exp = 3600))
        server.enqueue(json(state))
        server.enqueue(json(state))
        server.enqueue(token("t2", exp = 3600))
        server.enqueue(json(state))
        api.state()
        now += 50 * 60 * 1000 // 10 min left: still fine
        api.state()
        now += 6 * 60 * 1000 // 4 min left: refresh first
        api.state()
        val paths = List(5) { server.takeRequest() }.map { it.url.encodedPath to it.headers["Authorization"] }
        assertEquals(
            listOf("/v1/devices/token" to null, "/v1/device/state" to "Bearer t1", "/v1/device/state" to "Bearer t1", "/v1/devices/token" to null, "/v1/device/state" to "Bearer t2"),
            paths,
        )
    }

    @Test
    fun tokenExpiredRefreshesAndRetriesOnce() = runBlocking {
        server.enqueue(token("t1"))
        server.enqueue(json("""{"type":"x","title":"expired","status":401,"code":"token_expired"}""", 401))
        server.enqueue(token("t2"))
        server.enqueue(json(state))
        assertEquals(3, api.state().latestSeq)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun deviceRevokedSurfacesAsException() = runBlocking {
        server.enqueue(token("t1"))
        server.enqueue(json("""{"type":"x","title":"revoked","status":401,"code":"device_revoked"}""", 401))
        try {
            api.state(); fail()
        } catch (e: DeviceRevokedException) {
            assertEquals("device_revoked", e.code)
        }
    }

    @Test
    fun rejectedSecretMeansRevoked() = runBlocking {
        server.enqueue(json("""{"type":"x","title":"no","status":401,"code":"unauthorized"}""", 401))
        try {
            api.state(); fail()
        } catch (e: DeviceRevokedException) {
            // expected
        }
    }

    @Test
    fun settingsPatchSendsIfMatchAndMergePatch() = runBlocking {
        server.enqueue(token("t1"))
        server.enqueue(json("""{"type":"x","title":"stale","status":412,"code":"version_conflict"}""", 412))
        try {
            api.patchSettings(7, kotlinx.serialization.json.buildJsonObject { })
            fail()
        } catch (e: ApiException) {
            assertEquals(412, e.status)
            assertEquals("version_conflict", e.code)
        }
        server.takeRequest()
        val r = server.takeRequest()
        assertEquals("\"7\"", r.headers["If-Match"])
        assertTrue(r.headers["Content-Type"]!!.startsWith("application/merge-patch+json"))
    }

    @Test
    fun reactionNullIsSentExplicitly() = runBlocking {
        server.enqueue(token("t1"))
        server.enqueue(MockResponse.Builder().code(204).build())
        api.setReaction("p1", null)
        server.takeRequest()
        val r = server.takeRequest()
        assertEquals("PUT", r.method)
        assertEquals("""{"reaction":null}""", r.body!!.utf8())
    }

    @Test
    fun checkUpdateReturnsReleaseOrNullOn204() = runBlocking {
        server.enqueue(token("t1"))
        server.enqueue(json("""{"version_name":"0.2.0","version_code":20099,"min_sdk":23,"url":"/v1/device/update/apk/20099",
            "sha256":"${"a".repeat(64)}","size":1234,"published_at":"2026-10-22T10:00:00Z","prerelease":false,"future":1}"""))
        val u = api.checkUpdate(channel = "beta", skip = listOf(30001, 30002))!!
        assertEquals(20099, u.versionCode)
        assertEquals(1234L, u.size)
        server.takeRequest()
        val r = server.takeRequest()
        assertEquals("/v1/device/update", r.url.encodedPath)
        assertEquals("beta", r.url.queryParameter("channel"))
        assertEquals("30001,30002", r.url.queryParameter("skip"))
        server.enqueue(MockResponse.Builder().code(204).build())
        assertEquals(null, api.checkUpdate())
    }

    @Test
    fun importFileStreamsBodyWithHeadersAndCaption() = runBlocking {
        val f = java.io.File.createTempFile("imp", ".jpg").apply { writeBytes(ByteArray(200_000) { it.toByte() }); deleteOnExit() }
        server.enqueue(token("t1"))
        server.enqueue(json("""{"id":"p9","item":{"id":"i9","kind":"photo"},"status":"pending"}""", 202))
        var progress = 0L
        val res = api.importFile(f, "Zoë's photo.jpg", "image/jpeg", capturedAt = "2026-01-02T03:04:05Z", caption = "Zee & zon") { progress = it }
        assertEquals("p9", res.placement.id)
        assertEquals(false, res.duplicate)
        assertEquals(200_000L, progress)
        server.takeRequest()
        val r = server.takeRequest()
        assertEquals("/v1/device/imports", r.url.encodedPath)
        assertEquals("Zee & zon", r.url.queryParameter("caption"))
        assertEquals("Zo_'s photo.jpg", r.headers["X-Filename"])
        assertEquals("2026-01-02T03:04:05Z", r.headers["X-Captured-At"])
        assertEquals("image/jpeg", r.headers["Content-Type"])
        assertEquals(200_000L, r.body!!.size.toLong())
    }

    @Test
    fun importFileReportsDuplicateOn200() = runBlocking {
        val f = java.io.File.createTempFile("imp", ".jpg").apply { writeText("x"); deleteOnExit() }
        server.enqueue(token("t1"))
        server.enqueue(json("""{"id":"p1","item":{"id":"i1"},"status":"seen"}"""))
        assertTrue(api.importFile(f, "a.jpg", null).duplicate)
    }

    @Test
    fun importCheckBatchesAndLowercases() = runBlocking {
        val hashes = (0 until 600).map { "%064X".format(it) }
        server.enqueue(token("t1"))
        server.enqueue(json("""{"known":["${hashes[3].lowercase()}"]}"""))
        server.enqueue(json("""{"known":["${hashes[550].lowercase()}"]}"""))
        val known = api.importCheck(hashes)
        assertEquals(setOf(hashes[3].lowercase(), hashes[550].lowercase()), known)
        server.takeRequest()
        val first = server.takeRequest()
        assertEquals("/v1/device/imports/check", first.url.encodedPath)
        assertTrue(first.body!!.utf8().startsWith("""{"sha256":["""))
    }

    @Test
    fun updateRequiredHookFiresOn426() = runBlocking {
        var fired = 0
        api.onUpdateRequired = { fired++ }
        server.enqueue(token("t1"))
        server.enqueue(json("""{"type":"x","title":"x","status":426,"code":"app_update_required"}""", 426))
        try {
            api.state(); fail("expected 426")
        } catch (e: app.kinframe.core.api.AppUpdateRequiredException) {
            assertEquals(1, fired)
        }
    }
}
