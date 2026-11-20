package app.kinframe.frame.remotehelp

import android.util.Log
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.RemoteHelpReport
import app.kinframe.core.api.RemoteHelpState
import app.kinframe.core.util.AtomicFile
import app.kinframe.frame.system.RootShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/**
 * The frame's side of a remote help session (docs/spec/remote-help.md §5.4), without Android UI: what the server
 * wants ([accept]) is recorded at once in [stateFile]; [run] brings the device in line with it, one step at a time,
 * and reports `starting` → `on` / `failed` → `off` through [onReport] (an immediate heartbeat).
 *
 * - Start: binaries (download, SHA-256, unpack, install; cached by version), `/system/etc/resolv.conf`, tailscaled,
 *   `tailscale up`, the operator's ADB key and ADB over TCP.
 * - End (`remote_help: null`, `ends_at` passed by [now], Stop at the frame, unpaired): `tailscale logout`, kill
 *   tailscaled, remove its state, the key line, `resolv.conf`, ADB back to USB only, `off`.
 * - App start: a session still ahead starts again (the node state survives in the state dir); a device-side session
 *   without one is cleaned up.
 */
class RemoteHelpEngine(
    private val root: RootShell,
    /** App-private files: the state file, the script, the keys handed to root. */
    private val work: File,
    /** App cache: the downloaded tarball and the unpacked binaries until root copied them. */
    private val staging: File,
    private val download: suspend (url: String, dest: File) -> Unit,
    private val now: () -> Long,
    private val abis: List<String>,
    private val onReport: () -> Unit,
    private val deviceDir: String = RemoteHelpScript.DIR,
    private val retryDelayMs: Long = 5_000,
) {
    private val stateFile = File(work, "state.json")
    private val lock = Any()
    @Volatile private var record: RemoteHelpRecord = load()
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** The session this process brought up (null after a restart, until it is brought up again). */
    @Volatile private var upHere: String? = null
    private var bootChecked = false

    private val _badge = MutableStateFlow(badgeOf(record))
    val badge: StateFlow<Long?> = _badge

    val current: RemoteHelpRecord get() = record

    fun report(): RemoteHelpReport? = record.report

    // ------------------------------------------------------------------ what the server wants

    /** A `remote_help` change: records it and wakes the worker. Quick and idempotent (called inside the feed's transaction). */
    fun accept(raw: JsonObject?) {
        val p = RemoteHelpPayload.parse(raw)
        if (raw != null && p == null) Log.w(TAG, "unreadable remote_help change: ignored")
        val wanted = p?.takeIf { it.state == RemoteHelpPayload.ACTIVE }
        update { r ->
            when {
                wanted == null -> r.copy(session = null, authKey = null)
                wanted.sessionId in r.ended -> r
                else -> r.copy(
                    session = wanted.copy(authKey = null),
                    authKey = wanted.authKey ?: r.authKey?.takeIf { r.session?.sessionId == wanted.sessionId },
                )
            }
        }
        wake.trySend(Unit)
    }

    /** Ends the session here (Stop at the frame, unpaired) without waiting for the server's change. */
    suspend fun endHere() {
        val id = record.session?.sessionId ?: record.report?.sessionId
        update { r -> r.copy(session = null, authKey = null, ended = id?.let { remember(r.ended, it) } ?: r.ended) }
        _badge.value = null
        wake.trySend(Unit)
        mutex.withLock { reconcile() }
    }

    // ------------------------------------------------------------------ the worker

    /** Runs until cancelled: reconciles on every change and at least every minute while a session runs ([now] decides when it ends). */
    suspend fun run() {
        while (true) {
            try {
                mutex.withLock { reconcile() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "remote help", e)
            }
            val s = record.session
            val wait = if (s != null) (s.endsAtMs - now()).coerceIn(1_000, 60_000) else IDLE_MS
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** One pass; callers hold [mutex]. Visible for tests. */
    internal suspend fun reconcile() {
        val r = record
        val session = r.session
        val rep = r.report
        if (session != null && session.endsAtMs <= now()) {
            Log.i(TAG, "session ${session.sessionId} reached its end time")
            update { it.copy(session = null, authKey = null, ended = remember(it.ended, session.sessionId)) }
            return stopDevice(session.sessionId)
        }
        if (session == null) {
            val busy = rep != null && (rep.state == RemoteHelpState.STARTING || rep.state == RemoteHelpState.ON)
            if (busy || upHere != null) {
                stopDevice(rep?.sessionId)
            } else if (!bootChecked && root.isAvailable()) {
                // A session the device still has but this app no longer wants (data cleared, missed change).
                val st = RemoteHelpScript.values(sh("status").output)
                if (st["SESSION"] != null || st["RUNNING"] != null) {
                    Log.i(TAG, "cleaning up a leftover session ${st["SESSION"]}")
                    stopDevice(st["SESSION"] ?: rep?.sessionId, report = rep != null || st["SESSION"] != null)
                }
            }
            bootChecked = true
            pruneCache()
            return
        }
        bootChecked = true
        if (rep != null && rep.sessionId != session.sessionId && upHere != null) stopDevice(rep.sessionId)
        val same = rep?.sessionId == session.sessionId
        when {
            same && (rep!!.state == RemoteHelpState.FAILED || rep.state == RemoteHelpState.OFF) -> Unit
            upHere == session.sessionId -> Unit
            else -> startDevice(session, r.authKey)
        }
    }

    private suspend fun startDevice(session: RemoteHelpPayload, authKey: String?) {
        val id = session.sessionId
        upHere = null
        report(RemoteHelpReport(id, RemoteHelpState.STARTING))
        _badge.value = session.endsAtMs
        val result = try {
            Result.success(bringUp(session, authKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        result.onSuccess { ip ->
            Log.i(TAG, "remote help on: $ip")
            upHere = id
            update { it.copy(authKey = null, cacheUntil = 0) }
            report(RemoteHelpReport(id, RemoteHelpState.ON, ip = ip))
        }.onFailure { e ->
            if (e is Superseded) {
                Log.i(TAG, "session $id ended while starting")
                stopDevice(id)
                return
            }
            val error = (e.message ?: e.javaClass.simpleName).take(300)
            Log.w(TAG, "remote help failed: $error")
            update { it.copy(authKey = null, ended = remember(it.ended, id)) }
            if (root.isAvailable()) runCatching { sh("stop", timeoutMs = STOP_TIMEOUT_MS) }
            upHere = null
            _badge.value = null
            update { it.copy(cacheUntil = now() + CACHE_MS) }
            report(RemoteHelpReport(id, RemoteHelpState.FAILED, error = error))
        }
    }

    /** Steps 1–5 of §5.4; returns the tailnet IP. Throws with the failing step's last line. */
    private suspend fun bringUp(s: RemoteHelpPayload, authKey: String?): String {
        s.problem()?.let { throw IOException(it) }
        if (!root.isAvailable()) throw IOException("no root")
        val abi = TailscaleAbi.pick(abis) ?: throw IOException("unsupported ABI ${abis.joinToString()}")
        val bin = s.tailscale.forAbi(abi) ?: throw IOException("no tailscale binary for $abi")
        val version = s.tailscale.version

        if (RemoteHelpScript.values(sh("has", version).output)["PRESENT"] == null) {
            installBinaries(version, abi, bin)
        }
        checkWanted(s)
        step(sh("dns"))
        checkWanted(s)
        step(sh("start", version, s.sessionId, timeoutMs = 40_000))
        checkWanted(s)
        val keyFile = authKey?.let { privateFile("authkey", it) }
        val up = try {
            step(sh("up", version, s.loginServer, s.hostname, keyFile?.path ?: "", timeoutMs = 100_000))
        } finally {
            keyFile?.delete()
        }
        val ip = up["IP"] ?: throw IOException("no tailnet address")
        checkWanted(s)
        val adbKey = privateFile("adbkey.pub", s.adbKey)
        try {
            step(sh("adb-on", adbKey.path, timeoutMs = 30_000))
        } finally {
            adbKey.delete()
        }
        return ip
    }

    private suspend fun installBinaries(version: String, abi: String, bin: TailscaleBinary) {
        withContext(Dispatchers.IO) {
            staging.mkdirs()
            val tgz = File(staging, "tailscale_${version}_$abi.tgz")
            var attempt = 0
            while (true) {
                try {
                    download(bin.url, tgz)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++attempt >= DOWNLOAD_ATTEMPTS) throw IOException("download failed: ${e.message}")
                    Log.i(TAG, "tailscale download failed (${e.message}), retrying")
                    delay(retryDelayMs * attempt)
                }
            }
            val sha = TailscaleArchive.sha256(tgz)
            if (!sha.equals(bin.sha256, ignoreCase = true)) {
                tgz.delete()
                throw IOException("sha256 mismatch: got $sha")
            }
            val out = File(staging, "bin")
            out.deleteRecursively()
            try {
                TailscaleArchive.extract(tgz, out)
                tgz.delete()
                step(sh("install", version, out.path, timeoutMs = 60_000))
            } finally {
                out.deleteRecursively()
                tgz.delete()
            }
        }
    }

    private fun checkWanted(s: RemoteHelpPayload) {
        if (record.session?.sessionId != s.sessionId) throw Superseded()
    }

    private suspend fun stopDevice(id: String?, report: Boolean = true) {
        _badge.value = null
        if (root.isAvailable()) {
            val r = sh("stop", timeoutMs = STOP_TIMEOUT_MS)
            if (r.exitCode != 0) Log.w(TAG, "clean-up: ${r.output.trim().take(200)}")
        }
        upHere = null
        update { it.copy(cacheUntil = now() + CACHE_MS) }
        if (report && id != null) report(RemoteHelpReport(id, RemoteHelpState.OFF))
    }

    /** The binaries stay 7 days after the last session, then go. */
    private suspend fun pruneCache() {
        val until = record.cacheUntil
        if (until == 0L || now() < until) return
        if (root.isAvailable()) sh("purge")
        update { it.copy(cacheUntil = 0) }
    }

    private fun report(rep: RemoteHelpReport) {
        update { it.copy(report = rep) }
        runCatching { onReport() }
    }

    // ------------------------------------------------------------------ root and files

    private class Superseded : Exception("superseded")

    private fun step(r: RootShell.Result): Map<String, String> {
        val v = RemoteHelpScript.values(r.output)
        if (r.exitCode != 0) {
            throw IOException(v["ERROR"] ?: r.output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "exit ${r.exitCode}")
        }
        return v
    }

    private suspend fun sh(command: String, vararg args: String, timeoutMs: Long = 20_000): RootShell.Result {
        val script = withContext(Dispatchers.IO) {
            work.mkdirs()
            File(work, "rh.sh").also { f -> if (!f.exists() || f.readText() != RemoteHelpScript.SCRIPT) AtomicFile.writeText(f, RemoteHelpScript.SCRIPT) }
        }
        return root.run(RemoteHelpScript.commandLine(script.path, deviceDir, command, *args), timeoutMs)
    }

    /** A file only this app (and root) can read, for handing a key to the script. */
    private suspend fun privateFile(name: String, text: String): File = withContext(Dispatchers.IO) {
        work.mkdirs()
        File(work, name).apply {
            writeText(text + "\n")
            setReadable(false, false); setReadable(true, true)
        }
    }

    private fun load(): RemoteHelpRecord = AtomicFile.readTextOrNull(stateFile)
        ?.let { runCatching { KinJson.decodeFromString(RemoteHelpRecord.serializer(), it) }.getOrNull() }
        ?: RemoteHelpRecord()

    private fun update(f: (RemoteHelpRecord) -> RemoteHelpRecord) = synchronized(lock) {
        val next = f(record)
        if (next == record) return@synchronized
        record = next
        runCatching {
            work.mkdirs()
            AtomicFile.writeText(stateFile, KinJson.encodeToString(RemoteHelpRecord.serializer(), next))
        }.onFailure { Log.w(TAG, "could not save the remote help state", it) }
    }

    private fun remember(ended: List<String>, id: String) = (ended - id + id).takeLast(20)

    private fun badgeOf(r: RemoteHelpRecord): Long? {
        val s = r.session ?: return null
        val st = r.report?.takeIf { it.sessionId == s.sessionId }?.state
        return if (st == RemoteHelpState.ON || st == RemoteHelpState.STARTING) s.endsAtMs else null
    }

    companion object {
        private const val TAG = "KinRemoteHelp"
        const val CACHE_MS = 7 * 24 * 3_600_000L
        private const val IDLE_MS = 15 * 60_000L
        private const val STOP_TIMEOUT_MS = 60_000L
        private const val DOWNLOAD_ATTEMPTS = 3
    }
}
