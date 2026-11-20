package app.kinframe.frame.remotehelp

import android.os.Build
import android.util.Log
import app.kinframe.core.api.FrameFeatures
import app.kinframe.core.api.RemoteHelpReport
import app.kinframe.frame.FrameController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Remote help in the GitHub build (docs/spec/remote-help.md §5.4): wires [RemoteHelpEngine] to the frame (root,
 * the frame's HTTP client and clock, immediate heartbeats, `DELETE /v1/device/remote-help` for Stop at the frame).
 */
class RemoteHelpManager(private val c: FrameController) : RemoteHelp {
    private val g = c.g
    private val engine by lazy {
        RemoteHelpEngine(
            root = g.root,
            work = File(g.app.filesDir, "remote-help"),
            staging = File(g.app.cacheDir, "remote-help"),
            download = { url, dest -> withContext(Dispatchers.IO) { TailscaleArchive.download(g.http, url, dest) } },
            now = g.clock::nowMs,
            abis = Build.SUPPORTED_ABIS.toList(),
            onReport = { c.session.value?.sendHeartbeatNow() },
        )
    }

    override val badge: StateFlow<Long?> get() = engine.badge

    override fun start() {
        c.scope.launch(Dispatchers.Default) { engine.run() }
    }

    override fun onChange(payload: JsonObject?) = engine.accept(payload)

    override fun report(): RemoteHelpReport? = engine.report()

    override suspend fun features(): List<String> = if (g.root.isAvailable()) listOf(FrameFeatures.REMOTE_HELP) else emptyList()

    /** `Build.SERIAL` (the serial `adb devices` shows) on Android 7 and older; newer Android hides it from apps. */
    @Suppress("DEPRECATION")
    override fun serial(): String? =
        if (Build.VERSION.SDK_INT < 26) Build.SERIAL?.takeUnless { it.isBlank() || it.equals(Build.UNKNOWN, true) } else null

    override fun stopHere() {
        c.scope.launch(Dispatchers.Default) {
            // Tell the server first (it logs "ended at the frame" and tells the family), then clean up either way.
            val told = tellServer()
            engine.endHere()
            if (!told) {
                // Offline or the server didn't answer: keep trying for a while; the heartbeat says `off` meanwhile.
                for (wait in listOf(30_000L, 120_000L, 600_000L)) {
                    delay(wait)
                    if (tellServer()) break
                }
            }
        }
    }

    private suspend fun tellServer(): Boolean = withTimeoutOrNull(15_000) {
        try {
            g.api.endRemoteHelp()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "DELETE /device/remote-help failed: ${e.message}")
            false
        }
    } ?: false

    override suspend fun onUnpaired() = engine.endHere()

    companion object {
        private const val TAG = "KinRemoteHelp"
    }
}
