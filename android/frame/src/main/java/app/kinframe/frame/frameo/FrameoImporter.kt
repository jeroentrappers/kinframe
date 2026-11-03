package app.kinframe.frame.frameo

import android.util.Log
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.ImportResult
import app.kinframe.frame.removable.Progress
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Uploads Frameo's files through `POST /device/imports` (docs/spec/frameo-import.md). Asks the server first which
 * source keys this frame imported before, so a re-run (after a reboot, a network loss, or on purpose) only sends
 * what is missing; the server answers a known key with the existing placement anyway.
 *
 * A network failure ends the run with the exception (the caller waits and runs it again, which resumes); a file the
 * server refuses (unsupported, too large) is counted as failed and the run goes on.
 */
class FrameoImporter(
    private val knownKeys: suspend (Collection<String>) -> Set<String>,
    private val upload: suspend (FrameoCandidate, (Long) -> Unit) -> ImportResult,
) {
    data class Outcome(val imported: Int, val alreadyThere: Int, val failed: Int) {
        val total: Int get() = imported + alreadyThere + failed
    }

    suspend fun run(items: List<FrameoCandidate>, onProgress: (Progress) -> Unit, cancelled: () -> Boolean = { false }): Outcome {
        val known = knownKeys(items.map { it.key })
        val todo = items.filter { it.key !in known }
        var alreadyThere = items.size - todo.size
        var imported = 0
        var failed = 0
        val bytesTotal = items.sumOf { it.bytes }.coerceAtLeast(1)
        var bytesDone = items.filter { it.key in known }.sumOf { it.bytes }
        fun report(partial: Long, current: String?) =
            onProgress(Progress(alreadyThere + imported + failed, items.size, bytesDone + partial, bytesTotal, current))
        report(0, null)
        for (c in todo) {
            if (cancelled()) throw CancellationException("Frameo import stopped")
            report(0, c.key)
            try {
                val r = upload(c) { b -> report(b, c.key) }
                if (r.duplicate) alreadyThere++ else imported++
            } catch (e: ApiException) {
                if (retryable(e)) throw e
                Log.w(TAG, "server refused ${c.key}: ${e.status} ${e.code}")
                failed++
            }
            bytesDone += c.bytes
            report(0, null)
        }
        return Outcome(imported, alreadyThere, failed)
    }

    companion object {
        private const val TAG = "KinFrameo"

        /** Worth trying again later: server trouble, rate limits, auth hiccups. 4xx about the file itself are not. */
        fun retryable(e: IOException): Boolean = e !is ApiException || e.status >= 500 || e.status == 429 || e.status == 401 || e.status == 408
    }
}
