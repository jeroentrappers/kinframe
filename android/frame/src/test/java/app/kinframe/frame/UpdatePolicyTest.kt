package app.kinframe.frame

import app.kinframe.core.api.FrameUpdate
import app.kinframe.frame.update.PendingInstall
import app.kinframe.frame.update.UpdatePolicy
import app.kinframe.frame.update.UpdatePolicy.Block
import app.kinframe.frame.update.UpdatePolicy.Resolution
import app.kinframe.frame.update.UpdateRecord
import app.kinframe.frame.update.UpdateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class UpdatePolicyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val min = 60_000L
    private val hour = 60 * min
    private val sha = "ab".repeat(32)
    private fun offer(code: Int = 20099, minSdk: Int = 23, size: Long = 1000, sha256: String = sha) =
        FrameUpdate("0.2.0", code, minSdk, "/v1/device/update/apk/$code", sha256, size)

    // ------------------------------------------------------------------ timing

    @Test
    fun checksEveryDayWithBoundedJitter() {
        val r = Random(7)
        repeat(1000) {
            val j = UpdatePolicy.jitter(r)
            assertTrue(j in 0..90 * min)
            val next = UpdatePolicy.nextCheckAfter(1_000, j)
            assertTrue(next in 1_000 + 24 * hour..1_000 + 24 * hour + 90 * min)
        }
        assertEquals(1_000 + 24 * hour + 90 * min, UpdatePolicy.nextCheckAfter(1_000, 10 * hour)) // clamped
    }

    @Test
    fun firstCheckAfterStartIsDelayedButKeepsAPlausibleSchedule() {
        val now = 1_000_000_000L
        // Nothing scheduled, or overdue (frame was off): 5 minutes plus jitter from now.
        assertEquals(now + 5 * min + 30 * min, UpdatePolicy.firstCheckAt(now, 0, 30 * min))
        assertEquals(now + 5 * min, UpdatePolicy.firstCheckAt(now, now - hour, 0))
        // A schedule within the next day is kept.
        assertEquals(now + 7 * hour, UpdatePolicy.firstCheckAt(now, now + 7 * hour, 0))
        // A schedule far in the future (clock jumped back) is replaced.
        assertEquals(now + 5 * min, UpdatePolicy.firstCheckAt(now, now + 30 * 24 * hour, 0))
    }

    @Test
    fun installWaitsForAQuietFrame() {
        val now = 10 * hour
        assertEquals(Block.RecentTouch, UpdatePolicy.block(now, now - 14 * min, false, false))
        assertEquals(Block.None, UpdatePolicy.block(now, now - 15 * min, false, false))
        assertEquals(Block.None, UpdatePolicy.block(now, 0, false, false)) // never touched since start
        assertEquals(Block.ScreenOffByHand, UpdatePolicy.block(now, 0, true, false))
        // The person asked for it on the frame: go.
        assertEquals(Block.None, UpdatePolicy.block(now, now - 1_000, true, true))
    }

    // ------------------------------------------------------------------ offers and verification

    @Test
    fun offersMustBeNewerAndSuitable() {
        assertNull(UpdatePolicy.rejectOffer(offer(), 10099, 23, emptyList()))
        assertEquals("not_newer", UpdatePolicy.rejectOffer(offer(code = 10099), 10099, 23, emptyList()))
        assertEquals("not_newer", UpdatePolicy.rejectOffer(offer(code = 5), 10099, 23, emptyList()))
        assertEquals("skipped", UpdatePolicy.rejectOffer(offer(), 10099, 23, listOf(20099)))
        assertEquals("min_sdk", UpdatePolicy.rejectOffer(offer(minSdk = 26), 10099, 23, emptyList()))
        assertEquals("bad_sha256", UpdatePolicy.rejectOffer(offer(sha256 = "xyz"), 10099, 23, emptyList()))
    }

    @Test
    fun downloadsAreVerifiedBeforeInstalling() {
        val signer = setOf("cert-a")
        val good = UpdatePolicy.ArchiveInfo("app.kinframe.frame", 20099, signer)
        fun v(actualSha: String = sha, size: Long = 1000, archive: UpdatePolicy.ArchiveInfo? = good, installed: Set<String> = signer, code: Int = 10099) =
            UpdatePolicy.verifyDownload(offer(), actualSha, size, archive, installed, code)
        assertNull(v())
        assertNull(v(actualSha = sha.uppercase()))
        assertEquals("size_mismatch", v(size = 999))
        assertEquals("sha256_mismatch", v(actualSha = "cd".repeat(32)))
        assertEquals("not_an_apk", v(archive = null))
        assertEquals("wrong_package", v(archive = good.copy(packageName = "net.frameo.frame")))
        assertEquals("version_mismatch", v(archive = good.copy(versionCode = 20100)))
        assertEquals("not_newer", v(code = 20099))
        assertEquals("signature_mismatch", v(archive = good.copy(signers = setOf("cert-b"))))
        assertEquals("signature_mismatch", v(archive = good.copy(signers = setOf("cert-a", "cert-b"))))
        assertEquals("signature_mismatch", v(installed = emptySet()))
    }

    // ------------------------------------------------------------------ install outcome and rollback

    private val start = 5_000_000L
    private val install = PendingInstall(20099, "0.2.0", 10099, "0.1.0", start)

    @Test
    fun pendingInstallIsJudgedByTheVersionThatRuns() {
        assertEquals(Resolution.Updated("0.2.0"), UpdatePolicy.resolvePending(install, 20099, start + min, "Success"))
        assertEquals(Resolution.StillRunning, UpdatePolicy.resolvePending(install, 10099, start + min, "start install"))
        val failed = UpdatePolicy.resolvePending(install, 10099, start + min, "start install\nFailure [INSTALL_FAILED_INSUFFICIENT_STORAGE]")
        assertEquals(Resolution.Failed("install_failed: Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"), failed)
        assertEquals(Resolution.Failed("install_timeout"), UpdatePolicy.resolvePending(install, 10099, start + 11 * min, null))
        // The script's watchdog put the old version back because the new one never started.
        assertEquals(
            Resolution.RolledBack(20099, "not_started 20099"),
            UpdatePolicy.resolvePending(install, 10099, start + 4 * min, "Success\nwatchdog_rollback\nSuccess"),
        )
    }

    @Test
    fun rollbackIsJudgedToo() {
        val rb = PendingInstall(20099, "0.2.0", 10099, "", start, rollback = true)
        assertEquals(Resolution.RolledBack(20099, "crash_loop 20099"), UpdatePolicy.resolvePending(rb, 10099, start + min, "Success"))
        assertEquals(Resolution.StillRunning, UpdatePolicy.resolvePending(rb, 20099, start + min, null))
        assertTrue(UpdatePolicy.resolvePending(rb, 20099, start + min, "Failure [x]") is Resolution.Failed)
    }

    @Test
    fun rollsBackOnlyForACrashLoopRightAfterAnUpdate() {
        val r = UpdateRecord(installedCode = 20099, installedAt = start, previousApk = "/data/prev.apk", previousCode = 10099)
        assertTrue(UpdatePolicy.shouldRollBack(r, 20099, start + 3 * min, inCrashLoop = true))
        assertFalse(UpdatePolicy.shouldRollBack(r, 20099, start + 3 * min, inCrashLoop = false))
        assertFalse("after the window", UpdatePolicy.shouldRollBack(r, 20099, start + 11 * min, inCrashLoop = true))
        assertFalse("other version running", UpdatePolicy.shouldRollBack(r, 10099, start + 3 * min, inCrashLoop = true))
        assertFalse("nothing to go back to", UpdatePolicy.shouldRollBack(r.copy(previousApk = null), 20099, start + min, true))
        assertFalse("already rolling back", UpdatePolicy.shouldRollBack(r.copy(pending = install.copy(rollback = true)), 20099, start + min, true))
        assertFalse("never installed by us", UpdatePolicy.shouldRollBack(UpdateRecord(), 20099, start + min, true))
    }

    @Test
    fun storeSurvivesRestartsAndIgnoresUnknownFields() {
        val f = tmp.newFile("update.json")
        val a = UpdateStore(f)
        a.update { it.copy(state = "installing", pending = install, skipped = listOf(30001)) }
        val b = UpdateStore(f)
        assertEquals("installing", b.record.state)
        assertEquals(install, b.record.pending)
        assertEquals(listOf(30001), b.record.skipped)
        f.writeText("""{"state":"updated","from_a_newer_version":{"x":1}}""")
        assertEquals("updated", UpdateStore(f).record.state)
        f.writeText("garbage")
        assertEquals(UpdateRecord(), UpdateStore(f).record)
    }
}
