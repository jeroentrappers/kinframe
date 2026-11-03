package app.kinframe.frame.removable

import android.util.Log
import app.kinframe.core.api.ImportResult
import app.kinframe.frame.data.PlacementEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** How far an export or import got. */
data class Progress(
    val done: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val current: String? = null,
) {
    val fraction: Float get() = when {
        bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
        total > 0 -> (done.toFloat() / total).coerceIn(0f, 1f)
        else -> 0f
    }
}

/**
 * Copies the frame's visible photos and videos (the renditions it has, which is the best quality on the frame) into
 * a folder on a stick with a `kinframe-export.json` manifest. Incremental: files listed in the folder's manifest with
 * the same hash stay. The manifest is written after every few files, so a pulled stick still has a usable export.
 */
class ExportEngine(
    private val mediaFile: (String) -> File,
    private val zone: ZoneId,
    private val now: () -> Long,
    private val frameName: String?,
    private val appVersion: String?,
) {
    sealed interface Outcome {
        data class Done(val copied: Int, val kept: Int, val skipped: List<ExportManifest.Skipped>, val dir: String, val method: VolumeWriter.Method) : Outcome
        data class NoSpace(val needBytes: Long, val freeBytes: Long) : Outcome
        data object NothingToExport : Outcome
        data object Failed : Outcome
    }

    fun previousManifest(dir: File): ExportManifest? =
        runCatching { File(dir, ExportManifest.FILE_NAME).takeIf { it.isFile }?.readText() }.getOrNull()?.let(ExportManifest::parse)

    fun plan(placements: List<PlacementEntity>, writer: VolumeWriter, fatLimited: Boolean): ExportPlanner.Plan {
        val dir = writer.readDir
        return ExportPlanner.plan(placements, previousManifest(dir), zone, fatLimited) { File(dir, it).isFile }
    }

    suspend fun run(
        plan: ExportPlanner.Plan,
        writer: VolumeWriter,
        freeBytes: Long,
        onProgress: (Progress) -> Unit,
        cancelled: () -> Boolean,
    ): Outcome {
        if (plan.total == 0 && plan.skipped.isEmpty()) return Outcome.NothingToExport
        // Real file sizes (the feed's byte counts can be missing for older items).
        val sizes = plan.copies.map { c -> c.placement.localFile?.let(mediaFile)?.length()?.takeIf { it > 0 } ?: c.placement.mediaBytes }
        val bytesTotal = sizes.sum()
        val need = bytesTotal + ExportPlanner.marginFor(plan)
        if (freeBytes >= 0 && need > freeBytes) return Outcome.NoSpace(need, freeBytes)
        val written = ArrayList(plan.kept)
        val skipped = ArrayList(plan.skipped)
        var bytesBefore = 0L
        val total = plan.copies.size
        var copied = 0
        onProgress(Progress(0, total, 0, bytesTotal))
        suspend fun saveManifest() = writer.writeText(
            ExportManifest.FILE_NAME,
            ExportManifest(
                exportedAt = Instant.ofEpochMilli(now()).toString(), frameName = frameName, appVersion = appVersion,
                items = written.sortedBy { it.receivedAt ?: "" }, skipped = skipped,
            ).encode(),
        )
        try {
            for ((i, c) in plan.copies.withIndex()) {
                if (cancelled()) throw CancellationException("export cancelled")
                val src = c.placement.localFile?.let(mediaFile)
                if (src == null || !src.isFile) {
                    skipped += ExportManifest.Skipped(c.placement.id, SkipReason.NOT_ON_FRAME)
                    bytesBefore += sizes[i]
                    continue
                }
                val ok = writer.copy(src, c.file, { b -> onProgress(Progress(i, total, bytesBefore + b, bytesTotal, c.file)) }, cancelled)
                bytesBefore += src.length()
                if (ok) {
                    written += ExportManifest.entry(c.placement, c.file); copied++
                } else {
                    skipped += ExportManifest.Skipped(c.placement.id, SkipReason.WRITE_FAILED)
                    // A stick that stops taking writes (pulled, full, read-only) fails every file: stop early.
                    if (skipped.count { it.reason == SkipReason.WRITE_FAILED } >= 3 && copied == 0) return Outcome.Failed
                }
                onProgress(Progress(i + 1, total, bytesBefore, bytesTotal, c.file))
                if ((i + 1) % 25 == 0) saveManifest()
            }
        } catch (e: CancellationException) {
            // Keep what was copied: the next export (or backup) continues from there.
            runCatching { saveManifest(); writer.sync() }
            throw e
        }
        if (!saveManifest()) return Outcome.Failed
        writer.sync()
        return Outcome.Done(copied, plan.kept.size, skipped, writer.shownDir, writer.method)
    }
}

/**
 * Uploads picked files through `POST /device/imports` so the whole family sees them. Hashes every file first, skips
 * what the frame already has (local rendition hashes and the server's answer) and duplicates within the pick.
 */
class ImportEngine(
    private val localHashes: suspend () -> Set<String>,
    private val serverKnown: suspend (Collection<String>) -> Set<String>,
    private val upload: suspend (ImportCandidate, (Long) -> Unit) -> ImportResult,
) {
    data class Outcome(val imported: Int, val alreadyThere: Int, val tooLarge: Int, val failed: Int, val notReadable: Int = 0)

    suspend fun run(items: List<ImportCandidate>, onProgress: (Progress) -> Unit, cancelled: () -> Boolean): Outcome {
        val hashBytes = items.sumOf { it.bytes }
        var hashed = 0L
        val withSha = ArrayList<Pair<ImportCandidate, String>>()
        var unreadable = 0
        for ((i, c) in items.withIndex()) {
            if (cancelled()) throw CancellationException("import cancelled")
            val sha = c.sha256 ?: withContext(Dispatchers.IO) {
                runCatching { Sha256.of(c.file.inputStream(), { b -> onProgress(Progress(i, items.size, (hashed + b) / 4, hashBytes, c.name)) }, cancelled) }
                    .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "can't read ${c.relativePath}", it) }.getOrNull()
            }
            hashed += c.bytes
            if (sha == null) unreadable++ else withSha += c to sha
        }
        // Hashing counts as the first quarter of the work; uploading as the rest.
        val known = runCatching { serverKnown(withSha.map { it.second }) }.getOrElse { if (it is CancellationException) throw it; emptySet() }
        val verdicts = ImportDedupe.classify(withSha, localHashes(), known)
        val toUpload = withSha.zip(verdicts).filter { it.second == ImportDedupe.Verdict.Upload }.map { it.first.first }
        val uploadBytes = toUpload.sumOf { it.bytes }.coerceAtLeast(1)
        var sent = 0L
        var imported = 0
        var duplicates = verdicts.count { it == ImportDedupe.Verdict.AlreadyOnFrame || it == ImportDedupe.Verdict.DuplicateInSelection }
        var failed = 0
        fun report(done: Int, partial: Long, name: String?) =
            onProgress(Progress(done, toUpload.size, hashBytes / 4 + ((sent + partial).toDouble() / uploadBytes * (hashBytes - hashBytes / 4)).toLong(), hashBytes, name))
        for ((i, c) in toUpload.withIndex()) {
            if (cancelled()) throw CancellationException("import cancelled")
            report(i, 0, c.name)
            try {
                val r = upload(c) { b -> report(i, b, c.name) }
                if (r.duplicate) duplicates++ else imported++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "import ${c.relativePath} failed", e)
                failed++
            }
            sent += c.bytes
            report(i + 1, 0, c.name)
        }
        return Outcome(imported, duplicates, verdicts.count { it == ImportDedupe.Verdict.TooLarge }, failed, unreadable)
    }

    private companion object { const val TAG = "KinImport" }
}
