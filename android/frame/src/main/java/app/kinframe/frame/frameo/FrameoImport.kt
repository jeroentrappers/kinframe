package app.kinframe.frame.frameo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.kinframe.core.api.DeviceRevokedException
import app.kinframe.core.api.ItemOrigin
import app.kinframe.frame.FrameController
import app.kinframe.frame.removable.Progress
import app.kinframe.frame.sync.FrameSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What the Frameo import shows (slideshow card, settings section). */
sealed interface FrameoState {
    /** Nothing running. [found] is the number of Frameo files on the frame (0 = none, so nothing is offered). */
    data class Idle(val found: Int) : FrameoState
    data class Running(val progress: Progress, val waitingForNetwork: Boolean = false) : FrameoState
    data class Done(val outcome: FrameoImporter.Outcome) : FrameoState
    data object NoPermission : FrameoState
}

/**
 * Brings the photos and videos a converted Frameo frame received into kinframe (docs/spec/frameo-import.md).
 * Runs once by itself after the frame is claimed (unless the owner stopped it before), and again from the frame
 * settings. Survives reboots and network loss: an interrupted run continues at the next start, and the server's
 * list of imported keys is the progress, so nothing is uploaded twice.
 */
class FrameoImport(
    private val c: FrameController,
    private val mediaDir: File = File(Environment.getExternalStorageDirectory(), "frameo_files/media"),
    private val database: FrameoDatabase = FrameoDatabase(c.g.app, c.g.root),
) {
    private val g = c.g
    private val sp = g.app.getSharedPreferences("frameo", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<FrameoState>(FrameoState.Idle(0))
    val state: StateFlow<FrameoState> = _state
    private var job: Job? = null
    @Volatile private var stopRequested = false

    /** Started automatically or by hand; a stopped automatic run counts as declined. */
    private var autoDone: Boolean
        get() = sp.getBoolean("auto_done", false)
        set(v) = sp.edit(commit = true) { putBoolean("auto_done", v) }

    /** A run was going on when the frame stopped: continue it at the next start. */
    private var interrupted: Boolean
        get() = sp.getBoolean("running", false)
        set(v) = sp.edit(commit = true) { putBoolean("running", v) }

    val busy: Boolean get() = _state.value is FrameoState.Running

    fun start() {
        c.scope.launch {
            // Only for a frame that belongs to a family, and once it has been online (the session exists).
            c.session.first { it != null }
            while (!g.prefs.claimed) delay(5_000)
            if (!ensurePermission()) return@launch
            val found = withContext(Dispatchers.IO) { count() }
            if (!busy) _state.value = FrameoState.Idle(found)
            if (found > 0 && (interrupted || !autoDone)) {
                Log.i(TAG, "found $found Frameo files: importing (resume=$interrupted)")
                run()
            }
        }
    }

    /** From the frame settings (owner-gated). */
    fun importNow() = run()

    /** Stop button: the run ends after the current upload; the automatic run is not offered again. */
    fun stop() {
        stopRequested = true
        job?.cancel()
        interrupted = false
        autoDone = true
    }

    fun dismissResult() {
        if (_state.value is FrameoState.Done || _state.value is FrameoState.NoPermission) {
            c.scope.launch { _state.value = FrameoState.Idle(withContext(Dispatchers.IO) { count() }) }
        }
    }

    /** The frame was reset (credential revoked): the next family gets the automatic import again. */
    fun reset() {
        job?.cancel()
        sp.edit(commit = true) { clear() }
        _state.value = FrameoState.Idle(0)
        start()
    }

    private fun files(): List<File> = mediaDir.listFiles()?.toList().orEmpty()

    private fun count(): Int = FrameoMapping.plan(files(), emptyMap()).size

    private fun run(): Job {
        job?.takeIf { it.isActive }?.let { return it }
        stopRequested = false
        val j = c.scope.launch {
            try {
                if (!ensurePermission()) { _state.value = FrameoState.NoPermission; return@launch }
                interrupted = true
                _state.value = FrameoState.Running(Progress())
                val entries = database.read()
                val items = withContext(Dispatchers.IO) { FrameoMapping.plan(files(), entries.orEmpty()) }
                Log.i(TAG, "${items.size} files to import, ${entries?.size ?: "no"} database entries")
                val importer = FrameoImporter(
                    knownKeys = { g.api.importedKeys(ItemOrigin.FRAMEO, it) },
                    upload = { cand, onBytes ->
                        g.api.importFile(
                            cand.file, cand.key, cand.mime, FrameoMapping.capturedAt(cand), cand.entry?.caption,
                            origin = FrameoMapping.origin(cand), onProgress = onBytes,
                        )
                    },
                )
                var backoff = 15_000L
                var last = Progress(0, items.size)
                while (true) {
                    awaitOnline(last)
                    try {
                        val outcome = importer.run(items, { p -> last = p; _state.value = FrameoState.Running(p) }, { stopRequested })
                        Log.i(TAG, "Frameo import finished: $outcome")
                        interrupted = false
                        autoDone = true
                        _state.value = FrameoState.Done(outcome)
                        c.session.value?.requestPull()
                        break
                    } catch (e: DeviceRevokedException) {
                        throw e
                    } catch (e: IOException) {
                        if (!FrameoImporter.retryable(e)) throw e
                        Log.w(TAG, "Frameo import paused: ${e.message}")
                        _state.value = FrameoState.Running(last, waitingForNetwork = true)
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(10 * 60_000L)
                    }
                }
            } catch (e: CancellationException) {
                _state.value = FrameoState.Idle(withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { count() })
            } catch (e: Exception) {
                // Revoked, or an answer that won't change by trying again: keep the resume flag for the next start.
                Log.w(TAG, "Frameo import failed", e)
                _state.value = FrameoState.Idle(withContext(Dispatchers.IO) { count() })
            }
        }
        job = j
        return j
    }

    private suspend fun awaitOnline(last: Progress) {
        while (!(g.network.status.value.connected && c.connection.value == FrameSession.Connection.Online)) {
            _state.value = FrameoState.Running(last, waitingForNetwork = true)
            delay(5_000)
        }
        _state.value = FrameoState.Running(last)
    }

    private fun granted() = ContextCompat.checkSelfPermission(g.app, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** The media folder is on shared storage: granted at provisioning, else by root (`pm grant`). */
    private suspend fun ensurePermission(): Boolean {
        if (granted()) return true
        if (g.root.isAvailable()) g.root.run("pm grant ${g.app.packageName} ${Manifest.permission.READ_EXTERNAL_STORAGE}")
        return granted()
    }

    private companion object { const val TAG = "KinFrameo" }
}
