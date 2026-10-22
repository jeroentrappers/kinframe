@testable import KinframeKit
import XCTest

final class ComposeRulesTests: XCTestCase {
    let limits = Limits(maxItemsPerSend: 3, maxVideoSeconds: 60, maxUploadBytes: 1000)

    func testMediaKind() {
        XCTAssertEqual(ComposeRules.kind(mime: "image/heic", filename: "x"), .photo)
        XCTAssertEqual(ComposeRules.kind(mime: nil, filename: "IMG_0001.HEIC"), .photo)
        XCTAssertEqual(ComposeRules.kind(mime: "", filename: "clip.mov"), .video)
        XCTAssertEqual(ComposeRules.kind(mime: "video/mp4", filename: "x"), .video)
        XCTAssertNil(ComposeRules.kind(mime: "application/pdf", filename: "doc.pdf"))
        XCTAssertEqual(ComposeRules.mimeType(forExtension: "HEIC"), "image/heic")
        XCTAssertEqual(ComposeRules.mimeType(forExtension: "mov"), "video/quicktime")
    }

    func testIssues() {
        XCTAssertNil(ComposeRules.issue(kind: .photo, bytes: 1000, durationSeconds: nil, trim: nil, limits: limits))
        XCTAssertEqual(ComposeRules.issue(kind: .photo, bytes: 1001, durationSeconds: nil, trim: nil, limits: limits), .tooLarge)
        XCTAssertEqual(ComposeRules.issue(kind: .video, bytes: 10, durationSeconds: 90, trim: nil, limits: limits), .videoTooLong)
        // Trimming a long video under the limit fixes it.
        XCTAssertNil(ComposeRules.issue(kind: .video, bytes: 10, durationSeconds: 90, trim: Trim(startMs: 10_000, endMs: 70_000), limits: limits))
        XCTAssertNil(ComposeRules.issue(kind: .video, bytes: 10, durationSeconds: nil, trim: nil, limits: limits), "unknown length is the server's call")
    }

    func testBlockers() {
        XCTAssertEqual(ComposeRules.blocker(itemCount: 0, frameCount: 1, issues: 0, longestCaption: 0, limits: limits), .noItems)
        XCTAssertEqual(ComposeRules.blocker(itemCount: 4, frameCount: 1, issues: 0, longestCaption: 0, limits: limits), .overLimit(3))
        XCTAssertEqual(ComposeRules.blocker(itemCount: 2, frameCount: 1, issues: 1, longestCaption: 0, limits: limits), .issues(1))
        XCTAssertEqual(ComposeRules.blocker(itemCount: 2, frameCount: 1, issues: 0, longestCaption: 281, limits: limits), .captionTooLong)
        XCTAssertEqual(ComposeRules.blocker(itemCount: 2, frameCount: 0, issues: 0, longestCaption: 280, limits: limits), .noFrames)
        XCTAssertNil(ComposeRules.blocker(itemCount: 3, frameCount: 2, issues: 0, longestCaption: 10, limits: limits))
        XCTAssertEqual(ComposeRules.room(alreadyQueued: 2, limits: limits), 1)
        XCTAssertEqual(ComposeRules.room(alreadyQueued: 5, limits: limits), 0)
    }

    func testTrimClamping() {
        // At least 3 s.
        XCTAssertEqual(ComposeRules.clampTrim(start: 10, end: 11, duration: 120, maxSeconds: 60), Trim(startMs: 10_000, endMs: 13_000))
        // At most the server limit.
        XCTAssertEqual(ComposeRules.clampTrim(start: 0, end: 120, duration: 120, maxSeconds: 60), Trim(startMs: 0, endMs: 60_000))
        // Inside the video.
        XCTAssertEqual(ComposeRules.clampTrim(start: -5, end: 200, duration: 30, maxSeconds: 60), Trim(startMs: 0, endMs: 30_000))
        XCTAssertEqual(ComposeRules.clampTrim(start: 29, end: 30, duration: 30, maxSeconds: 60), Trim(startMs: 27_000, endMs: 30_000))
        // Videos shorter than 3 s keep their length.
        XCTAssertEqual(ComposeRules.clampTrim(start: 0, end: 2, duration: 2, maxSeconds: 60), Trim(startMs: 0, endMs: 2_000))
    }

    func testCoverPositionKeepsFocalInView() {
        // Square photo in a landscape box: only the vertical axis overflows.
        let p = Framing.coverPosition(focal: CGPoint(x: 0.5, y: 0), imageAspect: 1, boxAspect: 16.0 / 10)
        XCTAssertEqual(p.x, 0.5, accuracy: 0.001)
        XCTAssertEqual(p.y, 0, accuracy: 0.001)
        let centre = Framing.coverPosition(focal: CGPoint(x: 0.5, y: 0.5), imageAspect: 1, boxAspect: 16.0 / 10)
        XCTAssertEqual(centre.y, 0.5, accuracy: 0.001)
        let bottom = Framing.coverPosition(focal: CGPoint(x: 0.5, y: 0.95), imageAspect: 1, boxAspect: 16.0 / 10)
        XCTAssertEqual(bottom.y, 1, accuracy: 0.001)
        XCTAssertEqual(Framing.clampFocal(CGPoint(x: 1.2, y: 0.12345)), CGPoint(x: 1, y: 0.123))
    }

    func testBackoff() {
        XCTAssertEqual(backoffDelay(attempt: 0, random: 0.5), 1)
        XCTAssertEqual(backoffDelay(attempt: 3, random: 0.5), 8)
        XCTAssertEqual(backoffDelay(attempt: 10, random: 0.5), 60)
        XCTAssertEqual(backoffDelay(attempt: 10, random: 0), 48, accuracy: 0.001)
        XCTAssertEqual(backoffDelay(attempt: 10, random: 0.9999), 72, accuracy: 0.01)
    }
}

final class MergePatchTests: XCTestCase {
    func obj(_ json: String) -> [String: JSONValue] {
        try! JSONDecoder().decode(JSONValue.self, from: Data(json.utf8)).object!
    }

    func testDiffOnlyChangedLeaves() {
        let from = obj(#"{"version":3,"slideshow":{"interval_s":30,"order":"received"},"display":{"sleep_schedules":[{"id":"a"}]}}"#)
        let to = obj(#"{"version":3,"slideshow":{"interval_s":60,"order":"received"},"display":{"sleep_schedules":[]}}"#)
        XCTAssertEqual(MergePatch.diff(from: from, to: to), obj(#"{"slideshow":{"interval_s":60},"display":{"sleep_schedules":[]}}"#))
    }

    func testRemovedKeysBecomeNull() {
        XCTAssertEqual(MergePatch.diff(from: obj(#"{"location":{"lat":1,"lon":2}}"#), to: obj("{}")), obj(#"{"location":null}"#))
    }

    func testApply() {
        let merged = MergePatch.apply(obj(#"{"a":{"b":1},"c":null,"d":[1]}"#), to: obj(#"{"a":{"x":2},"c":5}"#))
        XCTAssertEqual(merged, obj(#"{"a":{"x":2,"b":1},"d":[1]}"#))
    }

    func testRebaseRetriesWhenFieldsDontOverlap() {
        let base = obj(#"{"version":1,"slideshow":{"interval_s":30},"display":{"brightness":204}}"#)
        let latest = obj(#"{"version":2,"slideshow":{"interval_s":30},"display":{"brightness":100}}"#)
        let ours = obj(#"{"slideshow":{"interval_s":60}}"#)
        XCTAssertEqual(MergePatch.rebase(base: base, latest: latest, ours: ours), .retry(patch: ours))
    }

    func testRebaseReviewsOverlappingEdits() {
        let base = obj(#"{"version":1,"slideshow":{"interval_s":30}}"#)
        let latest = obj(#"{"version":2,"slideshow":{"interval_s":10}}"#)
        let ours = obj(#"{"slideshow":{"interval_s":60}}"#)
        XCTAssertEqual(MergePatch.rebase(base: base, latest: latest, ours: ours), .review(merged: obj(#"{"version":2,"slideshow":{"interval_s":60}}"#)))
    }

    func testNumbersEncodeAsIntegersWhenWhole() throws {
        let data = try JSONEncoder().encode(JSONValue.object(["a": .number(30), "b": .number(0.5)]))
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(text.contains(#""a":30"#), text)
        XCTAssertTrue(text.contains(#""b":0.5"#), text)
    }
}

final class DeliveryTests: XCTestCase {
    func send(_ placements: [(String, PlacementStatus, Reaction?)], itemStatus: Item.StatusPayload = .ready) -> Send {
        Send(
            id: "s1",
            createdAt: Date(),
            items: [Item(id: "i1", kind: .photo, status: itemStatus, senderId: "u1", createdAt: Date())],
            frames: [
                SendFrame(frameId: "f1", frameName: "Oma", online: true, placements: placements.map {
                    SendPlacement(placementId: $0.0, itemId: "i1", status: $0.1, reaction: $0.2)
                })
            ]
        )
    }

    func testStages() {
        XCTAssertEqual(Stage(status: .pending), .processing)
        XCTAssertEqual(Stage(status: .ready), .onItsWay)
        XCTAssertEqual(Stage(status: .ready, itemStatus: .processing), .processing)
        XCTAssertEqual(Stage(status: .seen, itemStatus: .failed), .failed)
        XCTAssertEqual(Stage.overall([.seen, .delivered, .removed]), .delivered)
        XCTAssertEqual(Stage.overall([.seen, .failed]), .failed)
        XCTAssertEqual(Stage.overall([.removed]), .removed)
        XCTAssertEqual(Stage.progress([.seen, .onItsWay]), (1 + 1.0 / 3) / 2, accuracy: 0.0001)
    }

    func testFrameDeliveriesAndTally() {
        let s = send([("p1", .seen, .love), ("p2", .delivered, .love), ("p3", .ready, .wow)])
        let d = Delivery.frames(of: s)
        XCTAssertEqual(d.count, 1)
        XCTAssertEqual(d[0].stage, .onItsWay)
        XCTAssertEqual(d[0].counts.arrived, 2)
        XCTAssertEqual(Delivery.tally(d[0].reactions), [ReactionCount(reaction: .love, count: 2), ReactionCount(reaction: .wow, count: 1)])
    }

    func testLiveEventsUpdateSend() {
        var s = send([("p1", .seen, nil)])
        XCTAssertTrue(Delivery.apply(reaction: .laugh, placementId: "p1", to: &s))
        XCTAssertEqual(s.frames[0].placements[0].reaction, .laugh)
        XCTAssertTrue(Delivery.apply(reply: .callMe, placementId: "p1", to: &s))
        XCTAssertEqual(s.frames[0].placements[0].replies, [.callMe])
        XCTAssertFalse(Delivery.apply(reaction: .sad, placementId: "nope", to: &s))
    }
}
