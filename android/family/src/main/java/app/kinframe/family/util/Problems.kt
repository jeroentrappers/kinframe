package app.kinframe.family.util

import androidx.annotation.StringRes
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.SessionExpiredException
import app.kinframe.family.R
import java.io.IOException

/**
 * A friendly, localized sentence for any error. We branch on the stable ProblemCode (README §1), fall back on the
 * HTTP status, and treat I/O failures as "you seem to be offline".
 */
object Problems {
    @StringRes
    fun message(e: Throwable): Int = when (e) {
        is SessionExpiredException -> R.string.problem_token_expired
        is ApiException -> byCode(e.code) ?: byStatus(e.status)
        is IOException -> R.string.problem_network
        else -> R.string.problem_server_error
    }

    /** The code of an error, for logic that branches on it (null for network errors). */
    fun code(e: Throwable): String? = (e as? ApiException)?.code

    @StringRes
    fun byCode(code: String?): Int? = when (code) {
        ProblemCode.UNAUTHORIZED -> R.string.problem_unauthorized
        ProblemCode.TOKEN_EXPIRED -> R.string.problem_token_expired
        ProblemCode.FORBIDDEN -> R.string.problem_forbidden
        ProblemCode.MISSING_PERMISSION -> R.string.problem_missing_permission
        ProblemCode.NOT_FOUND -> R.string.problem_not_found
        ProblemCode.CODE_INVALID -> R.string.problem_code_invalid
        ProblemCode.CODE_EXPIRED -> R.string.problem_code_expired
        ProblemCode.INVITE_INVALID -> R.string.problem_invite_invalid
        ProblemCode.INVITE_EXPIRED -> R.string.problem_invite_expired
        ProblemCode.ALREADY_MEMBER -> R.string.problem_already_member
        ProblemCode.VALIDATION_FAILED -> R.string.problem_validation_failed
        ProblemCode.TOO_MANY_ITEMS -> R.string.problem_too_many_items
        ProblemCode.VIDEO_TOO_LONG -> R.string.problem_video_too_long
        ProblemCode.UPLOAD_TOO_LARGE -> R.string.problem_upload_too_large
        ProblemCode.UPLOAD_NOT_FOUND -> R.string.problem_upload_not_found
        ProblemCode.UNSUPPORTED_MEDIA -> R.string.problem_unsupported_media
        ProblemCode.CORRUPT_MEDIA -> R.string.problem_corrupt_media
        ProblemCode.VERSION_CONFLICT -> R.string.problem_version_conflict
        ProblemCode.FRAME_OFFLINE -> R.string.problem_frame_offline
        ProblemCode.COMMAND_TIMEOUT -> R.string.problem_command_timeout
        ProblemCode.OWNER_CANNOT_LEAVE -> R.string.problem_owner_cannot_leave
        ProblemCode.RATE_LIMITED -> R.string.problem_rate_limited
        ProblemCode.APP_UPDATE_REQUIRED -> R.string.problem_app_update_required
        ProblemCode.UPSTREAM_UNAVAILABLE -> R.string.problem_upstream_unavailable
        ProblemCode.FEATURE_DISABLED -> R.string.problem_feature_disabled
        ProblemCode.SERVER_ERROR -> R.string.problem_server_error
        "network" -> R.string.problem_network
        else -> null
    }

    @StringRes
    private fun byStatus(status: Int): Int = when (status) {
        401 -> R.string.problem_unauthorized
        403 -> R.string.problem_forbidden
        404 -> R.string.problem_not_found
        409 -> R.string.problem_frame_offline
        410 -> R.string.problem_code_expired
        412 -> R.string.problem_version_conflict
        413 -> R.string.problem_upload_too_large
        422 -> R.string.problem_validation_failed
        426 -> R.string.problem_app_update_required
        429 -> R.string.problem_rate_limited
        504 -> R.string.problem_command_timeout
        else -> R.string.problem_server_error
    }

    /** Why an item failed processing (`Item.failure_code`). */
    @StringRes
    fun failure(code: String?): Int = when (code) {
        ProblemCode.UNSUPPORTED_MEDIA -> R.string.failure_unsupported_media
        ProblemCode.CORRUPT_MEDIA -> R.string.failure_corrupt_media
        ProblemCode.VIDEO_TOO_LONG -> R.string.failure_video_too_long
        else -> R.string.failure_unknown
    }
}
