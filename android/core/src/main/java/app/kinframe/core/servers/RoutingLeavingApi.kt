package app.kinframe.core.servers

import app.kinframe.core.api.PlacementsPage
import app.kinframe.core.leaving.AccountDeletion
import app.kinframe.core.leaving.FormerPlaces
import app.kinframe.core.leaving.KeepRequest
import app.kinframe.core.leaving.KeepRequestAnswer
import app.kinframe.core.leaving.KeepRequestCreate
import app.kinframe.core.leaving.KeepRequestList
import app.kinframe.core.leaving.LeaveFeedRequest
import app.kinframe.core.leaving.LeaveFrameRequest
import app.kinframe.core.leaving.LeavePreview
import app.kinframe.core.leaving.LeavingApi
import app.kinframe.core.leaving.MemoryPreferences
import app.kinframe.core.leaving.MemoryPreferencesUpdate
import app.kinframe.core.leaving.TakeAwayRequest
import app.kinframe.core.leaving.TakeAwayResult
import app.kinframe.core.leaving.TakenAwayGroupList

/**
 * [LeavingApi] across accounts: the person's keep requests and former places from every server that has leaving;
 * everything about one family, frame or request on its own server; memories and account deletion on the primary
 * account (they are per account: use that account's client for another one).
 */
class RoutingLeavingApi(private val routes: Routes) : LeavingApi {
    private fun on(key: String?): LeavingApi {
        val r = routes.forKey(key)
        return r.leaving ?: throw java.io.IOException("leaving is off on ${r.discovery.serverName}")
    }

    private fun primary(): LeavingApi = routes.primary?.leaving ?: throw java.io.IOException("leaving is off")

    private val withLeaving get() = routes.all().filter { it.leaving != null }

    override suspend fun leavePreview(feedId: String?, frameId: String?) = on(feedId ?: frameId).leavePreview(feedId, frameId)
    override suspend fun leaveFeed(feedId: String, request: LeaveFeedRequest) = on(feedId).leaveFeed(feedId, request)
    override suspend fun leaveFrame(frameId: String, request: LeaveFrameRequest) = on(frameId).leaveFrame(frameId, request)

    override suspend fun formerPlaces(): FormerPlaces {
        val all = routes.each(withLeaving) { it.leaving!!.formerPlaces() }
        all.forEach { (r, p) -> routes.index.putAll(p.feeds.map { it.feedId } + p.frames.map { it.frameId }, r.id) }
        return FormerPlaces(all.flatMap { it.second.feeds }, all.flatMap { it.second.frames })
    }

    override suspend fun takeAwayFromFeed(feedId: String, request: TakeAwayRequest, idempotencyKey: String): TakeAwayResult =
        on(feedId).takeAwayFromFeed(feedId, request, idempotencyKey)
    override suspend fun takeAwayFromFrame(frameId: String, idempotencyKey: String) = on(frameId).takeAwayFromFrame(frameId, idempotencyKey)
    override suspend fun frameTakenAway(frameId: String): TakenAwayGroupList = on(frameId).frameTakenAway(frameId)
    override suspend fun feedTakenAway(feedId: String): TakenAwayGroupList = on(feedId).feedTakenAway(feedId)
    override suspend fun takenAwayPlacements(frameId: String, takingId: String?, cursor: String?, limit: Int): PlacementsPage =
        on(frameId).takenAwayPlacements(frameId, takingId, cursor, limit)
    override suspend fun createKeepRequest(request: KeepRequestCreate, idempotencyKey: String): KeepRequest =
        on(request.frameId ?: request.feedId).createKeepRequest(request, idempotencyKey)

    override suspend fun myKeepRequests(status: String?): KeepRequestList {
        val all = routes.each(withLeaving) { it.leaving!!.myKeepRequests(status) }
        all.forEach { (r, l) -> routes.index.putAll(l.requests.map { it.id }, r.id) }
        return KeepRequestList(all.flatMap { it.second.requests }.sortedByDescending { it.askedAt })
    }

    override suspend fun frameKeepRequests(frameId: String) = on(frameId).frameKeepRequests(frameId)
    override suspend fun feedKeepRequests(feedId: String) = on(feedId).feedKeepRequests(feedId)
    override suspend fun keepRequest(requestId: String) = on(requestId).keepRequest(requestId)
    override suspend fun answerKeepRequest(requestId: String, answer: KeepRequestAnswer) = on(requestId).answerKeepRequest(requestId, answer)
    override suspend fun memories(): MemoryPreferences = primary().memories()
    override suspend fun updateMemories(update: MemoryPreferencesUpdate): MemoryPreferences = primary().updateMemories(update)
    override suspend fun deleteAccount(deletion: AccountDeletion) = primary().deleteAccount(deletion)
}
