package app.kinframe.family.data

import app.kinframe.core.api.KinJson
import app.kinframe.core.social.Badge
import app.kinframe.core.social.Circle
import app.kinframe.core.social.Comment
import app.kinframe.core.social.Conversation
import app.kinframe.core.social.FeedPrompt
import app.kinframe.core.social.FeedStreakSummary
import app.kinframe.core.social.Message
import app.kinframe.core.social.Milestone
import app.kinframe.core.social.PhotoRequest
import app.kinframe.core.social.ReactionSummary
import app.kinframe.core.social.Report
import app.kinframe.core.social.SocialEvents
import app.kinframe.core.social.ThreadSummary
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Family social events on the user websocket (README §6.2). */
sealed interface SocialLive : LiveEvent {
    data class PostUpdated(val postId: String, val feedId: String?, val frameId: String?, val thread: ThreadSummary?) : SocialLive
    data class PostRemoved(val postId: String, val feedId: String?, val frameId: String?) : SocialLive
    data class CommentCreated(val postId: String, val comment: Comment) : SocialLive
    data class CommentUpdated(val postId: String, val comment: Comment) : SocialLive
    data class CommentDeleted(val postId: String, val commentId: String) : SocialLive
    data class ThreadRead(val postId: String, val readUpTo: String) : SocialLive
    data class ReactionUpdated(
        val targetKind: String, val targetId: String, val postId: String?, val conversationId: String?, val summary: ReactionSummary,
    ) : SocialLive
    data class CircleUpdated(val feedId: String, val circle: Circle) : SocialLive
    data class CircleRemoved(val feedId: String, val circleId: String) : SocialLive
    data class ConversationUpdated(val conversation: Conversation) : SocialLive
    data class ConversationRemoved(val conversationId: String) : SocialLive
    data class MessageCreated(val conversationId: String, val message: Message) : SocialLive
    data class MessageUpdated(val conversationId: String, val message: Message) : SocialLive
    data class ReadUpdated(val conversationId: String, val userId: String, val lastReadMessageId: String?) : SocialLive
    data class Typing(val conversationId: String, val userId: String, val displayName: String, val activity: String, val expiresAt: String?) : SocialLive
    data class BadgeUpdated(val badge: Badge) : SocialLive
    data class ReportCreated(val report: Report) : SocialLive
    data class PhotoRequestChanged(val request: PhotoRequest, val created: Boolean) : SocialLive
    data class StreakUpdated(val feedId: String, val streak: FeedStreakSummary) : SocialLive
    data class PromptUpdated(val feedId: String, val prompt: FeedPrompt) : SocialLive
    data class MilestoneAchieved(val milestone: Milestone) : SocialLive

    companion object {
        /** The family social event of this [type], or null for types it doesn't know. Throws on a malformed payload. */
        fun parse(type: String?, o: JsonObject): SocialLive? {
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            fun <T> obj(k: String, ser: KSerializer<T>): T = KinJson.decodeFromJsonElement(ser, o[k]!!)
            fun <T> objOrNull(k: String, ser: KSerializer<T>): T? = (o[k] as? JsonObject)?.let { KinJson.decodeFromJsonElement(ser, it) }
            return when (type) {
                SocialEvents.POST_UPDATED -> PostUpdated(s("post_id")!!, s("feed_id"), s("frame_id"), objOrNull("thread", ThreadSummary.serializer()))
                SocialEvents.POST_REMOVED -> PostRemoved(s("post_id")!!, s("feed_id"), s("frame_id"))
                SocialEvents.COMMENT_CREATED -> CommentCreated(s("post_id")!!, obj("comment", Comment.serializer()))
                SocialEvents.COMMENT_UPDATED -> CommentUpdated(s("post_id")!!, obj("comment", Comment.serializer()))
                SocialEvents.COMMENT_DELETED -> CommentDeleted(s("post_id")!!, s("comment_id")!!)
                SocialEvents.THREAD_READ -> ThreadRead(s("post_id")!!, s("read_up_to")!!)
                SocialEvents.REACTION_UPDATED -> ReactionUpdated(
                    s("target_kind")!!, s("target_id")!!, s("post_id"), s("conversation_id"), obj("summary", ReactionSummary.serializer()),
                )
                SocialEvents.CIRCLE_UPDATED -> CircleUpdated(s("feed_id")!!, obj("circle", Circle.serializer()))
                SocialEvents.CIRCLE_REMOVED -> CircleRemoved(s("feed_id")!!, s("circle_id")!!)
                SocialEvents.CONVERSATION_UPDATED -> ConversationUpdated(obj("conversation", Conversation.serializer()))
                SocialEvents.CONVERSATION_REMOVED -> ConversationRemoved(s("conversation_id")!!)
                SocialEvents.MESSAGE_CREATED -> MessageCreated(s("conversation_id")!!, obj("message", Message.serializer()))
                SocialEvents.MESSAGE_UPDATED -> MessageUpdated(s("conversation_id")!!, obj("message", Message.serializer()))
                SocialEvents.READ_UPDATED -> ReadUpdated(s("conversation_id")!!, s("user_id")!!, s("last_read_message_id"))
                SocialEvents.TYPING -> Typing(s("conversation_id")!!, s("user_id")!!, s("display_name").orEmpty(), s("activity") ?: "typing", s("expires_at"))
                SocialEvents.BADGE_UPDATED -> BadgeUpdated(obj("badge", Badge.serializer()))
                SocialEvents.REPORT_CREATED -> ReportCreated(obj("report", Report.serializer()))
                SocialEvents.PHOTO_REQUEST_CREATED -> PhotoRequestChanged(obj("request", PhotoRequest.serializer()), created = true)
                SocialEvents.PHOTO_REQUEST_UPDATED -> PhotoRequestChanged(obj("request", PhotoRequest.serializer()), created = false)
                SocialEvents.STREAK_UPDATED -> StreakUpdated(s("feed_id")!!, obj("streak", FeedStreakSummary.serializer()))
                SocialEvents.PROMPT_UPDATED -> PromptUpdated(s("feed_id")!!, obj("prompt", FeedPrompt.serializer()))
                SocialEvents.MILESTONE_ACHIEVED -> MilestoneAchieved(obj("milestone", Milestone.serializer()))
                else -> null
            }
        }
    }
}
