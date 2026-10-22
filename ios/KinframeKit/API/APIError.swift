import Foundation
import OpenAPIRuntime

/// An API failure: an RFC 9457 problem with a stable `code` (docs/api/README.md §1), or a transport failure
/// (`network`). Views show `message`, which is localized from the code like the web app does.
public struct APIError: Error, Equatable, Sendable, LocalizedError {
    public var status: Int
    public var code: String
    public var title: String?
    public var detail: String?
    /// Seconds from `Retry-After`, when the server sent one.
    public var retryAfter: Int?

    public init(status: Int, code: String, title: String? = nil, detail: String? = nil, retryAfter: Int? = nil) {
        self.status = status
        self.code = code
        self.title = title
        self.detail = detail
        self.retryAfter = retryAfter
    }

    public static let network = APIError(status: 0, code: "network")
    public static let unauthorized = APIError(status: 401, code: "unauthorized")
    public static let unexpected = APIError(status: 0, code: "server_error")

    /// Build from a problem+json body; falls back to a status-based code.
    public static func from(status: Int, body: Data?, retryAfter: Int? = nil) -> APIError {
        struct Problem: Decodable {
            var code: String?
            var title: String?
            var detail: String?
        }
        let p = body.flatMap { try? JSONDecoder().decode(Problem.self, from: $0) }
        let fallback: String = switch status {
        case 401: "unauthorized"
        case 403: "forbidden"
        case 404: "not_found"
        case 409: "version_conflict"
        case 412: "version_conflict"
        case 413: "upload_too_large"
        case 429: "rate_limited"
        default: "server_error"
        }
        return APIError(status: status, code: p?.code ?? fallback, title: p?.title, detail: p?.detail, retryAfter: retryAfter)
    }

    /// Map anything thrown by the generated client, URLSession or our middleware to an APIError.
    public static func wrap(_ error: Error) -> Error {
        if let e = error as? APIError { return e }
        if let e = error as? ClientError { return wrap(e.underlyingError) }
        if error is CancellationError { return error }
        if let e = error as? URLError {
            return e.code == .cancelled ? CancellationError() : APIError.network
        }
        if error is DecodingError { return APIError(status: 0, code: "server_error", detail: String(describing: error)) }
        return error
    }

    /// Localized, user-facing text for this error (web: problem.<code>).
    public var message: String {
        let key = "problem.\(code)"
        if L10n.has(key) { return L(key) }
        return title ?? L("problem.server_error")
    }

    public var errorDescription: String? { message }
}

extension L10n {
    public static func has(_ key: String) -> Bool {
        lookup(key) != key
    }
}

/// User-facing text for any error thrown by API calls.
public func errorMessage(_ error: Error) -> String {
    if let e = APIError.wrap(error) as? APIError { return e.message }
    return L("problem.server_error")
}
