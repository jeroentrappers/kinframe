package app.kinframe.frame.frameo

import android.content.Context
import android.util.Log
import app.kinframe.frame.system.RootShell
import io.realm.DynamicRealm
import io.realm.DynamicRealmObject
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmFieldType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads the old app's database (a Realm file, docs/spec/frameo-import.md) from a private **copy**: the original is
 * never opened, locked or written. The copy is opened with the official Realm library in dynamic mode (no model
 * classes) and deleted afterwards. Returns null when the database can't be read; the import then uses the files alone.
 */
class FrameoDatabase(private val context: Context, private val root: RootShell, private val source: String = SOURCE) {

    suspend fun read(): Map<String, FrameoEntry>? = withContext(Dispatchers.IO) {
        val dir = File(context.noBackupFilesDir, "frameo-db")
        try {
            dir.deleteRecursively()
            dir.mkdirs()
            val copy = File(dir, FILE_NAME)
            if (!copyDatabase(copy)) return@withContext null
            readCopy(context, dir)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError (no native library for this ABI) included: never take the frame down.
            Log.w(TAG, "can't read the Frameo database", e)
            null
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Readable as is (tests, emulators), else copied as root and handed to this app's user. */
    private suspend fun copyDatabase(dst: File): Boolean {
        val src = File(source)
        if (src.canRead()) {
            src.copyTo(dst, overwrite = true)
            return dst.length() > 0
        }
        if (!root.isAvailable()) {
            Log.i(TAG, "no root: importing Frameo's files without their details")
            return false
        }
        val uid = android.os.Process.myUid()
        val r = root.run("cp ${q(source)} ${q(dst.path)} && chown $uid:$uid ${q(dst.path)} && chmod 600 ${q(dst.path)}", timeoutMs = 30_000)
        if (r.exitCode != 0 || !dst.canRead() || dst.length() == 0L) {
            Log.w(TAG, "copying the Frameo database failed: ${r.exitCode} ${r.output.trim().take(200)}")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "KinFrameo"
        const val SOURCE = "/data/data/net.frameo.frame/files/default.realm"
        private const val FILE_NAME = "default.realm"

        /** Single quotes for the root shell (paths here never contain one, but be safe). */
        private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /**
         * Opens `default.realm` in [dir] and reads every `Media` object. Realm checks the stored schema version only
         * against a non-zero requested one, but a library that insists gets the version from its error and a retry.
         */
        fun readCopy(context: Context, dir: File): Map<String, FrameoEntry> {
            Realm.init(context)
            fun config(version: Long) = RealmConfiguration.Builder().directory(dir).name(FILE_NAME).schemaVersion(version)
                .allowQueriesOnUiThread(true).allowWritesOnUiThread(true).build()
            val realm = try {
                DynamicRealm.getInstance(config(0))
            } catch (e: IllegalArgumentException) {
                val v = Regex("""version (?:is )?(\d+)""").findAll(e.message.orEmpty()).map { it.groupValues[1].toLong() }.maxOrNull() ?: throw e
                Log.i(TAG, "reopening with schema version $v")
                DynamicRealm.getInstance(config(v))
            }
            realm.use { r ->
                if (!r.schema.contains(FrameoMapping.MEDIA_CLASS)) return emptyMap()
                val out = LinkedHashMap<String, FrameoEntry>()
                for (o in r.where(FrameoMapping.MEDIA_CLASS).findAll()) {
                    FrameoMapping.entryOf(RealmFields(o))?.let { out[it.fileName] = it }
                }
                Log.i(TAG, "read ${out.size} entries from the Frameo database")
                return out
            }
        }
    }
}

/** [FieldSource] over a dynamic Realm object; a missing field, a null or a field of another type reads as null. */
private class RealmFields(private val o: DynamicRealmObject) : FieldSource {
    private fun <T> field(name: String, vararg types: RealmFieldType, get: () -> T): T? =
        if (o.hasField(name) && o.getFieldType(name) in types && !o.isNull(name)) runCatching(get).getOrNull() else null

    override fun string(name: String): String? = field(name, RealmFieldType.STRING) { o.getString(name) }
    override fun epochMs(name: String): Long? = field(name, RealmFieldType.DATE) { o.getDate(name).time }
    override fun bool(name: String): Boolean? = field(name, RealmFieldType.BOOLEAN) { o.getBoolean(name) }
    override fun long(name: String): Long? = field(name, RealmFieldType.INTEGER) { o.getLong(name) }
    override fun double(name: String): Double? = when {
        !o.hasField(name) || o.isNull(name) -> null
        o.getFieldType(name) == RealmFieldType.FLOAT -> o.getFloat(name).toDouble()
        o.getFieldType(name) == RealmFieldType.DOUBLE -> o.getDouble(name)
        else -> null
    }
    override fun obj(name: String): FieldSource? = field(name, RealmFieldType.OBJECT) { o.getObject(name) }?.let(::RealmFields)
}
