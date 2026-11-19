package app.kinframe.frame.social

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.kinframe.frame.data.CommentCardShownEntity
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.data.PlacementCommentsEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The 11 → 12 migration (family social) keeps the frame's data: a database built from the exported v11 schema opens
 * with only [FrameDatabase.MIGRATION_11_12] and no destructive fallback, so Room's schema check passes and the cursor,
 * the placements and their local files survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SocialMigrationTest {
    @Test
    fun version11MigratesWithoutLosingTheLibrary() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("migrate-social.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/app.kinframe.frame.data.FrameDatabase/11.json").readText()).jsonObject["database"]!!.jsonObject
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
            db.version = 11
        }
        val room = Room.databaseBuilder(app, FrameDatabase::class.java, file.absolutePath)
            .addMigrations(FrameDatabase.MIGRATION_11_12).allowMainThreadQueries().build()
        val st = room.syncState().get()!!
        assertEquals(42L, st.nextSeq)
        assertEquals("{}", st.birthdaysJson)
        assertNull(st.photoRequestJson)
        assertNull(st.newPhotosFrom)
        val p = room.placements().get("p1")!!
        assertEquals("abc.webp", p.localFile)
        assertNull(p.promptId)
        room.social().putComments(PlacementCommentsEntity("p1", "{}", 1))
        assertEquals("{}", room.social().comments("p1")!!.json)
        assertEquals(1L, room.social().markCardShown(CommentCardShownEntity("p1", "2026-11-19")))
        assertEquals(-1L, room.social().markCardShown(CommentCardShownEntity("p1", "2026-11-19")))
        room.close()
    }
}
