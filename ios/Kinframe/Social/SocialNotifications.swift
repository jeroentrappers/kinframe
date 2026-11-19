import KinframeKit
import UIKit
import UserNotifications

/// Family social notifications (README §6.3): the reminder category with Snooze 1 month and Fewer reminders, the chat
/// category with a reply field, and what those buttons do.
extension PushRegistrar {
    /// Called at launch: the categories the server's payloads name (`kinframe.reminder`) and the chat reply.
    func registerCategories() {
        let snooze = UNNotificationAction(identifier: ReminderAction.snoozeMonth.rawValue, title: ReminderAction.snoozeMonth.title, options: [])
        let fewer = UNNotificationAction(identifier: ReminderAction.fewerReminders.rawValue, title: ReminderAction.fewerReminders.title, options: [])
        let reminder = UNNotificationCategory(identifier: SocialPush.reminderCategory, actions: [snooze, fewer], intentIdentifiers: [], options: [])
        let reply = UNTextInputNotificationAction(identifier: SocialPush.replyAction, title: L("chat.reply"), options: [],
                                                  textInputButtonTitle: L("chat.send"), textInputPlaceholder: L("chat.placeholder"))
        let message = UNNotificationCategory(identifier: SocialPush.messageCategory, actions: [reply], intentIdentifiers: [],
                                             options: [.hiddenPreviewsShowTitle])
        UNUserNotificationCenter.current().setNotificationCategories([reminder, message])
    }

    /// A notification button: true when it was one of ours and nothing needs to open.
    func handleAction(_ identifier: String, payload: [AnyHashable: Any], reply: String?, api: API?) -> Bool {
        if let action = ReminderAction(rawValue: identifier) {
            guard let api else { return true }
            Task {
                _ = try? await api.updateReminderPreferences(action.update)
            }
            return true
        }
        if identifier == SocialPush.replyAction {
            let text = reply?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard let api, let conversation = SocialPush.conversationId(payload), !text.isEmpty else { return true }
            let messageId = ((payload["ids"] as? [String: Any])?["message_id"] as? String)
            Task {
                _ = try? await api.sendMessage(conversation, API.OutgoingMessage(body: text))
                if let messageId { try? await api.markConversationRead(conversation, upTo: messageId) }
            }
            return true
        }
        return false
    }
}
