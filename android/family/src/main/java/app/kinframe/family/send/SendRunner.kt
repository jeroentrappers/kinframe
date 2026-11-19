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
import app.kinframe.family.send.prep.PreparationFailedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
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
    /**
     * Client-side preparation (README §5.3). Every item goes through it before uploading; it throws
     * [PreparationFailedException] for a file that can neither be prepared nor go as it is.
     */
    private val prepare: suspend (filesKey: String, item: OutboxItem, onProgress: (Float) -> Unit) -> OutboxItem,
    /** Family social: posts a chat message once its files are uploaded ([OutboxSend.chat]); returns the message id. */
    private val sendChat: (suspend (OutboxSend) -> String)? = null,
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
                if (item.needsPrepAgain) {
                    // Queued by an older version that uploaded originals: drop that upload and prepare the file.
                    item = item.resetPrep()
                    store.updateItem(id, item.id) { it.resetPrep() }
                }
                if (item.prepState == null) {
                    // Prepare before uploading; recorded so a retry doesn't do it again. The parts of a send split
                    // across servers prepare each file once: whichever part gets there first, the others reuse it.
                    val current = item
                    val prepared = prepLock(send, current).withLock {
                        sharedPrep(send, current)?.let { current.withPrepFrom(it) } ?: run {
                            store.reportProgress(OutboxProgress(id, index, items.size, 0, 0, preparing = true))
                            prepare(send.filesKey, current) { f -> store.reportProgress(OutboxProgress(id, index, items.size, 0, 0, preparing = true, prepFraction = f)) }
                        }
                    }
                    store.updateItem(id, item.id) { prepared }
                    shareWithOtherParts(send, prepared)
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
            val createdId = if (ready.chat != null) {
                val chat = sendChat ?: return fail(id, ProblemCode.FEATURE_DISABLED)
                chat(ready)
            } else {
                api.createSend(
                    SendCreate.of(ready.frameIds, ready.feedIds, ready.items.map { it.toSendItem() }, ready.circleIds, ready.promptId),
                    idempotencyKey = ready.id,
                ).id
            }
            cleanup(ready, store.sends.value.filter { it.id != id })
            store.remove(id)
            Outcome.Sent(createdId, ready.recipientNames)
        } catch (e: CancellationException) {
            store.update(id) { it.copy(state = OutboxState.QUEUED) }
            throw e
        } catch (e: PreparationFailedException) {
            fail(id, PREPARE_FAILED)
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

    /** A preparation of [item] another part of the same split send already made (its files still there). */
    private fun sharedPrep(send: OutboxSend, item: OutboxItem): OutboxItem? {
        val group = send.group ?: return null
        return store.sends.value.asSequence()
            .filter { it.group == group && it.id != send.id }
            .flatMap { it.items.asSequence() }
            .firstOrNull { it.prepKey == item.prepKey && it.prepState != null && it.prepState != OutboxItem.PREP_ORIGINAL && !it.needsPrepAgain &&
                (it.preparedPath == null || File(it.preparedPath).exists()) && (it.motion?.localPath == null || File(it.motion.localPath).exists()) }
    }

    /**
     * Hands a preparation to the other parts of a split send that haven't prepared that file yet, so their queue
     * entries hold its files (and a part that finishes first doesn't delete what the others still upload).
     */
    private fun shareWithOtherParts(send: OutboxSend, prepared: OutboxItem) {
        val group = send.group ?: return
        store.sends.value.filter { it.group == group && it.id != send.id }.forEach { other ->
            other.items.filter { it.prepKey == prepared.prepKey && it.prepState == null }.forEach { i ->
                store.updateItem(other.id, i.id) { it.withPrepFrom(prepared) }
            }
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
        /** Outcome code: a file couldn't be prepared on the phone and was too large to send as it is. */
        const val PREPARE_FAILED = "prepare_failed"

        /** One lock per prepared file of a split send, so two parts never prepare the same file at once. */
        private val prepLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

        private fun prepLock(send: OutboxSend, item: OutboxItem) =
            prepLocks.getOrPut(send.filesKey + "/" + item.prepKey) { kotlinx.coroutines.sync.Mutex() }

        private fun OutboxItem.files(): List<String> = listOfNotNull(localPath, preparedPath, motion?.localPath, motion?.preparedPath)

        /**
         * Deletes the private copies of a finished or discarded send, except files another queued send still uses
         * ([others]: the other parts of a send split across servers share one copy and one preparation).
         */
        fun cleanup(send: OutboxSend, others: List<OutboxSend> = emptyList()) {
            val keep = others.flatMap { o -> o.items.flatMap { it.files() } }.toSet()
            val files = send.items.flatMap { it.files() }.filter { it !in keep }
            files.forEach { File(it).delete() }
            send.items.forEach { i -> prepLocks.remove(send.filesKey + "/" + i.prepKey) }
            (files.firstOrNull() ?: send.items.firstNotNullOfOrNull { it.files().firstOrNull() })
                ?.let { File(it).parentFile?.takeIf { d -> d.list().isNullOrEmpty() }?.delete() }
        }

        /** Content URIs of [send] no other queued send still reads (their persisted grants can be released). */
        fun urisToRelease(send: OutboxSend, others: List<OutboxSend>): List<String> {
            val used = others.flatMap { o -> o.items.filter { it.localPath == null }.map { it.uri } }.toSet()
            return send.items.filter { it.localPath == null && it.uri !in used }.map { it.uri }
        }

        fun outboxDir(context: Context, sendId: String) = File(context.filesDir, "outbox/$sendId")
    }
}
