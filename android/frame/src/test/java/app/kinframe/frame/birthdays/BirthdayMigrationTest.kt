package app.kinframe.frame.birthdays

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.kinframe.frame.data.BirthdayGreetingEntity
import app.kinframe.frame.data.FrameDatabase
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
 * The 8 → 9 migration keeps the frame's data: a database built from the exported v8 schema (schemas/…/8.json) opens
 * with only [FrameDatabase.MIGRATION_8_9] and no destructive fallback, so Room's schema check passes and rows survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BirthdayMigrationTest {
    @Test
    fun version8MigratesWithoutLosingTheCursor() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("migrate-test.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/app.kinframe.frame.data.FrameDatabase/8.json").readText()).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (e in schema["entities"]!!.jsonArray) {
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                o["indices"]?.jsonArray?.forEach { i -> db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO sync_state (id, nextSeq, calendarJson) VALUES (0, 42, '{}')")
            db.version = 8
        }
        val room = Room.databaseBuilder(app, FrameDatabase::class.java, file.absolutePath)
            .addMigrations(FrameDatabase.MIGRATION_8_9, FrameDatabase.MIGRATION_9_10).allowMainThreadQueries().build()
        val st = room.syncState().get()!!
        assertEquals(42L, st.nextSeq)
        assertEquals("{}", st.calendarJson)
        assertNull(st.birthdaysJson)
        room.birthdayGreetings().insert(BirthdayGreetingEntity("b1", "2027-03-12", 1))
        assertEquals(true, room.birthdayGreetings().has("b1", "2027-03-12"))
        room.close()
    }
}
