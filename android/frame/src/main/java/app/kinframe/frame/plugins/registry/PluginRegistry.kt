package app.kinframe.frame.plugins.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.decodeBase64
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream

/**
 * The community plugin registry as the frame sees it (docs/plugins.md §7, §7.1): an index signed with the registry's
 * key, checked here with the key built into the app, and the bundles it lists, checked against the hashes in it. The
 * server only relays both; nothing it could change would make the frame run other code.
 */
object PluginRegistry {
    const val MAX_BUNDLE = 512 * 1024
    const val MAX_UNPACKED = 1024 * 1024

    @Serializable
    data class Index(
        val format: Int,
        val sequence: Long,
        val plugins: List<Plugin> = emptyList(),
        val revoked: List<Revoked> = emptyList(),
    ) {
        fun plugin(id: String): Plugin? = plugins.firstOrNull { it.id == id }
        fun isRevoked(id: String, version: String) = revoked.any { it.id == id && it.version == version }
    }

    @Serializable
    data class Plugin(val id: String, val versions: List<Version> = emptyList())

    @Serializable
    data class Version(
        val version: String,
        val api: Int,
        val file: String,
        val size: Long,
        val sha256: String,
        val manifest: JsonObject,
        @SerialName("published_at") val publishedAt: String? = null,
    ) {
        val permissions: JsonElement? get() = manifest["permissions"]
    }

    @Serializable
    data class Revoked(val id: String, val version: String, val reason: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun publicKey(b64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64.trim().decodeBase64()?.toByteArray() ?: throw IllegalArgumentException("registry key is not base64")))

    /** The index from [raw] when [signatureB64] (DER ECDSA P-256 over SHA-256) verifies with [key]; else an exception. */
    fun verify(key: PublicKey, raw: ByteArray, signatureB64: String): Index {
        // Android 6 has no java.util.Base64 (API 26).
        val sig = signatureB64.trim().decodeBase64()?.toByteArray() ?: throw SecurityException("registry signature is not base64")
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(raw); verify(sig) }
        if (!ok) throw SecurityException("registry index signature does not verify")
        val ix = json.decodeFromString(Index.serializer(), raw.toString(Charsets.UTF_8))
        if (ix.format != 1) throw SecurityException("registry index format ${ix.format}")
        return ix
    }

    /** What an instance runs: [run] (null: nothing runnable), and [update], a newer version that asks for other permissions. */
    data class Choice(val run: Version?, val update: Version?, val withdrawn: String? = null)

    /**
     * The version to run for an instance of [id] that names [pinned] (null: the newest). Newer versions with the
     * same permissions run at once; the newest one with other permissions waits for the owner ([Choice.update]). A
     * revoked pinned version falls back to the newest earlier one with its permissions.
     */
    fun choose(ix: Index, id: String, pinned: String?): Choice {
        val all = ix.plugin(id)?.versions.orEmpty().filter { it.api == 1 }
        if (all.isEmpty()) return Choice(null, null)
        val live = all.filter { !ix.isRevoked(id, it.version) }
        val base = (if (pinned == null) live.lastOrNull() else all.firstOrNull { it.version == pinned })
            ?: return Choice(null, null)
        val start = all.indexOf(base)
        val run = if (!ix.isRevoked(id, base.version)) {
            live.filter { all.indexOf(it) >= start && it.permissions == base.permissions }.last()
        } else {
            live.filter { all.indexOf(it) < start && it.permissions == base.permissions }.lastOrNull()
        }
        val after = if (run == null) start else all.indexOf(run)
        val update = live.filter { all.indexOf(it) > after && it.permissions != (run ?: base).permissions }.lastOrNull()
        val withdrawn = if (run == null) ix.revoked.firstOrNull { it.id == id && it.version == base.version }?.reason ?: "withdrawn" else null
        return Choice(run, update, withdrawn)
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val ENTRY = Regex("^(plugin\\.json|main\\.js|locales/[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*\\.json)$")

    /**
     * Unpacks a bundle that matches [v] into [dir] (which must not exist yet; written next to it, then renamed):
     * only `plugin.json`, `main.js` and `locales/<tag>.json`, at most [MAX_UNPACKED] in all, and a manifest that names
     * the same id and version as the index.
     */
    fun install(id: String, v: Version, bytes: ByteArray, dir: File) {
        if (bytes.size.toLong() != v.size || bytes.size > MAX_BUNDLE || sha256(bytes) != v.sha256) {
            throw SecurityException("plugin bundle ${v.file} does not match the signed index")
        }
        val tmp = File(dir.parentFile, ".tmp-${dir.name}-${System.nanoTime()}")
        tmp.mkdirs()
        try {
            var total = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) { if (e.name == "locales/") continue else throw SecurityException("bundle: directory ${e.name}") }
                    if (!ENTRY.matches(e.name)) throw SecurityException("bundle: unexpected file ${e.name}")
                    val out = File(tmp, e.name)
                    out.parentFile!!.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_UNPACKED) throw SecurityException("bundle: larger than $MAX_UNPACKED bytes unpacked")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            val manifest = json.parseToJsonElement(File(tmp, "plugin.json").takeIf { it.isFile }?.readText() ?: throw SecurityException("bundle: no plugin.json")) as JsonObject
            if (manifest["id"]?.toString()?.trim('"') != id || manifest["version"]?.toString()?.trim('"') != v.version) {
                throw SecurityException("bundle: manifest doesn't match $id ${v.version}")
            }
            if (!File(tmp, "main.js").isFile) throw SecurityException("bundle: no main.js")
            dir.parentFile?.mkdirs()
            if (!tmp.renameTo(dir)) throw IOException("could not install ${v.file}")
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }
}
