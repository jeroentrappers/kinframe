package app.kinframe.core.servers

import app.kinframe.core.api.Send
import app.kinframe.core.social.*

/**
 * The family app's [SocialApi] across several servers (docs/spec/multi-server.md §2.2): Home's timeline interleaved
 * by time, Chats merged by last message, the badge summed; posts, threads, conversations and people go to the server
 * they came from ([AccountIndex]); everything else to the primary account.
 */
class RoutingSocialApi(private val routes: Routes, primary: SocialApi) : SocialApi by primary {

    private val socials: List<Pair<ServerRoute, SocialApi>>
        get() = routes.all().mapNotNull { r -> r.social?.let { r to it } }

    private suspend fun <T> on(key: String?, block: suspend SocialApi.() -> T): T {
        val r = routes.forKey(key)
        val api = r.social ?: throw java.io.IOException("no social api")
        return api.block().also { routes.index.learn(r.id, it) }
    }

    private suspend fun <T> eachSocial(filter: (ServerRoute) -> Boolean = { true }, block: suspend (ServerRoute, SocialApi) -> T): List<Pair<ServerRoute, T>> {
        val apis = socials.filter { filter(it.first) }
        return routes.each(apis.map { it.first }) { r -> block(r, apis.first { it.first.id == r.id }.second) }
    }

    // ---- merged

    override suspend fun timeline(before: String?, limit: Int, feedId: String?, circleId: String?, promptId: String?, frameId: String?): TimelinePage {
        val scoped = feedId ?: frameId ?: circleId
        if (scoped != null || promptId != null) return on(scoped) { timeline(before, limit, feedId, circleId, promptId, frameId) }
        val cursors = Merging.decodeCursor(before)
        val pages = eachSocial({ (it.discovery.feeds && it.discovery.social) && (cursors == null || it.id in cursors) }) {
            r, api -> api.timeline(cursors?.get(r.id), limit, null, null, null, null)
        }
        if (pages.size == 1 && cursors == null) return pages.single().second
        return TimelinePage(
            posts = Merging.interleave(pages.map { it.second.posts }) { it.createdAt },
            nextBefore = Merging.encodeCursor(pages.associate { it.first.id to it.second.nextBefore }),
        )
    }

    override suspend fun conversations(archived: Boolean): List<Conversation> =
        eachSocial({ it.discovery.chats }) { _, a -> a.conversations(archived) }.flatMap { it.second }.sortedByDescending { it.lastActivityAt }

    override suspend fun contacts(): List<Person> = eachSocial({ it.discovery.chats }) { _, a -> a.contacts() }.flatMap { it.second }

    override suspend fun blocks(): List<Block> = eachSocial({ it.discovery.chats }) { _, a -> a.blocks() }.flatMap { it.second }

    /** The sum of every account's badge (README §5.22). */
    override suspend fun badge(): Badge = eachSocial({ it.discovery.chats || it.discovery.social }) { _, a -> a.badge() }.map { it.second }
        .fold(Badge()) { a, b -> Badge(a.total + b.total, a.conversations + b.conversations, a.threads + b.threads) }

    /** A new chat lives on the server of the people in it (contacts are per server). */
    override suspend fun createConversation(create: ConversationCreate, idempotencyKey: String): Conversation =
        on(create.memberIds.firstOrNull()) { createConversation(create, idempotencyKey) }

    /** Every account's memories; each group's family and posts are recorded for their account ("Don't show again"). */
    override suspend fun onThisDay(): OnThisDay {
        val days = eachSocial({ it.discovery.engagement }) { _, a -> a.onThisDay() }.map { it.second }
        return OnThisDay(days.firstOrNull()?.date.orEmpty(), days.flatMap { it.groups })
    }

    /**
     * Every account's weekly summary of [weekOf] (Home shows one card per server, each opening its own summary), by
     * account id; servers without one that week (404) are left out. Throws only when every server failed.
     */
    suspend fun weeklySummaries(weekOf: String): List<Pair<String, WeeklySummary>> =
        eachSocial({ it.discovery.engagement }) { _, a -> a.weeklySummary(weekOf) }.map { it.first.id to it.second }

    /**
     * The same client pinned to one account, for that server's own settings (reminders, blocked people, milestones,
     * operator reports, the weekly summary): everything goes there, and the ids it answers are recorded for it, so a
     * post or chat opened from those screens opens on that server.
     */
    fun forAccount(account: String): SocialApi = routes.byId(account)?.social?.let { RoutingSocialApi(routes.only(account), it) } ?: this

    /** The operator's reports of the primary account (or the pinned one); their posts and chats are recorded for it. */
    override suspend fun operatorReports(status: String): List<Report> = on(null) { operatorReports(status) }

    // ---- routed by id (generated from the SocialApi interface)

    override suspend fun post(postId: String): TimelinePost = on(postId) { post(postId) }
    override suspend fun comments(postId: String, after: String?, limit: Int): CommentPage = on(postId) { comments(postId, after, limit) }
    override suspend fun createComment(postId: String, comment: CommentCreate, idempotencyKey: String): Comment = on(postId) { createComment(postId, comment, idempotencyKey) }
    override suspend fun updateComment(postId: String, commentId: String, update: CommentUpdate): Comment = on(postId) { updateComment(postId, commentId, update) }
    override suspend fun deleteComment(postId: String, commentId: String): Unit = on(postId) { deleteComment(postId, commentId) }
    override suspend fun setThreadState(postId: String, state: String): ThreadSummary = on(postId) { setThreadState(postId, state) }
    override suspend fun markThreadRead(postId: String, upTo: String): Unit = on(postId) { markThreadRead(postId, upTo) }
    override suspend fun setThreadMute(postId: String, muted: Boolean): Unit = on(postId) { setThreadMute(postId, muted) }
    override suspend fun reactions(targetKind: String, targetId: String, postId: String?): ReactionList = on(postId ?: targetId) { reactions(targetKind, targetId, postId) }
    override suspend fun setReaction(targetKind: String, targetId: String, emoji: String?, postId: String?): ReactionSummary = on(postId ?: targetId) { setReaction(targetKind, targetId, emoji, postId) }
    override suspend fun tagPostPrompt(postId: String, promptId: String?): TimelinePost = on(postId) { tagPostPrompt(postId, promptId) }
    override suspend fun circles(feedId: String): List<Circle> = on(feedId) { circles(feedId) }
    override suspend fun createCircle(feedId: String, create: CircleCreate, idempotencyKey: String): Circle = on(feedId) { createCircle(feedId, create, idempotencyKey) }
    override suspend fun circle(feedId: String, circleId: String): Circle = on(feedId) { circle(feedId, circleId) }
    override suspend fun updateCircle(feedId: String, circleId: String, update: CircleUpdate): Circle = on(feedId) { updateCircle(feedId, circleId, update) }
    override suspend fun deleteCircle(feedId: String, circleId: String): Unit = on(feedId) { deleteCircle(feedId, circleId) }
    override suspend fun addCircleMembers(feedId: String, circleId: String, userIds: List<String>): Circle = on(feedId) { addCircleMembers(feedId, circleId, userIds) }
    override suspend fun updateCircleMember(feedId: String, circleId: String, userId: String, role: String): Circle = on(feedId) { updateCircleMember(feedId, circleId, userId, role) }
    override suspend fun removeCircleMember(feedId: String, circleId: String, userId: String): Unit = on(feedId) { removeCircleMember(feedId, circleId, userId) }
    override suspend fun conversation(conversationId: String): Conversation = on(conversationId) { conversation(conversationId) }
    override suspend fun renameConversation(conversationId: String, name: String): Conversation = on(conversationId) { renameConversation(conversationId, name) }
    override suspend fun putConversationPhoto(conversationId: String, bytes: ByteArray, mime: String): Conversation = on(conversationId) { putConversationPhoto(conversationId, bytes, mime) }
    override suspend fun deleteConversationPhoto(conversationId: String): Unit = on(conversationId) { deleteConversationPhoto(conversationId) }
    override suspend fun addConversationMembers(conversationId: String, userIds: List<String>): Conversation = on(conversationId) { addConversationMembers(conversationId, userIds) }
    override suspend fun updateConversationMember(conversationId: String, userId: String, role: String): Conversation = on(conversationId) { updateConversationMember(conversationId, userId, role) }
    override suspend fun removeConversationMember(conversationId: String, userId: String): Unit = on(conversationId) { removeConversationMember(conversationId, userId) }
    override suspend fun updateConversationPreferences(conversationId: String, mute: String?, archived: Boolean?): Conversation = on(conversationId) { updateConversationPreferences(conversationId, mute, archived) }
    override suspend fun messages(conversationId: String, before: String?, after: String?, limit: Int): MessagePage = on(conversationId) { messages(conversationId, before, after, limit) }
    override suspend fun sendMessage(conversationId: String, message: MessageCreate, idempotencyKey: String): Message = on(conversationId) { sendMessage(conversationId, message, idempotencyKey) }
    override suspend fun message(conversationId: String, messageId: String): Message = on(conversationId) { message(conversationId, messageId) }
    override suspend fun updateMessage(conversationId: String, messageId: String, update: MessageUpdate): Message = on(conversationId) { updateMessage(conversationId, messageId, update) }
    override suspend fun deleteMessage(conversationId: String, messageId: String): Unit = on(conversationId) { deleteMessage(conversationId, messageId) }
    override suspend fun putMessageOnFrame(conversationId: String, messageId: String, request: PutOnFrameRequest, idempotencyKey: String): Send = on(conversationId) { putMessageOnFrame(conversationId, messageId, request, idempotencyKey) }
    override suspend fun markConversationRead(conversationId: String, messageId: String): Unit = on(conversationId) { markConversationRead(conversationId, messageId) }
    override suspend fun sendTyping(conversationId: String, activity: String): Unit = on(conversationId) { sendTyping(conversationId, activity) }
    override suspend fun person(userId: String): Person = on(userId) { person(userId) }
    override suspend fun mediaUrl(itemId: String, variant: String): String = on(itemId) { mediaUrl(itemId, variant) }
    override suspend fun block(userId: String): Unit = on(userId) { block(userId) }
    override suspend fun unblock(userId: String): Unit = on(userId) { unblock(userId) }
    override suspend fun feedReports(feedId: String): List<Report> = on(feedId) { feedReports(feedId) }
    override suspend fun resolveReport(reportId: String, action: String): Report = on(reportId) { resolveReport(reportId, action) }
    override suspend fun feedEngagement(feedId: String): FeedEngagement = on(feedId) { feedEngagement(feedId) }
    override suspend fun updateFeedEngagement(feedId: String, update: FeedEngagementUpdate): FeedEngagement = on(feedId) { updateFeedEngagement(feedId, update) }
    override suspend fun feedStreak(feedId: String): FeedStreak = on(feedId) { feedStreak(feedId) }
    override suspend fun feedPrompts(feedId: String): List<FeedPrompt> = on(feedId) { feedPrompts(feedId) }
    override suspend fun setFeedPrompt(feedId: String, weekOf: String, set: FeedPromptSet): FeedPrompt = on(feedId) { setFeedPrompt(feedId, weekOf, set) }
    override suspend fun resetFeedPrompt(feedId: String, weekOf: String): Unit = on(feedId) { resetFeedPrompt(feedId, weekOf) }
    override suspend fun feedRecap(feedId: String, month: String): MonthlyRecap = on(feedId) { feedRecap(feedId, month) }
    override suspend fun photoRequest(requestId: String): PhotoRequest = on(requestId) { photoRequest(requestId) }
    override suspend fun framePhotoRequests(frameId: String): List<PhotoRequest> = on(frameId) { framePhotoRequests(frameId) }
}
