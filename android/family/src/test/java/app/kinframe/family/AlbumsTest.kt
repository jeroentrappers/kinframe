package app.kinframe.family

import app.kinframe.core.api.Album
import app.kinframe.core.api.AlbumSchedule
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.BulkResult
import app.kinframe.core.api.Item
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Placement
import app.kinframe.core.api.PlacementsPage
import app.kinframe.core.api.Problem
import app.kinframe.core.api.SettingsDocument
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.frame.AlbumDates
import app.kinframe.family.ui.frame.LibraryEvent
import app.kinframe.family.ui.frame.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Albums and "On this day" in a frame's library (web `FrameLibrary.svelte`). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AlbumsTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun pl(id: String) = Placement(id, item = Item("i$id"))

    private open inner class Api : FakeApi() {
        var albums = mutableListOf(Album("a1", "Xmas", version = 3, placementCount = 2, placementIds = listOf("p2", "p1")))
        val calls = mutableListOf<String>()
        val patches = mutableListOf<Pair<Int, JsonObject>>()
        var settingsVersion = 7
        override suspend fun placements(frameId: String, visibility: String, cursor: String?, limit: Int, albumId: String?, onThisDay: String?, sort: String?): PlacementsPage {
            calls += "placements album=$albumId day=$onThisDay sort=$sort limit=$limit"
            return when {
                onThisDay != null -> PlacementsPage(listOf(pl("m1")))
                albumId != null -> PlacementsPage(listOf(pl("p1"), pl("p2")))
                else -> PlacementsPage(listOf(pl("p1"), pl("p2"), pl("p3")))
            }
        }
        override suspend fun albums(frameId: String) = albums.toList()
        override suspend fun createAlbum(frameId: String, name: String, placementIds: List<String>) =
            Album("a2", name, placementCount = placementIds.size, placementIds = placementIds).also { albums += it }
        override suspend fun updateAlbum(frameId: String, albumId: String, version: Int, patch: JsonObject): Album {
            patches += version to patch
            val a = albums.first { it.id == albumId }
            return a.copy(version = a.version + 1)
        }
        override suspend fun bulkPlacements(frameId: String, placementIds: List<String>, action: String, albumId: String?): BulkResult {
            calls += "bulk $action $albumId $placementIds"
            return BulkResult(ok = placementIds)
        }
        override suspend fun settings(frameId: String) = SettingsDocument(KinJson.parseToJsonElement("""{"version":$settingsVersion,"slideshow":{}}""").jsonObject)
        override suspend fun patchSettings(frameId: String, version: Int, patch: JsonObject): SettingsDocument {
            calls += "settings v$version $patch"
            return SettingsDocument(KinJson.parseToJsonElement("""{"version":${version + 1},"slideshow":{"album_id":"a1"}}""").jsonObject)
        }
    }

    @Test fun memoriesAskForTodaysDateInEarlierYears() = runTest {
        val api = Api()
        val vm = LibraryViewModel(api, "f1", today = { LocalDate.of(2026, 3, 7) })
        assertTrue("placements album=null day=03-07 sort=taken_desc limit=24" in api.calls)
        assertEquals(listOf("m1"), vm.state.value.memories.map { it.id })
        assertEquals(1, vm.state.value.albums.size)
    }

    @Test fun anAlbumShowsItsPhotosInItsOwnOrder() = runTest {
        val api = Api()
        val vm = LibraryViewModel(api, "f1")
        vm.selectAlbum("a1")
        assertEquals(listOf("p2", "p1"), (vm.state.value.placements as Load.Ready).value.map { it.id })
        // Arrange: move p1 first and save the new order with the album's version.
        vm.startArranging()
        vm.move("p1", -1)
        assertEquals(listOf("p1", "p2"), vm.state.value.arranging)
        vm.saveOrder()
        assertNull(vm.state.value.arranging)
        assertEquals(3, api.patches.single().first)
        assertEquals("""{"placement_ids":["p1","p2"]}""", api.patches.single().second.toString())
        assertEquals(4, vm.state.value.album!!.version)
    }

    @Test fun addToNewOrExistingAlbumAndRemoveWithUndo() = runTest {
        val api = Api()
        val vm = LibraryViewModel(api, "f1")
        val events = mutableListOf<LibraryEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.addToAlbum(listOf("p3"), "a1", null)
        assertTrue("bulk add_to_album a1 [p3]" in api.calls)
        vm.addToAlbum(listOf("p1", "p3"), null, "Summer")
        assertEquals(listOf("p1", "p3"), api.albums.last().placementIds)
        assertNull(vm.state.value.albumId) // adding to a new album doesn't leave the library
        vm.selectAlbum("a1")
        vm.toggle("p1")
        vm.removeFromAlbum()
        assertTrue("bulk remove_from_album a1 [p1]" in api.calls)
        val removed = events.filterIsInstance<LibraryEvent.Message>().last()
        assertEquals(R.plurals.albums_removed, removed.message)
        removed.undo!!()
        assertTrue("bulk add_to_album a1 [p1]" in api.calls)
        job.cancel()
    }

    @Test fun playAnAlbumOnTheFrameAndSchedules() = runTest {
        val api = Api()
        val vm = LibraryViewModel(api, "f1", canSettings = true)
        vm.play("a1", "Oma")
        assertTrue(api.calls.any { it == """settings v7 {"slideshow":{"album_id":"a1"}}""" })
        assertEquals("a1", vm.state.value.playingAlbumId)
        vm.scheduleAlbum(vm.state.value.albums.first(), "12-01", "01-06")
        assertEquals("""{"schedule":{"from_month_day":"12-01","to_month_day":"01-06"}}""", api.patches.last().second.toString())
        vm.scheduleAlbum(vm.state.value.albums.first(), null, null)
        assertEquals("""{"schedule":null}""", api.patches.last().second.toString())
        assertTrue(AlbumDates.wraps("12-01", "01-06"))
        assertFalse(AlbumDates.wraps("06-01", "08-31"))
        assertEquals(29, AlbumDates.days(2))
        assertEquals(AlbumSchedule("12-01", "01-06"), AlbumSchedule(AlbumDates.format(12, 1), AlbumDates.format(1, 6)))
    }

    @Test fun aConflictReloadsTheAlbums() = runTest {
        val api = object : Api() {
            override suspend fun updateAlbum(frameId: String, albumId: String, version: Int, patch: JsonObject): Album =
                throw ApiException(412, Problem(code = "version_conflict"))
        }
        val vm = LibraryViewModel(api, "f1")
        val events = mutableListOf<LibraryEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.renameAlbum(vm.state.value.albums.first(), "Kerst")
        assertEquals(LibraryEvent.Error(R.string.albums_conflict), events.single())
        job.cancel()
    }
}
