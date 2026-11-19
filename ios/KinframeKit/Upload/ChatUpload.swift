import Foundation

/// Photos, videos and voice recordings for a chat message (README §5.19): prepared like every upload
/// (photo-master-v1, video-master-v1; originals only when preparing failed and they're within the limits), then sent
/// with tus in the foreground. The message is created with the upload ids afterwards.
public enum ChatUpload {
    /// Prepare and upload one picked photo or video; returns its `items[]` entry.
    public static func item(_ source: URL, filename: String, api: API, maxVideoSeconds: Int, preparer: MediaPreparing = MediaPreparer(),
                            progress: @escaping @Sendable (Double) -> Void = { _ in }) async throws -> Components.Schemas.MessageItemCreate {
        let dir = FileManager.default.temporaryDirectory.appending(path: "chat-upload-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        guard let kind = ComposeRules.kind(mime: nil, filename: filename) ?? ComposeRules.kind(mime: nil, filename: source.lastPathComponent) else {
            throw OutboxRunner.unprepared
        }
        let captured = await preparer.captureDate(source, kind: kind)
        let outcome: PrepOutcome = switch kind {
        case .photo: await preparer.preparePhoto(source, to: dir, name: "photo")
        case .video: await preparer.prepareVideo(source, to: dir, name: "video", trim: nil, includeAudio: true,
                                                 maxSeconds: Double(maxVideoSeconds), progress: { p in progress(p * 0.3) })
        }
        var file = source
        var mime = ComposeRules.mimeType(forExtension: source.pathExtension)
        var media: PreparedMedia?
        switch outcome {
        case .prepared(let out, let m, let prepared):
            file = out
            mime = m
            media = prepared
        case .failed:
            guard await preparer.withinLimits(source, kind: kind) else { throw OutboxRunner.unprepared }
        }
        let base = (filename as NSString).deletingPathExtension.isEmpty ? (kind == .video ? "video" : "photo") : (filename as NSString).deletingPathExtension
        let id = try await PhotoUpload.tus(file, filename: base + "." + file.pathExtension, mime: mime, prepared: media?.profile, api: api,
                                           progress: { p in progress(kind == .video ? 0.3 + p * 0.7 : p) })
        return Components.Schemas.MessageItemCreate(uploadId: id, capturedAt: captured, width: media?.width, height: media?.height,
                                                    durationMs: kind == .video ? media?.durationMs : nil)
    }

    /// Upload a voice recording (AAC in MP4, `.m4a`, as recorded); returns the upload id.
    public static func voice(_ file: URL, api: API, progress: @escaping @Sendable (Double) -> Void = { _ in }) async throws -> String {
        try await PhotoUpload.tus(file, filename: "voice.m4a", mime: "audio/mp4", prepared: nil, api: api, progress: progress)
    }
}
