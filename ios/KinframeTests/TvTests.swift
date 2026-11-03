import XCTest
@testable import KinframeKit

final class TvLogicTests: XCTestCase {
    private func slide(_ id: String, kind: Components.Schemas.MediaKind = .photo, durationMs: Int? = nil,
                       captured: Date? = nil, sender: String = "Oma") -> TvSlide {
        TvSlide(placementId: id, kind: kind, url: "https://m.example.org/\(id)", mime: kind == .video ? "video/mp4" : "image/jpeg",
                senderName: sender, capturedAt: captured, receivedAt: Date(timeIntervalSince1970: 1_715_515_200),
                width: 1920, height: 1080, durationMs: durationMs)
    }

    private func link(_ id: String, purpose: TvLink.PurposePayload, expires: Date = Date().addingTimeInterval(3600),
                      url: String = "https://k.example.org/tv/f1?t=abc") -> TvLink {
        TvLink(id: id, frameId: "f1", token: "abc", url: url, purpose: purpose, intervalSeconds: 10,
               expiresAt: expires, createdAt: Date())
    }

    func testLinkBodyFollowsScopeAndPurpose() {
        let all = TvLogic.linkBody(scope: .all, interval: 15, purpose: .tv)
        XCTAssertEqual(all.intervalSeconds, 15)
        XCTAssertEqual(all.purpose, .tv)
        XCTAssertEqual(all.expiresInHours, 12)
        XCTAssertNil(all.albumId)
        XCTAssertNil(all.placementIds)

        let album = TvLogic.linkBody(scope: .album(id: "a1", name: "Zomer"), interval: 10, purpose: .cast)
        XCTAssertEqual(album.albumId, "a1")
        XCTAssertEqual(album.expiresInHours, 6, "a cast link only lives as long as the session")

        let many = (0..<620).map { "p\($0)" }
        let selection = TvLogic.linkBody(scope: .selection(many), interval: 10, purpose: .tv)
        XCTAssertEqual(selection.placementIds?.count, 500)
        XCTAssertEqual(selection.placementIds?.first, "p0")

        let mirror = TvLogic.linkBody(scope: .selection(["p1"]), interval: 10, purpose: .mirror)
        XCTAssertNil(mirror.placementIds, "a mirror has no scope of its own")
        XCTAssertEqual(mirror.purpose, .mirror)
    }

    func testStepWrapsAround() {
        XCTAssertEqual(TvLogic.step(0, count: 3, by: 1), 1)
        XCTAssertEqual(TvLogic.step(2, count: 3, by: 1), 0)
        XCTAssertEqual(TvLogic.step(0, count: 3, by: -1), 2)
        XCTAssertEqual(TvLogic.step(5, count: 0, by: 1), 0)
    }

    func testDwellPlaysVideosToTheEnd() {
        XCTAssertEqual(TvLogic.dwell(slide("a"), interval: 10), 10)
        XCTAssertEqual(TvLogic.dwell(slide("a"), interval: 1), 3, "at least 3 s")
        XCTAssertEqual(TvLogic.dwell(slide("v", kind: .video, durationMs: 12_000), interval: 10), 12.5)
        XCTAssertEqual(TvLogic.dwell(slide("v", kind: .video, durationMs: 3_600_000), interval: 10), 300, "capped at 5 min")
        XCTAssertEqual(TvLogic.dwell(nil, interval: 30), 30)
    }

    func testRefreshKeepsThePlace() {
        let now = Date()
        let show = TvSlideshow(frameId: "f1", frameName: "Oma", intervalSeconds: 10, expiresAt: now.addingTimeInterval(3600),
                               refreshAfter: now.addingTimeInterval(-1), slides: [])
        XCTAssertTrue(TvLogic.needsRefresh(show, now: now))
        XCTAssertFalse(TvLogic.needsRefresh(show, now: now.addingTimeInterval(-10)))

        let next = [slide("a"), slide("b"), slide("c")]
        XCTAssertEqual(TvLogic.keepPlace(previous: "c", in: next, fallback: 0), 2)
        XCTAssertEqual(TvLogic.keepPlace(previous: "gone", in: next, fallback: 7), 2)
        XCTAssertEqual(TvLogic.keepPlace(previous: nil, in: [], fallback: 3), 0)
    }

    func testPageURL() {
        XCTAssertEqual(TvLogic.pageURL(link("l1", purpose: .tv), web: nil)?.absoluteString, "https://k.example.org/tv/f1?t=abc")
        let bare = link("l1", purpose: .tv, url: "")
        XCTAssertEqual(TvLogic.pageURL(bare, web: "https://kinframe.example.org/")?.absoluteString, "https://kinframe.example.org/tv/f1?t=abc")
        XCTAssertNil(TvLogic.pageURL(bare, web: nil))
    }

    func testBylineUsesTheTakenDate() {
        let taken = ISO8601DateFormatter().date(from: "2024-05-12T10:00:00Z")!
        let s = slide("a", captured: taken)
        let text = TvLogic.byline(s, locale: Locale(identifier: "en_GB"))
        XCTAssertTrue(text.hasPrefix("Oma · "), text)
        XCTAssertTrue(text.contains("2024"), text)
        XCTAssertTrue(text.contains("May"), text)
    }

    func testActiveLinksLeaveOutCastAndExpired() {
        let links = [
            link("tv", purpose: .tv),
            link("mirror", purpose: .mirror),
            link("cast", purpose: .cast),
            link("old", purpose: .tv, expires: Date().addingTimeInterval(-60)),
        ]
        XCTAssertEqual(TvLogic.activeLinks(links).map(\.id), ["tv", "mirror"])
        XCTAssertEqual(TvLogic.activeLinks(links, excluding: "tv").map(\.id), ["mirror"])
    }

    func testSendOnTvUsesTheFirstFrameWithLivePlacements() {
        let send = Send(
            id: "s1", createdAt: Date(),
            items: [Item(id: "i1", kind: .photo, status: .ready, senderId: "u1", createdAt: Date()),
                    Item(id: "i2", kind: .video, status: .processing, senderId: "u1", createdAt: Date())],
            frames: [
                SendFrame(frameId: "f1", frameName: "Oma", placements: [
                    SendPlacement(placementId: "a1", itemId: "i1", status: .removed),
                    SendPlacement(placementId: "a2", itemId: "i2", status: .delivered),
                ]),
                SendFrame(frameId: "f2", frameName: "Opa", placements: [
                    SendPlacement(placementId: "b1", itemId: "i1", status: .seen),
                    SendPlacement(placementId: "b2", itemId: "i2", status: .seen),
                ]),
            ])
        let onTv = TvLogic.sendOnTv(send)
        XCTAssertEqual(onTv?.frameId, "f2")
        XCTAssertEqual(onTv?.frameName, "Opa")
        XCTAssertEqual(onTv?.placementIds, ["b1"], "only ready items")
    }
}
