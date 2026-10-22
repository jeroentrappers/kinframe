package app.kinframe.frame.slideshow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a cached rendition into one screen-sized bitmap: the photo cropped around its focal point (fill) or
 * letterboxed over a blurred copy of itself (fit). Pre-composing on a worker thread means the cross-fade only
 * draws two textured quads per frame, which the Mali-400 handles easily.
 *
 * Memory: the source is decoded with a power-of-two sample size that never goes below screen size, then
 * recycled immediately; only the screen-sized result (4 MB at 1280 × 800) is kept.
 */
class SlideRenderer {
    data class Request(
        val file: File,
        val isVideo: Boolean,
        val screenW: Int,
        val screenH: Int,
        val fill: Boolean,
        val focalX: Double?,
        val focalY: Double?,
        val rotation: Int,
        val blurBars: Boolean,
        /** Caption, sender and date, drawn into the bitmap so the cross-fade stays two textured quads. */
        val caption: CaptionSpec? = null,
        /** px per dp. */
        val density: Float = 1f,
    )

    data class CaptionSpec(
        val text: String?,
        val meta: String,
        val avatar: Bitmap?,
        val initials: String?,
        val avatarColor: Int,
        val textScale: Float = 1f,
        /** Space kept free on the right for the clock widget, in dp. */
        val rightInsetDp: Float = 340f,
    )

    class UndecodableException(msg: String) : Exception(msg)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    companion object {
        /** Warm near-black between collage tiles (the theme's `night`). */
        val TILE_BACKGROUND: Int = Color.rgb(28, 23, 20)
    }

    /** Caption strip for video slides, drawn above the playing video (bottom [stripHeight] px of the screen). */
    fun renderCaptionStrip(r: Request): Bitmap? {
        val c = r.caption ?: return null
        val stripH = stripHeight(r)
        val out = Bitmap.createBitmap(r.screenW, stripH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate(0f, -(r.screenH - stripH).toFloat())
        CaptionPainter.draw(canvas, r.screenW, r.screenH, c, r.density)
        return out
    }

    fun stripHeight(r: Request) = minOf(r.screenH, (300 * r.density).roundToInt())

    fun render(r: Request): Bitmap {
        val source = if (r.isVideo) videoFrame(r) else decode(r)
        try {
            return compose(source, r)
        } finally {
            source.recycle()
        }
    }

    private fun decode(r: Request): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(r.file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UndecodableException("cannot read ${r.file.name}")
        val rotated = r.rotation % 180 != 0
        val srcW = if (rotated) bounds.outHeight else bounds.outWidth
        val srcH = if (rotated) bounds.outWidth else bounds.outHeight
        val (needW, needH) = needed(srcW, srcH, r)
        val sample = if (rotated) FocalCrop.sampleSize(bounds.outWidth, bounds.outHeight, needH, needW)
        else FocalCrop.sampleSize(bounds.outWidth, bounds.outHeight, needW, needH)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(r.file.absolutePath, opts) ?: throw UndecodableException("decode failed ${r.file.name}")
        return if (r.rotation % 360 != 0) rotate(bmp, r.rotation) else bmp
    }

    private fun videoFrame(r: Request): Bitmap {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(r.file.absolutePath)
            val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw UndecodableException("no video frame ${r.file.name}")
            return if (r.rotation % 360 != 0) rotate(frame, r.rotation) else frame
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** Size the decoded source must at least have for the chosen mode. */
    private fun needed(srcW: Int, srcH: Int, r: Request): Pair<Int, Int> {
        val fill = useFill(srcW, srcH, r)
        val scale = if (fill) max(r.screenW.toFloat() / srcW, r.screenH.toFloat() / srcH)
        else minOf(r.screenW.toFloat() / srcW, r.screenH.toFloat() / srcH)
        return (srcW * scale).roundToInt().coerceAtLeast(1) to (srcH * scale).roundToInt().coerceAtLeast(1)
    }

    private fun useFill(srcW: Int, srcH: Int, r: Request): Boolean =
        r.fill && !FocalCrop.cropTooAggressive(srcW, srcH, r.screenW, r.screenH)

    private fun compose(src: Bitmap, r: Request): Bitmap {
        val out = Bitmap.createBitmap(r.screenW, r.screenH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val dst = Rect(0, 0, r.screenW, r.screenH)
        if (useFill(src.width, src.height, r)) {
            val c = FocalCrop.fillSource(src.width, src.height, r.screenW, r.screenH, r.focalX ?: 0.5, r.focalY ?: 0.5)
            canvas.drawBitmap(src, Rect(c.left.roundToInt(), c.top.roundToInt(), c.right.roundToInt(), c.bottom.roundToInt()), dst, paint)
        } else {
            if (r.blurBars) drawBlurredBackground(canvas, src, r)
            val f = FocalCrop.fitDest(src.width, src.height, r.screenW, r.screenH)
            canvas.drawBitmap(src, null, RectF(f.left, f.top, f.right, f.bottom), paint)
        }
        r.caption?.let { CaptionPainter.draw(canvas, r.screenW, r.screenH, it, r.density) }
        return out
    }

    /** One photo of a collage or a smart pair. [label] is a short caption ("Anna · Yesterday") or null. */
    data class TileRequest(
        val file: File,
        val rotation: Int,
        val focalX: Double?,
        val focalY: Double?,
        val cell: Collage.Cell,
        val label: String? = null,
        val caption: String? = null,
    )

    /**
     * Composes a collage into one screen-sized bitmap (so the transition stays two textured quads): each photo
     * fills its cell around its focal point, with soft rounded corners and an even gap on a warm dark background.
     * Photos are decoded one at a time at the cell's size and recycled right away.
     */
    fun renderTiles(tiles: List<TileRequest>, screenW: Int, screenH: Int, density: Float): Bitmap {
        val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(TILE_BACKGROUND)
        val gap = 8f * density
        val radius = 14f * density
        for (t in tiles) {
            val dst = RectF(
                t.cell.left * screenW + gap / 2 + if (t.cell.left == 0f) gap / 2 else 0f,
                t.cell.top * screenH + gap / 2 + if (t.cell.top == 0f) gap / 2 else 0f,
                t.cell.right * screenW - gap / 2 - if (t.cell.right >= 1f) gap / 2 else 0f,
                t.cell.bottom * screenH - gap / 2 - if (t.cell.bottom >= 1f) gap / 2 else 0f,
            )
            val dw = dst.width().roundToInt().coerceAtLeast(1)
            val dh = dst.height().roundToInt().coerceAtLeast(1)
            val src = runCatching { decodeFill(t.file, t.rotation, dw, dh) }.getOrNull()
            canvas.save()
            val clip = android.graphics.Path().apply { addRoundRect(dst, radius, radius, android.graphics.Path.Direction.CW) }
            canvas.clipPath(clip)
            if (src != null) {
                val c = FocalCrop.fillSource(src.width, src.height, dw, dh, t.focalX ?: 0.5, t.focalY ?: 0.5)
                canvas.drawBitmap(src, Rect(c.left.roundToInt(), c.top.roundToInt(), c.right.roundToInt(), c.bottom.roundToInt()), dst, paint)
                src.recycle()
            } else {
                canvas.drawColor(Color.rgb(46, 38, 33))
            }
            if (t.label != null || t.caption != null) drawTileLabel(canvas, dst, t, density)
            canvas.restore()
        }
        return out
    }

    /** Caption (one line) and sender line at the bottom of a tile, over a soft scrim. */
    private fun drawTileLabel(canvas: Canvas, dst: RectF, t: TileRequest, density: Float) {
        val pad = 16f * density
        val width = (dst.width() - 2 * pad).toInt().coerceAtLeast(1)
        val metaPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255); textSize = 16f * density
            setShadowLayer(6f, 0f, 1f, Color.argb(153, 0, 0, 0))
        }
        val capPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 20f * density
            setShadowLayer(6f, 0f, 1f, Color.argb(153, 0, 0, 0))
        }
        val cap = t.caption?.let { android.text.TextUtils.ellipsize(it.replace('\n', ' '), capPaint, width.toFloat(), android.text.TextUtils.TruncateAt.END).toString() }
        val meta = t.label?.let { android.text.TextUtils.ellipsize(it, metaPaint, width.toFloat(), android.text.TextUtils.TruncateAt.END).toString() }
        val lineCap = capPaint.fontSpacing
        val lineMeta = metaPaint.fontSpacing
        val textH = (if (cap != null) lineCap else 0f) + (if (meta != null) lineMeta else 0f)
        val scrimTop = dst.bottom - textH - pad * 2.5f
        canvas.drawRect(dst.left, scrimTop, dst.right, dst.bottom, Paint().apply {
            shader = android.graphics.LinearGradient(0f, scrimTop, 0f, dst.bottom, Color.TRANSPARENT, Color.argb(150, 0, 0, 0), android.graphics.Shader.TileMode.CLAMP)
        })
        var y = dst.bottom - pad - textH
        if (cap != null) { canvas.drawText(cap, dst.left + pad, y - capPaint.ascent(), capPaint); y += lineCap }
        if (meta != null) canvas.drawText(meta, dst.left + pad, y - metaPaint.ascent(), metaPaint)
    }

    /** Decodes for fill mode at [w] × [h]: the smallest power-of-two sample that still covers the cell. */
    private fun decodeFill(file: File, rotation: Int, w: Int, h: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UndecodableException("cannot read ${file.name}")
        val rotated = rotation % 180 != 0
        val srcW = if (rotated) bounds.outHeight else bounds.outWidth
        val srcH = if (rotated) bounds.outWidth else bounds.outHeight
        val scale = max(w.toFloat() / srcW, h.toFloat() / srcH)
        val needW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val needH = (srcH * scale).roundToInt().coerceAtLeast(1)
        val sample = if (rotated) FocalCrop.sampleSize(bounds.outWidth, bounds.outHeight, needH, needW)
        else FocalCrop.sampleSize(bounds.outWidth, bounds.outHeight, needW, needH)
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw UndecodableException("decode failed ${file.name}")
        return if (rotation % 360 != 0) rotate(bmp, rotation) else bmp
    }

    /** A heavily blurred, slightly darkened copy of the photo behind the letterbox bars. */
    private fun drawBlurredBackground(canvas: Canvas, src: Bitmap, r: Request) {
        val c = FocalCrop.fillSource(src.width, src.height, r.screenW, r.screenH)
        val smallW = 64
        val smallH = (smallW * r.screenH.toFloat() / r.screenW).roundToInt().coerceAtLeast(8)
        val small = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(
            src, Rect(c.left.roundToInt(), c.top.roundToInt(), c.right.roundToInt(), c.bottom.roundToInt()),
            Rect(0, 0, smallW, smallH), paint,
        )
        StackBlur.blur(small, 4)
        StackBlur.blur(small, 4)
        canvas.drawBitmap(small, null, Rect(0, 0, r.screenW, r.screenH), paint)
        small.recycle()
        canvas.drawColor(Color.argb(70, 0, 0, 0))
    }

    private fun rotate(b: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r != b) b.recycle()
        return r
    }
}

/** Draws the caption block (bottom-left) the same way the Compose UI would, with a soft scrim for legibility. */
object CaptionPainter {
    fun draw(canvas: Canvas, w: Int, h: Int, c: SlideRenderer.CaptionSpec, density: Float) {
        val dp = density
        val sp = density * c.textScale
        val hasText = !c.text.isNullOrBlank()
        val scrimH = (if (hasText) 260f else 180f) * dp
        val scrim = Paint().apply {
            shader = android.graphics.LinearGradient(0f, h - scrimH, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(140, 0, 0, 0), android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, h - scrimH, w.toFloat(), h.toFloat(), scrim)

        val left = 36f * dp
        val bottom = h - 32f * dp
        val avatarSize = 52f * dp
        val textLeft = if (c.initials != null) left + avatarSize + 16f * dp else left
        val maxTextW = (w - textLeft - c.rightInsetDp * dp).toInt().coerceAtLeast((160 * dp).toInt())

        val metaPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255); textSize = 19f * sp
            setShadowLayer(8f, 0f, 2f, Color.argb(153, 0, 0, 0))
        }
        val metaLayout = layout(c.meta, metaPaint, maxTextW, 1)
        var y = bottom - metaLayout.height
        val captionLayout = if (hasText) {
            val p = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 24f * sp
                setShadowLayer(8f, 0f, 2f, Color.argb(153, 0, 0, 0))
            }
            layout(c.text!!, p, maxTextW, 3)
        } else null
        if (captionLayout != null) y -= captionLayout.height
        // Without caption text, centre the single meta line on the avatar.
        if (captionLayout == null && c.initials != null) y = bottom - avatarSize / 2 - metaLayout.height / 2f
        canvas.save(); canvas.translate(textLeft, y)
        captionLayout?.let { it.draw(canvas); canvas.translate(0f, it.height.toFloat()) }
        metaLayout.draw(canvas)
        canvas.restore()

        if (c.initials != null) {
            val cx = left + avatarSize / 2
            val cy = bottom - avatarSize / 2
            val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (c.avatar != null) {
                val shader = android.graphics.BitmapShader(c.avatar, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                val scale = avatarSize / minOf(c.avatar.width, c.avatar.height)
                shader.setLocalMatrix(Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(cx - c.avatar.width * scale / 2, cy - c.avatar.height * scale / 2)
                })
                fill.shader = shader
            } else fill.color = c.avatarColor
            canvas.drawCircle(cx, cy, avatarSize / 2, fill)
            canvas.drawCircle(cx, cy, avatarSize / 2 - dp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = Color.argb(217, 255, 255, 255)
            })
            if (c.avatar == null) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = 18f * sp; textAlign = Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }
                canvas.drawText(c.initials, cx, cy - (tp.descent() + tp.ascent()) / 2, tp)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun layout(text: CharSequence, paint: android.text.TextPaint, width: Int, maxLines: Int): android.text.StaticLayout {
        val full = android.text.StaticLayout(text, paint, width, android.text.Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
        if (full.lineCount <= maxLines) return full
        val end = full.getLineEnd(maxLines - 1)
        val cut = text.subSequence(0, (end - 1).coerceAtLeast(0)).toString().trimEnd() + "…"
        return android.text.StaticLayout(cut, paint, width, android.text.Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
    }
}

/** Small in-place box/stack blur for tiny bitmaps (the 64 px background), no RenderScript needed. */
object StackBlur {
    fun blur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        pass(px, tmp, w, h, radius, horizontal = true)
        pass(tmp, px, w, h, radius, horizontal = false)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun idx(i: Int): Int { val c = i.coerceIn(0, len - 1); return if (horizontal) line * w + c else c * w + line }
            for (i in -r..r) { val p = src[idx(i)]; sr += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sb += p and 0xff }
            for (i in 0 until len) {
                dst[idx(i)] = (0xff shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[idx(i - r)]
                val pIn = src[idx(i + r + 1)]
                sr += ((pIn shr 16) and 0xff) - ((pOut shr 16) and 0xff)
                sg += ((pIn shr 8) and 0xff) - ((pOut shr 8) and 0xff)
                sb += (pIn and 0xff) - (pOut and 0xff)
            }
        }
    }
}
