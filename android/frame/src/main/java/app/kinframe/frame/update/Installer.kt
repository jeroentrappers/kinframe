package app.kinframe.frame.update

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.kinframe.frame.MainActivity
import app.kinframe.frame.system.RootShell
import java.io.File
import java.security.MessageDigest

/**
 * Android side of installing an update: package inspection, the root installer script (Android 6 compatible),
 * the system installer fallback, and the relaunch alarm.
 */
class Installer(private val context: Context, private val root: RootShell, private val dir: File) {
    private val pm: PackageManager = context.packageManager

    val logFile = File(dir, "install.log")
    private val script = File(dir, "install.sh")

    /** Marker the new version creates when it starts; the root script's watchdog waits for it. */
    fun startedMarker(versionCode: Int) = File(dir, "started-$versionCode")

    fun readLog(): String? = runCatching { if (logFile.exists()) logFile.readText() else null }.getOrNull()

    // ------------------------------------------------------------------ inspection

    @SuppressLint("PackageManagerGetSignatures") // compared against our own installed signer, not used for trust in others
    @Suppress("DEPRECATION")
    fun archiveInfo(apk: File): UpdatePolicy.ArchiveInfo? {
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES) ?: return null
        return UpdatePolicy.ArchiveInfo(info.packageName, versionCode(info), signerDigests(info))
    }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    fun installedSigners(): Set<String> = runCatching {
        signerDigests(pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES))
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> = info.signatures.orEmpty().map { sig ->
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet()

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode

    /** Copies the installed APK (for rollback). */
    fun keepInstalledApk(): File {
        val src = File(context.applicationInfo.sourceDir)
        val dst = File(dir, "previous.apk")
        dir.mkdirs()
        src.copyTo(dst, overwrite = true)
        return dst
    }

    // ------------------------------------------------------------------ root

    /**
     * Runs the installer script as root, detached so that it outlives this process: the package manager kills the
     * app (and everything in its process group) while replacing it.
     *
     * - `install`: `pm install -r` the new APK, bring the frame back, then watch for [startedMarker]. If the new
     *   version never starts within 3 minutes, reinstall [previous] with `-d` and log `watchdog_rollback`.
     * - `rollback`: `pm install -r -d` [apk] (the kept previous version) and bring the frame back.
     */
    suspend fun runRootScript(mode: String, apk: File, previous: File?, marker: File?) {
        dir.mkdirs()
        script.writeText(SCRIPT)
        logFile.delete()
        val args = listOf(mode, apk.path, previous?.path ?: "", marker?.path ?: "", logFile.path, Process.myUid().toString(), context.packageName)
            .joinToString(" ") { "'" + it.replace("'", "") + "'" }
        root.run("sh '${script.path}' $args </dev/null >/dev/null 2>&1 &", timeoutMs = 5_000)
    }

    // ------------------------------------------------------------------ without root

    fun canRequestInstalls(): Boolean = Build.VERSION.SDK_INT < 26 || pm.canRequestPackageInstalls()

    /** Opens the system installer for [apk]; on Android 8+ first the "install unknown apps" setting if needed. */
    fun launchSystemInstaller(apk: File): Boolean = runCatching {
        if (!canRequestInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return@runCatching true
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        } else {
            // Android 6/7.0: the installer can't read our private files and has no content:// support; app-specific
            // external storage is readable by it.
            val ext = context.externalCacheDir ?: return@runCatching false
            val copy = File(ext, apk.name)
            apk.copyTo(copy, overwrite = true)
            Uri.fromFile(copy)
        }
        @Suppress("DEPRECATION")
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true),
        )
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ relaunch

    private fun relaunchIntent(): PendingIntent = PendingIntent.getActivity(
        context, 7,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Safety net in case neither the script nor MY_PACKAGE_REPLACED brings the frame back. Alarms survive a replace. */
    fun scheduleRelaunch(delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, relaunchIntent())
    }

    fun cancelRelaunch() {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(relaunchIntent())
    }

    companion object {
        /**
         * Root installer. Arguments: mode apk previous marker log uid package. `KINFRAME_TMP` and `KINFRAME_WATCHDOG_S`
         * exist for tests only (defaults: /data/local/tmp, 180 s).
         */
        val SCRIPT = """
            |MODE="${'$'}1"; APK="${'$'}2"; PREV="${'$'}3"; MARK="${'$'}4"; LOG="${'$'}5"; OWNER="${'$'}6"; PKG="${'$'}7"
            |# Leave the app's process group: the package manager kills it while replacing the app.
            |echo ${'$'}${'$'} > /acct/tasks 2>/dev/null
            |WAIT="${'$'}{KINFRAME_WATCHDOG_S:-180}"
            |T="${'$'}{KINFRAME_TMP:-/data/local/tmp}/kinframe-update.apk"
            |log() { echo "${'$'}*" >> "${'$'}LOG"; chown "${'$'}OWNER:${'$'}OWNER" "${'$'}LOG" 2>/dev/null; chmod 644 "${'$'}LOG" 2>/dev/null; }
            |inst() {
            |  cp "${'$'}1" "${'$'}T" && chmod 644 "${'$'}T" || { log "Failure [copy failed]"; return 1; }
            |  OUT=${'$'}(pm install -r ${'$'}2 "${'$'}T" 2>&1); rm -f "${'$'}T"; log "${'$'}OUT"
            |  case "${'$'}OUT" in *Success*) return 0;; esac
            |  case "${'$'}OUT" in *Failure*) ;; *) log "Failure [no result]";; esac
            |  return 1
            |}
            |relaunch() { sleep 2; am start -n "${'$'}PKG/.MainActivity" >/dev/null 2>&1; }
            |log "start ${'$'}MODE"
            |if [ "${'$'}MODE" = rollback ]; then inst "${'$'}APK" -d; relaunch; exit 0; fi
            |inst "${'$'}APK" "" || exit 0
            |relaunch
            |[ -n "${'$'}PREV" ] && [ -n "${'$'}MARK" ] || exit 0
            |i=0
            |while [ ${'$'}i -lt "${'$'}WAIT" ]; do [ -e "${'$'}MARK" ] && exit 0; sleep 1; i=${'$'}((i+1)); done
            |log "watchdog_rollback"
            |inst "${'$'}PREV" -d
            |relaunch
            |""".trimMargin()
    }
}
