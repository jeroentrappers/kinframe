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
    /** Capture time read from the original before preparing (prepared files carry no metadata). */
    val capturedAt: String? = null,
    /** Set once preparation ran: [PREP_PREPARED] or [PREP_ORIGINAL] (upload the file as is). */
    val prepState: String? = null,
    /** The prepared master (README §5.3 "Prepared uploads") and what it is. */
    val preparedPath: String? = null,
    val preparedSize: Long? = null,
    val prepared: String? = null,
    val preparedMime: String? = null,
    val preparedWidth: Int? = null,
    val preparedHeight: Int? = null,
    val preparedDurationMs: Long? = null,
    /** Trim and mute are baked into the prepared video, so the send doesn't repeat them. */
    val preparedTrimmed: Boolean = false,
    /** An Android Motion Photo (a still with an embedded clip). */
    val motionPhoto: Boolean = false,
    /** Send a Motion Photo as live (default) or, when false, as a still only. */
    val live: Boolean = true,
    /** The clip of a live item, split out of the Motion Photo and uploaded on its own. */
    val motion: OutboxItem? = null,
) {
    val tusState: TusState? get() = uploadUrl?.let { TusState(it, uploadedBytes) }

    /** What goes over the wire: the prepared master when there is one. */
    val uploadSize: Long get() = preparedSize ?: size
    val uploadMime: String get() = preparedMime ?: mime
    val uploadName: String get() = if (preparedPath != null) name.substringBeforeLast('.') + "." + preparedPath.substringAfterLast('.') else name
    val isPrepared: Boolean get() = prepState == PREP_PREPARED

    /** Bytes of this item and its clip, for progress. */
    val totalUploadSize: Long get() = uploadSize + (motion?.uploadSize ?: 0)

    /**
     * Duplicates can't be trimmed or muted server-side (server NOTES), so only plain files offer a hash. A prepared
     * video has its trim and mute baked in, so its bytes are what the item is.
     */
    val dedupable: Boolean get() = (isPrepared && preparedTrimmed) || (trimStartMs == null && trimEndMs == null && includeAudio)

    /** Records the outcome of preparing this item. */
    fun withPrep(r: app.kinframe.family.send.prep.PrepResult): OutboxItem = when (r) {
        is app.kinframe.family.send.prep.PrepResult.Prepared -> copy(
            prepState = PREP_PREPARED, preparedPath = r.path, preparedSize = r.size, prepared = r.profile, preparedMime = r.mime,
            preparedWidth = r.width, preparedHeight = r.height, preparedDurationMs = r.durationMs, preparedTrimmed = r.trimmed,
            sha256 = null,
        )
        is app.kinframe.family.send.prep.PrepResult.Original -> copy(prepState = PREP_ORIGINAL)
    }

    fun toSendItem(): SendItemCreate {
        val video = kind == MediaKind.VIDEO
        val baked = isPrepared && preparedTrimmed
        return SendItemCreate(
            uploadId = requireNotNull(uploadId),
            caption = caption?.takeIf { it.isNotBlank() },
            focalPoint = if (focalX != null && focalY != null) FocalPoint(focalX, focalY) else null,
            fit = fit,
            trim = if (video && !baked && (trimStartMs != null || trimEndMs != null) && trimEndMs != null) Trim(trimStartMs ?: 0, trimEndMs) else null,
            includeAudio = if (video && !includeAudio) false else null,
            capturedAt = capturedAt,
            width = if (isPrepared) preparedWidth else null,
            height = if (isPrepared) preparedHeight else null,
            durationMs = if (isPrepared && video) preparedDurationMs else null,
            liveVideoUploadId = motion?.uploadId,
            live = if (motionPhoto && !live) false else null,
        )
    }

    companion object {
        const val PREP_PREPARED = "prepared"
        const val PREP_ORIGINAL = "original"
    }
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
    val totalBytes: Long get() = items.sumOf { it.totalUploadSize }
    val uploadedBytes: Long get() = items.sumOf { it.sentBytes() + (it.motion?.sentBytes() ?: 0) }
}

private fun OutboxItem.sentBytes(): Long = if (uploadId != null) uploadSize else uploadedBytes.coerceAtMost(uploadSize)

/**
 * Live progress of the send in flight (not persisted on every byte). While [preparing] is set, [fraction] is the
 * preparation progress of item [itemIndex] (resizing a photo, transcoding a video).
 */
data class OutboxProgress(
    val sendId: String,
    val itemIndex: Int,
    val itemCount: Int,
    val sent: Long,
    val total: Long,
    val preparing: Boolean = false,
    val prepFraction: Float = 0f,
) {
    val fraction: Float get() = if (preparing) prepFraction.coerceIn(0f, 1f) else if (total <= 0) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)
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
