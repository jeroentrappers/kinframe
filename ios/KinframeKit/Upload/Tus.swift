import Foundation

/// One file's resumable upload (tus 1.0, docs/api/README.md §5.3), persisted between steps so it survives the app
/// being suspended or killed.
public struct TusUpload: Codable, Equatable, Sendable {
    /// File name inside the job's directory.
    public var file: String
    public var size: Int64
    public var filename: String
    public var mime: String
    public var sha256: String?
    /// The upload URL from `Location` once created.
    public var location: URL?
    /// Bytes the server has.
    public var offset: Int64 = 0
    /// The offset is unknown (an upload request was interrupted): ask with HEAD before sending more.
    public var needsHead = false
    /// The server already has this file as an item (`Kinframe-Existing-Item`): no bytes needed.
    public var existingItemId: String?

    public init(file: String, size: Int64, filename: String, mime: String, sha256: String? = nil) {
        self.file = file
        self.size = size
        self.filename = filename
        self.mime = mime
        self.sha256 = sha256
    }

    public var isComplete: Bool {
        existingItemId != nil || (location != nil && !needsHead && offset >= size)
    }

    /// What goes into `items[].upload_id`: the tus upload id, or the existing item id.
    public var uploadId: String? {
        if let existingItemId { return existingItemId }
        guard isComplete, let location else { return nil }
        return Tus.uploadId(from: location)
    }

    /// Bytes counted as done for progress.
    public var bytesDone: Int64 {
        isComplete ? size : min(offset, size)
    }
}

/// The next request for an upload.
public enum TusStep: Codable, Equatable, Hashable, Sendable {
    /// `POST /v1/uploads` with the first chunk (creation-with-upload).
    case create(length: Int64)
    /// `HEAD <location>` to learn the server's offset.
    case head
    /// `PATCH <location>` with the bytes `[offset, offset + length)`.
    case patch(offset: Int64, length: Int64)
    case done
}

public enum TusError: Error, Equatable {
    /// Try again (after a pause); the upload state already reflects what is known.
    case retry
    /// The access token was rejected.
    case unauthorized
    /// Give up on this file.
    case failed(APIError)
}

/// The tus protocol as a pure state machine. Transport, files and tokens live elsewhere (TusUploader).
public enum Tus {
    public static let version = "1.0.0"
    public static let chunkSize: Int64 = 4 * 1024 * 1024

    public static func next(_ u: TusUpload, chunkSize: Int64 = chunkSize) -> TusStep {
        if u.isComplete { return .done }
        guard u.location != nil else { return .create(length: min(chunkSize, u.size)) }
        if u.needsHead { return .head }
        return .patch(offset: u.offset, length: min(chunkSize, u.size - u.offset))
    }

    /// `Upload-Metadata`: comma-separated `key base64(value)` pairs.
    public static func metadata(filename: String, filetype: String, sha256: String?) -> String {
        var pairs = [("filename", filename), ("filetype", filetype)]
        if let sha256 { pairs.append(("sha256", sha256)) }
        return pairs.map { "\($0.0) \(Data($0.1.utf8).base64EncodedString())" }.joined(separator: ",")
    }

    /// The request for a step (without Authorization). Bodies are sent from a file by the transport.
    public static func request(for step: TusStep, upload u: TusUpload, endpoint: URL) -> URLRequest? {
        var r: URLRequest
        switch step {
        case .done:
            return nil
        case .create:
            // URLSession sets Content-Length from the body file.
            r = URLRequest(url: endpoint)
            r.httpMethod = "POST"
            r.setValue(String(u.size), forHTTPHeaderField: "Upload-Length")
            r.setValue(metadata(filename: u.filename, filetype: u.mime, sha256: u.sha256), forHTTPHeaderField: "Upload-Metadata")
            r.setValue("application/offset+octet-stream", forHTTPHeaderField: "Content-Type")
        case .head:
            guard let location = u.location else { return nil }
            r = URLRequest(url: location)
            r.httpMethod = "HEAD"
            r.cachePolicy = .reloadIgnoringLocalCacheData
        case .patch(let offset, _):
            guard let location = u.location else { return nil }
            r = URLRequest(url: location)
            r.httpMethod = "PATCH"
            r.setValue(String(offset), forHTTPHeaderField: "Upload-Offset")
            r.setValue("application/offset+octet-stream", forHTTPHeaderField: "Content-Type")
        }
        r.setValue(version, forHTTPHeaderField: "Tus-Resumable")
        r.timeoutInterval = 120
        return r
    }

    /// Update the upload with a response to `step`. Throws what the caller should do next.
    public static func apply(_ step: TusStep, to u: inout TusUpload, status: Int, headers: [String: String], endpoint: URL) throws {
        func header(_ name: String) -> String? {
            headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
        }
        let serverOffset = header("Upload-Offset").flatMap { Int64($0.trimmingCharacters(in: .whitespaces)) }

        if status == 401 { throw TusError.unauthorized }
        if status == 413 { throw TusError.failed(APIError(status: 413, code: "upload_too_large")) }

        switch step {
        case .done:
            return
        case .create(let length):
            guard status == 201 || status == 200 else {
                if status >= 400 && status < 500 && status != 408 && status != 429 {
                    throw TusError.failed(APIError.from(status: status, body: nil))
                }
                throw TusError.retry
            }
            if let existing = header("Kinframe-Existing-Item"), !existing.isEmpty {
                u.existingItemId = existing
            }
            guard let loc = header("Location"), let url = URL(string: loc, relativeTo: endpoint)?.absoluteURL else {
                if u.existingItemId != nil { return }
                throw TusError.retry
            }
            u.location = url
            // creation-with-upload: the server reports how much of the first chunk it kept.
            u.offset = serverOffset ?? length
            u.needsHead = false
        case .head:
            switch status {
            case 200, 204:
                guard let serverOffset else { throw TusError.retry }
                u.offset = serverOffset
                u.needsHead = false
            case 404, 410:
                reset(&u)
                throw TusError.retry
            default:
                throw TusError.retry
            }
        case .patch(let offset, let length):
            switch status {
            case 200, 204:
                u.offset = serverOffset ?? (offset + length)
                u.needsHead = false
            case 409:
                // Offset mismatch: ask the server where it is.
                u.needsHead = true
                throw TusError.retry
            case 404, 410:
                reset(&u)
                throw TusError.retry
            default:
                u.needsHead = true
                throw TusError.retry
            }
        }
    }

    /// The upload expired or vanished on the server: start over.
    static func reset(_ u: inout TusUpload) {
        u.location = nil
        u.offset = 0
        u.needsHead = false
    }

    /// An interrupted request (network error, process killed): the offset is unknown if the upload exists.
    public static func interrupted(_ u: inout TusUpload) {
        if u.location != nil { u.needsHead = true }
    }

    /// tus upload URL → upload id (its last path segment).
    public static func uploadId(from url: URL) -> String {
        url.lastPathComponent.removingPercentEncoding ?? url.lastPathComponent
    }
}
