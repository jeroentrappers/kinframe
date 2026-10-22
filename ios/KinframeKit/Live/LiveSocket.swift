import Foundation

/// User websocket events (docs/api/README.md §6.2). Unknown types are ignored.
public enum LiveEvent: Equatable, Sendable {
    case sendUpdated(sendId: String)
    case reaction(placementId: String, frameId: String, reaction: Reaction?, byFrameName: String?)
    case reply(placementId: String, frameId: String, preset: ReplyPreset)
    case frameStatus(frameId: String, online: Bool, lastSeenAt: Date?)
    case joinRequest(frameId: String, name: String?)
    case membershipUpdated(frameId: String)

    /// Parse one websocket text frame.
    public static func parse(_ data: Data) -> LiveEvent? {
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let type = obj["type"] as? String else { return nil }
        let str = { (k: String) in obj[k] as? String }
        switch type {
        case "send.updated":
            guard let id = (obj["send"] as? [String: Any])?["id"] as? String else { return nil }
            return .sendUpdated(sendId: id)
        case "reaction":
            guard let p = str("placement_id"), let f = str("frame_id") else { return nil }
            return .reaction(placementId: p, frameId: f, reaction: str("reaction").flatMap(Reaction.init(rawValue:)), byFrameName: str("by_frame_name"))
        case "reply":
            guard let p = str("placement_id"), let f = str("frame_id"), let preset = str("preset").flatMap(ReplyPreset.init(rawValue:)) else { return nil }
            return .reply(placementId: p, frameId: f, preset: preset)
        case "frame.status":
            guard let f = str("frame_id") else { return nil }
            return .frameStatus(frameId: f, online: obj["online"] as? Bool ?? false, lastSeenAt: str("last_seen_at").flatMap(FlexibleDateTranscoder.parse))
        case "join_request":
            guard let f = str("frame_id") else { return nil }
            let user = (obj["request"] as? [String: Any])?["user"] as? [String: Any]
            return .joinRequest(frameId: f, name: user?["display_name"] as? String)
        case "membership.updated":
            guard let f = str("frame_id") else { return nil }
            return .membershipUpdated(frameId: f)
        default:
            return nil
        }
    }
}

/// Keeps `GET /v1/ws?access_token=…` open while the app is in the foreground. Reconnects with back-off (1 s → 60 s,
/// ±20 %), and right away with a fresh token after the server closes with 4401 at token expiry.
@MainActor
public final class LiveSocket {
    public enum Status: Sendable { case idle, connecting, open, waiting }

    public private(set) var status: Status = .idle
    public var onEvent: ((LiveEvent) -> Void)?
    public var onStatus: ((Status) -> Void)?

    private var api: API?
    private var task: URLSessionWebSocketTask?
    private var attempt = 0
    private var retry: Task<Void, Never>?
    private var generation = 0
    private let session = URLSession(configuration: .default)

    public init() {}

    public func start(api: API) {
        self.api = api
        guard status == .idle else { return }
        connect()
    }

    public func stop() {
        generation += 1
        retry?.cancel()
        retry = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        api = nil
        set(.idle)
    }

    /// Reconnect now (app came to the foreground, network is back).
    public func kick() {
        guard api != nil, status == .waiting || status == .idle else { return }
        retry?.cancel()
        attempt = 0
        connect()
    }

    private func set(_ s: Status) {
        status = s
        onStatus?(s)
    }

    private func connect() {
        guard let api else { return }
        generation += 1
        let gen = generation
        set(.connecting)
        Task {
            let token = try? await api.token()
            guard gen == self.generation else { return }
            guard let token, var c = URLComponents(url: api.url("/v1/ws"), resolvingAgainstBaseURL: false) else {
                self.schedule()
                return
            }
            c.scheme = c.scheme == "https" ? "wss" : "ws"
            c.queryItems = [URLQueryItem(name: "access_token", value: token)]
            guard let url = c.url else { return }
            let ws = self.session.webSocketTask(with: url)
            self.task = ws
            ws.resume()
            self.receive(ws, gen: gen)
        }
    }

    private func receive(_ ws: URLSessionWebSocketTask, gen: Int) {
        ws.receive { [weak self] result in
            Task { @MainActor in
                guard let self, gen == self.generation else { return }
                switch result {
                case .success(let message):
                    if self.status != .open {
                        self.attempt = 0
                        self.set(.open)
                    }
                    let data: Data? = switch message {
                    case .string(let s): Data(s.utf8)
                    case .data(let d): d
                    @unknown default: nil
                    }
                    if let data, let event = LiveEvent.parse(data) {
                        self.onEvent?(event)
                    }
                    self.receive(ws, gen: gen)
                case .failure:
                    self.closed(ws)
                }
            }
        }
        // The server says hello only implicitly; mark open once the handshake succeeded.
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard let self, gen == self.generation, ws.state == .running, self.status == .connecting else { return }
            self.attempt = 0
            self.set(.open)
        }
    }

    private func closed(_ ws: URLSessionWebSocketTask) {
        task = nil
        if ws.closeCode.rawValue == 4401, let api {
            // Token expired: get a fresh one and reconnect right away.
            Task {
                _ = await api.tokens.refreshAfterUnauthorized()
                self.attempt = 0
                self.connect()
            }
            return
        }
        schedule()
    }

    private func schedule() {
        guard api != nil else { return }
        set(.waiting)
        let delay = backoffDelay(attempt: attempt)
        attempt += 1
        let gen = generation
        retry = Task {
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, gen == self.generation else { return }
            self.connect()
        }
    }
}
