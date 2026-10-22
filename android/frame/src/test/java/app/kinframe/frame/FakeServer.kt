package app.kinframe.frame

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Routes MockWebServer requests like the kinframe device API (token, change pages, media with Range). */
class FakeServer : Dispatcher() {
    /** since → JSON page body (or an HTTP status to fail with). */
    val pages = mutableMapOf<Long, String>()
    val failSince = mutableMapOf<Long, Int>()
    val media = mutableMapOf<String, ByteArray>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    var settingsStatus = 200

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.url.encodedPath
        return when {
            path.endsWith("/devices/token") -> json("""{"access_token":"tok","expires_in":3600}""")
            path.endsWith("/device/changes") -> {
                val since = request.url.queryParameter("since")!!.toLong()
                failSince[since]?.let { return MockResponse.Builder().code(it).build() }
                json(pages[since] ?: """{"changes":[],"next_seq":$since,"has_more":false}""")
            }
            // Live photo clips are served as "<id>?motion" (`?variant=motion`).
            path.contains("/device/media/") -> mediaResponse(request, path.substringAfterLast('/') + if (request.url.queryParameter("variant") == "motion") "?motion" else "")
            path.endsWith("/device/acks") -> MockResponse.Builder().code(204).build()
            path.endsWith("/device/settings") ->
                if (settingsStatus == 200) json("""{"version":9,"slideshow":{"interval_s":10}}""")
                else MockResponse.Builder().code(settingsStatus).addHeader("Content-Type", "application/problem+json")
                    .body("""{"type":"x","title":"stale","status":412,"code":"version_conflict"}""").build()
            else -> MockResponse.Builder().code(204).build()
        }
    }

    private fun mediaResponse(r: RecordedRequest, id: String): MockResponse {
        val bytes = media[id] ?: return MockResponse.Builder().code(404).build()
        val range = r.headers["Range"]
        if (range != null) {
            val from = range.removePrefix("bytes=").substringBefore('-').toInt()
            return MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                .body(Buffer().write(bytes, from, bytes.size - from)).build()
        }
        return MockResponse.Builder().code(200).body(Buffer().write(bytes)).build()
    }

    private fun json(s: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(s).build()

    companion object {
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun placement(id: String, bytes: ByteArray?, received: String = "2026-10-20T10:00:00Z", extra: String = "", sha: String? = null): String {
            val media = if (bytes == null) "" else
                ""","media":{"url":"/v1/device/media/$id","sha256":"${sha ?: sha(bytes)}","bytes":${bytes.size},"mime":"image/webp","width":1920,"height":1280}"""
            return """{"id":"$id","frame_id":"f","item":{"id":"i-$id","kind":"photo","sender_id":"u1","sender_name":"Ann","caption":"hi $id","created_at":"$received"},
                "status":"ready","hidden":false,"trashed":false,"received_at":"$received"$extra$media}"""
        }

        fun page(nextSeq: Long, hasMore: Boolean, vararg changes: String) =
            """{"changes":[${changes.joinToString(",")}],"next_seq":$nextSeq,"has_more":$hasMore}"""
    }
}
