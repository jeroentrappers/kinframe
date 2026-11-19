package app.kinframe.core.social

/** User websocket event types for family social (README §6.2). Clients ignore types they don't know. */
object SocialEvents {
    const val POST_UPDATED = "post.updated"
    const val POST_REMOVED = "post.removed"
    const val COMMENT_CREATED = "comment.created"
    const val COMMENT_UPDATED = "comment.updated"
    const val COMMENT_DELETED = "comment.deleted"
    const val THREAD_READ = "thread.read"
    const val REACTION_UPDATED = "reaction.updated"
    const val CIRCLE_UPDATED = "circle.updated"
    const val CIRCLE_REMOVED = "circle.removed"
    const val CONVERSATION_UPDATED = "conversation.updated"
    const val CONVERSATION_REMOVED = "conversation.removed"
    const val MESSAGE_CREATED = "message.created"
    const val MESSAGE_UPDATED = "message.updated"
    const val READ_UPDATED = "read.updated"
    const val TYPING = "typing"
    const val BADGE_UPDATED = "badge.updated"
    const val REPORT_CREATED = "report.created"
    const val PHOTO_REQUEST_CREATED = "photo_request.created"
    const val PHOTO_REQUEST_UPDATED = "photo_request.updated"
    const val STREAK_UPDATED = "streak.updated"
    const val PROMPT_UPDATED = "prompt.updated"
    const val MILESTONE_ACHIEVED = "milestone.achieved"
}

/** Push keys for family social (README §6.3) and the reminder actions. */
object SocialPush {
    const val COMMENT = "comment"
    const val COMMENT_REPLY = "comment_reply"
    const val MENTION = "mention"
    const val REACTION_PERSON = "reaction_person"
    const val REACTION_DIGEST = "reaction_digest"
    const val MESSAGE = "message"
    const val CONVERSATION_ADDED = "conversation_added"
    const val REPORT = "report"
    const val FRAME_REQUEST = "frame_request"
    const val REMINDER_QUIET = "reminder_quiet"
    const val REMINDER_STREAK = "reminder_streak"
    const val REMINDER_BIRTHDAY = "reminder_birthday"
    const val WEEKLY_SUMMARY = "weekly_summary"

    /** Notification buttons on the four reminder keys. */
    const val ACTION_SNOOZE_MONTH = "snooze_month"
    const val ACTION_FEWER_REMINDERS = "fewer_reminders"

    val REMINDERS = setOf(REMINDER_QUIET, REMINDER_STREAK, REMINDER_BIRTHDAY, WEEKLY_SUMMARY)

    val ALL = setOf(
        COMMENT, COMMENT_REPLY, MENTION, REACTION_PERSON, REACTION_DIGEST, MESSAGE, CONVERSATION_ADDED, REPORT,
        FRAME_REQUEST, REMINDER_QUIET, REMINDER_STREAK, REMINDER_BIRTHDAY, WEEKLY_SUMMARY,
    )
}
