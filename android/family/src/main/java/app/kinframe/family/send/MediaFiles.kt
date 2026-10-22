package app.kinframe.family.send

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.UploadSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** What we know about a picked or shared file before sending it. */
data class MediaInfo(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: String,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

object MediaFiles {
    private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif")
    private val VIDEO_EXT = setOf("mp4", "mov", "m4v", "3gp", "mkv", "webm")

    /** Classify by MIME type, falling back to the extension (HEIC often has a generic type). */
    fun kindOf(mime: String?, name: String?): String? {
        if (mime != null && mime.startsWith("image/")) return MediaKind.PHOTO
        if (mime != null && mime.startsWith("video/")) return MediaKind.VIDEO
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            in PHOTO_EXT -> MediaKind.PHOTO
            in VIDEO_EXT -> MediaKind.VIDEO
            else -> null
        }
    }

    /** Reads name, size, type and (for videos) duration and size; null when it isn't a photo or video. */
    fun inspect(context: Context, uri: Uri): MediaInfo? {
        val cr = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        var mime = cr.getType(uri)
        if (mime == null || mime == "application/octet-stream") {
            val ext = name?.substringAfterLast('.', "")?.lowercase()
            mime = ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: mime
        }
        val kind = kindOf(mime, name) ?: return null
        if (size < 0) size = runCatching { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1
        var duration: Long? = null
        var w: Int? = null
        var h: Int? = null
        if (kind == MediaKind.VIDEO) {
            runCatching {
                MediaMetadataRetriever().apply {
                    setDataSource(context, uri)
                    duration = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    val rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val vw = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val vh = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    if (rot == 90 || rot == 270) { w = vh; h = vw } else { w = vw; h = vh }
                    release()
                }
            }
        }
        if (kind == MediaKind.PHOTO) {
            runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                val rotated = cr.openInputStream(uri)?.use {
                    when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        5, 6, 7, 8 -> true
                        else -> false
                    }
                } ?: false
                if (o.outWidth > 0 && o.outHeight > 0) {
                    if (rotated) { w = o.outHeight; h = o.outWidth } else { w = o.outWidth; h = o.outHeight }
                }
            }
        }
        val fallbackMime = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg"
        return MediaInfo(uri, name ?: "file", mime ?: fallbackMime, size.coerceAtLeast(0), kind, duration, w, h)
    }

    /**
     * Keeps access to [uri] after the screen closes: a persistable grant when the provider offers one (the photo
     * picker does), otherwise null and the caller copies the file.
     */
    fun persist(cr: ContentResolver, uri: Uri): Boolean = try {
        cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }

    fun release(cr: ContentResolver, uri: Uri) {
        runCatching { cr.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Copies [uri] into [target] (app-private), returning the byte count. */
    fun copy(cr: ContentResolver, uri: Uri, target: File): Long {
        target.parentFile?.mkdirs()
        val input = cr.openInputStream(uri) ?: throw IOException("cannot open $uri")
        return input.use { i -> target.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
    }

    fun sha256(input: InputStream): String = input.use { i ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Upload source over a private copy or a content URI. */
class ItemSource(private val context: Context, private val item: OutboxItem) : UploadSource {
    override val length: Long get() = item.size

    override fun open(offset: Long): InputStream {
        val s: InputStream = item.localPath?.let { FileInputStream(File(it)) }
            ?: context.contentResolver.openInputStream(Uri.parse(item.uri))
            ?: throw IOException("cannot open ${item.uri}")
        var left = offset
        while (left > 0) {
            val skipped = s.skip(left)
            if (skipped <= 0) {
                if (s.read() < 0) throw IOException("source shorter than offset $offset"); left--
            } else left -= skipped
        }
        return s
    }
}
