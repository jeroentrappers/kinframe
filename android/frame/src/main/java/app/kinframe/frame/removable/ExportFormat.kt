package app.kinframe.frame.removable

import app.kinframe.core.api.KinJson
import app.kinframe.frame.data.PlacementEntity
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Names for exported files: `YYYY-MM-DD_sender_caption.ext` (frameo-behaviour-spec B.10 export, product spec 4.10).
 *
 * The stick is usually FAT32 (vfat): long names are UTF-16, at most 255 characters, case-insensitive, and may not
 * contain `" * / : < > ? \ |` or control characters, nor end in a dot or space. Names stay well under the limit
 * so the whole path fits too, and collisions get `_2`, `_3`, … (compared case-insensitively).
 */
object ExportNaming {
    private const val MAX_SENDER = 24
    private const val MAX_CAPTION = 48
    private val INVALID = Regex("""[\u0000-\u001f"*/:<>?\\|\u007f]""")
    private val SPACES = Regex("""\s+""")
    private val DASHES = Regex("""[-_]{2,}""")

    /** One FAT-safe name part: invalid characters dropped, whitespace as `-`, trimmed, capped at [max] characters. */
    fun part(text: String?, max: Int): String {
        if (text.isNullOrBlank()) return ""
        val clean = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(INVALID, "")
            .replace('_', '-')
            .trim()
            .replace(SPACES, "-")
            .replace(DASHES, "-")
        // Cut on a code point boundary, then drop trailing dots, dashes and spaces (FAT ignores trailing dots).
        val cut = if (clean.codePointCount(0, clean.length) > max) clean.substring(0, clean.offsetByCodePoints(0, max)) else clean
        return cut.trim('.', '-', ' ')
    }

    fun extension(mime: String?, kind: String): String = when (mime?.lowercase(Locale.ROOT)) {
        "image/webp" -> "webp"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        "video/quicktime" -> "mov"
        else -> if (kind == "video" || mime?.startsWith("video/") == true) "mp4" else "jpg"
    }

    /** The date in the name: when it was taken, else when it arrived, in the frame's time zone. */
    fun date(capturedAt: Long?, receivedAt: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(capturedAt ?: receivedAt).atZone(zone).toLocalDate().toString()

    /** `2026-10-22_Oma_Sunday-at-the-sea.webp`, unique among [taken] (lower-cased names, updated). */
    fun name(date: String, sender: String?, caption: String?, ext: String, taken: MutableSet<String>): String {
        val base = listOf(date, part(sender, MAX_SENDER), part(caption, MAX_CAPTION)).filter { it.isNotEmpty() }.joinToString("_")
        var candidate = "$base.$ext"
        var n = 2
        while (!taken.add(candidate.lowercase(Locale.ROOT))) candidate = "${base}_${n++}.$ext"
        return candidate
    }

    fun forPlacement(p: PlacementEntity, zone: ZoneId, taken: MutableSet<String>): String =
        name(date(p.capturedAt, p.receivedAt, zone), p.senderName, p.caption, extension(p.mediaMime, p.kind), taken)
}

/** FAT32 cannot hold files of 4 GiB or more; such files are skipped with a note instead of failing the export. */
object Fat32 {
    const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024 - 1
    fun fits(bytes: Long) = bytes in 0..MAX_FILE_BYTES

    /** File systems with the 4 GiB limit (vold reports vfat for FAT12/16/32). exFAT and others are unlimited. */
    fun limited(fsType: String?): Boolean = fsType == null || fsType.lowercase(Locale.ROOT) in setOf("vfat", "fat", "fat32", "msdos")
}

/** `kinframe-export.json`: what is in an export or backup folder, so a frame can restore captions, senders and dates. */
@Serializable
data class ExportManifest(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: String,
    val frameName: String? = null,
    val appVersion: String? = null,
    val items: List<Entry> = emptyList(),
    val skipped: List<Skipped> = emptyList(),
) {
    @Serializable
    data class Entry(
        val file: String,
        val placementId: String,
        val itemId: String? = null,
        val kind: String,
        val senderName: String? = null,
        val caption: String? = null,
        val capturedAt: String? = null,
        val receivedAt: String? = null,
        val sha256: String? = null,
        val bytes: Long = 0,
        val mime: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
    )

    @Serializable
    data class Skipped(val placementId: String, val reason: String)

    fun encode(): String = PrettyJson.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = "kinframe-export"
        const val FILE_NAME = "kinframe-export.json"
        private val PrettyJson = kotlinx.serialization.json.Json(KinJson) { prettyPrint = true }

        /** Null for anything that is not a kinframe manifest (or is from a newer, incompatible format). */
        fun parse(text: String): ExportManifest? = runCatching { KinJson.decodeFromString(serializer(), text) }.getOrNull()
            ?.takeIf { it.format == FORMAT && it.version in 1..1 }

        fun entry(p: PlacementEntity, file: String): Entry = Entry(
            file = file, placementId = p.id, itemId = p.itemId, kind = p.kind, senderName = p.senderName, caption = p.caption,
            capturedAt = p.capturedAt?.let { Instant.ofEpochMilli(it).toString() }, receivedAt = Instant.ofEpochMilli(p.receivedAt).toString(),
            sha256 = p.mediaSha256?.lowercase(Locale.ROOT), bytes = p.mediaBytes, mime = p.mediaMime, width = p.width, height = p.height,
            durationMs = p.durationMs,
        )
    }
}

/** Why an item is not in an export. */
object SkipReason {
    const val NOT_ON_FRAME = "not_on_frame"
    const val TOO_LARGE_FOR_FAT32 = "too_large_for_fat32"
    const val NO_SPACE = "no_space"
    const val WRITE_FAILED = "write_failed"
}

/**
 * What an export or (incremental) backup copies. Visible items only, oldest first. Items already in the folder's
 * manifest with the same sha256 and an existing file are kept as they are; the rest get fresh names.
 */
object ExportPlanner {
    data class Copy(val placement: PlacementEntity, val file: String)

    data class Plan(
        val copies: List<Copy>,
        /** Entries of the previous manifest that stay (their files are already there). */
        val kept: List<ExportManifest.Entry>,
        val skipped: List<ExportManifest.Skipped>,
    ) {
        val bytes: Long get() = copies.sumOf { it.placement.mediaBytes }
        val total: Int get() = copies.size + kept.size
    }

    fun plan(
        placements: List<PlacementEntity>,
        previous: ExportManifest?,
        zone: ZoneId,
        fatLimited: Boolean,
        fileExists: (String) -> Boolean,
    ): Plan {
        val visible = placements.filter { it.isVisible }.sortedWith(compareBy<PlacementEntity> { it.receivedAt }.thenBy { it.id })
        val prevById = previous?.items?.associateBy { it.placementId }.orEmpty()
        val kept = ArrayList<ExportManifest.Entry>()
        val taken = HashSet<String>()
        // Entries of removed items stay too: a backup never loses what it once held.
        val visibleIds = visible.mapTo(HashSet()) { it.id }
        previous?.items?.filter { it.placementId !in visibleIds && fileExists(it.file) }?.forEach { kept += it; taken += it.file.lowercase(Locale.ROOT) }
        val todo = ArrayList<PlacementEntity>()
        for (p in visible) {
            val prev = prevById[p.id]
            val same = prev != null && p.mediaSha256 != null && prev.sha256.equals(p.mediaSha256, ignoreCase = true) && fileExists(prev.file)
            if (same) {
                // Captions may have been edited since: refresh the metadata, keep the file.
                kept += ExportManifest.entry(p, prev.file)
                taken += prev.file.lowercase(Locale.ROOT)
            } else {
                todo += p
            }
        }
        val copies = ArrayList<Copy>()
        val skipped = ArrayList<ExportManifest.Skipped>()
        for (p in todo) {
            when {
                p.localFile == null -> skipped += ExportManifest.Skipped(p.id, SkipReason.NOT_ON_FRAME)
                fatLimited && !Fat32.fits(p.mediaBytes) -> skipped += ExportManifest.Skipped(p.id, SkipReason.TOO_LARGE_FOR_FAT32)
                else -> copies += Copy(p, ExportNaming.forPlacement(p, zone, taken))
            }
        }
        return Plan(copies, kept, skipped)
    }

    /** Space check: room for everything plus a small margin for the manifest and FAT clusters. */
    fun fits(plan: Plan, freeBytes: Long): Boolean = plan.bytes + marginFor(plan) <= freeBytes

    /** 32 KiB per file (worst-case FAT32 cluster slack) plus 1 MiB for the manifest. */
    fun marginFor(plan: Plan): Long = plan.copies.size * 32L * 1024 + 1024L * 1024
}
