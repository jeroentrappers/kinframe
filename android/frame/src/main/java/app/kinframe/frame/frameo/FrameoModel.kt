package app.kinframe.frame.frameo

import app.kinframe.core.api.FocalPoint
import app.kinframe.core.api.ImportOrigin
import app.kinframe.core.api.ItemOrigin
import app.kinframe.frame.removable.ImportScanner
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * What the old app's database says about one received file (docs/spec/frameo-import.md, "The database"). Every
 * field is optional: a file without an entry is still imported.
 */
data class FrameoEntry(
    /** File name inside `frameo_files/media/`, the import's source key. */
    val fileName: String,
    val receivedAtMs: Long? = null,
    val capturedAtMs: Long? = null,
    val caption: String? = null,
    val senderName: String? = null,
    /** False when the photo was hidden from the slideshow. */
    val enabled: Boolean = true,
    val scaleType: Int? = null,
    val centerX: Double? = null,
    val centerY: Double? = null,
    val rotation: Int = 0,
)

/** One file to import. [key] is the Frameo file name (stable per frame, so re-runs never duplicate). */
data class FrameoCandidate(val file: File, val key: String, val mime: String, val bytes: Long, val entry: FrameoEntry?) {
    val kind: String get() = if (mime.startsWith("video/")) "video" else "photo"
}

/**
 * Read access to one stored object, so the mapping from database objects to [FrameoEntry] runs (and is tested)
 * without the native database library. Missing fields read as null.
 */
interface FieldSource {
    fun string(name: String): String?
    fun epochMs(name: String): Long?
    fun bool(name: String): Boolean?
    fun long(name: String): Long?
    fun double(name: String): Double?
    fun obj(name: String): FieldSource?
}

object FrameoMapping {
    /** Class and field names as observed in the database (interoperability, see the spec note). */
    const val MEDIA_CLASS = "Media"

    /** Received times before this are not plausible for a file name (2010-01-01). */
    private const val MIN_PLAUSIBLE_MS = 1_262_304_000_000L

    /** One `Media` object, or null when it has no usable file path. */
    fun entryOf(m: FieldSource): FrameoEntry? {
        val path = m.string("filePath")?.trim().orEmpty()
        val name = path.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return null
        val edited = m.bool("isCaptionEdited") == true
        val caption = (if (edited) m.string("editedCaption") else m.string("caption"))?.trim()?.takeIf { it.isNotEmpty() }
        val picture = m.obj("picture")
        return FrameoEntry(
            fileName = name,
            receivedAtMs = m.epochMs("receivedTime"),
            capturedAtMs = m.epochMs("captureTime"),
            caption = caption,
            senderName = m.obj("friend")?.string("name")?.trim()?.takeIf { it.isNotEmpty() },
            enabled = m.bool("isEnabled") ?: true,
            scaleType = picture?.long("scaleType")?.toInt(),
            centerX = picture?.double("centerPointX"),
            centerY = picture?.double("centerPointY"),
            rotation = picture?.long("rotation")?.toInt() ?: 0,
        )
    }

    /** The received time: from the database, else the number in the file name. */
    fun receivedAtMs(c: FrameoCandidate): Long? =
        c.entry?.receivedAtMs ?: c.key.substringBefore('.').toLongOrNull()?.takeIf { it >= MIN_PLAUSIBLE_MS }

    /** 0 crops to fill the screen, 1 shows the whole photo (inferred from data, see the spec note). */
    fun fitOf(scaleType: Int?): String? = when (scaleType) {
        0 -> "fill"
        1 -> "fit"
        else -> null
    }

    /** The centre point to keep in view when cropping; only for "fill" photos, and not the default centre. */
    fun focalOf(e: FrameoEntry): FocalPoint? {
        if (fitOf(e.scaleType) != "fill") return null
        val x = e.centerX ?: return null
        val y = e.centerY ?: return null
        if (x.isNaN() || y.isNaN() || x !in 0.0..1.0 || y !in 0.0..1.0) return null
        if (x == 0.5 && y == 0.5) return null
        return FocalPoint(x, y)
    }

    /** A quarter turn kinframe can show, else none. */
    fun rotationOf(degrees: Int): Int = ((degrees % 360 + 360) % 360).takeIf { it % 90 == 0 } ?: 0

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    /** What `POST /device/imports` gets for a candidate. */
    fun origin(c: FrameoCandidate): ImportOrigin {
        val e = c.entry
        val photo = c.kind == "photo"
        return ImportOrigin(
            source = ItemOrigin.FRAMEO,
            key = c.key,
            senderName = e?.senderName,
            receivedAt = receivedAtMs(c)?.let(::iso),
            fit = if (photo) fitOf(e?.scaleType) else null,
            focal = if (photo && e != null) focalOf(e) else null,
            hidden = e?.enabled == false,
            rotation = if (photo && e != null) rotationOf(e.rotation) else 0,
        )
    }

    fun capturedAt(c: FrameoCandidate): String? = c.entry?.capturedAtMs?.let(::iso)

    /**
     * Every photo or video in the media folder, oldest first, with its database entry when there is one. Other
     * files and files larger than the server accepts are left out.
     */
    fun plan(files: List<File>, entries: Map<String, FrameoEntry>): List<FrameoCandidate> = files.mapNotNull { f ->
        if (!f.isFile || f.name.startsWith(".")) return@mapNotNull null
        val mime = ImportScanner.mimeOf(f.name) ?: return@mapNotNull null
        val bytes = f.length()
        if (bytes <= 0 || bytes > ImportScanner.MAX_IMPORT_BYTES) return@mapNotNull null
        FrameoCandidate(f, f.name, mime, bytes, entries[f.name])
    }.sortedWith(compareBy<FrameoCandidate> { receivedAtMs(it) ?: Long.MAX_VALUE }.thenBy { it.key.lowercase(Locale.ROOT) })
}
