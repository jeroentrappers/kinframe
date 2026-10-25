package app.kinframe.frame.frameo

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.FocalPoint
import app.kinframe.core.api.ImportResult
import app.kinframe.core.api.Item
import app.kinframe.core.api.Placement
import app.kinframe.frame.Locales
import app.kinframe.frame.slideshow.Credit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Frameo import (docs/spec/frameo-import.md): database mapping, planning, the upload loop and the caption credit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FrameoImportTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A stored object as plain values (dates as epoch ms, links as nested maps). Synthetic data only. */
    private class Fields(private val m: Map<String, Any?>) : FieldSource {
        override fun string(name: String) = m[name] as? String
        override fun epochMs(name: String) = m[name] as? Long
        override fun bool(name: String) = m[name] as? Boolean
        override fun long(name: String) = (m[name] as? Number)?.toLong()
        override fun double(name: String) = (m[name] as? Number)?.toDouble()
        @Suppress("UNCHECKED_CAST")
        override fun obj(name: String) = (m[name] as? Map<String, Any?>)?.let(::Fields)
    }

    private val received = 1_700_000_000_000L

    private fun media(vararg extra: Pair<String, Any?>) = Fields(
        mapOf(
            "filePath" to "/storage/emulated/0/frameo_files/media/$received.webp",
            "receivedTime" to received,
            "captureTime" to received - 86_400_000L,
            "caption" to "Original",
            "editedCaption" to "Edited",
            "isCaptionEdited" to false,
            "isEnabled" to true,
            "friend" to mapOf("name" to " Anna "),
            "picture" to mapOf("scaleType" to 0L, "centerPointX" to 0.25f, "centerPointY" to 0.75f, "rotation" to 0L),
        ) + extra,
    )

    @Test fun entryFromDatabaseObject() {
        val e = FrameoMapping.entryOf(media())!!
        assertEquals("$received.webp", e.fileName)
        assertEquals(received, e.receivedAtMs)
        assertEquals("Original", e.caption)
        assertEquals("Anna", e.senderName)
        assertEquals(0, e.scaleType)
        assertEquals(0.25, e.centerX!!, 1e-6)
        // An edited caption wins; a blank one means none.
        assertEquals("Edited", FrameoMapping.entryOf(media("isCaptionEdited" to true))!!.caption)
        assertNull(FrameoMapping.entryOf(media("caption" to "  "))!!.caption)
        // Missing links and fields are fine; a missing path is not.
        val bare = FrameoMapping.entryOf(Fields(mapOf("filePath" to "/x/y/1.mp4")))!!
        assertEquals(FrameoEntry("1.mp4"), bare)
        assertNull(FrameoMapping.entryOf(Fields(mapOf("caption" to "x"))))
    }

    @Test fun mapsFitFocalRotationAndHidden() {
        val e = FrameoMapping.entryOf(media())!!
        val c = FrameoCandidate(File("$received.webp"), "$received.webp", "image/webp", 10, e)
        val o = FrameoMapping.origin(c)
        assertEquals("frameo", o.source)
        assertEquals("$received.webp", o.key)
        assertEquals("Anna", o.senderName)
        assertEquals("2023-11-14T22:13:20Z", o.receivedAt)
        assertEquals("fill", o.fit)
        assertEquals(FocalPoint(0.25, 0.75), o.focal)
        assertEquals(false, o.hidden)
        assertEquals("2023-11-13T22:13:20Z", FrameoMapping.capturedAt(c))

        // Whole photo: no focal point. The default centre is no focal point either.
        assertEquals("fit", FrameoMapping.origin(c.copy(entry = e.copy(scaleType = 1))).fit)
        assertNull(FrameoMapping.origin(c.copy(entry = e.copy(scaleType = 1))).focal)
        assertNull(FrameoMapping.origin(c.copy(entry = e.copy(centerX = 0.5, centerY = 0.5))).focal)
        assertNull(FrameoMapping.origin(c.copy(entry = e.copy(centerX = 1.5))).focal)
        assertNull(FrameoMapping.origin(c.copy(entry = e.copy(scaleType = 7))).fit)
        // Hidden on the old frame stays hidden; quarter turns carry over, odd angles don't.
        assertTrue(FrameoMapping.origin(c.copy(entry = e.copy(enabled = false))).hidden)
        assertEquals(270, FrameoMapping.origin(c.copy(entry = e.copy(rotation = -90))).rotation)
        assertEquals(0, FrameoMapping.origin(c.copy(entry = e.copy(rotation = 45))).rotation)
        // Videos keep neither fit nor focal point.
        val v = FrameoMapping.origin(c.copy(mime = "video/mp4"))
        assertNull(v.fit); assertNull(v.focal)

        // Without the database: the received time comes from the file name, nothing else is known.
        val bare = FrameoMapping.origin(FrameoCandidate(File("1600000000000.mp4"), "1600000000000.mp4", "video/mp4", 1, null))
        assertEquals("2020-09-13T12:26:40Z", bare.receivedAt)
        assertNull(bare.senderName); assertNull(bare.fit); assertEquals(false, bare.hidden)
        assertNull(FrameoMapping.origin(FrameoCandidate(File("cover.webp"), "cover.webp", "image/webp", 1, null)).receivedAt)
    }

    @Test fun planKeepsMediaOldestFirst() {
        val dir = tmp.newFolder("media")
        fun f(name: String, bytes: Int = 10) = File(dir, name).apply { writeBytes(ByteArray(bytes)) }
        val files = listOf(f("1700000000300.webp"), f("1700000000100.mp4"), f("notes.txt"), f(".hidden.webp"), f("1700000000200.webp", 0))
        File(dir, "sub").mkdirs()
        val entries = mapOf("1700000000300.webp" to FrameoEntry("1700000000300.webp", receivedAtMs = 1_600_000_000_000L))
        val plan = FrameoMapping.plan(files + File(dir, "sub"), entries)
        assertEquals(listOf("1700000000300.webp", "1700000000100.mp4"), plan.map { it.key })
        assertEquals("video", plan[1].kind)
        assertEquals(entries["1700000000300.webp"], plan[0].entry)
    }

    private fun result(dup: Boolean) = ImportResult(Placement("p", item = Item("i")), dup)

    private fun candidates(n: Int) = (1..n).map { i ->
        FrameoCandidate(File("$i.webp"), "$i.webp", "image/webp", 100, null)
    }

    @Test fun importerSkipsKnownKeysAndCountsOutcomes() = runBlocking {
        val uploaded = mutableListOf<String>()
        val importer = FrameoImporter(
            knownKeys = { keys -> assertEquals(4, keys.size); setOf("1.webp") },
            upload = { c, onBytes ->
                onBytes(50)
                uploaded += c.key
                when (c.key) {
                    "2.webp" -> result(dup = true)
                    "3.webp" -> throw ApiException(415, null)
                    else -> result(dup = false)
                }
            },
        )
        var last: app.kinframe.frame.removable.Progress? = null
        val out = importer.run(candidates(4), { last = it })
        assertEquals(FrameoImporter.Outcome(imported = 1, alreadyThere = 2, failed = 1), out)
        assertEquals(listOf("2.webp", "3.webp", "4.webp"), uploaded)
        assertEquals(4, last!!.done)
        assertEquals(1f, last!!.fraction)
    }

    @Test fun importerStopsOnNetworkTroubleSoTheCallerCanResume() = runBlocking {
        val importer = FrameoImporter(
            knownKeys = { emptySet() },
            upload = { c, _ -> if (c.key == "2.webp") throw IOException("connection reset") else result(false) },
        )
        try {
            importer.run(candidates(3), {}); fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(FrameoImporter.retryable(e))
        }
        assertTrue(FrameoImporter.retryable(ApiException(503, null)))
        assertTrue(!FrameoImporter.retryable(ApiException(413, null)))
    }

    private fun ctx(lang: String): Context {
        val base: Context = RuntimeEnvironment.getApplication()
        val locale = Locales.toLocale(lang)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) })
    }

    private fun plain(s: String?) = s?.filterNot { it in "‎‏‪‫‬‭‮⁦⁧⁨⁩" }

    @Test fun creditNamesTheOriginalSender() {
        assertEquals("Anna · via Frameo", plain(Credit.imported(ctx("en"), "frameo", "Anna")))
        assertEquals("Anna · über Frameo", plain(Credit.imported(ctx("de"), "frameo", "Anna")))
        assertEquals("via Frameo", Credit.imported(ctx("en"), "frameo", " "))
        assertNull(Credit.imported(ctx("en"), "other", "Anna"))
    }
}
