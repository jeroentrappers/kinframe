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

/// docs/spec/framing.md §3 (the crop), §3.1 (which view) and §4 (editor rules).
final class FramingGeometryTests: XCTestCase {
    func v(_ x: Double, _ y: Double, _ zoom: Double) -> FramingView { FramingView(x: x, y: y, zoom: zoom) }

    func assertRect(_ r: CGRect, _ x: Double, _ y: Double, _ w: Double, _ h: Double, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(r.minX, x, accuracy: 0.001, file: file, line: line)
        XCTAssertEqual(r.minY, y, accuracy: 0.001, file: file, line: line)
        XCTAssertEqual(r.width, w, accuracy: 0.001, file: file, line: line)
        XCTAssertEqual(r.height, h, accuracy: 0.001, file: file, line: line)
    }

    func testZoomOneIsTheCoverCrop() {
        // A 4:3 photo on a 16:9 screen loses top and bottom; centred on the focal point, clamped to the photo.
        let photo = CGSize(width: 4000, height: 3000), screen = CGSize(width: 1920, height: 1080)
        assertRect(Framing.cropRect(screen: screen, photo: photo, view: v(0.5, 0.5, 1)), 0, 375, 4000, 2250)
        assertRect(Framing.cropRect(screen: screen, photo: photo, view: v(0.5, 0, 1)), 0, 0, 4000, 2250)
        assertRect(Framing.cropRect(screen: screen, photo: photo, view: v(0.5, 1, 1)), 0, 750, 4000, 2250)
        // The same as the focal point maths the previews used before framing.
        let pos = Framing.coverPosition(focal: CGPoint(x: 0.5, y: 0.6), imageAspect: 4.0 / 3, boxAspect: 16.0 / 9)
        let r = Framing.cropRect(screen: screen, photo: photo, view: v(0.5, 0.6, 1))
        XCTAssertEqual(r.minY / (3000 - 2250), pos.y, accuracy: 0.001)
    }

    func testZoomShowsLessAndStaysOnThePhoto() {
        let photo = CGSize(width: 4000, height: 3000), screen = CGSize(width: 1600, height: 1000)
        // Zoom 2 shows half the width and height of the cover crop.
        assertRect(Framing.cropRect(screen: screen, photo: photo, view: v(0.5, 0.5, 2)), 1000, 875, 2000, 1250)
        // A centre near the corner is clamped so the window can't leave the photo.
        assertRect(Framing.cropRect(screen: screen, photo: photo, view: v(0.05, 0.98, 2)), 0, 1750, 2000, 1250)
        // Zoom is clamped to 1…4.
        XCTAssertEqual(Framing.cropRect(screen: screen, photo: photo, view: v(0.5, 0.5, 9)).width, 1000, accuracy: 0.001)
        XCTAssertEqual(Framing.cropRect(screen: screen, photo: photo, view: v(0.5, 0.5, 0.2)).width, 4000, accuracy: 0.001)
        // A portrait screen crops the sides of a landscape photo.
        assertRect(Framing.cropRect(screen: CGSize(width: 1000, height: 1600), photo: photo, view: v(0.5, 0.5, 1)), 1062.5, 0, 1875, 3000)
    }

    func testUnitCropMatchesThePixelCrop() {
        let r = Framing.unitCrop(screenAspect: 16.0 / 10, photoAspect: 4.0 / 3, view: v(0.05, 0.98, 2))
        assertRect(r, 0, 1750.0 / 3000, 0.5, 1250.0 / 3000)
    }

    func testOrientation() {
        XCTAssertEqual(ScreenOrientation(width: 1920, height: 1080), .landscape)
        XCTAssertEqual(ScreenOrientation(width: 800, height: 800), .landscape, "a square screen is landscape")
        XCTAssertEqual(ScreenOrientation(width: 600, height: 1024), .portrait)
    }

    func testViewPrecedence() {
        let item = ItemFraming(landscape: v(0.3, 0.4, 2))
        let override = ItemFraming(portrait: v(0.7, 0.7, 3))
        let focal = FocalPoint(x: 0.2, y: 0.8)
        let focalOverride = FocalPoint(x: 0.9, y: 0.1)
        // 1. The frame owner's framing for this orientation.
        XCTAssertEqual(Framing.view(for: .portrait, framingOverride: override, focalOverride: focalOverride, framing: item, focal: focal),
                       .init(view: v(0.7, 0.7, 3), chosen: true))
        // 2. The frame's focal override at 1× beats the item's framing.
        XCTAssertEqual(Framing.view(for: .landscape, framingOverride: override, focalOverride: focalOverride, framing: item, focal: focal),
                       .init(view: v(0.9, 0.1, 1), chosen: false))
        // 3. The item's framing.
        XCTAssertEqual(Framing.view(for: .landscape, framing: item, focal: focal), .init(view: v(0.3, 0.4, 2), chosen: true))
        // 4. The focal point, else the centre.
        XCTAssertEqual(Framing.view(for: .portrait, framing: item, focal: focal), .init(view: v(0.2, 0.8, 1), chosen: false))
        XCTAssertEqual(Framing.view(for: .portrait, framing: nil, focal: nil), .init(view: v(0.5, 0.5, 1), chosen: false))
    }

    func testEditorClampAndMove() {
        // The centre moves so the window stays on the photo; values are rounded like the server does.
        XCTAssertEqual(Framing.clamped(v(0.01, 0.5, 2), screenAspect: 16.0 / 10, photoAspect: 4.0 / 3), v(0.25, 0.5, 2))
        XCTAssertEqual(Framing.clamped(v(0.5, 0.5, 7), screenAspect: 16.0 / 10, photoAspect: 4.0 / 3).zoom, 4)
        XCTAssertEqual(Framing.clamped(v(0.123456, 0.5, 4), screenAspect: 16.0 / 10, photoAspect: 4.0 / 3).x, 0.125)
        // Moving right by 10 % of the visible part (half the photo wide at 2×).
        XCTAssertEqual(Framing.moved(v(0.5, 0.5, 2), dx: 0.1, dy: 0, screenAspect: 16.0 / 10, photoAspect: 4.0 / 3), v(0.55, 0.5, 2))
        XCTAssertEqual(Framing.moved(v(0.74, 0.5, 2), dx: 0.1, dy: 0, screenAspect: 16.0 / 10, photoAspect: 4.0 / 3), v(0.75, 0.5, 2))
    }

    func testFocalTapAndSending() {
        let f = ItemFraming(landscape: v(0.3, 0.4, 2), portrait: v(0.6, 0.6, 1.5))
        let moved = Framing.recentred(f, at: CGPoint(x: 0.8, y: 0.1))
        XCTAssertEqual(moved, ItemFraming(landscape: v(0.8, 0.1, 2), portrait: v(0.8, 0.1, 1.5)))
        XCTAssertNil(Framing.recentred(nil, at: CGPoint(x: 0.8, y: 0.1)), "without views it is just the focal point")
        XCTAssertNil(Framing.sendable(ItemFraming()))
        // The focal point sent: landscape centre, else portrait, else the tapped point.
        XCTAssertEqual(Framing.focal(for: f, tapped: CGPoint(x: 0.1, y: 0.1)), CGPoint(x: 0.3, y: 0.4))
        XCTAssertEqual(Framing.focal(for: ItemFraming(portrait: v(0.6, 0.7, 2)), tapped: CGPoint(x: 0.1, y: 0.1)), CGPoint(x: 0.6, y: 0.7))
        XCTAssertEqual(Framing.focal(for: nil, tapped: CGPoint(x: 0.1, y: 0.1)), CGPoint(x: 0.1, y: 0.1))
    }

    func testSharpnessWarning() {
        // A 4:3 photo (master 2560×1920) on a 16:10 frame: 2560 px across at 1×, 1280 at 2×, 853 at 3×.
        XCTAssertFalse(Framing.mayLookSoft(photoWidth: 4032, photoHeight: 3024, screenAspect: 1.6, zoom: 2))
        XCTAssertTrue(Framing.mayLookSoft(photoWidth: 4032, photoHeight: 3024, screenAspect: 1.6, zoom: 3))
        // A small photo isn't scaled up to 2560.
        XCTAssertTrue(Framing.mayLookSoft(photoWidth: 1600, photoHeight: 1200, screenAspect: 1.6, zoom: 2))
        // Portrait screen: the long side is the height (1920 master px at 1×, about 1010 at 1.9×, 914 at 2.1×).
        XCTAssertFalse(Framing.mayLookSoft(photoWidth: 4032, photoHeight: 3024, screenAspect: 0.625, zoom: 1.9))
        XCTAssertTrue(Framing.mayLookSoft(photoWidth: 4032, photoHeight: 3024, screenAspect: 0.625, zoom: 2.1))
        XCTAssertFalse(Framing.mayLookSoft(photoWidth: 0, photoHeight: 0, screenAspect: 1.6, zoom: 4))
    }

    func testZoomText() {
        XCTAssertEqual(Framing.zoomText(2), "2×")
        XCTAssertEqual(Framing.zoomText(1.54), "1.5×")
    }

    func testSendBodyCarriesFraming() throws {
        var photo = OutboxItem(upload: TusUpload(file: "a.jpg", size: 10, filename: "a.jpg", mime: "image/jpeg"), kind: .photo,
                               fit: .fill, focal: .init(x: 0.3, y: 0.4), framing: ItemFraming(landscape: v(0.3, 0.4, 2)))
        photo.upload.existingItemId = "up1"
        var video = OutboxItem(upload: TusUpload(file: "b.mp4", size: 10, filename: "b.mp4", mime: "video/mp4"), kind: .video,
                               framing: ItemFraming(landscape: v(0.3, 0.4, 2)))
        video.upload.existingItemId = "up2"
        let job = OutboxJob(server: URL(string: "https://k.example")!, frameIds: ["f1"], frameNames: ["Oma"], items: [photo, video])
        let body = try JSONDecoder().decode(JSONValue.self, from: job.sendBody())
        guard case .array(let items)? = body["items"] else { return XCTFail("no items") }
        XCTAssertEqual(items[0]["framing"]?["landscape"]?["zoom"], .number(2))
        XCTAssertNil(items[0]["framing"]?["portrait"], "only the orientations the person changed")
        XCTAssertNil(items[1]["framing"], "videos ignore framing")
        // The job file keeps it.
        let again = try JSONDecoder().decode(OutboxJob.self, from: JSONEncoder().encode(job))
        XCTAssertEqual(again.items[0].framing, photo.framing)
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
