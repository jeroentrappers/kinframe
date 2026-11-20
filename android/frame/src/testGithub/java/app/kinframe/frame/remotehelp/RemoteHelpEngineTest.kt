package app.kinframe.frame.remotehelp

import app.kinframe.core.api.RemoteHelpReport
import app.kinframe.core.api.RemoteHelpState
import app.kinframe.frame.system.RootShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPOutputStream

/** The remote help state machine against a fake root shell (what the script does is in [RemoteHelpScriptTest]). */
class RemoteHelpEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private data class Call(val command: String, val args: List<String>, val files: Map<String, String>)

    /** Answers the script's commands; remembers them, and the contents of files handed over (keys). */
    private inner class FakeRoot : RootShell() {
        var rootOk = true
        val calls = mutableListOf<Call>()
        val answers = mutableMapOf<String, Result>()
        override suspend fun isAvailable() = rootOk
        override suspend fun run(command: String, timeoutMs: Long): Result {
            val m = Regex("""sh '[^']*/rh\.sh' (\S+)(.*)$""").find(command) ?: error("unexpected command $command")
            assertTrue(command, command.startsWith("mkdir -p '${deviceDir}'"))
            val args = Regex("""'((?:[^']|'\\'')*)'""").findAll(m.groupValues[2]).map { it.groupValues[1].replace("'\\''", "'") }.toList()
            val files = args.filter { it.startsWith(work.path) }.associateWith { File(it).takeIf(File::exists)?.readText() ?: "<gone>" }
            calls += Call(m.groupValues[1], args, files)
            return answers[m.groupValues[1]] ?: Result(0, defaults[m.groupValues[1]] ?: "")
        }
        fun names() = calls.map { it.command }
    }

    private val defaults = mapOf("install" to "INSTALLED=1", "dns" to "DNS=1", "start" to "RUNNING=1", "up" to "IP=100.64.0.9\n", "adb-on" to "ADB=1", "stop" to "STOPPED=1")

    private lateinit var work: File
    private lateinit var staging: File
    private val deviceDir get() = tmp.root.path + "/kinframe-ts"
    private val root = FakeRoot()
    private var now = Instant.parse("2026-11-20T10:00:00Z").toEpochMilli()
    private val endsAt = "2026-11-20T18:00:00Z"
    private var reports = 0
    private var downloads = 0
    private lateinit var tgz: ByteArray
    private lateinit var sha: String

    @Before fun setUp() {
        work = tmp.newFolder("files", "remote-help")
        staging = tmp.newFolder("cache", "remote-help")
        tgz = tarGz(mapOf("tailscale_1.104.7_arm/tailscaled" to "daemon", "tailscale_1.104.7_arm/tailscale" to "cli"))
        sha = TailscaleArchive.sha256(tgz.inputStream())
    }

    private fun engine() = RemoteHelpEngine(
        root = root, work = work, staging = staging,
        download = { _, dest -> downloads++; dest.parentFile!!.mkdirs(); dest.writeBytes(tgz) },
        now = { now }, abis = listOf("armeabi-v7a", "armeabi"), onReport = { reports++ },
        deviceDir = deviceDir, retryDelayMs = 1,
    )

    private fun payload(session: String = "rh_01", key: String? = "secretkey123", armSha: String = sha) =
        RemoteHelpPayloadTest.obj(RemoteHelpPayloadTest.json(sessionId = session, endsAt = endsAt, authKey = key, armSha = armSha))

    private fun tarGz(entries: Map<String, String>): ByteArray {
        val tar = ByteArrayOutputStream()
        for ((name, body) in entries) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "%011o".format(body.length).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            tar.write(h); tar.write(body.toByteArray()); tar.write(ByteArray((512 - body.length % 512) % 512))
        }
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(tar.toByteArray()) } }.toByteArray()
    }

    private fun RemoteHelpEngine.state() = report()?.state

    @Test fun startsUpAndReportsOn() = runBlocking {
        val e = engine()
        e.accept(payload())
        // The key is kept until used, but never in the session record.
        assertEquals("secretkey123", e.current.authKey)
        assertNull(e.current.session!!.authKey)
        e.reconcile()
        assertEquals(listOf("has", "install", "dns", "start", "up", "adb-on"), root.names())
        assertEquals(1, downloads)
        val up = root.calls.first { it.command == "up" }
        assertEquals(listOf("1.104.7", "https://headscale.appmire.be", "kinframe-36339301"), up.args.take(3))
        assertEquals("secretkey123\n", up.files[up.args[3]])
        val adb = root.calls.first { it.command == "adb-on" }
        assertEquals("QAAAAJ+x/abc= jeroen@laptop\n", adb.files.values.single())
        assertEquals(listOf("1.104.7", "rh_01"), root.calls.first { it.command == "start" }.args)
        assertEquals(RemoteHelpReport("rh_01", RemoteHelpState.ON, ip = "100.64.0.9"), e.report())
        assertEquals(2, reports) // starting, then on: each an immediate heartbeat
        assertEquals(Instant.parse(endsAt).toEpochMilli(), e.badge.value)
        // Used: gone from memory, the state file and the disk.
        assertNull(e.current.authKey)
        assertFalse(File(work, "state.json").readText().contains("secretkey123"))
        assertFalse(File(work, "authkey").exists())
        assertFalse(File(work, "adbkey.pub").exists())
        assertTrue(staging.listFiles()!!.isEmpty())
        // The binaries the root step copied were the unpacked ones.
        assertTrue(root.calls.first { it.command == "install" }.args[1].startsWith(staging.path))

        // The server's follow-up change (no auth key) for the same session changes nothing.
        e.accept(payload(key = null))
        e.reconcile()
        assertEquals(6, root.calls.size)
    }

    @Test fun anAppRestartStartsTheSessionAgainWithoutAKey() = runBlocking {
        engine().apply { accept(payload()); reconcile() }
        root.calls.clear()
        root.answers["has"] = RootShell.Result(0, "PRESENT=1\n")
        val again = engine()
        assertEquals(Instant.parse(endsAt).toEpochMilli(), again.badge.value)
        again.reconcile()
        assertEquals(listOf("has", "dns", "start", "up", "adb-on"), root.names())
        assertEquals("", root.calls.first { it.command == "up" }.args[3])
        assertEquals(1, downloads)
        assertEquals(RemoteHelpState.ON, again.state())
    }

    @Test fun noSessionCleansUpAndReportsOffThenTheCacheGoesAfterAWeek() = runBlocking {
        val e = engine()
        e.accept(payload()); e.reconcile()
        root.calls.clear()
        e.accept(null)
        e.reconcile()
        assertEquals(listOf("stop"), root.names())
        assertEquals(RemoteHelpReport("rh_01", RemoteHelpState.OFF), e.report())
        assertNull(e.badge.value)
        assertEquals(now + RemoteHelpEngine.CACHE_MS, e.current.cacheUntil)
        e.reconcile()
        assertEquals(listOf("stop"), root.names()) // nothing more to do
        now += RemoteHelpEngine.CACHE_MS + 1
        e.reconcile()
        assertEquals(listOf("stop", "purge"), root.names())
        assertEquals(0L, e.current.cacheUntil)
    }

    @Test fun endsByTheFramesClockAndDoesNotStartTheSameSessionAgain() = runBlocking {
        val e = engine()
        e.accept(payload()); e.reconcile()
        root.calls.clear()
        now = Instant.parse(endsAt).toEpochMilli() + 1
        e.reconcile()
        assertEquals(listOf("stop"), root.names())
        assertEquals(RemoteHelpState.OFF, e.state())
        assertNull(e.current.session)
        e.accept(payload(key = null)) // a late change for the ended session
        e.reconcile()
        assertEquals(listOf("stop"), root.names())
    }

    @Test fun aBadDownloadFailsTheSession() = runBlocking {
        val e = engine()
        e.accept(payload(armSha = "0".repeat(64)))
        e.reconcile()
        assertEquals(listOf("has", "stop"), root.names())
        assertEquals(RemoteHelpState.FAILED, e.state())
        assertTrue(e.report()!!.error!!.startsWith("sha256 mismatch"))
        assertNull(e.current.authKey)
        assertNull(e.badge.value)
        // Not tried again: the server ends a failed session.
        e.reconcile()
        assertEquals(listOf("has", "stop"), root.names())
    }

    @Test fun aFailedJoinReportsTheLastLine() = runBlocking {
        root.answers["up"] = RootShell.Result(1, "ERROR=backend error: invalid key: unable to authenticate\n")
        val e = engine()
        e.accept(payload())
        e.reconcile()
        assertEquals(listOf("has", "install", "dns", "start", "up", "stop"), root.names())
        assertEquals(RemoteHelpReport("rh_01", RemoteHelpState.FAILED, error = "backend error: invalid key: unable to authenticate"), e.report())
        assertFalse(File(work, "authkey").exists())
    }

    @Test fun stopAtTheFrameEndsItAndIgnoresALateChange() = runBlocking {
        val e = engine()
        e.accept(payload()); e.reconcile()
        root.calls.clear()
        e.endHere()
        assertEquals(listOf("stop"), root.names())
        assertEquals(RemoteHelpState.OFF, e.state())
        e.accept(payload(key = null))
        e.reconcile()
        assertEquals(listOf("stop"), root.names())
        // A new session the operator starts later does run.
        e.accept(payload(session = "rh_02"))
        e.reconcile()
        assertEquals(listOf("stop", "has", "install", "dns", "start", "up", "adb-on"), root.names())
        assertEquals(RemoteHelpState.ON, e.state())
    }

    @Test fun aNewSessionReplacesTheRunningOne() = runBlocking {
        val e = engine()
        e.accept(payload()); e.reconcile()
        root.calls.clear()
        root.answers["has"] = RootShell.Result(0, "PRESENT=1\n")
        e.accept(payload(session = "rh_02"))
        e.reconcile()
        assertEquals(listOf("stop", "has", "dns", "start", "up", "adb-on"), root.names())
        assertEquals("rh_02", e.report()!!.sessionId)
    }

    @Test fun aLeftoverSessionOnTheDeviceIsCleanedUpAtStart() = runBlocking {
        root.answers["status"] = RootShell.Result(0, "SESSION=rh_old\nRUNNING=1\n")
        val e = engine()
        e.reconcile()
        assertEquals(listOf("status", "stop"), root.names())
        assertEquals(RemoteHelpReport("rh_old", RemoteHelpState.OFF), e.report())
        e.reconcile()
        assertEquals(listOf("status", "stop"), root.names()) // checked once per start
    }

    @Test fun nothingLeftOverMeansOneStatusCheck() = runBlocking {
        val e = engine()
        e.reconcile()
        assertEquals(listOf("status"), root.names())
        assertNull(e.report())
    }

    @Test fun withoutRootItFails() = runBlocking {
        root.rootOk = false
        val e = engine()
        e.accept(payload())
        e.reconcile()
        assertEquals(emptyList<String>(), root.names())
        assertEquals(RemoteHelpReport("rh_01", RemoteHelpState.FAILED, error = "no root"), e.report())
    }
}
