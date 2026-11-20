package app.kinframe.frame.remotehelp

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Tailscale's static Linux release (`tailscale_<version>_<arch>.tgz`): download, SHA-256 check and unpacking of the
 * two binaries the frame needs. The tar reader understands what that tarball uses (ustar with prefix, GNU long
 * names) and nothing more.
 */
object TailscaleArchive {
    val BINARIES = setOf("tailscaled", "tailscale")

    /** The tarball is ≈30 MB; anything far bigger is not what we asked for. */
    const val MAX_DOWNLOAD_BYTES = 120L * 1024 * 1024
    /** Each unpacked binary is ≈35 MB. */
    private const val MAX_ENTRY_BYTES = 150L * 1024 * 1024

    fun sha256(f: File): String = f.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val d = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            d.update(buf, 0, n)
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads [url] to [dest] (replacing it). Blocking: call on an IO thread. */
    fun download(http: OkHttpClient, url: String, dest: File, maxBytes: Long = MAX_DOWNLOAD_BYTES) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("download failed: HTTP ${r.code}")
            part.outputStream().use { out ->
                val input = r.body.byteStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("interrupted")
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("download too large")
                    out.write(buf, 0, n)
                }
            }
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("rename failed")
    }

    /**
     * Unpacks the entries of the gzip'd tar [tgz] whose file name (last path segment) is in [names] into [outDir],
     * flat. Returns name → file; throws when one is missing.
     */
    fun extract(tgz: File, outDir: File, names: Set<String> = BINARIES): Map<String, File> {
        outDir.mkdirs()
        val found = HashMap<String, File>()
        GZIPInputStream(tgz.inputStream().buffered(), 64 * 1024).use { gz ->
            val header = ByteArray(BLOCK)
            var longName: String? = null
            while (true) {
                if (!readFully(gz, header)) break
                if (header.all { it == 0.toByte() }) break // end of archive
                val type = header[156].toInt().toChar()
                val size = octal(header, 124, 12)
                if (size < 0 || size > MAX_ENTRY_BYTES) throw IOException("bad tar entry size")
                val name = longName ?: run {
                    val prefix = cString(header, 345, 155)
                    val n = cString(header, 0, 100)
                    if (prefix.isNotEmpty() && String(header, 257, 5, Charsets.US_ASCII) == "ustar") "$prefix/$n" else n
                }
                longName = null
                when (type) {
                    'L' -> { // GNU long name: the data is the next entry's name
                        val data = ByteArray(size.toInt())
                        if (!readFully(gz, data)) throw EOFException("truncated tar")
                        skip(gz, pad(size))
                        longName = String(data, Charsets.UTF_8).trimEnd('\u0000')
                    }
                    '0', '\u0000', '7' -> {
                        val base = name.trimEnd('/').substringAfterLast('/')
                        if (base in names && base !in found) {
                            val f = File(outDir, base)
                            f.outputStream().use { out -> copy(gz, out, size) }
                            skip(gz, pad(size))
                            f.setExecutable(true, false)
                            found[base] = f
                        } else {
                            skip(gz, size + pad(size))
                        }
                    }
                    else -> skip(gz, size + pad(size))
                }
            }
        }
        val missing = names - found.keys
        if (missing.isNotEmpty()) throw IOException("missing in archive: ${missing.sorted().joinToString()}")
        return found
    }

    private const val BLOCK = 512

    private fun pad(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    /** Tar numbers: octal text, or base-256 when the high bit of the first byte is set (GNU, large files). */
    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        val s = cString(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLongOrNull(8) ?: -1
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("truncated tar")
            }
            off += n
        }
        return true
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated tar")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated tar")
            left -= n
        }
    }
}
