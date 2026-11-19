package app.kinframe.family.ui.channels

import androidx.annotation.StringRes
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.ChannelTarget
import app.kinframe.core.api.EmailAddress
import app.kinframe.core.api.EmailOutcome
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.JoinVia
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.TelegramChat
import app.kinframe.core.api.TelegramChatErrorCode
import app.kinframe.core.api.TelegramChatStatus
import app.kinframe.core.api.TelegramChatWarning
import app.kinframe.core.api.TelegramCodeErrorCode
import app.kinframe.core.api.TelegramEndedReason
import app.kinframe.family.R
import app.kinframe.family.util.Problems
import java.text.Normalizer

/*
 * Email to frame (docs/spec/email-inbox.md) and Telegram (docs/spec/telegram.md) in the family app: what an address or a
 * chat fills, and the texts for their states. Pure logic, so it is tested without a UI.
 */

/** The frame or feed an inbox address or a Telegram chat fills. */
data class ChannelScope(val kind: String, val id: String, val name: String) {
    val isFeed: Boolean get() = kind == ChannelTarget.KIND_FEED

    fun holds(t: ChannelTarget): Boolean = if (isFeed) t.feedId == id else t.frameId == id

    fun holds(frameId: String?, feedId: String?): Boolean = if (isFeed) feedId == id else frameId == id

    companion object {
        fun frame(id: String, name: String) = ChannelScope(ChannelTarget.KIND_FRAME, id, name)
        fun feed(id: String, name: String) = ChannelScope(ChannelTarget.KIND_FEED, id, name)
    }
}

object EmailLogic {
    private val slugChars = Regex("[^a-z0-9]+")
    private val slugPattern = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
    const val SLUG_MAX = 24

    /** A friendly part from a name, like the server's default: lower case, accents removed ("Oma's frame" → "oma-s-frame"). */
    fun slugOf(name: String): String {
        val plain = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.replace(slugChars, "-").trim('-').take(SLUG_MAX).trim('-')
    }

    /** What the person typed, made into a valid friendly part (or empty). */
    fun cleanSlug(input: String): String =
        Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("-{2,}"), "-").take(SLUG_MAX)

    fun isValidSlug(slug: String): Boolean = slug.length in 1..SLUG_MAX && slugPattern.matches(slug)

    fun isValidEmail(email: String): Boolean {
        val e = email.trim()
        val at = e.indexOf('@')
        return e.length in 3..254 && at > 0 && at == e.lastIndexOf('@') && '.' in e.substring(at + 1) && e.none { it.isWhitespace() }
    }

    /**
     * The ready-made message for a relative (README §5.16): "You can now email photos to {name}: {address}. Just attach
     * them; the subject becomes the caption. The first time, {owner} approves your address." Without approval (anyone
     * may send) the last sentence is left out.
     */
    fun shareText(a: EmailAddress, ownerFallback: String, withApproval: (String, String, String) -> String, without: (String, String) -> String): String {
        val name = a.name.ifBlank { a.target.name }
        return if (a.anyoneMaySend) without(name, a.address)
        else withApproval(name, a.address, a.owner?.displayName?.takeIf { it.isNotBlank() } ?: ownerFallback)
    }

    @StringRes
    fun outcome(code: String): Int = when (code) {
        EmailOutcome.DELIVERED -> R.string.email_outcome_delivered
        EmailOutcome.HELD -> R.string.email_outcome_held
        EmailOutcome.NO_MEDIA -> R.string.email_outcome_no_media
        EmailOutcome.TOO_LARGE -> R.string.email_outcome_too_large
        EmailOutcome.ENCRYPTED -> R.string.email_outcome_encrypted
        EmailOutcome.DUPLICATE -> R.string.email_outcome_duplicate
        EmailOutcome.BLOCKED -> R.string.email_outcome_blocked
        EmailOutcome.REJECTED_DMARC -> R.string.email_outcome_rejected_dmarc
        EmailOutcome.IGNORED -> R.string.email_outcome_ignored
        EmailOutcome.RATE_LIMITED -> R.string.email_outcome_rate_limited
        else -> R.string.email_outcome_failed
    }

    /** Outcomes that need no sender name in front ("Blocked sender · dropped"). */
    fun outcomeHasSender(code: String): Boolean =
        code != EmailOutcome.BLOCKED && code != EmailOutcome.REJECTED_DMARC && code != EmailOutcome.IGNORED && code != EmailOutcome.RATE_LIMITED && code != EmailOutcome.FAILED
}

/** A friendly sentence for a failed inbox address call. */
@StringRes
fun emailProblem(e: Throwable): Int {
    val a = e as? ApiException ?: return Problems.message(e)
    return when (a.code) {
        ProblemCode.DUPLICATE_SENDER -> R.string.email_problem_duplicate_sender
        ProblemCode.LIMIT_REACHED -> R.string.email_problem_limit
        ProblemCode.ALREADY_DECIDED -> R.string.email_problem_already_decided
        ProblemCode.VALIDATION_FAILED -> R.string.email_problem_validation
        ProblemCode.FEATURE_DISABLED -> R.string.email_problem_disabled
        ProblemCode.RATE_LIMITED -> R.string.problem_rate_limited
        else -> if (a.status == 409 && a.code == null) R.string.email_problem_already_decided else Problems.message(e)
    }
}

enum class ChatTone { Good, Warn, Danger, Paused, Ended }

/** How a Telegram chat looks: a tone, a short status line, and the problem to fix (telegram.md §15.1). */
data class ChatStatusUi(
    val tone: ChatTone,
    @StringRes val status: Int,
    @StringRes val problem: Int? = null,
    val problemArgs: List<String> = emptyList(),
    @StringRes val warnings: List<Int> = emptyList(),
    /** The fix is connecting again (`bot_changed`). */
    val reconnect: Boolean = false,
)

object TelegramLogic {
    fun status(c: TelegramChat): ChatStatusUi {
        val warnings = c.warnings.mapNotNull { warning(it) }
        return when (c.status) {
            TelegramChatStatus.DISCONNECTED -> ChatStatusUi(ChatTone.Ended, R.string.tg_status_disconnected, ended(c.endedReason), listOfNotNull(c.endedByName ?: c.title))
            TelegramChatStatus.PAUSED -> ChatStatusUi(ChatTone.Paused, R.string.tg_status_paused, warnings = warnings)
            TelegramChatStatus.NEEDS_ATTENTION -> when (c.errorCode) {
                TelegramChatErrorCode.BOT_CHANGED -> ChatStatusUi(ChatTone.Warn, R.string.tg_status_attention, R.string.tg_err_bot_changed, warnings = warnings, reconnect = true)
                else -> ChatStatusUi(ChatTone.Warn, R.string.tg_status_attention, R.string.tg_err_privacy_mode, listOf(c.title), warnings)
            }
            else -> if (!c.seesAllMessages && c.isGroup) ChatStatusUi(ChatTone.Warn, R.string.tg_status_attention, R.string.tg_err_privacy_mode, listOf(c.title), warnings)
            else ChatStatusUi(ChatTone.Good, R.string.tg_status_active, warnings = warnings)
        }
    }

    @StringRes
    fun warning(code: String): Int? = when (code) {
        TelegramChatWarning.CANNOT_POST -> R.string.tg_warn_cannot_post
        TelegramChatWarning.REACTIONS_OFF -> R.string.tg_warn_reactions_off
        else -> null
    }

    @StringRes
    fun ended(reason: String?): Int? = when (reason) {
        TelegramEndedReason.DISCONNECTED -> R.string.tg_ended_disconnected
        TelegramEndedReason.STOPPED -> R.string.tg_ended_stopped
        TelegramEndedReason.BOT_REMOVED -> R.string.tg_ended_bot_removed
        TelegramEndedReason.CHAT_GONE -> R.string.tg_ended_chat_gone
        TelegramEndedReason.BOT_BLOCKED -> R.string.tg_ended_bot_blocked
        else -> null
    }

    /** Why redeeming a code in Telegram failed (`TelegramCode.error_code`, telegram.md §15.2). */
    @StringRes
    fun codeError(code: String?): Int? = when (code) {
        null -> null
        TelegramCodeErrorCode.CODE_EXPIRED -> R.string.tg_code_expired
        TelegramCodeErrorCode.CODE_USED -> R.string.tg_code_used
        TelegramCodeErrorCode.ALREADY_CONNECTED -> R.string.tg_code_already_connected
        TelegramCodeErrorCode.LIMIT_REACHED -> R.string.tg_code_limit
        TelegramCodeErrorCode.WRONG_CHAT_KIND -> R.string.tg_code_wrong_kind
        TelegramCodeErrorCode.ALREADY_LINKED -> R.string.tg_code_already_linked
        TelegramCodeErrorCode.MISSING_PERMISSION -> R.string.tg_code_missing_permission
        else -> R.string.tg_code_failed
    }
}

/** A friendly sentence for a failed Telegram call. */
@StringRes
fun telegramProblem(e: Throwable): Int {
    val a = e as? ApiException ?: return Problems.message(e)
    return when (a.code) {
        ProblemCode.LIMIT_REACHED -> R.string.tg_problem_limit
        ProblemCode.TELEGRAM_ALREADY_LINKED -> R.string.tg_problem_already_linked
        ProblemCode.FEATURE_DISABLED -> R.string.tg_problem_disabled
        ProblemCode.INVALID_TRANSITION -> R.string.tg_problem_disconnected
        ProblemCode.RATE_LIMITED -> R.string.problem_rate_limited
        else -> Problems.message(e)
    }
}

/** How a join request came, for the People lists (README §5.16 "Join links for senders"). */
object JoinRequestVia {
    @StringRes
    fun line(r: JoinRequest): Int = when (r.via) {
        JoinVia.INVITE -> R.string.people_via_invite
        JoinVia.EMAIL -> R.string.people_via_email
        JoinVia.TELEGRAM -> R.string.people_via_telegram
        else -> R.string.people_via_code
    }

    /** The channel's name for the requester ("Jan · jan.peeters@telenet.be"), and whether it matches (check mark). */
    fun detail(r: JoinRequest): Pair<String, Boolean?>? = r.viaName?.trim()?.takeIf { r.isChannel && it.isNotEmpty() }?.let { it to r.viaMatches }
}
