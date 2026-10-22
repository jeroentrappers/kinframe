package app.kinframe.frame.update

import app.kinframe.core.api.FrameUpdate
import kotlin.random.Random

/**
 * The decisions of the self-update (docs/api/README.md §5.7, product spec 4.10), kept free of Android so they can be
 * unit-tested: when to check, when an install may interrupt the frame, whether a downloaded APK is acceptable, and
 * when to roll back.
 */
object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 24 * 3600_000L
    const val MAX_JITTER_MS = 90 * 60_000L
    /** First check after the app starts (boot, update) is between these, so a fleet doesn't check at the same time. */
    const val FIRST_CHECK_MIN_MS = 5 * 60_000L
    const val IDLE_BEFORE_INSTALL_MS = 15 * 60_000L
    const val ROLLBACK_WINDOW_MS = 10 * 60_000L
    /** An install that neither finished nor failed after this long is given up. */
    const val INSTALL_TIMEOUT_MS = 10 * 60_000L
    const val RETRY_AFTER_FAILURE_MS = 3600_000L

    const val PACKAGE = "app.kinframe.frame"

    fun jitter(random: Random = Random.Default): Long = random.nextLong(0, MAX_JITTER_MS + 1)

    /** Next scheduled check after a check at [checkedAt]. */
    fun nextCheckAfter(checkedAt: Long, jitterMs: Long): Long = checkedAt + CHECK_INTERVAL_MS + jitterMs.coerceIn(0, MAX_JITTER_MS)

    /** First check after an app start when none is scheduled (or the schedule is implausible, e.g. clock jumps). */
    fun firstCheckAt(now: Long, scheduled: Long, jitterMs: Long): Long {
        val earliest = now + FIRST_CHECK_MIN_MS
        val latest = now + CHECK_INTERVAL_MS + MAX_JITTER_MS
        return if (scheduled in earliest..latest) scheduled else earliest + jitterMs.coerceIn(0, MAX_JITTER_MS)
    }

    enum class Block { None, RecentTouch, ScreenOffByHand }

    /**
     * Whether the frame may be interrupted now (an automatic check or an install restarts the app).
     * [lastTouchElapsed] is `SystemClock.elapsedRealtime()` of the last touch, 0 when never touched since start.
     * Something the person asked for on the frame itself ([userInitiated]) is never blocked.
     */
    fun block(nowElapsed: Long, lastTouchElapsed: Long, screenOffByHand: Boolean, userInitiated: Boolean): Block = when {
        userInitiated -> Block.None
        screenOffByHand -> Block.ScreenOffByHand
        lastTouchElapsed > 0 && nowElapsed - lastTouchElapsed < IDLE_BEFORE_INSTALL_MS -> Block.RecentTouch
        else -> Block.None
    }

    /** Null when [u] may be installed over [installedCode] on [sdk]; otherwise why not. */
    fun rejectOffer(u: FrameUpdate, installedCode: Int, sdk: Int, skipped: Collection<Int>): String? = when {
        u.versionCode <= installedCode -> "not_newer"
        u.versionCode in skipped -> "skipped"
        sdk < u.minSdk -> "min_sdk"
        !Regex("^[0-9a-fA-F]{64}$").matches(u.sha256) -> "bad_sha256"
        u.size <= 0 -> "bad_size"
        else -> null
    }

    /** What PackageManager reports about a downloaded APK (signers as SHA-256 of the certificate bytes). */
    data class ArchiveInfo(val packageName: String, val versionCode: Int, val signers: Set<String>)

    /**
     * Checks a downloaded APK before installing; returns null when it is good, else a short error code for the
     * heartbeat's `last_update_error`.
     */
    fun verifyDownload(
        offer: FrameUpdate,
        actualSha256: String,
        actualSize: Long,
        archive: ArchiveInfo?,
        installedSigners: Set<String>,
        installedCode: Int,
    ): String? = when {
        actualSize != offer.size -> "size_mismatch"
        !actualSha256.equals(offer.sha256, ignoreCase = true) -> "sha256_mismatch"
        archive == null -> "not_an_apk"
        archive.packageName != PACKAGE -> "wrong_package"
        archive.versionCode != offer.versionCode -> "version_mismatch"
        archive.versionCode <= installedCode -> "not_newer"
        installedSigners.isEmpty() || archive.signers != installedSigners -> "signature_mismatch"
        else -> null
    }

    /**
     * Roll back when the crash-loop guard tripped while this version is the one we just installed, within
     * [ROLLBACK_WINDOW_MS] of its first start, and a copy of the previous APK exists.
     */
    fun shouldRollBack(record: UpdateRecord, runningCode: Int, now: Long, inCrashLoop: Boolean): Boolean =
        inCrashLoop &&
            record.installedCode == runningCode &&
            record.installedAt > 0 && now - record.installedAt in 0..ROLLBACK_WINDOW_MS &&
            record.previousApk != null && record.previousCode in 1 until runningCode &&
            record.pending == null

    /** Outcome of an install started by the previous process, judged by the version that is running now. */
    sealed interface Resolution {
        data object StillRunning : Resolution
        data class Updated(val versionName: String) : Resolution
        data class Failed(val error: String) : Resolution
        data class RolledBack(val badCode: Int, val error: String) : Resolution
    }

    /**
     * [installLog] is the root installer's output (null without root or when missing). The install script appends
     * `watchdog_rollback` when the new version never started and it reinstalled the previous one.
     */
    fun resolvePending(p: PendingInstall, runningCode: Int, now: Long, installLog: String?): Resolution {
        val log = installLog.orEmpty()
        return if (!p.rollback) {
            when {
                runningCode == p.targetCode -> Resolution.Updated(p.targetName)
                log.contains("watchdog_rollback") -> Resolution.RolledBack(p.targetCode, "not_started ${p.targetCode}")
                log.contains("Failure") -> Resolution.Failed("install_failed: " + failureLine(log))
                now - p.startedAt !in 0..INSTALL_TIMEOUT_MS -> Resolution.Failed("install_timeout")
                else -> Resolution.StillRunning
            }
        } else {
            when {
                runningCode == p.previousCode -> Resolution.RolledBack(p.targetCode, "crash_loop ${p.targetCode}")
                log.contains("Failure") -> Resolution.Failed("rollback_failed: " + failureLine(log))
                now - p.startedAt !in 0..INSTALL_TIMEOUT_MS -> Resolution.Failed("rollback_failed")
                else -> Resolution.StillRunning
            }
        }
    }

    private fun failureLine(log: String): String =
        log.lineSequence().firstOrNull { it.contains("Failure") }?.trim()?.take(120) ?: "unknown"
}
