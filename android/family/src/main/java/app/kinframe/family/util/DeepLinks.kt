package app.kinframe.family.util

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Links the app opens: a frame's QR code (`https://<server>/f/<code>`) and invite links (`/i/<token>`). */
sealed interface DeepLink {
    /** The server the link belongs to (with a trailing slash, possibly below a path prefix). */
    val server: HttpUrl

    data class FrameCode(override val server: HttpUrl, val code: String) : DeepLink
    data class Invite(override val server: HttpUrl, val token: String) : DeepLink
}

object DeepLinks {
    private val CODE = Regex("^[0-9]{8}$")
    private val TOKEN = Regex("^[A-Za-z0-9_-]{22,64}$")

    fun parse(link: String?): DeepLink? {
        val url = link?.trim()?.toHttpUrlOrNull() ?: return null
        val segs = url.pathSegments.filter { it.isNotEmpty() }
        if (segs.size < 2) return null
        val kind = segs[segs.size - 2]
        val value = segs.last()
        val server = url.newBuilder().encodedPath("/").apply {
            segs.dropLast(2).forEach { addPathSegment(it) }
            if (segs.size > 2) addPathSegment("")
        }.query(null).fragment(null).build()
        return when {
            kind == "f" && CODE.matches(value) -> DeepLink.FrameCode(server, value)
            kind == "f" -> FrameCodes.digits(value).takeIf { it.length == FrameCodes.LENGTH }?.let { DeepLink.FrameCode(server, it) }
            kind == "i" && TOKEN.matches(value) -> DeepLink.Invite(server, value)
            else -> null
        }
    }

    /** Whether two server base URLs are the same server (scheme, host, port and path). */
    fun sameServer(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port &&
            a.encodedPath.trimEnd('/') == b.encodedPath.trimEnd('/')
}
