package app.kinframe.family.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Profile photos: a centred square, at most [SIZE] px, as JPEG, made on the phone like the web app does. */
object Avatars {
    const val SIZE = 512

    fun squareJpeg(context: Context, uri: Uri): ByteArray {
        val src: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                val n = maxOf(1, minOf(info.size.width, info.size.height) / SIZE)
                d.setTargetSampleSize(Integer.highestOneBit(n))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: throw IOException("cannot read $uri")
        }
        val (x, y, side) = crop(src.width, src.height)
        val square = Bitmap.createBitmap(src, x, y, side, side)
        val out = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
        return ByteArrayOutputStream().use { o -> out.compress(Bitmap.CompressFormat.JPEG, 90, o); o.toByteArray() }
    }

    /** The centred square of a w × h image: left, top, side. */
    fun crop(w: Int, h: Int): Triple<Int, Int, Int> {
        val side = minOf(w, h)
        return Triple((w - side) / 2, (h - side) / 2, side)
    }
}
