package app.kinframe.family

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import org.robolectric.RuntimeEnvironment
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.KinJson
import app.kinframe.family.accounts.AccountDeps
import app.kinframe.family.accounts.AccountManager
import app.kinframe.family.accounts.AccountStore
import app.kinframe.family.accounts.AlreadySignedInException
import app.kinframe.family.data.MemorySecureStore
import app.kinframe.family.data.ServerChoice
import okhttp3.HttpUrl.Companion.toHttpUrl
import app.kinframe.family.data.SecureStore
import app.kinframe.family.notify.PushMessage
import app.kinframe.family.notify.PushRegistrar
import app.kinframe.family.send.OutboxItem
import app.kinframe.family.send.OutboxSend
import app.kinframe.family.send.ServerSplit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Several servers in one app (docs/spec/multi-server.md §2): account storage, migration, routing, split sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServersTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val secures = mutableMapOf<String, SecureStore>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        listOf("kinframe", "kinframe_account_b").forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun prefs(id: String) = app.getSharedPreferences(AccountStore.prefsName(id), Context.MODE_PRIVATE)

    private fun disco(name: String, serverId: String, base: String) =
        Discovery(name = name, serverId = serverId, serverName = name, baseUrl = base, devAuth = true, feeds = true)

    /** What `ServerRepository` and `AuthRepository` (dev sign-in) store for a signed-in account. */
    private fun seed(id: String, url: String, d: Discovery) {
        prefs(id).edit(commit = true) {
            putString("server_url", url)
            putString("server_discovery", KinJson.encodeToString(Discovery.serializer(), d))
        }
        secures.getOrPut(id) { MemorySecureStore() }.apply {
            put("auth_server", url); put("auth_mode", "Dev"); put("dev_token", "t-$id"); put("dev_token_expires_at", (System.currentTimeMillis() + 3_600_000).toString())
        }
    }

    private fun manager() = AccountManager(
        AccountDeps(app, OkHttpClient(), CoroutineScope(SupervisorJob() + Dispatchers.Main), "test", "test", ::prefs) { secures.getOrPut(it) { MemorySecureStore() } },
        app.getSharedPreferences("kinframe", Context.MODE_PRIVATE),
    )

    @Test
    fun aSingleServerInstallBecomesTheFirstAccountWithoutSigningInAgain() {
        seed(AccountStore.MAIN, "https://kinframe.appmire.be/", disco("appmire", "s-b", "https://kinframe.appmire.be/"))
        val m = manager()
        assertEquals(listOf(AccountStore.MAIN), m.store.ids())
        val a = m.primary.value
        assertEquals(AccountStore.MAIN, a.id)
        assertTrue(a.signedIn)
        assertEquals("appmire", a.label)
        assertFalse(m.several)
        assertNotNull(m.routes.primary)
    }

    @Test
    fun aFreshInstallHasNoAccountsUntilAServerIsChosen() {
        val m = manager()
        assertTrue(m.store.ids().isEmpty())
        assertTrue(m.accounts.value.isEmpty())
        assertNull(m.primary.value.server)
    }

    @Test
    fun accountsAddDedupeAndPrimary() {
        seed(AccountStore.MAIN, "https://kinframe.appmire.be/", disco("appmire", "s-b", "https://kinframe.appmire.be/"))
        val m = manager()
        // "Add a server": a draft joins the list once committed.
        val draft = m.newDraft()
        assertNull(draft.server)
        draft.servers.choose(ServerChoice("https://kinframe.janssens.be/".toHttpUrl(), disco("Janssens", "s-a", "https://kinframe.janssens.be/")))
        m.commit(draft)
        assertEquals(listOf(AccountStore.MAIN, draft.id), m.store.ids())
        assertEquals(2, m.accounts.value.size)
        // The same server twice (same server_id, another address) is refused and forgotten.
        val dup = m.newDraft()
        dup.servers.choose(ServerChoice("https://janssens.example/".toHttpUrl(), disco("Janssens", "s-a", "https://kinframe.janssens.be/")))
        try {
            m.commit(dup); fail()
        } catch (e: AlreadySignedInException) {
            assertEquals(draft.id, e.account.id)
        }
        assertFalse(dup.id in m.store.ids())
        // After a restart, with both signed in: server chips and push routing by the payload's server.
        seed(draft.id, "https://kinframe.janssens.be/", disco("Janssens", "s-a", "https://kinframe.janssens.be/"))
        val m4 = manager()
        assertTrue(m4.several)
        m4.index.put("feed-j", draft.id)
        assertEquals("Janssens", m4.chipFor("feed-j"))
        assertEquals(draft.id, m4.forKey("feed-j").id)
        assertEquals(AccountStore.MAIN, m4.forKey("unknown").id)
        assertEquals(draft.id, m4.forServer("https://kinframe.janssens.be")?.id)
        assertEquals(AccountStore.MAIN, m4.forServer("https://kinframe.appmire.be/")?.id)
        assertNull(m4.forServer("https://elsewhere.example/"))
        assertEquals(2, m4.routes.all().size)
        // Primary and signing out of one.
        m4.setPrimary(draft.id)
        assertEquals(draft.id, m4.primary.value.id)
        assertEquals(draft.id, m4.accounts.value.first().id)
        m4.remove(m4.byId(draft.id)!!)
        assertEquals(AccountStore.MAIN, m4.primary.value.id)
        assertEquals(listOf(AccountStore.MAIN), m4.store.ids())
        // The first account's file keeps the account list.
        assertTrue(prefs(AccountStore.MAIN).contains("accounts"))
    }

    @Test
    fun theIndexSurvivesARestart() {
        seed(AccountStore.MAIN, "https://kinframe.appmire.be/", disco("appmire", "s-b", "https://kinframe.appmire.be/"))
        val m = manager()
        m.index.put("frame-1", AccountStore.MAIN)
        m.store.saveIndex(m.index)
        assertEquals(AccountStore.MAIN, manager().index.get("frame-1"))
    }

    @Test
    fun pushPayloadsCarryTheServerAndInstancesMapToAccounts() {
        val msg = PushMessage.parse("""{"type":"x","key":"keep_request","title":"t","body":"b","ids":{"request_id":"k1"},"server":"https://kinframe.janssens.be/"}""".toByteArray())!!
        assertEquals("https://kinframe.janssens.be/", msg.server)
        assertEquals("", PushMessage.parse("""{"type":"x","title":"t","body":"b","ids":{}}""".toByteArray())!!.server)
        assertEquals(AccountStore.MAIN, PushRegistrar.accountOf("default"))
        assertEquals(AccountStore.MAIN, PushRegistrar.accountOf(null))
        assertEquals("abc", PushRegistrar.accountOf("abc"))
        assertEquals("default", PushRegistrar.instanceOf(AccountStore.MAIN))
    }

    @Test
    fun aSendToTwoServersIsTwoSends() {
        val item = OutboxItem(id = "i1", uri = "content://x/1", mime = "image/jpeg", name = "a.jpg", size = 10, uploadId = "up1")
        val send = OutboxSend(
            id = "s1", server = "https://b/", frameIds = listOf("fb", "fa"), frameNames = listOf("Oma", "Kitchen"),
            items = listOf(item), createdAt = 0, feedIds = listOf("feedA"), feedNames = listOf("Janssens"),
        )
        val accountOf = mapOf("fa" to "A", "feedA" to "A", "fb" to "B")
        var n = 0
        val parts = ServerSplit.split(send, "B", { accountOf[it] }, { "https://$it/" }) { "new${n++}" }
        assertEquals(2, parts.size)
        assertEquals("s1", parts[0].id)
        assertEquals("https://B/", parts[0].server)
        assertEquals(listOf("fb"), parts[0].frameIds)
        assertTrue(parts[0].feedIds.isEmpty())
        assertEquals(listOf("fa"), parts[1].frameIds)
        assertEquals(listOf("Janssens"), parts[1].feedNames)
        assertEquals("up1", parts[0].items.single().uploadId)
        assertNull(parts[1].items.single().uploadId)
        // One server: unchanged apart from the server.
        assertEquals(1, ServerSplit.split(send.copy(frameIds = listOf("fb"), frameNames = listOf("Oma"), feedIds = emptyList(), feedNames = emptyList()), "B", { accountOf[it] }, { "https://$it/" }).size)
    }
}
