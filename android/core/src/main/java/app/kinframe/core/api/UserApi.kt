package app.kinframe.core.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Where the API client gets a person's access token (Logto or a dev token). */
interface AccessTokenSource {
    /** A valid access token for the API resource; [forceRefresh] after the server rejected the last one. */
    suspend fun accessToken(forceRefresh: Boolean = false): String

    /** The server rejected a fresh token too: the session is over and the person must sign in again. */
    fun onSessionExpired() {}
}

/** Signing in is needed again (no token, or the server rejected a fresh one). */
class SessionExpiredException(problem: Problem? = null) : ApiException(401, problem, "session expired")

/**
 * The person-facing API (`/v1/me`, frames, sends, library, settings…) used by the family app.
 * An interface so view models can be tested with fakes; [HttpKinApi] is the real client.
 */
interface KinApi {
    suspend fun me(): User
    suspend fun updateMe(displayName: String? = null, locale: String? = null): User
    suspend fun deleteMe(removeItems: Boolean)
    suspend fun registerPush(endpoint: PushEndpointCreate): PushEndpoint
    suspend fun deletePush(endpointId: String)

    suspend fun frames(): List<Frame>
    suspend fun frame(frameId: String): Frame
    /** `PATCH /frames/{id}`: name, location, default_permissions, approve_joins_on_frame, invite_requires_approval. */
    suspend fun updateFrame(frameId: String, patch: JsonObject): Frame
    suspend fun removeFrame(frameId: String)
    suspend fun leaveFrame(frameId: String, removeMyItems: Boolean)
    suspend fun redeem(code: String, idempotencyKey: String): RedeemResult
    suspend fun health(frameId: String): FrameHealth
    suspend fun command(frameId: String, command: FrameCommand): CommandResult

    suspend fun members(frameId: String): List<Member>
    suspend fun updateMember(frameId: String, userId: String, role: String? = null, permissions: Permissions? = null): Member
    suspend fun removeMember(frameId: String, userId: String, removeItems: Boolean)
    suspend fun joinRequests(frameId: String): List<JoinRequest>
    suspend fun decideJoinRequest(frameId: String, requestId: String, approve: Boolean)
    suspend fun invites(frameId: String): List<Invite>
    suspend fun createInvite(frameId: String, request: InviteCreate, idempotencyKey: String): Invite
    suspend fun revokeInvite(frameId: String, inviteId: String)
    suspend fun previewInvite(token: String): InvitePreview
    suspend fun acceptInvite(token: String): RedeemResult

    suspend fun sends(cursor: String? = null, limit: Int = 30, state: String = "all"): SendsPage
    suspend fun send(sendId: String): Send
    suspend fun createSend(request: SendCreate, idempotencyKey: String): Send
    suspend fun unsend(sendId: String)
    /** `PATCH /items/{id}`; a JSON null caption clears it. */
    suspend fun updateItem(itemId: String, patch: JsonObject): Item

    suspend fun placements(frameId: String, visibility: String = "visible", cursor: String? = null, limit: Int = 60): PlacementsPage
    suspend fun updatePlacement(frameId: String, placementId: String, patch: JsonObject): Placement
    suspend fun bulkPlacements(frameId: String, placementIds: List<String>, action: String): BulkResult

    suspend fun settings(frameId: String): SettingsDocument
    /** Merge patch with `If-Match: "<version>"`; a stale version answers `412 version_conflict`. */
    suspend fun patchSettings(frameId: String, version: Int, patch: JsonObject): SettingsDocument
    suspend fun geocode(query: String, lang: String): List<Place>

    /** The user websocket, `GET /v1/ws?access_token=…` (README §6.2). */
    suspend fun webSocketRequest(): Request
}

class HttpKinApi(
    private val http: OkHttpClient,
    @Volatile var apiBase: HttpUrl,
    private val tokens: AccessTokenSource,
    private val userAgent: String = "kinframe-family",
    private val acceptLanguage: () -> String? = { null },
) : KinApi {

    private fun ep(path: String, query: Map<String, String?> = emptyMap()): HttpUrl =
        apiBase.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()

    private fun json(obj: JsonElement, media: MediaType = JSON_MEDIA): RequestBody =
        KinJson.encodeToString(JsonElement.serializer(), obj).toRequestBody(media)

    private fun <T> json(value: T, ser: KSerializer<T>): RequestBody = KinJson.encodeToString(ser, value).toRequestBody(JSON_MEDIA)

    private val empty: RequestBody get() = ByteArray(0).toRequestBody(null)

    /** Runs a request with the bearer token; on 401 refreshes once, then reports the session as expired. */
    private suspend fun call(spec: Request.Builder.() -> Request.Builder): Response {
        var attempt = 0
        while (true) {
            val token = try {
                tokens.accessToken(forceRefresh = attempt > 0)
            } catch (e: SessionExpiredException) {
                tokens.onSessionExpired(); throw e
            }
            val req = Request.Builder()
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .apply { acceptLanguage()?.let { header("Accept-Language", it) } }
                .spec()
                .header("Authorization", "Bearer $token")
                .build()
            val r = http.newCall(req).await()
            if (r.code != 401) return r
            val p = r.use { it.problemOrNull() }
            if (attempt++ >= 1) {
                tokens.onSessionExpired()
                throw SessionExpiredException(p)
            }
        }
    }

    private fun <T> Response.decode(ser: KSerializer<T>): T = use {
        if (!isSuccessful) throwForStatus()
        KinJson.decodeFromString(ser, body.string())
    }

    private fun Response.expectOk() = use { if (!isSuccessful) throwForStatus() }

    private fun Response.settingsDoc(): SettingsDocument = use {
        if (!isSuccessful) throwForStatus()
        SettingsDocument(KinJson.parseToJsonElement(body.string()).jsonObject)
    }

    // ------------------------------------------------------------------ me

    override suspend fun me(): User = call { url(ep("me")).get() }.decode(User.serializer())

    override suspend fun updateMe(displayName: String?, locale: String?): User = call {
        url(ep("me")).patch(json(buildJsonObject {
            displayName?.let { put("display_name", JsonPrimitive(it)) }
            locale?.let { put("locale", JsonPrimitive(it)) }
        }))
    }.decode(User.serializer())

    override suspend fun deleteMe(removeItems: Boolean) =
        call { url(ep("me", mapOf("remove_items" to removeItems.toString()))).delete() }.expectOk()

    override suspend fun registerPush(endpoint: PushEndpointCreate): PushEndpoint =
        call { url(ep("me/push-endpoints")).post(json(endpoint, PushEndpointCreate.serializer())) }.decode(PushEndpoint.serializer())

    override suspend fun deletePush(endpointId: String) = call { url(ep("me/push-endpoints/$endpointId")).delete() }.expectOk()

    // ------------------------------------------------------------------ frames

    override suspend fun frames(): List<Frame> = call { url(ep("frames")).get() }.decode(ListSerializer(Frame.serializer()))

    override suspend fun frame(frameId: String): Frame = call { url(ep("frames/$frameId")).get() }.decode(Frame.serializer())

    override suspend fun updateFrame(frameId: String, patch: JsonObject): Frame =
        call { url(ep("frames/$frameId")).patch(json(patch)) }.decode(Frame.serializer())

    override suspend fun removeFrame(frameId: String) = call { url(ep("frames/$frameId")).delete() }.expectOk()

    override suspend fun leaveFrame(frameId: String, removeMyItems: Boolean) = call {
        url(ep("frames/$frameId/leave")).post(json(buildJsonObject { put("remove_my_items", JsonPrimitive(removeMyItems)) }))
    }.expectOk()

    override suspend fun redeem(code: String, idempotencyKey: String): RedeemResult = call {
        url(ep("frames/redeem")).header("Idempotency-Key", idempotencyKey)
            .post(json(buildJsonObject { put("code", JsonPrimitive(code)) }))
    }.decode(RedeemResult.serializer())

    override suspend fun health(frameId: String): FrameHealth = call { url(ep("frames/$frameId/health")).get() }.decode(FrameHealth.serializer())

    override suspend fun command(frameId: String, command: FrameCommand): CommandResult =
        call { url(ep("frames/$frameId/commands")).post(json(command, FrameCommand.serializer())) }.decode(CommandResult.serializer())

    // ------------------------------------------------------------------ members and invites

    override suspend fun members(frameId: String): List<Member> =
        call { url(ep("frames/$frameId/members")).get() }.decode(ListSerializer(Member.serializer()))

    override suspend fun updateMember(frameId: String, userId: String, role: String?, permissions: Permissions?): Member = call {
        url(ep("frames/$frameId/members/$userId")).patch(json(buildJsonObject {
            role?.let { put("role", JsonPrimitive(it)) }
            permissions?.let { put("permissions", KinJson.encodeToJsonElement(Permissions.serializer(), it)) }
        }))
    }.decode(Member.serializer())

    override suspend fun removeMember(frameId: String, userId: String, removeItems: Boolean) =
        call { url(ep("frames/$frameId/members/$userId", mapOf("remove_items" to removeItems.toString()))).delete() }.expectOk()

    override suspend fun joinRequests(frameId: String): List<JoinRequest> =
        call { url(ep("frames/$frameId/join-requests")).get() }.decode(ListSerializer(JoinRequest.serializer()))

    override suspend fun decideJoinRequest(frameId: String, requestId: String, approve: Boolean) =
        call { url(ep("frames/$frameId/join-requests/$requestId/${if (approve) "approve" else "deny"}")).post(empty) }.expectOk()

    override suspend fun invites(frameId: String): List<Invite> =
        call { url(ep("frames/$frameId/invites")).get() }.decode(ListSerializer(Invite.serializer()))

    override suspend fun createInvite(frameId: String, request: InviteCreate, idempotencyKey: String): Invite = call {
        // max_uses null means unlimited, so it is always sent explicitly.
        val body = buildJsonObject {
            put("max_uses", request.maxUses?.let { JsonPrimitive(it) } ?: JsonNull)
            put("expires_in_hours", JsonPrimitive(request.expiresInHours))
            request.note?.takeIf { it.isNotBlank() }?.let { put("note", JsonPrimitive(it)) }
        }
        url(ep("frames/$frameId/invites")).header("Idempotency-Key", idempotencyKey).post(json(body))
    }.decode(Invite.serializer())

    override suspend fun revokeInvite(frameId: String, inviteId: String) =
        call { url(ep("frames/$frameId/invites/$inviteId")).delete() }.expectOk()

    override suspend fun previewInvite(token: String): InvitePreview =
        call { url(ep("invites/$token")).get() }.decode(InvitePreview.serializer())

    override suspend fun acceptInvite(token: String): RedeemResult =
        call { url(ep("invites/$token/accept")).post(empty) }.decode(RedeemResult.serializer())

    // ------------------------------------------------------------------ sends

    override suspend fun sends(cursor: String?, limit: Int, state: String): SendsPage =
        call { url(ep("sends", mapOf("cursor" to cursor, "limit" to limit.toString(), "state" to state))).get() }
            .decode(SendsPage.serializer())

    override suspend fun send(sendId: String): Send = call { url(ep("sends/$sendId")).get() }.decode(Send.serializer())

    override suspend fun createSend(request: SendCreate, idempotencyKey: String): Send = call {
        url(ep("sends")).header("Idempotency-Key", idempotencyKey).post(json(request, SendCreate.serializer()))
    }.decode(Send.serializer())

    override suspend fun unsend(sendId: String) = call { url(ep("sends/$sendId")).delete() }.expectOk()

    override suspend fun updateItem(itemId: String, patch: JsonObject): Item =
        call { url(ep("items/$itemId")).patch(json(patch)) }.decode(Item.serializer())

    // ------------------------------------------------------------------ library

    override suspend fun placements(frameId: String, visibility: String, cursor: String?, limit: Int): PlacementsPage = call {
        url(ep("frames/$frameId/placements", mapOf("visibility" to visibility, "cursor" to cursor, "limit" to limit.toString()))).get()
    }.decode(PlacementsPage.serializer())

    override suspend fun updatePlacement(frameId: String, placementId: String, patch: JsonObject): Placement =
        call { url(ep("frames/$frameId/placements/$placementId")).patch(json(patch)) }.decode(Placement.serializer())

    override suspend fun bulkPlacements(frameId: String, placementIds: List<String>, action: String): BulkResult = call {
        // The path segment contains a colon (`placements:bulk`), which addPathSegments would keep as is.
        url(ep("frames/$frameId/placements:bulk")).post(json(buildJsonObject {
            put("placement_ids", kotlinx.serialization.json.JsonArray(placementIds.map { JsonPrimitive(it) }))
            put("action", JsonPrimitive(action))
        }))
    }.decode(BulkResult.serializer())

    // ------------------------------------------------------------------ settings and weather

    override suspend fun settings(frameId: String): SettingsDocument = call { url(ep("frames/$frameId/settings")).get() }.settingsDoc()

    override suspend fun patchSettings(frameId: String, version: Int, patch: JsonObject): SettingsDocument = call {
        url(ep("frames/$frameId/settings")).header("If-Match", "\"$version\"").patch(json(patch, MERGE_PATCH_MEDIA))
    }.settingsDoc()

    override suspend fun geocode(query: String, lang: String): List<Place> =
        call { url(ep("geocode", mapOf("q" to query, "lang" to lang))).get() }.decode(ListSerializer(Place.serializer()))

    override suspend fun webSocketRequest(): Request {
        val token = tokens.accessToken()
        return Request.Builder().url(ep("ws", mapOf("access_token" to token))).header("User-Agent", userAgent).build()
    }
}

/** `POST /dev/token` on development servers (discovery `dev_auth: true`). */
class DevAuthClient(private val http: OkHttpClient) {
    suspend fun token(server: HttpUrl, email: String, name: String?): AccessTokenResponse {
        val req = Request.Builder().url(server.newBuilder().addPathSegments("dev/token").build())
            .header("Accept", "application/json")
            .post(KinJson.encodeToString(DevTokenRequest.serializer(), DevTokenRequest(email, name?.takeIf { it.isNotBlank() })).toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(req).await().use { r ->
            if (!r.isSuccessful) r.throwForStatus()
            return KinJson.decodeFromString(AccessTokenResponse.serializer(), r.body.string())
        }
    }
}
