package app.kinframe.frame.slideshow

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.kinframe.frame.data.FrameDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The 12 → 13 migration (framing, docs/spec/framing.md §5) keeps the frame's data: a database built from the exported
 * v12 schema opens with only [FrameDatabase.MIGRATION_12_13] and no destructive fallback, so Room's schema check passes
 * and the cursor, the placements and their local files survive; the new columns start empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FramingMigrationTest {
    @Test
    fun version12MigratesWithoutLosingTheLibrary() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("migrate-framing.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/app.kinframe.frame.data.FrameDatabase/12.json").readText()).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (e in schema["entities"]!!.jsonArray) {
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                o["indices"]?.jsonArray?.forEach { i -> db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO sync_state (id, nextSeq, birthdaysJson) VALUES (0, 42, '{}')")
            db.execSQL(
                "INSERT INTO placements (id, itemId, kind, status, senderId, senderName, caption, captionEdited, capturedAt, receivedAt, " +
                    "focalX, focalY, fit, rotation, hidden, trashed, pendingModeration, reaction, width, height, durationMs, hasAudio, " +
                    "mediaUrl, mediaSha256, mediaBytes, mediaMime, localFile, downloadAttempts, nextAttemptAt, isNew) VALUES " +
                    "('p1', 'i1', 'photo', 'ready', 'u1', 'Ann', NULL, 0, NULL, 1, NULL, NULL, NULL, 0, 0, 0, 0, NULL, 10, 10, NULL, NULL, " +
                    "'/m', 'abc', 3, 'image/webp', 'abc.webp', 0, 0, 0)",
            )
            db.version = 12
        }
        val room = Room.databaseBuilder(app, FrameDatabase::class.java, file.absolutePath)
            .addMigrations(FrameDatabase.MIGRATION_12_13).allowMainThreadQueries().build()
        assertEquals(42L, room.syncState().get()!!.nextSeq)
        val p = room.placements().get("p1")!!
        assertEquals("abc.webp", p.localFile)
        assertNull(p.framing)
        assertNull(p.framingOverride)
        assertFalse(p.focalOverridden)
        room.placements().upsert(p.copy(framing = """{"landscape":{"x":0.4,"y":0.3,"zoom":2.0}}""", focalOverridden = true))
        assertEquals("""{"landscape":{"x":0.4,"y":0.3,"zoom":2.0}}""", room.placements().get("p1")!!.framing)
        room.close()
    }
}
