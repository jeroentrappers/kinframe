package app.kinframe.frame

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.kinframe.frame.FakeServer.Companion.page
import app.kinframe.frame.data.AlbumFlagEntity
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.data.PlacementEntity
import app.kinframe.frame.slideshow.SlideshowSource
import app.kinframe.frame.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** "Only in album mode" (photo-sources.md §9.1): album-only photos stay out of the normal slideshow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AlbumOnlyModeTest : SyncTestBase() {
    private val engine by lazy { SyncEngine(api, db, media, { now }) }

    private fun entity(id: String, albumIds: String? = null, feedIds: String? = null) = PlacementEntity(
        id = id, itemId = "i-$id", kind = "photo", status = "ready", senderId = "u1", senderName = "Ann", caption = null,
        captionEdited = false, capturedAt = null, receivedAt = 1, focalX = null, focalY = null, fit = null, rotation = 0, hidden = false,
        trashed = false, pendingModeration = false, reaction = null, width = null, height = null, durationMs = null, hasAudio = null,
        mediaUrl = null, mediaSha256 = null, mediaBytes = 0, mediaMime = null, feedIds = feedIds, albumIds = albumIds,
    )

    @Test
    fun normalSlideshowLeavesOutAlbumOnlyPhotos() {
        val synced = entity("synced", albumIds = "immich")
        val both = entity("both", albumIds = "immich,garden")
        val everyday = entity("everyday", albumIds = "garden")
        val loose = entity("loose")
        val twoOnly = entity("twoOnly", albumIds = "immich,archive")
        val all = listOf(synced, both, everyday, loose, twoOnly)
        val only = setOf("immich", "archive")
        assertEquals("also in an everyday album keeps playing", listOf("both", "everyday", "loose"),
            SlideshowSource.select(all, null, null, only).map { it.id })
        assertEquals("without flags everything plays", all, SlideshowSource.select(all, null, null))
        assertEquals("album mode plays the album-only album", listOf("synced", "both", "twoOnly"),
            SlideshowSource.select(all, "immich", null, only).map { it.id })
        val fed = entity("fed", albumIds = "immich", feedIds = "fd1")
        assertEquals("feed mode is unchanged", listOf("fed"), SlideshowSource.select(all + fed, null, "fd1", only).map { it.id })
        assertEquals("nothing left: play everything", listOf(synced, twoOnly), SlideshowSource.select(listOf(synced, twoOnly), null, null, only))
        assertTrue(SlideshowSource.isAlbumOnly(synced, only))
        assertFalse(SlideshowSource.isAlbumOnly(both, only))
        assertFalse(SlideshowSource.isAlbumOnly(loose, only))
    }

    @Test
    fun albumChangesStoreTheFlag() = runBlocking {
        fake.pages[0] = page(3, false,
            """{"seq":1,"type":"album.upsert","album":{"id":"immich","name":"Immich","placement_ids":[],"only_in_album_mode":true}}""",
            """{"seq":2,"type":"album.upsert","album":{"id":"garden","name":"Garden","placement_ids":[]}}""",
            """{"seq":3,"type":"album.upsert","album":{"id":"old","name":"Old","only_in_album_mode":true}}""",
        )
        engine.pull()
        assertEquals(setOf("immich", "old"), db.albumFlags().albumOnlyIds().toSet())
        fake.pages[3] = page(5, false,
            """{"seq":4,"type":"album.upsert","album":{"id":"immich","name":"Immich","only_in_album_mode":false}}""",
            """{"seq":5,"type":"album.remove","id":"old"}""",
        )
        engine.pull()
        assertEquals(emptyList<String>(), db.albumFlags().albumOnlyIds())
        db.albumFlags().put(AlbumFlagEntity("x", true))
        fake.pages[5] = page(6, false, """{"seq":6,"type":"reset"}""")
        fake.pages[0] = page(7, false)
        engine.pull()
        assertEquals("a reset forgets the albums", emptyList<String>(), db.albumFlags().albumOnlyIds())
    }

    @Test
    fun version9MigratesToAlbumFlags() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("migrate-album-flags.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/app.kinframe.frame.data.FrameDatabase/9.json").readText()).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { d ->
            for (e in schema["entities"]!!.jsonArray) {
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                d.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                o["indices"]?.jsonArray?.forEach { i -> d.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { d.execSQL(it.jsonPrimitive.content) }
            d.execSQL("INSERT INTO sync_state (id, nextSeq) VALUES (0, 42)")
            d.version = 9
        }
        val room = Room.databaseBuilder(app, FrameDatabase::class.java, file.absolutePath)
            .addMigrations(FrameDatabase.MIGRATION_9_10).allowMainThreadQueries().build()
        assertEquals(42L, room.syncState().get()!!.nextSeq)
        room.albumFlags().put(AlbumFlagEntity("a1", true))
        assertEquals(listOf("a1"), room.albumFlags().albumOnlyIds())
        room.close()
    }
}
