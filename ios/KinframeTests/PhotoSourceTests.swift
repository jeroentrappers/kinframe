@testable import KinframeKit
import XCTest

/// Photo sources and Google Photos imports: decoding of the contract's schemas (also with enum values a newer server
/// may send), link parsing and validation, request bodies, status and error texts with their fixes, credits, the
/// Google import flow, live events, push routes, discovery and "Only in album mode". Fixtures follow
/// docs/api/openapi.yaml and the examples in docs/spec/photo-sources.md.
enum SourceFixtures {
    static func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try OutboxRunner.decoder.decode(type, from: Data(json.utf8))
    }

    static func source(status: String = "active", running: Bool = false, provider: String = "immich_link", error: String? = nil,
                       warnings: [String] = [], pending: Int = 0, pendingRemovals: Int = 0, target: String = album,
                       isOwner: Bool = true, synced: String? = "2026-11-19T10:00:00Z", next: String? = "2026-11-19T10:30:00Z") -> String {
        """
        {"id":"src1","provider":"\(provider)","name":"Garden 2026","target":\(target),
         "owner":{"user_id":"u1","display_name":"Anna","avatar_url":null},"best_effort":\(provider == "icloud_link"),
         "url_hint":"https://photos.peeters.be/share/…","server_url":null,"immich_album_id":null,"has_api_key":false,"has_password":false,
         "status":"\(status)","running":\(running),"warnings":[\(warnings.map { "\"\($0)\"" }.joined(separator: ","))],
         "last_error_code":\(error.map { "\"\($0)\"" } ?? "null"),"last_error":null,"error_since":"2026-11-17T10:00:00Z",
         "pending_removals":\(pendingRemovals),"history":"all","history_count":null,"on_upstream_delete":"mirror","include_videos":true,
         "notify_reactions":true,
         "counts":{"upstream":220,"imported":40,"duplicates":2,"pending":\(pending),"skipped":3,"failed":0,"removed_locally":0,"gone_upstream":0},
         "last_run":{"started_at":"2026-11-19T10:00:00Z","finished_at":"2026-11-19T10:02:00Z","added":40,"updated":0,"removed":0,"skipped":3,
                     "failed":0,"bytes":123456,"error_code":null},
         "last_synced_at":\(synced.map { "\"\($0)\"" } ?? "null"),"last_attempt_at":"2026-11-19T10:00:00Z",
         "next_sync_at":\(next.map { "\"\($0)\"" } ?? "null"),"can_manage":true,"is_owner":\(isOwner),"created_at":"2026-11-18T09:00:00Z"}
        """
    }

    static let album = """
    {"kind":"album","frame_id":"f1","frame_name":"Oma’s frame","album_id":"a1","album_name":"Garden","feed_id":null,"feed_name":null}
    """
    static let feed = """
    {"kind":"feed","frame_id":null,"frame_name":null,"album_id":null,"album_name":null,"feed_id":"feed1","feed_name":"Peeters kids"}
    """

    static func googleImport(status: String, picked: Int = 23, imported: Int = 0, duplicates: Int = 0, failed: Int = 0, error: String? = nil,
                             id: String = "imp1") -> String {
        """
        {"id":"\(id)","status":"\(status)","target":\(album),
         "auth_url":\(status == "needs_auth" ? "\"https://accounts.google.com/o/oauth2/v2/auth?x=1\"" : "null"),
         "picker_uri":\(status == "picking" ? "\"https://photos.google.com/picker/abc\"" : "null"),"picker_expires_at":null,
         "progress":{"picked":\(picked),"imported":\(imported),"duplicates":\(duplicates),"skipped":0,"failed":\(failed),"bytes":0},
         "send_ids":\(imported > 0 ? "[\"send1\"]" : "[]"),"error_code":\(error.map { "\"\($0)\"" } ?? "null"),
         "created_at":"2026-11-19T10:00:00Z","updated_at":null,"finished_at":null}
        """
    }
}

final class PhotoSourceDecodingTests: XCTestCase {
    func testSourceDecodes() throws {
        let s = try SourceFixtures.decode(PhotoSource.self, SourceFixtures.source(warnings: ["downloads_disabled"]))
        XCTAssertEqual(s.kind, .immichLink)
        XCTAssertEqual(s.state, .active)
        XCTAssertEqual(s.knownWarnings, [.downloadsDisabled])
        XCTAssertEqual(s.historyKind, .all)
        XCTAssertEqual(s.deletePolicy, .mirror)
        XCTAssertEqual(s.counts.imported, 40)
        XCTAssertEqual(s.owner?.displayName, "Anna")
        XCTAssertEqual(s.target.kind, .album)
        XCTAssertEqual(s.serviceLabel, "Immich")
        XCTAssertFalse(s.isFeedTarget)
    }

    func testUnknownEnumValuesDoNotBreakDecoding() throws {
        let json = SourceFixtures.source(status: "archived", provider: "nextcloud", error: "quota_exceeded", warnings: ["slow", "metadata_hidden"])
            .replacingOccurrences(of: #""history":"all""#, with: #""history":"since_date""#)
            .replacingOccurrences(of: #""on_upstream_delete":"mirror""#, with: #""on_upstream_delete":"archive""#)
        let s = try SourceFixtures.decode(PhotoSource.self, json)
        XCTAssertNil(s.kind)
        XCTAssertNil(s.state)
        XCTAssertNil(s.errorCode)
        XCTAssertNil(s.historyKind)
        XCTAssertNil(s.deletePolicy)
        XCTAssertEqual(s.knownWarnings, [.metadataHidden], "unknown warnings are left out")
        XCTAssertEqual(SourceStatusText.headline(s).key, "ios.ps.status.unknown")
        XCTAssertEqual(SourceStatusText.fix(s), .syncNow, "an unknown error offers Sync now, nothing specific")
        XCTAssertEqual(s.serviceLabel, "nextcloud")
        // A whole list with one future source still decodes.
        let list = try SourceFixtures.decode([PhotoSource].self, "[\(json),\(SourceFixtures.source())]")
        XCTAssertEqual(list.count, 2)
    }

    func testItemViaDecodesWithKnownAndUnknownServices() throws {
        let item = try SourceFixtures.decode(Item.self, SchemaFixtures.item.replacingOccurrences(
            of: #""thumb_url""#, with: #""via":{"service":"immich","label":"Immich","source_id":"src1"},"thumb_url""#))
        XCTAssertEqual(item.via?.knownService, .immich)
        XCTAssertEqual(item.via?.sourceId, "src1")
        let future = try SourceFixtures.decode(Item.self, SchemaFixtures.item.replacingOccurrences(
            of: #""thumb_url""#, with: #""via":{"service":"nextcloud","label":"","source_id":null},"thumb_url""#))
        XCTAssertNil(future.via?.knownService)
        XCTAssertEqual(future.via?.displayLabel, "nextcloud")
        let plain = try SourceFixtures.decode(Item.self, SchemaFixtures.item)
        XCTAssertNil(plain.via)
    }

    func testFeedPostAndSendCarryVia() throws {
        let post = try SourceFixtures.decode(FeedPost.self, SchemaFixtures.post.replacingOccurrences(
            of: #""send_id":"send1","#, with: #""send_id":"send1","via":{"service":"icloud","label":"iCloud","source_id":"src2"},"#))
        XCTAssertEqual(post.via?.displayLabel, "iCloud")
        let send = try SourceFixtures.decode(Send.self, #"{"id":"send1","created_at":"2026-11-19T10:00:00Z","items":[],"frames":[],"via":{"service":"google_photos","label":"Google Photos"}}"#)
        XCTAssertEqual(send.via?.knownService, .googlePhotos)
    }

    func testTestResultAndImmichAlbums() throws {
        let r = try SourceFixtures.decode(PhotoSourceTestResult.self, """
        {"ok":true,"error_code":null,"error":null,"album_name":"Garden 2026","photos":214,"videos":6,"estimate_bytes":1100000000,
         "warnings":["metadata_hidden","future_warning"],"best_effort":false,"server_version":"v1.132.3","albums":[]}
        """)
        XCTAssertTrue(r.ok)
        XCTAssertEqual(SourceStatusText.warnings(r.warnings).map(\.key), ["ios.ps.warn.metadata_hidden"])
        let failed = try SourceFixtures.decode(PhotoSourceTestResult.self, #"{"ok":false,"error_code":"some_new_code","error":"x"}"#)
        XCTAssertEqual(SourceStatusText.testError(failed.errorCode, provider: .immichLink, host: "photos.peeters.be").key, "ios.ps.err.unknown")
        let albums = try SourceFixtures.decode(ImmichAlbumList.self, """
        {"ok":true,"error_code":null,"server_version":"v1.120.0","albums":[{"id":"al1","name":"Christmas","asset_count":120,
         "updated_at":"2026-11-01T10:00:00Z","owner_name":"Ben","shared":false,"thumbnail_url":null}]}
        """)
        XCTAssertEqual(albums.albums.first?.name, "Christmas")
    }

    func testGoogleImportDecodesWithUnknownStatus() throws {
        let imp = try SourceFixtures.decode(GoogleImport.self, SourceFixtures.googleImport(status: "picking"))
        XCTAssertEqual(imp.state, .picking)
        XCTAssertEqual(imp.pickerUri, "https://photos.google.com/picker/abc")
        let future = try SourceFixtures.decode(GoogleImport.self, SourceFixtures.googleImport(status: "verifying", error: "brand_new"))
        XCTAssertNil(future.state)
        XCTAssertNil(future.errorKind)
    }

    func testAlbumOnlyInAlbumMode() throws {
        let a = try SourceFixtures.decode(Album.self, #"{"id":"a1","name":"Garden","version":3,"placement_count":220,"photo_source_count":1,"only_in_album_mode":true}"#)
        XCTAssertEqual(a.onlyInAlbumMode, true)
        XCTAssertEqual(a.photoSourceCount, 1)
        let old = try SourceFixtures.decode(Album.self, #"{"id":"a1","name":"Garden","version":3,"placement_count":2}"#)
        XCTAssertNil(old.onlyInAlbumMode, "absent: the default (off)")
        // Encoding: the generated AlbumWrite carries it, and the PATCH body sets it.
        let write = Components.Schemas.AlbumWrite(onlyInAlbumMode: true)
        let json = String(decoding: try JSONEncoder().encode(write), as: UTF8.self)
        XCTAssertTrue(json.contains(#""only_in_album_mode":true"#), json)
        XCTAssertEqual(AlbumLogic.onlyInAlbumModeBody(false), ["only_in_album_mode": .bool(false)])
    }

    func testOnlyInAlbumModeSuggestion() throws {
        let a = try SourceFixtures.decode(Album.self, #"{"id":"a1","name":"Garden","version":3,"placement_count":0}"#)
        XCTAssertTrue(AlbumLogic.suggestsOnlyInAlbumMode(album: a, history: .all, upstream: 220))
        XCTAssertFalse(AlbumLogic.suggestsOnlyInAlbumMode(album: a, history: .all, upstream: 200), "more than 200")
        XCTAssertFalse(AlbumLogic.suggestsOnlyInAlbumMode(album: a, history: .lastN(50), upstream: 3000))
        XCTAssertTrue(AlbumLogic.suggestsOnlyInAlbumMode(album: a, history: .lastN(500), upstream: 3000))
        XCTAssertFalse(AlbumLogic.suggestsOnlyInAlbumMode(album: a, history: .none, upstream: 3000))
        XCTAssertFalse(AlbumLogic.suggestsOnlyInAlbumMode(album: nil, history: .all, upstream: 3000), "feeds: no album")
        let already = try SourceFixtures.decode(Album.self, #"{"id":"a1","name":"Garden","version":3,"placement_count":0,"only_in_album_mode":true}"#)
        XCTAssertFalse(AlbumLogic.suggestsOnlyInAlbumMode(album: already, history: .all, upstream: 3000))
    }

    func testDiscoveryAndLimits() throws {
        let on = try JSONDecoder().decode(Discovery.self, from: Data("""
        {"name":"k","api":"https://k/v1","photo_sources":true,"photo_source_providers":["immich_link","icloud_link","dropbox"],
         "photo_sources_private_networks":true,"google_photos_import":true,
         "limits":{"max_photo_sources_per_target":3,"max_photo_source_items":5000,"max_google_import_items":100}}
        """.utf8))
        let f = PhotoSourceFeatures(on)
        XCTAssertEqual(f.providers, [.immichLink, .icloudLink], "unknown providers are skipped, the order is ours")
        XCTAssertTrue(f.privateNetworks)
        XCTAssertTrue(f.googleImport)
        XCTAssertEqual(on.limits.maxPhotoSourcesPerTarget, 3)
        XCTAssertEqual(on.limits.maxPhotoSourceItems, 5000)
        XCTAssertEqual(on.limits.maxGoogleImportItems, 100)
        XCTAssertEqual(on.limits.maxPhotoSourcesPerUser, 20, "default")
        let off = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k/v1","photo_source_providers":["immich_link"]}"#.utf8))
        XCTAssertFalse(PhotoSourceFeatures(off).sources, "providers without photo_sources are ignored")
        XCTAssertFalse(PhotoSourceFeatures(off).googleImport)
        XCTAssertFalse(PhotoSourceFeatures(nil).sources)
        let again = try JSONDecoder().decode(Discovery.self, from: JSONEncoder().encode(on))
        XCTAssertEqual(again, on, "stored and read back")
        XCTAssertEqual(SourceDefaults.providers([], enabled: true), [.immichLink, .immichApi], "an older server without the list")
    }
}

final class SourceLinkTests: XCTestCase {
    func testParsing() {
        XCTAssertEqual(SourceLink.parse("https://photos.example.org/share/0d1eAbC")?.host, "photos.example.org")
        if case .immichShare = SourceLink.parse("https://photos.example.org/share/0d1eAbC") {} else { XCTFail("immich share") }
        if case .immichShare = SourceLink.parse("https://example.org/immich/s/our-garden") {} else { XCTFail("slug under a prefix") }
        if case .immichShare = SourceLink.parse("photos.example.org/share/key") {} else { XCTFail("without scheme") }
        if case .icloudSharedAlbum = SourceLink.parse("https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz") {} else { XCTFail("icloud") }
        if case .icloudSharedAlbum = SourceLink.parse("https://icloud.com/sharedalbum/nl-nl/#B0aGWZuqDGxyz") {} else { XCTFail("language segment") }
        XCTAssertNil(SourceLink.parse("https://www.icloud.com/sharedalbum/"), "no token")
        XCTAssertEqual(SourceLink.parse("https://www.icloud.com/photos/#0abcDEF"), .icloudPhotosLink)
        if case .server = SourceLink.parse("https://photos.peeters.be") {} else { XCTFail("server") }
        XCTAssertNil(SourceLink.parse("hello"))
        XCTAssertNil(SourceLink.parse(""))
    }

    func testExtractFromPastedText() {
        XCTAssertEqual(SourceLink.extract(from: "Our album: https://photos.example.org/share/abc. Enjoy!"), "https://photos.example.org/share/abc")
        XCTAssertEqual(SourceLink.extract(from: "<https://www.icloud.com/sharedalbum/#B0a>"), "https://www.icloud.com/sharedalbum/#B0a")
        XCTAssertEqual(SourceLink.extract(from: "  photos.example.org/share/abc \n"), "photos.example.org/share/abc")
        XCTAssertNil(SourceLink.extract(from: "anna@example.org"))
        XCTAssertNil(SourceLink.extract(from: "no link here"))
    }

    func testValidation() {
        let immich = "https://photos.example.org/share/abc"
        let icloud = "https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz"
        XCTAssertNil(SourceInput.problem(immich, provider: .immichLink, privateNetworks: false))
        XCTAssertEqual(SourceInput.problem("", provider: .immichLink, privateNetworks: false), .empty)
        XCTAssertEqual(SourceInput.problem("not a link", provider: .immichLink, privateNetworks: false), .notALink)
        XCTAssertEqual(SourceInput.problem(icloud, provider: .immichLink, privateNetworks: false), .wrongProvider(.icloudLink))
        XCTAssertEqual(SourceInput.problem(immich, provider: .icloudLink, privateNetworks: false), .wrongProvider(.immichLink))
        XCTAssertNil(SourceInput.problem(icloud, provider: .icloudLink, privateNetworks: false))
        XCTAssertEqual(SourceInput.problem("https://www.icloud.com/photos/#abc", provider: .icloudLink, privateNetworks: false), .icloudPhotosLink)
        XCTAssertEqual(SourceInput.problem("https://photos.example.org", provider: .immichLink, privateNetworks: false), .notAShareLink)
        XCTAssertNil(SourceInput.problem("https://photos.example.org", provider: .immichApi, privateNetworks: false))
        XCTAssertEqual(SourceInput.problem(immich, provider: .immichApi, privateNetworks: false), .wrongProvider(.immichLink))
        // Home networks.
        XCTAssertEqual(SourceInput.problem("http://192.168.1.10:2283", provider: .immichApi, privateNetworks: false), .privateAddressBlocked)
        XCTAssertNil(SourceInput.problem("http://192.168.1.10:2283", provider: .immichApi, privateNetworks: true))
        XCTAssertEqual(SourceInput.problem("http://photos.example.org", provider: .immichApi, privateNetworks: true), .httpNeedsHomeNetwork)
        XCTAssertEqual(SourceInput.problem("http://nas.local:2283/share/abc", provider: .immichLink, privateNetworks: false), .privateAddressBlocked)
    }

    func testHomeNetworkHosts() {
        for h in ["192.168.1.10", "10.0.0.2", "172.20.1.1", "127.0.0.1", "100.100.1.1", "169.254.1.1", "nas.local", "immich", "localhost",
                  "fd00::1", "[::1]", "photos.home.arpa", "box.tail1234.ts.net"] {
            XCTAssertTrue(HomeNetwork.isPrivate(host: h), h)
        }
        for h in ["photos.example.org", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1"] {
            XCTAssertFalse(HomeNetwork.isPrivate(host: h), h)
        }
    }

    func testCleaning() {
        XCTAssertEqual(SourceInput.cleanLink(" photos.example.org/share/abc "), "https://photos.example.org/share/abc")
        XCTAssertEqual(SourceInput.cleanServer("photos.example.org/"), "https://photos.example.org")
        XCTAssertEqual(SourceInput.cleanServer("https://photos.example.org/api/?x=1"), "https://photos.example.org/api")
    }
}

final class PhotoSourceBodyTests: XCTestCase {
    private func json<T: Encodable>(_ v: T) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: JSONEncoder().encode(v)) as! [String: Any]
    }

    func testDefaultsPerTarget() {
        let album = SourceTargetRef.album(frameId: "f1", albumId: "a1")
        XCTAssertEqual(SourceDefaults.history(for: album), .all)
        XCTAssertEqual(SourceDefaults.deletePolicy(for: album), .mirror)
        XCTAssertEqual(SourceDefaults.history(for: .feed("feed1")), .lastN(20))
        XCTAssertEqual(SourceDefaults.deletePolicy(for: .feed("feed1")), .keep)
    }

    func testCreateBodyForALink() throws {
        var d = SourceDraft(connection: SourceConnection(provider: .immichLink, link: "photos.example.org/share/abc", password: " pw "),
                            target: .feed("feed1"))
        d.name = "  Kids  "
        let o = try json(d.createBody)
        XCTAssertEqual(o["provider"] as? String, "immich_link")
        XCTAssertEqual(o["url"] as? String, "https://photos.example.org/share/abc")
        XCTAssertEqual(o["password"] as? String, "pw")
        XCTAssertEqual(o["feed_id"] as? String, "feed1")
        XCTAssertNil(o["album_id"])
        XCTAssertEqual(o["name"] as? String, "Kids")
        XCTAssertEqual(o["history"] as? String, "last_n")
        XCTAssertEqual(o["history_count"] as? Int, 20)
        XCTAssertEqual(o["on_upstream_delete"] as? String, "keep")
        XCTAssertNil(o["server_url"])
    }

    func testCreateBodyForImmichApi() throws {
        let c = SourceConnection(provider: .immichApi, serverURL: "photos.peeters.be/", apiKey: " key12345 ", immichAlbumId: "al1")
        let d = SourceDraft(connection: c, target: .album(frameId: "f1", albumId: "a1"))
        let o = try json(d.createBody)
        XCTAssertEqual(o["server_url"] as? String, "https://photos.peeters.be")
        XCTAssertEqual(o["api_key"] as? String, "key12345")
        XCTAssertEqual(o["immich_album_id"] as? String, "al1")
        XCTAssertEqual(o["album_id"] as? String, "a1")
        XCTAssertEqual(o["history"] as? String, "all")
        XCTAssertNil(o["history_count"])
        XCTAssertNil(o["url"])
        XCTAssertNil(c.problem(privateNetworks: false))
        XCTAssertEqual(SourceConnection(provider: .immichApi, serverURL: "https://p.example.org", apiKey: "short").problem(privateNetworks: false), .empty)
    }

    func testPatch() throws {
        let s = try SourceFixtures.decode(PhotoSource.self, SourceFixtures.source())
        XCTAssertTrue(SourcePatch.body(s, name: "Garden 2026", deletePolicy: .mirror, includeVideos: true, notifyReactions: true, newLink: "",
                                       newPassword: nil, removePassword: false, newServerURL: "", newApiKey: "", newAlbumId: nil).isEmpty)
        let p = SourcePatch.body(s, name: "Tuin", deletePolicy: .keep, includeVideos: false, notifyReactions: false,
                                 newLink: "photos.example.org/share/new", newPassword: nil, removePassword: true,
                                 newServerURL: "", newApiKey: "", newAlbumId: nil, takeOver: true)
        XCTAssertEqual(p["name"], .string("Tuin"))
        XCTAssertEqual(p["on_upstream_delete"], .string("keep"))
        XCTAssertEqual(p["include_videos"], .bool(false))
        XCTAssertEqual(p["notify_reactions"], .bool(false))
        XCTAssertEqual(p["url"], .string("https://photos.example.org/share/new"))
        XCTAssertEqual(p["password"], .null, "removing the password is an explicit null")
        XCTAssertEqual(p["take_over"], .bool(true))
        // Only the owner changes notify_reactions.
        let other = try SourceFixtures.decode(PhotoSource.self, SourceFixtures.source(isOwner: false))
        XCTAssertNil(SourcePatch.body(other, name: "", deletePolicy: .mirror, includeVideos: true, notifyReactions: false, newLink: "",
                                      newPassword: nil, removePassword: false, newServerURL: "", newApiKey: "", newAlbumId: nil)["notify_reactions"])
    }

    func testHistoryChoiceClamps() {
        XCTAssertEqual(SourceHistoryChoice.lastN(0).count, 1)
        XCTAssertEqual(SourceHistoryChoice.lastN(50_000).count, 10_000)
        XCTAssertNil(SourceHistoryChoice.all.count)
    }
}

final class PhotoSourceStatusTests: XCTestCase {
    private func src(_ status: String = "active", running: Bool = false, provider: String = "immich_link", error: String? = nil,
                     pending: Int = 0, removals: Int = 0, target: String = SourceFixtures.album, synced: String? = "2026-11-19T10:00:00Z") throws -> PhotoSource {
        try SourceFixtures.decode(PhotoSource.self, SourceFixtures.source(status: status, running: running, provider: provider, error: error,
                                                                          pending: pending, pendingRemovals: removals, target: target, synced: synced))
    }

    func testHeadlines() throws {
        let now = ISO8601DateFormatter().date(from: "2026-11-19T10:05:00Z")!
        XCTAssertEqual(SourceStatusText.headline(try src(), now: now).key, "ios.ps.status.synced")
        XCTAssertEqual(SourceStatusText.headline(try src(), now: now).args["service"], "Immich")
        XCTAssertEqual(SourceStatusText.headline(try src(), now: now).tone, .ok)
        XCTAssertEqual(SourceStatusText.headline(try src(synced: nil), now: now).key, "ios.ps.status.firstSync")
        XCTAssertEqual(SourceStatusText.headline(try src(running: true), now: now).key, "ios.ps.status.syncing")
        let importing = SourceStatusText.headline(try src(running: true, pending: 180), now: now)
        XCTAssertEqual(importing.key, "ios.ps.status.importing")
        XCTAssertEqual(importing.args, ["done": "40", "total": "220"], "importing 40 of 220")
        XCTAssertEqual(SourceStatusText.headline(try src("paused"), now: now).key, "ios.ps.status.paused")
        XCTAssertEqual(SourceStatusText.headline(try src("paused"), now: now).tone, .paused)
        let down = SourceStatusText.headline(try src("error", error: "unreachable"), now: now)
        XCTAssertEqual(down.key, "ios.ps.err.unreachable.since")
        XCTAssertEqual(down.args["host"], "photos.peeters.be")
        XCTAssertEqual(down.tone, .error)
        let gone = SourceStatusText.headline(try src("needs_attention", error: "link_not_found"), now: now)
        XCTAssertEqual(gone.key, "ios.ps.err.link_not_found")
        XCTAssertEqual(gone.tone, .attention)
        XCTAssertEqual(SourceStatusText.headline(try src("needs_attention", provider: "icloud_link", error: "link_not_found"), now: now).key,
                       "ios.ps.err.link_not_found.icloud")
        XCTAssertEqual(SourceStatusText.headline(try src("needs_attention"), now: now).key, "ios.ps.status.attentionGeneric")
    }

    func testErrorTextsAndFixes() throws {
        let cases: [(String, String, String, SourceFix)] = [
            ("immich_link", "auth_failed", "ios.ps.err.auth_failed.password", .enterPassword),
            ("immich_api", "auth_failed", "ios.ps.err.auth_failed.key", .replaceKey),
            ("immich_link", "password_required", "ios.ps.err.password_required", .enterPassword),
            ("immich_api", "permission_missing", "ios.ps.err.permission_missing", .replaceKey),
            ("immich_link", "link_expired", "ios.ps.err.link_expired", .replaceLink),
            ("immich_api", "album_not_found", "ios.ps.err.album_not_found", .pickAlbum),
            ("immich_link", "album_not_found", "ios.ps.err.album_not_found", .replaceLink),
            ("immich_link", "tls_error", "ios.ps.err.tls_error", .replaceLink),
            ("immich_link", "too_large", "ios.ps.err.too_large", .none),
            ("icloud_link", "format_changed", "ios.ps.err.format_changed", .none),
            ("immich_link", "rate_limited_upstream", "ios.ps.err.rate_limited_upstream", .syncNow),
            ("immich_api", "credentials_unreadable", "ios.ps.err.credentials_unreadable", .replaceKey),
            ("immich_link", "owner_lost_access", "ios.ps.err.owner_lost_access.album", .takeOver),
        ]
        for (provider, code, key, fix) in cases {
            let s = try src("needs_attention", provider: provider, error: code)
            XCTAssertEqual(SourceStatusText.error(s)?.key, key, code)
            XCTAssertEqual(SourceStatusText.fix(s), fix, code)
        }
        let feedOwner = try src("needs_attention", error: "owner_lost_access", target: SourceFixtures.feed)
        XCTAssertEqual(SourceStatusText.error(feedOwner)?.key, "ios.ps.err.owner_lost_access.feed")
        XCTAssertEqual(SourceStatusText.error(feedOwner)?.args["name"], "Anna")
        let mass = try src("needs_attention", error: "mass_removal", removals: 342)
        XCTAssertEqual(SourceStatusText.error(mass)?.args["count"], "342")
        XCTAssertEqual(SourceStatusText.fix(mass), .confirmRemovals)
        XCTAssertEqual(SourceStatusText.fixKey(.confirmRemovals), "ios.ps.fix.confirmRemovals")
        XCTAssertNil(SourceStatusText.fixKey(.none))
        XCTAssertEqual(SourceStatusText.fix(try src()), .none, "no error, nothing to fix")
        // A retried single failure keeps the source active, shown as a muted note.
        XCTAssertEqual(SourceStatusText.error(try src(error: "unreachable"))?.tone, .muted)
    }

    func testNextAndCounts() throws {
        let now = ISO8601DateFormatter().date(from: "2026-11-19T10:05:00Z")!
        XCTAssertEqual(SourceStatusText.next(try src(), now: now)?.key, "ios.ps.status.next")
        XCTAssertNil(SourceStatusText.next(try src("paused"), now: now))
        XCTAssertNil(SourceStatusText.next(try src("needs_attention", error: "auth_failed"), now: now))
        XCTAssertNil(SourceStatusText.next(try src(running: true), now: now))
        let soon = ISO8601DateFormatter().date(from: "2026-11-19T10:29:30Z")!
        XCTAssertEqual(SourceStatusText.next(try src(), now: soon)?.key, "ios.ps.status.nextSoon")
        XCTAssertEqual(SourceStatusText.counts(try src(pending: 14)).map(\.key),
                       ["ios.ps.count.imported", "ios.ps.count.upstream", "ios.ps.count.pending", "ios.ps.count.duplicates", "ios.ps.count.skipped"])
        let s = try SourceFixtures.decode(PhotoSource.self, SourceFixtures.source(warnings: ["item_limit_reached"]))
        XCTAssertEqual(SourceStatusText.warnings(s, limits: Limits()).first?.args["count"], "10000")
    }

    func testTargetsAndCreateErrors() throws {
        let s = try src(target: SourceFixtures.feed)
        XCTAssertEqual(SourceStatusText.target(s.target), SourceLine("ios.ps.target.feed", ["feed": "Peeters kids"]))
        let a = try src()
        XCTAssertEqual(SourceStatusText.target(a.target).args, ["album": "Garden", "frame": "Oma’s frame"])
        var e = APIError(status: 422, code: "source_test_failed", detail: "link_not_found")
        XCTAssertEqual(SourceErrors.line(e, provider: .icloudLink, host: nil)?.key, "ios.ps.err.link_not_found.icloud")
        e = APIError(status: 409, code: "duplicate_source", detail: "src9")
        XCTAssertEqual(SourceErrors.line(e, provider: .immichLink, host: nil)?.key, "ios.ps.err.duplicate")
        XCTAssertEqual(SourceErrors.line(APIError(status: 409, code: "limit_reached"), provider: .immichLink, host: nil)?.key, "ios.ps.err.limit")
        XCTAssertNil(SourceErrors.line(APIError(status: 500, code: "server_error"), provider: .immichLink, host: nil))
    }
}

final class CreditTests: XCTestCase {
    private let en = "{name} via {service}"

    func testFormats() throws {
        let via = try SourceFixtures.decode(ItemVia.self, #"{"service":"immich","label":"Immich","source_id":"src1"}"#)
        XCTAssertEqual(Credit.sender("Anna", via: via, template: en), "Anna via Immich")
        XCTAssertEqual(Credit.sender("Anna", via: via, feed: "Peeters kids", template: en), "Anna · Peeters kids via Immich")
        XCTAssertEqual(Credit.sender("Anna", via: nil, template: en), "Anna")
        XCTAssertEqual(Credit.sender("Anna", via: nil, feed: "Peeters kids", template: en), "Anna · Peeters kids")
        XCTAssertEqual(Credit.sender("12 photos", via: via, template: en), "12 photos via Immich")
        XCTAssertEqual(Credit.sender("Anna", via: via, template: "ios.ps.via"), "Anna via Immich", "a missing catalog falls back to English")
        let unlabeled = try SourceFixtures.decode(ItemVia.self, #"{"service":"google_photos","label":" "}"#)
        XCTAssertEqual(Credit.sender("Ben", via: unlabeled, template: en), "Ben via Google Photos")
    }

    func testItems() throws {
        let item = try SourceFixtures.decode(Item.self, SchemaFixtures.item.replacingOccurrences(
            of: #""thumb_url""#, with: #""via":{"service":"icloud","label":"iCloud","source_id":null},"thumb_url""#))
        XCTAssertEqual(Credit.item(item, template: en), "Ben via iCloud")
        XCTAssertEqual(Credit.item(try SourceFixtures.decode(Item.self, SchemaFixtures.item), template: en), "Ben")
    }
}

final class GoogleImportFlowTests: XCTestCase {
    private func imp(_ status: String, imported: Int = 0, duplicates: Int = 0, failed: Int = 0, error: String? = nil, id: String = "imp1") throws -> GoogleImport {
        try SourceFixtures.decode(GoogleImport.self, SourceFixtures.googleImport(status: status, imported: imported, duplicates: duplicates,
                                                                                 failed: failed, error: error, id: id))
    }

    func testHappyPath() throws {
        var f = GoogleImportFlow()
        XCTAssertEqual(f.handle(.start), [.post])
        XCTAssertEqual(f.phase, .starting)
        XCTAssertEqual(f.handle(.start), [], "one at a time")
        let consent = URL(string: "https://accounts.google.com/o/oauth2/v2/auth?x=1")!
        XCTAssertEqual(f.handle(.update(try imp("needs_auth"))), [.openConsent(consent)])
        XCTAssertEqual(f.phase, .consent(consent))
        XCTAssertTrue(f.needsPolling)
        XCTAssertEqual(f.handle(.update(try imp("needs_auth"))), [], "the browser session opens once")
        XCTAssertEqual(f.handle(.consentReturned(importId: "imp1", status: "picking")), [.refresh])
        let picker = URL(string: "https://photos.google.com/picker/abc")!
        XCTAssertEqual(f.handle(.update(try imp("picking"))), [.openPicker(picker)])
        XCTAssertEqual(f.handle(.update(try imp("picking"))), [], "the picker opens once")
        XCTAssertEqual(f.reopenPicker(), [.openPicker(picker)], "unless asked again")
        XCTAssertEqual(f.handle(.appActive), [.refresh])
        XCTAssertEqual(f.handle(.update(try imp("importing", imported: 9))), [])
        XCTAssertEqual(f.phase, .importing(picked: 23, done: 9))
        XCTAssertTrue(f.canCancel)
        XCTAssertEqual(f.handle(.update(try imp("done", imported: 21, duplicates: 2))), [])
        XCTAssertEqual(f.phase, .finished(.done(imported: 21, duplicates: 2, skipped: 0, failed: 0)))
        XCTAssertFalse(f.needsPolling)
        XCTAssertEqual(f.sendIds, ["send1"])
        XCTAssertEqual(f.handle(.update(try imp("importing"))), [], "final states stay")
        XCTAssertEqual(f.handle(.appActive), [])
        XCTAssertEqual(f.handle(.start), [.post], "a new import after one finished")
        XCTAssertNil(f.importId)
    }

    func testFreshTokenSkipsConsent() throws {
        var f = GoogleImportFlow()
        _ = f.handle(.start)
        XCTAssertEqual(f.handle(.update(try imp("picking"))), [.openPicker(URL(string: "https://photos.google.com/picker/abc")!)])
    }

    func testDismissedConsentCancels() throws {
        var f = GoogleImportFlow()
        _ = f.handle(.start)
        _ = f.handle(.update(try imp("needs_auth")))
        XCTAssertEqual(f.handle(.consentDismissed), [.cancelOnServer])
        XCTAssertEqual(f.phase, .finished(.cancelled))
    }

    func testCancelWhileImporting() throws {
        var f = GoogleImportFlow()
        XCTAssertEqual(f.handle(.cancel), [], "nothing to cancel")
        _ = f.handle(.start)
        _ = f.handle(.update(try imp("importing", imported: 3)))
        XCTAssertEqual(f.handle(.cancel), [.cancelOnServer])
        XCTAssertEqual(f.phase, .finished(.cancelled))
        XCTAssertEqual(f.handle(.cancel), [])
    }

    func testFailuresAndForeignUpdates() throws {
        var f = GoogleImportFlow()
        _ = f.handle(.start)
        _ = f.handle(.update(try imp("picking")))
        XCTAssertEqual(f.handle(.update(try imp("done", id: "other"))), [], "another import's event")
        XCTAssertEqual(f.handle(.consentReturned(importId: "other", status: "failed")), [])
        _ = f.handle(.update(try imp("failed", error: "consent_denied")))
        XCTAssertEqual(f.phase, .finished(.failed("consent_denied")))
        XCTAssertEqual(GoogleImportText.outcome(.failed("consent_denied")).key, "ios.gi.err.consent_denied")
        XCTAssertEqual(GoogleImportText.outcome(.failed("brand_new")).key, "ios.gi.err.server_error")

        var g = GoogleImportFlow()
        _ = g.handle(.start)
        _ = g.handle(.update(try imp("expired")))
        XCTAssertEqual(g.phase, .finished(.expired))

        var h = GoogleImportFlow()
        _ = h.handle(.start)
        XCTAssertEqual(h.handle(.startFailed), [])
        XCTAssertEqual(h.phase, .idle)

        var u = GoogleImportFlow()
        _ = u.handle(.start)
        XCTAssertEqual(u.handle(.update(try imp("verifying"))), [], "an unknown status waits")
        XCTAssertTrue(u.needsPolling == false || u.importId == "imp1")
    }

    func testOutcomeTexts() {
        XCTAssertEqual(GoogleImportText.outcome(.done(imported: 23, duplicates: 0, skipped: 0, failed: 0)), SourceLine("ios.gi.done", ["count": "23"], tone: .ok))
        XCTAssertEqual(GoogleImportText.outcome(.done(imported: 21, duplicates: 2, skipped: 0, failed: 0)).key, "ios.gi.doneDuplicates")
        XCTAssertEqual(GoogleImportText.outcome(.done(imported: 21, duplicates: 0, skipped: 0, failed: 2)).key, "ios.gi.doneFailed")
        XCTAssertEqual(GoogleImportText.outcome(.done(imported: 20, duplicates: 1, skipped: 1, failed: 1)).args["failed"], "2")
        XCTAssertEqual(GoogleImportText.outcome(.done(imported: 0, duplicates: 0, skipped: 0, failed: 0)).key, "ios.gi.doneNothing")
        XCTAssertEqual(GoogleImportText.outcome(.expired).key, "ios.gi.expired")
    }

    func testReturnURIAndResume() throws {
        XCTAssertEqual(GoogleImportFlow.returnURI, "app.kinframe:/google-import")
        let r = GoogleImportFlow.parseReturn(URL(string: "app.kinframe:/google-import?import_id=imp1&status=picking")!)
        XCTAssertEqual(r?.importId, "imp1")
        XCTAssertEqual(r?.status, "picking")
        XCTAssertEqual(GoogleImportFlow.parseReturn(URL(string: "app.kinframe://google-import?import_id=imp2&status=failed")!)?.importId, "imp2")
        XCTAssertNil(GoogleImportFlow.parseReturn(URL(string: "app.kinframe:/callback?code=x")!))
        XCTAssertNil(GoogleImportFlow.parseReturn(URL(string: "https://kinframe.appmire.be/google-import/return?import_id=x")!))
        XCTAssertNil(DeepLink.parse(URL(string: "app.kinframe:/google-import?import_id=imp1&status=picking")!), "not a deep link")

        let now = ISO8601DateFormatter().date(from: "2026-11-19T10:30:00Z")!
        let list = [try imp("done", id: "old"), try imp("picking", id: "open")]
        XCTAssertEqual(GoogleImportFlow.resumable(list, now: now)?.id, "open")
        let later = ISO8601DateFormatter().date(from: "2026-11-19T13:00:00Z")!
        XCTAssertNil(GoogleImportFlow.resumable(list, now: later), "too old to pick up")
    }
}

final class PhotoSourceEventTests: XCTestCase {
    func testLiveEvents() throws {
        let updated = LiveEvent.parse(Data("{\"type\":\"photo_source.updated\",\"source\":\(SourceFixtures.source(status: "needs_attention", error: "mass_removal"))}".utf8))
        guard case .photoSourceUpdated(let s) = updated else { return XCTFail("updated") }
        XCTAssertEqual(s.errorCode, .massRemoval)
        XCTAssertEqual(LiveEvent.parse(Data(#"{"type":"photo_source.removed","source_id":"src1","album_id":"a1"}"#.utf8)),
                       .photoSourceRemoved(sourceId: "src1", albumId: "a1", feedId: nil))
        let gi = LiveEvent.parse(Data("{\"type\":\"google_import.updated\",\"import\":\(SourceFixtures.googleImport(status: "importing", imported: 5))}".utf8))
        guard case .googleImportUpdated(let i) = gi else { return XCTFail("import") }
        XCTAssertEqual(i.progress.imported, 5)
        // A future status still arrives.
        let future = LiveEvent.parse(Data("{\"type\":\"photo_source.updated\",\"source\":\(SourceFixtures.source(status: "archived"))}".utf8))
        if case .photoSourceUpdated = future {} else { XCTFail("future status") }
    }

    func testPushRoutes() {
        XCTAssertEqual(PushRoute.parse(["key": "photo_source_attention", "ids": ["source_id": "src1", "frame_id": "f1", "album_id": "a1"]]),
                       .photoSource(sourceId: "src1", frameId: "f1", albumId: "a1", feedId: nil))
        XCTAssertEqual(PushRoute.parse(["key": "photo_source_attention", "ids": ["source_id": "src1", "feed_id": "feed1"]]),
                       .photoSource(sourceId: "src1", frameId: nil, albumId: nil, feedId: "feed1"))
        XCTAssertNil(PushRoute.parse(["key": "photo_source_attention", "ids": [:] as [String: String]]))
        XCTAssertEqual(PushRoute.parse(["key": "google_import_done", "import_id": "imp1"]), .googleImport(importId: "imp1"))
        XCTAssertNil(PushRoute.parse(["key": "something_new"]))
    }
}
