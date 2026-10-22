import AVFoundation
import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// What the composer needs to know about a picked file before it is sent as is.
public struct MediaInfo: Sendable {
    public var kind: MediaKind
    public var mime: String
    public var bytes: Int64
    /// Display size, with orientation applied.
    public var pixelSize: CGSize?
    public var duration: Double?
    public var hasAudio: Bool?
    public var thumbnail: Data?
}

public enum MediaProbe {
    /// Inspect a local media file: kind, size, duration and a small JPEG thumbnail.
    public static func probe(_ url: URL, maxThumbnail: CGFloat = 600) async -> MediaInfo? {
        let ext = url.pathExtension
        let type = UTType(filenameExtension: ext)
        let mime = type?.preferredMIMEType ?? ComposeRules.mimeType(forExtension: ext)
        guard let kind = ComposeRules.kind(mime: mime, filename: url.lastPathComponent) else { return nil }
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        switch kind {
        case .photo:
            let (size, thumb) = imageInfo(url, maxThumbnail: maxThumbnail)
            return MediaInfo(kind: .photo, mime: mime, bytes: bytes, pixelSize: size, thumbnail: thumb)
        case .video:
            let asset = AVURLAsset(url: url)
            let duration = try? await asset.load(.duration).seconds
            let tracks = (try? await asset.loadTracks(withMediaType: .video)) ?? []
            var size: CGSize?
            if let track = tracks.first, let natural = try? await track.load(.naturalSize), let transform = try? await track.load(.preferredTransform) {
                let r = CGRect(origin: .zero, size: natural).applying(transform)
                size = CGSize(width: abs(r.width), height: abs(r.height))
            }
            let audio = (try? await asset.loadTracks(withMediaType: .audio))?.isEmpty == false
            let thumb = await videoFrame(asset, at: 0, maxSize: maxThumbnail)
            return MediaInfo(kind: .video, mime: mime, bytes: bytes, pixelSize: size, duration: duration, hasAudio: audio, thumbnail: thumb)
        }
    }

    static func imageInfo(_ url: URL, maxThumbnail: CGFloat) -> (CGSize?, Data?) {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return (nil, nil) }
        var size: CGSize?
        if let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
           let w = props[kCGImagePropertyPixelWidth] as? Double, let h = props[kCGImagePropertyPixelHeight] as? Double {
            let orientation = props[kCGImagePropertyOrientation] as? Int ?? 1
            size = orientation >= 5 ? CGSize(width: h, height: w) : CGSize(width: w, height: h)
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxThumbnail,
        ]
        let thumb = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary)
            .flatMap { UIImage(cgImage: $0).jpegData(compressionQuality: 0.8) }
        return (size, thumb)
    }

    /// One frame of a video as JPEG data.
    public static func videoFrame(_ asset: AVAsset, at seconds: Double, maxSize: CGFloat) async -> Data? {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxSize, height: maxSize)
        generator.requestedTimeToleranceBefore = .positiveInfinity
        generator.requestedTimeToleranceAfter = .positiveInfinity
        guard let image = try? await generator.image(at: CMTime(seconds: seconds, preferredTimescale: 600)).image else { return nil }
        return UIImage(cgImage: image).jpegData(compressionQuality: 0.75)
    }

    /// A unique file name that keeps the extension (servers and frames look at it).
    public static func safeFilename(_ name: String, fallbackExtension: String) -> String {
        let base = name.replacingOccurrences(of: "/", with: "_").trimmingCharacters(in: .whitespaces)
        if base.isEmpty { return "media.\(fallbackExtension)" }
        return (base as NSString).pathExtension.isEmpty ? "\(base).\(fallbackExtension)" : base
    }
}
