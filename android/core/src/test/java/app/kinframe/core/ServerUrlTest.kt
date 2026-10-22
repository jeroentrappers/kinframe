package app.kinframe.core

import app.kinframe.core.api.Discovery
import app.kinframe.core.api.ServerUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    private fun n(s: String) = ServerUrl.normalize(s)?.toString()

    @Test
    fun toleratesCommonTypos() {
        assertEquals("https://frames.example.org/", n("frames.example.org"))
        assertEquals("https://frames.example.org/", n("  https://frames.example.org/  "))
        assertEquals("https://frames.example.org/", n("https//frames.example.org"))
        assertEquals("https://frames.example.org/", n("https:/frames.example.org"))
        assertEquals("https://frames.example.org/", n("HTTPS://Frames.Example.org"))
        assertEquals("https://frames.example.org/", n("https://frames.example.org/.well-known/kinframe"))
        assertEquals("https://frames.example.org/", n("https://frames.example.org/v1/"))
        assertEquals("https://frames.example.org/kin/", n("frames.example.org/kin"))
        assertEquals("http://192.168.1.10:8110/", n("http://192.168.1.10:8110"))
        assertEquals("http://localhost:8110/", n("http://localhost:8110"))
        assertEquals("https://httpbin.org/", n("httpbin.org"))
    }

    @Test
    fun rejectsNonsense() {
        assertNull(n(""))
        assertNull(n("hello"))
        assertNull(n("https://"))
    }

    @Test
    fun apiBaseComesFromDiscovery() {
        val server = "https://frames.example.org/".toHttpUrl()
        assertEquals("https://frames.example.org/v1", ServerUrl.apiBase(server, null).toString())
        assertEquals("https://api.example.org/v1", ServerUrl.apiBase(server, Discovery("x", api = "https://api.example.org/v1/")).toString())
        assertEquals("https://frames.example.org/api/v1", ServerUrl.apiBase(server, Discovery("x", api = "/api/v1")).toString())
        assertEquals("frames.example.org", ServerUrl.display(server))
        assertEquals("localhost:8110", ServerUrl.display("http://localhost:8110/".toHttpUrl()))
    }
}
