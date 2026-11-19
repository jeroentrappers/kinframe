package app.kinframe.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import okhttp3.MediaType.Companion.toMediaType
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
    /** [timezone]: the device's IANA zone, set automatically (birthday morning notifications at 08:00 local). */
    suspend fun updateMe(displayName: String? = null, locale: String? = null, timezone: String? = null): User
    suspend fun deleteMe(removeItems: Boolean)
    /** `PUT /me/avatar` (JPEG, PNG or WebP; the server crops to 256×256). */
    suspend fun putAvatar(bytes: ByteArray, mime: String): User
    suspend fun deleteAvatar()
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
    /** Owner only: the person becomes owner, the previous owner an admin. */
    suspend fun transferOwnership(frameId: String, userId: String)
    /** Owner only: attach a new, unclaimed device (by the 8-digit code it shows) to this frame. */
    suspend fun replaceDevice(frameId: String, code: String): Frame

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
    /** Place the items of a send on more frames, or post them to feeds, without uploading again. */
    suspend fun forwardSend(sendId: String, frameIds: List<String>, feedIds: List<String> = emptyList()): Send
    /** `PATCH /items/{id}`; a JSON null caption clears it. */
    suspend fun updateItem(itemId: String, patch: JsonObject): Item

    /** [albumId] limits to one album; [onThisDay] (`MM-DD`) gives "On this day" memories; [sort] e.g. `taken_desc`. */
    suspend fun placements(
        frameId: String, visibility: String = "visible", cursor: String? = null, limit: Int = 60,
        albumId: String? = null, onThisDay: String? = null, sort: String? = null,
    ): PlacementsPage
    suspend fun updatePlacement(frameId: String, placementId: String, patch: JsonObject): Placement
    /** [albumId] for `add_to_album` and `remove_from_album`. */
    suspend fun bulkPlacements(frameId: String, placementIds: List<String>, action: String, albumId: String? = null): BulkResult
    /**
     * Share placements of [frameId] to other frames without uploading again (README §5.3). With [dryRun] nothing is
     * created and `skipped` tells which frames already have the items.
     */
    suspend fun sharePlacements(frameId: String, placementIds: List<String>, frameIds: List<String>, dryRun: Boolean = false, idempotencyKey: String? = null): ShareResult

    suspend fun settings(frameId: String): SettingsDocument
    /** Merge patch with `If-Match: "<version>"`; a stale version answers `412 version_conflict`. */
    suspend fun patchSettings(frameId: String, version: Int, patch: JsonObject): SettingsDocument
    suspend fun geocode(query: String, lang: String): List<Place>
    /** `GET /frames/{id}/plugins`: the frame's plugin catalog and the status of its instances (docs/plugins.md §6). */
    suspend fun framePlugins(frameId: String): FramePlugins

    suspend fun albums(frameId: String): List<Album>
    suspend fun createAlbum(frameId: String, name: String, placementIds: List<String> = emptyList()): Album
    /** `PATCH` with `If-Match: "<version>"`: name, placement_ids, cover_placement_id, schedule (JSON null clears it). */
    suspend fun updateAlbum(frameId: String, albumId: String, version: Int, patch: JsonObject): Album
    /** [removeSourceItems]: also remove the photos the album's photo sources imported (asked when `photo_source_count` > 0). */
    suspend fun deleteAlbum(frameId: String, albumId: String, removeSourceItems: Boolean = false)

    /** The merged calendar the frame shows, plus its weather (needs change_settings). */
    suspend fun frameCalendar(frameId: String): CalendarView
    suspend fun calendars(frameId: String): List<CalendarSource>
    suspend fun createCalendar(frameId: String, request: CalendarSourceCreate, idempotencyKey: String): CalendarSource
    /** Omitted fields are kept: name, color, enabled, url, username, password, calendar_url. */
    suspend fun updateCalendar(frameId: String, calendarId: String, patch: JsonObject): CalendarSource
    suspend fun deleteCalendar(frameId: String, calendarId: String)
    suspend fun syncCalendar(frameId: String, calendarId: String): CalendarSource
    suspend fun testCalendar(frameId: String, request: CalendarSourceCreate): CalendarTestResult
    suspend fun setCountdown(frameId: String, eventId: String)
    suspend fun clearCountdown(frameId: String, eventId: String)
    /** Events sent to the frame, newest first: your own, or all of them for owner and admin. */
    suspend fun eventInvitations(frameId: String): List<EventInvitation>
    suspend fun sendEvent(frameId: String, request: EventInvitationCreate, idempotencyKey: String): EventInvitation
    /** Withdraw a pending invitation (`409 already_decided` once the frame answered). */
    suspend fun withdrawEvent(frameId: String, invitationId: String)
    /** The invitation's photo (WebP), or null when it has none. */
    suspend fun eventPhoto(frameId: String, invitationId: String): ByteArray?

    suspend fun tvLinks(frameId: String): List<TvLink>
    suspend fun createTvLink(frameId: String, request: TvLinkCreate): TvLink
    suspend fun revokeTvLink(frameId: String, linkId: String)

    // ---- feeds (README §5.12, docs/spec/feeds.md)

    /** Feeds the caller is a member of (summaries). */
    suspend fun feeds(): List<Feed>
    suspend fun createFeed(request: FeedCreate, idempotencyKey: String): Feed
    suspend fun feed(feedId: String): Feed
    /** `PATCH /feeds/{id}`: name, cover_item_id (JSON null removes it), and the four settings. */
    suspend fun updateFeed(feedId: String, patch: JsonObject): Feed
    suspend fun deleteFeed(feedId: String)
    suspend fun leaveFeed(feedId: String, removeMyPosts: Boolean)
    suspend fun transferFeed(feedId: String, userId: String)
    suspend fun feedMembers(feedId: String): List<FeedMember>
    suspend fun updateFeedMember(feedId: String, userId: String, role: String): FeedMember
    suspend fun removeFeedMember(feedId: String, userId: String, removePosts: Boolean)
    suspend fun feedJoinRequests(feedId: String): List<JoinRequest>
    suspend fun decideFeedJoinRequest(feedId: String, requestId: String, approve: Boolean)
    suspend fun feedInvites(feedId: String): List<FeedInvite>
    suspend fun createFeedInvite(feedId: String, request: FeedInviteCreate, idempotencyKey: String): FeedInvite
    suspend fun revokeFeedInvite(feedId: String, inviteId: String)
    /** `POST /feed-invites:lookup`: a short code typed into "Enter a code" → the preview with its token. */
    suspend fun lookupFeedInvite(code: String): FeedInvitePreview
    suspend fun previewFeedInvite(token: String): FeedInvitePreview
    suspend fun acceptFeedInvite(token: String, accept: FeedInviteAccept?, idempotencyKey: String): FeedInviteResult
    suspend fun feedPosts(feedId: String, cursor: String? = null, limit: Int = 30, posterId: String? = null): FeedPostsPage
    suspend fun feedPost(feedId: String, postId: String): FeedPost
    /** Unsend (the poster) or remove (owner/admin) a post from the feed and every subscribed frame. */
    suspend fun removeFeedPost(feedId: String, postId: String)
    suspend fun removeFeedPostItem(feedId: String, postId: String, itemId: String)
    suspend fun feedPostReceipts(feedId: String, postId: String): FeedPostReceipts

    // ---- subscriptions

    suspend fun feedSubscriptions(feedId: String, status: String? = null): List<Subscription>
    suspend fun requestSubscription(feedId: String, request: SubscriptionRequest, idempotencyKey: String): Subscription
    suspend fun frameSubscriptions(frameId: String, status: String? = null): List<Subscription>
    suspend fun subscription(subscriptionId: String): Subscription
    suspend fun updateSubscription(subscriptionId: String, update: SubscriptionUpdate): Subscription
    /** Unsubscribe, withdraw a pending request, or (feed side) remove. */
    suspend fun removeSubscription(subscriptionId: String)
    suspend fun approveSubscription(subscriptionId: String): Subscription
    suspend fun rejectSubscription(subscriptionId: String): Subscription
    suspend fun pauseSubscription(subscriptionId: String): Subscription
    suspend fun resumeSubscription(subscriptionId: String): Subscription

    // ---- birthdays (README §5.13, docs/spec/birthdays.md)

    /** The feed's birthday people the caller may see, sorted by next birthday; [status] limits to one status. */
    suspend fun feedBirthdays(feedId: String, status: String? = null): List<BirthdayPerson>
    /** Approved at once for owner/admins (and posters with `posters_can_add_birthdays`), else a pending suggestion. */
    suspend fun createFeedBirthday(feedId: String, request: BirthdayPersonCreate, idempotencyKey: String): BirthdayPerson
    suspend fun feedBirthday(feedId: String, birthdayId: String): BirthdayPerson
    suspend fun updateFeedBirthday(feedId: String, birthdayId: String, update: BirthdayPersonUpdate): BirthdayPerson
    suspend fun removeFeedBirthday(feedId: String, birthdayId: String)
    suspend fun approveFeedBirthday(feedId: String, birthdayId: String): BirthdayPerson
    suspend fun rejectFeedBirthday(feedId: String, birthdayId: String): BirthdayPerson
    /** JPEG, PNG or WebP; the server crops to 256×256 like an avatar. */
    suspend fun putFeedBirthdayPhoto(feedId: String, birthdayId: String, bytes: ByteArray, mime: String): BirthdayPerson
    suspend fun deleteFeedBirthdayPhoto(feedId: String, birthdayId: String): BirthdayPerson
    suspend fun feedBirthdayGreetings(
        feedId: String, birthdayId: String? = null, date: String? = null, cursor: String? = null, limit: Int = 50,
    ): FeedBirthdayGreetingsPage
    /** The caller's own preferences in a feed (`birthday_notifications`). */
    suspend fun updateFeedPreferences(feedId: String, update: FeedPreferencesUpdate): Feed

    // ---- photo sources and Google Photos import (README §5.14, docs/spec/photo-sources.md)

    /** The album's sources (album managers), oldest first. */
    suspend fun albumPhotoSources(frameId: String, albumId: String): List<PhotoSource>
    /** The feed's sources (feed owner/admins), oldest first. */
    suspend fun feedPhotoSources(feedId: String): List<PhotoSource>
    /** The sources the caller owns, across albums and feeds, newest first. */
    suspend fun myPhotoSources(): List<PhotoSource>
    /** Tests, then creates; `422 source_test_failed` with the error code in `detail`. */
    suspend fun createPhotoSource(request: PhotoSourceCreate, idempotencyKey: String): PhotoSource
    /** Tries a connection without saving it; for `immich_api` without an album it lists the albums. */
    suspend fun testPhotoSource(connection: PhotoSourceConnection): PhotoSourceTestResult
    suspend fun immichAlbums(serverUrl: String, apiKey: String): ImmichAlbumList
    suspend fun photoSource(sourceId: String): PhotoSource
    /** Pause/resume, rename, settings, new credentials, take over. Omitted fields are kept. */
    suspend fun updatePhotoSource(sourceId: String, update: PhotoSourceUpdate): PhotoSource
    /** Stop syncing; [removeItems] also removes the photos it imported. */
    suspend fun deletePhotoSource(sourceId: String, removeItems: Boolean)
    /** Sync now (`202`, `running: true`); [confirmRemovals] applies removals held back by the mass-removal guard. */
    suspend fun syncPhotoSource(sourceId: String, confirmRemovals: Boolean = false): PhotoSource
    /** The caller's imports of the last 7 days, newest first. */
    suspend fun myGoogleImports(): List<GoogleImport>
    suspend fun startGoogleImport(request: GoogleImportCreate, idempotencyKey: String): GoogleImport
    suspend fun googleImport(importId: String): GoogleImport
    suspend fun cancelGoogleImport(importId: String): GoogleImport

    /** The user websocket, `GET /v1/ws?access_token=…` (README §6.2). */
    suspend fun webSocketRequest(): Request
}

class HttpKinApi(
    private val http: OkHttpClient,
    @Volatile var apiBase: HttpUrl,
    private val tokens: AccessTokenSource,
    private val userAgent: String = "kinframe-family",
    /** `X-Kinframe-App` ([FamilyAppHeader.value]); null leaves it to an [AppVersionInterceptor] on [http] (or out). */
    private val appHeader: String? = null,
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
                .apply { appHeader?.let { header(FamilyAppHeader.NAME, it) } }
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

    // Bodies are read off the caller's thread: callers run on the main thread, and a body larger than what came in
    // with the headers would read from the socket there (NetworkOnMainThreadException).
    private suspend fun <T> Response.decode(ser: KSerializer<T>): T = withContext(Dispatchers.IO) {
        use {
            if (!isSuccessful) throwForStatus()
            KinJson.decodeFromString(ser, body.string())
        }
    }

    private suspend fun Response.expectOk() = withContext(Dispatchers.IO) { use { if (!isSuccessful) throwForStatus() } }

    private suspend fun Response.settingsDoc(): SettingsDocument = withContext(Dispatchers.IO) {
        use {
            if (!isSuccessful) throwForStatus()
            SettingsDocument(KinJson.parseToJsonElement(body.string()).jsonObject)
        }
    }

    // ------------------------------------------------------------------ me

    override suspend fun me(): User = call { url(ep("me")).get() }.decode(User.serializer())

    override suspend fun updateMe(displayName: String?, locale: String?, timezone: String?): User = call {
        url(ep("me")).patch(json(buildJsonObject {
            displayName?.let { put("display_name", JsonPrimitive(it)) }
            locale?.let { put("locale", JsonPrimitive(it)) }
            timezone?.let { put("timezone", JsonPrimitive(it)) }
        }))
    }.decode(User.serializer())

    override suspend fun deleteMe(removeItems: Boolean) =
        call { url(ep("me", mapOf("remove_items" to removeItems.toString()))).delete() }.expectOk()

    override suspend fun putAvatar(bytes: ByteArray, mime: String): User =
        call { url(ep("me/avatar")).put(bytes.toRequestBody(mime.toMediaType())) }.decode(User.serializer())

    override suspend fun deleteAvatar() = call { url(ep("me/avatar")).delete() }.expectOk()

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

    override suspend fun transferOwnership(frameId: String, userId: String) = call {
        url(ep("frames/$frameId/transfer")).post(json(buildJsonObject { put("user_id", JsonPrimitive(userId)) }))
    }.expectOk()

    override suspend fun replaceDevice(frameId: String, code: String): Frame = call {
        url(ep("frames/$frameId/replace-device")).post(json(buildJsonObject { put("code", JsonPrimitive(code)) }))
    }.decode(Frame.serializer())

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

    override suspend fun forwardSend(sendId: String, frameIds: List<String>, feedIds: List<String>): Send = call {
        url(ep("sends/$sendId/forward")).post(json(buildJsonObject {
            put("frame_ids", kotlinx.serialization.json.JsonArray(frameIds.map { JsonPrimitive(it) }))
            if (feedIds.isNotEmpty()) put("feed_ids", kotlinx.serialization.json.JsonArray(feedIds.map { JsonPrimitive(it) }))
        }))
    }.decode(Send.serializer())

    override suspend fun updateItem(itemId: String, patch: JsonObject): Item =
        call { url(ep("items/$itemId")).patch(json(patch)) }.decode(Item.serializer())

    // ------------------------------------------------------------------ library

    override suspend fun placements(frameId: String, visibility: String, cursor: String?, limit: Int, albumId: String?, onThisDay: String?, sort: String?): PlacementsPage = call {
        url(ep("frames/$frameId/placements", mapOf(
            "visibility" to visibility, "cursor" to cursor, "limit" to limit.toString(), "album_id" to albumId, "on_this_day" to onThisDay, "sort" to sort,
        ))).get()
    }.decode(PlacementsPage.serializer())

    override suspend fun updatePlacement(frameId: String, placementId: String, patch: JsonObject): Placement =
        call { url(ep("frames/$frameId/placements/$placementId")).patch(json(patch)) }.decode(Placement.serializer())

    override suspend fun bulkPlacements(frameId: String, placementIds: List<String>, action: String, albumId: String?): BulkResult = call {
        // The path segment contains a colon (`placements:bulk`), which addPathSegments would keep as is.
        url(ep("frames/$frameId/placements:bulk")).post(json(buildJsonObject {
            put("placement_ids", kotlinx.serialization.json.JsonArray(placementIds.map { JsonPrimitive(it) }))
            put("action", JsonPrimitive(action))
            albumId?.let { put("album_id", JsonPrimitive(it)) }
        }))
    }.decode(BulkResult.serializer())

    override suspend fun sharePlacements(frameId: String, placementIds: List<String>, frameIds: List<String>, dryRun: Boolean, idempotencyKey: String?): ShareResult = call {
        url(ep("frames/$frameId/placements:share")).apply { idempotencyKey?.let { header("Idempotency-Key", it) } }.post(json(buildJsonObject {
            put("placement_ids", kotlinx.serialization.json.JsonArray(placementIds.map { JsonPrimitive(it) }))
            put("frame_ids", kotlinx.serialization.json.JsonArray(frameIds.map { JsonPrimitive(it) }))
            if (dryRun) put("dry_run", JsonPrimitive(true))
        }))
    }.decode(ShareResult.serializer())

    // ------------------------------------------------------------------ settings and weather

    override suspend fun settings(frameId: String): SettingsDocument = call { url(ep("frames/$frameId/settings")).get() }.settingsDoc()

    override suspend fun patchSettings(frameId: String, version: Int, patch: JsonObject): SettingsDocument = call {
        url(ep("frames/$frameId/settings")).header("If-Match", "\"$version\"").patch(json(patch, MERGE_PATCH_MEDIA))
    }.settingsDoc()

    override suspend fun geocode(query: String, lang: String): List<Place> =
        call { url(ep("geocode", mapOf("q" to query, "lang" to lang))).get() }.decode(ListSerializer(Place.serializer()))

    override suspend fun framePlugins(frameId: String): FramePlugins =
        call { url(ep("frames/$frameId/plugins")).get() }.decode(FramePlugins.serializer())

    // ------------------------------------------------------------------ albums

    override suspend fun albums(frameId: String): List<Album> =
        call { url(ep("frames/$frameId/albums")).get() }.decode(ListSerializer(Album.serializer()))

    override suspend fun createAlbum(frameId: String, name: String, placementIds: List<String>): Album = call {
        url(ep("frames/$frameId/albums")).post(json(buildJsonObject {
            put("name", JsonPrimitive(name))
            if (placementIds.isNotEmpty()) put("placement_ids", kotlinx.serialization.json.JsonArray(placementIds.map { JsonPrimitive(it) }))
        }))
    }.decode(Album.serializer())

    override suspend fun updateAlbum(frameId: String, albumId: String, version: Int, patch: JsonObject): Album =
        call { url(ep("frames/$frameId/albums/$albumId")).header("If-Match", "\"$version\"").patch(json(patch)) }.decode(Album.serializer())

    override suspend fun deleteAlbum(frameId: String, albumId: String, removeSourceItems: Boolean) = call {
        url(ep("frames/$frameId/albums/$albumId", mapOf("remove_source_items" to if (removeSourceItems) "true" else null))).delete()
    }.expectOk()

    // ------------------------------------------------------------------ calendars and sent events

    override suspend fun frameCalendar(frameId: String): CalendarView =
        call { url(ep("frames/$frameId/calendar")).get() }.decode(CalendarView.serializer())

    override suspend fun calendars(frameId: String): List<CalendarSource> =
        call { url(ep("frames/$frameId/calendars")).get() }.decode(ListSerializer(CalendarSource.serializer()))

    override suspend fun createCalendar(frameId: String, request: CalendarSourceCreate, idempotencyKey: String): CalendarSource = call {
        url(ep("frames/$frameId/calendars")).header("Idempotency-Key", idempotencyKey).post(json(request, CalendarSourceCreate.serializer()))
    }.decode(CalendarSource.serializer())

    override suspend fun updateCalendar(frameId: String, calendarId: String, patch: JsonObject): CalendarSource =
        call { url(ep("frames/$frameId/calendars/$calendarId")).patch(json(patch)) }.decode(CalendarSource.serializer())

    override suspend fun deleteCalendar(frameId: String, calendarId: String) =
        call { url(ep("frames/$frameId/calendars/$calendarId")).delete() }.expectOk()

    override suspend fun syncCalendar(frameId: String, calendarId: String): CalendarSource =
        call { url(ep("frames/$frameId/calendars/$calendarId/sync")).post(empty) }.decode(CalendarSource.serializer())

    override suspend fun testCalendar(frameId: String, request: CalendarSourceCreate): CalendarTestResult = call {
        // Like placements:bulk, the colon stays in the last path segment.
        url(ep("frames/$frameId/calendars:test")).post(json(request, CalendarSourceCreate.serializer()))
    }.decode(CalendarTestResult.serializer())

    override suspend fun setCountdown(frameId: String, eventId: String) =
        call { url(ep("frames/$frameId/countdowns/$eventId")).put(empty) }.expectOk()

    override suspend fun clearCountdown(frameId: String, eventId: String) =
        call { url(ep("frames/$frameId/countdowns/$eventId")).delete() }.expectOk()

    override suspend fun eventInvitations(frameId: String): List<EventInvitation> =
        call { url(ep("frames/$frameId/events")).get() }.decode(ListSerializer(EventInvitation.serializer()))

    override suspend fun sendEvent(frameId: String, request: EventInvitationCreate, idempotencyKey: String): EventInvitation = call {
        url(ep("frames/$frameId/events")).header("Idempotency-Key", idempotencyKey).post(json(request, EventInvitationCreate.serializer()))
    }.decode(EventInvitation.serializer())

    override suspend fun withdrawEvent(frameId: String, invitationId: String) =
        call { url(ep("frames/$frameId/events/$invitationId")).delete() }.expectOk()

    override suspend fun eventPhoto(frameId: String, invitationId: String): ByteArray? =
        call { url(ep("frames/$frameId/events/$invitationId/photo")).header("Accept", "image/webp").get() }.use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> r.throwForStatus()
                else -> r.body.bytes()
            }
        }

    // ------------------------------------------------------------------ TV links

    override suspend fun tvLinks(frameId: String): List<TvLink> =
        call { url(ep("frames/$frameId/tv-links")).get() }.decode(ListSerializer(TvLink.serializer()))

    override suspend fun createTvLink(frameId: String, request: TvLinkCreate): TvLink =
        call { url(ep("frames/$frameId/tv-links")).post(json(request, TvLinkCreate.serializer())) }.decode(TvLink.serializer())

    override suspend fun revokeTvLink(frameId: String, linkId: String) =
        call { url(ep("frames/$frameId/tv-links/$linkId")).delete() }.expectOk()

    // ------------------------------------------------------------------ feeds

    override suspend fun feeds(): List<Feed> = call { url(ep("feeds")).get() }.decode(ListSerializer(Feed.serializer()))

    override suspend fun createFeed(request: FeedCreate, idempotencyKey: String): Feed = call {
        url(ep("feeds")).header("Idempotency-Key", idempotencyKey).post(json(request, FeedCreate.serializer()))
    }.decode(Feed.serializer())

    override suspend fun feed(feedId: String): Feed = call { url(ep("feeds/$feedId")).get() }.decode(Feed.serializer())

    override suspend fun updateFeed(feedId: String, patch: JsonObject): Feed =
        call { url(ep("feeds/$feedId")).patch(json(patch)) }.decode(Feed.serializer())

    override suspend fun deleteFeed(feedId: String) = call { url(ep("feeds/$feedId")).delete() }.expectOk()

    override suspend fun leaveFeed(feedId: String, removeMyPosts: Boolean) = call {
        url(ep("feeds/$feedId/leave")).post(json(buildJsonObject { put("remove_my_posts", JsonPrimitive(removeMyPosts)) }))
    }.expectOk()

    override suspend fun transferFeed(feedId: String, userId: String) = call {
        url(ep("feeds/$feedId/transfer")).post(json(buildJsonObject { put("user_id", JsonPrimitive(userId)) }))
    }.expectOk()

    override suspend fun feedMembers(feedId: String): List<FeedMember> =
        call { url(ep("feeds/$feedId/members")).get() }.decode(ListSerializer(FeedMember.serializer()))

    override suspend fun updateFeedMember(feedId: String, userId: String, role: String): FeedMember = call {
        url(ep("feeds/$feedId/members/$userId")).patch(json(buildJsonObject { put("role", JsonPrimitive(role)) }))
    }.decode(FeedMember.serializer())

    override suspend fun removeFeedMember(feedId: String, userId: String, removePosts: Boolean) =
        call { url(ep("feeds/$feedId/members/$userId", mapOf("remove_posts" to removePosts.toString()))).delete() }.expectOk()

    override suspend fun feedJoinRequests(feedId: String): List<JoinRequest> =
        call { url(ep("feeds/$feedId/join-requests")).get() }.decode(ListSerializer(JoinRequest.serializer()))

    override suspend fun decideFeedJoinRequest(feedId: String, requestId: String, approve: Boolean) =
        call { url(ep("feeds/$feedId/join-requests/$requestId/${if (approve) "approve" else "deny"}")).post(empty) }.expectOk()

    override suspend fun feedInvites(feedId: String): List<FeedInvite> =
        call { url(ep("feeds/$feedId/invites")).get() }.decode(ListSerializer(FeedInvite.serializer()))

    override suspend fun createFeedInvite(feedId: String, request: FeedInviteCreate, idempotencyKey: String): FeedInvite = call {
        // max_uses null means unlimited, so it is always sent explicitly.
        val body = buildJsonObject {
            put("purpose", JsonPrimitive(request.purpose))
            put("max_uses", request.maxUses?.let { JsonPrimitive(it) } ?: JsonNull)
            put("expires_in_hours", JsonPrimitive(request.expiresInHours))
            request.note?.takeIf { it.isNotBlank() }?.let { put("note", JsonPrimitive(it)) }
        }
        url(ep("feeds/$feedId/invites")).header("Idempotency-Key", idempotencyKey).post(json(body))
    }.decode(FeedInvite.serializer())

    override suspend fun revokeFeedInvite(feedId: String, inviteId: String) =
        call { url(ep("feeds/$feedId/invites/$inviteId")).delete() }.expectOk()

    override suspend fun lookupFeedInvite(code: String): FeedInvitePreview = call {
        // Like placements:bulk, the colon stays in the path segment.
        url(ep("feed-invites:lookup")).post(json(buildJsonObject { put("code", JsonPrimitive(code)) }))
    }.decode(FeedInvitePreview.serializer())

    override suspend fun previewFeedInvite(token: String): FeedInvitePreview =
        call { url(ep("feed-invites/$token")).get() }.decode(FeedInvitePreview.serializer())

    override suspend fun acceptFeedInvite(token: String, accept: FeedInviteAccept?, idempotencyKey: String): FeedInviteResult = call {
        url(ep("feed-invites/$token/accept")).header("Idempotency-Key", idempotencyKey)
            .post(if (accept == null) empty else json(accept, FeedInviteAccept.serializer()))
    }.decode(FeedInviteResult.serializer())

    override suspend fun feedPosts(feedId: String, cursor: String?, limit: Int, posterId: String?): FeedPostsPage = call {
        url(ep("feeds/$feedId/posts", mapOf("cursor" to cursor, "limit" to limit.toString(), "poster_id" to posterId))).get()
    }.decode(FeedPostsPage.serializer())

    override suspend fun feedPost(feedId: String, postId: String): FeedPost =
        call { url(ep("feeds/$feedId/posts/$postId")).get() }.decode(FeedPost.serializer())

    override suspend fun removeFeedPost(feedId: String, postId: String) =
        call { url(ep("feeds/$feedId/posts/$postId")).delete() }.expectOk()

    override suspend fun removeFeedPostItem(feedId: String, postId: String, itemId: String) =
        call { url(ep("feeds/$feedId/posts/$postId/items/$itemId")).delete() }.expectOk()

    override suspend fun feedPostReceipts(feedId: String, postId: String): FeedPostReceipts =
        call { url(ep("feeds/$feedId/posts/$postId/receipts")).get() }.decode(FeedPostReceipts.serializer())

    // ------------------------------------------------------------------ subscriptions

    override suspend fun feedSubscriptions(feedId: String, status: String?): List<Subscription> =
        call { url(ep("feeds/$feedId/subscriptions", mapOf("status" to status))).get() }.decode(ListSerializer(Subscription.serializer()))

    override suspend fun requestSubscription(feedId: String, request: SubscriptionRequest, idempotencyKey: String): Subscription = call {
        url(ep("feeds/$feedId/subscriptions")).header("Idempotency-Key", idempotencyKey).post(json(request, SubscriptionRequest.serializer()))
    }.decode(Subscription.serializer())

    override suspend fun frameSubscriptions(frameId: String, status: String?): List<Subscription> =
        call { url(ep("frames/$frameId/subscriptions", mapOf("status" to status))).get() }.decode(ListSerializer(Subscription.serializer()))

    override suspend fun subscription(subscriptionId: String): Subscription =
        call { url(ep("subscriptions/$subscriptionId")).get() }.decode(Subscription.serializer())

    override suspend fun updateSubscription(subscriptionId: String, update: SubscriptionUpdate): Subscription =
        call { url(ep("subscriptions/$subscriptionId")).patch(json(update, SubscriptionUpdate.serializer())) }.decode(Subscription.serializer())

    override suspend fun removeSubscription(subscriptionId: String) =
        call { url(ep("subscriptions/$subscriptionId")).delete() }.expectOk()

    private suspend fun subscriptionAction(subscriptionId: String, action: String): Subscription =
        call { url(ep("subscriptions/$subscriptionId/$action")).post(empty) }.decode(Subscription.serializer())

    override suspend fun approveSubscription(subscriptionId: String) = subscriptionAction(subscriptionId, "approve")
    override suspend fun rejectSubscription(subscriptionId: String) = subscriptionAction(subscriptionId, "reject")
    override suspend fun pauseSubscription(subscriptionId: String) = subscriptionAction(subscriptionId, "pause")
    override suspend fun resumeSubscription(subscriptionId: String) = subscriptionAction(subscriptionId, "resume")

    // ------------------------------------------------------------------ birthdays

    override suspend fun feedBirthdays(feedId: String, status: String?): List<BirthdayPerson> =
        call { url(ep("feeds/$feedId/birthdays", mapOf("status" to status))).get() }.decode(ListSerializer(BirthdayPerson.serializer()))

    override suspend fun createFeedBirthday(feedId: String, request: BirthdayPersonCreate, idempotencyKey: String): BirthdayPerson = call {
        url(ep("feeds/$feedId/birthdays")).header("Idempotency-Key", idempotencyKey).post(json(request, BirthdayPersonCreate.serializer()))
    }.decode(BirthdayPerson.serializer())

    override suspend fun feedBirthday(feedId: String, birthdayId: String): BirthdayPerson =
        call { url(ep("feeds/$feedId/birthdays/$birthdayId")).get() }.decode(BirthdayPerson.serializer())

    override suspend fun updateFeedBirthday(feedId: String, birthdayId: String, update: BirthdayPersonUpdate): BirthdayPerson =
        call { url(ep("feeds/$feedId/birthdays/$birthdayId")).patch(json(update.toJson())) }.decode(BirthdayPerson.serializer())

    override suspend fun removeFeedBirthday(feedId: String, birthdayId: String) =
        call { url(ep("feeds/$feedId/birthdays/$birthdayId")).delete() }.expectOk()

    override suspend fun approveFeedBirthday(feedId: String, birthdayId: String): BirthdayPerson =
        call { url(ep("feeds/$feedId/birthdays/$birthdayId/approve")).post(empty) }.decode(BirthdayPerson.serializer())

    override suspend fun rejectFeedBirthday(feedId: String, birthdayId: String): BirthdayPerson =
        call { url(ep("feeds/$feedId/birthdays/$birthdayId/reject")).post(empty) }.decode(BirthdayPerson.serializer())

    override suspend fun putFeedBirthdayPhoto(feedId: String, birthdayId: String, bytes: ByteArray, mime: String): BirthdayPerson =
        call { url(ep("feeds/$feedId/birthdays/$birthdayId/photo")).put(bytes.toRequestBody(mime.toMediaType())) }.decode(BirthdayPerson.serializer())

    override suspend fun deleteFeedBirthdayPhoto(feedId: String, birthdayId: String): BirthdayPerson =
        call { url(ep("feeds/$feedId/birthdays/$birthdayId/photo")).delete() }.decode(BirthdayPerson.serializer())

    override suspend fun feedBirthdayGreetings(feedId: String, birthdayId: String?, date: String?, cursor: String?, limit: Int): FeedBirthdayGreetingsPage = call {
        url(ep("feeds/$feedId/birthday-greetings", mapOf("birthday_id" to birthdayId, "date" to date, "cursor" to cursor, "limit" to limit.toString()))).get()
    }.decode(FeedBirthdayGreetingsPage.serializer())

    override suspend fun updateFeedPreferences(feedId: String, update: FeedPreferencesUpdate): Feed =
        call { url(ep("feeds/$feedId/preferences")).patch(json(update, FeedPreferencesUpdate.serializer())) }.decode(Feed.serializer())

    // ------------------------------------------------------------------ photo sources

    override suspend fun albumPhotoSources(frameId: String, albumId: String): List<PhotoSource> =
        call { url(ep("frames/$frameId/albums/$albumId/photo-sources")).get() }.decode(ListSerializer(PhotoSource.serializer()))

    override suspend fun feedPhotoSources(feedId: String): List<PhotoSource> =
        call { url(ep("feeds/$feedId/photo-sources")).get() }.decode(ListSerializer(PhotoSource.serializer()))

    override suspend fun myPhotoSources(): List<PhotoSource> =
        call { url(ep("photo-sources")).get() }.decode(ListSerializer(PhotoSource.serializer()))

    override suspend fun createPhotoSource(request: PhotoSourceCreate, idempotencyKey: String): PhotoSource = call {
        url(ep("photo-sources")).header("Idempotency-Key", idempotencyKey).post(json(request.toJson()))
    }.decode(PhotoSource.serializer())

    override suspend fun testPhotoSource(connection: PhotoSourceConnection): PhotoSourceTestResult = call {
        // Like placements:bulk, the colon stays in the path segment.
        url(ep("photo-sources:test")).post(json(connection.toJson()))
    }.decode(PhotoSourceTestResult.serializer())

    override suspend fun immichAlbums(serverUrl: String, apiKey: String): ImmichAlbumList = call {
        url(ep("photo-sources:immich-albums")).post(json(buildJsonObject {
            put("server_url", JsonPrimitive(serverUrl.trim())); put("api_key", JsonPrimitive(apiKey.trim()))
        }))
    }.decode(ImmichAlbumList.serializer())

    override suspend fun photoSource(sourceId: String): PhotoSource =
        call { url(ep("photo-sources/$sourceId")).get() }.decode(PhotoSource.serializer())

    override suspend fun updatePhotoSource(sourceId: String, update: PhotoSourceUpdate): PhotoSource =
        call { url(ep("photo-sources/$sourceId")).patch(json(update.toJson())) }.decode(PhotoSource.serializer())

    override suspend fun deletePhotoSource(sourceId: String, removeItems: Boolean) =
        call { url(ep("photo-sources/$sourceId", mapOf("remove_items" to removeItems.toString()))).delete() }.expectOk()

    override suspend fun syncPhotoSource(sourceId: String, confirmRemovals: Boolean): PhotoSource = call {
        url(ep("photo-sources/$sourceId/sync")).post(json(buildJsonObject { if (confirmRemovals) put("confirm_removals", JsonPrimitive(true)) }))
    }.decode(PhotoSource.serializer())

    override suspend fun myGoogleImports(): List<GoogleImport> =
        call { url(ep("google-imports")).get() }.decode(ListSerializer(GoogleImport.serializer()))

    override suspend fun startGoogleImport(request: GoogleImportCreate, idempotencyKey: String): GoogleImport = call {
        url(ep("google-imports")).header("Idempotency-Key", idempotencyKey).post(json(request, GoogleImportCreate.serializer()))
    }.decode(GoogleImport.serializer())

    override suspend fun googleImport(importId: String): GoogleImport =
        call { url(ep("google-imports/$importId")).get() }.decode(GoogleImport.serializer())

    override suspend fun cancelGoogleImport(importId: String): GoogleImport =
        call { url(ep("google-imports/$importId/cancel")).post(empty) }.decode(GoogleImport.serializer())

    override suspend fun webSocketRequest(): Request {
        val token = tokens.accessToken()
        return Request.Builder().url(ep("ws", mapOf("access_token" to token))).header("User-Agent", userAgent)
            .apply { appHeader?.let { header(FamilyAppHeader.NAME, it) } }.build()
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
