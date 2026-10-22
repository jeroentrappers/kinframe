package app.kinframe.frame.removable

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.kinframe.frame.system.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/**
 * Writes an export or backup folder onto a stick. Android 6 lets apps write to removable storage only in their own
 * `Android/data/<pkg>` folder, or through a Storage Access Framework grant; with root the frame can write anywhere.
 * [WriterChooser] tries the plain ways first and root only when they can't write.
 */
interface VolumeWriter {
    enum class Method { Direct, Saf, Root, AppFolder }

    val method: Method
    /** Where the files end up, as people see the stick (for messages). */
    val shownDir: String
    /** The folder as the app can read it (to find what an earlier export left there). */
    val readDir: File get() = File(shownDir)

    /** Creates the folder and checks that writing works. */
    suspend fun prepare(): Boolean

    /** Copies [src] to [name] in the folder (replacing it), via a temporary name so a pulled stick leaves no half file. */
    suspend fun copy(src: File, name: String, onBytes: (Long) -> Unit, cancelled: () -> Boolean): Boolean

    suspend fun writeText(name: String, text: String): Boolean

    /** Flushes to the medium (FAT has no journal: pulling the stick before this can lose the last files). */
    suspend fun sync() {}
}

/** Streams [input] into [out], reporting bytes and stopping when [cancelled]. */
internal fun pump(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
    val buf = ByteArray(128 * 1024)
    var total = 0L
    while (true) {
        if (cancelled()) throw CancellationException("export cancelled")
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        onBytes(total)
    }
}

/** Plain file access: a writable volume root (some firmwares), the app's own folder on the stick, or test folders. */
class DirectWriter(private val dir: File, override val method: VolumeWriter.Method = VolumeWriter.Method.Direct) : VolumeWriter {
    override val shownDir: String get() = dir.absolutePath

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val probe = File(dir, ".kinframe-probe")
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrDefault(false)
    }

    override suspend fun copy(src: File, name: String, onBytes: (Long) -> Unit, cancelled: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val part = File(dir, "$name.part")
        try {
            FileOutputStream(part).use { out ->
                src.inputStream().use { pump(it, out, onBytes, cancelled) }
                out.flush()
                runCatching { out.fd.sync() }
            }
            val target = File(dir, name)
            if (target.exists()) target.delete()
            part.renameTo(target)
        } catch (e: CancellationException) {
            part.delete(); throw e
        } catch (e: Exception) {
            Log.w(TAG, "copy $name failed", e)
            part.delete()
            false
        }
    }

    override suspend fun writeText(name: String, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val part = File(dir, "$name.part")
            FileOutputStream(part).use { it.write(text.toByteArray()); it.flush(); runCatching { it.fd.sync() } }
            File(dir, name).delete()
            part.renameTo(File(dir, name))
        }.getOrDefault(false)
    }

    private companion object { const val TAG = "KinExport" }
}

/**
 * Root fallback: `cp` from the app's private media folder onto the raw vfat mount (`/mnt/media_rw/<uuid>`), which
 * root can write even where the `/storage` view is read-only for apps. Commands go to [RootShell] on stdin, so plain
 * shell quoting works (the frame's `su` mangles `-c` arguments, see [RootShell.run]).
 */
class RootWriter(
    private val root: RootShell,
    /** The folder on the raw mount, e.g. `/mnt/media_rw/1A2B-3C4D/kinframe-export`. */
    private val rawDir: File,
    /** The same folder as apps see it (for messages). */
    private val viewDir: File,
    private val tempDir: File,
) : VolumeWriter {
    override val method = VolumeWriter.Method.Root
    override val shownDir: String get() = viewDir.absolutePath

    override suspend fun prepare(): Boolean {
        val probe = q(File(rawDir, ".kinframe-probe").path)
        return root.run("mkdir -p ${q(rawDir.path)} && echo ok > $probe && rm $probe && echo DONE").ok()
    }

    override suspend fun copy(src: File, name: String, onBytes: (Long) -> Unit, cancelled: () -> Boolean): Boolean {
        if (cancelled()) throw CancellationException("export cancelled")
        val part = q(File(rawDir, "$name.part").path)
        val target = q(File(rawDir, name).path)
        // Generous timeout: USB 2 sticks write 3–10 MB/s; allow 256 KiB/s plus a minute.
        val timeout = 60_000L + src.length() / 256
        val ok = root.run("cp ${q(src.path)} $part && mv -f $part $target && echo DONE", timeoutMs = timeout).ok()
        if (ok) onBytes(src.length()) else root.run("rm -f $part")
        return ok
    }

    override suspend fun writeText(name: String, text: String): Boolean {
        val tmp = withContext(Dispatchers.IO) {
            File(tempDir, "export-$name").apply { parentFile?.mkdirs(); writeText(text) }
        }
        return try {
            copy(tmp, name, {}, { false })
        } finally {
            withContext(Dispatchers.IO) { tmp.delete() }
        }
    }

    override suspend fun sync() { root.run("sync", timeoutMs = 60_000) }

    private fun RootShell.Result.ok() = exitCode == 0 && output.contains("DONE")

    companion object {
        /** Single-quotes [s] for sh. */
        fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}

/** Storage Access Framework: a folder tree the person granted once with the system picker (persisted per stick). */
class SafWriter(private val resolver: ContentResolver, private val tree: Uri, private val folder: String, override val shownDir: String) : VolumeWriter {
    override val method = VolumeWriter.Method.Saf
    private var dirUri: Uri? = null

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            dirUri = child(rootDoc, folder)
                ?: DocumentsContract.createDocument(resolver, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, folder)
            dirUri != null
        }.onFailure { Log.w("KinExport", "SAF prepare failed", it) }.getOrDefault(false)
    }

    private fun child(parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
    }

    private fun create(name: String, mime: String): Uri? {
        val dir = dirUri ?: return null
        child(dir, name)?.let { DocumentsContract.deleteDocument(resolver, it) }
        return DocumentsContract.createDocument(resolver, dir, mime, name)
    }

    override suspend fun copy(src: File, name: String, onBytes: (Long) -> Unit, cancelled: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        var uri: Uri? = null
        try {
            uri = create(name, ImportScanner.mimeOf(name) ?: "application/octet-stream") ?: return@withContext false
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { pump(it, out, onBytes, cancelled) } }
            true
        } catch (e: CancellationException) {
            uri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw e
        } catch (e: Exception) {
            Log.w("KinExport", "SAF copy $name failed", e)
            uri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            false
        }
    }

    override suspend fun writeText(name: String, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val uri = create(name, "application/json") ?: return@runCatching false
            resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            true
        }.getOrDefault(false)
    }
}

/**
 * Picks how to write [folder] on [volume]: directly, then a SAF grant, then root, then the app's own folder on the
 * stick (always writable, but Android deletes it if kinframe is uninstalled). Null when nothing can write.
 */
class WriterChooser(
    private val root: RootShell,
    private val tempDir: File,
    private val safFor: (Volume) -> Uri?,
    private val resolver: ContentResolver?,
    private val appDirOn: (Volume) -> File?,
) {
    suspend fun choose(volume: Volume, folder: String): VolumeWriter? {
        val direct = DirectWriter(File(volume.path, folder))
        if (direct.prepare()) return direct
        val tree = safFor(volume)
        if (tree != null && resolver != null) {
            val saf = SafWriter(resolver, tree, folder, File(volume.path, folder).path)
            if (saf.prepare()) return saf
        }
        if (root.isAvailable()) {
            for (base in listOfNotNull(volume.rawPath, volume.uuid?.let { File("/mnt/media_rw/$it") }, volume.path).distinct()) {
                val w = RootWriter(root, File(base, folder), File(volume.path, folder), tempDir)
                if (w.prepare()) return w
            }
        }
        val app = appDirOn(volume)?.let { DirectWriter(File(it, folder), VolumeWriter.Method.AppFolder) }
        if (app != null && app.prepare()) return app
        return null
    }
}
