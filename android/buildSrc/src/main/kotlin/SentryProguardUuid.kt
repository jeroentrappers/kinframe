import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Crash reports (docs/spec/crash-reporting.md): writes the asset `sentry-debug-meta.properties` with the ProGuard UUID
 * of a build's R8 mapping. The SDK sends it with every report, so GlitchTip finds the mapping to deobfuscate with.
 *
 * The UUID is the one `sentry-cli upload-proguard` derives from the mapping's content (UUIDv5 of the file in the
 * namespace UUIDv5(DNS, "guardsquare.com")), so a release only has to upload its mapping.txt; no Sentry Gradle plugin.
 */
abstract class SentryProguardUuid : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mapping: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun write() {
        val uuid = proguardUuid(mapping.get().asFile.readBytes())
        val dir = output.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("sentry-debug-meta.properties").writeText("io.sentry.ProguardUuids=$uuid\n")
        logger.info("Sentry ProGuard UUID $uuid")
    }

    companion object {
        private val DNS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

        fun proguardUuid(content: ByteArray): UUID = uuid5(uuid5(DNS, "guardsquare.com".toByteArray()), content)

        private fun uuid5(namespace: UUID, name: ByteArray): UUID {
            val ns = ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()
            val h = MessageDigest.getInstance("SHA-1").run { update(ns); update(name); digest() }
            h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
            h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
            val b = ByteBuffer.wrap(h, 0, 16)
            return UUID(b.long, b.long)
        }
    }
}
