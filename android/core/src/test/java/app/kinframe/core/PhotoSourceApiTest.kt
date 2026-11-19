package app.kinframe.core

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.FeedPost
import app.kinframe.core.api.GoogleImportCreate
import app.kinframe.core.api.GoogleImportStatus
import app.kinframe.core.api.HttpKinApi
import app.kinframe.core.api.Item
import app.kinframe.core.api.ItemVia
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Limits
import app.kinframe.core.api.PhotoService
import app.kinframe.core.api.PhotoSource
import app.kinframe.core.api.PhotoSourceConnection
import app.kinframe.core.api.PhotoSourceCreate
import app.kinframe.core.api.PhotoSourceDeletePolicy
import app.kinframe.core.api.PhotoSourceErrorCode
import app.kinframe.core.api.PhotoSourceHistory
import app.kinframe.core.api.PhotoSourceLinks
import app.kinframe.core.api.PhotoSourceProvider
import app.kinframe.core.api.PhotoSourceStatus
import app.kinframe.core.api.PhotoSourceUpdate
import app.kinframe.core.api.PhotoSourceWarning
import app.kinframe.core.api.Placement
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.Send
import app.kinframe.core.api.ServerAddress
import app.kinframe.core.api.SourceLink
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PhotoSourceApiTest {
    private val server = MockWebServer()
    private val tokens = object : AccessTokenSource {
        override suspend fun accessToken(forceRefresh: Boolean) = "t"
    }
    private lateinit var api: HttpKinApi

    @Before
    fun setUp() {
        server.start()
        api = HttpKinApi(OkHttpClient(), server.url("/v1"), tokens)
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    private val sourceJson = """{"id":"ps1","provider":"immich_link","name":"Garden 2026",
        "target":{"kind":"album","frame_id":"f1","frame_name":"Oma","album_id":"a1","album_name":"Garden","feed_id":null,"feed_name":null},
        "owner":{"user_id":"u1","display_name":"Anna","avatar_url":null},"best_effort":false,
        "url_hint":"https://photos.peeters.be/share/…","server_url":null,"immich_album_id":null,"has_api_key":false,"has_password":true,
        "status":"needs_attention","running":false,"warnings":["downloads_disabled","something_new"],
        "last_error_code":"mass_removal","last_error":"342 assets gone","error_since":"2026-11-18T10:00:00Z","pending_removals":342,
        "history":"all","history_count":null,"on_upstream_delete":"mirror","include_videos":true,"notify_reactions":false,
        "counts":{"upstream":560,"imported":214,"duplicates":3,"pending":6,"skipped":2,"failed":1,"removed_locally":0,"gone_upstream":0},
        "last_run":{"started_at":"2026-11-19T09:00:00Z","finished_at":"2026-11-19T09:01:00Z","added":12,"bytes":1024,"error_code":null},
        "last_synced_at":"2026-11-19T09:01:00Z","next_sync_at":null,"can_manage":true,"is_owner":true,"created_at":"2026-11-01T10:00:00Z",
        "future_field":{"x":1}}"""

    // ---------------------------------------------------------------------------------------------- decoding

    @Test
    fun decodesPhotoSourceWithExplicitNullsAndUnknowns() = runBlocking {
        server.enqueue(json("[$sourceJson]"))
        val s = api.albumPhotoSources("f1", "a1").single()
        assertEquals("/v1/frames/f1/albums/a1/photo-sources", server.takeRequest().url.encodedPath)
        assertEquals(PhotoSourceProvider.IMMICH_LINK, s.provider)
        assertEquals("a1", s.target.albumId)
        assertNull("explicit null", s.target.feedId)
        assertFalse(s.target.isFeed)
        assertEquals("Anna", s.owner?.displayName)
        assertEquals(PhotoSourceStatus.NEEDS_ATTENTION, s.status)
        assertEquals(PhotoSourceErrorCode.MASS_REMOVAL, s.lastErrorCode)
        assertEquals(342, s.pendingRemovals)
        assertEquals("unknown warnings are kept as strings", listOf(PhotoSourceWarning.DOWNLOADS_DISABLED, "something_new"), s.warnings)
        assertNull(s.historyCount)
        assertNull(s.nextSyncAt)
        assertFalse(s.notifyReactions)
        assertEquals(214, s.counts.imported)
        assertEquals(6, s.counts.pending)
        assertEquals(12, s.lastRun?.added)
        assertTrue(s.hasPassword && !s.hasApiKey)
        assertEquals("Immich", s.serviceLabel)
    }

    @Test
    fun writeOnlySecretsAreNeverDecodedOrReEncoded() {
        // A misbehaving server that echoes secrets: the model has nowhere to keep them.
        val leaky = sourceJson.replace("\"has_password\":true", "\"has_password\":true,\"url\":\"https://photos.peeters.be/share/SECRETKEY\",\"api_key\":\"KEY123456\",\"password\":\"hunter2\"")
        val s = KinJson.decodeFromString(PhotoSource.serializer(), leaky)
        val again = KinJson.encodeToString(PhotoSource.serializer(), s)
        assertFalse(again.contains("SECRETKEY"))
        assertFalse(again.contains("KEY123456"))
        assertFalse(again.contains("hunter2"))
        assertFalse(again.contains("\"api_key\""))
        // The request side never prints secrets either.
        val c = PhotoSourceConnection(PhotoSourceProvider.IMMICH_API, serverUrl = "https://photos.peeters.be", apiKey = "KEY123456")
        assertFalse(c.toString().contains("KEY123456"))
        assertFalse(PhotoSourceUpdate(apiKey = "KEY123456").toString().contains("KEY123456"))
    }

    @Test
    fun minimalSourceDecodesWithDefaults() {
        val s = KinJson.decodeFromString(PhotoSource.serializer(), """{"id":"ps2","provider":"icloud_link","name":"Kids","best_effort":true,
            "target":{"kind":"feed","feed_id":"fd1","feed_name":"Peeters kids"},"status":"active","running":true,"history":"last_n","history_count":20,
            "on_upstream_delete":"keep","include_videos":false,"counts":{"imported":0,"pending":0},"created_at":"2026-11-19T10:00:00Z"}""")
        assertTrue(s.target.isFeed)
        assertTrue(s.bestEffort && s.running)
        assertEquals("iCloud", s.serviceLabel)
        assertNull(s.owner)
        assertEquals(emptyList<String>(), s.warnings)
        assertTrue("notify_reactions defaults on", s.notifyReactions)
    }

    @Test
    fun viaOnItemsSendsPostsAndPlacements() {
        val item = KinJson.decodeFromString(Item.serializer(), """{"id":"i1","kind":"photo","sender_name":"Anna","via":{"service":"immich","label":"Immich","source_id":"ps1"}}""")
        assertEquals(ItemVia(PhotoService.IMMICH, "Immich", "ps1"), item.via)
        val google = KinJson.decodeFromString(Item.serializer(), """{"id":"i2","via":{"service":"google_photos","label":"","source_id":null}}""")
        assertEquals("Google Photos", google.via?.displayLabel)
        assertNull(google.via?.sourceId)
        assertNull("absent and null are the same", KinJson.decodeFromString(Item.serializer(), """{"id":"i3","via":null}""").via)
        val send = KinJson.decodeFromString(Send.serializer(), """{"id":"s1","created_at":"2026-11-19T10:00:00Z","items":[],"frames":[],"via":{"service":"icloud","label":"iCloud"}}""")
        assertEquals("iCloud", send.via?.displayLabel)
        val post = KinJson.decodeFromString(FeedPost.serializer(), """{"id":"p1","feed_id":"fd1","poster":{"user_id":"u1","display_name":"Anna"},
            "created_at":"2026-11-19T10:00:00Z","items":[],"via":{"service":"immich","label":"Immich"}}""")
        assertEquals(PhotoService.IMMICH, post.via?.service)
        val pl = KinJson.decodeFromString(Placement.serializer(), """{"id":"pl1","item":{"id":"i1","sender_name":"Anna","via":{"service":"immich","label":"Immich"}}}""")
        assertEquals("Immich", pl.item.via?.label)
    }

    @Test
    fun discoveryFlagsAndLimits() {
        val d = KinJson.decodeFromString(Discovery.serializer(), """{"name":"kinframe","photo_sources":true,"photo_source_providers":["immich_link","immich_api","webdav"],
            "photo_sources_private_networks":false,"google_photos_import":true,
            "limits":{"max_items_per_send":200,"max_video_seconds":300,"max_upload_bytes":1000,"max_photo_sources_per_target":3,"max_google_import_items":100}}""")
        assertTrue(d.photoSources && d.googlePhotosImport)
        assertFalse(d.photoSourcesPrivateNetworks)
        assertEquals("unknown providers are not offered", listOf(PhotoSourceProvider.IMMICH_LINK, PhotoSourceProvider.IMMICH_API), d.sourceProviders)
        assertEquals(3, d.limits?.maxPhotoSourcesPerTarget)
        assertEquals(100, d.limits?.maxGoogleImportItems)
        assertEquals(10000, d.limits?.maxPhotoSourceItems)
        val old = KinJson.decodeFromString(Discovery.serializer(), """{"name":"kinframe","photo_source_providers":["immich_link"]}""")
        assertEquals("sources off: no providers", emptyList<String>(), old.sourceProviders)
        assertFalse(old.googlePhotosImport)
        assertEquals(20, Limits().maxPhotoSourcesPerUser)
    }

    // ---------------------------------------------------------------------------------------------- encoding

    @Test
    fun createSendsConnectionTargetAndSettings() = runBlocking {
        server.enqueue(json(sourceJson, 201))
        api.createPhotoSource(PhotoSourceCreate(
            PhotoSourceConnection(PhotoSourceProvider.IMMICH_LINK, url = " https://photos.peeters.be/share/abcdEFGH ", password = "pw"),
            albumId = "a1", history = PhotoSourceHistory.ALL, onUpstreamDelete = PhotoSourceDeletePolicy.MIRROR,
        ), "k1")
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/v1/photo-sources", r.url.encodedPath)
        assertEquals("k1", r.headers["Idempotency-Key"])
        assertEquals("""{"provider":"immich_link","url":"https://photos.peeters.be/share/abcdEFGH","password":"pw","album_id":"a1","history":"all","on_upstream_delete":"mirror","include_videos":true,"notify_reactions":true}""", r.body!!.utf8())
    }

    @Test
    fun createForFeedWithLastNAlwaysSendsCount() = runBlocking {
        server.enqueue(json(sourceJson, 201))
        api.createPhotoSource(PhotoSourceCreate(
            PhotoSourceConnection(PhotoSourceProvider.IMMICH_API, serverUrl = "https://photos.peeters.be", apiKey = "abcdefgh12", immichAlbumId = "alb-1"),
            feedId = "fd1", name = "Kids", history = PhotoSourceHistory.LAST_N, onUpstreamDelete = PhotoSourceDeletePolicy.KEEP, notifyReactions = false,
        ), "k2")
        assertEquals("""{"provider":"immich_api","server_url":"https://photos.peeters.be","api_key":"abcdefgh12","immich_album_id":"alb-1","feed_id":"fd1","name":"Kids","history":"last_n","history_count":20,"on_upstream_delete":"keep","include_videos":true,"notify_reactions":false}""",
            server.takeRequest().body!!.utf8())
    }

    @Test
    fun updateOmitsKeptFieldsAndSendsExplicitNullPassword() = runBlocking {
        server.enqueue(json(sourceJson))
        server.enqueue(json(sourceJson))
        server.enqueue(json(sourceJson))
        api.updatePhotoSource("ps1", PhotoSourceUpdate(paused = true))
        assertEquals("""{"paused":true}""", server.takeRequest().body!!.utf8())
        api.updatePhotoSource("ps1", PhotoSourceUpdate(removePassword = true, onUpstreamDelete = PhotoSourceDeletePolicy.KEEP))
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals("/v1/photo-sources/ps1", r.url.encodedPath)
        assertEquals("""{"password":null,"on_upstream_delete":"keep"}""", r.body!!.utf8())
        api.updatePhotoSource("ps1", PhotoSourceUpdate(takeOver = true, apiKey = "newkey1234"))
        assertEquals("""{"api_key":"newkey1234","take_over":true}""", server.takeRequest().body!!.utf8())
        assertTrue(PhotoSourceUpdate().isEmpty)
    }

    @Test
    fun testListSyncAndDeletePaths() = runBlocking {
        server.enqueue(json("""{"ok":false,"error_code":"auth_failed","error":"401","albums":[]}"""))
        server.enqueue(json("""{"ok":true,"server_version":"v1.132.3","albums":[{"id":"alb-1","name":"Christmas","asset_count":40,"shared":true,"owner_name":"Ben"}]}"""))
        server.enqueue(json(sourceJson.replace("\"running\":false", "\"running\":true"), 202))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(json("[$sourceJson]"))
        server.enqueue(json("[]"))
        val t = api.testPhotoSource(PhotoSourceConnection(PhotoSourceProvider.ICLOUD_LINK, url = "https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz"))
        assertFalse(t.ok)
        assertEquals(PhotoSourceErrorCode.AUTH_FAILED, t.errorCode)
        val test = server.takeRequest()
        assertEquals("/v1/photo-sources:test", test.url.encodedPath)
        assertEquals("""{"provider":"icloud_link","url":"https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz"}""", test.body!!.utf8())
        val albums = api.immichAlbums("https://photos.peeters.be", "abcdefgh12")
        assertEquals("Christmas", albums.albums.single().name)
        assertTrue(albums.albums.single().shared)
        val list = server.takeRequest()
        assertEquals("/v1/photo-sources:immich-albums", list.url.encodedPath)
        assertEquals("""{"server_url":"https://photos.peeters.be","api_key":"abcdefgh12"}""", list.body!!.utf8())
        assertTrue(api.syncPhotoSource("ps1", confirmRemovals = true).running)
        val sync = server.takeRequest()
        assertEquals("/v1/photo-sources/ps1/sync", sync.url.encodedPath)
        assertEquals("""{"confirm_removals":true}""", sync.body!!.utf8())
        api.deletePhotoSource("ps1", removeItems = false)
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("false", del.url.queryParameter("remove_items"))
        api.feedPhotoSources("fd1")
        assertEquals("/v1/feeds/fd1/photo-sources", server.takeRequest().url.encodedPath)
        api.myPhotoSources()
        assertEquals("/v1/photo-sources", server.takeRequest().url.encodedPath)
    }

    @Test
    fun deleteAlbumAsksAboutSourceItemsOnlyWhenTrue() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        api.deleteAlbum("f1", "a1")
        assertNull(server.takeRequest().url.queryParameter("remove_source_items"))
        api.deleteAlbum("f1", "a1", removeSourceItems = true)
        assertEquals("true", server.takeRequest().url.queryParameter("remove_source_items"))
    }

    @Test
    fun albumOnlyInAlbumModeDecodesAndPatches() = runBlocking {
        val a = KinJson.decodeFromString(app.kinframe.core.api.Album.serializer(), """{"id":"a1","name":"Immich","version":3,"photo_source_count":1,"only_in_album_mode":true}""")
        assertTrue(a.onlyInAlbumMode)
        assertEquals(1, a.photoSourceCount)
        assertFalse("default off", KinJson.decodeFromString(app.kinframe.core.api.Album.serializer(), """{"id":"a2","name":"Garden"}""").onlyInAlbumMode)
        server.enqueue(json("""{"id":"a1","name":"Immich","version":4,"only_in_album_mode":false}"""))
        val n = api.updateAlbum("f1", "a1", 3, app.kinframe.core.api.AlbumWrite(onlyInAlbumMode = false).toJson())
        assertFalse(n.onlyInAlbumMode)
        val r = server.takeRequest()
        assertEquals("\"3\"", r.headers["If-Match"])
        assertEquals("""{"only_in_album_mode":false}""", r.body!!.utf8())
    }

    @Test
    fun problemsCarryTheSourceErrorCode() = runBlocking {
        server.enqueue(json("""{"code":"source_test_failed","status":422,"detail":"link_not_found"}""", 422))
        try {
            api.createPhotoSource(PhotoSourceCreate(PhotoSourceConnection(PhotoSourceProvider.IMMICH_LINK, url = "https://x.example/share/abcd"), albumId = "a1"), "k")
            fail("expected a problem")
        } catch (e: ApiException) {
            assertEquals(ProblemCode.SOURCE_TEST_FAILED, e.code)
            assertEquals(PhotoSourceErrorCode.LINK_NOT_FOUND, e.problem?.detail)
        }
    }

    @Test
    fun googleImportStartPollCancel() = runBlocking {
        server.enqueue(json("""{"id":"gi1","status":"needs_auth","target":{"kind":"album","album_id":"a1","frame_id":"f1"},
            "auth_url":"https://accounts.google.com/o/oauth2/v2/auth?x=1","picker_uri":null,"progress":{"picked":0,"imported":0},"created_at":"2026-11-19T10:00:00Z"}""", 201))
        server.enqueue(json("""{"id":"gi1","status":"importing","target":{"kind":"album"},"progress":{"picked":23,"imported":7,"duplicates":2},"send_ids":["s1"],"created_at":"2026-11-19T10:00:00Z"}"""))
        server.enqueue(json("""{"id":"gi1","status":"cancelled","target":{"kind":"album"},"progress":{"picked":23,"imported":9},"created_at":"2026-11-19T10:00:00Z"}"""))
        server.enqueue(json("""[]"""))
        val g = api.startGoogleImport(GoogleImportCreate(albumId = "a1"), "k1")
        assertEquals(GoogleImportStatus.NEEDS_AUTH, g.status)
        assertFalse(g.isFinal)
        val start = server.takeRequest()
        assertEquals("/v1/google-imports", start.url.encodedPath)
        assertEquals("k1", start.headers["Idempotency-Key"])
        assertEquals("""{"album_id":"a1","return_uri":"app.kinframe:/google-import"}""", start.body!!.utf8())
        val p = api.googleImport("gi1")
        assertEquals(9, p.progress.handled)
        assertEquals(listOf("s1"), p.sendIds)
        assertEquals("/v1/google-imports/gi1", server.takeRequest().url.encodedPath)
        assertTrue(api.cancelGoogleImport("gi1").isFinal)
        assertEquals("/v1/google-imports/gi1/cancel", server.takeRequest().url.encodedPath)
        api.myGoogleImports()
        assertEquals("GET", server.takeRequest().method)
    }

    // ---------------------------------------------------------------------------------------------- links

    @Test
    fun recognizesImmichShareLinks() {
        val l = PhotoSourceLinks.parse("Our garden: https://photos.peeters.be/share/0d1eAbC_-xyz123.")
        assertTrue(l is SourceLink.ImmichShare)
        l as SourceLink.ImmichShare
        assertEquals("https://photos.peeters.be/share/0d1eAbC_-xyz123", l.url)
        assertEquals("0d1eAbC_-xyz123", l.key)
        assertFalse(l.privateHost)
        assertEquals(PhotoSourceProvider.IMMICH_LINK, l.provider)
        val slug = PhotoSourceLinks.parse("https://peeters.be/immich/s/garden-2026") as SourceLink.ImmichShare
        assertEquals("garden-2026", slug.key)
        val lan = PhotoSourceLinks.parse("http://192.168.1.10:2283/share/abcdEFGH") as SourceLink.ImmichShare
        assertTrue(lan.privateHost)
        assertTrue(PhotoSourceLinks.parse("https://photos.peeters.be/albums/123") is SourceLink.Other)
        assertNull(PhotoSourceLinks.parse("no link here"))
    }

    @Test
    fun recognizesICloudLinks() {
        val a = PhotoSourceLinks.parse("https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz")
        assertEquals(SourceLink.ICloudSharedAlbum("https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz", "B0aGWZuqDGxyz"), a)
        assertEquals(PhotoSourceProvider.ICLOUD_LINK, a?.provider)
        assertTrue(PhotoSourceLinks.parse("https://icloud.com/sharedalbum/nl-nl/#B0aGWZuqDGxyz") is SourceLink.ICloudSharedAlbum)
        assertTrue("a few photos, not an album", PhotoSourceLinks.parse("https://www.icloud.com/photos/#0abcDEF123") is SourceLink.ICloudPhotosLink)
        assertTrue("token cut off", PhotoSourceLinks.parse("https://www.icloud.com/sharedalbum/") is SourceLink.ICloudIncomplete)
        assertNull(PhotoSourceLinks.parse("https://www.icloud.com/photos/#0abc")?.provider)
    }

    @Test
    fun normalizesImmichServerAddresses() {
        assertEquals(ServerAddress.Ok("https://photos.peeters.be", "photos.peeters.be", false), PhotoSourceLinks.serverAddress(" photos.peeters.be/api/ "))
        assertEquals("https://peeters.be/immich", (PhotoSourceLinks.serverAddress("https://peeters.be/immich/api") as ServerAddress.Ok).url)
        assertEquals(ServerAddress.NeedsHttps, PhotoSourceLinks.serverAddress("http://photos.peeters.be"))
        val lan = PhotoSourceLinks.serverAddress("http://192.168.1.10:2283") as ServerAddress.Ok
        assertEquals("http://192.168.1.10:2283", lan.url)
        assertTrue(lan.privateHost)
        assertEquals(ServerAddress.Invalid, PhotoSourceLinks.serverAddress("  "))
        assertEquals(ServerAddress.Invalid, PhotoSourceLinks.serverAddress("not a url at all"))
    }

    @Test
    fun privateHosts() {
        listOf("10.0.0.5", "172.20.1.1", "192.168.1.10", "100.101.102.103", "127.0.0.1", "169.254.1.1", "localhost", "immich.lan",
            "nas.local", "fd12::1", "::1", "nas").forEach { assertTrue(it, PhotoSourceLinks.isPrivateHost(it)) }
        listOf("8.8.8.8", "172.32.0.1", "photos.peeters.be", "100.128.0.1", "2001:db8::1").forEach { assertFalse(it, PhotoSourceLinks.isPrivateHost(it)) }
        assertEquals("photos.peeters.be", PhotoSourceLinks.hostOf("https://photos.peeters.be/share/…"))
        assertNull(PhotoSourceLinks.hostOf(null))
    }
}
