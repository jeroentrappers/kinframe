package app.kinframe.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * Telegram (openapi tag `telegram`; README §5.17, docs/spec/telegram.md): groups and private chats connected to a frame
 * or a feed through the server's bot, and linked Telegram accounts. Telegram's webhook is not for clients and has no
 * call here. Enum-like values stay strings so a newer server never breaks parsing.
 */

object TelegramChatKind {
    const val GROUP = "group"
    const val PRIVATE = "private"
}

object TelegramCodePurpose {
    const val CONNECT = "connect"
    const val LINK = "link"
}

object TelegramCodeStatus {
    const val OPEN = "open"
    const val USED = "used"
    const val EXPIRED = "expired"
    const val REVOKED = "revoked"
}

/** Why the last attempt to redeem a code in Telegram failed (the code may still be open). */
object TelegramCodeErrorCode {
    const val CODE_EXPIRED = "code_expired"
    const val CODE_USED = "code_used"
    const val ALREADY_CONNECTED = "already_connected"
    const val LIMIT_REACHED = "limit_reached"
    const val WRONG_CHAT_KIND = "wrong_chat_kind"
    const val ALREADY_LINKED = "already_linked"
    const val MISSING_PERMISSION = "missing_permission"
}

object TelegramChatStatus {
    const val ACTIVE = "active"
    const val NEEDS_ATTENTION = "needs_attention"
    const val PAUSED = "paused"
    const val DISCONNECTED = "disconnected"
}

object TelegramChatErrorCode {
    /** The bot only sees commands in the group: make it an admin. */
    const val PRIVACY_MODE = "privacy_mode"
    /** The server now uses another bot: connect again. */
    const val BOT_CHANGED = "bot_changed"
}

object TelegramChatWarning {
    const val CANNOT_POST = "cannot_post"
    const val REACTIONS_OFF = "reactions_off"
}

object TelegramEndedReason {
    const val DISCONNECTED = "disconnected"
    const val STOPPED = "stopped"
    const val BOT_REMOVED = "bot_removed"
    const val CHAT_GONE = "chat_gone"
    const val BOT_BLOCKED = "bot_blocked"
    const val TARGET_DELETED = "target_deleted"
}

object TelegramContributorsMode {
    const val EVERYONE = "everyone"
    const val LINKED_ONLY = "linked_only"
}

/** Websocket message types for Telegram (README §6.2). */
object TelegramEvents {
    const val CHAT_UPDATED = "telegram_chat.updated"
    const val CHAT_REMOVED = "telegram_chat.removed"
    const val CODE_UPDATED = "telegram_code.updated"
    const val LINK_UPDATED = "telegram_link.updated"
}

/** Push keys for Telegram (README §6.3). */
object TelegramPush {
    /** The chat's owner only: it needs attention, the bot was removed, or ownership passed to them. */
    const val ATTENTION = "telegram_attention"
}

/** How a join request came (`JoinRequest.via`). */
object JoinVia {
    const val FRAME_CODE = "frame_code"
    const val INVITE = "invite"
    const val EMAIL = "email"
    const val TELEGRAM = "telegram"
}

/** `TelegramConnectCodeCreate`: [linkAccount] only with `private` ("Send from my Telegram"). */
@Serializable
data class TelegramConnectCodeCreate(val chatKind: String, val linkAccount: Boolean = false)

/** A one-time connect or link code. [code] and [url] are only in the create response. */
@Serializable
data class TelegramCode(
    val id: String,
    val purpose: String = TelegramCodePurpose.CONNECT,
    val chatKind: String? = null,
    val linkAccount: Boolean = false,
    val target: ChannelTarget? = null,
    val code: String? = null,
    val url: String? = null,
    val status: String = TelegramCodeStatus.OPEN,
    val expiresAt: String? = null,
    /** `connect`, once used: the chat it created. */
    val chatId: String? = null,
    val errorCode: String? = null,
) {
    val isOpen: Boolean get() = status == TelegramCodeStatus.OPEN
    val isUsed: Boolean get() = status == TelegramCodeStatus.USED
}

@Serializable
data class TelegramTopic(val id: Long, val name: String = "")

@Serializable
data class TelegramChatCounts(
    val sent: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val contributors: Int = 0,
)

/** One Telegram chat bound to a frame or a feed. Telegram user and chat ids are never exposed. */
@Serializable
data class TelegramChat(
    val id: String,
    val chatKind: String = TelegramChatKind.GROUP,
    /** The group's title, or for a private chat the Telegram user's display name. */
    val title: String = "",
    val isForum: Boolean = false,
    val target: ChannelTarget = ChannelTarget(),
    val owner: ChannelOwner? = null,
    val status: String = TelegramChatStatus.ACTIVE,
    val errorCode: String? = null,
    val warnings: List<String> = emptyList(),
    val endedReason: String? = null,
    val endedByName: String? = null,
    val endedAt: String? = null,
    val botIsAdmin: Boolean = false,
    val seesAllMessages: Boolean = true,
    val contributors: String = TelegramContributorsMode.EVERYONE,
    val frameReplies: Boolean = false,
    val notifyReactions: Boolean = false,
    val offerAppInvite: Boolean = true,
    val topicId: Long? = null,
    val topics: List<TelegramTopic> = emptyList(),
    val counts: TelegramChatCounts = TelegramChatCounts(),
    val lastDeliveryAt: String? = null,
    val canManage: Boolean = false,
    val isOwner: Boolean = false,
    val createdAt: String? = null,
) {
    val isLive: Boolean get() = status != TelegramChatStatus.DISCONNECTED
    val isPaused: Boolean get() = status == TelegramChatStatus.PAUSED
    val isGroup: Boolean get() = chatKind == TelegramChatKind.GROUP
}

/**
 * `TelegramChatUpdate`: omitted fields are kept. [allTopics] sends an explicit `topic_id: null` (every topic counts),
 * which a plain data class encoding would drop.
 */
data class TelegramChatUpdate(
    val paused: Boolean? = null,
    val contributors: String? = null,
    val frameReplies: Boolean? = null,
    val notifyReactions: Boolean? = null,
    val offerAppInvite: Boolean? = null,
    val topicId: Long? = null,
    val allTopics: Boolean = false,
    val takeOver: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        paused?.let { put("paused", JsonPrimitive(it)) }
        contributors?.let { put("contributors", JsonPrimitive(it)) }
        frameReplies?.let { put("frame_replies", JsonPrimitive(it)) }
        notifyReactions?.let { put("notify_reactions", JsonPrimitive(it)) }
        offerAppInvite?.let { put("offer_app_invite", JsonPrimitive(it)) }
        when {
            topicId != null -> put("topic_id", JsonPrimitive(topicId))
            allTopics -> put("topic_id", JsonNull)
        }
        takeOver?.let { put("take_over", JsonPrimitive(it)) }
    }

    val isEmpty: Boolean get() = toJson().isEmpty()
}

@Serializable
data class TelegramContributor(
    val id: String,
    /** The Telegram display name, or the kinframe name when linked. */
    val displayName: String = "",
    val linked: Boolean = false,
    val userId: String? = null,
    val blocked: Boolean = false,
    val sentCount: Int = 0,
    val firstSeenAt: String? = null,
    val lastSeenAt: String? = null,
)

@Serializable
data class TelegramContributorUpdate(val blocked: Boolean)

/** The Telegram account linked to the caller. */
@Serializable
data class TelegramLink(
    val displayName: String = "",
    /** Without "@". */
    val username: String? = null,
    /** False when the user blocked the bot. */
    val canMessage: Boolean = true,
    val linkedAt: String? = null,
)

/** Telegram deep links (`https://t.me/<bot>?startgroup=<code>` / `?start=<code>`). */
object TelegramLinks {
    /**
     * The `tg://resolve` form of a `t.me` link, which the Telegram app opens directly; null when [url] isn't a t.me
     * bot link. The https link stays the fallback (browser, or a phone without Telegram).
     */
    fun appUri(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (u.host != "t.me" && u.host != "telegram.me") return null
        val bot = u.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val b = StringBuilder("tg://resolve?domain=").append(bot)
        u.queryParameter("startgroup")?.let { b.append("&startgroup=").append(it) }
        u.queryParameter("start")?.let { b.append("&start=").append(it) }
        return b.toString()
    }

    /** `https://t.me/<bot>`: the bot's private chat, for "Open the bot" without a code. */
    fun bot(username: String?): String? = username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }?.let { "https://t.me/$it" }
}
