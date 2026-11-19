package app.kinframe.core

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.AppUpdateRequiredException
import app.kinframe.core.api.AppVersionInterceptor
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.DiscoveryClient
import app.kinframe.core.api.FamilyAppHeader
import app.kinframe.core.api.HttpKinApi
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Problem
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.TusUploader
import app.kinframe.core.api.UploadSource
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class AppVersionTest {
    private val server = MockWebServer()
    private val tokens = object : AccessTokenSource {
        override suspend fun accessToken(forceRefresh: Boolean) = "t"
    }
    private val header = FamilyAppHeader.value("1.4.0", 42)
    private val problems = mutableListOf<Problem?>()
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        http = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .addInterceptor(AppVersionInterceptor(header) { problems += it }).build()
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    private val updateRequired = """{"type":"about:blank","title":"Update the app","status":426,"code":"app_update_required"}"""

    @Test
    fun headerFormat() {
        assertEquals("kinframe-family-android/1.4.0 (42)", header)
    }

    @Test
    fun decodesAppVersions() {
        val d = KinJson.decodeFromString(Discovery.serializer(), """{"name":"k","app_versions":{
            "android":{"minimum_build":40,"recommended_build":45,"store_url":"https://f-droid.org/packages/app.kinframe.family"},
            "ios":{"minimum_build":null,"recommended_build":7,"store_url":null},"future":{"x":1}}}""")
        val a = d.appVersions!!.android!!
        assertEquals(40, a.minimumBuild)
        assertEquals(45, a.recommendedBuild)
        assertEquals("https://f-droid.org/packages/app.kinframe.family", a.storeUrl)
        assertNull(d.appVersions!!.ios!!.minimumBuild)
        assertEquals(7, d.appVersions!!.ios!!.recommendedBuild)
        assertNull(d.appVersions!!.macos)
        assertNull(KinJson.decodeFromString(Discovery.serializer(), """{"name":"k"}""").appVersions)
    }

    @Test
    fun apiSendsAppHeaderAndUserAgent() = runBlocking {
        // Without the interceptor: HttpKinApi sets the header itself.
        val api = HttpKinApi(OkHttpClient(), server.url("/v1"), tokens, "kinframe-family/1.4.0 (Android 15)", header)
        server.enqueue(json("""{"id":"u1","display_name":"Anna"}"""))
        api.me()
        val r = server.takeRequest()
        assertEquals(header, r.headers[FamilyAppHeader.NAME])
        assertEquals("kinframe-family/1.4.0 (Android 15)", r.headers["User-Agent"])
        assertEquals(header, api.webSocketRequest().header(FamilyAppHeader.NAME))
    }

    @Test
    fun interceptorAddsHeaderToDiscoveryAndUploads() = runBlocking {
        server.enqueue(json("""{"name":"kinframe"}"""))
        DiscoveryClient(http).fetch(server.url("/"))
        assertEquals(header, server.takeRequest().headers[FamilyAppHeader.NAME])

        server.enqueue(MockResponse.Builder().code(201).addHeader("Location", "/v1/uploads/u1").addHeader("Upload-Offset", "0").build())
        server.enqueue(MockResponse.Builder().code(204).addHeader("Upload-Offset", "3").build())
        TusUploader(http, server.url("/v1/uploads"), tokens, retryDelaysMs = emptyList()).upload(bytes("abc"), mapOf("filename" to "a.jpg"))
        assertEquals(header, server.takeRequest().headers[FamilyAppHeader.NAME])
        assertEquals(header, server.takeRequest().headers[FamilyAppHeader.NAME])
    }

    @Test
    fun updateRequiredIsADistinctSignal() = runBlocking {
        val api = HttpKinApi(http, server.url("/v1"), tokens)
        server.enqueue(json(updateRequired, 426))
        try {
            api.frames(); fail()
        } catch (e: AppUpdateRequiredException) {
            assertEquals(426, e.status)
            assertEquals(ProblemCode.APP_UPDATE_REQUIRED, e.code)
        }
        // The interceptor reports it too (every call through the client, whoever made it), and leaves the body readable.
        assertEquals(ProblemCode.APP_UPDATE_REQUIRED, problems.single()?.code)
        assertEquals(header, server.takeRequest().headers[FamilyAppHeader.NAME])
    }

    @Test
    fun updateRequiredOnUploads() = runBlocking {
        server.enqueue(json(updateRequired, 426))
        try {
            TusUploader(http, server.url("/v1/uploads"), tokens, retryDelaysMs = emptyList()).upload(bytes("abc"), emptyMap())
            fail()
        } catch (e: AppUpdateRequiredException) {
            assertEquals(426, e.status)
        }
        assertEquals(1, problems.size)
    }

    @Test
    fun otherErrorsAreNotReported() = runBlocking {
        val api = HttpKinApi(http, server.url("/v1"), tokens)
        server.enqueue(json("""{"status":404,"code":"not_found"}""", 404))
        runCatching { api.frames() }
        assertTrue(problems.isEmpty())
    }

    private fun bytes(s: String) = object : UploadSource {
        override val length = s.length.toLong()
        override fun open(offset: Long): InputStream = ByteArrayInputStream(s.toByteArray(), offset.toInt(), s.length - offset.toInt())
    }
}
