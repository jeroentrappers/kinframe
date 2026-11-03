package app.kinframe.family.send.prep

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToInt

/** A pixel size. */
data class PxSize(val width: Int, val height: Int) {
    val long: Int get() = maxOf(width, height)
    val short: Int get() = minOf(width, height)
}

/**
 * The decisions behind client-side preparation (docs/api/README.md §5.3 "Prepared uploads"), kept free of Android
 * types so they can be unit tested. The server verifies whatever we produce and falls back to its own pipeline when
 * something doesn't conform, so every rule here errs on the side of "upload the original".
 */
object PrepPolicy {
    /** photo-master-v1: long edge at most this many pixels. */
    const val PHOTO_MAX_EDGE = 2560
    const val PHOTO_QUALITY = 90

    /** video-master-v1: at most 1080p in either orientation. */
    const val VIDEO_MAX_LONG = 1920
    const val VIDEO_MAX_SHORT = 1080
    const val VIDEO_MAX_FPS = 30f
    const val VIDEO_MAX_BITRATE = 8_000_000

    /** Live/Motion Photo clips longer than this are cut by the server; we cut them first. */
    const val MOTION_MAX_MS = 10_000L

    /** Photo types we can decode and re-encode. GIF stays as is (the server keeps its first frame). */
    fun canPreparePhoto(mime: String, name: String, sdk: Int): Boolean {
        val type = mime.lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            type == "image/gif" || ext == "gif" -> false
            type == "image/heic" || type == "image/heif" || ext == "heic" || ext == "heif" -> sdk >= 28 // ImageDecoder
            type == "image/avif" || ext == "avif" -> sdk >= 31
            type == "image/jpeg" || type == "image/png" || type == "image/webp" -> true
            ext in setOf("jpg", "jpeg", "png", "webp") -> true
            else -> false
        }
    }

    /** Media3 Transformer handles every container/codec the platform can decode; the server catches the rest. */
    fun canPrepareVideo(sdk: Int): Boolean = sdk >= 26

    /** The prepared photo size: long edge ≤ 2560, never upscaled. */
    fun photoTarget(src: PxSize): PxSize {
        val scale = min(1.0, PHOTO_MAX_EDGE.toDouble() / src.long)
        return PxSize((src.width * scale).roundToInt().coerceAtLeast(1), (src.height * scale).roundToInt().coerceAtLeast(1))
    }

    /** Power-of-two subsampling that keeps the decoded bitmap at least [target] (saves memory on 50 MP files). */
    fun inSampleSize(src: PxSize, target: PxSize): Int {
        var n = 1
        while (src.width / (n * 2) >= target.width && src.height / (n * 2) >= target.height) n *= 2
        return n
    }

    /** The prepared video size: within 1920×1080 (either orientation), never upscaled, even dimensions. */
    fun videoTarget(src: PxSize): PxSize {
        val scale = min(1.0, min(VIDEO_MAX_LONG.toDouble() / src.long, VIDEO_MAX_SHORT.toDouble() / src.short))
        fun even(v: Double) = ((v.roundToInt() / 2) * 2).coerceAtLeast(2)
        return PxSize(even(src.width * scale), even(src.height * scale))
    }

    /** ≈ w·h·3.5 bps, capped at 8 Mbps: under the frame rendition budget (w·h·4), so frames get the master as is. */
    fun videoBitrate(size: PxSize): Int = min((size.width.toLong() * size.height * 7 / 2), VIDEO_MAX_BITRATE.toLong()).toInt()

    /** The part of the video to keep, in ms: the trim, cut to [maxMs] for motion clips. */
    fun clip(durationMs: Long?, trimStartMs: Long?, trimEndMs: Long?, maxMs: Long? = null): Pair<Long, Long?> {
        val start = trimStartMs ?: 0L
        var end = trimEndMs ?: durationMs
        if (maxMs != null && (end == null || end - start > maxMs)) end = start + maxMs
        return start to end
    }

    /**
     * Capture time for `captured_at`: EXIF `DateTimeOriginal` ("2023:05:01 12:00:00") with `OffsetTimeOriginal`
     * ("+02:00") when present. Without an offset the wall-clock time is sent as if UTC, which is what the server
     * does with EXIF times too (frames show the time the photo was taken).
     */
    fun exifCaptureTime(dateTime: String?, offset: String?): String? {
        if (dateTime == null || dateTime.length < 19) return null
        val local = runCatching { LocalDateTime.parse(dateTime.substring(0, 19), EXIF_TIME) }.getOrNull() ?: return null
        if (local.year < 1900) return null
        val zone = offset?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneOffset.of(it) }.getOrNull() } ?: ZoneOffset.UTC
        return local.atOffset(zone).withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", "Z")
    }

    /** Capture time from epoch millis (MediaStore DATE_TAKEN, video metadata), or null when unknown. */
    fun epochCaptureTime(ms: Long?): String? {
        if (ms == null || ms <= 0) return null
        return java.time.Instant.ofEpochMilli(ms).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
    }

    /** `MediaMetadataRetriever.METADATA_KEY_DATE` ("20230501T120000.000Z") → epoch ms; null for unset dates (1904/1970). */
    fun videoDate(raw: String?): Long? {
        if (raw == null || raw.length < 15) return null
        val local = runCatching { LocalDateTime.parse(raw.substring(0, 15), VIDEO_TIME) }.getOrNull() ?: return null
        if (local.year < 1971) return null
        return local.toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val VIDEO_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
}

/** What preparing one file produced. */
sealed interface PrepResult {
    /** A prepared master at [path] for the given profile. */
    data class Prepared(
        val path: String,
        val size: Long,
        val profile: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val durationMs: Long? = null,
        /** Trim and mute are baked in: the send must not repeat them. */
        val trimmed: Boolean = false,
    ) : PrepResult

    /** Upload the original (unsupported here, or preparing failed). */
    data class Original(val reason: String) : PrepResult
}
