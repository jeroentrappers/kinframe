package app.kinframe.frame.removable

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** A photo or video found on a stick, offered in the import picker. */
data class ImportCandidate(
    val file: File,
    /** Path relative to the volume root, for display and sorting. */
    val relativePath: String,
    val kind: String,
    val mime: String,
    val bytes: Long,
    val modifiedAt: Long,
    /** From an export manifest (restore): caption and capture time to send along. */
    val caption: String? = null,
    val capturedAt: String? = null,
    val senderName: String? = null,
    /** Known up front for exports (the manifest lists it), otherwise computed while importing. */
    val sha256: String? = null,
) {
    val name: String get() = file.name
}

/**
 * Finds importable media on a volume (frameo-behaviour-spec B.10 supported files, plus what the server converts:
 * README §5.3). Skips hidden folders, Android's own folders, and kinframe exports/backups (those are restored, not
 * imported). Bounded in depth and count so a stick full of files can't stall the frame.
 */
object ImportScanner {
    const val MAX_FILES = 2_000
    private const val MAX_DEPTH = 8
    /** The server accepts imports up to 512 MiB (openapi `deviceImport`). */
    const val MAX_IMPORT_BYTES = 512L * 1024 * 1024

    private val TYPES = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif",
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "3gp" to "video/3gpp", "mov" to "video/quicktime", "mkv" to "video/x-matroska",
        "webm" to "video/webm",
    )
    private val SKIP_DIRS = setOf("android", "lost.dir", "system volume information", "\$recycle.bin", "kinframe-export", "kinframe-backup")

    fun mimeOf(name: String): String? = TYPES[name.substringAfterLast('.', "").lowercase(Locale.ROOT)]

    fun scan(root: File, limit: Int = MAX_FILES): List<ImportCandidate> {
        val out = ArrayList<ImportCandidate>()
        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= limit) return
            val children = dir.listFiles() ?: return
            for (f in children.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                if (out.size >= limit) return
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (f.name.lowercase(Locale.ROOT) !in SKIP_DIRS) walk(f, depth + 1)
                    continue
                }
                val mime = mimeOf(f.name) ?: continue
                out += ImportCandidate(
                    file = f, relativePath = f.relativeTo(root).path, kind = if (mime.startsWith("video/")) "video" else "photo",
                    mime = mime, bytes = f.length(), modifiedAt = f.lastModified(),
                )
            }
        }
        walk(root, 0)
        return out
    }

    /** The items of a kinframe export or backup folder, with their manifest metadata (restore). */
    fun fromExport(dir: File, manifest: ExportManifest): List<ImportCandidate> = manifest.items.mapNotNull { e ->
        val f = File(dir, e.file).takeIf { it.isFile } ?: return@mapNotNull null
        val mime = e.mime ?: mimeOf(f.name) ?: return@mapNotNull null
        ImportCandidate(
            file = f, relativePath = "${dir.name}/${e.file}", kind = e.kind, mime = mime, bytes = f.length(), modifiedAt = f.lastModified(),
            caption = e.caption, capturedAt = e.capturedAt, senderName = e.senderName, sha256 = e.sha256,
        )
    }

    enum class Sort { Date, Name }

    fun sort(items: List<ImportCandidate>, sort: Sort): List<ImportCandidate> = when (sort) {
        Sort.Date -> items.sortedWith(compareByDescending<ImportCandidate> { it.modifiedAt }.thenBy { it.relativePath.lowercase(Locale.ROOT) })
        Sort.Name -> items.sortedBy { it.relativePath.lowercase(Locale.ROOT) }
    }
}

/** Streaming sha256 in hex. */
object Sha256 {
    fun of(input: InputStream, onBytes: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        input.use {
            while (true) {
                if (cancelled()) throw java.util.concurrent.CancellationException()
                val n = it.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
                onBytes(total)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun of(file: File): String = of(file.inputStream())
}

/**
 * Which picked files to upload. A file is skipped when the frame already has it (a local placement with that
 * rendition hash, or the server says so: source, master or rendition hash), when the same file was picked twice,
 * or when it is larger than the server accepts.
 */
object ImportDedupe {
    enum class Verdict { Upload, AlreadyOnFrame, DuplicateInSelection, TooLarge }

    fun classify(items: List<Pair<ImportCandidate, String>>, localHashes: Set<String>, serverKnown: Set<String>): List<Verdict> {
        val seen = HashSet<String>()
        return items.map { (c, sha) ->
            val h = sha.lowercase(Locale.ROOT)
            when {
                c.bytes > ImportScanner.MAX_IMPORT_BYTES -> Verdict.TooLarge
                h in localHashes || h in serverKnown -> Verdict.AlreadyOnFrame
                !seen.add(h) -> Verdict.DuplicateInSelection
                else -> Verdict.Upload
            }
        }
    }
}
