package app.kinframe.core.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Parsing of server addresses typed by people (product spec 4.8: "common typos tolerated"). */
object ServerUrl {
    const val DEFAULT = "https://kinframe.appmire.be"

    /**
     * Normalises what someone typed into a base URL, or null if it can't be a server address.
     * Tolerates spaces, missing scheme, `https//`, `https:/`, upper case host, trailing slashes and a pasted
     * `/.well-known/kinframe` or `/v1` suffix.
     */
    fun normalize(input: String): HttpUrl? {
        var s = input.trim().replace(" ", "").replace(",", ".").trimEnd('/')
        if (s.isEmpty()) return null
        s = s.replace(Regex("^(https?)(?:[:;]/{0,2}|/{1,2})(?=[^/])", RegexOption.IGNORE_CASE)) { m ->
            m.groupValues[1].lowercase() + "://"
        }
        if (!s.contains("://")) s = "https://$s"
        val url = s.toHttpUrlOrNull() ?: return null
        if (!url.host.contains('.') && url.host != "localhost" && !url.host.all { it.isDigit() || it == ':' }) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segments.takeLast(2) == listOf(".well-known", "kinframe")) repeat(2) { segments.removeAt(segments.size - 1) }
        if (segments.lastOrNull() == "v1") segments.removeAt(segments.size - 1)
        // Keep a trailing slash so relative references resolve below the server's path.
        return url.newBuilder().encodedPath("/").apply { segments.forEach { addPathSegment(it) }; if (segments.isNotEmpty()) addPathSegment("") }
            .query(null).fragment(null).build()
    }

    fun discoveryUrl(base: HttpUrl): HttpUrl = base.newBuilder().addPathSegments(".well-known/kinframe").build()

    /** The API base from discovery (`api`), falling back to `<server>/v1`. Always without trailing slash. */
    fun apiBase(server: HttpUrl, discovery: Discovery?): HttpUrl {
        val fromDiscovery = discovery?.api?.takeIf { it.isNotBlank() }?.let { server.resolve(it) }
        val base = fromDiscovery ?: server.newBuilder().addPathSegment("v1").build()
        val segs = base.pathSegments.filter { it.isNotEmpty() }
        return base.newBuilder().encodedPath("/").apply { segs.forEach { addPathSegment(it) } }.build()
    }

    /** "kinframe.appmire.be" or "frames.example.org:8443" for display. */
    fun display(url: HttpUrl): String = buildString {
        append(url.host)
        if (url.port != HttpUrl.defaultPort(url.scheme)) append(':').append(url.port)
        url.pathSegments.filter { it.isNotEmpty() }.forEach { append('/').append(it) }
    }
}
