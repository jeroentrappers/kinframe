package app.kinframe.frame.plugins.registry

import app.kinframe.core.api.DeviceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeBase64
import java.io.File
import java.security.PublicKey

/**
 * The registry index the frame accepted last and the bundles it installed, in [dir] (docs/plugins.md §7): the index
 * is checked again when read back, never replaced by one with a lower sequence, and bundles live per version in
 * `bundles/<id>/<version>/`.
 */
class RegistryStore(private val dir: File, private val key: PublicKey) {
    private val _index = MutableStateFlow(load())
    val index: StateFlow<PluginRegistry.Index?> = _index

    private fun load(): PluginRegistry.Index? = runCatching {
        val raw = File(dir, "index.json").takeIf { it.isFile }?.readBytes() ?: return null
        PluginRegistry.verify(key, raw, File(dir, "index.json.sig").readText())
    }.getOrNull()

    /** Fetches the index from the server; returns whether a newer one was accepted. */
    suspend fun refresh(api: DeviceApi): Boolean = withContext(Dispatchers.IO) {
        val dto = api.pluginRegistry() ?: return@withContext false
        val raw = dto.index.decodeBase64()?.toByteArray() ?: throw SecurityException("registry index is not base64")
        val ix = PluginRegistry.verify(key, raw, dto.signature)
        val cur = _index.value
        if (cur != null && ix.sequence <= cur.sequence) return@withContext false
        dir.mkdirs()
        File(dir, "index.json.sig.tmp").writeText(dto.signature)
        File(dir, "index.json.tmp").writeBytes(raw)
        File(dir, "index.json.sig.tmp").renameTo(File(dir, "index.json.sig"))
        File(dir, "index.json.tmp").renameTo(File(dir, "index.json"))
        _index.value = ix
        true
    }

    fun bundleDir(id: String, version: String) = File(dir, "bundles/$id/$version")

    fun installed(id: String, version: String) = File(bundleDir(id, version), "plugin.json").isFile

    /** Downloads and installs [v] of [id] unless it is installed already. */
    suspend fun ensure(api: DeviceApi, id: String, v: PluginRegistry.Version) {
        if (installed(id, v.version)) return
        val bytes = api.pluginBundle(v.file, PluginRegistry.MAX_BUNDLE)
        withContext(Dispatchers.IO) {
            val target = bundleDir(id, v.version)
            if (target.exists()) target.deleteRecursively()
            PluginRegistry.install(id, v, bytes, target)
        }
    }

    /** Removes installed versions nothing runs any more ([keep]: plugin id → versions). */
    fun prune(keep: Map<String, Set<String>>) {
        File(dir, "bundles").listFiles()?.forEach { p ->
            p.listFiles()?.forEach { v -> if (v.name !in keep[p.name].orEmpty()) v.deleteRecursively() }
            if (p.listFiles().isNullOrEmpty()) p.delete()
        }
    }
}
