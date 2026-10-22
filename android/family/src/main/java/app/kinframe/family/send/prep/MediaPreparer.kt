package app.kinframe.family.send.prep

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.PreparedProfile
import app.kinframe.family.send.OutboxItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client-side preparation (docs/api/README.md §5.3 "Prepared uploads"), run by the background send worker before
 * uploading: photos become a ≤ 2560 px WebP q90 without metadata, videos an H.264/AAC ≤ 1080p MP4 with trim and mute
 * applied (Media3 Transformer), and Motion Photos are split into still + clip. Anything that fails falls back to
 * uploading the original; the server verifies prepared files anyway.
 */
class MediaPreparer(private val context: Context) {
    private val sdk get() = Build.VERSION.SDK_INT
    private val cr get() = context.contentResolver

    /**
     * Prepares [item] (and the clip of a Motion Photo it carries) into [dir]. Returns the item with its preparation
     * recorded; never throws except for cancellation.
     */
    suspend fun prepare(item: OutboxItem, dir: File, onProgress: (Float) -> Unit): OutboxItem = withContext(Dispatchers.IO) {
        dir.mkdirs()
        var out = item.copy(capturedAt = item.capturedAt ?: captureTime(item))
        if (item.kind == MediaKind.PHOTO && item.motionPhoto && item.live && item.motion == null) {
            out = out.copy(motion = runCatching { splitMotion(item, dir) }.onFailure { warn("motion split", it) }.getOrNull())
        }
        val result = try {
            when (item.kind) {
                MediaKind.VIDEO -> prepareVideo(item, dir, null) { onProgress(it * if (out.motion != null) 0.5f else 1f) }
                else -> preparePhoto(item, dir)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            warn("prepare ${item.kind}", e)
            PrepResult.Original(e.javaClass.simpleName)
        }
        out = out.withPrep(result)
        val motion = out.motion
        if (motion != null && motion.prepState == null) {
            val mr = try {
                prepareVideo(motion, dir, PrepPolicy.MOTION_MAX_MS) { onProgress(0.5f + it / 2) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                warn("prepare motion", e)
                PrepResult.Original(e.javaClass.simpleName)
            }
            out = out.copy(motion = motion.withPrep(mr))
        }
        onProgress(1f)
        out
    }

    // ------------------------------------------------------------------ photos

    private fun open(item: OutboxItem): InputStream =
        item.localPath?.let { File(it).inputStream() } ?: cr.openInputStream(Uri.parse(item.uri)) ?: error("cannot open ${item.uri}")

    private fun preparePhoto(item: OutboxItem, dir: File): PrepResult {
        if (!PrepPolicy.canPreparePhoto(item.mime, item.name, sdk)) return PrepResult.Original("type ${item.mime}")
        val bitmap = decode(item) ?: return PrepResult.Original("decode")
        try {
            val target = PrepPolicy.photoTarget(PxSize(bitmap.width, bitmap.height))
            val scaled = if (target.width != bitmap.width || target.height != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, target.width, target.height, true)
            } else bitmap
            val file = File(dir, "prep-${item.id}.webp")
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            // Bitmap.compress writes no EXIF/XMP: the master carries pixels only.
            FileOutputStream(file).use { if (!scaled.compress(format, PrepPolicy.PHOTO_QUALITY, it)) return PrepResult.Original("encode") }
            if (scaled !== bitmap) scaled.recycle()
            return PrepResult.Prepared(file.path, file.length(), PreparedProfile.PHOTO, "image/webp", target.width, target.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes with EXIF orientation applied, subsampled close to the target size. */
    private fun decode(item: OutboxItem): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(item).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { decodeWithImageDecoder(item) }.onFailure { warn("ImageDecoder", it) }.getOrNull()?.let { return it }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val src = PxSize(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply { inSampleSize = PrepPolicy.inSampleSize(src, PrepPolicy.photoTarget(src)) }
        val raw = open(item).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = runCatching { open(item).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return raw
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }

    @androidx.annotation.RequiresApi(28)
    private fun decodeWithImageDecoder(item: OutboxItem): Bitmap {
        val source = item.localPath?.let { ImageDecoder.createSource(File(it)) } ?: ImageDecoder.createSource(cr, Uri.parse(item.uri))
        // ImageDecoder applies EXIF orientation itself and decodes HEIC/AVIF. Subsample by a power of two from the
        // header size (orientation-independent), then scale exactly afterwards.
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val src = PxSize(info.size.width, info.size.height)
            decoder.setTargetSampleSize(PrepPolicy.inSampleSize(src, PrepPolicy.photoTarget(src)))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    }

    // ------------------------------------------------------------------ videos

    @OptIn(UnstableApi::class)
    private suspend fun prepareVideo(item: OutboxItem, dir: File, maxMs: Long?, onProgress: (Float) -> Unit): PrepResult {
        if (!PrepPolicy.canPrepareVideo(sdk)) return PrepResult.Original("sdk")
        val uri = item.localPath?.let { Uri.fromFile(File(it)) } ?: Uri.parse(item.uri)
        val probe = probeVideo(uri)
        val (start, end) = PrepPolicy.clip(probe.durationMs, item.trimStartMs, item.trimEndMs, maxMs)
        val file = File(dir, "prep-${item.id}.mp4")
        val result = transcode(uri, file, probe.size, start, end, mute = !item.includeAudio, onProgress)
        val w = result.width.takeIf { it > 0 } ?: probe.size?.let { PrepPolicy.videoTarget(it).width } ?: return PrepResult.Original("size")
        val h = result.height.takeIf { it > 0 } ?: probe.size?.let { PrepPolicy.videoTarget(it).height } ?: return PrepResult.Original("size")
        val duration = result.approximateDurationMs.takeIf { it > 0 } ?: probeVideo(Uri.fromFile(file)).durationMs
        return PrepResult.Prepared(file.path, file.length(), PreparedProfile.VIDEO, "video/mp4", w, h, duration, trimmed = true)
    }

    private data class Probe(val size: PxSize?, val durationMs: Long?, val dateMs: Long?)

    private fun probeVideo(uri: Uri): Probe = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val size = if (vw != null && vh != null) (if (rot % 180 != 0) PxSize(vh, vw) else PxSize(vw, vh)) else null
            Probe(size, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                PrepPolicy.videoDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)))
        } finally {
            r.release()
        }
    }.getOrDefault(Probe(null, null, null))

    @OptIn(UnstableApi::class)
    private suspend fun transcode(
        input: Uri, out: File, src: PxSize?, startMs: Long, endMs: Long?, mute: Boolean, onProgress: (Float) -> Unit,
    ): ExportResult = withContext(Dispatchers.Main) {
        coroutineScope {
            val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs)
                .apply { if (endMs != null) setEndPositionMs(endMs) }.build()
            val mediaItem = MediaItem.Builder().setUri(input).setClippingConfiguration(clipping).build()
            val target = src?.let { PrepPolicy.videoTarget(it) }
            val effects = buildList {
                if (target != null) add(Presentation.createForWidthAndHeight(target.width, target.height, Presentation.LAYOUT_SCALE_TO_FIT))
                add(FrameDropEffect.createDefaultFrameDropEffect(PrepPolicy.VIDEO_MAX_FPS))
            }
            val edited = EditedMediaItem.Builder(mediaItem).setRemoveAudio(mute).setEffects(Effects(emptyList(), effects)).build()
            val settings = VideoEncoderSettings.Builder()
                .setBitrate(PrepPolicy.videoBitrate(target ?: PxSize(PrepPolicy.VIDEO_MAX_LONG, PrepPolicy.VIDEO_MAX_SHORT)))
                .apply {
                    // High@4.1 (1080p30): what the server accepts as a master and frames decode in hardware.
                    setEncodingProfileLevel(
                        android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                        android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel41,
                    )
                }.build()
            val encoders = DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(settings).setEnableFallback(true).build()
            out.delete()
            var transformer: Transformer? = null
            val poll = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    delay(300)
                    val t = transformer ?: continue
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val t = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        // Portrait stays portrait (no rotation flag, which the server's fast path rejects).
                        .setPortraitEncodingEnabled(true)
                        .setEncoderFactory(encoders)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(exportResult)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()
                    transformer = t
                    cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { t.cancel() } }
                    t.start(edited, out.path)
                }
            } finally {
                poll.cancel()
            }
        }
    }

    // ------------------------------------------------------------------ capture time, Motion Photos

    /** Read before preparing: prepared files carry no metadata, so the time travels as `captured_at`. */
    private fun captureTime(item: OutboxItem): String? = runCatching {
        if (item.kind == MediaKind.VIDEO) {
            val uri = item.localPath?.let { Uri.fromFile(File(it)) } ?: Uri.parse(item.uri)
            PrepPolicy.epochCaptureTime(probeVideo(uri).dateMs)
        } else {
            open(item).use { s ->
                val exif = ExifInterface(s)
                PrepPolicy.exifCaptureTime(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
                    exif.getAttribute("OffsetTimeOriginal") ?: exif.getAttribute("OffsetTime"),
                )
            }
        }
    }.getOrNull()

    /** Extracts the MP4 of a Motion Photo into [dir] as the item's motion clip (muted unless audio was kept). */
    private fun splitMotion(item: OutboxItem, dir: File): OutboxItem? {
        val original = item.localPath?.let { File(it) } ?: File(dir, "orig-${item.id}").also { f ->
            if (!f.exists()) open(item).use { i -> f.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        }
        val range = FileReader(original).use { MotionPhotos.find(it) } ?: return null
        val clip = File(dir, "motion-${item.id}.mp4")
        java.io.RandomAccessFile(original, "r").use { raf ->
            raf.seek(range.first)
            clip.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                var left = range.last - range.first + 1
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    o.write(buf, 0, n)
                    left -= n
                }
            }
        }
        if (item.localPath == null) original.delete()
        return OutboxItem(
            id = "${item.id}-motion", uri = Uri.fromFile(clip).toString(), localPath = clip.path, mime = "video/mp4",
            name = item.name.substringBeforeLast('.') + ".mp4", size = clip.length(), kind = MediaKind.VIDEO, includeAudio = false,
        )
    }

    private fun warn(what: String, e: Throwable) = Log.w(TAG, "$what failed, uploading the original: ${e.message}")

    companion object {
        private const val TAG = "MediaPreparer"

        /** Cheap check for the composer badge: the head of the file has Motion Photo XMP. */
        fun isMotionPhoto(head: ByteArray): Boolean = MotionPhotos.xmpOf(head)?.let { MotionPhotos.isFlagged(it) } ?: false
    }
}
