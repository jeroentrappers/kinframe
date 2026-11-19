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

/**
 * Photos mailed to an inbox address or posted in a Telegram chat: the person who mailed or posted them is credited
 * instead of the sender (the address's or chat's owner): "Jan via email" (email-inbox.md §9.2, telegram.md §8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChannelCreditTest : SyncTestBase() {
    private val engine by lazy { SyncEngine(api, db, media, { now }) }
    private fun up(seq: Int, p: String) = """{"seq":$seq,"type":"placement.upsert","placement":$p}"""
    private fun withVia(p: String, id: String, via: String) = p.replace("\"caption\":\"hi $id\"", "\"caption\":\"hi $id\",\"via\":$via")
    private val email = """{"service":"email","label":"Jan","address_id":"ea1"}"""
    private val telegram = """{"service":"telegram","label":"Ben","telegram_chat_id":"tc1"}"""

    private fun plain(s: String?) = s?.filterNot { it in "‎‏‪‫‬‭‮⁦⁧⁨⁩" }

    private fun ctx(lang: String): Context {
        val base: Context = RuntimeEnvironment.getApplication()
        val locale = Locales.toLocale(lang)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) })
    }

    @Test
    fun mailAndTelegramCreditThePerson() = runBlocking {
        val feed = ""","origin":"feed","origin_feed_id":"fd1","origin_feed_name":"Peeters kids","feed_ids":["fd1"]"""
        fake.pages[0] = page(5, false,
            up(1, withVia(placement("p1", byteArrayOf(1)), "p1", email)),
            up(2, withVia(placement("p2", byteArrayOf(2), extra = feed), "p2", telegram)),
            up(3, withVia(placement("p3", byteArrayOf(3), extra = ""","shared_by_name":"Ben""""), "p3", email)),
            up(4, withVia(placement("p4", byteArrayOf(4)), "p4", """{"service":"email","label":"  "}""")),
            up(5, withVia(placement("p5", byteArrayOf(5)), "p5", """{"service":"fax","label":"Fax"}""")),
        )
        engine.pull()
        val p1 = db.placements().get("p1")!!
        assertEquals("Jan", p1.viaLabel)
        assertEquals("email", p1.viaService)
        assertEquals("Jan via email", plain(Credit.of(ctx("en"), p1)))
        assertEquals("Jan via e-mail", plain(Credit.of(ctx("nl"), p1)))
        assertEquals("Jan per E-Mail", plain(Credit.of(ctx("de"), p1)))
        assertEquals("Jan par e-mail", plain(Credit.of(ctx("fr"), p1)))
        val p2 = db.placements().get("p2")!!
        assertEquals("Ben · Peeters kids via Telegram", plain(Credit.of(ctx("en"), p2)))
        assertEquals("Jan via email · shared by Ben", plain(Credit.of(ctx("en"), db.placements().get("p3")!!)))
        assertEquals("an empty label falls back to the sender", "Ann via email", plain(Credit.of(ctx("en"), db.placements().get("p4")!!)))
        assertEquals("an unknown service reads like a photo service", "Ann via Fax", plain(Credit.of(ctx("en"), db.placements().get("p5")!!)))
        val ar = Credit.of(ctx("ar"), p2)!!
        assertTrue(ar, ar.contains("تيليجرام"))
    }

    @Test
    fun everyLanguageHasTheCredit() {
        for (lang in listOf("en", "nl", "de", "fr", "ja", "ar", "zh-CN", "fi")) {
            val c = ctx(lang)
            assertTrue(lang, c.getString(R.string.credit_via_email, "Jan").contains("Jan"))
            assertTrue(lang, c.getString(R.string.credit_via_telegram, "Jan").contains("Telegram") || lang == "ar")
        }
        assertNull(Credit.channel(ctx("en"), null, "email"))
        assertEquals("Anna", Credit.channel(ctx("en"), "Anna", "immich"))
    }

    @Test
    fun version10MigratesWithoutLosingPlacements() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("migrate-channel.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/app.kinframe.frame.data.FrameDatabase/10.json").readText()).jsonObject["database"]!!.jsonObject
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
                mediaUrl, mediaSha256, mediaBytes, mediaMime, downloadAttempts, nextAttemptAt, isNew, viaLabel)
                VALUES ('p1', 'i1', 'photo', 'ready', 'u1', 'Ann', NULL, 0, NULL, 1, NULL, NULL, NULL, 0, 0, 0, 0, NULL, NULL, NULL, NULL, NULL,
                NULL, NULL, 0, NULL, 0, 0, 0, 'Immich')""")
            d.version = 10
        }
        val room = Room.databaseBuilder(app, FrameDatabase::class.java, file.absolutePath)
            .addMigrations(FrameDatabase.MIGRATION_10_11, FrameDatabase.MIGRATION_11_12).allowMainThreadQueries().build()
        val p = room.placements().get("p1")!!
        assertEquals("Immich", p.viaLabel)
        assertNull(p.viaService)
        assertEquals("Ann via Immich", plain(Credit.of(ctx("en"), p)))
        room.close()
    }
}
