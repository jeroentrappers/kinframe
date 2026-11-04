import Foundation
import KinframeKit
import os

/// Answers requests from a test-provided handler. Install with `StubURLProtocol.session(...)`.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    enum Reply {
        case response(status: Int, headers: [String: String] = [:], body: Data = Data())
        case failure(URLError.Code)
    }

    struct Request: Sendable {
        var method: String
        var url: URL
        var headers: [String: String]
        var body: Data

        func header(_ name: String) -> String? {
            headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
        }
    }

    typealias Handler = @Sendable (Request) -> Reply

    private static let handlers = OSAllocatedUnfairLock(initialState: [String: Handler]())

    /// A session whose requests go to `handler`.
    static func session(_ handler: @escaping Handler) -> URLSession {
        let id = UUID().uuidString
        handlers.withLock { $0[id] = handler }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        config.httpAdditionalHeaders = ["X-Stub-Id": id]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let headers = request.allHTTPHeaderFields ?? [:]
        guard let id = headers["X-Stub-Id"], let handler = Self.handlers.withLock({ $0[id] }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        let req = Request(method: request.httpMethod ?? "GET", url: request.url!, headers: headers, body: Self.body(of: request))
        switch handler(req) {
        case .failure(let code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        case .response(let status, let headers, let body):
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            if !body.isEmpty { client?.urlProtocol(self, didLoad: body) }
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}

    private static func body(of request: URLRequest) -> Data {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 64 * 1024)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return data
    }
}

/// Fixed tokens for tests; counts refreshes.
final class StubTokens: TokenProvider, @unchecked Sendable {
    private let state = OSAllocatedUnfairLock(initialState: (token: "t1", refreshes: 0))

    var refreshes: Int { state.withLock { $0.refreshes } }

    func accessToken() async throws -> String? { state.withLock { $0.token } }

    func refreshAfterUnauthorized() async -> String? {
        state.withLock {
            $0.refreshes += 1
            $0.token = "t\($0.refreshes + 1)"
            return $0.token
        }
    }
}
