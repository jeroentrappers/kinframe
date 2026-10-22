package app.kinframe.frame.slideshow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.kinframe.core.util.AtomicFile
import app.kinframe.frame.FrameGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/** Sender avatars: cached per user in `avatars/`, fetched once per process when missing, decoded at 96 px. */
object Avatars {
    private val attempted = Collections.synchronizedSet(HashSet<String>())
    private val memory = Collections.synchronizedMap(object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 24
    })

    suspend fun load(g: FrameGraph, senderId: String, placementId: String): Bitmap? = withContext(Dispatchers.IO) {
        memory[senderId]?.let { return@withContext it }
        val f = g.media.avatar(senderId)
        if (!f.exists() && attempted.add(senderId)) {
            runCatching {
                val member = g.db.members().get(senderId)
                val url = member?.avatarUrl?.let { g.api.resolve(it) } ?: g.api.mediaUrl(placementId, "avatar")
                g.api.media(url).use { r -> if (r.isSuccessful) AtomicFile.write(f, r.body.bytes()) }
            }
        }
        if (!f.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 96) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.also { memory[senderId] = it }
    }

    /** Avatar from an explicit URL (join requests: the person isn't a member yet). */
    suspend fun loadUrl(g: FrameGraph, userId: String, url: String?): Bitmap? = withContext(Dispatchers.IO) {
        memory[userId]?.let { return@withContext it }
        val f = g.media.avatar(userId)
        if (!f.exists() && url != null && attempted.add(userId)) {
            runCatching {
                val target = g.api.resolve(url) ?: return@runCatching
                g.api.media(target).use { r -> if (r.isSuccessful) AtomicFile.write(f, r.body.bytes()) }
            }
        }
        if (!f.exists()) return@withContext null
        decode(f)?.also { memory[userId] = it }
    }

    private fun decode(f: java.io.File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 160) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private val palette = intArrayOf(0xFFC0623E.toInt(), 0xFF5F7F68.toInt(), 0xFF7A6A9A.toInt(), 0xFF4F7C93.toInt(), 0xFFB0853A.toInt(), 0xFF9A5B6F.toInt())
    fun color(seed: String) = palette[Math.abs(seed.hashCode() % palette.size)]
    fun initials(name: String) = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
}
