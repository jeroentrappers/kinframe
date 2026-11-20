package app.kinframe.core.crash

import android.content.ContextWrapper
import io.sentry.Attachment
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonSerializer
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.Device
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.Response
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportsTest {
    private val setup = CrashReports.Setup("http://key@localhost:9/1", "family-android", "1.3.0", 412, mapOf("flavor" to "play"))

    private fun event() = SentryEvent(IllegalStateException("boom")).apply {
        user = User().apply { id = "installation-id"; email = "oma@example.com"; ipAddress = "203.0.113.7"; username = "oma" }
        serverName = "pixel-of-oma"
        request = Request().apply {
            method = "POST"
            url = "https://kinframe.example/v1/uploads/abc?token=s3cret&name=IMG_0001.jpg#frag"
            queryString = "token=s3cret&name=IMG_0001.jpg"
            headers = mapOf("Authorization" to "Bearer s3cret", "Cookie" to "session=s3cret")
            cookies = "session=s3cret"
            data = "{\"caption\":\"Happy birthday Oma\"}"
            envs = mapOf("REMOTE_ADDR" to "203.0.113.7")
            fragment = "frag"
        }
        contexts.setDevice(Device().apply { id = "installation-id"; model = "Pixel 8" })
        contexts.setResponse(Response().apply {
            statusCode = 500
            headers = mapOf("Set-Cookie" to "session=s3cret")
            cookies = "session=s3cret"
            data = "secret body"
        })
        message = Message().apply {
            message = "GET https://kinframe.example/v1/me?access_token=s3cret failed"
            formatted = "GET /v1/me?access_token=s3cret failed"
        }
        exceptions = listOf(SentryException().apply {
            type = "IOException"
            value = "unexpected end of stream on https://kinframe.example/v1/feeds?cursor=abc&token=s3cret"
        })
        breadcrumbs = mutableListOf(Breadcrumb.http("https://kinframe.example/v1/items?sig=s3cret", "GET", 200).apply {
            setData("request_headers", mapOf("Authorization" to "Bearer s3cret"))
            setData("http.query", "sig=s3cret")
            setData("request_body", "secret body")
            setData("response_body_size", 1234L)
        })
    }

    @Test
    fun scrubbingDropsUserIpQueryStringsHeadersAndBodies() {
        val hint = Hint.withAttachment(Attachment(byteArrayOf(1, 2, 3), "screenshot.png"))
        val e = CrashReports.scrub(event(), hint)

        assertNull("no user, so no id, email or IP", e.user)
        assertNull(e.serverName)
        assertNull("no installation id", e.contexts.device?.id)
        assertEquals("Pixel 8", e.contexts.device?.model)

        val r = e.request!!
        assertEquals("POST", r.method)
        assertEquals("https://kinframe.example/v1/uploads/abc", r.url)
        assertNull(r.queryString)
        assertNull(r.headers)
        assertNull(r.cookies)
        assertNull(r.data)
        assertNull(r.envs)
        assertNull(r.fragment)

        val response = e.contexts.response!!
        assertEquals(500, response.statusCode)
        assertNull(response.headers)
        assertNull(response.cookies)
        assertNull(response.data)

        assertEquals("GET https://kinframe.example/v1/me failed", e.message!!.message)
        assertEquals("GET /v1/me failed", e.message!!.formatted)
        assertEquals("unexpected end of stream on https://kinframe.example/v1/feeds", e.exceptions!!.single().value)

        val crumb = e.breadcrumbs!!.single()
        assertEquals("https://kinframe.example/v1/items", crumb.getData("url"))
        assertEquals("GET", crumb.getData("method"))
        assertEquals(200, crumb.getData("status_code"))
        assertEquals(1234L, crumb.getData("response_body_size"))
        assertNull(crumb.getData("request_headers"))
        assertNull(crumb.getData("http.query"))
        assertNull(crumb.getData("request_body"))

        assertTrue("no attachments", hint.attachments.isEmpty())
        val json = java.io.StringWriter().also { JsonSerializer(SentryOptions()).serialize(e, it) }.toString()
        assertTrue(json, json.contains("unexpected end of stream"))
        for (secret in listOf("s3cret", "oma@example.com", "203.0.113.7", "installation-id", "IMG_0001", "Happy birthday")) {
            assertFalse("$secret in $json", json.contains(secret))
        }
    }

    @Test
    fun breadcrumbsAreScrubbedBeforeTheyAreKept() {
        val crumb = CrashReports.scrub(Breadcrumb.http("http://h/p?q=1", "PUT").apply { setData("Cookie", "c=1") })
        assertEquals("http://h/p", crumb.getData("url"))
        assertNull(crumb.getData("Cookie"))
    }

    @Test
    fun textKeepsWhatIsNotAQueryString() {
        assertEquals("Expected ? but got 3", CrashReports.withoutQueries("Expected ? but got 3"))
        assertEquals("open /data/user/0/x/files/a.jpg failed", CrashReports.withoutQueries("open /data/user/0/x/files/a.jpg failed"))
        assertEquals("see content://media/external/images/1", CrashReports.withoutQueries("see content://media/external/images/1?x=y"))
    }

    @Test
    fun optionsFollowThePrivacyRules() {
        val o = SentryAndroidOptions()
        CrashReports.configure(o, setup)
        assertEquals("http://key@localhost:9/1", o.dsn)
        assertEquals("family-android@1.3.0+412", o.release)
        assertEquals("production", o.environment)
        assertEquals("play", o.tags["flavor"])
        assertFalse(o.isSendDefaultPii)
        assertFalse(o.isAttachScreenshot)
        assertFalse(o.isAttachViewHierarchy)
        assertFalse(o.isEnableAutoSessionTracking)
        assertEquals(0.0, o.tracesSampleRate!!, 0.0)
        assertEquals(0.0, o.profileSessionSampleRate!!, 0.0)
        assertEquals(0.0, o.sessionReplay.sessionSampleRate!!, 0.0)
        assertEquals(0.0, o.sessionReplay.onErrorSampleRate!!, 0.0)
        assertTrue(o.isAnrEnabled)

        val sent = o.beforeSend!!.execute(event(), Hint())
        assertNotNull(sent)
        assertNull(sent!!.user)
        val crumb = Breadcrumb.http("https://h/p?token=s3cret", "GET")
        assertSame(crumb, o.beforeBreadcrumb!!.execute(crumb, Hint()))
        assertEquals("https://h/p", crumb.getData("url"))
    }

    @Test
    fun withoutADsnNothingStarts() {
        assertFalse(CrashReports.start(ContextWrapper(null), CrashReports.Setup("", "frame-android", "0.1.0", 1)))
        assertFalse(CrashReports.start(ContextWrapper(null), CrashReports.Setup("  ", "frame-android", "0.1.0", 1)))
        assertFalse(Sentry.isEnabled())
    }
}
