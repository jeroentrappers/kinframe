package app.kinframe.family.send

import android.content.Context
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.PreparedProfile
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.SendCreate
import app.kinframe.core.api.SessionExpiredException
import app.kinframe.core.api.TusResult
import app.kinframe.core.api.TusUploader
import app.kinframe.core.api.UploadSource
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * Runs one queued send: uploads every file with tus (resuming where it stopped), then creates the send with its
 * idempotency key, so a retry after a crash between the two never sends twice. Kept apart from WorkManager so it can
 * be tested directly.
 */
class SendRunner(
    private val store: OutboxStore,
    private val api: KinApi,
    private val tus: TusUploader,
    private val sourceFor: (OutboxItem) -> UploadSource,
    private val hash: (OutboxItem) -> String? = { null },
    /** Client-side preparation (README §5.3); the default uploads originals. */
    private val prepare: suspend (sendId: String, item: OutboxItem, onProgress: (Float) -> Unit) -> OutboxItem = { _, item, _ -> item.copy(prepState = OutboxItem.PREP_ORIGINAL) },
) {
    sealed interface Outcome {
        data class Sent(val sendId: String, val frameNames: List<String>) : Outcome
        /** Try again later (network, server hiccup). */
        data object Retry : Outcome
        /** Needs the person: sign in again, or the server refused the send ([code]). */
        data class Failed(val code: String) : Outcome
    }

    suspend fun run(id: String): Outcome {
        val send = store.get(id) ?: return Outcome.Failed(ProblemCode.NOT_FOUND)
        store.update(id) { it.copy(state = OutboxState.UPLOADING, error = null) }
        return try {
            val items = send.items
            for ((index, original) in items.withIndex()) {
                var item = store.get(id)?.items?.firstOrNull { it.id == original.id } ?: original
                if (item.prepState == null && item.uploadUrl != null) {
                    // An upload of the original started before preparation existed: finish that one.
                    item = item.copy(prepState = OutboxItem.PREP_ORIGINAL)
                    store.updateItem(id, item.id) { it.copy(prepState = OutboxItem.PREP_ORIGINAL) }
                }
                if (item.prepState == null) {
                    // Prepare before uploading; recorded so a retry doesn't do it again.
                    store.reportProgress(OutboxProgress(id, index, items.size, 0, 0, preparing = true))
                    val prepared = prepare(id, item) { f -> store.reportProgress(OutboxProgress(id, index, items.size, 0, 0, preparing = true, prepFraction = f)) }
                    store.updateItem(id, item.id) { prepared }
                    item = prepared
                }
                val current = store.get(id)?.items.orEmpty()
                val total = current.sumOf { it.totalUploadSize }
                val doneBefore = current.take(index).sumOf { it.totalUploadSize }
                if (item.uploadId == null) {
                    val uploadId = upload(id, item, index, items.size, doneBefore, total) { f -> store.updateItem(id, item.id, f) }
                    store.updateItem(id, item.id) { it.copy(uploadId = uploadId, uploadedBytes = it.uploadSize) }
                    item = store.get(id)?.items?.firstOrNull { it.id == item.id } ?: item
                }
                val motion = item.motion
                if (motion != null && motion.uploadId == null) {
                    val updateMotion: ((OutboxItem) -> OutboxItem) -> Unit = { f -> store.updateItem(id, item.id) { it.copy(motion = it.motion?.let(f)) } }
                    val uploadId = upload(id, motion, index, items.size, doneBefore + item.uploadSize, total, updateMotion)
                    updateMotion { it.copy(uploadId = uploadId, uploadedBytes = it.uploadSize) }
                }
            }
            val ready = store.get(id) ?: return Outcome.Failed(ProblemCode.NOT_FOUND)
            val created = api.createSend(SendCreate(ready.frameIds, ready.items.map { it.toSendItem() }), idempotencyKey = ready.id)
            cleanup(ready)
            store.remove(id)
            Outcome.Sent(created.id, ready.frameNames)
        } catch (e: CancellationException) {
            store.update(id) { it.copy(state = OutboxState.QUEUED) }
            throw e
        } catch (e: SessionExpiredException) {
            fail(id, ProblemCode.UNAUTHORIZED)
        } catch (e: ApiException) {
            when {
                e.status >= 500 || e.status == 429 || e.status == 408 -> retry(id)
                // An upload expired before the send referenced it: forget the ids so the next run uploads again.
                e.code == ProblemCode.UPLOAD_NOT_FOUND -> {
                    store.update(id) { s ->
                        s.copy(items = s.items.map {
                            it.copy(uploadId = null, uploadUrl = null, uploadedBytes = 0, motion = it.motion?.copy(uploadId = null, uploadUrl = null, uploadedBytes = 0))
                        })
                    }
                    retry(id)
                }
                else -> fail(id, e.code ?: "http_${e.status}")
            }
        } catch (e: IOException) {
            retry(id)
        } finally {
            store.clearProgress(id)
        }
    }

    /** Uploads one file with tus (resuming), returning the upload id (or the existing item id). */
    private suspend fun upload(
        id: String, start: OutboxItem, index: Int, count: Int, doneBefore: Long, total: Long,
        update: ((OutboxItem) -> OutboxItem) -> Unit,
    ): String {
        var item = start
        if (item.sha256 == null && item.dedupable) {
            hash(item)?.let { h -> update { it.copy(sha256 = h) }; item = item.copy(sha256 = h) }
        }
        val meta = buildMap {
            put("filename", item.uploadName)
            put("filetype", item.uploadMime)
            item.sha256?.let { put("sha256", it) }
            if (item.isPrepared) item.prepared?.let { put(PreparedProfile.KEY, it) }
        }
        val result = tus.upload(
            sourceFor(item), meta, item.tusState,
            onState = { st -> update { it.copy(uploadUrl = st.uploadUrl, uploadedBytes = st.offset) } },
            onProgress = { sent, _ -> store.reportProgress(OutboxProgress(id, index, count, doneBefore + sent, total)) },
        )
        return when (result) {
            is TusResult.Uploaded -> result.uploadId
            is TusResult.Existing -> result.itemId
        }
    }

    private fun retry(id: String): Outcome {
        store.update(id) { it.copy(state = OutboxState.QUEUED, error = "network") }
        return Outcome.Retry
    }

    private fun fail(id: String, code: String): Outcome {
        store.update(id) { it.copy(state = OutboxState.FAILED, error = code) }
        return Outcome.Failed(code)
    }

    companion object {
        /** Deletes the private copies of a finished or discarded send. */
        fun cleanup(send: OutboxSend) {
            val files = send.items.flatMap { listOfNotNull(it.localPath, it.preparedPath, it.motion?.localPath, it.motion?.preparedPath) }
            files.forEach { File(it).delete() }
            files.firstOrNull()?.let { File(it).parentFile?.takeIf { d -> d.list().isNullOrEmpty() }?.delete() }
        }

        fun outboxDir(context: Context, sendId: String) = File(context.filesDir, "outbox/$sendId")
    }
}
