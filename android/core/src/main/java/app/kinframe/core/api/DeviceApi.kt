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
import okhttp3.Response

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
    }
}
