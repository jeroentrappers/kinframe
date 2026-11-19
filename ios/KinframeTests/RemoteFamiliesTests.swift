@testable import KinframeKit
import XCTest

// Families on other servers (docs/spec/multi-server.md §3, README §5.22): invite links, labels of remote frames and
// photos, remote subscription states, blocked hosts, the live event and decoding. Fixtures follow openapi.yaml.
final class RemoteFamiliesTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try OutboxRunner.decoder.decode(type, from: Data(json.utf8))
    }

    func testDiscoveryFlag() throws {
        let on = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k.example/v1","remote_families":true,"server_name":"Janssens"}"#.utf8))
        XCTAssertTrue(on.remoteFamilies)
        XCTAssertEqual(on.serverName, "Janssens")
        let off = try JSONDecoder().decode(Discovery.self, from: Data(#"{"name":"k","api":"https://k.example/v1"}"#.utf8))
        XCTAssertFalse(off.remoteFamilies)
    }

    func testInviteLinks() {
        let token = "9cQ2xV0kFq7Lm3Zt8bW1rA"
        XCTAssertEqual(RemoteInvite.normalize("  https://Kinframe.Janssens.be/fi/\(token)  ")?.absoluteString,
                       "https://kinframe.janssens.be/fi/\(token)")
        XCTAssertEqual(RemoteInvite.normalize("kinframe.janssens.be/fi/\(token)?utm=x")?.absoluteString,
                       "https://kinframe.janssens.be/fi/\(token)")
        XCTAssertEqual(RemoteInvite.normalize("app.kinframe://fi/\(token)?server=https://kinframe.janssens.be")?.absoluteString,
                       "https://kinframe.janssens.be/fi/\(token)")
        XCTAssertNil(RemoteInvite.normalize("https://kinframe.janssens.be/i/\(token)"), "frame invites are not family invites")
        XCTAssertNil(RemoteInvite.normalize("https://kinframe.janssens.be/fi/short"))
        XCTAssertNil(RemoteInvite.normalize("KF7Q-M2XA"))
        XCTAssertNil(RemoteInvite.normalize(""))
    }

    func testFrameLabels() {
        XCTAssertEqual(RemoteLabel.frame("Oma's frame", server: "kinframe.appmire.be", template: "{frame} ({server})"),
                       "Oma's frame (kinframe.appmire.be)")
        XCTAssertEqual(RemoteLabel.frame("Oma's frame", server: nil), "Oma's frame")
        XCTAssertEqual(RemoteLabel.frame("Oma's frame", server: "  "), "Oma's frame")
    }

    func testThreadFrameEntryNamesTheServer() throws {
        let c = try decode(Comment.self, #"{"id":"c1","post_id":"p1","kind":"frame","frame":{"frame_id":"rs1","frame_name":"Oma’s frame","reaction":"love","server_name":"kinframe.appmire.be"},"reactions":{"total":0,"counts":[],"people":[],"frames":[]},"created_at":"2026-11-19T10:00:00Z"}"#)
        XCTAssertTrue(ThreadLogic.frameEntryText(c).contains("Oma’s frame (kinframe.appmire.be)"))
    }

    func testRemotePlacementCredit() throws {
        let item = #"{"id":"i1","kind":"photo","status":"ready","sender_id":"rs","sender_name":"Lies","created_at":"2026-11-19T10:00:00Z","remote_sender":{"name":"Lies","server_name":"Janssens","server_url":"https://kinframe.janssens.be"}}"#
        let remote = #""origin_remote":{"remote_subscription_id":"r1","server_name":"Janssens","server_url":"https://kinframe.janssens.be","feed_name":"Janssens","sender_name":"Lies"}"#
        let p = try decode(Placement.self, #"{"id":"pl1","frame_id":"f1","item":\#(item),"status":"ready","hidden":false,"trashed":false,"origin":"feed","origin_feed_name":"Janssens",\#(remote)}"#)
        XCTAssertEqual(RemoteLabel.credit(p), "Lies · Janssens")
        XCTAssertEqual(p.item.remoteSender?.serverName, "Janssens")
        let local = try decode(Placement.self, #"{"id":"pl2","frame_id":"f1","item":\#(item),"status":"ready","hidden":false,"trashed":false}"#)
        XCTAssertNil(RemoteLabel.credit(local))
    }

    private func sub(_ status: String, extra: String = "") -> String {
        #"{"id":"rs1","frame_id":"f1","status":"\#(status)","feed_name":"Janssens","server":{"server_name":"Janssens","server_url":"https://kinframe.janssens.be","host":"kinframe.janssens.be"},"history":"none","on_end":"remove","created_at":"2026-11-19T10:00:00Z"\#(extra)}"#
    }

    func testSubscriptionStates() throws {
        let pending = try decode(RemoteSubscription.self, sub("pending"))
        let active = try decode(RemoteSubscription.self, sub("active", extra: #","item_count":12,"backfill":{"state":"running","placed":3,"total":12}"#))
        let ended = try decode(RemoteSubscription.self, sub("removed", extra: #","removed_reason":"server_blocked""#))
        let error = try decode(RemoteSubscription.self, sub("error", extra: #","last_error":"timeout""#))
        XCTAssertTrue(RemoteSubscriptionLogic.isLive(pending.status))
        XCTAssertTrue(RemoteSubscriptionLogic.isLive(error.status), "errors are retried: still following")
        XCTAssertFalse(RemoteSubscriptionLogic.isLive(ended.status))
        XCTAssertEqual(RemoteSubscriptionLogic.sorted([ended, active, error, pending]).map(\.status), [.pending, .error, .active, .removed])
        XCTAssertEqual(RemoteSubscriptionLogic.backfillProgress(active), 0.25)
        XCTAssertNil(RemoteSubscriptionLogic.backfillProgress(pending))
        XCTAssertEqual(RemoteSubscriptionLogic.statusText(ended), L("servers.remote.reason.server_blocked"))
        XCTAssertEqual(RemoteInvite.host(active.server), "kinframe.janssens.be")
    }

    func testConfirmationNamesBothServers() throws {
        let p = try decode(RemoteInvitePreview.self, #"{"server":{"server_name":"Janssens","server_url":"https://kinframe.janssens.be","host":"kinframe.janssens.be"},"feed_name":"Janssens","frame_count":3,"requires_approval":false,"this_server_name":"kinframe","this_server_host":"kinframe.appmire.be"}"#)
        let text = RemoteSubscriptionLogic.confirmation(p, frameName: "Oma's frame")
        XCTAssertTrue(text.contains("kinframe.janssens.be"))
        XCTAssertTrue(text.contains("kinframe.appmire.be"))
        XCTAssertTrue(text.contains("Oma's frame"))
        XCTAssertTrue(text.hasPrefix("Janssens"))
    }

    func testBlockedHosts() {
        XCTAssertEqual(BlockedHost.normalize("Kinframe.Example.org"), "kinframe.example.org")
        XCTAssertEqual(BlockedHost.normalize("https://kinframe.example.org/fi/abc"), "kinframe.example.org")
        XCTAssertNil(BlockedHost.normalize(""))
        XCTAssertNil(BlockedHost.normalize("not a host"))
    }

    func testLiveEvent() {
        let json = #"{"type":"remote_subscription.updated","frame_id":"f1","remote_subscription":\#(sub("active"))}"#
        guard case .remoteSubscriptionUpdated(let frameId, let s)? = LiveEvent.parse(Data(json.utf8)) else {
            return XCTFail("not parsed")
        }
        XCTAssertEqual(frameId, "f1")
        XCTAssertEqual(s?.status, .active)
    }
}
