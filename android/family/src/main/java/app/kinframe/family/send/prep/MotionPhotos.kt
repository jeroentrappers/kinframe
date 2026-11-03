package app.kinframe.family.send.prep

import java.io.File
import java.io.RandomAccessFile

/** Random access to a file's bytes (a [File], or an in-memory array in tests). */
interface ByteReader {
    val size: Long

    /** Up to [len] bytes at [offset]; shorter at the end of the file. */
    fun read(offset: Long, len: Int): ByteArray
}

class ArrayReader(private val bytes: ByteArray) : ByteReader {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, len: Int): ByteArray {
        if (offset >= bytes.size) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + len).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}

class FileReader(file: File) : ByteReader, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()
    override fun read(offset: Long, len: Int): ByteArray {
        if (offset >= size) return ByteArray(0)
        val n = minOf(size - offset, len.toLong()).toInt()
        val b = ByteArray(n)
        raf.seek(offset)
        raf.readFully(b)
        return b
    }
    override fun close() = raf.close()
}

/**
 * Android Motion Photos: a JPEG/HEIC still with a short MP4 appended. The XMP says where it starts:
 * `GCamera:MotionPhoto="1"` plus a `Container:Directory` whose `MotionPhoto` item has `Item:Length` (counted from the
 * end of the file), or the older `GCamera:MicroVideo="1"` with `GCamera:MicroVideoOffset`. Offsets are only trusted
 * when an MP4 `ftyp` box really starts there; the end comes from the MP4's own top-level boxes.
 */
object MotionPhotos {
    /** How much of the file head is searched for the XMP packet. */
    const val XMP_SCAN = 256 * 1024

    private val FLAG = Regex("""(?:MotionPhoto|MicroVideo)\s*=\s*"1"|<\w+:(?:MotionPhoto|MicroVideo)>\s*1\s*<""")
    private val MICRO_OFFSET = Regex("""MicroVideoOffset\s*=\s*"(\d+)"|<\w+:MicroVideoOffset>\s*(\d+)\s*<""")
    private val ITEM = Regex("""<\w+:Item\b[^>]*>""")
    private val SEMANTIC = Regex("""\w+:Semantic\s*=\s*"MotionPhoto"""")
    private val LENGTH = Regex("""\w+:Length\s*=\s*"(\d+)"""")
    private val MP4_BRANDS = setOf("isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "qt  ", "M4V ")

    /** True when the XMP marks a Motion Photo (cheap check for the composer badge). */
    fun isFlagged(xmp: String): Boolean = FLAG.containsMatchIn(xmp)

    /** Candidate video start offsets from the XMP, for a file of [size] bytes. */
    fun candidateOffsets(xmp: String, size: Long): List<Long> {
        if (!isFlagged(xmp)) return emptyList()
        val out = mutableListOf<Long>()
        MICRO_OFFSET.find(xmp)?.let { m ->
            val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toLongOrNull()
            if (v != null && v in 1 until size) out += size - v
        }
        for (item in ITEM.findAll(xmp)) {
            if (!SEMANTIC.containsMatchIn(item.value)) continue
            val len = LENGTH.find(item.value)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (len in 1 until size) out += size - len
        }
        return out.distinct()
    }

    /** The XMP packet in the head of the file, if any. */
    fun xmpOf(head: ByteArray): String? {
        val text = String(head, Charsets.ISO_8859_1)
        val start = text.indexOf("<x:xmpmeta").takeIf { it >= 0 } ?: return null
        val end = text.indexOf("</x:xmpmeta>", start).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    /** The byte range of the embedded MP4, or null for ordinary photos. */
    fun find(r: ByteReader): LongRange? {
        val head = r.read(0, XMP_SCAN)
        val xmp = xmpOf(head) ?: return null
        for (start in candidateOffsets(xmp, r.size)) {
            mp4At(r, start)?.let { return it }
        }
        // Offsets slightly off (OEM padding): look for the last MP4 ftyp in the tail.
        if (!isFlagged(xmp)) return null
        return scan(r)
    }

    /** An MP4 that starts at [start] and its extent over the top-level boxes (needs moov and mdat). */
    fun mp4At(r: ByteReader, start: Long): LongRange? {
        if (start <= 0 || start + 16 > r.size) return null
        val h = r.read(start, 16)
        if (h.size < 16) return null
        val ftypSize = u32(h, 0)
        if (String(h, 4, 4, Charsets.ISO_8859_1) != "ftyp" || ftypSize !in 16..256 ||
            String(h, 8, 4, Charsets.ISO_8859_1) !in MP4_BRANDS
        ) return null
        var off = start
        var moov = false
        var mdat = false
        while (off + 8 <= r.size) {
            val b = r.read(off, 16)
            if (b.size < 8) break
            var n = u32(b, 0)
            val type = String(b, 4, 4, Charsets.ISO_8859_1)
            if (n == 1L && b.size >= 16) n = u64(b, 8) else if (n == 0L) n = r.size - off
            if (n < 8 || off + n > r.size || type.any { it.code !in 0x20..0x7e }) break
            if (type == "moov") moov = true
            if (type == "mdat") mdat = true
            off += n
        }
        return if (moov && mdat) start until off else null
    }

    private fun scan(r: ByteReader): LongRange? {
        val window = minOf(r.size, 8L * 1024 * 1024)
        val from = r.size - window
        val tail = r.read(from, window.toInt())
        var i = tail.size
        while (true) {
            val j = lastIndexOf(tail, FTYP, i)
            if (j < 4) return null
            mp4At(r, from + j - 4)?.let { return it }
            i = j
        }
    }

    private val FTYP = "ftyp".toByteArray(Charsets.ISO_8859_1)

    private fun lastIndexOf(hay: ByteArray, needle: ByteArray, before: Int): Int {
        var i = minOf(before, hay.size) - needle.size
        while (i >= 0) {
            var ok = true
            for (k in needle.indices) if (hay[i + k] != needle[k]) { ok = false; break }
            if (ok) return i
            i--
        }
        return -1
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)
}
