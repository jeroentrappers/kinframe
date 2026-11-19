package app.kinframe.family.ui.sources

import androidx.annotation.StringRes
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.ItemVia
import app.kinframe.core.api.PhotoSource
import app.kinframe.core.api.PhotoSourceErrorCode as Code
import app.kinframe.core.api.PhotoSourceLinks
import app.kinframe.core.api.PhotoSourceProvider
import app.kinframe.core.api.PhotoSourceStatus
import app.kinframe.core.api.PhotoSourceWarning
import app.kinframe.core.api.ProblemCode
import app.kinframe.family.R
import app.kinframe.family.util.Problems

/**
 * Where a photo source (or a Google Photos import) puts its photos: one album of one frame, or a feed. Carries the
 * names for the screens' titles.
 */
data class SourceTarget(
    val frameId: String? = null,
    val frameName: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val feedId: String? = null,
    val feedName: String? = null,
    /** The album is already "Only in album mode" (photo-sources.md §9.1). */
    val albumOnly: Boolean = false,
) {
    val isFeed: Boolean get() = feedId != null
    val name: String get() = (if (isFeed) feedName else albumName).orEmpty()

    companion object {
        fun album(frameId: String, frameName: String?, albumId: String, albumName: String, albumOnly: Boolean = false) =
            SourceTarget(frameId = frameId, frameName = frameName, albumId = albumId, albumName = albumName, albumOnly = albumOnly)

        fun feed(feedId: String, feedName: String) = SourceTarget(feedId = feedId, feedName = feedName)

        fun of(t: app.kinframe.core.api.PhotoSourceTarget) =
            SourceTarget(t.frameId, t.frameName, t.albumId, t.albumName, t.feedId, t.feedName)
    }
}

/** How a source's state looks: green, red, amber or grey (photo-sources.md §5.1). */
enum class SourceTone { Good, Danger, Warn, Paused }

/** The one thing a person can do to fix a source that needs attention. */
enum class SourceFix { None, NewLink, NewPassword, NewKey, TakeOver, ConfirmRemovals }

/** What the card's status line says; times are formatted by the screen. */
sealed interface SourceHeadline {
    data object Paused : SourceHeadline
    data object Running : SourceHeadline
    /** The first import is still downloading: "Importing 40 of 220". */
    data class Importing(val done: Int, val total: Int) : SourceHeadline
    data class Synced(val at: String?, val next: String?) : SourceHeadline
    data object Waiting : SourceHeadline
    data class Failing(val since: String?) : SourceHeadline
    data object NeedsYou : SourceHeadline
}

data class SourceStatusUi(
    val tone: SourceTone,
    val headline: SourceHeadline,
    /** The problem in words, with its arguments (a host, a name, a count); null when all is well. */
    @StringRes val problem: Int? = null,
    val problemArgs: List<Any> = emptyList(),
    val fix: SourceFix = SourceFix.None,
    /** A single failed run that is being retried keeps the source green, with this note. */
    val retrying: Boolean = false,
)

object SourceStatus {
    fun of(s: PhotoSource): SourceStatusUi {
        val problem = s.lastErrorCode?.let { errorText(it, s) }
        return when (s.status) {
            PhotoSourceStatus.PAUSED -> SourceStatusUi(SourceTone.Paused, SourceHeadline.Paused)
            PhotoSourceStatus.NEEDS_ATTENTION -> SourceStatusUi(
                SourceTone.Warn, SourceHeadline.NeedsYou, problem?.first ?: R.string.source_err_unknown, problem?.second.orEmpty(), fix(s),
            )
            PhotoSourceStatus.ERROR -> SourceStatusUi(
                SourceTone.Danger, SourceHeadline.Failing(s.errorSince), problem?.first ?: R.string.source_err_unknown, problem?.second.orEmpty(),
            )
            else -> SourceStatusUi(
                SourceTone.Good,
                when {
                    s.running -> SourceHeadline.Running
                    s.counts.pending > 0 -> SourceHeadline.Importing(s.counts.imported, s.counts.imported + s.counts.pending)
                    s.lastSyncedAt != null -> SourceHeadline.Synced(s.lastSyncedAt, s.nextSyncAt)
                    else -> SourceHeadline.Waiting
                },
                problem?.first, problem?.second.orEmpty(), retrying = s.lastErrorCode != null,
            )
        }
    }

    /** The host shown in error texts: the Immich server or the link's host. Never the whole link (it is a secret). */
    fun host(s: PhotoSource): String =
        PhotoSourceLinks.hostOf(s.serverUrl) ?: PhotoSourceLinks.hostOf(s.urlHint) ?: s.serviceLabel

    /** The user-facing text for an error code (photo-sources.md §13), with its arguments. */
    fun errorText(code: String, s: PhotoSource?): Pair<Int, List<Any>> {
        val host = s?.let { host(it) } ?: "Immich"
        val api = s?.provider == PhotoSourceProvider.IMMICH_API
        val icloud = s?.provider == PhotoSourceProvider.ICLOUD_LINK
        return when (code) {
            Code.UNREACHABLE -> R.string.source_err_unreachable to listOf(host)
            Code.TLS_ERROR -> R.string.source_err_tls to listOf(host)
            Code.UPSTREAM_ERROR -> R.string.source_err_upstream to listOf(host)
            Code.OWNER_LOST_ACCESS -> (if (s?.target?.isFeed == true) R.string.source_err_owner_lost_feed else R.string.source_err_owner_lost_album) to
                listOf(s?.owner?.displayName ?: "")
            Code.MASS_REMOVAL -> R.string.source_err_mass_removal to listOf(s?.pendingRemovals ?: 0)
            Code.AUTH_FAILED -> (if (api) R.string.source_err_auth_key else R.string.source_err_auth_password) to emptyList()
            Code.FORMAT_CHANGED -> (if (icloud) R.string.source_err_format_icloud else R.string.source_err_format) to emptyList()
            else -> simpleError(code) to emptyList()
        }
    }

    @StringRes
    fun simpleError(code: String?): Int = when (code) {
        Code.PRIVATE_ADDRESS_BLOCKED -> R.string.source_err_private
        Code.PASSWORD_REQUIRED -> R.string.source_err_password_required
        Code.PERMISSION_MISSING -> R.string.source_err_permission
        Code.LINK_NOT_FOUND -> R.string.source_err_link_not_found
        Code.LINK_EXPIRED -> R.string.source_err_link_expired
        Code.ALBUM_NOT_FOUND -> R.string.source_err_album_not_found
        Code.UNSUPPORTED_LINK -> R.string.source_err_unsupported_link
        Code.TOO_LARGE -> R.string.source_err_too_large
        Code.FORMAT_CHANGED -> R.string.source_err_format
        Code.RATE_LIMITED_UPSTREAM -> R.string.source_err_rate_limited
        Code.CREDENTIALS_UNREADABLE -> R.string.source_err_credentials
        Code.AUTH_FAILED -> R.string.source_err_auth_password
        Code.UNREACHABLE -> R.string.source_err_unreachable_generic
        Code.TLS_ERROR -> R.string.source_err_tls_generic
        Code.UPSTREAM_ERROR -> R.string.source_err_upstream_generic
        else -> R.string.source_err_unknown
    }

    /** The button that fixes a source in `needs_attention`. */
    fun fix(s: PhotoSource): SourceFix {
        val api = s.provider == PhotoSourceProvider.IMMICH_API
        return when (s.lastErrorCode) {
            Code.MASS_REMOVAL -> SourceFix.ConfirmRemovals
            Code.OWNER_LOST_ACCESS -> if (s.canManage) SourceFix.TakeOver else SourceFix.None
            Code.AUTH_FAILED -> if (api) SourceFix.NewKey else SourceFix.NewPassword
            Code.PASSWORD_REQUIRED -> SourceFix.NewPassword
            Code.PERMISSION_MISSING -> SourceFix.NewKey
            Code.CREDENTIALS_UNREADABLE -> if (api) SourceFix.NewKey else SourceFix.NewLink
            Code.LINK_NOT_FOUND, Code.LINK_EXPIRED, Code.UNSUPPORTED_LINK -> SourceFix.NewLink
            Code.ALBUM_NOT_FOUND -> if (api) SourceFix.None else SourceFix.NewLink
            else -> SourceFix.None
        }.takeIf { s.canManage } ?: SourceFix.None
    }

    @StringRes
    fun warning(code: String): Int? = when (code) {
        PhotoSourceWarning.DOWNLOADS_DISABLED -> R.string.source_warn_downloads
        PhotoSourceWarning.METADATA_HIDDEN -> R.string.source_warn_metadata
        PhotoSourceWarning.ITEM_LIMIT_REACHED -> R.string.source_warn_limit
        else -> null
    }

    @StringRes
    fun providerName(provider: String): Int = when (provider) {
        PhotoSourceProvider.IMMICH_API -> R.string.source_provider_immich_api
        PhotoSourceProvider.ICLOUD_LINK -> R.string.source_provider_icloud
        else -> R.string.source_provider_immich_link
    }
}

/** A friendly sentence for a failed photo-source call (problem codes of photo-sources.md §13). */
@StringRes
fun sourceProblem(e: Throwable): Int {
    val a = e as? ApiException ?: return Problems.message(e)
    return when (a.code) {
        ProblemCode.SOURCE_TEST_FAILED -> SourceStatus.simpleError(a.problem?.detail)
        ProblemCode.DUPLICATE_SOURCE -> R.string.source_problem_duplicate
        ProblemCode.LIMIT_REACHED -> R.string.source_problem_limit
        ProblemCode.INVALID_TRANSITION -> R.string.source_problem_paused
        ProblemCode.FEATURE_DISABLED -> R.string.source_problem_disabled
        ProblemCode.VALIDATION_FAILED -> R.string.source_problem_address
        ProblemCode.RATE_LIMITED -> R.string.source_problem_rate_limited
        else -> if (a.status == 429) R.string.source_problem_rate_limited else Problems.message(e)
    }
}

/**
 * "Anna via Immich" for the screens that show who a photo is from (photo-sources.md §9.5). Mail and Telegram credit the
 * person who mailed or posted it **instead of** the sender ([name] may be the address's or chat's owner): "Jan via
 * email", "Ben via Telegram" ([channel] formats person and service; email-inbox.md §9.2, telegram.md §8).
 */
object SourceCredit {
    fun text(name: String, via: ItemVia?, format: (String, String) -> String, channel: ((String, String) -> String)? = null): String {
        if (via?.isChannel == true) {
            val person = via.personLabel ?: name
            return channel?.invoke(person, via.service) ?: person
        }
        return via?.let { v -> v.displayLabel.takeIf { it.isNotBlank() }?.let { format(name, it) } } ?: name
    }
}
