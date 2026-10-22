package app.kinframe.frame.storage

import android.util.Log
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.data.MediaCache
import app.kinframe.frame.data.PlacementEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * When to free space (product spec 4.6: "the frame cache is managed automatically"). Thresholds are on free bytes
 * of the data partition: start evicting below [lowBytes], free up to [targetBytes], and call it critical below
 * [criticalBytes] when nothing more can be evicted. Small partitions scale the thresholds down.
 */
data class StoragePolicy(val lowBytes: Long, val targetBytes: Long, val criticalBytes: Long) {
    enum class Level { Ok, Low, Critical }

    fun level(freeBytes: Long): Level = when {
        freeBytes < criticalBytes -> Level.Critical
        freeBytes < lowBytes -> Level.Low
        else -> Level.Ok
    }

    companion object {
        private const val MB = 1024L * 1024

        fun forTotal(totalBytes: Long): StoragePolicy {
            fun cap(fixed: Long, fraction: Double) = if (totalBytes <= 0) fixed else minOf(fixed, (totalBytes * fraction).toLong())
            val critical = maxOf(MediaCache.MIN_FREE_BYTES + 64 * MB, cap(256 * MB, 0.02))
            val low = maxOf(critical + 128 * MB, cap(512 * MB, 0.04))
            val target = maxOf(low + 128 * MB, cap(768 * MB, 0.06))
            return StoragePolicy(low, target, critical)
        }
    }
}

/**
 * Picks cached files to delete: least recently shown first (never-shown items count from when they were stored),
 * never anything [protectedIds] uses (the current and next slides, items with acks not yet sent). Files are
 * content-addressed and may back several placements, so candidates are per file.
 */
object EvictionPolicy {
    data class Candidate(val file: String, val bytes: Long, val lastUsedAt: Long, val placementIds: List<String>)

    fun candidates(placements: List<PlacementEntity>, fileSize: (String) -> Long): List<Candidate> =
        placements.filter { it.localFile != null }.groupBy { it.localFile!! }.map { (file, ps) ->
            Candidate(
                file = file,
                bytes = ps.maxOf { it.mediaBytes }.takeIf { it > 0 } ?: fileSize(file),
                lastUsedAt = ps.maxOf { it.lastShownAt ?: it.storedAt ?: 0L },
                placementIds = ps.map { it.id },
            )
        }

    fun select(candidates: List<Candidate>, protectedIds: Set<String>, bytesToFree: Long): List<Candidate> {
        if (bytesToFree <= 0) return emptyList()
        val out = ArrayList<Candidate>()
        var freed = 0L
        for (c in candidates.filter { cand -> cand.placementIds.none { it in protectedIds } }
            .sortedWith(compareBy<Candidate> { it.lastUsedAt }.thenByDescending { it.bytes }.thenBy { it.file })) {
            if (freed >= bytesToFree) break
            out += c
            freed += c.bytes
        }
        return out
    }
}

/**
 * Keeps enough free space for new photos: evicts least-recently-shown renditions (the server keeps everything, so
 * nothing is lost) and puts them back in the download queue when the slideshow is about to need them.
 */
class StorageManager(
    private val db: FrameDatabase,
    private val media: MediaCache,
    private val protectedIds: suspend () -> Set<String>,
    private val now: () -> Long,
    private val freeBytes: () -> Long = media::freeBytes,
    private val totalBytes: () -> Long = media::totalBytes,
) {
    data class State(
        val freeBytes: Long = Long.MAX_VALUE,
        val totalBytes: Long = 0,
        val evicted: Int = 0,
        val level: StoragePolicy.Level = StoragePolicy.Level.Ok,
    )

    private val lock = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    /** Called after evicting or un-evicting so the downloader re-reads its queue. */
    var onQueueChanged: () -> Unit = {}

    val policy: StoragePolicy get() = StoragePolicy.forTotal(totalBytes())

    /**
     * Frees space when below the low mark (or when [extraBytes] more must fit). Returns true when, afterwards, at
     * least [MediaCache.MIN_FREE_BYTES] plus [extraBytes] are free.
     */
    suspend fun ensureSpace(extraBytes: Long = 0): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val p = policy
            var free = freeBytes()
            if (free - extraBytes < p.lowBytes) {
                val need = p.targetBytes + extraBytes - free
                val protect = protectedIds() + db.acks().placementIds()
                val chosen = EvictionPolicy.select(EvictionPolicy.candidates(db.placements().all()) { media.file(it).length() }, protect, need)
                if (chosen.isNotEmpty()) {
                    val at = now()
                    for (c in chosen) {
                        // Database first: a power cut in between leaves an orphan file, which garbage collection removes.
                        db.placements().evict(c.file, at)
                        media.file(c.file).delete()
                    }
                    Log.i(TAG, "evicted ${chosen.size} files (${chosen.sumOf { it.bytes } / 1024} KiB), free was ${free / 1024 / 1024} MiB")
                    free = freeBytes()
                }
            }
            publish(free)
            free - extraBytes >= MediaCache.MIN_FREE_BYTES
        }
    }

    /** The slideshow wants an evicted placement: download it again (first in the queue). */
    suspend fun requestOnDemand(id: String) {
        val changed = withContext(Dispatchers.IO) { db.placements().unevict(id) }
        if (changed > 0) {
            Log.i(TAG, "re-downloading $id on demand")
            onQueueChanged()
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) { publish(freeBytes()) }

    private suspend fun publish(free: Long) {
        val total = totalBytes()
        _state.value = State(free, total, db.placements().countEvicted(), StoragePolicy.forTotal(total).level(free))
    }

    private companion object { const val TAG = "KinStorage" }
}
