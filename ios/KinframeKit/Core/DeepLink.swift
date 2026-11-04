import Foundation

/// Links that open the app: frame codes and invites.
///
/// - Universal Links: `https://<server>/f/<code>` and `https://<server>/i/<token>` (the QR on a frame, invite links).
/// - Custom scheme fallback, for servers whose domain the app can't claim:
///   `app.kinframe://f/<code>?server=<url>`, `app.kinframe://i/<token>?server=<url>`, or
///   `app.kinframe://open?url=<https link>`.
public enum DeepLink: Equatable, Sendable {
    case frameCode(server: URL?, code: String)
    case invite(server: URL?, token: String)

    public static let scheme = "app.kinframe"

    public var server: URL? {
        switch self {
        case .frameCode(let server, _), .invite(let server, _): server
        }
    }

    public static func parse(_ url: URL) -> DeepLink? {
        guard let scheme = url.scheme?.lowercased() else { return nil }
        if scheme == "https" || scheme == "http" {
            return parseWeb(url)
        }
        guard scheme == Self.scheme, let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let query = Dictionary((c.queryItems ?? []).compactMap { item in item.value.map { (item.name, $0) } }, uniquingKeysWith: { a, _ in a })
        // app.kinframe://open?url=https://server/f/12345678
        if c.host == "open", let inner = query["url"].flatMap(URL.init(string:)), let s = inner.scheme?.lowercased(), s == "https" || s == "http" {
            return parseWeb(inner)
        }
        // app.kinframe://f/<code> (host "f") or app.kinframe:/f/<code> (no host).
        var parts = c.path.split(separator: "/").map(String.init)
        if let host = c.host, !host.isEmpty { parts.insert(host, at: 0) }
        let server = query["server"].flatMap(ServerAddress.normalize)
        return route(parts, server: server)
    }

    static func parseWeb(_ url: URL) -> DeepLink? {
        guard let server = ServerAddress.normalize(url.absoluteString) else { return nil }
        let parts = url.path.split(separator: "/").map(String.init)
        return route(parts, server: server)
    }

    static func route(_ parts: [String], server: URL?) -> DeepLink? {
        guard parts.count == 2 else { return nil }
        switch parts[0] {
        case "f":
            let digits = parts[1].removingPercentEncoding ?? parts[1]
            guard digits.count == Format.codeLength, digits.allSatisfy(\.isASCII), digits.allSatisfy(\.isNumber) else { return nil }
            return .frameCode(server: server, code: digits)
        case "i":
            let token = parts[1]
            let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
            guard (22...64).contains(token.count), token.unicodeScalars.allSatisfy({ $0.isASCII && allowed.contains($0) }) else { return nil }
            return .invite(server: server, token: token)
        default:
            return nil
        }
    }

    /// Whether this link points at a server other than `current` (then the app asks before switching).
    public func needsServerSwitch(from current: URL?) -> Bool {
        guard let server else { return false }
        guard let current else { return true }
        return ServerAddress.normalize(server.absoluteString) != ServerAddress.normalize(current.absoluteString)
    }
}
