package app.kinframe.core.social

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.FamilyAppHeader
import app.kinframe.core.api.JSON_MEDIA
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Send
import app.kinframe.core.api.SessionExpiredException
import app.kinframe.core.api.User
import app.kinframe.core.api.await
import app.kinframe.core.api.problemOrNull
import app.kinframe.core.api.throwForStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The person-facing family social API (README §5.18–§5.20; openapi tags `social`, `circles`, `chats`, `safety`,
 * `engagement`, and the operator's report list). Kept apart from `KinApi` so the areas can grow without touching it;
 * an interface so view models can be tested with fakes. [HttpSocialApi] is the real client.
 */
interface SocialApi {
    // ---- timeline, threads, reactions (social)

    suspend fun timeline(
        before: String? = null, limit: Int = 20, feedId: String? = null, circleId: String? = null,
        promptId: String? = null, frameId: String? = null,
    ): TimelinePage
    suspend fun post(postId: String): TimelinePost
    suspend fun comments(postId: String, after: String? = null, limit: Int = 100): CommentPage
    suspend fun createComment(postId: String, comment: CommentCreate, idempotencyKey: String): Comment
    suspend fun updateComment(postId: String, commentId: String, update: CommentUpdate): Comment
    suspend fun deleteComment(postId: String, commentId: String)
    suspend fun setThreadState(postId: String, state: String): ThreadSummary
    suspend fun markThreadRead(postId: String, upTo: String)
    suspend fun setThreadMute(postId: String, muted: Boolean)
    /** [postId] is required for `post_item` targets. */
    suspend fun reactions(targetKind: String, targetId: String, postId: String? = null): ReactionList
    /** [emoji] null removes the caller's reaction. */
    suspend fun setReaction(targetKind: String, targetId: String, emoji: String?, postId: String? = null): ReactionSummary
    /** [promptId] null removes the tag. */
    suspend fun tagPostPrompt(postId: String, promptId: String?): TimelinePost

    // ---- circles

    suspend fun circles(feedId: String): List<Circle>
    suspend fun createCircle(feedId: String, create: CircleCreate, idempotencyKey: String): Circle
    suspend fun circle(feedId: String, circleId: String): Circle
    suspend fun updateCircle(feedId: String, circleId: String, update: CircleUpdate): Circle
    suspend fun deleteCircle(feedId: String, circleId: String)
    suspend fun addCircleMembers(feedId: String, circleId: String, userIds: List<String>): Circle
    suspend fun updateCircleMember(feedId: String, circleId: String, userId: String, role: String): Circle
    /** Remove someone, or leave (the caller's own id). */
    suspend fun removeCircleMember(feedId: String, circleId: String, userId: String)

    // ---- chats

    suspend fun conversations(archived: Boolean = false): List<Conversation>
    /** `direct` is idempotent per pair (the existing one comes back). */
    suspend fun createConversation(create: ConversationCreate, idempotencyKey: String): Conversation
    suspend fun conversation(conversationId: String): Conversation
    suspend fun renameConversation(conversationId: String, name: String): Conversation
    /** JPEG, PNG or WebP. */
    suspend fun putConversationPhoto(conversationId: String, bytes: ByteArray, mime: String): Conversation
    suspend fun deleteConversationPhoto(conversationId: String)
    suspend fun addConversationMembers(conversationId: String, userIds: List<String>): Conversation
    suspend fun updateConversationMember(conversationId: String, userId: String, role: String): Conversation
    /** Remove someone, or leave (the caller's own id). */
    suspend fun removeConversationMember(conversationId: String, userId: String)
    /** [mute] a [MuteFor] value; [archived] archives or un-archives for the caller. */
    suspend fun updateConversationPreferences(conversationId: String, mute: String? = null, archived: Boolean? = null): Conversation
    /** Oldest first. No cursor = the newest [limit]. */
    suspend fun messages(conversationId: String, before: String? = null, after: String? = null, limit: Int = 50): MessagePage
    suspend fun sendMessage(conversationId: String, message: MessageCreate, idempotencyKey: String): Message
    suspend fun message(conversationId: String, messageId: String): Message
    suspend fun updateMessage(conversationId: String, messageId: String, update: MessageUpdate): Message
    suspend fun deleteMessage(conversationId: String, messageId: String)
    suspend fun putMessageOnFrame(conversationId: String, messageId: String, request: PutOnFrameRequest, idempotencyKey: String): Send
    suspend fun markConversationRead(conversationId: String, messageId: String)
    suspend fun sendTyping(conversationId: String, activity: String = TypingActivity.TYPING)
    suspend fun contacts(): List<Person>
    suspend fun person(userId: String): Person
    suspend fun badge(): Badge
    suspend fun linkPreview(url: String): LinkPreview
    /** `PATCH /me {show_read_receipts}`. */
    suspend fun setShowReadReceipts(on: Boolean): User
    /**
     * A short-lived signed URL for an item's media (`GET /items/{id}/media/{variant}` answers 302), for players that
     * can't send the bearer token: voice messages (`master` of [VoiceClip.itemId]) and full-size photos and videos.
     */
    suspend fun mediaUrl(itemId: String, variant: String = "master"): String

    // ---- safety

    suspend fun blocks(): List<Block>
    suspend fun block(userId: String)
    suspend fun unblock(userId: String)
    suspend fun report(report: ReportCreate): Report
    suspend fun feedReports(feedId: String): List<Report>
    /** Operators only; [status] `open`, `resolved` or `all`. */
    suspend fun operatorReports(status: String = "open"): List<Report>
    /** [action] `remove` or `dismiss`; `409 already_decided` when someone else was first. */
    suspend fun resolveReport(reportId: String, action: String): Report

    // ---- engagement

    suspend fun feedEngagement(feedId: String): FeedEngagement
    suspend fun updateFeedEngagement(feedId: String, update: FeedEngagementUpdate): FeedEngagement
    suspend fun feedStreak(feedId: String): FeedStreak
    suspend fun feedPrompts(feedId: String): List<FeedPrompt>
    suspend fun setFeedPrompt(feedId: String, weekOf: String, set: FeedPromptSet): FeedPrompt
    suspend fun resetFeedPrompt(feedId: String, weekOf: String)
    suspend fun promptLibrary(): List<PromptLibraryEntry>
    suspend fun onThisDay(): OnThisDay
    suspend fun weeklySummary(weekOf: String): WeeklySummary
    /** [month] `YYYY-MM`. */
    suspend fun feedRecap(feedId: String, month: String): MonthlyRecap
    suspend fun milestones(): List<Milestone>
    suspend fun updateMilestone(key: String, update: MilestoneUpdate): Milestone
    suspend fun reminderPreferences(): ReminderPreferences
    suspend fun updateReminderPreferences(update: ReminderPreferencesUpdate): ReminderPreferences
    suspend fun photoRequest(requestId: String): PhotoRequest
    suspend fun framePhotoRequests(frameId: String): List<PhotoRequest>
}

/** The real [SocialApi] over HTTP: bearer token, one refresh on 401, then the session is over (like `HttpKinApi`). */
class HttpSocialApi(
    private val http: OkHttpClient,
    @Volatile var apiBase: HttpUrl,
    private val tokens: AccessTokenSource,
    private val userAgent: String = "kinframe-family",
    private val appHeader: String? = null,
    private val acceptLanguage: () -> String? = { null },
) : SocialApi {

    private val noRedirects: OkHttpClient by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    private fun ep(path: String, query: Map<String, String?> = emptyMap()): HttpUrl =
        apiBase.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()

    private fun json(obj: JsonElement): RequestBody = KinJson.encodeToString(JsonElement.serializer(), obj).toRequestBody(JSON_MEDIA)

    private fun <T> json(value: T, ser: KSerializer<T>): RequestBody = KinJson.encodeToString(ser, value).toRequestBody(JSON_MEDIA)

    private val empty: RequestBody get() = ByteArray(0).toRequestBody(null)

    private suspend fun call(client: OkHttpClient = http, spec: Request.Builder.() -> Request.Builder): Response {
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
            val r = client.newCall(req).await()
            if (r.code != 401) return r
            val p = r.use { it.problemOrNull() }
            if (attempt++ >= 1) {
                tokens.onSessionExpired()
                throw SessionExpiredException(p)
            }
        }
    }

    private suspend fun <T> Response.decode(ser: KSerializer<T>): T = withContext(Dispatchers.IO) {
        use {
            if (!isSuccessful) throwForStatus()
            KinJson.decodeFromString(ser, body.string())
        }
    }

    private suspend fun Response.expectOk() = withContext(Dispatchers.IO) { use { if (!isSuccessful) throwForStatus() } }

    private fun ids(userIds: List<String>) = buildJsonObject { put("user_ids", buildJsonArray { userIds.forEach { add(JsonPrimitive(it)) } }) }

    // ------------------------------------------------------------------ social

    override suspend fun timeline(before: String?, limit: Int, feedId: String?, circleId: String?, promptId: String?, frameId: String?): TimelinePage =
        call {
            url(ep("timeline", mapOf("before" to before, "limit" to limit.toString(), "feed_id" to feedId, "circle_id" to circleId,
                "prompt_id" to promptId, "frame_id" to frameId))).get()
        }.decode(TimelinePage.serializer())

    override suspend fun post(postId: String): TimelinePost = call { url(ep("posts/$postId")).get() }.decode(TimelinePost.serializer())

    override suspend fun comments(postId: String, after: String?, limit: Int): CommentPage =
        call { url(ep("posts/$postId/comments", mapOf("after" to after, "limit" to limit.toString()))).get() }.decode(CommentPage.serializer())

    override suspend fun createComment(postId: String, comment: CommentCreate, idempotencyKey: String): Comment = call {
        url(ep("posts/$postId/comments")).header("Idempotency-Key", idempotencyKey).post(json(comment, CommentCreate.serializer()))
    }.decode(Comment.serializer())

    override suspend fun updateComment(postId: String, commentId: String, update: CommentUpdate): Comment =
        call { url(ep("posts/$postId/comments/$commentId")).patch(json(update, CommentUpdate.serializer())) }.decode(Comment.serializer())

    override suspend fun deleteComment(postId: String, commentId: String) = call { url(ep("posts/$postId/comments/$commentId")).delete() }.expectOk()

    override suspend fun setThreadState(postId: String, state: String): ThreadSummary =
        call { url(ep("posts/$postId/thread")).put(json(buildJsonObject { put("state", JsonPrimitive(state)) })) }.decode(ThreadSummary.serializer())

    override suspend fun markThreadRead(postId: String, upTo: String) =
        call { url(ep("posts/$postId/read")).put(json(buildJsonObject { put("up_to", JsonPrimitive(upTo)) })) }.expectOk()

    override suspend fun setThreadMute(postId: String, muted: Boolean) =
        call { url(ep("posts/$postId/mute")).put(json(buildJsonObject { put("muted", JsonPrimitive(muted)) })) }.expectOk()

    override suspend fun reactions(targetKind: String, targetId: String, postId: String?): ReactionList =
        call { url(ep("reactions/$targetKind/$targetId", mapOf("post_id" to postId))).get() }.decode(ReactionList.serializer())

    override suspend fun setReaction(targetKind: String, targetId: String, emoji: String?, postId: String?): ReactionSummary = call {
        url(ep("reactions/$targetKind/$targetId", mapOf("post_id" to postId)))
            .put(json(buildJsonObject { put("emoji", emoji?.let { JsonPrimitive(it) } ?: JsonNull) }))
    }.decode(ReactionSummary.serializer())

    override suspend fun tagPostPrompt(postId: String, promptId: String?): TimelinePost = call {
        url(ep("posts/$postId/prompt")).put(json(buildJsonObject { put("prompt_id", promptId?.let { JsonPrimitive(it) } ?: JsonNull) }))
    }.decode(TimelinePost.serializer())

    // ------------------------------------------------------------------ circles

    override suspend fun circles(feedId: String): List<Circle> =
        call { url(ep("feeds/$feedId/circles")).get() }.decode(ListSerializer(Circle.serializer()))

    override suspend fun createCircle(feedId: String, create: CircleCreate, idempotencyKey: String): Circle = call {
        url(ep("feeds/$feedId/circles")).header("Idempotency-Key", idempotencyKey).post(json(create, CircleCreate.serializer()))
    }.decode(Circle.serializer())

    override suspend fun circle(feedId: String, circleId: String): Circle =
        call { url(ep("feeds/$feedId/circles/$circleId")).get() }.decode(Circle.serializer())

    override suspend fun updateCircle(feedId: String, circleId: String, update: CircleUpdate): Circle =
        call { url(ep("feeds/$feedId/circles/$circleId")).patch(json(update, CircleUpdate.serializer())) }.decode(Circle.serializer())

    override suspend fun deleteCircle(feedId: String, circleId: String) = call { url(ep("feeds/$feedId/circles/$circleId")).delete() }.expectOk()

    override suspend fun addCircleMembers(feedId: String, circleId: String, userIds: List<String>): Circle =
        call { url(ep("feeds/$feedId/circles/$circleId/members")).post(json(ids(userIds))) }.decode(Circle.serializer())

    override suspend fun updateCircleMember(feedId: String, circleId: String, userId: String, role: String): Circle = call {
        url(ep("feeds/$feedId/circles/$circleId/members/$userId")).patch(json(buildJsonObject { put("role", JsonPrimitive(role)) }))
    }.decode(Circle.serializer())

    override suspend fun removeCircleMember(feedId: String, circleId: String, userId: String) =
        call { url(ep("feeds/$feedId/circles/$circleId/members/$userId")).delete() }.expectOk()

    // ------------------------------------------------------------------ chats

    override suspend fun conversations(archived: Boolean): List<Conversation> =
        call { url(ep("conversations", mapOf("archived" to archived.takeIf { it }?.toString()))).get() }.decode(ListSerializer(Conversation.serializer()))

    override suspend fun createConversation(create: ConversationCreate, idempotencyKey: String): Conversation = call {
        url(ep("conversations")).header("Idempotency-Key", idempotencyKey).post(json(create, ConversationCreate.serializer()))
    }.decode(Conversation.serializer())

    override suspend fun conversation(conversationId: String): Conversation =
        call { url(ep("conversations/$conversationId")).get() }.decode(Conversation.serializer())

    override suspend fun renameConversation(conversationId: String, name: String): Conversation =
        call { url(ep("conversations/$conversationId")).patch(json(buildJsonObject { put("name", JsonPrimitive(name)) })) }.decode(Conversation.serializer())

    override suspend fun putConversationPhoto(conversationId: String, bytes: ByteArray, mime: String): Conversation =
        call { url(ep("conversations/$conversationId/photo")).put(bytes.toRequestBody(mime.toMediaType())) }.decode(Conversation.serializer())

    override suspend fun deleteConversationPhoto(conversationId: String) = call { url(ep("conversations/$conversationId/photo")).delete() }.expectOk()

    override suspend fun addConversationMembers(conversationId: String, userIds: List<String>): Conversation =
        call { url(ep("conversations/$conversationId/members")).post(json(ids(userIds))) }.decode(Conversation.serializer())

    override suspend fun updateConversationMember(conversationId: String, userId: String, role: String): Conversation = call {
        url(ep("conversations/$conversationId/members/$userId")).patch(json(buildJsonObject { put("role", JsonPrimitive(role)) }))
    }.decode(Conversation.serializer())

    override suspend fun removeConversationMember(conversationId: String, userId: String) =
        call { url(ep("conversations/$conversationId/members/$userId")).delete() }.expectOk()

    override suspend fun updateConversationPreferences(conversationId: String, mute: String?, archived: Boolean?): Conversation = call {
        url(ep("conversations/$conversationId/preferences")).patch(json(buildJsonObject {
            mute?.let { put("mute", JsonPrimitive(it)) }
            archived?.let { put("archived", JsonPrimitive(it)) }
        }))
    }.decode(Conversation.serializer())

    override suspend fun messages(conversationId: String, before: String?, after: String?, limit: Int): MessagePage = call {
        url(ep("conversations/$conversationId/messages", mapOf("before" to before, "after" to after, "limit" to limit.toString()))).get()
    }.decode(MessagePage.serializer())

    override suspend fun sendMessage(conversationId: String, message: MessageCreate, idempotencyKey: String): Message = call {
        url(ep("conversations/$conversationId/messages")).header("Idempotency-Key", idempotencyKey).post(json(message, MessageCreate.serializer()))
    }.decode(Message.serializer())

    override suspend fun message(conversationId: String, messageId: String): Message =
        call { url(ep("conversations/$conversationId/messages/$messageId")).get() }.decode(Message.serializer())

    override suspend fun updateMessage(conversationId: String, messageId: String, update: MessageUpdate): Message =
        call { url(ep("conversations/$conversationId/messages/$messageId")).patch(json(update, MessageUpdate.serializer())) }.decode(Message.serializer())

    override suspend fun deleteMessage(conversationId: String, messageId: String) =
        call { url(ep("conversations/$conversationId/messages/$messageId")).delete() }.expectOk()

    override suspend fun putMessageOnFrame(conversationId: String, messageId: String, request: PutOnFrameRequest, idempotencyKey: String): Send = call {
        url(ep("conversations/$conversationId/messages/$messageId/send")).header("Idempotency-Key", idempotencyKey)
            .post(json(request, PutOnFrameRequest.serializer()))
    }.decode(Send.serializer())

    override suspend fun markConversationRead(conversationId: String, messageId: String) = call {
        url(ep("conversations/$conversationId/read")).put(json(buildJsonObject { put("message_id", JsonPrimitive(messageId)) }))
    }.expectOk()

    override suspend fun sendTyping(conversationId: String, activity: String) = call {
        url(ep("conversations/$conversationId/typing")).post(json(buildJsonObject { put("activity", JsonPrimitive(activity)) }))
    }.expectOk()

    override suspend fun contacts(): List<Person> = call { url(ep("contacts")).get() }.decode(ListSerializer(Person.serializer()))

    override suspend fun person(userId: String): Person = call { url(ep("people/$userId")).get() }.decode(Person.serializer())

    override suspend fun badge(): Badge = call { url(ep("me/badge")).get() }.decode(Badge.serializer())

    override suspend fun linkPreview(url: String): LinkPreview =
        call { url(ep("link-previews")).post(json(buildJsonObject { put("url", JsonPrimitive(url)) })) }.decode(LinkPreview.serializer())

    override suspend fun setShowReadReceipts(on: Boolean): User =
        call { url(ep("me")).patch(json(buildJsonObject { put("show_read_receipts", JsonPrimitive(on)) })) }.decode(User.serializer())

    override suspend fun mediaUrl(itemId: String, variant: String): String {
        val r = call(noRedirects) { url(ep("items/$itemId/media/$variant")).get() }
        return withContext(Dispatchers.IO) {
            r.use {
                when {
                    it.isRedirect -> it.header("Location")?.let { loc -> it.request.url.resolve(loc)?.toString() } ?: it.throwForStatus()
                    it.isSuccessful -> it.request.url.toString()
                    else -> it.throwForStatus()
                }
            }
        }
    }

    // ------------------------------------------------------------------ safety

    override suspend fun blocks(): List<Block> = call { url(ep("blocks")).get() }.decode(ListSerializer(Block.serializer()))

    override suspend fun block(userId: String) = call { url(ep("blocks/$userId")).put(empty) }.expectOk()

    override suspend fun unblock(userId: String) = call { url(ep("blocks/$userId")).delete() }.expectOk()

    override suspend fun report(report: ReportCreate): Report =
        call { url(ep("reports")).post(json(report, ReportCreate.serializer())) }.decode(Report.serializer())

    override suspend fun feedReports(feedId: String): List<Report> =
        call { url(ep("feeds/$feedId/reports")).get() }.decode(ListSerializer(Report.serializer()))

    override suspend fun operatorReports(status: String): List<Report> =
        call { url(ep("operator/reports", mapOf("status" to status))).get() }.decode(ListSerializer(Report.serializer()))

    override suspend fun resolveReport(reportId: String, action: String): Report =
        call { url(ep("reports/$reportId/resolve")).post(json(buildJsonObject { put("action", JsonPrimitive(action)) })) }.decode(Report.serializer())

    // ------------------------------------------------------------------ engagement

    override suspend fun feedEngagement(feedId: String): FeedEngagement =
        call { url(ep("feeds/$feedId/engagement")).get() }.decode(FeedEngagement.serializer())

    override suspend fun updateFeedEngagement(feedId: String, update: FeedEngagementUpdate): FeedEngagement =
        call { url(ep("feeds/$feedId/engagement")).patch(json(update, FeedEngagementUpdate.serializer())) }.decode(FeedEngagement.serializer())

    override suspend fun feedStreak(feedId: String): FeedStreak = call { url(ep("feeds/$feedId/streak")).get() }.decode(FeedStreak.serializer())

    override suspend fun feedPrompts(feedId: String): List<FeedPrompt> =
        call { url(ep("feeds/$feedId/prompts")).get() }.decode(ListSerializer(FeedPrompt.serializer()))

    override suspend fun setFeedPrompt(feedId: String, weekOf: String, set: FeedPromptSet): FeedPrompt =
        call { url(ep("feeds/$feedId/prompts/$weekOf")).put(json(set, FeedPromptSet.serializer())) }.decode(FeedPrompt.serializer())

    override suspend fun resetFeedPrompt(feedId: String, weekOf: String) = call { url(ep("feeds/$feedId/prompts/$weekOf")).delete() }.expectOk()

    override suspend fun promptLibrary(): List<PromptLibraryEntry> =
        call { url(ep("prompts")).get() }.decode(ListSerializer(PromptLibraryEntry.serializer()))

    override suspend fun onThisDay(): OnThisDay = call { url(ep("me/on-this-day")).get() }.decode(OnThisDay.serializer())

    override suspend fun weeklySummary(weekOf: String): WeeklySummary =
        call { url(ep("me/weekly-summaries/$weekOf")).get() }.decode(WeeklySummary.serializer())

    override suspend fun feedRecap(feedId: String, month: String): MonthlyRecap =
        call { url(ep("feeds/$feedId/recaps/$month")).get() }.decode(MonthlyRecap.serializer())

    override suspend fun milestones(): List<Milestone> = call { url(ep("me/milestones")).get() }.decode(ListSerializer(Milestone.serializer()))

    override suspend fun updateMilestone(key: String, update: MilestoneUpdate): Milestone =
        call { url(ep("me/milestones/$key")).patch(json(update, MilestoneUpdate.serializer())) }.decode(Milestone.serializer())

    override suspend fun reminderPreferences(): ReminderPreferences =
        call { url(ep("me/reminders")).get() }.decode(ReminderPreferences.serializer())

    override suspend fun updateReminderPreferences(update: ReminderPreferencesUpdate): ReminderPreferences =
        call { url(ep("me/reminders")).patch(json(update, ReminderPreferencesUpdate.serializer())) }.decode(ReminderPreferences.serializer())

    override suspend fun photoRequest(requestId: String): PhotoRequest =
        call { url(ep("photo-requests/$requestId")).get() }.decode(PhotoRequest.serializer())

    override suspend fun framePhotoRequests(frameId: String): List<PhotoRequest> =
        call { url(ep("frames/$frameId/photo-requests")).get() }.decode(ListSerializer(PhotoRequest.serializer()))
}
