package app.kinframe.frame

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.kinframe.frame.FakeServer.Companion.page
import app.kinframe.frame.FakeServer.Companion.placement
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.slideshow.Credit
import app.kinframe.frame.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Photos a photo source or a Google import brought in: "Anna via Immich" in the caption (photo-sources.md §9.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhotoSourceCreditTest : SyncTestBase() {
    private val engine by lazy { SyncEngine(api, db, media, { now }) }
    private fun up(seq: Int, p: String) = """{"seq":$seq,"type":"placement.upsert","placement":$p}"""
    private fun withVia(p: String, id: String, via: String) = p.replace("\"caption\":\"hi $id\"", "\"caption\":\"hi $id\",\"via\":$via")
    private val immich = """{"service":"immich","label":"Immich","source_id":"ps1"}"""

    private fun plain(s: String?) = s?.filterNot { it in "‎‏‪‫‬‭‮⁦⁧⁨⁩" }

    private fun ctx(lang: String): Context {
        val base: Context = RuntimeEnvironment.getApplication()
        val locale = Locales.toLocale(lang)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) })
    }

    @Test
    fun viaIsStoredAndCredited() = runBlocking {
        val feed = ""","origin":"feed","origin_feed_id":"fd1","origin_feed_name":"Peeters kids","feed_ids":["fd1"]"""
        fake.pages[0] = page(4, false,
            up(1, withVia(placement("p1", byteArrayOf(1)), "p1", immich)),
            up(2, withVia(placement("p2", byteArrayOf(2), extra = feed), "p2", immich)),
            up(3, withVia(placement("p3", byteArrayOf(3), extra = ""","shared_by_name":"Ben""""), "p3", """{"service":"google_photos","label":"Google Photos"}""")),
            up(4, placement("p4", byteArrayOf(4))),
        )
        engine.pull()
        val p1 = db.placements().get("p1")!!
        assertEquals("Immich", p1.viaLabel)
        assertEquals("Ann via Immich", plain(Credit.of(ctx("en"), p1)))
        assertEquals("Ann via Immich", plain(Credit.of(ctx("nl"), p1)))
        assertEquals("Ann über Immich", plain(Credit.of(ctx("de"), p1)))
        assertEquals("Ann · Peeters kids via Immich", plain(Credit.of(ctx("en"), db.placements().get("p2")!!)))
        assertEquals("Ann via Google Photos · shared by Ben", plain(Credit.of(ctx("en"), db.placements().get("p3")!!)))
        val p4 = db.placements().get("p4")!!
        assertNull("frames ignore what isn't there", p4.viaLabel)
        assertEquals("Ann", plain(Credit.of(ctx("en"), p4)))
        // Right to left: the service name is isolated like the names.
        val ar = Credit.of(ctx("ar"), p1)!!
        assertTrue(ar, ar.contains("عبر") && ar.length > plain(ar)!!.length)
    }

    @Test
    fun creditHelpers() {
        assertEquals("Anna", plain(Credit.via(ctx("en"), "Anna", null)))
        assertEquals("Anna", plain(Credit.via(ctx("en"), "Anna", "  ")))
        assertNull(Credit.via(ctx("en"), null, "Immich"))
        assertEquals("Anna via iCloud", plain(Credit.line(ctx("en"), "Anna", null, "iCloud")))
        assertEquals("Anna · shared by Ben", plain(Credit.line(ctx("en"), "Anna", "Ben")))
    }

    @Test
    fun version9MigratesWithoutLosingPlacements() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("migrate-via.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/app.kinframe.frame.data.FrameDatabase/9.json").readText()).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { d ->
            for (e in schema["entities"]!!.jsonArray) {
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                d.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                o["indices"]?.jsonArray?.forEach { i -> d.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { d.execSQL(it.jsonPrimitive.content) }
            d.execSQL("""INSERT INTO placements (id, itemId, kind, status, senderId, senderName, caption, captionEdited, capturedAt, receivedAt,
                focalX, focalY, fit, rotation, hidden, trashed, pendingModeration, reaction, width, height, durationMs, hasAudio,
                mediaUrl, mediaSha256, mediaBytes, mediaMime, downloadAttempts, nextAttemptAt, isNew)
                VALUES ('p1', 'i1', 'photo', 'ready', 'u1', 'Ann', NULL, 0, NULL, 1, NULL, NULL, NULL, 0, 0, 0, 0, NULL, NULL, NULL, NULL, NULL,
                NULL, NULL, 0, NULL, 0, 0, 0)""")
            d.version = 9
        }
        val room = Room.databaseBuilder(app, FrameDatabase::class.java, file.absolutePath)
            .addMigrations(FrameDatabase.MIGRATION_9_10, FrameDatabase.MIGRATION_10_11).allowMainThreadQueries().build()
        val p = room.placements().get("p1")!!
        assertEquals("Ann", p.senderName)
        assertNull(p.viaLabel)
        room.close()
    }
}
