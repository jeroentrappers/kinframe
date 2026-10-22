package app.kinframe.frame.sync

import android.util.Log
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.DeviceRevokedException
import app.kinframe.frame.data.AckEntity
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.data.MediaCache
import app.kinframe.frame.data.PlacementEntity
import app.kinframe.core.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.random.Random

/**
 * Downloads frame renditions: resumable `Range` requests into `partial/`, SHA-256 verification, fsync, atomic
 * rename into the content-addressed cache. At most [parallelism] downloads run at once; failures back off
 * per placement (30 s doubling to 1 h, with jitter).
 */
class Downloader(
    private val api: DeviceApi,
    private val db: FrameDatabase,
    private val media: MediaCache,
    private val acks: AckQueue,
    private val now: () -> Long,
    private val parallelism: Int = 2,
    private val minFreeBytes: Long = MediaCache.MIN_FREE_BYTES,
    private val freeBytes: () -> Long = media::freeBytes,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val inFlight: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    var onStored: (PlacementEntity) -> Unit = {}
    var onRevoked: () -> Unit = {}

    fun kick() { wake.trySend(Unit) }

    /** Runs until cancelled. */
    suspend fun run() = coroutineScope {
        val gate = Semaphore(parallelism)
        while (isActive) {
            val due = db.placements().pendingDownloads(now(), 20).filter { it.id !in inFlight }
            if (due.isEmpty() || inFlight.size >= parallelism) {
                val earliest = db.placements().earliestRetry()
                val wait = when {
                    inFlight.isNotEmpty() -> 60_000L
                    earliest == null -> Long.MAX_VALUE
                    else -> (earliest - now()).coerceIn(1_000, 3_600_000)
                }
                if (wait == Long.MAX_VALUE) wake.receive() else withTimeoutOrNull(wait) { wake.receive() }
                continue
            }
            for (p in due) {
                if (inFlight.size >= parallelism) break
                inFlight += p.id
                launch {
                    try {
                        gate.withPermit { downloadOne(p) }
                    } finally {
                        inFlight -= p.id
                        kick()
                    }
                }
            }
            // Wait for a slot or a nudge before looking at the queue again.
            withTimeoutOrNull(60_000) { wake.receive() }
        }
    }

    /** Downloads one placement now; returns true when it is stored. Public for tests. */
    suspend fun downloadOne(p: PlacementEntity): Boolean {
        val sha = p.mediaSha256 ?: return false
        val url = p.mediaUrl?.let { api.resolve(it) } ?: return false
        val name = media.fileName(sha, p.mediaMime)
        val target = media.file(name)
        try {
            // Same content already on disk (another placement, or before a reset): just link it.
            if (target.exists() && (p.mediaBytes <= 0 || target.length() == p.mediaBytes)) {
                link(sha, name, p)
                return true
            }
            if (freeBytes() - p.mediaBytes < minFreeBytes) throw IOException("storage_full")
            val part = media.partial(sha)
            fetchInto(url, part, sha, p.mediaBytes)
            if (!part.renameTo(target)) throw IOException("rename failed")
            AtomicFile.syncDir(media.dir)
            link(sha, name, p)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceRevokedException) {
            onRevoked()
            return false
        } catch (e: Exception) {
            val attempts = p.downloadAttempts + 1
            val base = min(3_600_000.0, 30_000.0 * Math.pow(2.0, (attempts - 1).toDouble()))
            val jittered = (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
            val next = now() + if (e.message == "storage_full") 3_600_000L else jittered
            Log.w(TAG, "download ${p.id} failed (attempt $attempts): ${e.message}")
            db.placements().markFailed(p.id, attempts, next, e.message?.take(120))
            return false
        }
    }

    private suspend fun link(sha: String, name: String, p: PlacementEntity) {
        val ids = db.tx {
            db.placements().markStoredBySha(sha, name, now())
            val ids = db.placements().idsWithSha(sha)
            ids.forEach { acks.enqueueDeliveredInTx(it) }
            ids
        }
        acks.nudge()
        db.placements().get(p.id)?.let(onStored)
        if (ids.isEmpty()) Log.i(TAG, "stored $name but placement ${p.id} is gone")
    }

    private suspend fun fetchInto(url: okhttp3.HttpUrl, part: File, sha: String, expectedBytes: Long) {
        var offset = if (part.exists()) part.length() else 0L
        if (expectedBytes in 1..offset) {
            // Complete (or too long) partial from before a power cut: verify what we have.
            if (offset == expectedBytes && verify(part, sha)) return
            part.delete(); offset = 0
        }
        api.media(url, rangeFrom = offset).use { r ->
            if (r.code == 416) { part.delete(); throw IOException("range not satisfiable") }
            if (!r.isSuccessful) throw ApiException(r.code, null)
            val append = r.code == 206 && offset > 0 && contentRangeStart(r.header("Content-Range")) == offset
            if (!append) offset = 0
            val digest = MessageDigest.getInstance("SHA-256")
            withContext(Dispatchers.IO) {
                if (append) part.inputStream().use { feed(digest, it) }
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    val input = r.body.byteStream()
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            val hex = digest.digest().toHex()
            if (!hex.equals(sha, ignoreCase = true)) {
                part.delete()
                throw IOException("sha256 mismatch")
            }
            if (expectedBytes > 0 && part.length() != expectedBytes) {
                part.delete()
                throw IOException("size mismatch")
            }
        }
    }

    private fun verify(f: File, sha: String): Boolean {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { feed(d, it) }
        return d.digest().toHex().equals(sha, ignoreCase = true)
    }

    private fun feed(d: MessageDigest, input: InputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            d.update(buf, 0, n)
        }
    }

    private fun contentRangeStart(h: String?): Long? =
        h?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    companion object {
        private const val TAG = "KinDownload"
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
