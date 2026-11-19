package app.kinframe.core.leaving

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.PlacementsPage
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Leaving a family (README §5.21; openapi tag `leaving`, plus the new bodies of `leaveFeed`, `leaveFrame` and
 * `deleteMe`). Kept apart from `KinApi` so the area can grow without touching it; an interface so view models can be
 * tested with fakes. Everything here is hidden while discovery `leaving` is false.
 */
interface LeavingApi {
    /** The leave sheet's numbers and "Leave this frame too" checkboxes. Exactly one of the ids. */
    suspend fun leavePreview(feedId: String? = null, frameId: String? = null): LeavePreview
    /** `POST /feeds/{id}/leave` with the choice; null when the server answered 204 (nothing to report). */
    suspend fun leaveFeed(feedId: String, request: LeaveFeedRequest): TakeAwayResult?
    suspend fun leaveFrame(frameId: String, request: LeaveFrameRequest): TakeAwayResult?
    /** Families and frames the caller left (or was removed from) where their photos still are. */
    suspend fun formerPlaces(): FormerPlaces
    suspend fun takeAwayFromFeed(feedId: String, request: TakeAwayRequest, idempotencyKey: String): TakeAwayResult
    suspend fun takeAwayFromFrame(frameId: String, idempotencyKey: String): TakeAwayResult
    /** Managers: the takings still running in that scope ("Taken away by Tom"). */
    suspend fun frameTakenAway(frameId: String): TakenAwayGroupList
    suspend fun feedTakenAway(feedId: String): TakenAwayGroupList
    /** `listPlacements?visibility=taken_away&taking_id=`: the photos of one taking, to pick from for "Ask to keep". */
    suspend fun takenAwayPlacements(frameId: String, takingId: String? = null, cursor: String? = null, limit: Int = 120): PlacementsPage
    suspend fun createKeepRequest(request: KeepRequestCreate, idempotencyKey: String): KeepRequest
    /** [status] `open` (default) or `all`. */
    suspend fun myKeepRequests(status: String? = null): KeepRequestList
    suspend fun frameKeepRequests(frameId: String): KeepRequestList
    suspend fun feedKeepRequests(feedId: String): KeepRequestList
    suspend fun keepRequest(requestId: String): KeepRequest
    suspend fun answerKeepRequest(requestId: String, answer: KeepRequestAnswer): KeepRequest
    suspend fun memories(): MemoryPreferences
    suspend fun updateMemories(update: MemoryPreferencesUpdate): MemoryPreferences
    /** `DELETE /v1/me` with `{photos: take|leave}`. */
    suspend fun deleteAccount(deletion: AccountDeletion)
}

class HttpLeavingApi(
    http: OkHttpClient,
    apiBase: HttpUrl,
    tokens: AccessTokenSource,
    userAgent: String = "kinframe-family",
    appHeader: String? = null,
    acceptLanguage: () -> String? = { null },
) : LeavingApi {
    private val c = AuthedClient(http, apiBase, tokens, userAgent, appHeader, acceptLanguage)

    override suspend fun leavePreview(feedId: String?, frameId: String?): LeavePreview =
        c.decode(c.call { url(c.ep("me/leave-preview", mapOf("feed_id" to feedId, "frame_id" to frameId))).get() }, LeavePreview.serializer())

    override suspend fun leaveFeed(feedId: String, request: LeaveFeedRequest): TakeAwayResult? =
        c.decodeOrNull(c.call { url(c.ep("feeds/$feedId/leave")).post(c.json(request, LeaveFeedRequest.serializer())) }, TakeAwayResult.serializer())

    override suspend fun leaveFrame(frameId: String, request: LeaveFrameRequest): TakeAwayResult? =
        c.decodeOrNull(c.call { url(c.ep("frames/$frameId/leave")).post(c.json(request, LeaveFrameRequest.serializer())) }, TakeAwayResult.serializer())

    override suspend fun formerPlaces(): FormerPlaces =
        c.decode(c.call { url(c.ep("me/former-places")).get() }, FormerPlaces.serializer())

    override suspend fun takeAwayFromFeed(feedId: String, request: TakeAwayRequest, idempotencyKey: String): TakeAwayResult =
        c.decode(c.call {
            url(c.ep("feeds/$feedId/take-away")).header("Idempotency-Key", idempotencyKey).post(c.json(request, TakeAwayRequest.serializer()))
        }, TakeAwayResult.serializer())

    override suspend fun takeAwayFromFrame(frameId: String, idempotencyKey: String): TakeAwayResult =
        c.decode(c.call { url(c.ep("frames/$frameId/take-away")).header("Idempotency-Key", idempotencyKey).post(c.empty) }, TakeAwayResult.serializer())

    override suspend fun frameTakenAway(frameId: String): TakenAwayGroupList =
        c.decode(c.call { url(c.ep("frames/$frameId/taken-away")).get() }, TakenAwayGroupList.serializer())

    override suspend fun feedTakenAway(feedId: String): TakenAwayGroupList =
        c.decode(c.call { url(c.ep("feeds/$feedId/taken-away")).get() }, TakenAwayGroupList.serializer())

    override suspend fun takenAwayPlacements(frameId: String, takingId: String?, cursor: String?, limit: Int): PlacementsPage =
        c.decode(c.call {
            url(c.ep("frames/$frameId/placements", mapOf("visibility" to "taken_away", "taking_id" to takingId, "cursor" to cursor, "limit" to limit.toString()))).get()
        }, PlacementsPage.serializer())

    override suspend fun createKeepRequest(request: KeepRequestCreate, idempotencyKey: String): KeepRequest =
        c.decode(c.call {
            url(c.ep("keep-requests")).header("Idempotency-Key", idempotencyKey).post(c.json(request, KeepRequestCreate.serializer()))
        }, KeepRequest.serializer())

    override suspend fun myKeepRequests(status: String?): KeepRequestList =
        c.decode(c.call { url(c.ep("me/keep-requests", mapOf("status" to status))).get() }, KeepRequestList.serializer())

    override suspend fun frameKeepRequests(frameId: String): KeepRequestList =
        c.decode(c.call { url(c.ep("frames/$frameId/keep-requests")).get() }, KeepRequestList.serializer())

    override suspend fun feedKeepRequests(feedId: String): KeepRequestList =
        c.decode(c.call { url(c.ep("feeds/$feedId/keep-requests")).get() }, KeepRequestList.serializer())

    override suspend fun keepRequest(requestId: String): KeepRequest =
        c.decode(c.call { url(c.ep("keep-requests/$requestId")).get() }, KeepRequest.serializer())

    override suspend fun answerKeepRequest(requestId: String, answer: KeepRequestAnswer): KeepRequest =
        c.decode(c.call { url(c.ep("keep-requests/$requestId/answer")).post(c.json(answer, KeepRequestAnswer.serializer())) }, KeepRequest.serializer())

    override suspend fun memories(): MemoryPreferences =
        c.decode(c.call { url(c.ep("me/memories")).get() }, MemoryPreferences.serializer())

    override suspend fun updateMemories(update: MemoryPreferencesUpdate): MemoryPreferences =
        c.decode(c.call { url(c.ep("me/memories")).put(c.json(update, MemoryPreferencesUpdate.serializer())) }, MemoryPreferences.serializer())

    override suspend fun deleteAccount(deletion: AccountDeletion) =
        c.expectOk(c.call { url(c.ep("me")).delete(c.json(deletion, AccountDeletion.serializer())) })
}
