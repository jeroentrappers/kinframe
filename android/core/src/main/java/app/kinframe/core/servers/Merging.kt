package app.kinframe.core.servers

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Lists of several servers shown as one (docs/spec/multi-server.md §2.2): pages of each server interleaved by time,
 * and one opaque cursor that holds every server's own cursor.
 */
object Merging {
    private val json = Json
    private val map = MapSerializer(String.serializer(), String.serializer())

    /**
     * A cursor for the next merged page: each account's next cursor; accounts at their end are left out. Null when
     * every account is at its end.
     */
    fun encodeCursor(next: Map<String, String?>): String? {
        val live = next.filterValues { it != null }.mapValues { it.value!! }
        if (live.isEmpty()) return null
        return json.encodeToString(map, live).toByteArray().joinToString("") { "%02x".format(it) }
    }

    /** The accounts' cursors of a merged cursor; null for the first page (every account from its start). */
    fun decodeCursor(cursor: String?): Map<String, String>? {
        if (cursor.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(map, String(cursor.chunked(2).map { it.toInt(16).toByte() }.toByteArray())) }.getOrNull()
    }

    /**
     * Interleaves each account's page (newest first) by [time] (ISO-8601 strings, compared as instants when they parse,
     * else as text). Stable: equal times keep the accounts' order.
     */
    fun <T> interleave(pages: List<List<T>>, time: (T) -> String?): List<T> =
        pages.flatten().sortedWith(compareByDescending<T> { instant(time(it)) })

    private fun instant(s: String?): Long =
        s?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() ?: runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: Long.MIN_VALUE

    /**
     * Whether a push payload's `server` (the sending server's base URL) is this account's server. Scheme, host and
     * port must match, and the path prefix ignoring a trailing slash.
     */
    fun sameServer(a: String?, b: String?): Boolean {
        val x = a?.trim()?.toHttpUrlOrNull() ?: return false
        val y = b?.trim()?.toHttpUrlOrNull() ?: return false
        return sameServer(x, y)
    }

    fun sameServer(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port &&
            a.encodedPath.trimEnd('/') == b.encodedPath.trimEnd('/')
}
