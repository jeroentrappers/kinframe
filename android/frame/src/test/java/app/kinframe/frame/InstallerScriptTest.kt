package app.kinframe.frame

import android.app.Application
import org.robolectric.RuntimeEnvironment
import app.kinframe.frame.system.RootShell
import app.kinframe.frame.update.Installer
import app.kinframe.frame.update.PendingInstall
import app.kinframe.frame.update.UpdatePolicy
import app.kinframe.frame.update.UpdatePolicy.Resolution
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the real root installer command line and script ([Installer.SCRIPT]) with the host shell, with fake `pm` and
 * `am` on the PATH that record their calls. Covers the install, failure, watchdog rollback and crash-loop rollback
 * sequences, and checks that [UpdatePolicy.resolvePending] reads the resulting log the right way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InstallerScriptTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var bin: File
    private lateinit var calls: File
    private lateinit var dataTmp: File
    private lateinit var dir: File
    private val commands = mutableListOf<String>()
    private var pmFails = false
    private var watchdogS = 3

    /** Executes what the app would hand to `su`, on the host, with the fakes first on the PATH. */
    private val fakeRoot = object : RootShell() {
        override suspend fun isAvailable() = true
        override suspend fun run(command: String, timeoutMs: Long): Result {
            commands += command
            val pb = ProcessBuilder("sh", "-c", command).redirectErrorStream(true)
            pb.environment().apply {
                put("PATH", bin.path + ":" + get("PATH"))
                put("KINFRAME_TMP", dataTmp.path)
                put("KINFRAME_WATCHDOG_S", watchdogS.toString())
                put("FAKE_CALLS", calls.path)
                put("FAKE_PM_FAIL", if (pmFails) "1" else "0")
            }
            val p = pb.start()
            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            return Result(p.exitValue(), p.inputStream.bufferedReader().readText())
        }
    }

    @Before
    fun setUp() {
        assumeTrue(File("/bin/sh").canExecute())
        bin = tmp.newFolder("bin")
        calls = tmp.newFolder("calls")
        dataTmp = tmp.newFolder("data-local-tmp")
        dir = tmp.newFolder("updates")
        fun fake(name: String, body: String) = File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
        // pm: record the arguments and a copy of the APK it was given (to check the staging copy).
        fake(
            "pm",
            """
            n=${'$'}(ls "${'$'}FAKE_CALLS" | grep -c '\.args${'$'}')
            echo "${'$'}*" > "${'$'}FAKE_CALLS/pm-${'$'}n.args"
            for a; do last="${'$'}a"; done
            cp "${'$'}last" "${'$'}FAKE_CALLS/pm-${'$'}n.apk"
            echo "	pkg: ${'$'}last"
            if [ "${'$'}FAKE_PM_FAIL" = 1 ]; then echo "Failure [INSTALL_FAILED_TEST]"; else echo "Success"; fi
            """.trimIndent(),
        )
        fake("am", """echo "${'$'}*" >> "${'$'}FAKE_CALLS/am.log"""")
        fake("chown", "exit 0")
    }

    private fun installer() = Installer(RuntimeEnvironment.getApplication(), fakeRoot, dir)

    private fun pmCalls(): List<String> =
        calls.listFiles()!!.filter { it.name.endsWith(".args") }.sortedBy { it.name }.map { it.readText().trim() }

    private fun amCalls(): List<String> = File(calls, "am.log").takeIf { it.exists() }?.readLines().orEmpty()

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what; pm=${pmCalls()} am=${amCalls()} log=${installer().readLog()}")
    }

    private val newApk by lazy { File(tmp.root, "new.apk").apply { writeBytes(ByteArray(4096) { 1 }) } }
    private val oldApk by lazy { File(tmp.root, "previous.apk").apply { writeBytes(ByteArray(2048) { 2 }) } }
    private val staged get() = File(dataTmp, "kinframe-update.apk")
    private val pending = PendingInstall(20099, "0.2.0", 10099, "0.1.0", startedAt = 1_000)

    @Test
    fun installsTheNewVersionAndRelaunchesTheFrame() = runBlocking {
        val inst = installer()
        val marker = inst.startedMarker(20099)
        inst.runRootScript("install", newApk, oldApk, marker)
        // The command detaches: the app's process may be killed while pm runs.
        assertTrue(commands.single().trimEnd().endsWith("&"))
        assertTrue(commands.single().contains("'install' '${newApk.path}' '${oldApk.path}' '${marker.path}'"))
        assertEquals(Installer.SCRIPT, File(dir, "install.sh").readText())
        waitFor("pm install") { pmCalls().isNotEmpty() }
        marker.createNewFile() // the new version started
        waitFor("relaunch") { amCalls().isNotEmpty() }
        Thread.sleep(1_500) // longer than one watchdog step
        assertEquals(listOf("install -r ${staged.path}"), pmCalls())
        assertArrayEquals(newApk.readBytes(), File(calls, "pm-0.apk").readBytes())
        assertFalse("staging copy removed", staged.exists())
        assertEquals(listOf("start -n ${RuntimeEnvironment.getApplication().packageName}/.MainActivity"), amCalls())
        val log = inst.readLog()!!
        assertTrue(log.contains("Success"))
        assertFalse(log.contains("watchdog_rollback"))
        assertEquals(Resolution.Updated("0.2.0"), UpdatePolicy.resolvePending(pending, 20099, 2_000, log))
    }

    @Test
    fun watchdogReinstallsThePreviousVersionWhenTheNewOneNeverStarts() = runBlocking {
        val inst = installer()
        inst.runRootScript("install", newApk, oldApk, inst.startedMarker(20099))
        waitFor("watchdog rollback") { pmCalls().size == 2 && amCalls().size == 2 }
        assertEquals(listOf("install -r ${staged.path}", "install -r -d ${staged.path}"), pmCalls())
        assertArrayEquals(oldApk.readBytes(), File(calls, "pm-1.apk").readBytes())
        val log = inst.readLog()!!
        assertTrue(log.contains("watchdog_rollback"))
        // The previous version comes back and records that 20099 must be skipped.
        assertEquals(Resolution.RolledBack(20099, "not_started 20099"), UpdatePolicy.resolvePending(pending, 10099, 2_000, log))
    }

    @Test
    fun aFailedInstallIsReportedAndNothingElseHappens() = runBlocking {
        pmFails = true
        val inst = installer()
        inst.runRootScript("install", newApk, oldApk, inst.startedMarker(20099))
        waitFor("failure in log") { inst.readLog()?.contains("Failure") == true }
        Thread.sleep(watchdogS * 1_000L + 500)
        assertEquals(listOf("install -r ${staged.path}"), pmCalls())
        assertTrue(amCalls().isEmpty())
        assertEquals(
            Resolution.Failed("install_failed: Failure [INSTALL_FAILED_TEST]"),
            UpdatePolicy.resolvePending(pending, 10099, 2_000, inst.readLog()),
        )
    }

    @Test
    fun crashLoopRollbackDowngradesToTheKeptApk() = runBlocking {
        val inst = installer()
        inst.runRootScript("rollback", oldApk, null, null)
        waitFor("rollback relaunch") { amCalls().isNotEmpty() }
        assertEquals(listOf("install -r -d ${staged.path}"), pmCalls())
        assertArrayEquals(oldApk.readBytes(), File(calls, "pm-0.apk").readBytes())
        val rb = pending.copy(rollback = true)
        assertEquals(Resolution.RolledBack(20099, "crash_loop 20099"), UpdatePolicy.resolvePending(rb, 10099, 2_000, inst.readLog()))
    }
}
