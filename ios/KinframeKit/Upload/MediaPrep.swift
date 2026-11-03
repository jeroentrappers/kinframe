import AVFoundation
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// tus `Upload-Metadata` `prepared` values (docs/api/README.md §5.3 "Prepared uploads").
public enum PreparedProfile {
    public static let key = "prepared"
    public static let photo = "photo-master-v1"
    public static let video = "video-master-v1"
}

/// What a prepared master is, as the send item describes it.
public struct PreparedMedia: Codable, Equatable, Sendable {
    public var profile: String
    public var width: Int
    public var height: Int
    public var durationMs: Int?
    /// Trim and mute are baked in: the send must not repeat the trim.
    public var trimmed: Bool

    public init(profile: String, width: Int, height: Int, durationMs: Int? = nil, trimmed: Bool = false) {
        self.profile = profile
        self.width = width
        self.height = height
        self.durationMs = durationMs
        self.trimmed = trimmed
    }
}

/// The decisions behind client-side preparation, free of I/O so they can be unit tested. The server verifies what
/// we produce and falls back to its own pipeline when something doesn't conform, so every rule errs on the side of
/// "upload the original".
public enum PrepPolicy {
    /// photo-master-v1: long edge at most this many pixels.
    public static let photoMaxEdge = 2560
    public static let photoQuality = 0.9
    /// video-master-v1: within 1920×1080 in either orientation, ≤ 30 fps, ≤ 8 Mbit/s.
    public static let videoMaxLong = 1920
    public static let videoMaxShort = 1080
    public static let videoMaxFPS: Int32 = 30
    public static let videoMaxBitrate = 8_000_000
    /// Live Photo clips longer than this are cut by the server; we cut them first.
    public static let motionMaxSeconds = 10.0

    /// Photo types we decode and re-encode. GIF stays as it is (the server keeps its first frame).
    public static func canPreparePhoto(filename: String) -> Bool {
        let ext = (filename as NSString).pathExtension.lowercased()
        return ["jpg", "jpeg", "heic", "heif", "png", "webp", "avif", "tif", "tiff", "dng"].contains(ext)
    }

    /// The prepared photo size: long edge ≤ 2560, never upscaled.
    public static func photoTarget(_ size: CGSize) -> CGSize {
        let long = max(size.width, size.height)
        guard long > 0 else { return size }
        let scale = min(1, Double(photoMaxEdge) / long)
        return CGSize(width: max(1, (size.width * scale).rounded()), height: max(1, (size.height * scale).rounded()))
    }

    /// The prepared video size: within 1920×1080 (either orientation), never upscaled, even numbers.
    public static func videoTarget(_ size: CGSize) -> CGSize {
        let long = max(size.width, size.height), short = min(size.width, size.height)
        guard long > 0, short > 0 else { return size }
        let scale = min(1, min(Double(videoMaxLong) / long, Double(videoMaxShort) / short))
        func even(_ v: Double) -> Double { max(2, (Double(Int(v.rounded()) / 2) * 2)) }
        return CGSize(width: even(size.width * scale), height: even(size.height * scale))
    }

    /// ≈ w·h·3.5 bit/s, capped at 8 Mbit/s: under the frames' rendition budget, so frames get the master as is.
    public static func videoBitrate(_ size: CGSize) -> Int {
        min(Int(size.width * size.height * 3.5), videoMaxBitrate)
    }

    /// The part of a video to keep, in seconds: the trim, cut to `maxSeconds` for Live Photo clips.
    public static func clip(duration: Double, trim: Trim?, maxSeconds: Double? = nil) -> ClosedRange<Double> {
        var start = trim.map { Double($0.startMs) / 1000 } ?? 0
        var end = trim.map { Double($0.endMs) / 1000 } ?? duration
        start = max(0, min(start, duration))
        end = max(start, min(end, duration))
        if let maxSeconds, end - start > maxSeconds { end = start + maxSeconds }
        return start...end
    }

    /// EXIF `DateTimeOriginal` ("2023:05:01 12:00:00") with `OffsetTimeOriginal` ("+02:00") when present. Without an
    /// offset the wall-clock time is taken as UTC, like the server does with EXIF times.
    public static func exifCaptureTime(_ dateTime: String?, offset: String?) -> Date? {
        guard let dateTime, dateTime.count >= 19 else { return nil }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy:MM:dd HH:mm:ss"
        f.timeZone = TimeZone(secondsFromGMT: offsetSeconds(offset) ?? 0)
        guard let date = f.date(from: String(dateTime.prefix(19))) else { return nil }
        guard Calendar(identifier: .gregorian).component(.year, from: date) >= 1900 else { return nil }
        return date
    }

    /// "+02:00" → 7200, "-05:30" → -19800.
    static func offsetSeconds(_ s: String?) -> Int? {
        guard let s = s?.trimmingCharacters(in: .whitespaces), s.count >= 3 else { return nil }
        let sign: Int
        switch s.first {
        case "+": sign = 1
        case "-": sign = -1
        default: return nil
        }
        let parts = s.dropFirst().split(separator: ":")
        guard let h = parts.first.flatMap({ Int($0) }) else { return nil }
        let m = parts.count > 1 ? Int(parts[1]) ?? 0 : 0
        return sign * (h * 3600 + m * 60)
    }
}

/// What preparing one file produced.
public enum PrepOutcome: Sendable {
    /// A prepared master in `file` (in the job's directory).
    case prepared(file: URL, mime: String, media: PreparedMedia)
    /// Upload the original.
    case original
}

/// Turns an original into a `photo-master-v1` / `video-master-v1` master. Runs in the upload queue, never on the main
/// actor and never in the share extension.
public protocol MediaPreparing: Sendable {
    func preparePhoto(_ source: URL, to directory: URL, name: String) async -> PrepOutcome
    /// `trim` is applied and the audio dropped when `includeAudio` is false; `maxSeconds` cuts Live Photo clips.
    func prepareVideo(_ source: URL, to directory: URL, name: String, trim: Trim?, includeAudio: Bool, maxSeconds: Double?,
                      progress: @escaping @Sendable (Double) -> Void) async -> PrepOutcome
    /// When the photo or video was taken, read from the original (prepared files carry no metadata).
    func captureDate(_ source: URL, kind: MediaKind) async -> Date?
}

public struct MediaPreparer: MediaPreparing {
    public init() {}

    // MARK: Photos

    public func preparePhoto(_ source: URL, to directory: URL, name: String) async -> PrepOutcome {
        guard PrepPolicy.canPreparePhoto(filename: source.lastPathComponent),
              let src = CGImageSourceCreateWithURL(source as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Double, let h = props[kCGImagePropertyPixelHeight] as? Double
        else { return .original }
        // Decode and downscale in one step; the EXIF orientation is applied, never upscaled.
        let target = PrepPolicy.photoTarget(CGSize(width: w, height: h))
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(max(target.width, target.height)),
        ]
        guard let decoded = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary),
              let image = Self.sRGB(decoded) else { return .original }

        let webp = "org.webmproject.webp"
        let types = (CGImageDestinationCopyTypeIdentifiers() as? [String]) ?? []
        let (type, ext, mime) = types.contains(webp) ? (webp, "webp", "image/webp") : (UTType.jpeg.identifier, "jpg", "image/jpeg")
        let out = directory.appending(path: "\(name).\(ext)")
        try? FileManager.default.removeItem(at: out)
        guard let dest = CGImageDestinationCreateWithURL(out as CFURL, type as CFString, 1, nil) else { return .original }
        // No source properties: no GPS, EXIF, XMP or orientation tag. Only the encoder quality.
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: PrepPolicy.photoQuality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else {
            try? FileManager.default.removeItem(at: out)
            return .original
        }
        return .prepared(file: out, mime: mime, media: PreparedMedia(profile: PreparedProfile.photo, width: image.width, height: image.height))
    }

    /// The image redrawn in 8-bit sRGB (Display P3 and HDR sources become sRGB, the master's colour space).
    static func sRGB(_ image: CGImage) -> CGImage? {
        guard let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        if image.colorSpace?.name == CGColorSpace.sRGB, image.bitsPerComponent == 8 { return image }
        guard let ctx = CGContext(data: nil, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        return ctx.makeImage()
    }

    // MARK: Videos

    public func prepareVideo(_ source: URL, to directory: URL, name: String, trim: Trim?, includeAudio: Bool, maxSeconds: Double?,
                             progress: @escaping @Sendable (Double) -> Void) async -> PrepOutcome {
        let asset = AVURLAsset(url: source)
        guard let duration = try? await asset.load(.duration).seconds, duration > 0,
              let track = try? await asset.loadTracks(withMediaType: .video).first,
              let natural = try? await track.load(.naturalSize),
              let transform = try? await track.load(.preferredTransform)
        else { return .original }
        let fps = (try? await track.load(.nominalFrameRate)) ?? 30
        let range = PrepPolicy.clip(duration: duration, trim: trim, maxSeconds: maxSeconds)
        let timeRange = CMTimeRange(start: CMTime(seconds: range.lowerBound, preferredTimescale: 600),
                                    end: CMTime(seconds: range.upperBound, preferredTimescale: 600))

        // A composition with the trim applied and, when muted, no audio track.
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              (try? videoTrack.insertTimeRange(timeRange, of: track, at: .zero)) != nil else { return .original }
        if includeAudio, let audio = try? await asset.loadTracks(withMediaType: .audio).first,
           let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) {
            try? audioTrack.insertTimeRange(timeRange, of: audio, at: .zero)
        }

        // Bake the rotation in and scale to ≤ 1080p, ≤ 30 fps.
        let oriented = CGRect(origin: .zero, size: natural).applying(transform)
        let size = PrepPolicy.videoTarget(CGSize(width: abs(oriented.width), height: abs(oriented.height)))
        let scale = size.width / max(1, abs(oriented.width))
        let place = transform
            .concatenating(CGAffineTransform(translationX: -oriented.minX, y: -oriented.minY))
            .concatenating(CGAffineTransform(scaleX: scale, y: scale))
        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: videoTrack)
        layer.setTransform(place, at: .zero)
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: composition.duration)
        instruction.layerInstructions = [layer]
        let videoComposition = AVMutableVideoComposition()
        videoComposition.renderSize = size
        videoComposition.frameDuration = CMTime(value: 1, timescale: min(PrepPolicy.videoMaxFPS, max(1, Int32(fps.rounded()))))
        videoComposition.instructions = [instruction]

        guard let session = AVAssetExportSession(asset: composition, presetName: AVAssetExportPreset1920x1080) else { return .original }
        let out = directory.appending(path: "\(name).mp4")
        try? FileManager.default.removeItem(at: out)
        session.outputURL = out
        session.outputFileType = .mp4
        session.shouldOptimizeForNetworkUse = true
        session.videoComposition = videoComposition
        session.metadata = []
        session.metadataItemFilter = .forSharing()

        let box = ExportBox(session)
        let watcher = Task {
            while !Task.isCancelled {
                progress(Double(box.session.progress))
                try? await Task.sleep(for: .milliseconds(400))
            }
        }
        await withTaskCancellationHandler {
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                box.session.exportAsynchronously { cont.resume() }
            }
        } onCancel: {
            box.session.cancelExport()
        }
        watcher.cancel()
        guard box.session.status == .completed else {
            try? FileManager.default.removeItem(at: out)
            return .original
        }
        progress(1)
        let ms = Int(((range.upperBound - range.lowerBound) * 1000).rounded())
        return .prepared(file: out, mime: "video/mp4", media: PreparedMedia(
            profile: PreparedProfile.video, width: Int(size.width), height: Int(size.height), durationMs: ms, trimmed: true))
    }

    // MARK: Capture time

    public func captureDate(_ source: URL, kind: MediaKind) async -> Date? {
        if kind == .video {
            let asset = AVURLAsset(url: source)
            if let item = try? await asset.load(.creationDate), let date = try? await item.load(.dateValue) { return date }
            return nil
        }
        guard let src = CGImageSourceCreateWithURL(source as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any] else { return nil }
        return PrepPolicy.exifCaptureTime(exif[kCGImagePropertyExifDateTimeOriginal] as? String,
                                          offset: exif[kCGImagePropertyExifOffsetTimeOriginal] as? String)
    }
}

/// Lets the export session cross into the progress watcher and the completion handler.
private final class ExportBox: @unchecked Sendable {
    let session: AVAssetExportSession
    init(_ session: AVAssetExportSession) { self.session = session }
}
