package app.kinframe.frame.removable

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.kinframe.frame.BuildConfig
import app.kinframe.frame.FrameController
import app.kinframe.frame.sync.FrameSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the storage panel shows. */
sealed interface StorageTask {
    data object Idle : StorageTask
    data class Exporting(val volume: Volume, val backup: Boolean, val progress: Progress) : StorageTask
    data class Exported(val volume: Volume, val backup: Boolean, val result: ExportEngine.Outcome.Done) : StorageTask
    data object Scanning : StorageTask
    data class Picking(val volume: Volume, val candidates: List<ImportCandidate>, val restore: Boolean) : StorageTask
    data class Importing(val volume: Volume, val restore: Boolean, val progress: Progress) : StorageTask
    data class Imported(val volume: Volume, val restore: Boolean, val result: ImportEngine.Outcome) : StorageTask
    data class Problem(val volume: Volume?, val kind: Kind, val needBytes: Long = 0, val freeBytes: Long = 0) : StorageTask {
        enum class Kind { NoWriteAccess, NoSpace, WriteFailed, NothingToExport, NoPermission, Offline, NothingFound, ReadFailed }
    }
}

/** A kinframe export or backup on a stick, offered to a frame whose library is still empty (restore). */
data class RestoreOffer(val volume: Volume, val dir: File, val count: Int, val frameName: String?, val exportedAt: String?)

/**
 * SD card and USB stick features (product spec 4.10, frameo-behaviour-spec B.10/B.11): export, import, automatic
 * incremental backup, and restore onto a freshly set-up frame. Export and backup work offline; import and restore
 * upload to the server so everyone sees the photos.
 */
class RemovableStorage(private val c: FrameController) {
    private val g = c.g
    private val context: Context get() = g.app
    private val sp = g.app.getSharedPreferences("removable", Context.MODE_PRIVATE)
    val monitor = VolumeMonitor(g.app)
    val volumes: StateFlow<List<Volume>> get() = monitor.volumes

    private val _task = MutableStateFlow<StorageTask>(StorageTask.Idle)
    val task: StateFlow<StorageTask> = _task
    val panelOpen = MutableStateFlow(false)
    private val _restoreOffer = MutableStateFlow<RestoreOffer?>(null)
    val restoreOffer: StateFlow<RestoreOffer?> = _restoreOffer

    /** The activity shows the runtime permission prompt / the folder picker when these are set. */
    val permissionRequest = MutableStateFlow(false)
    val accessRequest = MutableStateFlow<Volume?>(null)

    private val _autoBackup = MutableStateFlow(sp.getBoolean("auto_backup", false))
    val autoBackup: StateFlow<Boolean> = _autoBackup
    private var job: Job? = null
    @Volatile private var cancelRequested = false

    val busy: Boolean get() = _task.value is StorageTask.Exporting || _task.value is StorageTask.Importing || _task.value is StorageTask.Scanning

    fun start() {
        monitor.start()
        c.scope.launch {
            // The offer is for an empty frame: drop it once photos arrive (e.g. the frame replaced an old one).
            g.db.placements().observeCount().collect { if (it > 0) _restoreOffer.value = null }
        }
        c.scope.launch {
            monitor.volumes.collectLatest { vols ->
                if (vols.isEmpty()) {
                    _restoreOffer.value = null
                    // A stick pulled mid-way: the running job fails on its own; a finished screen has nothing to show.
                    if (!busy) { _task.value = StorageTask.Idle; panelOpen.value = false }
                    return@collectLatest
                }
                delay(3_000) // let vold and the media scanner settle
                refreshRestoreOffer()
                while (true) {
                    maybeAutoBackup()
                    delay(AUTO_BACKUP_EVERY_MS)
                }
            }
        }
    }

    fun setAutoBackup(on: Boolean) {
        _autoBackup.value = on
        sp.edit { putBoolean("auto_backup", on) }
        if (on) c.scope.launch { maybeAutoBackup() }
    }

    fun lastBackupAt(v: Volume): Long? = sp.getLong("last_backup_${v.uuid ?: v.path.name}", 0L).takeIf { it > 0 }

    fun open() { panelOpen.value = true }

    fun close() {
        panelOpen.value = false
        if (!busy) _task.value = StorageTask.Idle
    }

    /**
     * Export stops after the current file and keeps what it copied (with a manifest); an upload in flight is
     * cancelled right away (the HTTP call is aborted).
     */
    fun cancel() {
        cancelRequested = true
        if (_task.value !is StorageTask.Exporting) job?.cancel()
    }

    fun dismissResult() { if (!busy) _task.value = StorageTask.Idle }

    // ------------------------------------------------------------------ permissions

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Storage permissions: already granted, granted by root (`pm grant`), or asked for with the system prompt. */
    private suspend fun ensurePermission(): Boolean {
        val perms = listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (perms.all(::granted)) return true
        if (g.root.isAvailable()) {
            for (p in perms) g.root.run("pm grant ${context.packageName} $p")
            if (perms.all(::granted)) return true
        }
        permissionRequest.value = true
        return false
    }

    /** From the activity after the prompt. */
    fun onPermissionResult(ok: Boolean) {
        permissionRequest.value = false
        if (!ok) _task.value = StorageTask.Problem(null, StorageTask.Problem.Kind.NoPermission)
        else c.scope.launch { refreshRestoreOffer() }
    }

    /** From the activity after the folder picker: remember the grant for this stick and try again. */
    fun onAccessGranted(volume: Volume, tree: Uri?) {
        accessRequest.value = null
        if (tree == null) return
        runCatching { context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        sp.edit { putString("saf_${volume.uuid ?: volume.path.name}", tree.toString()) }
        export(volume)
    }

    fun requestAccess(volume: Volume) { accessRequest.value = volume }

    private fun safFor(v: Volume): Uri? = sp.getString("saf_${v.uuid ?: v.path.name}", null)?.let(Uri::parse)

    private fun appDirOn(v: Volume): File? = context.getExternalFilesDirs(null).filterNotNull()
        .firstOrNull { it.absolutePath.startsWith(v.path.absolutePath + "/") }

    private val chooser by lazy { WriterChooser(g.root, File(context.cacheDir, "export"), ::safFor, context.contentResolver, ::appDirOn) }

    // ------------------------------------------------------------------ export and backup

    private fun engine() = ExportEngine(g.media::file, g.clock.zone, g.clock::nowMs, c.syncState.value?.frameName, BuildConfig.VERSION_NAME)

    fun export(volume: Volume) = launchTask { runExport(volume, backup = false) }

    private suspend fun runExport(volume: Volume, backup: Boolean): Boolean {
        if (!ensurePermission()) {
            if (!backup) _task.value = StorageTask.Problem(volume, StorageTask.Problem.Kind.NoPermission)
            return false
        }
        _task.value = StorageTask.Exporting(volume, backup, Progress())
        val folder = if (backup) Volume.BACKUP_DIR else Volume.EXPORT_DIR
        val writer = chooser.choose(volume, folder)
        if (writer == null) {
            _task.value = if (backup) StorageTask.Idle else StorageTask.Problem(volume, StorageTask.Problem.Kind.NoWriteAccess)
            return false
        }
        val engine = engine()
        val placements = withContext(Dispatchers.IO) { g.db.placements().all() }
        val plan = withContext(Dispatchers.IO) { engine.plan(placements, writer, Fat32.limited(volume.fsType)) }
        if (backup && plan.copies.isEmpty()) {
            _task.value = StorageTask.Idle
            markBackedUp(volume)
            return true
        }
        val outcome = withContext(Dispatchers.IO) {
            engine.run(plan, writer, volume.freeBytes(), { p -> _task.value = StorageTask.Exporting(volume, backup, p) }, { cancelRequested })
        }
        Log.i(TAG, "export to ${writer.shownDir} via ${writer.method}: $outcome")
        _task.value = when (outcome) {
            is ExportEngine.Outcome.Done -> {
                if (backup) markBackedUp(volume)
                StorageTask.Exported(volume, backup, outcome)
            }
            is ExportEngine.Outcome.NoSpace -> StorageTask.Problem(volume, StorageTask.Problem.Kind.NoSpace, outcome.needBytes, outcome.freeBytes)
            ExportEngine.Outcome.NothingToExport -> StorageTask.Problem(volume, StorageTask.Problem.Kind.NothingToExport)
            ExportEngine.Outcome.Failed -> StorageTask.Problem(volume, StorageTask.Problem.Kind.WriteFailed)
        }
        // A silent backup leaves no screen behind unless something went wrong or the panel is open.
        if (backup && outcome is ExportEngine.Outcome.Done && !panelOpen.value) _task.value = StorageTask.Idle
        return outcome is ExportEngine.Outcome.Done
    }

    private fun markBackedUp(v: Volume) = sp.edit { putLong("last_backup_${v.uuid ?: v.path.name}", g.clock.nowMs()) }

    /** Automatic backup (B.11): a stick labelled KINFRAME or with a `kinframe-backup/` folder gets new items. */
    private suspend fun maybeAutoBackup() {
        if (!_autoBackup.value || busy || !g.prefs.claimed) return
        val v = monitor.volumes.value.firstOrNull { it.isBackupTarget() } ?: return
        val j = launchTask { runExport(v, backup = true) }
        j.join()
    }

    // ------------------------------------------------------------------ import and restore

    /** Scans the stick and opens the picker. */
    fun pickImport(volume: Volume) = launchTask {
        if (!ensurePermission()) { _task.value = StorageTask.Problem(volume, StorageTask.Problem.Kind.NoPermission); return@launchTask }
        _task.value = StorageTask.Scanning
        val found = withContext(Dispatchers.IO) { ImportScanner.scan(volume.path) }
        _task.value = if (found.isEmpty()) StorageTask.Problem(volume, StorageTask.Problem.Kind.NothingFound)
        else StorageTask.Picking(volume, ImportScanner.sort(found, ImportScanner.Sort.Date), restore = false)
    }

    /** Restore: the items of a kinframe export or backup, with captions and dates from its manifest. */
    fun pickRestore(offer: RestoreOffer) = launchTask {
        _task.value = StorageTask.Scanning
        val manifest = withContext(Dispatchers.IO) { readManifest(offer.dir) }
        val items = manifest?.let { m -> withContext(Dispatchers.IO) { ImportScanner.fromExport(offer.dir, m) } }.orEmpty()
        _task.value = if (items.isEmpty()) StorageTask.Problem(offer.volume, StorageTask.Problem.Kind.NothingFound)
        else StorageTask.Picking(offer.volume, items, restore = true)
        panelOpen.value = true
    }

    fun import(volume: Volume, items: List<ImportCandidate>, restore: Boolean) = launchTask {
        if (!online()) { _task.value = StorageTask.Problem(volume, StorageTask.Problem.Kind.Offline); return@launchTask }
        _task.value = StorageTask.Importing(volume, restore, Progress(0, items.size))
        val engine = ImportEngine(
            localHashes = { withContext(Dispatchers.IO) { g.db.placements().all().mapNotNullTo(HashSet()) { it.mediaSha256?.lowercase() } } },
            serverKnown = { g.api.importCheck(it) },
            upload = { cand, onBytes -> g.api.importFile(cand.file, cand.name, cand.mime, cand.capturedAt, cand.caption, onProgress = onBytes) },
        )
        val result = engine.run(items, { p -> _task.value = StorageTask.Importing(volume, restore, p) }, { cancelRequested })
        Log.i(TAG, "import from ${volume.path}: $result")
        if (restore) dismissRestore()
        c.session.value?.requestPull()
        _task.value = StorageTask.Imported(volume, restore, result)
    }

    private fun online(): Boolean = g.network.status.value.connected && c.connection.value == FrameSession.Connection.Online

    private fun readManifest(dir: File): ExportManifest? =
        runCatching { File(dir, ExportManifest.FILE_NAME).readText() }.getOrNull()?.let(ExportManifest::parse)

    /** Offers a restore when the frame is claimed, its library is empty and a stick holds a kinframe export. */
    suspend fun refreshRestoreOffer() {
        _restoreOffer.value = withContext(Dispatchers.IO) {
            if (!g.prefs.claimed || g.db.placements().all().isNotEmpty()) return@withContext null
            for (v in monitor.volumes.value) {
                if (sp.getBoolean("restore_dismissed_${v.uuid ?: v.path.name}", false)) continue
                for (dir in listOf(v.backupDir, v.exportDir)) {
                    val m = readManifest(dir) ?: continue
                    if (m.items.isNotEmpty()) return@withContext RestoreOffer(v, dir, m.items.size, m.frameName, m.exportedAt)
                }
            }
            null
        }
    }

    fun dismissRestore() {
        _restoreOffer.value?.volume?.let { v -> sp.edit { putBoolean("restore_dismissed_${v.uuid ?: v.path.name}", true) } }
        _restoreOffer.value = null
    }

    // ------------------------------------------------------------------ eject

    /** Root can unmount through vold (`sm unmount <id>`); without root the panel says how to remove it safely. */
    fun canEject(v: Volume): Boolean = g.root.known && v.id != null

    fun eject(v: Volume) = c.scope.launch {
        g.root.run("sync", timeoutMs = 60_000)
        val r = g.root.run("sm unmount ${RootWriter.q(v.id ?: return@launch)}", timeoutMs = 30_000)
        Log.i(TAG, "eject ${v.id}: ${r.exitCode} ${r.output.trim()}")
        monitor.refresh()
        _task.value = StorageTask.Idle
        panelOpen.value = false
    }

    private fun launchTask(block: suspend () -> Unit): Job {
        if (busy) return job ?: Job()
        cancelRequested = false
        val j = c.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                _task.value = StorageTask.Idle
            } catch (e: Exception) {
                Log.w(TAG, "storage task failed", e)
                _task.value = StorageTask.Problem(null, StorageTask.Problem.Kind.ReadFailed)
            }
        }
        job = j
        return j
    }

    companion object {
        private const val TAG = "KinRemovable"
        const val AUTO_BACKUP_EVERY_MS = 60 * 60_000L
    }
}
