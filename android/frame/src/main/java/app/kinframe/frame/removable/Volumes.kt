package app.kinframe.frame.removable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/** A mounted SD card or USB stick. */
data class Volume(
    /** vold volume id when known (`public:179,65`), used to eject with root. */
    val id: String?,
    val uuid: String?,
    /** What apps see, e.g. `/storage/1A2B-3C4D`. */
    val path: File,
    /** The raw vfat mount root can write to (`/mnt/media_rw/1A2B-3C4D`), when it exists. */
    val rawPath: File?,
    val kind: Kind,
    /** File system label (e.g. `KINFRAME`), when the platform tells. */
    val label: String?,
    val fsType: String?,
    val description: String?,
) {
    enum class Kind { SdCard, Usb }

    val exportDir: File get() = File(path, EXPORT_DIR)
    val backupDir: File get() = File(path, BACKUP_DIR)

    /** Automatic backup targets: a stick labelled KINFRAME, or one that already has a `kinframe-backup/` folder. */
    fun isBackupTarget(): Boolean = label?.trim()?.equals(BACKUP_LABEL, ignoreCase = true) == true || backupDir.isDirectory

    fun freeBytes(): Long = runCatching { StatFs(path.absolutePath).availableBytes }.getOrDefault(-1L)
    fun totalBytes(): Long = runCatching { StatFs(path.absolutePath).totalBytes }.getOrDefault(-1L)

    companion object {
        const val EXPORT_DIR = "kinframe-export"
        const val BACKUP_DIR = "kinframe-backup"
        const val BACKUP_LABEL = "KINFRAME"

        /** SD or USB from vold's description or path ("SD card", "USB drive", `/storage/usbotg`, `usb_storage`). */
        fun kindOf(description: String?, path: String, diskIsUsb: Boolean?): Kind = when {
            diskIsUsb == true -> Kind.Usb
            diskIsUsb == false -> Kind.SdCard
            description?.lowercase(Locale.ROOT)?.contains("usb") == true -> Kind.Usb
            path.lowercase(Locale.ROOT).contains("usb") -> Kind.Usb
            else -> Kind.SdCard
        }

        /** The volume root for an app-specific directory such as `/storage/X/Android/data/<pkg>/files`. */
        fun rootOfAppDir(dir: File): File? {
            val p = dir.absolutePath
            val i = p.indexOf("/Android/data/")
            return if (i > 0) File(p.substring(0, i)) else null
        }
    }
}

/**
 * Finds mounted removable volumes and follows mount/unmount broadcasts (Android 6 has no StorageVolume callback).
 *
 * Detection, most detailed first, each wrapped so a missing hidden API never crashes the frame:
 *  1. `StorageManager.getVolumes()` (hidden, API 23+): `VolumeInfo` with type, state, fsLabel, fsType and the disk's
 *     SD/USB flags. This is what vold on the Denver frame reports for `sdcard1` and the USB host ports.
 *  2. `StorageManager.getStorageVolumes()` (API 24+) or the hidden `getVolumeList()` (API 23): removable volumes.
 *  3. `Context.getExternalFilesDirs()`: every entry after the first lives on a removable volume.
 */
class VolumeMonitor(private val context: Context) {
    private val _volumes = MutableStateFlow<List<Volume>>(emptyList())
    val volumes: StateFlow<List<Volume>> = _volumes
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        refresh()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                Log.i(TAG, "${intent.action} ${intent.data}")
                refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        runCatching {
            // System broadcasts: exported receivers are fine (and required on API 33+ for these to arrive).
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(r, filter)
            receiver = r
        }.onFailure { Log.w(TAG, "mount broadcasts unavailable", it) }
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    fun refresh() {
        val found = runCatching { detect() }.onFailure { Log.w(TAG, "volume detection failed", it) }.getOrDefault(emptyList())
        if (found != _volumes.value) {
            Log.i(TAG, "removable volumes: ${found.map { "${it.kind} ${it.path} label=${it.label} fs=${it.fsType}" }}")
            _volumes.value = found
        }
    }

    /** For tests and previews. */
    fun set(volumes: List<Volume>) { _volumes.value = volumes }

    private fun detect(): List<Volume> {
        val viaInfo = runCatching { fromVolumeInfos() }.getOrNull().orEmpty()
        if (viaInfo.isNotEmpty()) return viaInfo
        val viaStorage = runCatching { fromStorageVolumes() }.getOrNull().orEmpty()
        if (viaStorage.isNotEmpty()) return viaStorage
        return fromAppDirs()
    }

    private val sm get() = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager

    /** Hidden `List<VolumeInfo> getVolumes()`: TYPE_PUBLIC (0) volumes in STATE_MOUNTED (2) or MOUNTED_READ_ONLY (3). */
    private fun fromVolumeInfos(): List<Volume> {
        val list = sm.javaClass.getMethod("getVolumes").invoke(sm) as? List<*> ?: return emptyList()
        return list.mapNotNull { vi ->
            vi ?: return@mapNotNull null
            val cls = vi.javaClass
            fun field(name: String): Any? = runCatching { cls.getField(name).get(vi) }.getOrNull()
                ?: runCatching { cls.getDeclaredField(name).apply { isAccessible = true }.get(vi) }.getOrNull()
            val type = field("type") as? Int ?: return@mapNotNull null
            val state = field("state") as? Int ?: return@mapNotNull null
            if (type != 0 || (state != 2 && state != 3)) return@mapNotNull null
            val path = (field("path") as? String)?.let(::File)
                ?: runCatching { cls.getMethod("getPath").invoke(vi) as? File }.getOrNull() ?: return@mapNotNull null
            val disk = runCatching { cls.getMethod("getDisk").invoke(vi) }.getOrNull()
            val isUsb = disk?.let { d -> runCatching { d.javaClass.getMethod("isUsb").invoke(d) as Boolean }.getOrNull() }
            val desc = runCatching { cls.getMethod("getDescription").invoke(vi) as? String }.getOrNull()
                ?: disk?.let { d -> runCatching { d.javaClass.getMethod("getDescription").invoke(d) as? String }.getOrNull() }
            val fsUuid = field("fsUuid") as? String
            val raw = (field("internalPath") as? String)?.let(::File) ?: fsUuid?.let { File("/mnt/media_rw/$it") }
            Volume(
                id = field("id") as? String, uuid = fsUuid, path = path, rawPath = raw?.takeIf { it.exists() },
                kind = Volume.kindOf(desc, path.path, isUsb), label = (field("fsLabel") as? String)?.takeIf { it.isNotBlank() },
                fsType = field("fsType") as? String, description = desc,
            )
        }.filter { it.path.isDirectory }
    }

    private fun fromStorageVolumes(): List<Volume> {
        val vols: List<Any> = if (Build.VERSION.SDK_INT >= 24) sm.storageVolumes
        else (sm.javaClass.getMethod("getVolumeList").invoke(sm) as Array<*>).filterNotNull()
        return vols.mapNotNull { v ->
            val cls = v.javaClass
            val removable = runCatching { cls.getMethod("isRemovable").invoke(v) as Boolean }.getOrDefault(false)
            val primary = runCatching { cls.getMethod("isPrimary").invoke(v) as Boolean }.getOrDefault(false)
            val state = runCatching { cls.getMethod("getState").invoke(v) as String }.getOrNull()
            if (!removable || primary || (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY)) return@mapNotNull null
            val path = runCatching { cls.getMethod("getPathFile").invoke(v) as File }.getOrNull()
                ?: runCatching { File(cls.getMethod("getPath").invoke(v) as String) }.getOrNull()
                ?: (if (Build.VERSION.SDK_INT >= 30) (v as android.os.storage.StorageVolume).directory else null)
                ?: return@mapNotNull null
            val uuid = runCatching { cls.getMethod("getUuid").invoke(v) as? String }.getOrNull()
            val desc = runCatching { cls.getMethod("getDescription", Context::class.java).invoke(v, context) as? String }.getOrNull()
            val label = runCatching { cls.getMethod("getUserLabel").invoke(v) as? String }.getOrNull()
            Volume(
                id = runCatching { cls.getMethod("getId").invoke(v) as? String }.getOrNull(), uuid = uuid, path = path,
                rawPath = uuid?.let { File("/mnt/media_rw/$it") }?.takeIf { it.exists() },
                kind = Volume.kindOf(desc, path.path, null), label = label?.takeIf { it != desc }, fsType = null, description = desc,
            )
        }.filter { it.path.isDirectory }
    }

    private fun fromAppDirs(): List<Volume> = context.getExternalFilesDirs(null).drop(1).filterNotNull().mapNotNull { dir ->
        val root = Volume.rootOfAppDir(dir) ?: return@mapNotNull null
        if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) return@mapNotNull null
        Volume(null, root.name, root, File("/mnt/media_rw/${root.name}").takeIf { it.exists() }, Volume.kindOf(null, root.path, null), null, null, null)
    }

    companion object { private const val TAG = "KinVolumes" }
}
