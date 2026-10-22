import Foundation
import HTTPTypes
import OpenAPIRuntime
import OpenAPIURLSession

/// Supplies bearer tokens to API calls.
public protocol TokenProvider: Sendable {
    /// A valid access token for the API resource, or nil when signed out.
    func accessToken() async throws -> String?
    /// Called after a 401: drop the cached token and return a fresh one, or nil to give up (signed out).
    func refreshAfterUnauthorized() async -> String?
}

/// The kinframe API for one server: the generated client plus auth, language and problem handling.
public struct API: Sendable {
    public let client: Client
    public let baseURL: URL
    public let session: URLSession
    let tokens: TokenProvider

    public init(baseURL: URL, tokens: TokenProvider, session: URLSession = API.defaultSession()) {
        self.baseURL = baseURL
        self.tokens = tokens
        self.session = session
        client = Client(
            serverURL: baseURL,
            configuration: .init(dateTranscoder: FlexibleDateTranscoder()),
            transport: URLSessionTransport(configuration: .init(session: session)),
            middlewares: [APIMiddleware(tokens: tokens)]
        )
    }

    public static func defaultSession() -> URLSession {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }

    /// Run a generated-client call, turning failures into `APIError` (or `CancellationError`).
    public func call<T: Sendable>(_ operation: @Sendable (Client) async throws -> T) async throws -> T {
        do {
            return try await operation(client)
        } catch {
            throw APIError.wrap(error)
        }
    }

    /// A bearer token for requests made outside the generated client (websocket, tus, media).
    public func token() async throws -> String? {
        try await tokens.accessToken()
    }

    /// Absolute URL for a path on this server.
    public func url(_ path: String) -> URL {
        URL(string: path, relativeTo: baseURL)?.absoluteURL ?? baseURL.appending(path: path)
    }
}

/// Adds `Authorization` and `Accept-Language`, retries once after a 401 with a fresh token, and turns error
/// responses into thrown `APIError`s so call sites only handle success.
struct APIMiddleware: ClientMiddleware {
    let tokens: TokenProvider

    func intercept(
        _ request: HTTPRequest,
        body: HTTPBody?,
        baseURL: URL,
        operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        var request = request
        request.headerFields[.acceptLanguage] = L10n.code
        // The generator percent-encodes header parameters; If-Match must reach the server as a quoted version ("7").
        if let ifMatch = request.headerFields[.ifMatch], let decoded = ifMatch.removingPercentEncoding {
            request.headerFields[.ifMatch] = decoded
        }
        let anonymous = operationID == "getDiscovery" || operationID == "devToken"
        if !anonymous, let token = try await tokens.accessToken() {
            request.headerFields[.authorization] = "Bearer \(token)"
        }
        var (response, responseBody) = try await next(request, body, baseURL)
        if response.status.code == 401, !anonymous, body?.iterationBehavior != .single,
           let fresh = await tokens.refreshAfterUnauthorized() {
            request.headerFields[.authorization] = "Bearer \(fresh)"
            (response, responseBody) = try await next(request, body, baseURL)
        }
        guard response.status.code >= 400 else { return (response, responseBody) }
        var data: Data?
        if let responseBody {
            data = try? await Data(collecting: responseBody, upTo: 256 * 1024)
        }
        let retryAfter = response.headerFields[.retryAfter].flatMap { Int($0) }
        throw APIError.from(status: response.status.code, body: data, retryAfter: retryAfter)
    }
}

/// RFC 3339 dates with or without fractional seconds (Go emits fractions only when non-zero).
public struct FlexibleDateTranscoder: DateTranscoder {
    public init() {}

    public func encode(_ date: Date) throws -> String {
        ISO8601DateFormatter().string(from: date)
    }

    public func decode(_ string: String) throws -> Date {
        if let date = Self.parse(string) { return date }
        throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Expected an RFC 3339 date, got \(string)"))
    }

    public static func parse(_ string: String) -> Date? {
        if let d = try? Date(string, strategy: .iso8601.year().month().day().dateSeparator(.dash).time(includingFractionalSeconds: true).timeSeparator(.colon).timeZone(separator: .colon)) { return d }
        if let d = try? Date(string, strategy: .iso8601) { return d }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: string)
    }
}
