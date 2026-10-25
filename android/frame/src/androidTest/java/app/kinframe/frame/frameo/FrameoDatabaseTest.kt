package app.kinframe.frame.frameo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.realm.DynamicRealm
import io.realm.FieldAttribute
import io.realm.Realm
import io.realm.RealmConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Date

/**
 * Reads a Realm file laid out like the one a Frameo frame keeps (docs/spec/frameo-import.md) with the real native
 * library. Emulator only, since Realm's library for x86 is left out of normal builds:
 *
 *     ANDROID_SERIAL=emulator-5554 ./gradlew :frame:connectedDebugAndroidTest -PemulatorAbis=x86_64
 *
 * (Connected tests install on every attached device: set ANDROID_SERIAL so a frame in daily use is left alone.)
 *
 * `-Pandroid.testInstrumentationRunnerArguments.frameoRealm=/data/local/tmp/default.realm` additionally reads a
 * real file pushed to the emulator and logs counts only (tag KinFrameoTest).
 */
@RunWith(AndroidJUnit4::class)
class FrameoDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A synthetic database with the observed classes and fields (no real data). */
    private fun synthetic(dir: File) {
        Realm.init(context)
        val cfg = RealmConfiguration.Builder().directory(dir).name("default.realm").schemaVersion(30)
            .allowWritesOnUiThread(true).allowQueriesOnUiThread(true).build()
        DynamicRealm.getInstance(cfg).use { r ->
            r.executeTransaction {
                val s = r.schema
                val friend = s.create("Friend").addField("uuid", String::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("name", String::class.java, FieldAttribute.REQUIRED)
                val picture = s.create("Picture").addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("centerPointX", Float::class.java).addField("centerPointY", Float::class.java)
                    .addField("scaleType", Int::class.java).addField("rotation", Int::class.java)
                val video = s.create("Video").addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("durationMs", Long::class.java).addField("hasAudio", Boolean::class.java)
                s.create("Media").addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("filePath", String::class.java, FieldAttribute.REQUIRED)
                    .addField("receivedTime", Date::class.java, FieldAttribute.REQUIRED)
                    .addField("captureTime", Date::class.java)
                    .addField("caption", String::class.java).addField("editedCaption", String::class.java)
                    .addField("isCaptionEdited", Boolean::class.java).addField("isEnabled", Boolean::class.java)
                    .addRealmObjectField("friend", friend).addRealmObjectField("picture", picture).addRealmObjectField("video", video)
                val anna = r.createObject("Friend", "f1").apply { setString("name", "Anna") }
                r.createObject("Media", 1L).apply {
                    setString("filePath", "/storage/emulated/0/frameo_files/media/1700000000000.webp")
                    setDate("receivedTime", Date(1_700_000_000_000L))
                    setDate("captureTime", Date(1_699_000_000_000L))
                    setString("caption", "Zee")
                    setBoolean("isEnabled", true)
                    setObject("friend", anna)
                    setObject("picture", r.createObject("Picture", 1L).apply {
                        setFloat("centerPointX", 0.25f); setFloat("centerPointY", 0.75f); setInt("scaleType", 0)
                    })
                }
                r.createObject("Media", 2L).apply {
                    setString("filePath", "/storage/emulated/0/frameo_files/media/1700000001000.mp4")
                    setDate("receivedTime", Date(1_700_000_001_000L))
                    setBoolean("isEnabled", false)
                    setObject("video", r.createObject("Video", 2L).apply { setLong("durationMs", 4000) })
                }
            }
        }
    }

    @Test fun readsASyntheticFrameoDatabase() {
        val dir = File(context.cacheDir, "frameo-test").apply { deleteRecursively(); mkdirs() }
        synthetic(dir)
        val entries = FrameoDatabase.readCopy(context, dir)
        assertEquals(2, entries.size)
        val photo = entries.getValue("1700000000000.webp")
        assertEquals("Anna", photo.senderName)
        assertEquals("Zee", photo.caption)
        assertEquals(1_699_000_000_000L, photo.capturedAtMs)
        assertEquals(0, photo.scaleType)
        assertEquals(0.25, photo.centerX!!, 1e-6)
        val video = entries.getValue("1700000001000.mp4")
        assertEquals(false, video.enabled)
        assertNull(video.senderName)
        assertNull(video.scaleType)
        dir.deleteRecursively()
    }

    /** Local check against a real file (counts only, nothing personal is logged or asserted). */
    @Test fun readsARealFileWhenGiven() {
        val path = InstrumentationRegistry.getArguments().getString("frameoRealm")
        assumeTrue(path != null)
        val dir = File(context.cacheDir, "frameo-real").apply { deleteRecursively(); mkdirs() }
        File(path!!).copyTo(File(dir, "default.realm"))
        val entries = FrameoDatabase.readCopy(context, dir).values
        Log.i(
            "KinFrameoTest",
            "entries=${entries.size} captured=${entries.count { it.capturedAtMs != null }} sender=${entries.count { it.senderName != null }} " +
                "caption=${entries.count { it.caption != null }} hidden=${entries.count { !it.enabled }} fill=${entries.count { it.scaleType == 0 }} " +
                "fit=${entries.count { it.scaleType == 1 }} focal=${entries.count { FrameoMapping.focalOf(it) != null }} " +
                "senders=${entries.mapNotNull { it.senderName }.toSet().size} nameMatchesReceived=${entries.count { it.receivedAtMs.toString() == it.fileName.substringBefore('.') }}",
        )
        assertEquals(true, entries.isNotEmpty())
        dir.deleteRecursively()
    }
}
