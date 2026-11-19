import Foundation

/// Family social websocket events (docs/api/README.md §6.2, the rows added for family social), as one case of
/// `LiveEvent` so the older event list stays as it is.
public enum SocialEvent: Equatable, Sendable {
    case postUpdated(postId: String, feedId: String?, frameId: String?, thread: ThreadSummary?)
    case postRemoved(postId: String)
    case commentCreated(postId: String, comment: Comment)
    case commentUpdated(postId: String, comment: Comment)
    case commentDeleted(postId: String, commentId: String)
    case threadRead(postId: String, readUpTo: String?)
    case reactionUpdated(targetKind: ReactionTargetKind, targetId: String, postId: String?, conversationId: String?, summary: ReactionSummary?)
    case circleUpdated(feedId: String, circle: FamilyCircle?)
    case circleRemoved(feedId: String, circleId: String)
    case conversationUpdated(Conversation)
    case conversationRemoved(conversationId: String)
    case messageCreated(conversationId: String, message: Message)
    case messageUpdated(conversationId: String, message: Message)
    case readUpdated(conversationId: String, userId: String, lastReadMessageId: String?)
    case typing(conversationId: String, userId: String, name: String, recording: Bool, expiresAt: Date)
    case badgeUpdated(Badge)
    case reportCreated(Report?)
    case photoRequestCreated(PhotoRequest)
    case photoRequestUpdated(PhotoRequest)
    case streakUpdated(feedId: String, streak: FeedStreakSummary?)
    case promptUpdated(feedId: String, prompt: FeedPrompt?)
    case milestoneAchieved(Milestone)

    /// The event for one websocket message, or nil when the type isn't a family social one.
    static func parse(_ type: String, _ obj: [String: Any]) -> SocialEvent? {
        let str = { (k: String) in obj[k] as? String }
        switch type {
        case "post.updated":
            guard let post = str("post_id") else { return nil }
            return .postUpdated(postId: post, feedId: str("feed_id"), frameId: str("frame_id"), thread: decode(ThreadSummary.self, obj["thread"]))
        case "post.removed":
            return str("post_id").map { .postRemoved(postId: $0) }
        case "comment.created", "comment.updated":
            guard let post = str("post_id"), let c = decode(Comment.self, obj["comment"]) else { return nil }
            return type == "comment.created" ? .commentCreated(postId: post, comment: c) : .commentUpdated(postId: post, comment: c)
        case "comment.deleted":
            guard let post = str("post_id"), let id = str("comment_id") else { return nil }
            return .commentDeleted(postId: post, commentId: id)
        case "thread.read":
            guard let post = str("post_id") else { return nil }
            return .threadRead(postId: post, readUpTo: str("read_up_to"))
        case "reaction.updated":
            guard let kind = str("target_kind").flatMap(ReactionTargetKind.init(rawValue:)), let id = str("target_id") else { return nil }
            return .reactionUpdated(targetKind: kind, targetId: id, postId: str("post_id"), conversationId: str("conversation_id"),
                                    summary: decode(ReactionSummary.self, obj["summary"]))
        case "circle.updated":
            guard let feed = str("feed_id") else { return nil }
            return .circleUpdated(feedId: feed, circle: decode(FamilyCircle.self, obj["circle"]))
        case "circle.removed":
            guard let feed = str("feed_id"), let id = str("circle_id") else { return nil }
            return .circleRemoved(feedId: feed, circleId: id)
        case "conversation.updated":
            return decode(Conversation.self, obj["conversation"]).map { .conversationUpdated($0) }
        case "conversation.removed":
            return str("conversation_id").map { .conversationRemoved(conversationId: $0) }
        case "message.created", "message.updated":
            guard let conv = str("conversation_id"), let m = decode(Message.self, obj["message"]) else { return nil }
            return type == "message.created" ? .messageCreated(conversationId: conv, message: m) : .messageUpdated(conversationId: conv, message: m)
        case "read.updated":
            guard let conv = str("conversation_id"), let user = str("user_id") else { return nil }
            return .readUpdated(conversationId: conv, userId: user, lastReadMessageId: str("last_read_message_id"))
        case "typing":
            guard let conv = str("conversation_id"), let user = str("user_id") else { return nil }
            let until = str("expires_at").flatMap(FlexibleDateTranscoder.parse) ?? Date().addingTimeInterval(6)
            return .typing(conversationId: conv, userId: user, name: str("display_name") ?? "", recording: str("activity") == "recording", expiresAt: until)
        case "badge.updated":
            return decode(Badge.self, obj["badge"]).map { .badgeUpdated($0) }
        case "report.created":
            return .reportCreated(decode(Report.self, obj["report"]))
        case "photo_request.created", "photo_request.updated":
            guard let r = decode(PhotoRequest.self, obj["request"]) else { return nil }
            return type == "photo_request.created" ? .photoRequestCreated(r) : .photoRequestUpdated(r)
        case "streak.updated":
            guard let feed = str("feed_id") else { return nil }
            return .streakUpdated(feedId: feed, streak: decode(FeedStreakSummary.self, obj["streak"]))
        case "prompt.updated":
            guard let feed = str("feed_id") else { return nil }
            return .promptUpdated(feedId: feed, prompt: decode(FeedPrompt.self, obj["prompt"]))
        case "milestone.achieved":
            return decode(Milestone.self, obj["milestone"]).map { .milestoneAchieved($0) }
        default:
            return nil
        }
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ value: Any?) -> T? {
        guard let value, JSONSerialization.isValidJSONObject(value), let raw = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return try? OutboxRunner.decoder.decode(type, from: raw)
    }
}
