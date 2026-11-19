@testable import KinframeKit
import XCTest

// Several accounts in one app (docs/spec/multi-server.md §2.2): tabs follow every account, merged lists page strictly
// by time, a send is checked against the limits of the servers it goes to, frame requests reach family members who
// aren't on the frame, and links of any kinframe server are found in pasted and shared text.
final class MultiAccountTests: XCTestCase {
    private let d = { (s: Double) in Date(timeIntervalSince1970: s) }

    func testAnyAccountsFeaturesDecideTheTabs() {
        let none = SocialFeatures()
        let chatsOnly = SocialFeatures(chats: true)
        let homeOnly = SocialFeatures(social: true, engagement: true)
        let any = SocialFeatures.any([none, chatsOnly, homeOnly])
        XCTAssertTrue(any.home)
        XCTAssertTrue(any.chats)
        XCTAssertTrue(any.social)
        XCTAssertFalse(any.voiceMessages)
        XCTAssertFalse(SocialFeatures.any([none]).home)
        XCTAssertFalse(SocialFeatures.any([]).chats)
    }

    func testStrictestLimits() {
        var a = Limits(maxItemsPerSend: 200, maxVideoSeconds: 300, maxUploadBytes: 2 << 30, trashRetentionDays: 30)
        var b = Limits(maxItemsPerSend: 50, maxVideoSeconds: 600, maxUploadBytes: 1 << 30, trashRetentionDays: nil)
        a.maxCommentChars = 1000
        b.maxCommentChars = 2000
        let s = Limits.strictest([a, b])
        XCTAssertEqual(s.maxItemsPerSend, 50)
        XCTAssertEqual(s.maxVideoSeconds, 300)
        XCTAssertEqual(s.maxUploadBytes, 1 << 30)
        XCTAssertEqual(s.trashRetentionDays, 30)
        XCTAssertEqual(s.maxCommentChars, 1000)
        XCTAssertEqual(Limits.strictest([]), .defaults)
        XCTAssertEqual(Limits.strictest([b]), b)
    }

    func testComposerUsesTheLimitsOfTheChosenServers() {
        let big = Limits(maxItemsPerSend: 200, maxVideoSeconds: 300)
        let small = Limits(maxItemsPerSend: 20, maxVideoSeconds: 60)
        // Only the big server's frames are chosen: its limits.
        XCTAssertEqual(ComposeLimits.effective([(big, true), (small, false)]).maxItemsPerSend, 200)
        // Both: the strictest.
        XCTAssertEqual(ComposeLimits.effective([(big, true), (small, true)]).maxVideoSeconds, 60)
        // Nothing chosen yet: every server it could go to.
        XCTAssertEqual(ComposeLimits.effective([(big, false), (small, false)]).maxItemsPerSend, 20)
    }

    func testMergedPagesStayInTimeOrder() {
        // Server A loaded down to t=50 and has more; server B is done down to t=10.
        let a = [("a1", d(100)), ("a2", d(50))]
        let b = [("b1", d(90)), ("b2", d(40)), ("b3", d(10))]
        let shown = AccountMerge.merged([a, b], hasMore: [true, false]) { $0.1 }.map(\.0)
        XCTAssertEqual(shown, ["a1", "b1", "a2"], "B's older sends wait until A's next page is loaded")
        XCTAssertEqual(AccountMerge.toLoad([a, b], hasMore: [true, false]) { $0.1 }, [0])
        // A's next page: everything older than t=50 there is now known.
        let a2 = a + [("a3", d(45)), ("a4", d(5))]
        XCTAssertEqual(AccountMerge.merged([a2, b], hasMore: [false, false]) { $0.1 }.map(\.0), ["a1", "b1", "a2", "a3", "b2", "b3", "a4"])
        XCTAssertEqual(AccountMerge.toLoad([a2, b], hasMore: [false, false]) { $0.1 }, [])
    }

    func testToLoadPicksTheSourceHoldingTheListBack() {
        // Both have more: the one whose oldest loaded item is newest limits the list.
        XCTAssertEqual(AccountMerge.toLoad(oldest: [d(50), d(80)], hasMore: [true, true]), [1])
        XCTAssertEqual(AccountMerge.toLoad(oldest: [d(80), d(80)], hasMore: [true, true]), [0, 1])
        XCTAssertEqual(AccountMerge.toLoad(oldest: [d(50), nil], hasMore: [true, false]), [0])
        let shown = AccountMerge.merged([[("a", d(50))], [("b", d(80)), ("c", d(70))]], hasMore: [true, true]) { $0.1 }.map(\.0)
        XCTAssertEqual(shown, ["b", "c"], "nothing older than the newest of the oldest loaded items (c, 70)")
    }

    func testFrameRequestTargets() {
        XCTAssertEqual(FrameRequestTargets.target(frameId: "f1", sendableFrames: ["f1"], reaching: ["x"], postable: ["x"]), .frame("f1"))
        XCTAssertEqual(FrameRequestTargets.target(frameId: "f1", sendableFrames: [], reaching: ["peeters", "janssens"], postable: ["janssens", "other", "peeters"]),
                       .families(["janssens", "peeters"]), "in your order, only families you post to")
        XCTAssertEqual(FrameRequestTargets.target(frameId: "f1", sendableFrames: [], reaching: ["peeters"], postable: ["other"]), FrameRequestTargets.Target.none)
        XCTAssertEqual(FrameRequestTargets.familiesToPreselect(reaching: [], postable: ["a"]), [])
    }

    func testFeedIdsOfAPhotoRequestDecode() throws {
        let json = #"{"id":"r1","frame_id":"f1","frame_name":"Oma","state":"open","created_at":"2026-11-19T10:00:00Z","expires_at":"2026-11-20T10:00:00Z","feed_ids":["fd1","fd2"]}"#
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let r = try decoder.decode(PhotoRequest.self, from: Data(json.utf8))
        XCTAssertEqual(r.feedIds, ["fd1", "fd2"])
    }

    func testFindsKinframeLinksInText() {
        XCTAssertEqual(DeepLink.find(in: "Join us: https://kinframe.janssens.be/fi/abcdefghijklmnopqrstuv!")?.absoluteString,
                       "https://kinframe.janssens.be/fi/abcdefghijklmnopqrstuv")
        XCTAssertEqual(DeepLink.find(in: "kinframe.janssens.be/f/12345678")?.absoluteString, "https://kinframe.janssens.be/f/12345678")
        XCTAssertEqual(DeepLink.find(in: "(app.kinframe://i/abcdefghijklmnopqrstuv?server=https://kinframe.janssens.be)")?.scheme, "app.kinframe")
        XCTAssertNil(DeepLink.find(in: "Code: 4821 3907"))
        XCTAssertNil(DeepLink.find(in: "https://example.org/about"))
        if case .frameCode(let server, let code)? = DeepLink.find(in: "see https://kinframe.janssens.be/f/12345678.").flatMap(DeepLink.parse) {
            XCTAssertEqual(server?.host(), "kinframe.janssens.be")
            XCTAssertEqual(code, "12345678")
        } else {
            XCTFail("expected a frame code link")
        }
    }

    func testPendingLinkIsTakenOnceAndExpires() {
        let suite = "kinframe.tests.pendinglink"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        let url = URL(string: "https://kinframe.janssens.be/fi/abcdefghijklmnopqrstuv")!
        let now = Date()
        PendingLink.store(url, now: now, defaults)
        XCTAssertEqual(PendingLink.take(now: now.addingTimeInterval(60), defaults), url)
        XCTAssertNil(PendingLink.take(now: now.addingTimeInterval(61), defaults), "once")
        PendingLink.store(url, now: now, defaults)
        XCTAssertNil(PendingLink.take(now: now.addingTimeInterval(3600), defaults), "too old")
        defaults.set("https://example.org/about", forKey: "share.pendingLink")
        defaults.set(now.timeIntervalSince1970, forKey: "share.pendingLinkAt")
        XCTAssertNil(PendingLink.take(now: now, defaults), "not a kinframe link")
    }
}
