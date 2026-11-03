package app.kinframe.core.api

import app.kinframe.core.auth.CredentialStore
import app.kinframe.core.auth.DeviceCredential
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File

/** Fetches and validates a server's discovery document (`GET /.well-known/kinframe`). */
class DiscoveryClient(private val http: OkHttpClient) {
    suspend fun fetch(server: HttpUrl): Discovery {
        val req = Request.Builder().url(ServerUrl.discoveryUrl(server)).header("Accept", "application/json").build()
        http.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, r.problemOrNull())
            val text = r.body.string()
            val d = KinJson.decodeFromString(Discovery.serializer(), text)
            require(d.name.isNotBlank()) { "not a kinframe server" }
            return d
        }
    }
}

/**
 * Client for the frame's part of the API: `/v1/devices/...` (enrollment, token) and `/v1/device/...`.
 *
 * Token handling follows README §3.2: the access token is refreshed when less than 5 minutes remain, a
 * `401 token_expired` refreshes and retries once, and `401 device_revoked` surfaces as [DeviceRevokedException].
 */
class DeviceApi(
    private val http: OkHttpClient,
    @Volatile var apiBase: HttpUrl,
    private val credentials: CredentialStore,
    private val appVersion: String,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Called whenever any device call is answered with `426 app_update_required` (README §7). */
    @Volatile var onUpdateRequired: (() -> Unit)? = null

    private val tokenLock = Mutex()
    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAt: Long = 0

    private fun endpoint(path: String): HttpUrl = apiBase.newBuilder().addPathSegments(path).build()

    private fun base(url: HttpUrl) = Request.Builder().url(url)
        .header("X-Kinframe-App", appVersion)
        .header("Accept", "application/json")

    private inline fun <reified T> body(value: T, ser: KSerializer<T>) =
        KinJson.encodeToString(ser, value).toRequestBody(JSON_MEDIA)

    private fun <T> Response.decode(ser: KSerializer<T>): T = use {
        if (!isSuccessful) throwForStatus()
        KinJson.decodeFromString(ser, body.string())
    }

    private fun Response.expectOk() = use { if (!isSuccessful) throwForStatus() }

    // ------------------------------------------------------------------ unauthenticated

    suspend fun enroll(request: DeviceEnroll): EnrollResult =
        http.newCall(base(endpoint("devices/enroll")).post(body(request, DeviceEnroll.serializer())).build()).await()
            .decode(EnrollResult.serializer())

    private suspend fun exchange(cred: DeviceCredential): DeviceToken {
        val r = http.newCall(
            base(endpoint("devices/token"))
                .post(body(DeviceTokenRequest(cred.deviceId, cred.secret), DeviceTokenRequest.serializer())).build(),
        ).await()
        if (r.code == 401 || r.code == 404) {
            // The secret is no longer accepted: the frame was removed or replaced.
            throw DeviceRevokedException(r.use { it.problemOrNull() })
        }
        return r.decode(DeviceToken.serializer())
    }

    // ------------------------------------------------------------------ token

    /** Returns a valid access token, refreshing it when less than 5 minutes remain. */
    suspend fun accessToken(forceRefresh: Boolean = false): String = tokenLock.withLock {
        val now = monotonicMs()
        val current = token
        if (!forceRefresh && current != null && tokenExpiresAt - now > REFRESH_MARGIN_MS) return current
        val cred = credentials.load() ?: throw DeviceRevokedException(null)
        val t = exchange(cred)
        token = t.accessToken
        tokenExpiresAt = now + t.expiresIn * 1000
        t.accessToken
    }

    fun forgetToken() {
        token = null
        tokenExpiresAt = 0
    }

    private suspend fun authed(spec: Request.Builder.() -> Request.Builder): Response {
        var attempt = 0
        while (true) {
            val t = accessToken(forceRefresh = attempt > 0)
            val rb = Request.Builder().header("X-Kinframe-App", appVersion).header("Accept", "application/json")
            val request = rb.spec().header("Authorization", "Bearer $t").build()
            val r = http.newCall(request).await()
            if (r.code == 426) onUpdateRequired?.invoke()
            if (r.code != 401) return r
            val p = r.use { it.problemOrNull() }
            if (p?.code == ProblemCode.DEVICE_REVOKED) throw DeviceRevokedException(p)
            if (attempt++ >= 1) throw ApiException(401, p)
            forgetToken()
        }
    }

    // ------------------------------------------------------------------ device endpoints

    suspend fun state(): DeviceState = authed { url(endpoint("device/state")).get() }.decode(DeviceState.serializer())

    suspend fun code(): FrameCode = authed { url(endpoint("device/code")).post(ByteArray(0).toRequestBody(null)) }
        .decode(FrameCode.serializer())

    suspend fun changes(since: Long, limit: Int = 500): ChangesPage = authed {
        url(endpoint("device/changes").newBuilder().addQueryParameter("since", since.toString())
            .addQueryParameter("limit", limit.toString()).build()).get()
    }.decode(ChangesPage.serializer())

    suspend fun acks(request: AckRequest) = authed { url(endpoint("device/acks")).post(body(request, AckRequest.serializer())) }.expectOk()

    suspend fun heartbeat(hb: Heartbeat) = authed { url(endpoint("device/heartbeat")).post(body(hb, Heartbeat.serializer())) }.expectOk()

    /** `PATCH /device/settings` with `If-Match`; returns the full settings document. Throws 412 as [ApiException]. */
    suspend fun patchSettings(version: Int, patch: JsonObject): JsonObject = authed {
        url(endpoint("device/settings")).header("If-Match", "\"$version\"")
            .patch(KinJson.encodeToString(JsonObject.serializer(), patch).toRequestBody(MERGE_PATCH_MEDIA))
    }.use { r ->
        if (!r.isSuccessful) r.throwForStatus()
        KinJson.parseToJsonElement(r.body.string()).jsonObject
    }

    /** `PUT /device/plugins/catalog`: the plugins this frame can run (docs/plugins.md §6). */
    suspend fun putPluginCatalog(catalog: JsonObject) = authed {
        url(endpoint("device/plugins/catalog")).put(KinJson.encodeToString(JsonObject.serializer(), catalog).toRequestBody(JSON_MEDIA))
    }.expectOk()

    /** `PUT /device/plugins/status`: instance errors and what the frame discovered for the settings forms (§6.2). */
    suspend fun putPluginStatus(status: JsonObject) = authed {
        url(endpoint("device/plugins/status")).put(KinJson.encodeToString(JsonObject.serializer(), status).toRequestBody(JSON_MEDIA))
    }.expectOk()

    /** `GET /device/plugins/registry`: the community registry's index as signed (docs/plugins.md §7.1); null when none. */
    suspend fun pluginRegistry(): PluginRegistryDto? = authed { url(endpoint("device/plugins/registry")).get() }.use { r ->
        if (r.code == 204) return@use null
        if (!r.isSuccessful) r.throwForStatus()
        KinJson.decodeFromString(PluginRegistryDto.serializer(), r.body.string())
    }

    /** `GET /device/plugins/bundles/{file}`: a bundle's bytes, at most [maxBytes]. */
    suspend fun pluginBundle(file: String, maxBytes: Int): ByteArray = authed { url(endpoint("device/plugins/bundles/$file")).get() }.use { r ->
        if (!r.isSuccessful) r.throwForStatus()
        val source = r.body.source()
        source.request(maxBytes + 1L)
        if (source.buffer.size > maxBytes) throw java.io.IOException("plugin bundle larger than $maxBytes bytes")
        source.buffer.readByteArray()
    }

    suspend fun patchPlacement(placementId: String, patch: JsonObject) = authed {
        url(endpoint("device/placements/$placementId"))
            .patch(KinJson.encodeToString(JsonObject.serializer(), patch).toRequestBody(JSON_MEDIA))
    }.expectOk()

    suspend fun setReaction(placementId: String, reaction: String?) = authed {
        val b: JsonElement = if (reaction == null) JsonNull else JsonPrimitive(reaction)
        url(endpoint("device/placements/$placementId/reaction"))
            .put(KinJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("reaction", b) }).toRequestBody(JSON_MEDIA))
    }.expectOk()

    suspend fun sendReply(placementId: String, preset: String) = authed {
        url(endpoint("device/placements/$placementId/replies"))
            .post(KinJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("preset", JsonPrimitive(preset)) }).toRequestBody(JSON_MEDIA))
    }.expectOk()

    /** `GET /device/join-requests`: requests waiting for a decision on the frame. */
    suspend fun joinRequests(): List<JoinRequest> = authed { url(endpoint("device/join-requests")).get() }
        .decode(kotlinx.serialization.builtins.ListSerializer(JoinRequest.serializer()))

    /** `POST /device/join-requests/{id}` with `approve` or `deny`; 404 means someone already decided. */
    suspend fun decideJoin(requestId: String, approve: Boolean) = authed {
        url(endpoint("device/join-requests/$requestId")).post(
            KinJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("decision", JsonPrimitive(if (approve) "approve" else "deny")) })
                .toRequestBody(JSON_MEDIA),
        )
    }.expectOk()

    // ------------------------------------------------------------------ calendar (README §5.8)

    /** `GET /device/invitations`: events sent to the frame, waiting for accept or decline (oldest first). */
    suspend fun invitations(): List<EventInvitation> = authed { url(endpoint("device/invitations")).get() }
        .decode(kotlinx.serialization.builtins.ListSerializer(EventInvitation.serializer()))

    /** `POST /device/invitations/{id}`: final; `409 already_decided` when it was decided or withdrawn before. */
    suspend fun decideInvitation(invitationId: String, accept: Boolean) = authed {
        url(endpoint("device/invitations/$invitationId")).post(
            KinJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("decision", JsonPrimitive(if (accept) "accept" else "decline")) })
                .toRequestBody(JSON_MEDIA),
        )
    }.expectOk()

    /** The invitation's photo (WebP); fetch it with [media]. */
    fun invitationPhotoUrl(invitationId: String): HttpUrl = endpoint("device/invitations/$invitationId/photo")

    /** `PUT` / `DELETE /device/countdowns/{eventId}`: the countdown toggle in the event details. */
    suspend fun setCountdown(eventId: String, on: Boolean) = authed {
        val u = url(endpoint("device/countdowns/$eventId"))
        if (on) u.put(ByteArray(0).toRequestBody(null)) else u.delete()
    }.expectOk()

    /** `POST /device/calendar/sync`: "Sync now" for all of the frame's calendars (the server does the work). */
    suspend fun syncCalendars() = authed { url(endpoint("device/calendar/sync")).post(ByteArray(0).toRequestBody(null)) }.expectOk()

    /**
     * `GET /device/update`: the newest release for [channel] (null = the frame's setting) that is newer than this
     * app, skipping [skip]; null when up to date (204).
     */
    suspend fun checkUpdate(channel: String? = null, skip: Collection<Int> = emptyList()): FrameUpdate? = authed {
        url(endpoint("device/update").newBuilder().apply {
            if (channel != null) addQueryParameter("channel", channel)
            if (skip.isNotEmpty()) addQueryParameter("skip", skip.joinToString(","))
        }.build()).get()
    }.use { r ->
        if (r.code == 204) return@use null
        if (!r.isSuccessful) r.throwForStatus()
        KinJson.decodeFromString(FrameUpdate.serializer(), r.body.string())
    }

    /**
     * `POST /device/imports`: uploads one file from SD/USB so it joins the library (the frame is the sender). The
     * body streams from [file]; [onProgress] gets the bytes sent so far. `200` means the frame already had the file.
     */
    suspend fun importFile(
        file: File,
        filename: String,
        mime: String?,
        capturedAt: String? = null,
        caption: String? = null,
        origin: ImportOrigin? = null,
        onProgress: ((Long) -> Unit)? = null,
    ): ImportResult = authed {
        val u = endpoint("device/imports").newBuilder().apply {
            if (!caption.isNullOrBlank()) addQueryParameter("caption", caption.take(280))
            if (origin != null) {
                addQueryParameter("source", origin.source)
                addQueryParameter("source_key", origin.key.take(200))
                origin.senderName?.takeIf { it.isNotBlank() }?.let { addQueryParameter("sender_name", it.take(100)) }
                origin.receivedAt?.let { addQueryParameter("received_at", it) }
                origin.fit?.let { addQueryParameter("fit", it) }
                origin.focal?.let {
                    addQueryParameter("focal_x", it.x.coerceIn(0.0, 1.0).toString())
                    addQueryParameter("focal_y", it.y.coerceIn(0.0, 1.0).toString())
                }
                if (origin.hidden) addQueryParameter("hidden", "true")
                if (origin.rotation != 0) addQueryParameter("rotation", origin.rotation.toString())
            }
        }.build()
        url(u).header("X-Filename", asciiHeader(filename)).apply { if (capturedAt != null) header("X-Captured-At", capturedAt) }
            .post(FileBody(file, (mime ?: "application/octet-stream").toMediaTypeOrNull(), onProgress))
    }.use { r ->
        if (!r.isSuccessful) r.throwForStatus()
        ImportResult(KinJson.decodeFromString(Placement.serializer(), r.body.string()), duplicate = r.code == 200)
    }

    /** `POST /device/imports/check` with source keys: which of them this frame imported before (batches of 500). */
    suspend fun importedKeys(source: String, keys: Collection<String>): Set<String> {
        val out = HashSet<String>()
        for (chunk in keys.distinct().chunked(500)) {
            out += authed { url(endpoint("device/imports/check")).post(body(ImportCheckRequest(source = source, sourceKeys = chunk), ImportCheckRequest.serializer())) }
                .decode(ImportCheckResponse.serializer()).knownSourceKeys
        }
        return out
    }

    /** `POST /device/imports/check`: which of these sha256 hashes the frame already has (asked in batches of 500). */
    suspend fun importCheck(hashes: Collection<String>): Set<String> {
        val out = HashSet<String>()
        for (chunk in hashes.map { it.lowercase() }.distinct().chunked(500)) {
            out += authed { url(endpoint("device/imports/check")).post(body(ImportCheckRequest(chunk), ImportCheckRequest.serializer())) }
                .decode(ImportCheckResponse.serializer()).known.map { it.lowercase() }
        }
        return out
    }

    /**
     * Raw media request (caller closes the response). Sends the device token only to the API's own origin, so
     * renditions served from a CDN with signed URLs work too.
     */
    suspend fun media(target: HttpUrl, rangeFrom: Long = 0, ifRange: String? = null): Response {
        val sameOrigin = target.scheme == apiBase.scheme && target.host == apiBase.host && target.port == apiBase.port
        val decorate: Request.Builder.() -> Request.Builder = {
            url(target).get().apply {
                header("Accept", "*/*")
                header("Accept-Encoding", "identity")
                if (rangeFrom > 0) {
                    header("Range", "bytes=$rangeFrom-")
                    if (ifRange != null) header("If-Range", ifRange)
                }
            }
        }
        return if (sameOrigin) authed(decorate)
        else http.newCall(Request.Builder().header("X-Kinframe-App", appVersion).decorate().build()).await()
    }

    /** Media URL for a placement (`/device/media/{id}?variant=`). */
    fun mediaUrl(placementId: String, variant: String? = null): HttpUrl =
        endpoint("device/media/$placementId").newBuilder().apply { if (variant != null) addQueryParameter("variant", variant) }.build()

    /** Resolves a URL from the change feed (absolute, or relative to the server). */
    fun resolve(url: String): HttpUrl? = apiBase.resolve(url)

    /** Request for the device websocket, `GET /device/ws` with a fresh token. */
    suspend fun webSocketRequest(): Request {
        val t = accessToken()
        return Request.Builder().url(endpoint("device/ws")).header("Authorization", "Bearer $t")
            .header("X-Kinframe-App", appVersion).build()
    }

    companion object {
        const val REFRESH_MARGIN_MS = 5 * 60 * 1000L

        /** Header values must be ASCII in OkHttp: keep printable ASCII, replace the rest. */
        fun asciiHeader(v: String): String =
            v.map { if (it in ' '..'~') it else '_' }.joinToString("").trim().ifEmpty { "import" }
    }
}

/** Streams a file as a request body (repeatable, so a token refresh can resend it), reporting bytes written. */
private class FileBody(private val file: File, private val type: MediaType?, private val onProgress: ((Long) -> Unit)?) : RequestBody() {
    override fun contentType(): MediaType? = type
    override fun contentLength(): Long = file.length()
    override fun writeTo(sink: BufferedSink) {
        file.source().use { src ->
            var total = 0L
            val buf = okio.Buffer()
            while (true) {
                val n = src.read(buf, 64 * 1024L)
                if (n == -1L) break
                sink.write(buf, n)
                total += n
                onProgress?.invoke(total)
            }
        }
    }
}

/** The registry index (base64 of the signed bytes) and its signature. */
@kotlinx.serialization.Serializable
data class PluginRegistryDto(val index: String, val signature: String, val sequence: Long)
