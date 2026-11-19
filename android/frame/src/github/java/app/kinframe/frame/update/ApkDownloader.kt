package app.kinframe.frame.update

import app.kinframe.core.api.ApiException
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.FrameUpdate
import app.kinframe.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Resumable APK download into [dir]: `<code>.apk.part` grows across attempts (Range + If-Range with the server's
 * ETag), and becomes `<code>.apk` once complete. The caller verifies the hash and the package before installing.
 */
class ApkDownloader(private val api: DeviceApi, private val dir: File) {
    fun target(u: FrameUpdate) = File(dir, "kinframe-frame-${u.versionCode}.apk")
    private fun part(u: FrameUpdate) = File(dir, "kinframe-frame-${u.versionCode}.apk.part")
    private fun etagFile(u: FrameUpdate) = File(dir, "kinframe-frame-${u.versionCode}.apk.etag")

    /** Downloads (or finishes downloading) [u]; returns the complete file. [onProgress] gets bytes so far. */
    suspend fun fetch(u: FrameUpdate, onProgress: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val done = target(u)
        if (done.exists() && done.length() == u.size) return@withContext done
        done.delete()
        // Older downloads (other versions) are useless now.
        dir.listFiles()?.filter { it.name.startsWith("kinframe-frame-") && !it.name.startsWith("kinframe-frame-${u.versionCode}.") }
            ?.forEach { it.delete() }
        val part = part(u)
        var offset = if (part.exists()) part.length() else 0L
        if (offset > u.size) { part.delete(); offset = 0 }
        if (offset < u.size) {
            val url = api.resolve(u.url) ?: throw IOException("bad update url")
            val etag = AtomicFile.readTextOrNull(etagFile(u))?.trim()?.takeIf { it.isNotEmpty() }
            api.media(url, rangeFrom = offset, ifRange = etag).use { r ->
                if (r.code == 416) { part.delete(); throw IOException("range not satisfiable") }
                if (!r.isSuccessful) throw ApiException(r.code, null)
                val append = r.code == 206 && offset > 0 &&
                    Regex("""bytes (\d+)-""").find(r.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull() == offset
                if (!append) offset = 0
                r.header("ETag")?.let { runCatching { AtomicFile.writeText(etagFile(u), it) } }
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    val input = r.body.byteStream()
                    var total = offset
                    var reported = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > u.size) throw IOException("size_mismatch")
                        out.write(buf, 0, n)
                        if (total - reported > 256 * 1024) { onProgress(total); reported = total }
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
        }
        if (part.length() != u.size) throw IOException("incomplete download (${part.length()} of ${u.size})")
        if (!part.renameTo(done)) throw IOException("rename failed")
        etagFile(u).delete()
        onProgress(u.size)
        done
    }

    /** Forgets a download (bad hash, bad signature): the next attempt starts from zero. */
    fun discard(u: FrameUpdate) {
        target(u).delete(); part(u).delete(); etagFile(u).delete()
    }

    companion object {
        fun sha256(f: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    d.update(buf, 0, n)
                }
            }
            return d.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
