package app.kinframe.frame.update

import app.kinframe.core.api.KinJson
import app.kinframe.core.util.AtomicFile
import kotlinx.serialization.Serializable
import java.io.File

/** An install handed to the root installer or the system installer; survives the process being killed by it. */
@Serializable
data class PendingInstall(
    val targetCode: Int,
    val targetName: String,
    val previousCode: Int,
    val previousName: String,
    val startedAt: Long,
    /** True when this "install" reinstalls [previousCode] because [targetCode] crash-looped. */
    val rollback: Boolean = false,
    val viaRoot: Boolean = true,
)

/**
 * Persistent self-update state. Read by both the old and the new app version, so fields are only ever added, and
 * unknown ones are ignored ([KinJson]).
 */
@Serializable
data class UpdateRecord(
    /** Heartbeat `update_state`. */
    val state: String = "idle",
    val lastError: String? = null,
    val lastCheckAt: Long = 0,
    val nextCheckAt: Long = 0,
    /** Version codes that were rolled back; never offered again (sent as `skip`). */
    val skipped: List<Int> = emptyList(),
    val pending: PendingInstall? = null,
    /** The version installed by the last successful update and when it first started (rollback window). */
    val installedCode: Int = 0,
    val installedAt: Long = 0,
    /** Copy of the APK that was replaced, kept for rollback during the window. */
    val previousApk: String? = null,
    val previousCode: Int = 0,
    /** Version name to announce ("Updated to x.y") once the frame shows something. */
    val announce: String? = null,
)

class UpdateStore(private val file: File) {
    @Volatile var record: UpdateRecord = load()
        private set

    private fun load(): UpdateRecord = AtomicFile.readTextOrNull(file)
        ?.let { runCatching { KinJson.decodeFromString(UpdateRecord.serializer(), it) }.getOrNull() }
        ?: UpdateRecord()

    @Synchronized
    fun update(f: (UpdateRecord) -> UpdateRecord): UpdateRecord {
        val next = f(record)
        if (next != record) {
            record = next
            runCatching { AtomicFile.writeText(file, KinJson.encodeToString(UpdateRecord.serializer(), next)) }
        }
        return next
    }
}
