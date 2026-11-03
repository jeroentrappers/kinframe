@testable import KinframeKit
import ImageIO
import UniformTypeIdentifiers
import XCTest

final class PrepPolicyTests: XCTestCase {
    func testPhotoTargetNeverUpscales() {
        XCTAssertEqual(PrepPolicy.photoTarget(CGSize(width: 8064, height: 6048)), CGSize(width: 2560, height: 1920))
        XCTAssertEqual(PrepPolicy.photoTarget(CGSize(width: 3024, height: 4032)), CGSize(width: 1920, height: 2560))
        XCTAssertEqual(PrepPolicy.photoTarget(CGSize(width: 1200, height: 800)), CGSize(width: 1200, height: 800))
    }

    func testVideoTargetFitsEitherOrientationWithEvenSizes() {
        XCTAssertEqual(PrepPolicy.videoTarget(CGSize(width: 3840, height: 2160)), CGSize(width: 1920, height: 1080))
        XCTAssertEqual(PrepPolicy.videoTarget(CGSize(width: 2160, height: 3840)), CGSize(width: 1080, height: 1920))
        XCTAssertEqual(PrepPolicy.videoTarget(CGSize(width: 1440, height: 1080)), CGSize(width: 1440, height: 1080))
        XCTAssertEqual(PrepPolicy.videoTarget(CGSize(width: 641, height: 359)), CGSize(width: 640, height: 358))
    }

    func testBitrateIsCapped() {
        XCTAssertEqual(PrepPolicy.videoBitrate(CGSize(width: 1280, height: 720)), 3_225_600)
        XCTAssertEqual(PrepPolicy.videoBitrate(CGSize(width: 1920, height: 1080)), 7_257_600)
        XCTAssertEqual(PrepPolicy.videoBitrate(CGSize(width: 4000, height: 3000)), 8_000_000)
    }

    func testClipAppliesTrimAndMotionLimit() {
        XCTAssertEqual(PrepPolicy.clip(duration: 30, trim: nil), 0...30)
        XCTAssertEqual(PrepPolicy.clip(duration: 30, trim: Trim(startMs: 2000, endMs: 12000)), 2...12)
        XCTAssertEqual(PrepPolicy.clip(duration: 3, trim: nil, maxSeconds: 10), 0...3)
        XCTAssertEqual(PrepPolicy.clip(duration: 14, trim: nil, maxSeconds: 10), 0...10)
    }

    func testExifCaptureTime() {
        let iso = ISO8601DateFormatter()
        XCTAssertEqual(PrepPolicy.exifCaptureTime("2023:05:01 12:00:00", offset: "+02:00").map(iso.string(from:)), "2023-05-01T10:00:00Z")
        XCTAssertEqual(PrepPolicy.exifCaptureTime("2023:05:01 12:00:00", offset: "-05:30").map(iso.string(from:)), "2023-05-01T17:30:00Z")
        // Without an offset the wall-clock time is taken as UTC, like the server.
        XCTAssertEqual(PrepPolicy.exifCaptureTime("2023:05:01 12:00:00", offset: nil).map(iso.string(from:)), "2023-05-01T12:00:00Z")
        XCTAssertNil(PrepPolicy.exifCaptureTime("0000:00:00 00:00:00", offset: nil))
        XCTAssertNil(PrepPolicy.exifCaptureTime(nil, offset: nil))
    }

    func testOnlyDecodablePhotosArePrepared() {
        XCTAssertTrue(PrepPolicy.canPreparePhoto(filename: "IMG_1.HEIC"))
        XCTAssertTrue(PrepPolicy.canPreparePhoto(filename: "a.jpeg"))
        XCTAssertFalse(PrepPolicy.canPreparePhoto(filename: "party.gif"))
    }
}

final class MediaPreparerTests: XCTestCase {
    var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appending(path: "prep-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    /// A 4000×3000 JPEG with EXIF (capture time, orientation 6) and GPS, like a phone photo.
    func makePhoto() throws -> URL {
        let space = CGColorSpace(name: CGColorSpace.displayP3)!
        let ctx = CGContext(data: nil, width: 4000, height: 3000, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        ctx.setFillColor(CGColor(red: 0.9, green: 0.4, blue: 0.1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: 4000, height: 3000))
        let url = dir.appending(path: "IMG_0001.jpg")
        let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil)!
        let props: [CFString: Any] = [
            kCGImagePropertyOrientation: 6,
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2024:07:14 18:30:00",
                                             kCGImagePropertyExifOffsetTimeOriginal: "+02:00"],
            kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 51.05, kCGImagePropertyGPSLatitudeRef: "N"],
        ]
        CGImageDestinationAddImage(dest, ctx.makeImage()!, props as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        return url
    }

    func testPhotoMasterIsDownscaledRotatedAndWithoutMetadata() async throws {
        let source = try makePhoto()
        let preparer = MediaPreparer()
        let captured = await preparer.captureDate(source, kind: .photo)
        XCTAssertEqual(captured.map { ISO8601DateFormatter().string(from: $0) }, "2024-07-14T16:30:00Z")

        guard case .prepared(let file, let mime, let media) = await preparer.preparePhoto(source, to: dir, name: "prep-0") else {
            return XCTFail("not prepared")
        }
        XCTAssertEqual(media.profile, PreparedProfile.photo)
        // Orientation 6 turns the landscape pixels into a portrait photo; the long edge is 2560.
        XCTAssertEqual(media.width, 1920)
        XCTAssertEqual(media.height, 2560)
        XCTAssertTrue(["image/jpeg", "image/webp"].contains(mime))

        let src = try XCTUnwrap(CGImageSourceCreateWithURL(file as CFURL, nil))
        let props = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any])
        XCTAssertEqual(props[kCGImagePropertyPixelWidth] as? Int, 1920)
        XCTAssertEqual(props[kCGImagePropertyPixelHeight] as? Int, 2560)
        XCTAssertNil(props[kCGImagePropertyGPSDictionary])
        XCTAssertNil((props[kCGImagePropertyExifDictionary] as? [CFString: Any])?[kCGImagePropertyExifDateTimeOriginal])
        XCTAssertEqual(props[kCGImagePropertyOrientation] as? Int ?? 1, 1)
        XCTAssertEqual(props[kCGImagePropertyProfileName] as? String, "sRGB IEC61966-2.1")
    }

    func testUndecodableFileFallsBackToOriginal() async throws {
        let url = dir.appending(path: "broken.jpg")
        try Data("not an image".utf8).write(to: url)
        guard case .original = await MediaPreparer().preparePhoto(url, to: dir, name: "prep-0") else { return XCTFail("prepared garbage") }
    }
}

/// Writes a small file in place of a real master.
struct FakePreparer: MediaPreparing {
    func preparePhoto(_ source: URL, to directory: URL, name: String) async -> PrepOutcome {
        let out = directory.appending(path: "\(name).jpg")
        try? Data(repeating: 7, count: 300).write(to: out)
        return .prepared(file: out, mime: "image/jpeg", media: PreparedMedia(profile: PreparedProfile.photo, width: 2560, height: 1920))
    }

    func prepareVideo(_ source: URL, to directory: URL, name: String, trim: Trim?, includeAudio: Bool, maxSeconds: Double?,
                      progress: @escaping @Sendable (Double) -> Void) async -> PrepOutcome {
        progress(0.5)
        let out = directory.appending(path: "\(name).mp4")
        try? Data(repeating: 9, count: 500).write(to: out)
        let ms = maxSeconds.map { Int($0 * 1000) } ?? (trim.map { $0.endMs - $0.startMs } ?? 4000)
        return .prepared(file: out, mime: "video/mp4", media: PreparedMedia(profile: PreparedProfile.video, width: 1080, height: 1920, durationMs: ms, trimmed: true))
    }

    func captureDate(_ source: URL, kind: MediaKind) async -> Date? {
        Date(timeIntervalSince1970: 1_700_000_000)
    }
}

final class PreparedUploadTests: XCTestCase {
    var root: URL!
    let server = URL(string: "https://k.example.org")!
    let base = OutboxRunnerTests()

    override func setUp() {
        root = FileManager.default.temporaryDirectory.appending(path: "outbox-\(UUID().uuidString)")
        base.root = root
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    func makeJob(store: OutboxStore, bytes: Int) throws -> (OutboxJob, [Data]) {
        try base.makeJob(store: store, bytes: bytes)
    }

    func runner(_ fake: FakeServer, store: OutboxStore, preparer: MediaPreparing? = nil) async -> OutboxRunner {
        await base.runner(fake, store: store, preparer: preparer)
    }

    func json(_ fake: FakeServer) throws -> [[String: Any]] {
        let body = try XCTUnwrap(fake.state.withLock { $0.sends.first })
        return try XCTUnwrap((JSONSerialization.jsonObject(with: body) as? [String: Any])?["items"] as? [[String: Any]])
    }

    func testLivePhotoUploadsPreparedStillAndMutedClip() async throws {
        let fake = FakeServer()
        let store = OutboxStore(root: root)
        var (job, _) = try makeJob(store: store, bytes: 2000)
        try Data(repeating: 1, count: 1500).write(to: store.directory(job.id).appending(path: "motion-0.mov"))
        job.items[0].motion = TusUpload(file: "motion-0.mov", size: 1500, filename: "f0.mov", mime: "video/quicktime")
        try store.save(job)

        let runner = await runner(fake, store: store, preparer: FakePreparer())
        await runner.kick()
        await runner.wait(job.id)

        XCTAssertEqual(store.load(job.id)?.state, .sent(sendId: "send-1"))
        let meta = fake.state.withLock { $0.metadata }
        XCTAssertEqual(meta.map { $0["prepared"] }, [PreparedProfile.photo, PreparedProfile.video])
        XCTAssertEqual(meta.map { $0["filename"] }, ["f0.jpg", "f0.mp4"])
        // Prepared bytes went up, not the originals.
        XCTAssertEqual(fake.state.withLock { $0.uploads["u1"]?.data }, Data(repeating: 7, count: 300))
        XCTAssertEqual(fake.state.withLock { $0.uploads["u2"]?.data }, Data(repeating: 9, count: 500))

        let item = try XCTUnwrap(json(fake).first)
        XCTAssertEqual(item["upload_id"] as? String, "u1")
        XCTAssertEqual(item["live_video_upload_id"] as? String, "u2")
        XCTAssertEqual(item["width"] as? Int, 2560)
        XCTAssertEqual(item["height"] as? Int, 1920)
        XCTAssertEqual(item["captured_at"] as? String, "2023-11-14T22:13:20Z")
        XCTAssertNil(item["include_audio"], "Live clips are muted unless asked")
        XCTAssertNil(item["duration_ms"])
    }

    func testPreparedVideoBakesTheTrim() async throws {
        let fake = FakeServer()
        let store = OutboxStore(root: root)
        var (job, _) = try makeJob(store: store, bytes: 900)
        job.items[0].kind = .video
        job.items[0].trim = Trim(startMs: 1000, endMs: 6000)
        job.items[0].includeAudio = false
        try store.save(job)

        let runner = await runner(fake, store: store, preparer: FakePreparer())
        await runner.kick()
        await runner.wait(job.id)

        let item = try XCTUnwrap(json(fake).first)
        XCTAssertNil(item["trim"], "a prepared video already has the trim applied")
        XCTAssertEqual(item["duration_ms"] as? Int, 5000)
        XCTAssertEqual(item["include_audio"] as? Bool, false)
        XCTAssertEqual(fake.state.withLock { $0.metadata.first?["prepared"] }, PreparedProfile.video)
    }

    func testWithoutPreparerOriginalsGoAsTheyAre() async throws {
        let fake = FakeServer()
        let store = OutboxStore(root: root)
        let (job, datas) = try makeJob(store: store, bytes: 800)
        let runner = await runner(fake, store: store)
        await runner.kick()
        await runner.wait(job.id)
        XCTAssertNil(fake.state.withLock { $0.metadata.first?["prepared"] })
        XCTAssertEqual(fake.state.withLock { $0.uploads["u1"]?.data }, datas[0])
        XCTAssertEqual(store.load(job.id)?.items[0].prep, .original)
    }

    func testStartedUploadIsNotPreparedAgain() async throws {
        let fake = FakeServer()
        let store = OutboxStore(root: root)
        let (made, datas) = try makeJob(store: store, bytes: 2048)
        var job = made
        // The share extension started the original before the app took over.
        let firstKiB = datas[0].prefix(1024)
        fake.state.withLock { $0.uploads["u1"] = (2048, firstKiB) }
        job.items[0].upload.location = server.appending(path: "v1/uploads/u1")
        job.items[0].upload.offset = 1024
        try store.save(job)
        let runner = await runner(fake, store: store, preparer: FakePreparer())
        await runner.kick()
        await runner.wait(job.id)
        XCTAssertEqual(fake.state.withLock { $0.uploads["u1"]?.data }, datas[0])
        XCTAssertEqual(store.load(job.id)?.items[0].prep, .original)
    }

    func testStillOnlySendsNoClip() throws {
        var item = OutboxItem(upload: TusUpload(file: "a.jpg", size: 1, filename: "a.jpg", mime: "image/jpeg"), kind: .photo)
        item.upload.existingItemId = "i1"
        let job = OutboxJob(server: server, frameIds: ["f"], frameNames: ["F"], items: [item])
        let text = String(decoding: try job.sendBody(), as: UTF8.self)
        XCTAssertFalse(text.contains("live_video_upload_id"), text)
    }
}
