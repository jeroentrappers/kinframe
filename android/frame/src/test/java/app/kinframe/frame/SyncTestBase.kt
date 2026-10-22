package app.kinframe.frame

import android.app.Application
import androidx.room.Room
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.auth.DeviceCredential
import app.kinframe.core.auth.MemoryCredentialStore
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.data.MediaCache
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment

abstract class SyncTestBase {
    @get:Rule val tmp = TemporaryFolder()
    val server = MockWebServer()
    val fake = FakeServer()
    lateinit var db: FrameDatabase
    lateinit var media: MediaCache
    lateinit var api: DeviceApi
    var now = 1_000_000_000L

    @Before
    fun baseSetUp() {
        server.dispatcher = fake
        server.start()
        val app: Application = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, FrameDatabase::class.java).allowMainThreadQueries().build()
        media = MediaCache(tmp.newFolder("files"))
        api = DeviceApi(OkHttpClient(), server.url("/v1"), MemoryCredentialStore(DeviceCredential("d", "s")), "test")
    }

    @After
    fun baseTearDown() {
        db.close()
        server.close()
    }
}
