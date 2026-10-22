package app.kinframe.frame.data

import android.os.StatFs
import java.io.File

/**
 * Content-addressed media storage: `media/<sha256>.<ext>`, partial downloads in `media/partial/`.
 * Identical renditions are stored once, and a re-sync after `reset` reuses files already on disk.
 */
class MediaCache(root: File) {
    val dir = File(root, "media").apply { mkdirs() }
    val partialDir = File(dir, "partial").apply { mkdirs() }
    val avatarDir = File(root, "avatars").apply { mkdirs() }

    fun fileName(sha256: String, mime: String?): String = "${sha256.lowercase()}.${ext(mime)}"
    fun file(name: String) = File(dir, name)
    fun partial(sha256: String) = File(partialDir, "${sha256.lowercase()}.part")
    fun avatar(userId: String) = File(avatarDir, "$userId.img")

    fun freeBytes(): Long = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
    fun totalBytes(): Long = runCatching { StatFs(dir.absolutePath).totalBytes }.getOrDefault(0L)
    fun usedBytes(): Long = (dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L) +
        (partialDir.listFiles()?.sumOf { it.length() } ?: 0L)

    fun deleteAll() {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        partialDir.listFiles()?.forEach { it.delete() }
        avatarDir.listFiles()?.forEach { it.delete() }
    }

    private fun ext(mime: String?) = when (mime?.lowercase()) {
        "image/webp" -> "webp"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        null -> "bin"
        else -> if (mime.startsWith("video/")) "mp4" else "img"
    }

    companion object {
        /** Refuse new media below this much free space (Frameo refuses below 128 MB). */
        const val MIN_FREE_BYTES = 128L * 1024 * 1024
    }
}
