import Foundation

/// A single photo uploaded in the foreground with tus, for things that aren't a send (the photo of an event sent to a
/// frame). Like every upload it's prepared first (photo-master-v1); the original only goes as it is when preparing
/// failed and it's within the master's limits.
public enum PhotoUpload {
    /// Prepare `source` and upload it; returns the tus upload id. `progress` gets 0…1 of the bytes.
    public static func upload(_ source: URL, filename: String, api: API, preparer: MediaPreparing = MediaPreparer(),
                              session: URLSession = .shared, progress: @escaping @Sendable (Double) -> Void = { _ in }) async throws -> String {
        let dir = FileManager.default.temporaryDirectory.appending(path: "photo-upload-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        var file = source
        var mime = ComposeRules.mimeType(forExtension: source.pathExtension)
        var prepared: String?
        switch await preparer.preparePhoto(source, to: dir, name: "photo") {
        case .prepared(let out, let m, let media):
            file = out
            mime = m
            prepared = media.profile
        case .failed:
            guard await preparer.withinLimits(source, kind: .photo) else { throw OutboxRunner.unprepared }
        }
        let size = Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        let name = ((filename as NSString).deletingPathExtension.isEmpty ? "photo" : (filename as NSString).deletingPathExtension) + "." + file.pathExtension
        var upload = TusUpload(file: file.lastPathComponent, size: size, filename: name, mime: mime)
        upload.prepared = prepared
        let endpoint = api.baseURL.appending(path: "v1/uploads")

        var failures = 0
        var refreshed = false
        while true {
            try Task.checkCancellation()
            let step = Tus.next(upload)
            guard var request = Tus.request(for: step, upload: upload, endpoint: endpoint) else { break }
            guard let token = try await api.token() else { throw APIError.unauthorized }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let body: Data? = switch step {
            case .create(let length): try chunk(file, offset: 0, length: length)
            case .patch(let offset, let length): try chunk(file, offset: offset, length: length)
            default: nil
            }
            do {
                let (data, response) = if let body { try await session.upload(for: request, from: body) } else { try await session.data(for: request) }
                _ = data
                let http = response as? HTTPURLResponse
                let headers = (http?.allHeaderFields ?? [:]).reduce(into: [String: String]()) { $0["\($1.key)"] = "\($1.value)" }
                try Tus.apply(step, to: &upload, status: http?.statusCode ?? 0, headers: headers, endpoint: endpoint)
                failures = 0
                progress(Double(upload.bytesDone) / Double(max(1, upload.size)))
            } catch TusError.unauthorized {
                guard !refreshed, await api.refreshedToken() != nil else { throw APIError.unauthorized }
                refreshed = true
            } catch TusError.failed(let e) {
                throw e
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                Tus.interrupted(&upload)
                failures += 1
                if failures >= 4 { throw (error as? APIError) ?? APIError.network }
                try await Task.sleep(for: .seconds(Double(failures)))
            }
        }
        guard let id = upload.uploadId else { throw APIError.network }
        return id
    }

    private static func chunk(_ url: URL, offset: Int64, length: Int64) throws -> Data {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(offset))
        return try handle.read(upToCount: Int(length)) ?? Data()
    }
}
