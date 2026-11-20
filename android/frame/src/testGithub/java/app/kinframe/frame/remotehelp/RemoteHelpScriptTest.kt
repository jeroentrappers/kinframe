package app.kinframe.frame.remotehelp

import app.kinframe.frame.system.RootShell
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the real root script ([RemoteHelpScript.SCRIPT]) with the host shell through the exact command line the app
 * hands to `su 0 sh`, with fake `tailscaled`, `tailscale`, `getprop`/`setprop`, `stop`/`start`, `mount` and `chown`
 * that record what they were asked. Covers a whole session: install, DNS, start, join, ADB, status, stop, purge.
 */
class RemoteHelpScriptTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var bin: File
    private lateinit var calls: File
    private lateinit var props: File
    private lateinit var dir: File
    private lateinit var sys: File
    private lateinit var adbKeys: File
    private lateinit var script: File
    private var upFails = false

    private val root = object : RootShell() {
        override suspend fun isAvailable() = true
        override suspend fun run(command: String, timeoutMs: Long): Result {
            val pb = ProcessBuilder("sh", "-c", command).redirectErrorStream(true)
            pb.environment().apply {
                put("PATH", bin.path + ":" + get("PATH"))
                put("KINFRAME_ADB_KEYS", adbKeys.path)
                put("KINFRAME_SYSTEM", sys.path)
                put("KINFRAME_MOUNTS", File(tmp.root, "mounts").path)
                put("KINFRAME_ACCT", File(tmp.root, "acct-tasks").path)
                put("FAKE_CALLS", calls.path)
                put("FAKE_PROPS", props.path)
                put("FAKE_UP_FAIL", if (upFails) "1" else "0")
            }
            val p = pb.start()
            val out = StringBuilder()
            val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
            assertTrue("timed out: $command", p.waitFor(timeoutMs, TimeUnit.MILLISECONDS))
            reader.join(2_000)
            return Result(p.exitValue(), out.toString())
        }
    }

    private fun sh(command: String, vararg args: String): RootShell.Result = runBlocking {
        root.run(RemoteHelpScript.commandLine(script.path, dir.path, command, *args), 60_000)
    }

    private fun fake(name: String, body: String, into: File = bin) = File(into, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }

    private fun log() = File(calls, "log").takeIf { it.exists() }?.readLines().orEmpty()

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) { if (check()) return; Thread.sleep(100) }
        throw AssertionError("timed out waiting for $what; log: ${log()}")
    }

    @Before fun setUp() {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(listOf("/usr/bin/setsid", "/bin/setsid").any { File(it).canExecute() })
        bin = tmp.newFolder("bin")
        calls = tmp.newFolder("calls")
        props = tmp.newFolder("props")
        dir = File(tmp.root, "data-local-tmp/kinframe-ts")
        sys = tmp.newFolder("system")
        File(sys, "etc").mkdirs()
        adbKeys = File(tmp.newFolder("adb"), "adb_keys")
        script = File(tmp.newFolder("app"), "rh.sh").apply { writeText(RemoteHelpScript.SCRIPT) }
        File(tmp.root, "mounts").writeText("rootfs / rootfs ro 0 0\n/dev/block/by-name/system ${sys.path} ext4 ro,relatime 0 0\n/dev/block/by-name/userdata /data ext4 rw 0 0\n")
        fake("getprop", """cat "${'$'}FAKE_PROPS/${'$'}1" 2>/dev/null""")
        fake("setprop", """printf '%s' "${'$'}2" > "${'$'}FAKE_PROPS/${'$'}1"; echo "setprop ${'$'}1 ${'$'}2" >> "${'$'}FAKE_CALLS/log"""")
        fake("stop", """echo "stop ${'$'}1" >> "${'$'}FAKE_CALLS/log"""")
        fake("start", """echo "start ${'$'}1" >> "${'$'}FAKE_CALLS/log"""")
        fake("mount", """echo "mount ${'$'}*" >> "${'$'}FAKE_CALLS/log"""")
        fake("chown", """echo "chown ${'$'}*" >> "${'$'}FAKE_CALLS/log"""")
        File(props, "net.dns1").writeText("192.168.1.1")
        File(tmp.root, "acct-tasks").writeText("") // the root cgroup's task list
    }

    @After fun tearDown() {
        // Never leave a fake tailscaled behind.
        File(dir, "tailscaled.pid").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()?.let { pid ->
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        }
    }

    /** What the release tarball would give: fakes for the two binaries. */
    private fun binaries(): File {
        val src = tmp.newFolder("unpacked")
        fake("tailscaled", """
            for a in "${'$'}@"; do case "${'$'}a" in --socket=*) S="${'$'}{a#--socket=}";; esac; done
            echo "${'$'}*" > "${'$'}FAKE_CALLS/tailscaled.args"
            : > "${'$'}S"
            trap 'rm -f "${'$'}S"; exit 0' TERM
            while true; do sleep 1; done
        """.trimIndent(), src)
        fake("tailscale", """
            echo "tailscale ${'$'}*" >> "${'$'}FAKE_CALLS/log"
            case " ${'$'}* " in
              *" up "*)
                for a in "${'$'}@"; do case "${'$'}a" in --authkey=file:*) cat "${'$'}{a#--authkey=file:}" > "${'$'}FAKE_CALLS/authkey-seen";; esac; done
                if [ "${'$'}FAKE_UP_FAIL" = 1 ]; then echo "trying to log in"; echo "backend error: invalid key"; exit 1; fi ;;
              *" ip "*) echo 100.64.0.9 ;;
            esac
            exit 0
        """.trimIndent(), src)
        return src
    }

    private fun running(): Boolean {
        val pid = File(dir, "tailscaled.pid").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: return false
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    @Test fun aWholeSession() {
        val operatorKey = "QAAAAJ+x/abc= jeroen@laptop"
        File(sys, "etc/resolv.conf").writeText("nameserver 8.8.8.8\n")
        adbKeys.writeText("QAAAAOLD old@desk") // no newline at the end
        File(dir, "v-1.0.0").mkdirs()

        // 1. Binaries, cached by version.
        assertEquals("", RemoteHelpScript.values(sh("has", "1.104.7").output)["PRESENT"] ?: "")
        val install = sh("install", "1.104.7", binaries().path)
        assertEquals(install.output, 0, install.exitCode)
        assertTrue(File(dir, "v-1.104.7/tailscaled").canExecute())
        assertFalse(File(dir, "v-1.0.0").exists())
        assertEquals("1", RemoteHelpScript.values(sh("has", "1.104.7").output)["PRESENT"])

        // 2. DNS for the static binary, with /system remounted around the write.
        val dns = sh("dns")
        assertEquals(dns.output, 0, dns.exitCode)
        assertEquals("# kinframe remote help\nnameserver 192.168.1.1\nnameserver 1.1.1.1\n", File(sys, "etc/resolv.conf").readText())
        assertEquals("nameserver 8.8.8.8\n", File(dir, "resolv.conf.orig").readText())
        assertEquals(
            listOf("mount -o remount,rw /dev/block/by-name/system ${sys.path}", "mount -o remount,ro /dev/block/by-name/system ${sys.path}"),
            log().filter { it.startsWith("mount") },
        )

        // 3. tailscaled outlives the script that started it, in user-space mode.
        val start = sh("start", "1.104.7", "rh_01")
        assertEquals(start.output, 0, start.exitCode)
        assertTrue(running())
        assertEquals("rh_01\n", File(dir, "session").readText())
        assertEquals(
            "--tun=userspace-networking --statedir=${dir.path}/state --socket=${dir.path}/ts.sock --port=0",
            File(calls, "tailscaled.args").readText().trim(),
        )
        assertTrue(File(tmp.root, "acct-tasks").readText().isNotBlank()) // left the app's cgroup
        assertEquals("RUNNING=1", sh("start", "1.104.7", "rh_01").output.trim()) // already running: no second one

        // 4. Join with the key from a file; the copy is gone afterwards.
        val keyFile = File(tmp.root, "app/authkey").apply { writeText("secretkey123\n") }
        val up = sh("up", "1.104.7", "https://headscale.appmire.be", "kinframe-36339301", keyFile.path)
        assertEquals(up.output, 0, up.exitCode)
        assertEquals("100.64.0.9", RemoteHelpScript.values(up.output)["IP"])
        assertEquals("secretkey123\n", File(calls, "authkey-seen").readText())
        assertFalse(File(dir, "authkey").exists())
        val upLine = log().first { it.startsWith("tailscale") && " up " in it }
        assertEquals(
            "tailscale --socket=${dir.path}/ts.sock up --login-server=https://headscale.appmire.be --authkey=file:${dir.path}/authkey " +
                "--hostname=kinframe-36339301 --accept-dns=false --accept-routes=false --timeout=60s",
            upLine,
        )

        // 5. The operator's key, then ADB over TCP from a detached restart.
        val adbKey = File(tmp.root, "app/adbkey.pub").apply { writeText("$operatorKey\n") }
        val adb = sh("adb-on", adbKey.path)
        assertEquals(adb.output, 0, adb.exitCode)
        assertEquals("QAAAAOLD old@desk\n$operatorKey\n", adbKeys.readText())
        waitFor("adbd restart") { "start adbd" in log() }
        assertEquals(listOf("setprop service.adb.tcp.port 5555", "stop adbd", "start adbd"), log().filter { it.startsWith("setprop") || it.endsWith("adbd") })
        // Again (an app restart): no second key line, no second adbd restart.
        sh("adb-on", adbKey.path)
        assertEquals("QAAAAOLD old@desk\n$operatorKey\n", adbKeys.readText())
        assertEquals(1, log().count { it == "start adbd" })

        val status = RemoteHelpScript.values(sh("status").output)
        assertEquals("rh_01", status["SESSION"])
        assertEquals("1", status["RUNNING"])

        // 7. End: logout, tailscaled gone, only the operator's key line removed, resolv.conf and ADB as they were.
        val stop = sh("stop")
        assertEquals(stop.output, 0, stop.exitCode)
        assertTrue(log().any { it == "tailscale --socket=${dir.path}/ts.sock logout" })
        waitFor("tailscaled to stop") { !running() }
        assertEquals("QAAAAOLD old@desk\n", adbKeys.readText())
        assertEquals("nameserver 8.8.8.8\n", File(sys, "etc/resolv.conf").readText())
        assertFalse(File(dir, "state").exists())
        assertFalse(File(dir, "session").exists())
        waitFor("adbd back to USB") { log().count { it == "start adbd" } == 2 }
        assertTrue("setprop service.adb.tcp.port -1" in log())
        assertTrue(File(dir, "v-1.104.7/tailscaled").exists()) // the cache stays
        assertEquals(emptyMap<String, String>(), RemoteHelpScript.values(sh("status").output))

        // After the cache period.
        val purge = sh("purge")
        assertEquals(purge.output, 0, purge.exitCode)
        assertFalse(dir.exists())
    }

    @Test fun aKeyThatWasAlreadyAllowedStaysAndNoResolvConfIsLeftBehind() {
        val operatorKey = "QAAAAJ+x/abc= jeroen@laptop"
        adbKeys.writeText("$operatorKey\n")
        sh("install", "1.104.7", binaries().path)
        sh("dns")
        assertTrue(File(sys, "etc/resolv.conf").exists())
        val adbKey = File(tmp.root, "app/adbkey.pub").apply { writeText("$operatorKey\n") }
        sh("adb-on", adbKey.path)
        assertFalse(File(dir, "key-added").exists())
        sh("stop")
        assertEquals("$operatorKey\n", adbKeys.readText())
        assertFalse(File(sys, "etc/resolv.conf").exists()) // there was none before
    }

    @Test fun aNewKeysFileGetsAdbdsOwnerAndMode() {
        sh("install", "1.104.7", binaries().path)
        val adbKey = File(tmp.root, "app/adbkey.pub").apply { writeText("QAAAAJ+x/abc= jeroen@laptop\n") }
        sh("adb-on", adbKey.path)
        assertEquals("QAAAAJ+x/abc= jeroen@laptop\n", adbKeys.readText())
        assertTrue("chown 1000:2000 ${adbKeys.path}" in log())
        sh("stop")
        assertEquals("", adbKeys.readText())
    }

    @Test fun aFailedJoinSaysWhyInItsLastLine() {
        sh("install", "1.104.7", binaries().path)
        sh("start", "1.104.7", "rh_01")
        upFails = true
        val keyFile = File(tmp.root, "app/authkey").apply { writeText("k\n") }
        val up = sh("up", "1.104.7", "https://headscale.appmire.be", "kinframe-36339301", keyFile.path)
        assertNotEquals(0, up.exitCode)
        assertEquals("backend error: invalid key", RemoteHelpScript.values(up.output)["ERROR"])
        assertFalse(File(dir, "authkey").exists())
        sh("stop")
        waitFor("tailscaled to stop") { !running() }
    }

    @Test fun withoutASystemMountDnsFails() {
        File(tmp.root, "mounts").writeText("rootfs / rootfs ro 0 0\n")
        val r = sh("dns")
        assertNotEquals(0, r.exitCode)
        assertTrue(r.output, RemoteHelpScript.values(r.output)["ERROR"]!!.startsWith("no "))
    }

    @Test fun purgeRefusesWhileASessionRuns() {
        sh("install", "1.104.7", binaries().path)
        sh("start", "1.104.7", "rh_01")
        assertNotEquals(0, sh("purge").exitCode)
        assertTrue(dir.exists())
        sh("stop")
        waitFor("tailscaled to stop") { !running() }
    }

    @Test fun quotingSurvivesTheShell() {
        assertEquals("'it'\\''s'", RemoteHelpScript.quote("it's"))
        val line = RemoteHelpScript.commandLine("/data/user/0/app/files/rh.sh", "/data/local/tmp/kinframe-ts", "up", "1.104.7", "")
        assertEquals(
            "mkdir -p '/data/local/tmp/kinframe-ts' && chmod 700 '/data/local/tmp/kinframe-ts' && cp '/data/user/0/app/files/rh.sh' " +
                "'/data/local/tmp/kinframe-ts'/rh.sh && chmod 700 '/data/local/tmp/kinframe-ts'/rh.sh && sh '/data/local/tmp/kinframe-ts/rh.sh' up '1.104.7' ''",
            line,
        )
    }
}
