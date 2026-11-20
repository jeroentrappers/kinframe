package app.kinframe.frame.remotehelp

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class TailscaleArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val daemon = ByteArray(70_000) { (it * 7).toByte() }
    private val cli = "#!/bin/sh\necho cli\n".toByteArray()

    /** A ustar archive written by hand: the prefix field, a GNU long name, a directory and an unrelated file. */
    private fun handMadeTgz(): File {
        val tar = ByteArrayOutputStream()
        fun header(name: String, size: Int, type: Char, prefix: String = "") {
            val h = ByteArray(512)
            fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
            put(0, name); put(100, "0000755\u0000"); put(108, "0000000\u0000"); put(116, "0000000\u0000")
            put(124, "%011o".format(size) + "\u0000"); put(136, "%011o".format(0) + "\u0000")
            put(148, "        "); h[156] = type.code.toByte(); put(257, "ustar\u0000"); put(263, "00"); put(345, prefix)
            put(148, "%06o".format(h.sumOf { it.toInt() and 0xff }) + "\u0000 ")
            tar.write(h)
        }
        fun data(b: ByteArray) { tar.write(b); tar.write(ByteArray((512 - b.size % 512) % 512)) }
        header("tailscale_1.104.7_arm/", 0, '5')
        header("README", 6, '0', prefix = "tailscale_1.104.7_arm"); data("hello\n".toByteArray())
        header("tailscaled", daemon.size, '0', prefix = "tailscale_1.104.7_arm"); data(daemon)
        val long = "tailscale_1.104.7_arm/" + "x".repeat(120) + "/tailscale"
        header("././@LongLink", long.length + 1, 'L'); data((long + "\u0000").toByteArray())
        header(long.take(100), cli.size, '0'); data(cli)
        tar.write(ByteArray(1024))
        return tmp.newFile("hand.tgz").apply { GZIPOutputStream(outputStream()).use { it.write(tar.toByteArray()) } }
    }

    @Test fun extractsBothBinariesFromAHandMadeArchive() {
        val out = tmp.newFolder("out")
        val files = TailscaleArchive.extract(handMadeTgz(), out)
        assertEquals(setOf("tailscaled", "tailscale"), files.keys)
        assertArrayEquals(daemon, File(out, "tailscaled").readBytes())
        assertArrayEquals(cli, File(out, "tailscale").readBytes())
        assertFalse(File(out, "README").exists())
        assertTrue(File(out, "tailscaled").canExecute())
    }

    @Test fun extractsFromAnArchiveMadeByTar() {
        val tarBin = listOf("/usr/bin/tar", "/bin/tar").firstOrNull { File(it).canExecute() }
        assumeTrue(tarBin != null)
        val src = tmp.newFolder("src", "tailscale_1.104.7_arm64", "systemd")
        val top = src.parentFile!!
        File(top, "tailscaled").writeBytes(daemon)
        File(top, "tailscale").writeBytes(cli)
        File(src, "tailscaled.service").writeText("[Unit]\n")
        val tgz = File(tmp.root, "real.tgz")
        val p = ProcessBuilder(tarBin, "-czf", tgz.path, "-C", tmp.root.resolve("src").path, "tailscale_1.104.7_arm64").redirectErrorStream(true).start()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0)
        val out = tmp.newFolder("out2")
        TailscaleArchive.extract(tgz, out)
        assertArrayEquals(daemon, File(out, "tailscaled").readBytes())
        assertArrayEquals(cli, File(out, "tailscale").readBytes())
    }

    @Test fun aMissingBinaryIsAnError() {
        val tgz = handMadeTgz()
        try {
            TailscaleArchive.extract(tgz, tmp.newFolder("o"), setOf("tailscaled", "derper"))
            fail("expected an error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("derper"))
        }
    }

    @Test fun aTruncatedArchiveIsAnError() {
        val whole = handMadeTgz().readBytes()
        val raw = java.util.zip.GZIPInputStream(whole.inputStream()).readBytes()
        val cut = tmp.newFile("cut.tgz").apply { GZIPOutputStream(outputStream()).use { it.write(raw.copyOf(20_000)) } }
        try {
            TailscaleArchive.extract(cut, tmp.newFolder("c"))
            fail("expected an error")
        } catch (e: IOException) {
            // EOFException or missing binaries, both are IOExceptions
        }
    }

    @Test fun sha256MatchesTheKnownValue() {
        val f = tmp.newFile("abc").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", TailscaleArchive.sha256(f))
    }

    @Test fun downloadsAndRefusesOversizedOrFailedDownloads() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(Buffer().write(daemon)).build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(daemon)).build())
            server.enqueue(MockResponse.Builder().code(404).build())
            val dest = File(tmp.root, "dl/t.tgz")
            TailscaleArchive.download(OkHttpClient(), server.url("/t.tgz").toString(), dest)
            assertArrayEquals(daemon, dest.readBytes())
            try {
                TailscaleArchive.download(OkHttpClient(), server.url("/t.tgz").toString(), File(tmp.root, "dl/big.tgz"), maxBytes = 1000)
                fail("expected too large")
            } catch (e: IOException) {
                assertFalse(File(tmp.root, "dl/big.tgz").exists())
            }
            try {
                TailscaleArchive.download(OkHttpClient(), server.url("/t.tgz").toString(), File(tmp.root, "dl/gone.tgz"))
                fail("expected 404")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("404"))
            }
        }
    }
}
