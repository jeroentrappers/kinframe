package app.kinframe.core.util

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe small-file writes: write a temp file, fsync it, rename over the target, fsync the directory.
 * A power cut at any moment leaves either the old or the new content, never a torn file.
 */
object AtomicFile {
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("rename failed for $target")
        }
        syncDir(target.parentFile)
    }

    fun writeText(target: File, text: String) = write(target, text.toByteArray(Charsets.UTF_8))

    fun read(target: File): ByteArray = target.readBytes()

    fun readTextOrNull(target: File): String? = runCatching { if (target.exists()) target.readText() else null }.getOrNull()

    /** fsync a directory so a rename inside it is durable. Best effort (not every FS allows it). */
    fun syncDir(dir: File?) {
        if (dir == null) return
        runCatching {
            val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
}
