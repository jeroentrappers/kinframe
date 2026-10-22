package app.kinframe.core

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.BulkAction
import app.kinframe.core.api.HttpKinApi
import app.kinframe.core.api.InviteCreate
import app.kinframe.core.api.RedeemStatus
import app.kinframe.core.api.SendCreate
import app.kinframe.core.api.SendItemCreate
import app.kinframe.core.api.SessionExpiredException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class UserApiTest {
    private val server = MockWebServer()
    private var refreshes = 0
    private var expired = false
    private val tokens = object : AccessTokenSource {
        override suspend fun accessToken(forceRefresh: Boolean): String {
            if (forceRefresh) refreshes++
            return "t$refreshes"
        }
        override fun onSessionExpired() { expired = true }
    }
    private lateinit var api: HttpKinApi

    @Before
    fun setUp() {
        server.start()
        api = HttpKinApi(OkHttpClient(), server.url("/v1"), tokens, acceptLanguage = { "nl-BE" })
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    private val frameJson = """{"id":"f1","name":"Oma's frame","role":"owner","permissions":{"send":true},"online":true,"future_field":1}"""

    @Test
    fun sendsBearerAndLanguageAndParsesFrames() = runBlocking {
        server.enqueue(json("[$frameJson]"))
        val frames = api.frames()
        assertEquals("Oma's frame", frames.single().name)
        assertTrue(frames.single().isManager)
        val r = server.takeRequest()
        assertEquals("/v1/frames", r.url.encodedPath)
        assertEquals("Bearer t0", r.headers["Authorization"])
        assertEquals("nl-BE", r.headers["Accept-Language"])
    }

    @Test
    fun refreshesOnceOn401ThenExpiresTheSession() = runBlocking {
        server.enqueue(json("""{"type":"x","title":"t","status":401,"code":"token_expired"}""", 401))
        server.enqueue(json("""{"id":"u1","display_name":"Anna"}"""))
        assertEquals("Anna", api.me().displayName)
        assertEquals(1, refreshes)
        assertFalse(expired)

        server.enqueue(json("""{"type":"x","title":"t","status":401,"code":"unauthorized"}""", 401))
        server.enqueue(json("""{"type":"x","title":"t","status":401,"code":"unauthorized"}""", 401))
        try {
            api.me(); fail()
        } catch (e: SessionExpiredException) {
            assertTrue(expired)
        }
    }

    @Test
    fun redeemCarriesIdempotencyKeyAndMapsProblems() = runBlocking {
        server.enqueue(json("""{"status":"claimed","frame":$frameJson}"""))
        assertEquals(RedeemStatus.CLAIMED, api.redeem("12345678", "key-1").status)
        val r = server.takeRequest()
        assertEquals("key-1", r.headers["Idempotency-Key"])
        assertEquals("""{"code":"12345678"}""", r.body!!.utf8())

        server.enqueue(json("""{"type":"x","title":"This code has expired","status":410,"code":"code_expired"}""", 410))
        try {
            api.redeem("12345678", "key-2"); fail()
        } catch (e: ApiException) {
            assertEquals(410, e.status)
            assertEquals("code_expired", e.code)
        }
    }

    @Test
    fun settingsPatchUsesIfMatchAndMergePatch() = runBlocking {
        server.enqueue(json("""{"version":8,"slideshow":{"interval_s":60},"unknown":{"a":1}}"""))
        val doc = api.patchSettings("f1", 7, buildJsonObject { put("slideshow", buildJsonObject { put("interval_s", JsonPrimitive(60)) }) })
        assertEquals(8, doc.version)
        assertEquals(60, doc.typed.slideshow.intervalS)
        assertTrue(doc.raw.containsKey("unknown"))
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals("\"7\"", r.headers["If-Match"])
        assertTrue(r.headers["Content-Type"]!!.startsWith("application/merge-patch+json"))
    }

    @Test
    fun clearingACaptionSendsExplicitNull() = runBlocking {
        server.enqueue(json("""{"id":"i1","kind":"photo","status":"ready","sender_id":"u1","created_at":"2026-10-22T10:00:00Z"}"""))
        api.updateItem("i1", buildJsonObject { put("caption", JsonNull) })
        assertEquals("""{"caption":null}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun bulkKeepsTheColonPath() = runBlocking {
        server.enqueue(json("""{"ok":["p1"],"failed":[{"id":"p2","code":"forbidden"}]}"""))
        val r = api.bulkPlacements("f1", listOf("p1", "p2"), BulkAction.HIDE)
        assertEquals(listOf("p1"), r.ok)
        assertEquals("/v1/frames/f1/placements:bulk", server.takeRequest().url.encodedPath)
    }

    @Test
    fun unlimitedInviteSendsNullMaxUses() = runBlocking {
        server.enqueue(json("""{"id":"i","token":"tok","url":"https://x/i/tok","expires_at":"2026-10-29T10:00:00Z"}"""))
        api.createInvite("f1", InviteCreate(maxUses = null, expiresInHours = 24), "k")
        assertEquals("""{"max_uses":null,"expires_in_hours":24}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun createSendBody() = runBlocking {
        server.enqueue(json("""{"id":"s1","created_at":"2026-10-22T10:00:00Z","items":[],"frames":[]}""", 202))
        api.createSend(SendCreate(listOf("f1"), listOf(SendItemCreate("up1", caption = "Hi", fit = "fill"))), "idem")
        val r = server.takeRequest()
        assertEquals("idem", r.headers["Idempotency-Key"])
        assertEquals("""{"frame_ids":["f1"],"items":[{"upload_id":"up1","caption":"Hi","fit":"fill"}]}""", r.body!!.utf8())
    }

    @Test
    fun webSocketRequestPutsTokenInQuery() = runBlocking {
        val req = api.webSocketRequest()
        assertEquals("/v1/ws", req.url.encodedPath)
        assertEquals("t0", req.url.queryParameter("access_token"))
    }
}
