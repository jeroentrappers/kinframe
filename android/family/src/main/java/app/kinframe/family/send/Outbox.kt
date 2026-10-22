package app.kinframe.family.send

import app.kinframe.core.api.FocalPoint
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.SendItemCreate
import app.kinframe.core.api.Trim
import app.kinframe.core.api.TusState
import app.kinframe.core.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** One file of a queued send, with everything needed to resume its upload after the process dies. */
@Serializable
data class OutboxItem(
    val id: String,
    /** Content URI the person picked or shared. */
    val uri: String,
    /** A private copy when the URI grant would not outlive the screen (share sheet). */
    val localPath: String? = null,
    val mime: String,
    val name: String,
    val size: Long,
    val kind: String = MediaKind.PHOTO,
    val caption: String? = null,
    val fit: String? = null,
    val focalX: Double? = null,
    val focalY: Double? = null,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val includeAudio: Boolean = true,
    val sha256: String? = null,
    val uploadUrl: String? = null,
    val uploadedBytes: Long = 0,
    /** tus upload id, or an existing item id when the server already had the file. Set once uploaded. */
    val uploadId: String? = null,
) {
    val tusState: TusState? get() = uploadUrl?.let { TusState(it, uploadedBytes) }

    /** Duplicates can't be trimmed or muted server-side (server NOTES), so only plain files offer a hash. */
    val dedupable: Boolean get() = trimStartMs == null && trimEndMs == null && includeAudio

    fun toSendItem(): SendItemCreate = SendItemCreate(
        uploadId = requireNotNull(uploadId),
        caption = caption?.takeIf { it.isNotBlank() },
        focalPoint = if (focalX != null && focalY != null) FocalPoint(focalX, focalY) else null,
        fit = fit,
        trim = if (kind == MediaKind.VIDEO && (trimStartMs != null || trimEndMs != null) && trimEndMs != null) Trim(trimStartMs ?: 0, trimEndMs) else null,
        includeAudio = if (kind == MediaKind.VIDEO && !includeAudio) false else null,
    )
}

object OutboxState {
    const val QUEUED = "queued"
    const val UPLOADING = "uploading"
    const val FAILED = "failed"
}

/** A send waiting to go out. Its [id] doubles as the `Idempotency-Key` of `POST /v1/sends`. */
@Serializable
data class OutboxSend(
    val id: String,
    val server: String,
    val frameIds: List<String>,
    val frameNames: List<String>,
    val items: List<OutboxItem>,
    val createdAt: Long,
    val state: String = OutboxState.QUEUED,
    /** ProblemCode (or "network", "unauthorized") of the last failure. */
    val error: String? = null,
) {
    val totalBytes: Long get() = items.sumOf { it.size }
    val uploadedBytes: Long get() = items.sumOf { if (it.uploadId != null) it.size else it.uploadedBytes.coerceAtMost(it.size) }
}

/** Live progress of the upload in flight (not persisted on every byte). */
data class OutboxProgress(val sendId: String, val itemIndex: Int, val itemCount: Int, val sent: Long, val total: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)
}

/** Queued sends in a crash-safe JSON file. */
class OutboxStore(private val file: File) {
    private val _sends = MutableStateFlow(load())
    val sends: StateFlow<List<OutboxSend>> = _sends.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, OutboxProgress>>(emptyMap())
    val progress: StateFlow<Map<String, OutboxProgress>> = _progress.asStateFlow()

    private fun load(): List<OutboxSend> = AtomicFile.readTextOrNull(file)?.let {
        runCatching { KinJson.decodeFromString(ListSerializer(OutboxSend.serializer()), it) }.getOrNull()
    } ?: emptyList()

    @Synchronized
    private fun save(list: List<OutboxSend>) {
        file.parentFile?.mkdirs()
        AtomicFile.writeText(file, KinJson.encodeToString(ListSerializer(OutboxSend.serializer()), list))
        _sends.value = list
    }

    fun get(id: String): OutboxSend? = _sends.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(send: OutboxSend) = save(_sends.value.filterNot { it.id == send.id } + send)

    @Synchronized
    fun update(id: String, f: (OutboxSend) -> OutboxSend) {
        save(_sends.value.map { if (it.id == id) f(it) else it })
    }

    @Synchronized
    fun updateItem(sendId: String, itemId: String, f: (OutboxItem) -> OutboxItem) =
        update(sendId) { s -> s.copy(items = s.items.map { if (it.id == itemId) f(it) else it }) }

    @Synchronized
    fun remove(id: String) {
        save(_sends.value.filterNot { it.id == id })
        _progress.value = _progress.value - id
    }

    fun reportProgress(p: OutboxProgress) {
        _progress.value = _progress.value + (p.sendId to p)
    }

    fun clearProgress(id: String) {
        _progress.value = _progress.value - id
    }
}
