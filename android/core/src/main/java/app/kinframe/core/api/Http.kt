package app.kinframe.core.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
val MERGE_PATCH_MEDIA = "application/merge-patch+json; charset=utf-8".toMediaType()

/** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** A non-2xx answer from the server, with the RFC 9457 problem body when present. */
open class ApiException(val status: Int, val problem: Problem?, message: String? = null) :
    IOException(message ?: "HTTP $status ${problem?.code ?: ""} ${problem?.title ?: ""}".trim()) {
    val code: String? get() = problem?.code
}

/** The device credential was revoked: wipe it and go back to setup (README §3.2). */
class DeviceRevokedException(problem: Problem?) : ApiException(401, problem, "device revoked")

/** The server wants a newer app (426). */
class AppUpdateRequiredException(problem: Problem?) : ApiException(426, problem, "app update required")

internal fun Response.problemOrNull(): Problem? = runCatching {
    val text = body.string()
    if (text.isBlank()) null else KinJson.decodeFromString(Problem.serializer(), text)
}.getOrNull()

/** Throws the right [ApiException] for a non-successful response (and closes it). */
internal fun Response.throwForStatus(): Nothing {
    val p = use { problemOrNull() }
    when {
        code == 401 && p?.code == ProblemCode.DEVICE_REVOKED -> throw DeviceRevokedException(p)
        code == 426 -> throw AppUpdateRequiredException(p)
        else -> throw ApiException(code, p)
    }
}
