package app.kinframe.family.ui

/** Family social screens (docs/spec/family-social.md); routed by `SocialRoot`. */
sealed interface SocialRoute : Route

/** The Home and Chats tabs. */
sealed interface SocialTab : SocialRoute

/** Home: the current family's timeline with its people and frames (docs/spec/simple-app.md §3.1). */
data object HomeRoute : SocialTab

/** Home's activity button: posts with new comments or mentions, and frames asking for photos. */
data object ActivityRoute : SocialRoute

/** Chats: family, circle, group and direct conversations (discovery `chats`). */
data object ChatsRoute : SocialTab

/** One post with its thread; [commentId] scrolls to a comment (from a notification). */
data class PostRoute(val postId: String, val commentId: String? = null) : SocialRoute

data class ConversationRoute(val conversationId: String) : SocialRoute

/** "New chat" ([group] false) or "New group". */
data class NewChatRoute(val group: Boolean = false) : SocialRoute

/** A conversation's details: members, name and photo, mute, leave. */
data class ConversationInfoRoute(val conversationId: String) : SocialRoute

data class PersonRoute(val userId: String) : SocialRoute

data class CirclesRoute(val feedId: String) : SocialRoute

data class CircleRoute(val feedId: String, val circleId: String) : SocialRoute

/**
 * Per-server settings screens carry the [accountId] they are for (multi-server.md §2.1: reminders, blocked people,
 * milestones, the operator's reports and the weekly summary are each server's own); null is the primary account.
 */
data class BlockedPeopleRoute(val accountId: String? = null) : SocialRoute

/** A family's reports for its admins, or (null) the operator's. */
data class ReportsRoute(val feedId: String? = null, val accountId: String? = null) : SocialRoute

/** Settings → Reminders: level, frame requests, summary, birthdays, snooze, quiet hours, email digest, read receipts. */
data class RemindersRoute(val accountId: String? = null) : SocialRoute

/** A family's streak and weekly prompts, with the admins' settings. */
data class FeedEngagementRoute(val feedId: String) : SocialRoute

data class WeeklySummaryRoute(val weekOf: String, val accountId: String? = null) : SocialRoute

data class RecapRoute(val feedId: String, val month: String) : SocialRoute

data class MilestonesRoute(val accountId: String? = null) : SocialRoute

/** "Send something to Oma's frame": the frame's freshness, its photo request and suggestions. */
data class FreshFrameRoute(val frameId: String) : SocialRoute
