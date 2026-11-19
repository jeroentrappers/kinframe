package app.kinframe.core.social

import app.kinframe.core.api.Item
import kotlinx.serialization.Serializable

/*
 * Hand-written models for encouragement to share (openapi tag `engagement`, README §5.20). The device side (the
 * frame's `photo_request`, `comments` and `prompts` changes) lives with the frame app's models.
 */

object FreshnessState {
    const val FRESH = "fresh"
    const val RECENT = "recent"
    const val QUIET = "quiet"
    const val NEVER = "never"
}

/** How recently a frame (or family) got new photos from people: the ring's [level] from 1 (today) to 0. */
@Serializable
data class Freshness(
    val state: String = FreshnessState.NEVER,
    val level: Double = 0.0,
    val daysQuiet: Int? = null,
    val lastSharedAt: String? = null,
    val lastSharedBy: PersonRef? = null,
)

@Serializable
data class FeedStreakSummary(val currentWeeks: Int = 0, val weekPhotos: Int = 0, val goal: Int = 3, val met: Boolean = false)

@Serializable
data class StreakWeek(val weekOf: String, val photos: Int = 0, val met: Boolean = false, val helpers: List<PersonRef> = emptyList())

@Serializable
data class StreakHistoryWeek(val weekOf: String, val outcome: String)

@Serializable
data class FeedStreak(
    val enabled: Boolean = true,
    val goal: Int = 3,
    val currentWeeks: Int = 0,
    val bestWeeks: Int = 0,
    val freezesLeft: Int = 0,
    val week: StreakWeek,
    val history: List<StreakHistoryWeek> = emptyList(),
)

@Serializable
data class FeedEngagement(
    val feedId: String,
    val streakEnabled: Boolean = true,
    val streakGoal: Int = 3,
    val promptsEnabled: Boolean = false,
    val quietAfterDays: Int = 7,
    val monthlyRecap: Boolean = true,
    val timezone: String = "",
)

@Serializable
data class FeedEngagementUpdate(
    val streakEnabled: Boolean? = null,
    val streakGoal: Int? = null,
    val promptsEnabled: Boolean? = null,
    val quietAfterDays: Int? = null,
    val monthlyRecap: Boolean? = null,
    val timezone: String? = null,
)

object PromptStatus {
    const val CURRENT = "current"
    const val PLANNED = "planned"
    const val PAST = "past"
    const val SKIPPED = "skipped"
}

@Serializable
data class FeedPrompt(
    val id: String,
    val feedId: String,
    /** The Monday the prompt runs from (YYYY-MM-DD). */
    val weekOf: String,
    val status: String = PromptStatus.PLANNED,
    val source: String = "server",
    val libraryKey: String? = null,
    /** Empty when skipped. */
    val title: String = "",
    val answerCount: Int = 0,
    val setBy: PersonRef? = null,
)

/** One of [libraryKey], [title] or [skip]. */
@Serializable
data class FeedPromptSet(val libraryKey: String? = null, val title: String? = null, val skip: Boolean? = null)

@Serializable
data class PromptLibraryEntry(val key: String, val title: String)

@Serializable
data class OnThisDayGroup(
    val feedId: String,
    val feedName: String,
    val yearsAgo: Int,
    val postIds: List<String> = emptyList(),
    val items: List<Item> = emptyList(),
)

@Serializable
data class OnThisDay(val date: String, val groups: List<OnThisDayGroup> = emptyList())

@Serializable
data class SummaryFrame(val frameId: String, val frameName: String, val seenCount: Int = 0, val loveCount: Int = 0)

/** "Your photos were seen 58 times on 3 frames this week; Oma loved 4". */
@Serializable
data class WeeklySummary(
    val weekOf: String,
    val seenCount: Int = 0,
    val frameCount: Int = 0,
    val loveCount: Int = 0,
    val reactionCount: Int = 0,
    val commentCount: Int = 0,
    val frames: List<SummaryFrame> = emptyList(),
    val topItem: Item? = null,
)

@Serializable
data class RecapMostLoved(val postId: String, val item: Item, val reactionCount: Int = 0)

@Serializable
data class RecapFrame(val frameId: String, val frameName: String, val daysWithNewPhotos: Int = 0)

/** About the family, never ranking individuals. */
@Serializable
data class MonthlyRecap(
    val feedId: String,
    val month: String,
    val photosShared: Int = 0,
    val videosShared: Int = 0,
    val sharerCount: Int = 0,
    val mostLoved: RecapMostLoved? = null,
    val freshestFrames: List<RecapFrame> = emptyList(),
    val newMembers: List<PersonRef> = emptyList(),
    val streakWeeks: Int? = null,
)

object MilestoneKey {
    const val FIRST_PHOTO = "first_photo"
    const val PHOTOS_10 = "photos_10"
    const val PHOTOS_100 = "photos_100"
    const val PHOTOS_1000 = "photos_1000"
    const val FIRST_VIDEO = "first_video"
    const val YEAR_OF_SHARING = "year_of_sharing"
    const val LOVES_100 = "loves_100"
}

@Serializable
data class Milestone(val key: String, val achievedAt: String = "", val shown: Boolean = false, val seen: Boolean = false)

@Serializable
data class MilestoneUpdate(val shown: Boolean? = null, val seen: Boolean? = null)

object ReminderLevel {
    const val NORMAL = "normal"
    const val FEWER = "fewer"
    const val OFF = "off"

    val VALUES = listOf(NORMAL, FEWER, OFF)
}

object EmailDigest {
    const val OFF = "off"
    const val DAILY = "daily"
    const val WEEKLY = "weekly"

    val VALUES = listOf(OFF, DAILY, WEEKLY)
}

@Serializable
data class QuietHours(val start: String = "21:00", val end: String = "09:00")

@Serializable
data class ReminderPreferences(
    val level: String = ReminderLevel.NORMAL,
    val autoReduced: Boolean = false,
    val frameRequests: Boolean = true,
    val weeklySummary: Boolean = true,
    val birthdayReminders: Boolean = true,
    val snoozedUntil: String? = null,
    val quietHours: QuietHours = QuietHours(),
    val emailDigest: String = EmailDigest.OFF,
    val emailDigestAvailable: Boolean = false,
)

@Serializable
data class ReminderPreferencesUpdate(
    val level: String? = null,
    val frameRequests: Boolean? = null,
    val weeklySummary: Boolean? = null,
    val birthdayReminders: Boolean? = null,
    /** 0 ends a snooze. */
    val snoozeDays: Int? = null,
    val quietHours: QuietHours? = null,
    val emailDigest: String? = null,
    val autoReducedSeen: Boolean? = null,
)

object PhotoRequestState {
    const val OPEN = "open"
    const val ANSWERED = "answered"
    const val EXPIRED = "expired"
}

/** A frame's "Ask for new photos". */
@Serializable
data class PhotoRequest(
    val id: String,
    val frameId: String,
    val frameName: String = "",
    val state: String = PhotoRequestState.OPEN,
    val createdAt: String = "",
    val expiresAt: String = "",
    val answeredAt: String? = null,
    val answeredBy: PersonRef? = null,
    val freshness: Freshness? = null,
) {
    val isOpen: Boolean get() = state == PhotoRequestState.OPEN
}
