package app.kinframe.frame.plugins

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * `local_only` (docs/plugins.md §3.1): a plugin setting marked local-only may only reach the family's home network,
 * so a stolen token can't be sent to the internet through it. "Private" means RFC 1918 (10/8, 172.16/12,
 * 192.168/16), IPv4 link-local (169.254/16), IPv6 unique-local (fc00::/7) and link-local (fe80::/10). Loopback,
 * carrier-grade NAT (100.64/10), multicast and public addresses are not.
 */
object LocalAddress {
    fun isPrivate(a: InetAddress): Boolean {
        val b = a.address
        return when (a) {
            is Inet4Address -> isPrivate4(b)
            is Inet6Address -> {
                // IPv4-mapped (::ffff:a.b.c.d): judge the embedded IPv4 address.
                if (b.size == 16 && (0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()) {
                    isPrivate4(b.copyOfRange(12, 16))
                } else {
                    val first = b[0].toInt() and 0xFF
                    val second = b[1].toInt() and 0xFF
                    (first and 0xFE) == 0xFC || (first == 0xFE && (second and 0xC0) == 0x80)
                }
            }
            else -> false
        }
    }

    private fun isPrivate4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xFF
        val o1 = b[1].toInt() and 0xFF
        return o0 == 10 || (o0 == 172 && o1 in 16..31) || (o0 == 192 && o1 == 168) || (o0 == 169 && o1 == 254)
    }

    /**
     * A plain address, host name or URL as typed by the owner ("192.168.1.50", "p1meter.local:80",
     * "http://192.168.1.50/") as an http(s) URL without path, or null when it isn't one.
     */
    fun baseUrl(typed: String): HttpUrl? {
        val t = typed.trim().trimEnd('/')
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val http = t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)
        if (!http && t.contains("://")) return null // another scheme
        val withScheme = if (http) t
        else if (t.contains(':') && !t.contains('.') && t.count { it == ':' } > 1) "http://[$t]" // bare IPv6
        else "http://$t"
        val u = withScheme.toHttpUrlOrNull() ?: return null
        return u.newBuilder().encodedPath("/").query(null).fragment(null).build()
    }

    /** Whether [host] is an IP literal in a private range, without any DNS lookup (null: it's a name). */
    fun literalIsPrivate(host: String): Boolean? {
        val h = host.removePrefix("[").removeSuffix("]")
        val looksIp = h.all { it.isDigit() || it == '.' } && h.count { it == '.' } == 3 || h.contains(':')
        if (!looksIp) return null
        return runCatching { isPrivate(InetAddress.getByName(h)) }.getOrDefault(false)
    }

    /**
     * The DNS for local-only requests: every address a name resolves to must be private, checked at connect time
     * (so a name can't pass the check and then re-resolve to a public address).
     */
    val dns: Dns = Dns { host ->
        val all = Dns.SYSTEM.lookup(host)
        val ok = all.filter { isPrivate(it) }
        if (ok.isEmpty() || ok.size != all.size) throw UnknownHostException("$host is not on the home network")
        ok
    }
}
