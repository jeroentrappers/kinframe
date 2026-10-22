package app.kinframe.core.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import okio.ByteString.Companion.encodeUtf8

/** Bytes to upload. [open] returns a stream positioned at [offset]; the uploader closes it. */
interface UploadSource {
    val length: Long
    fun open(offset: Long): InputStream
}

/** In-memory source for tests and small files. */
class ByteArraySource(private val bytes: ByteArray) : UploadSource {
    override val length: Long get() = bytes.size.toLong()
    override fun open(offset: Long): InputStream = bytes.inputStream(offset.toInt(), bytes.size - offset.toInt())
}

/** Where an upload stands; persist it to resume after the process dies. */
data class TusState(val uploadUrl: String?, val offset: Long)

sealed interface TusResult {
    /** All bytes are on the server: reference [uploadId] in `POST /v1/sends`. */
    data class Uploaded(val uploadId: String) : TusResult

    /** The server already has this file from the same person (`Kinframe-Existing-Item`): send [itemId] instead. */
    data class Existing(val itemId: String) : TusResult
}

/**
 * The subset of tus 1.0 that kinframe uses (README §5.3): *creation* with `Upload-Metadata`, `PATCH` in chunks,
 * `HEAD` to find the offset after an interruption. Resumes from a saved [TusState] across process deaths.
 *
 * Transient failures (network, 5xx, 409/423 offset conflicts) are retried here with a short back-off, re-reading the
 * offset with `HEAD` first; anything left over surfaces as an exception so a WorkManager job can retry later.
 */
class TusUploader(
    private val http: OkHttpClient,
    private val endpoint: HttpUrl,
    private val tokens: AccessTokenSource,
    private val chunkSize: Int = CHUNK_SIZE,
    private val retryDelaysMs: List<Long> = listOf(0, 1_000, 3_000, 5_000, 10_000),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun upload(
        source: UploadSource,
        metadata: Map<String, String>,
        resume: TusState? = null,
        onState: (TusState) -> Unit = {},
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): TusResult {
        val total = source.length
        var url: HttpUrl? = resume?.uploadUrl?.let { endpoint.resolve(it) ?: it.toHttpUrlOrNull() }
        var offset = 0L
        var failures = 0
        var restarted = false
        // A saved upload (or one interrupted mid-chunk) needs its offset from the server first.
        var needsHead = url != null

        while (true) {
            try {
                val known = url
                if (known != null && needsHead) {
                    val o = headOffset(known)
                    if (o == null) url = null else offset = o
                    needsHead = false
                }
                var current = url
                if (current == null) {
                    val created = create(total, metadata)
                    if (created is Created.Duplicate) {
                        onProgress(total, total)
                        return TusResult.Existing(created.itemId)
                    }
                    current = (created as Created.New).url
                    url = current
                    offset = 0
                    onState(TusState(current.toString(), 0))
                }
                onProgress(offset, total)
                while (offset < total) {
                    offset = patch(current, source, offset, total, onProgress)
                    failures = 0
                    onState(TusState(current.toString(), offset))
                }
                return TusResult.Uploaded(uploadIdFromUrl(current.toString()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: UploadGoneException) {
                // The server forgot the upload (expired after 24 h or cleaned up): start once more from zero.
                if (restarted) throw ApiException(404, e.problem)
                restarted = true
                url = null
                needsHead = false
            } catch (e: TransientUploadException) {
                if (failures >= retryDelaysMs.size) throw e.cause ?: e
                sleep(retryDelaysMs[failures++])
                needsHead = url != null
            }
        }
    }

    // ------------------------------------------------------------------ protocol

    private sealed interface Created {
        data class New(val url: HttpUrl) : Created
        data class Duplicate(val itemId: String) : Created
    }

    private suspend fun create(total: Long, metadata: Map<String, String>): Created {
        val r = authed {
            url(endpoint)
                .header("Tus-Resumable", TUS_VERSION)
                .header("Upload-Length", total.toString())
                .header("Upload-Metadata", encodeMetadata(metadata))
                .post(ByteArray(0).toRequestBody(null))
        }
        r.use {
            if (it.code == 201 || it.code == 200) {
                it.header("Kinframe-Existing-Item")?.takeIf { id -> id.isNotBlank() }?.let { id -> return Created.Duplicate(id) }
                val loc = it.header("Location") ?: throw ApiException(it.code, null, "tus: no Location")
                return Created.New(endpoint.resolve(loc) ?: throw ApiException(it.code, null, "tus: bad Location $loc"))
            }
            throw failure(it)
        }
    }

    /** Current offset of an upload, or null when the server no longer has it. */
    private suspend fun headOffset(url: HttpUrl): Long? {
        val r = try {
            authed { url(url).header("Tus-Resumable", TUS_VERSION).head() }
        } catch (e: IOException) {
            if (e is ApiException) throw e
            throw TransientUploadException(e)
        }
        r.use {
            if (it.code == 404 || it.code == 410 || it.code == 403) return null
            if (!it.isSuccessful) throw failure(it)
            return it.header("Upload-Offset")?.toLongOrNull() ?: throw TransientUploadException(IOException("tus: HEAD without Upload-Offset"))
        }
    }

    private suspend fun patch(url: HttpUrl, source: UploadSource, offset: Long, total: Long, onProgress: (Long, Long) -> Unit): Long {
        val size = minOf(chunkSize.toLong(), total - offset)
        val body = ChunkBody(source, offset, size) { sent -> onProgress(offset + sent, total) }
        val r = try {
            authed {
                url(url).header("Tus-Resumable", TUS_VERSION).header("Upload-Offset", offset.toString()).patch(body)
            }
        } catch (e: IOException) {
            if (e is ApiException) throw e
            throw TransientUploadException(e)
        }
        r.use {
            if (it.code == 204 || it.code == 200) {
                return it.header("Upload-Offset")?.toLongOrNull() ?: (offset + size)
            }
            throw failure(it)
        }
    }

    private fun failure(r: Response): Exception {
        val p = r.problemOrNull()
        return when {
            r.code == 404 || r.code == 410 -> UploadGoneException(p)
            r.code == 409 || r.code == 423 || r.code >= 500 || r.code == 429 -> TransientUploadException(ApiException(r.code, p))
            r.code == 413 -> ApiException(413, p ?: Problem(code = ProblemCode.UPLOAD_TOO_LARGE, status = 413))
            else -> ApiException(r.code, p)
        }
    }

    private suspend fun authed(spec: Request.Builder.() -> Request.Builder): Response {
        var attempt = 0
        while (true) {
            val token = tokens.accessToken(forceRefresh = attempt > 0)
            val r = http.newCall(Request.Builder().spec().header("Authorization", "Bearer $token").build()).await()
            if (r.code != 401) return r
            r.close()
            if (attempt++ >= 1) {
                tokens.onSessionExpired()
                throw SessionExpiredException()
            }
        }
    }

    private class UploadGoneException(val problem: Problem?) : IOException("tus upload gone")
    private class TransientUploadException(cause: Throwable) : IOException(cause.message, cause)

    /** Streams [size] bytes of [source] from [offset], reporting bytes written. */
    private class ChunkBody(
        private val source: UploadSource,
        private val offset: Long,
        private val size: Long,
        private val progress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = OFFSET_OCTET_STREAM
        override fun contentLength(): Long = size
        override fun writeTo(sink: BufferedSink) {
            source.open(offset).use { input ->
                val buf = ByteArray(64 * 1024)
                var left = size
                var sent = 0L
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("source ended early at ${offset + sent}")
                    sink.write(buf, 0, n)
                    left -= n
                    sent += n
                    progress(sent)
                }
            }
        }
    }

    companion object {
        const val TUS_VERSION = "1.0.0"
        const val CHUNK_SIZE = 4 * 1024 * 1024
        private val OFFSET_OCTET_STREAM = "application/offset+octet-stream".toMediaType()

        /** `Upload-Metadata`: comma-separated `key base64(value)` pairs. */
        fun encodeMetadata(metadata: Map<String, String>): String =
            metadata.entries.joinToString(",") { (k, v) -> "$k ${v.encodeUtf8().base64()}" }

        /** tus upload URL → upload id (last path segment). */
        fun uploadIdFromUrl(url: String): String {
            val path = url.substringBefore('?').trimEnd('/')
            return java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
        }
    }
}
