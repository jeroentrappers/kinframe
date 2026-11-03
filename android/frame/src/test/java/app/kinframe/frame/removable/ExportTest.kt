package app.kinframe.frame.removable

import app.kinframe.core.api.ImportResult
import app.kinframe.core.api.Item
import app.kinframe.core.api.Placement
import app.kinframe.frame.data.PlacementEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

private val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

internal fun placement(
    id: String,
    sender: String? = "Oma",
    caption: String? = null,
    captured: Long? = null,
    received: Long = ms("2026-10-20T10:00:00Z"),
    file: String? = "$id.webp",
    sha: String? = "sha-$id",
    bytes: Long = 1000,
    mime: String? = "image/webp",
    kind: String = "photo",
    hidden: Boolean = false,
    trashed: Boolean = false,
) = PlacementEntity(
    id = id, itemId = "i-$id", kind = kind, status = "delivered", senderId = "u1", senderName = sender, caption = caption,
    captionEdited = false, capturedAt = captured, receivedAt = received, focalX = null, focalY = null, fit = null, rotation = 0,
    hidden = hidden, trashed = trashed, pendingModeration = false, reaction = null, width = 1280, height = 800,
    durationMs = null, hasAudio = null, mediaUrl = "u", mediaSha256 = sha, mediaBytes = bytes, mediaMime = mime, localFile = file,
)

class ExportNamingTest {
    @Test fun dateSenderCaptionExtension() {
        val taken = HashSet<String>()
        val p = placement("a", sender = "Oma Lies", caption = "Zondag aan zee", captured = ms("2026-07-05T21:30:00Z"))
        // 21:30 UTC is already the 5th → 23:30 in Amsterdam, still the 5th; the capture date wins over arrival.
        assertEquals("2026-07-05_Oma-Lies_Zondag-aan-zee.webp", ExportNaming.forPlacement(p, ZONE, taken))
    }

    @Test fun usesTheFrameTimeZoneAndArrivalWhenNotTaken() {
        val p = placement("a", caption = null, captured = null, received = ms("2026-10-20T22:30:00Z"))
        assertEquals("2026-10-21_Oma.webp", ExportNaming.forPlacement(p, ZONE, HashSet()))
    }

    @Test fun dropsFatInvalidCharactersAndKeepsUnicode() {
        assertEquals("Wat-een-dag!-Ça-va-🎉", ExportNaming.part("Wat een dag?! \"Ça va\" 🎉 <>|*", 48))
        assertEquals("abc", ExportNaming.part("a/b\\c", 48))
        assertEquals("multi-line", ExportNaming.part("multi\n  line\t", 48))
        assertEquals("", ExportNaming.part("???", 48))
        assertEquals("", ExportNaming.part(null, 48))
        // FAT ignores trailing dots: they are trimmed so two names can't silently collide.
        assertEquals("Tot-zo", ExportNaming.part("Tot zo...", 48))
        // Underscores separate the parts, so they don't appear inside one.
        assertEquals("snake-case", ExportNaming.part("snake_case", 48))
    }

    @Test fun capsLengthOnACodePointBoundary() {
        val long = "😀".repeat(100)
        val part = ExportNaming.part(long, 48)
        assertEquals(48, part.codePointCount(0, part.length))
        val name = ExportNaming.name("2026-01-01", "x".repeat(200), "y".repeat(500), "webp", HashSet())
        assertTrue("name too long: ${name.length}", name.length <= 100)
    }

    @Test fun collisionsAreCaseInsensitiveAndNumbered() {
        val taken = HashSet<String>()
        assertEquals("2026-01-01_Oma_Hi.jpg", ExportNaming.name("2026-01-01", "Oma", "Hi", "jpg", taken))
        assertEquals("2026-01-01_oma_hi_2.jpg", ExportNaming.name("2026-01-01", "oma", "hi", "jpg", taken))
        assertEquals("2026-01-01_Oma_Hi_3.jpg", ExportNaming.name("2026-01-01", "Oma", "Hi", "jpg", taken))
        assertEquals("2026-01-01_Oma_Hi.mp4", ExportNaming.name("2026-01-01", "Oma", "Hi", "mp4", taken))
    }

    @Test fun extensionFromMimeOrKind() {
        assertEquals("webp", ExportNaming.extension("image/webp", "photo"))
        assertEquals("mp4", ExportNaming.extension("video/mp4", "video"))
        assertEquals("mp4", ExportNaming.extension(null, "video"))
        assertEquals("jpg", ExportNaming.extension(null, "photo"))
    }
}

class Fat32Test {
    @Test fun fourGigabyteLimit() {
        assertTrue(Fat32.fits(Fat32.MAX_FILE_BYTES))
        assertFalse(Fat32.fits(4L * 1024 * 1024 * 1024))
        assertTrue(Fat32.fits(0))
        assertTrue(Fat32.limited("vfat"))
        assertTrue(Fat32.limited(null))
        assertFalse(Fat32.limited("exfat"))
        assertFalse(Fat32.limited("ntfs"))
    }
}

class ExportManifestTest {
    @Test fun roundTripsWithSnakeCaseFields() {
        val m = ExportManifest(
            exportedAt = "2026-10-22T10:00:00Z", frameName = "Oma's frame", appVersion = "0.2.0",
            items = listOf(ExportManifest.entry(placement("a", caption = "Hallo", captured = ms("2026-07-05T10:00:00Z")), "2026-07-05_Oma_Hallo.webp")),
            skipped = listOf(ExportManifest.Skipped("b", SkipReason.NOT_ON_FRAME)),
        )
        val json = m.encode()
        assertTrue(json, json.contains("\"format\": \"kinframe-export\""))
        assertTrue(json, json.contains("\"placement_id\": \"a\""))
        assertTrue(json, json.contains("\"captured_at\": \"2026-07-05T10:00:00Z\""))
        assertEquals(m, ExportManifest.parse(json))
    }

    @Test fun rejectsOtherFilesAndNewerFormats() {
        assertNull(ExportManifest.parse("""{"hello":"world"}"""))
        assertNull(ExportManifest.parse("""{"format":"kinframe-export","version":2,"exported_at":"x"}"""))
        assertNull(ExportManifest.parse("not json"))
        assertNotNull(ExportManifest.parse("""{"format":"kinframe-export","version":1,"exported_at":"x","future":true}"""))
    }
}

class ExportPlannerTest {
    @Test fun visibleItemsOldestFirstWithHiddenTrashedLeftOut() {
        val ps = listOf(
            placement("new", received = ms("2026-10-21T10:00:00Z")),
            placement("old", received = ms("2026-10-01T10:00:00Z")),
            placement("hidden", hidden = true), placement("trashed", trashed = true),
        )
        val plan = ExportPlanner.plan(ps, null, ZONE, fatLimited = true) { false }
        assertEquals(listOf("old", "new"), plan.copies.map { it.placement.id })
        assertEquals(2000L, plan.bytes)
    }

    @Test fun incrementalKeepsWhatIsThereAndCopiesTheRest() {
        val a = placement("a", caption = "edited later")
        val b = placement("b")
        val changed = placement("c", sha = "sha-new")
        val prev = ExportManifest(
            exportedAt = "x",
            items = listOf(
                ExportManifest.entry(placement("a"), "a.webp"),
                ExportManifest.entry(placement("c", sha = "sha-old"), "c.webp"),
                ExportManifest.entry(placement("gone"), "gone.webp"),
                ExportManifest.entry(placement("lost"), "lost.webp"),
            ),
        )
        val onStick = setOf("a.webp", "c.webp", "gone.webp")
        val plan = ExportPlanner.plan(listOf(a, b, changed), prev, ZONE, fatLimited = true) { it in onStick }
        assertEquals(listOf("b", "c"), plan.copies.map { it.placement.id }.sorted())
        // Kept: a (metadata refreshed), and "gone" (a backup never loses what it held). "lost" has no file anymore.
        assertEquals(setOf("a", "gone"), plan.kept.map { it.placementId }.toSet())
        assertEquals("edited later", plan.kept.first { it.placementId == "a" }.caption)
        // New names never clash with files that stay.
        assertTrue(plan.copies.none { it.file in onStick })
    }

    @Test fun skipsWhatIsNotOnTheFrameAndTooBigForFat32() {
        val big = placement("big", bytes = 5L * 1024 * 1024 * 1024, kind = "video", mime = "video/mp4")
        val evicted = placement("evicted", file = null)
        val plan = ExportPlanner.plan(listOf(big, evicted, placement("ok")), null, ZONE, fatLimited = true) { false }
        assertEquals(listOf("ok"), plan.copies.map { it.placement.id })
        assertEquals(
            mapOf("big" to SkipReason.TOO_LARGE_FOR_FAT32, "evicted" to SkipReason.NOT_ON_FRAME),
            plan.skipped.associate { it.placementId to it.reason },
        )
        // exFAT has no 4 GB limit.
        assertEquals(2, ExportPlanner.plan(listOf(big, placement("ok")), null, ZONE, fatLimited = false) { false }.copies.size)
    }

    @Test fun spaceCheckIncludesAMargin() {
        val plan = ExportPlanner.plan(listOf(placement("a", bytes = 10_000_000)), null, ZONE, true) { false }
        assertFalse(ExportPlanner.fits(plan, 10_000_000))
        assertTrue(ExportPlanner.fits(plan, 12_000_000))
    }
}

class ImportDedupeTest {
    private fun cand(name: String, bytes: Long = 10) = ImportCandidate(File(name), name, "photo", "image/jpeg", bytes, 0)

    @Test fun skipsKnownDuplicatesAndOversized() {
        val items = listOf(
            cand("a.jpg") to "AAA", cand("b.jpg") to "bbb", cand("c.jpg") to "ccc", cand("a-copy.jpg") to "aaa",
            cand("huge.mp4", ImportScanner.MAX_IMPORT_BYTES + 1) to "ddd", cand("new.jpg") to "eee",
        )
        val v = ImportDedupe.classify(items, localHashes = setOf("bbb"), serverKnown = setOf("ccc"))
        assertEquals(
            listOf(
                ImportDedupe.Verdict.Upload, ImportDedupe.Verdict.AlreadyOnFrame, ImportDedupe.Verdict.AlreadyOnFrame,
                ImportDedupe.Verdict.DuplicateInSelection, ImportDedupe.Verdict.TooLarge, ImportDedupe.Verdict.Upload,
            ),
            v,
        )
    }
}

class ImportScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun findsMediaAndSkipsSystemHiddenAndKinframeFolders() {
        val root = tmp.newFolder("stick")
        fun f(path: String, size: Int = 3) = File(root, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size)) }
        f("DCIM/Camera/IMG_1.JPG"); f("DCIM/Camera/clip.mp4"); f("photo.heic"); f("notes.txt")
        f(".hidden/x.jpg"); f("Android/data/x/y.jpg"); f("LOST.DIR/1.jpg"); f("kinframe-export/2026_a.webp"); f("kinframe-backup/b.webp")
        val found = ImportScanner.scan(root)
        assertEquals(listOf("DCIM/Camera/clip.mp4", "DCIM/Camera/IMG_1.JPG", "photo.heic"), found.map { it.relativePath })
        assertEquals("video", found[0].kind)
        assertEquals("image/jpeg", found[1].mime)
        assertEquals(2, ImportScanner.scan(root, limit = 2).size)
    }

    @Test fun sortsByDateNewestFirstOrByName() {
        val root = tmp.newFolder("s")
        val a = File(root, "b.jpg").apply { writeText("1"); setLastModified(1_000_000) }
        val b = File(root, "a.jpg").apply { writeText("2"); setLastModified(2_000_000) }
        val c = File(root, "c.jpg").apply { writeText("3"); setLastModified(1_500_000) }
        val found = ImportScanner.scan(root)
        assertEquals(listOf(b, c, a), ImportScanner.sort(found, ImportScanner.Sort.Date).map { it.file })
        assertEquals(listOf(b, a, c), ImportScanner.sort(found, ImportScanner.Sort.Name).map { it.file })
    }

    @Test fun restoreReadsTheManifestMetadata() {
        val dir = tmp.newFolder("kinframe-export")
        File(dir, "x.webp").writeText("x")
        val m = ExportManifest(
            exportedAt = "x",
            items = listOf(
                ExportManifest.entry(placement("p1", caption = "Hoi", captured = ms("2026-01-02T03:04:05Z")), "x.webp"),
                ExportManifest.entry(placement("p2"), "missing.webp"),
            ),
        )
        val items = ImportScanner.fromExport(dir, m)
        assertEquals(1, items.size)
        assertEquals("Hoi", items[0].caption)
        assertEquals("2026-01-02T03:04:05Z", items[0].capturedAt)
        assertEquals("sha-p1", items[0].sha256)
    }
}

class ExportEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun setup(): Triple<File, File, ExportEngine> {
        val media = tmp.newFolder("media")
        listOf("a", "b", "c").forEach { File(media, "$it.webp").writeText("data-$it") }
        val stick = tmp.newFolder("stick")
        return Triple(media, stick, ExportEngine({ File(media, it) }, ZONE, { ms("2026-10-22T10:00:00Z") }, "Oma's frame", "0.2.0"))
    }

    @Test fun copiesFilesWritesManifestAndIsIncremental() = runBlocking {
        val (_, stick, engine) = setup()
        val writer = DirectWriter(File(stick, Volume.EXPORT_DIR))
        assertTrue(writer.prepare())
        val ps = listOf(placement("a", caption = "Eén"), placement("b", received = ms("2026-10-21T10:00:00Z")))
        val progress = ArrayList<Progress>()
        val out = engine.run(engine.plan(ps, writer, true), writer, Long.MAX_VALUE, { progress += it }, { false }) as ExportEngine.Outcome.Done
        assertEquals(2, out.copied)
        val dir = File(stick, Volume.EXPORT_DIR)
        assertEquals("data-a", File(dir, "2026-10-20_Oma_Eén.webp").readText())
        assertTrue(File(dir, "2026-10-21_Oma.webp").isFile)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") || it.name.startsWith(".kinframe-probe") })
        val manifest = ExportManifest.parse(File(dir, ExportManifest.FILE_NAME).readText())!!
        assertEquals("Oma's frame", manifest.frameName)
        assertEquals(listOf("a", "b"), manifest.items.map { it.placementId })
        assertEquals(1f, progress.last().fraction)

        // Second run: one new item, nothing else copied again.
        val again = engine.run(engine.plan(ps + placement("c"), writer, true), writer, Long.MAX_VALUE, {}, { false }) as ExportEngine.Outcome.Done
        assertEquals(1, again.copied)
        assertEquals(2, again.kept)
        assertEquals(3, ExportManifest.parse(File(dir, ExportManifest.FILE_NAME).readText())!!.items.size)
    }

    @Test fun refusesWhenTheStickIsTooSmall() = runBlocking {
        val (_, stick, engine) = setup()
        val writer = DirectWriter(File(stick, "x")).also { it.prepare() }
        val out = engine.run(engine.plan(listOf(placement("big", bytes = 50_000_000)), writer, true), writer, 10_000_000, {}, { false })
        assertTrue(out is ExportEngine.Outcome.NoSpace)
    }

    @Test fun cancelKeepsWhatWasCopiedWithAManifest() = runBlocking {
        val (_, stick, engine) = setup()
        val writer = DirectWriter(File(stick, "x")).also { it.prepare() }
        var copied = 0
        try {
            engine.run(engine.plan(listOf(placement("a"), placement("b"), placement("c")), writer, true), writer, Long.MAX_VALUE,
                { if (it.done > copied) copied = it.done }, { copied >= 1 })
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        val m = ExportManifest.parse(File(stick, "x/${ExportManifest.FILE_NAME}").readText())!!
        assertEquals(1, m.items.size)
    }

    @Test fun nothingToExportOnAnEmptyFrame() = runBlocking {
        val (_, stick, engine) = setup()
        val writer = DirectWriter(File(stick, "x")).also { it.prepare() }
        assertEquals(ExportEngine.Outcome.NothingToExport, engine.run(engine.plan(emptyList(), writer, true), writer, Long.MAX_VALUE, {}, { false }))
    }
}

class ImportEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun hashesSkipsDuplicatesAndUploadsTheRest() = runBlocking {
        val root = tmp.newFolder("stick")
        val a = File(root, "a.jpg").apply { writeText("same") }
        val b = File(root, "b.jpg").apply { writeText("same") }
        val c = File(root, "c.jpg").apply { writeText("known on server") }
        val d = File(root, "d.jpg").apply { writeText("new") }
        val e = File(root, "e.jpg").apply { writeText("server says duplicate on upload") }
        val items = ImportScanner.scan(root)
        val cSha = Sha256.of(c)
        val uploaded = ArrayList<String>()
        val engine = ImportEngine(
            localHashes = { emptySet() },
            serverKnown = { hashes -> hashes.filterTo(HashSet()) { it == cSha } },
            upload = { cand, onBytes ->
                onBytes(cand.bytes); uploaded += cand.name
                ImportResult(Placement(id = "p-${cand.name}", item = Item(id = "i")), duplicate = cand.file == e)
            },
        )
        val progress = ArrayList<Progress>()
        val out = engine.run(items, { progress += it }, { false })
        assertEquals(listOf("a.jpg", "d.jpg", "e.jpg"), uploaded)
        assertEquals(ImportEngine.Outcome(imported = 2, alreadyThere = 3, tooLarge = 0, failed = 0), out)
        assertTrue(progress.zipWithNext().all { (x, y) -> y.bytesDone >= x.bytesDone })
        assertEquals(progress.last().bytesTotal, progress.last().bytesDone)
        assertTrue(a.exists() && b.exists())
    }

    @Test fun keepsGoingWhenOneUploadFails() = runBlocking {
        val root = tmp.newFolder("s")
        File(root, "a.jpg").writeText("1"); File(root, "b.jpg").writeText("2")
        val engine = ImportEngine({ emptySet() }, { emptySet() }) { cand, _ ->
            if (cand.name == "a.jpg") throw java.io.IOException("boom")
            ImportResult(Placement(id = "p", item = Item(id = "i")), false)
        }
        assertEquals(ImportEngine.Outcome(1, 0, 0, 1), engine.run(ImportScanner.scan(root), {}, { false }))
    }
}

class VolumeTest {
    @Test fun kindFromDiskDescriptionOrPath() {
        assertEquals(Volume.Kind.Usb, Volume.kindOf(null, "/storage/1A2B", diskIsUsb = true))
        assertEquals(Volume.Kind.SdCard, Volume.kindOf("USB drive", "/storage/1A2B", diskIsUsb = false))
        assertEquals(Volume.Kind.Usb, Volume.kindOf("SanDisk USB drive", "/storage/1A2B", null))
        assertEquals(Volume.Kind.Usb, Volume.kindOf(null, "/mnt/usb_storage", null))
        assertEquals(Volume.Kind.SdCard, Volume.kindOf("SD card", "/storage/sdcard1", null))
    }

    @Test fun rootOfTheAppFolder() {
        assertEquals(File("/storage/1A2B-3C4D"), Volume.rootOfAppDir(File("/storage/1A2B-3C4D/Android/data/app.kinframe.frame/files")))
        assertNull(Volume.rootOfAppDir(File("/data/user/0/app.kinframe.frame/files")))
    }

    @Test fun rootShellQuoting() {
        assertEquals("'/mnt/media_rw/X/kinframe-export/2026_Oma'\\''s_dag.jpg'", RootWriter.q("/mnt/media_rw/X/kinframe-export/2026_Oma's_dag.jpg"))
    }
}
