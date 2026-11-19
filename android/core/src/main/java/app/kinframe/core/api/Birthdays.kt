package app.kinframe.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate
import java.time.Year

/*
 * Birthday models (openapi `BirthdayPerson`, `BirthdayPersonCreate`, `BirthdayPersonUpdate`, `FeedBirthdayGreeting`,
 * `FrameBirthday`, `FrameBirthdays`, `FeedPreferencesUpdate`; README §5.13, docs/spec/birthdays.md).
 * People, not accounts: a name and a date, an optional year, photo and link to a feed member.
 */

object BirthdayStatus {
    /** A poster's suggestion waiting for the feed's owner or an admin. */
    const val PENDING = "pending"
    /** On the list and on the frames. */
    const val APPROVED = "approved"
    /** Declined; listed for the suggester and admins for 30 days. */
    const val REJECTED = "rejected"
}

@Serializable
data class BirthdayPerson(
    val id: String,
    val feedId: String = "",
    val status: String = BirthdayStatus.APPROVED,
    val displayName: String,
    val day: Int,
    val month: Int,
    /** Null when unknown or when the caller may not see it (birthdays.md §9). */
    val birthYear: Int? = null,
    val showAge: Boolean = false,
    /** The uploaded photo, else the linked member's avatar. */
    val photoUrl: String? = null,
    val hasOwnPhoto: Boolean = false,
    val userId: String? = null,
    val linkedMemberName: String? = null,
    val createdByName: String? = null,
    /** Who added the entry (account id), null when that account is gone: "added by me". */
    val createdById: String? = null,
    val suggested: Boolean = false,
    val decidedByName: String? = null,
    val decidedAt: String? = null,
    /** `YYYY-MM-DD` in the caller's time zone. */
    val nextBirthday: String? = null,
    val turns: Int? = null,
    val greetingsToday: Int = 0,
    val canEdit: Boolean = false,
    val canDecide: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    val isPending: Boolean get() = status == BirthdayStatus.PENDING
    val isApproved: Boolean get() = status == BirthdayStatus.APPROVED
    val isRejected: Boolean get() = status == BirthdayStatus.REJECTED
}

/** `BirthdayPersonCreate`: `user_id` links a feed member ("Add my birthday" sends the caller's id). */
@Serializable
data class BirthdayPersonCreate(
    val displayName: String,
    val day: Int,
    val month: Int,
    val birthYear: Int? = null,
    val showAge: Boolean = false,
    val userId: String? = null,
)

/**
 * `BirthdayPersonUpdate` as a JSON body: omitted fields are kept, and [clearYear] / [unlink] send an explicit JSON
 * null (`birth_year: null` clears the year, `user_id: null` unlinks), which the data class encoding would drop.
 */
data class BirthdayPersonUpdate(
    val displayName: String? = null,
    val day: Int? = null,
    val month: Int? = null,
    val birthYear: Int? = null,
    val clearYear: Boolean = false,
    val showAge: Boolean? = null,
    val userId: String? = null,
    val unlink: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        displayName?.let { put("display_name", JsonPrimitive(it)) }
        day?.let { put("day", JsonPrimitive(it)) }
        month?.let { put("month", JsonPrimitive(it)) }
        when {
            birthYear != null -> put("birth_year", JsonPrimitive(birthYear))
            clearYear -> put("birth_year", JsonNull)
        }
        showAge?.let { put("show_age", JsonPrimitive(it)) }
        when {
            userId != null -> put("user_id", JsonPrimitive(userId))
            unlink -> put("user_id", JsonNull)
        }
    }

    val isEmpty: Boolean get() = toJson().isEmpty()
}

/** `FeedPreferencesUpdate` (`PATCH /feeds/{id}/preferences`): the caller's own preferences in a feed. */
@Serializable
data class FeedPreferencesUpdate(val birthdayNotifications: Boolean? = null)

/** A frame said happy birthday to a person of this feed on their day. Also the `birthday.greeting` payload. */
@Serializable
data class FeedBirthdayGreeting(
    val id: String,
    val feedId: String = "",
    val birthdayId: String,
    val birthdayName: String = "",
    val frameId: String = "",
    val frameName: String = "",
    val frameLocation: String? = null,
    /** The occurrence date (the frame's local date). */
    val date: String,
    val createdAt: String,
)

@Serializable
data class FeedBirthdayGreetingsPage(val greetings: List<FeedBirthdayGreeting> = emptyList(), val nextCursor: String? = null)

/** One person on a frame, merged across the frame's birthday subscriptions (birthdays.md §6.5). */
@Serializable
data class FrameBirthday(
    /** The primary entry; used for `deviceGreetBirthday`. */
    val id: String,
    val name: String,
    val day: Int,
    val month: Int,
    /** Present only when the age may be shown. */
    val birthYear: Int? = null,
    val photoUrl: String? = null,
    val feedIds: List<String> = emptyList(),
    val birthdayIds: List<String> = emptyList(),
)

/** Payload of the `birthdays` change (README §5.13). */
@Serializable
data class FrameBirthdays(
    val generatedAt: String? = null,
    val people: List<FrameBirthday> = emptyList(),
) {
    companion object {
        /** Parses a stored or received payload; null when absent or not understood. */
        fun parse(json: String?): FrameBirthdays? =
            json?.let { runCatching { KinJson.decodeFromString(serializer(), it) }.getOrNull() }

        fun of(obj: JsonObject?): FrameBirthdays? =
            obj?.let { runCatching { KinJson.decodeFromJsonElement(serializer(), it) }.getOrNull() }
    }
}

/** Date rules shared by the apps and the frame (birthdays.md §2, §6.2, §10). */
object BirthdayDates {
    const val MIN_YEAR = 1900
    const val MAX_NAME = 40

    fun isLeap(year: Int): Boolean = Year.isLeap(year.toLong())

    /** The birthday in [year]: 29 February falls on 28 February in non-leap years. */
    fun occurrence(month: Int, day: Int, year: Int): LocalDate =
        if (month == 2 && day == 29 && !isLeap(year)) LocalDate.of(year, 2, 28) else LocalDate.of(year, month, day)

    /** The next occurrence on or after [from]. */
    fun next(month: Int, day: Int, from: LocalDate): LocalDate {
        val o = occurrence(month, day, from.year)
        return if (o.isBefore(from)) occurrence(month, day, from.year + 1) else o
    }

    /**
     * Whether [day] / [month] form a possible date: 29 February is allowed without a year and with a leap [year];
     * 30/31 February, 31 April etc. never.
     */
    fun isValid(day: Int, month: Int, year: Int? = null): Boolean {
        if (month !in 1..12 || day < 1) return false
        val max = if (month == 2) (if (year == null || isLeap(year)) 29 else 28) else java.time.Month.of(month).length(false)
        return day <= max
    }

    fun isValidYear(year: Int, today: LocalDate): Boolean = year in MIN_YEAR..today.year
}
