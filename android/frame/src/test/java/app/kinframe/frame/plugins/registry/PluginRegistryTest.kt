package app.kinframe.frame.plugins.registry

import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The frame's side of the registry (docs/plugins.md §7, §7.1): it trusts only what the registry's key signed. */
class PluginRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(k: KeyPair, raw: ByteArray): String =
        Signature.getInstance("SHA256withECDSA").run { initSign(k.private); update(raw); sign() }.toByteString().base64()

    private fun publicB64(k: KeyPair) = k.public.encoded.toByteString().base64()

    private fun manifest(version: String, hosts: String = "\"https://api.example.org\"") =
        """{"id":"community.x","name":"X","version":"$version","api":1,"permissions":{"http":[$hosts]}}"""

    private fun version(v: String, hosts: String = "\"https://api.example.org\"", bytes: ByteArray = byteArrayOf(1)) =
        """{"version":"$v","api":1,"file":"community.x-$v.zip","size":${bytes.size},"sha256":"${PluginRegistry.sha256(bytes)}","manifest":${manifest(v, hosts)}}"""

    private fun index(seq: Int, versions: List<String>, revoked: String = "") =
        """{"format":1,"sequence":$seq,"plugins":[{"id":"community.x","versions":[${versions.joinToString(",")}]}],"revoked":[$revoked]}""".toByteArray()

    @Test fun onlyWhatTheKeySignedIsTrusted() {
        val k = keys()
        val key = PluginRegistry.publicKey(publicB64(k))
        val raw = index(3, listOf(version("1.0.0")))
        val ix = PluginRegistry.verify(key, raw, sign(k, raw))
        assertEquals(3L, ix.sequence)
        assertEquals("1.0.0", ix.plugins.single().versions.single().version)
        // One byte changed after signing.
        val tampered = raw.copyOf().also { it[it.size - 3] = '1'.code.toByte() }
        expectSecurity { PluginRegistry.verify(key, tampered, sign(k, raw)) }
        // Signed by another key.
        expectSecurity { PluginRegistry.verify(key, raw, sign(keys(), raw)) }
        // The production key parses on the JVM as on Android.
        PluginRegistry.publicKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEt0Z/3gbK9h78oVewOrMVTasFYG4af/w1D3eXmNJk2gCHgw8rEsrammSJXnMu6VXjp0rkz87UXXVOzaAep6fB+Q==")
    }

    private fun parse(raw: ByteArray) = Json { ignoreUnknownKeys = true }.decodeFromString(PluginRegistry.Index.serializer(), raw.toString(Charsets.UTF_8))

    @Test fun newerVersionsRunAtOnceOnlyWithTheSamePermissions() {
        val ix = parse(index(1, listOf(version("1.0.0"), version("1.0.1"), version("1.1.0", "\"https://api.example.org\",\"https://other.example.org\""), version("1.1.1", "\"https://api.example.org\",\"https://other.example.org\""))))
        // Pinned 1.0.0: 1.0.1 runs (same permissions), 1.1.1 waits for the owner.
        val c = PluginRegistry.choose(ix, "community.x", "1.0.0")
        assertEquals("1.0.1", c.run!!.version)
        assertEquals("1.1.1", c.update!!.version)
        // Approved: the instance names 1.1.0, and 1.1.1 has its permissions.
        val approved = PluginRegistry.choose(ix, "community.x", "1.1.0")
        assertEquals("1.1.1", approved.run!!.version)
        assertNull(approved.update)
        // No version named: the newest.
        assertEquals("1.1.1", PluginRegistry.choose(ix, "community.x", null).run!!.version)
        // Unknown plugin or version: nothing to run.
        assertNull(PluginRegistry.choose(ix, "community.y", null).run)
        assertNull(PluginRegistry.choose(ix, "community.x", "9.0.0").run)
    }

    @Test fun aRevokedVersionFallsBackOrIsWithdrawn() {
        val ix = parse(index(1, listOf(version("1.0.0"), version("1.0.1")), revoked = """{"id":"community.x","version":"1.0.1","reason":"leaked tokens"}"""))
        // Pinned 1.0.1 (revoked): back to 1.0.0, same permissions.
        assertEquals("1.0.0", PluginRegistry.choose(ix, "community.x", "1.0.1").run!!.version)
        // Pinned 1.0.0: 1.0.1 is revoked, so 1.0.0 stays.
        assertEquals("1.0.0", PluginRegistry.choose(ix, "community.x", "1.0.0").run!!.version)
        val only = parse(index(1, listOf(version("1.0.0")), revoked = """{"id":"community.x","version":"1.0.0","reason":"leaked tokens"}"""))
        val c = PluginRegistry.choose(only, "community.x", "1.0.0")
        assertNull(c.run)
        assertEquals("leaked tokens", c.withdrawn)
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun versionOf(bytes: ByteArray, v: String = "1.0.0"): PluginRegistry.Version =
        Json.decodeFromString(PluginRegistry.Version.serializer(), version(v, bytes = bytes))

    @Test fun bundlesInstallOnlyWhenTheyMatchAndContainOnlyWhatABundleMay() {
        val good = zip("plugin.json" to manifest("1.0.0"), "main.js" to "export default {}", "locales/nl.json" to "{}")
        val dir = tmp.newFolder("bundles", "community.x").resolve("1.0.0")
        PluginRegistry.install("community.x", versionOf(good), good, dir)
        assertTrue(dir.resolve("main.js").isFile && dir.resolve("locales/nl.json").isFile)

        fun refused(bytes: ByteArray, v: PluginRegistry.Version = versionOf(bytes)) {
            val target = tmp.root.resolve("t-${System.nanoTime()}")
            expectSecurity { PluginRegistry.install("community.x", v, bytes, target) }
            assertFalse(target.exists())
            assertTrue(tmp.root.listFiles()!!.none { it.name.startsWith(".tmp-") })
        }
        // Not the bytes the index signed.
        refused(good, versionOf(zip("plugin.json" to manifest("1.0.0"), "main.js" to "evil")))
        // Path tricks and files a bundle has no business carrying.
        refused(zip("plugin.json" to manifest("1.0.0"), "main.js" to "x", "../escape.js" to "x"))
        refused(zip("plugin.json" to manifest("1.0.0"), "main.js" to "x", "lib/extra.js" to "x"))
        // A manifest that claims another plugin or version.
        refused(zip("plugin.json" to manifest("2.0.0"), "main.js" to "x"))
        // No code.
        refused(zip("plugin.json" to manifest("1.0.0")))
        // Too large unpacked (a zip bomb).
        refused(zip("plugin.json" to manifest("1.0.0"), "main.js" to "x".repeat(PluginRegistry.MAX_UNPACKED + 1)))
    }

    private fun expectSecurity(block: () -> Unit) {
        try { block(); fail("accepted") } catch (_: SecurityException) {}
    }

}
