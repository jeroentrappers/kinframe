import Foundation

/// User websocket events (docs/api/README.md §6.2). Unknown types are ignored.
public enum LiveEvent: Equatable, Sendable {
    case sendUpdated(sendId: String)
    case reaction(placementId: String, frameId: String, reaction: Reaction?, byFrameName: String?)
    case reply(placementId: String, frameId: String, preset: ReplyPreset)
    case frameStatus(frameId: String, online: Bool, lastSeenAt: Date?)
    case joinRequest(frameId: String, name: String?)
    case membershipUpdated(frameId: String)
    /// A frame accepted or declined an event sent to it.
    case invitationDecided(frameId: String, invitation: EventInvitation)
    /// A reaction or quick reply on a placement that came through a feed (to the poster).
    case feedReaction(feedId: String, postId: String, placementId: String, reaction: Reaction?, byFrameName: String?)
    case feedReply(feedId: String, postId: String, placementId: String, preset: ReplyPreset)
    /// Delivery or seen counts of a feed post changed.
    case feedPostUpdated(feedId: String, postId: String, summary: FeedReceiptSummary?)
    /// A frame asks to subscribe to a feed (feed owner and admins).
    case subscriptionRequest(feedId: String, subscription: FeedSubscription?)
    /// Approved, rejected, paused, resumed, history changed or removed.
    case subscriptionUpdated(feedId: String, frameId: String?, subscription: FeedSubscription?)
    /// Someone asks to join a feed (feed owner and admins).
    case feedJoinRequest(feedId: String, name: String?)
    /// Your feed role changed, you were added or removed, or the feed was deleted.
    case feedMembershipUpdated(feedId: String)
    /// A poster suggested a birthday person (feed owner and admins).
    case birthdaySuggested(feedId: String, birthday: BirthdayPerson?)
    /// Your suggestion was approved or rejected.
    case birthdayDecided(feedId: String, birthday: BirthdayPerson?)
    /// A birthday person was added, changed, approved or rejected.
    case birthdayUpdated(feedId: String, birthday: BirthdayPerson?)
    case birthdayRemoved(feedId: String, birthdayId: String)
    /// The morning notification while the app is open: whose birthday it is today, per feed.
    case birthdayToday(date: String, birthdays: [BirthdayRef])
    /// A frame said happy birthday.
    case birthdayGreeting(feedId: String, greeting: FeedBirthdayGreeting?)
    /// A photo source's status, counts or last run changed (its managers and its owner).
    case photoSourceUpdated(PhotoSource)
    /// A photo source was deleted, also with its album or feed.
    case photoSourceRemoved(sourceId: String, albumId: String?, feedId: String?)
    /// Status or progress of your Google Photos import changed.
    case googleImportUpdated(GoogleImport)
    /// An inbox address was created, changed, paused, regenerated or taken over, or its counts changed (its managers).
    case emailAddressUpdated(EmailAddress)
    case emailAddressRemoved(addressId: String, frameId: String?, feedId: String?)
    /// Someone not yet allowed mailed an address, or mailed again.
    case emailSenderPending(addressId: String, pending: EmailPendingSender?)
    /// A pending sender was decided, or the sender list changed (`decision`: approved, rejected, blocked, added, …).
    case emailSenderDecided(addressId: String, senderId: String?, decision: String?)
    /// A Telegram chat was connected, changed, paused, taken over or disconnected, or its status or counts changed.
    case telegramChatUpdated(TelegramChat)
    case telegramChatRemoved(chatId: String, frameId: String?, feedId: String?)
    /// Your connect or link code was used, or redeeming it failed.
    case telegramCodeUpdated(TelegramCode)
    /// Your Telegram account was linked, unlinked (nil) or blocked the bot.
    case telegramLinkUpdated(TelegramLink?)
    /// Family social: timeline, threads, reactions, circles, chats, badge and encouragement (SocialEvents.swift).
    case social(SocialEvent)
    /// A frame's follow of a family on another server changed (README §5.22).
    case remoteSubscriptionUpdated(frameId: String, subscription: RemoteSubscription?)
    /// Leaving (Leaving.swift): placements taken away or restored, keep requests.
    case leaving(LeavingEvent)

    public struct BirthdayRef: Equatable, Sendable {
        public var feedId: String
        public var birthdayId: String

        public init(feedId: String, birthdayId: String) {
            self.feedId = feedId
            self.birthdayId = birthdayId
        }
    }

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
            let reaction = str("reaction").flatMap(Reaction.init(rawValue:))
            if let feed = str("feed_id"), let post = str("post_id") {
                return .feedReaction(feedId: feed, postId: post, placementId: p, reaction: reaction, byFrameName: str("by_frame_name"))
            }
            return .reaction(placementId: p, frameId: f, reaction: reaction, byFrameName: str("by_frame_name"))
        case "reply":
            guard let p = str("placement_id"), let f = str("frame_id"), let preset = str("preset").flatMap(ReplyPreset.init(rawValue:)) else { return nil }
            if let feed = str("feed_id"), let post = str("post_id") {
                return .feedReply(feedId: feed, postId: post, placementId: p, preset: preset)
            }
            return .reply(placementId: p, frameId: f, preset: preset)
        case "feed_post.updated":
            guard let feed = str("feed_id"), let post = str("post_id") else { return nil }
            return .feedPostUpdated(feedId: feed, postId: post, summary: decode(FeedReceiptSummary.self, obj["summary"]))
        case "subscription.request":
            guard let feed = str("feed_id") else { return nil }
            return .subscriptionRequest(feedId: feed, subscription: decode(FeedSubscription.self, obj["subscription"]))
        case "subscription.updated":
            guard let feed = str("feed_id") else { return nil }
            return .subscriptionUpdated(feedId: feed, frameId: str("frame_id"), subscription: decode(FeedSubscription.self, obj["subscription"]))
        case "feed.join_request":
            guard let feed = str("feed_id") else { return nil }
            let user = (obj["request"] as? [String: Any])?["user"] as? [String: Any]
            return .feedJoinRequest(feedId: feed, name: user?["display_name"] as? String)
        case "feed.membership_updated":
            guard let feed = str("feed_id") else { return nil }
            return .feedMembershipUpdated(feedId: feed)
        case "birthday.suggested", "birthday.decided", "birthday.updated":
            guard let feed = str("feed_id") else { return nil }
            let b = decode(BirthdayPerson.self, obj["birthday"])
            switch type {
            case "birthday.suggested": return .birthdaySuggested(feedId: feed, birthday: b)
            case "birthday.decided": return .birthdayDecided(feedId: feed, birthday: b)
            default: return .birthdayUpdated(feedId: feed, birthday: b)
            }
        case "birthday.removed":
            guard let feed = str("feed_id"), let b = str("birthday_id") else { return nil }
            return .birthdayRemoved(feedId: feed, birthdayId: b)
        case "birthday.today":
            let refs = (obj["birthdays"] as? [[String: Any]] ?? []).compactMap { r -> BirthdayRef? in
                guard let f = r["feed_id"] as? String, let b = r["birthday_id"] as? String else { return nil }
                return BirthdayRef(feedId: f, birthdayId: b)
            }
            return .birthdayToday(date: str("date") ?? "", birthdays: refs)
        case "birthday.greeting":
            guard let feed = str("feed_id") else { return nil }
            return .birthdayGreeting(feedId: feed, greeting: decode(FeedBirthdayGreeting.self, obj["greeting"]))
        case "photo_source.updated":
            return decode(PhotoSource.self, obj["source"]).map { .photoSourceUpdated($0) }
        case "photo_source.removed":
            guard let id = str("source_id") else { return nil }
            return .photoSourceRemoved(sourceId: id, albumId: str("album_id"), feedId: str("feed_id"))
        case "google_import.updated":
            return decode(GoogleImport.self, obj["import"]).map { .googleImportUpdated($0) }
        case "email_address.updated":
            return decode(EmailAddress.self, obj["address"]).map { .emailAddressUpdated($0) }
        case "email_address.removed":
            guard let id = str("address_id") else { return nil }
            return .emailAddressRemoved(addressId: id, frameId: str("frame_id"), feedId: str("feed_id"))
        case "email_sender.pending":
            guard let id = str("address_id") else { return nil }
            return .emailSenderPending(addressId: id, pending: decode(EmailPendingSender.self, obj["pending"]))
        case "email_sender.decided":
            guard let id = str("address_id") else { return nil }
            return .emailSenderDecided(addressId: id, senderId: str("sender_id"), decision: str("decision"))
        case "telegram_chat.updated":
            return decode(TelegramChat.self, obj["chat"]).map { .telegramChatUpdated($0) }
        case "telegram_chat.removed":
            guard let id = str("chat_id") else { return nil }
            return .telegramChatRemoved(chatId: id, frameId: str("frame_id"), feedId: str("feed_id"))
        case "telegram_code.updated":
            return decode(TelegramCode.self, obj["code"]).map { .telegramCodeUpdated($0) }
        case "telegram_link.updated":
            return .telegramLinkUpdated(decode(TelegramLink.self, obj["link"]))
        case "remote_subscription.updated":
            guard let f = str("frame_id") else { return nil }
            return .remoteSubscriptionUpdated(frameId: f, subscription: decode(RemoteSubscription.self, obj["remote_subscription"]))
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
        case "event_invitation.decided":
            guard let f = str("frame_id"), let inv = obj["invitation"],
                  let raw = try? JSONSerialization.data(withJSONObject: inv),
                  let invitation = try? OutboxRunner.decoder.decode(EventInvitation.self, from: raw) else { return nil }
            return .invitationDecided(frameId: f, invitation: invitation)
        default:
            return SocialEvent.parse(type, obj).map { .social($0) } ?? LeavingEvent.parse(type, obj).map { .leaving($0) }
        }
    }

    /// A nested object of the event, decoded as a generated schema (nil when absent or not understood).
    private static func decode<T: Decodable>(_ type: T.Type, _ value: Any?) -> T? {
        guard let value, JSONSerialization.isValidJSONObject(value), let raw = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return try? OutboxRunner.decoder.decode(type, from: raw)
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
    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.identifyApp()
        return URLSession(configuration: config)
    }()

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
            var handshake = URLRequest(url: url)
            handshake.identifyApp()
            let ws = self.session.webSocketTask(with: handshake)
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
        // The handshake was refused because this build is no longer served (README §5.15).
        if (ws.response as? HTTPURLResponse)?.statusCode == 426 {
            AppUpdateSignal.raise()
        }
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
