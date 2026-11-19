package app.kinframe.core.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Recognizes the links people paste or share into the app for a photo source (photo-sources.md §6.3–6.5). The server
 * validates again; this only lets the app pick the provider, catch obvious mistakes early and explain them.
 */
sealed interface SourceLink {
    /** The link as it will be sent (trimmed, from the first URL in a shared text). */
    val url: String

    /** `https://<host>[/<prefix>]/share/<key>` or `/s/<slug>`. [privateHost]: a home-network address. */
    data class ImmichShare(override val url: String, val host: String, val key: String, val privateHost: Boolean) : SourceLink

    /** `https://www.icloud.com/sharedalbum/[<lang>/]#<token>`. */
    data class ICloudSharedAlbum(override val url: String, val token: String) : SourceLink

    /** An iCloud Link for a few photos (`/photos/#…`): a different service, rejected with `unsupported_link`. */
    data class ICloudPhotosLink(override val url: String) : SourceLink

    /** An iCloud shared album link without the token after `#` (cut off when copied). */
    data class ICloudIncomplete(override val url: String) : SourceLink

    /** A web link we don't recognize. */
    data class Other(override val url: String) : SourceLink

    val provider: String?
        get() = when (this) {
            is ImmichShare -> PhotoSourceProvider.IMMICH_LINK
            is ICloudSharedAlbum -> PhotoSourceProvider.ICLOUD_LINK
            else -> null
        }
}

/** An Immich server address as typed for `immich_api`. */
sealed interface ServerAddress {
    data class Ok(val url: String, val host: String, val privateHost: Boolean) : ServerAddress
    /** Not a web address. */
    data object Invalid : ServerAddress
    /** `http://` to a public address: the server only accepts `https://` there (§12.3). */
    data object NeedsHttps : ServerAddress
}

object PhotoSourceLinks {
    private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val IMMICH_KEY = Regex("^[A-Za-z0-9_.~-]{4,200}$")
    private val ICLOUD_TOKEN = Regex("^[A-Za-z0-9]{6,64}$")
    private val ICLOUD_HOSTS = setOf("icloud.com", "www.icloud.com")

    /** The first web link in a shared text ("Look at our album: https://…"), trailing punctuation removed. */
    fun firstUrl(text: String?): String? =
        text?.let { URL_IN_TEXT.find(it)?.value?.trimEnd('.', ',', ';', ')', ']', '!', '?') }

    /** Classifies a pasted or shared text; null when it holds no web link. */
    fun parse(text: String?): SourceLink? {
        val raw = firstUrl(text) ?: return null
        val url = raw.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase()
        if (host in ICLOUD_HOSTS) return icloud(raw, url)
        immich(raw, url)?.let { return it }
        return SourceLink.Other(raw)
    }

    private fun icloud(raw: String, url: HttpUrl): SourceLink {
        val segs = url.pathSegments.filter { it.isNotEmpty() }
        val fragment = url.fragment?.trim().orEmpty()
        return when (segs.firstOrNull()?.lowercase()) {
            "sharedalbum" -> if (ICLOUD_TOKEN.matches(fragment)) SourceLink.ICloudSharedAlbum(raw, fragment) else SourceLink.ICloudIncomplete(raw)
            "photos" -> SourceLink.ICloudPhotosLink(raw)
            else -> SourceLink.Other(raw)
        }
    }

    private fun immich(raw: String, url: HttpUrl): SourceLink? {
        val segs = url.pathSegments.filter { it.isNotEmpty() }
        if (segs.size < 2) return null
        val kind = segs[segs.size - 2]
        val key = segs.last()
        if ((kind != "share" && kind != "s") || !IMMICH_KEY.matches(key)) return null
        return SourceLink.ImmichShare(raw, url.host, key, isPrivateHost(url.host))
    }

    /**
     * An Immich server address: a scheme is added when missing (`https://`), a trailing `/api` or slash is dropped
     * (the server tolerates it, the stored value is cleaner). Plain `http://` is only allowed for home-network
     * addresses, as the server's guard does.
     */
    fun serverAddress(input: String): ServerAddress {
        var s = input.trim()
        if (s.isEmpty()) return ServerAddress.Invalid
        if (!s.contains("://")) s = "https://$s"
        val url = s.toHttpUrlOrNull() ?: return ServerAddress.Invalid
        if (url.host.isBlank()) return ServerAddress.Invalid
        val private = isPrivateHost(url.host)
        if (url.scheme == "http" && !private) return ServerAddress.NeedsHttps
        val path = url.encodedPath.trimEnd('/').removeSuffix("/api").trimEnd('/')
        val clean = url.newBuilder().encodedPath(if (path.isEmpty()) "/" else path).query(null).fragment(null).build().toString().trimEnd('/')
        return ServerAddress.Ok(clean, url.host, private)
    }

    /**
     * Whether a host is on a home or private network: RFC 1918, CGNAT (Tailscale), loopback, link-local, IPv6 ULA,
     * `localhost`, `.local`/`.lan`/`.home.arpa`/`.internal` names and single-label names. Used for the "this server
     * can't reach your home network" hint; the server decides.
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']').trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (listOf(".local", ".lan", ".home.arpa", ".internal", ".home").any { h.endsWith(it) }) return true
        ipv4(h)?.let { (a, b) ->
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254)
        }
        if (h.contains(':')) {
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") || h.startsWith("fe9") ||
                h.startsWith("fea") || h.startsWith("feb")
        }
        return !h.contains('.')
    }

    private fun ipv4(h: String): Pair<Int, Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val n = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return null }
        return n[0] to n[1]
    }

    /** The host of a redacted link or server address, for texts like "Can't reach photos.peeters.be". */
    fun hostOf(urlOrHint: String?): String? =
        urlOrHint?.replace("…", "")?.trim()?.let { it.toHttpUrlOrNull()?.host ?: firstUrl(it)?.toHttpUrlOrNull()?.host }
}
