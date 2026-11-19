import Foundation

/// Where a tapped family-social notification leads (docs/api/README.md §6.3, the family social keys).
public enum SocialRoute: Equatable, Hashable, Sendable {
    /// A post's thread, scrolled to a comment when there is one (`comment`, `comment_reply`, `mention`,
    /// `reaction_person` on posts and comments).
    case post(postId: String, commentId: String?)
    /// A conversation (`message`, `mention` in a chat, `conversation_added`, reactions on messages).
    case conversation(conversationId: String, messageId: String?)
    /// The reports to handle (`report`): the family's, or the operator's when there is no family.
    case reports(feedId: String?)
    /// A frame asked for photos (`frame_request`) or is getting quiet (`reminder_quiet`): "Send something to Oma's
    /// frame".
    case frameNudge(frameId: String, requestId: String?)
    /// The family's streak is at risk (`reminder_streak`): the family, to post.
    case feedStreak(feedId: String)
    /// A birthday is coming (`reminder_birthday`): the family's birthdays.
    case birthdayReminder(feedId: String, birthdayId: String?)
    /// "Seen and loved" (`weekly_summary`).
    case weeklySummary(weekOf: String?)
    /// More reactions today (`reaction_digest`): Home.
    case home
}

public enum SocialPush {
    /// The family social keys (README §6.3); nil for other keys.
    public static func route(key: String, id: (String) -> String?) -> SocialRoute? {
        switch key {
        case "comment", "comment_reply":
            return id("post_id").map { .post(postId: $0, commentId: id("comment_id")) }
        case "mention":
            if let conv = id("conversation_id") { return .conversation(conversationId: conv, messageId: id("message_id")) }
            return id("post_id").map { .post(postId: $0, commentId: id("comment_id")) }
        case "reaction_person":
            if let conv = id("conversation_id") { return .conversation(conversationId: conv, messageId: id("target_id")) }
            if id("target_kind") == "comment", let post = id("post_id") { return .post(postId: post, commentId: id("target_id")) }
            if let post = id("post_id") ?? (id("target_kind") == "post" ? id("target_id") : nil) { return .post(postId: post, commentId: nil) }
            return .home
        case "reaction_digest":
            return .home
        case "message", "conversation_added":
            return id("conversation_id").map { .conversation(conversationId: $0, messageId: id("message_id")) }
        case "report":
            return .reports(feedId: id("feed_id"))
        case "frame_request", "reminder_quiet":
            return id("frame_id").map { .frameNudge(frameId: $0, requestId: id("request_id")) }
        case "reminder_streak":
            return id("feed_id").map { .feedStreak(feedId: $0) }
        case "reminder_birthday":
            return id("feed_id").map { .birthdayReminder(feedId: $0, birthdayId: id("birthday_id")) }
        case "weekly_summary":
            return .weeklySummary(weekOf: id("week_of"))
        default:
            return nil
        }
    }

    // MARK: Notification categories

    /// Reminders carry Snooze 1 month and Fewer reminders (APNs category `kinframe.reminder`, README §6.3).
    public static let reminderCategory = "kinframe.reminder"
    /// Chat messages can be answered from the notification. The server sets no category for them yet, so the app
    /// also offers the reply for `message`/`mention` pushes that carry this category (see the client parity notes).
    public static let messageCategory = "kinframe.message"
    public static let replyAction = "chat.reply"

    public static let reminderKeys: Set<String> = ["reminder_quiet", "reminder_streak", "reminder_birthday", "weekly_summary"]

    /// The thread a notification belongs to in Notification Center: one per conversation, one per post, else the key.
    public static func threadIdentifier(_ payload: [AnyHashable: Any]) -> String? {
        if let tag = payload["tag"] as? String, !tag.isEmpty { return tag }
        let ids = (payload["ids"] as? [String: Any]) ?? [:]
        if let conv = ids["conversation_id"] as? String { return "conversation:\(conv)" }
        if let post = ids["post_id"] as? String { return "post:\(post)" }
        return payload["key"] as? String
    }

    /// The conversation a notification's reply goes to.
    public static func conversationId(_ payload: [AnyHashable: Any]) -> String? {
        let ids = (payload["ids"] as? [String: Any]) ?? [:]
        return (ids["conversation_id"] as? String) ?? (payload["conversation_id"] as? String)
    }

    /// The reminder buttons a payload offers (`actions`), or the defaults for the reminder keys.
    public static func actions(_ payload: [AnyHashable: Any]) -> [ReminderAction] {
        if let list = payload["actions"] as? [String] { return list.compactMap(ReminderAction.init(rawValue:)) }
        if let key = payload["key"] as? String, reminderKeys.contains(key) { return ReminderAction.allCases }
        return []
    }

    /// Notifications to clear once something was read in the app: the conversation's, or the post's.
    public static func matches(_ payload: [AnyHashable: Any], conversationId: String? = nil, postId: String? = nil) -> Bool {
        let ids = (payload["ids"] as? [String: Any]) ?? [:]
        if let conversationId, (ids["conversation_id"] as? String) == conversationId { return true }
        if let postId, (ids["post_id"] as? String) == postId { return true }
        return false
    }
}
