import Foundation
import OpenAPIRuntime

/// Email to frame (tag `email-inbox`) and Telegram (tag `telegram`) in openapi.yaml, as one-line calls for the screens.
/// Errors arrive as `APIError` like every `call`; `EmailText.problem` and `TelegramText.problem` turn the expected
/// problems into text.
extension API {
    // MARK: Inbox addresses

    public func emailAddresses(_ target: ChannelTarget) async throws -> [EmailAddress] {
        switch target {
        case .frame(let id): try await call { try await $0.listFrameEmailAddresses(path: .init(frameId: id)).ok.body.json }
        case .feed(let id): try await call { try await $0.listFeedEmailAddresses(path: .init(feedId: id)).ok.body.json }
        }
    }

    public func createEmailAddress(_ target: ChannelTarget, _ draft: EmailAddressDraft) async throws -> EmailAddress {
        let key = UUID().uuidString.lowercased()
        let body = draft.body
        switch target {
        case .frame(let id):
            return try await call {
                try await $0.createFrameEmailAddress(path: .init(frameId: id), headers: .init(idempotencyKey: key), body: .json(body)).created.body.json
            }
        case .feed(let id):
            return try await call {
                try await $0.createFeedEmailAddress(path: .init(feedId: id), headers: .init(idempotencyKey: key), body: .json(body)).created.body.json
            }
        }
    }

    public func emailAddress(_ id: String) async throws -> EmailAddress {
        try await call { try await $0.getEmailAddress(path: .init(addressId: id)).ok.body.json }
    }

    public func updateEmailAddress(_ id: String, _ change: EmailAddressChange) async throws -> EmailAddress {
        let body = change.body
        return try await call { try await $0.updateEmailAddress(path: .init(addressId: id), body: .json(body)).ok.body.json }
    }

    /// A new random part; the old address stops at once.
    public func regenerateEmailAddress(_ id: String) async throws -> EmailAddress {
        try await call { try await $0.regenerateEmailAddress(path: .init(addressId: id)).ok.body.json }
    }

    public func deleteEmailAddress(_ id: String) async throws {
        _ = try await call { try await $0.deleteEmailAddress(path: .init(addressId: id)).noContent }
    }

    // MARK: Senders

    public func emailSenders(_ addressId: String) async throws -> [EmailSender] {
        try await call { try await $0.listEmailSenders(path: .init(addressId: addressId)).ok.body.json }
    }

    /// Allow (or block) an address. A pending sender with it is approved (or rejected and blocked).
    public func addEmailSender(_ addressId: String, email: String, name: String, blocked: Bool = false) async throws -> EmailSender {
        let body = EmailSenderInput.body(email: email, name: name, blocked: blocked)
        return try await call { try await $0.addEmailSender(path: .init(addressId: addressId), body: .json(body)).created.body.json }
    }

    /// Rename (an empty name clears it: an explicit null), block or unblock.
    public func updateEmailSender(_ addressId: String, senderId: String, name: String? = nil, blocked: Bool? = nil) async throws -> EmailSender {
        var patch: [String: JSONValue] = [:]
        if let name {
            let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
            patch["display_name"] = n.isEmpty ? .null : .string(String(n.prefix(40)))
        }
        if let blocked { patch["blocked"] = .bool(blocked) }
        let payload = try JSONEncoder().encode(JSONValue.object(patch))
        let data = try await raw("PATCH", "v1/email-addresses/\(addressId)/senders/\(senderId)", body: payload)
        return try OutboxRunner.decoder.decode(EmailSender.self, from: data)
    }

    public func removeEmailSender(_ addressId: String, senderId: String) async throws {
        _ = try await call { try await $0.removeEmailSender(path: .init(addressId: addressId, senderId: senderId)).noContent }
    }

    public func emailPendingSenders(_ addressId: String) async throws -> [EmailPendingSender] {
        try await call { try await $0.listEmailPendingSenders(path: .init(addressId: addressId)).ok.body.json }
    }

    /// Allow a pending sender and deliver their authenticated held mail (`409 already_decided` when someone was first).
    public func approveEmailSender(_ addressId: String, senderId: String, deliver: Bool = true) async throws -> EmailSender {
        try await call {
            try await $0.approveEmailSender(path: .init(addressId: addressId, senderId: senderId),
                                            body: .json(.init(deliver: deliver))).ok.body.json
        }
    }

    /// Delete their held mail silently; `block` drops their later mail too.
    public func rejectEmailSender(_ addressId: String, senderId: String, block: Bool) async throws {
        _ = try await call {
            try await $0.rejectEmailSender(path: .init(addressId: addressId, senderId: senderId), body: .json(.init(block: block))).noContent
        }
    }

    // MARK: Held and recent mail

    public func emailHeldMessage(_ addressId: String, messageId: String) async throws -> EmailHeldMessage {
        try await call { try await $0.getEmailHeldMessage(path: .init(addressId: addressId, messageId: messageId)).ok.body.json }
    }

    public func deleteEmailHeldMessage(_ addressId: String, messageId: String) async throws {
        _ = try await call { try await $0.deleteEmailHeldMessage(path: .init(addressId: addressId, messageId: messageId)).noContent }
    }

    /// Deliver this one mail (also unverified mail; the sender is not allowed by it).
    public func deliverEmailHeldMessage(_ addressId: String, messageId: String) async throws -> EmailMessage {
        try await call { try await $0.deliverEmailHeldMessage(path: .init(addressId: addressId, messageId: messageId)).ok.body.json }
    }

    /// Recent mail (30 days), newest first.
    public func emailMessages(_ addressId: String, cursor: String? = nil, limit: Int = 30) async throws -> EmailMessagePage {
        try await call {
            try await $0.listEmailMessages(path: .init(addressId: addressId), query: .init(cursor: cursor, limit: limit)).ok.body.json
        }
    }

    // MARK: Telegram chats

    public func telegramChats(_ target: ChannelTarget) async throws -> [TelegramChat] {
        switch target {
        case .frame(let id): try await call { try await $0.listFrameTelegramChats(path: .init(frameId: id)).ok.body.json }
        case .feed(let id): try await call { try await $0.listFeedTelegramChats(path: .init(feedId: id)).ok.body.json }
        }
    }

    /// A one-time connect code: `group` (`?startgroup=`) or `private` (`?start=`); `linkAccount` for "Send from my
    /// Telegram". The `url` is only in this response.
    public func createTelegramCode(_ target: ChannelTarget, kind: TelegramKind, linkAccount: Bool = false) async throws -> TelegramCode {
        let key = UUID().uuidString.lowercased()
        let body = Components.Schemas.TelegramConnectCodeCreate(chatKind: kind.rawValue, linkAccount: linkAccount ? true : nil)
        switch target {
        case .frame(let id):
            return try await call {
                try await $0.createFrameTelegramCode(path: .init(frameId: id), headers: .init(idempotencyKey: key), body: .json(body)).created.body.json
            }
        case .feed(let id):
            return try await call {
                try await $0.createFeedTelegramCode(path: .init(feedId: id), headers: .init(idempotencyKey: key), body: .json(body)).created.body.json
            }
        }
    }

    public func telegramCode(_ id: String) async throws -> TelegramCode {
        try await call { try await $0.getTelegramCode(path: .init(codeId: id)).ok.body.json }
    }

    public func revokeTelegramCode(_ id: String) async throws {
        _ = try await call { try await $0.revokeTelegramCode(path: .init(codeId: id)).noContent }
    }

    public func telegramChat(_ id: String) async throws -> TelegramChat {
        try await call { try await $0.getTelegramChat(path: .init(chatId: id)).ok.body.json }
    }

    public func updateTelegramChat(_ id: String, _ change: TelegramChatChange) async throws -> TelegramChat {
        let payload = try JSONEncoder().encode(JSONValue.object(change.patch))
        let data = try await raw("PATCH", "v1/telegram-chats/\(id)", body: payload)
        return try OutboxRunner.decoder.decode(TelegramChat.self, from: data)
    }

    /// The bot says goodbye and leaves; photos already sent stay.
    public func disconnectTelegramChat(_ id: String) async throws {
        _ = try await call { try await $0.disconnectTelegramChat(path: .init(chatId: id)).noContent }
    }

    public func telegramContributors(_ chatId: String) async throws -> [TelegramContributor] {
        try await call { try await $0.listTelegramContributors(path: .init(chatId: chatId)).ok.body.json }
    }

    public func setTelegramContributorBlocked(_ chatId: String, contributorId: String, _ blocked: Bool) async throws -> TelegramContributor {
        try await call {
            try await $0.updateTelegramContributor(path: .init(chatId: chatId, contributorId: contributorId), body: .json(.init(blocked: blocked))).ok.body.json
        }
    }

    // MARK: My Telegram

    /// The linked Telegram account, nil when none is linked (`404`).
    public func telegramLink() async throws -> TelegramLink? {
        do {
            return try await call { try await $0.getTelegramLink().ok.body.json }
        } catch let e as APIError where e.status == 404 {
            return nil
        }
    }

    /// "Link my Telegram": a code in `https://t.me/<bot>?start=<code>`.
    public func createTelegramLinkCode() async throws -> TelegramCode {
        let key = UUID().uuidString.lowercased()
        return try await call { try await $0.createTelegramLinkCode(headers: .init(idempotencyKey: key)).created.body.json }
    }

    public func unlinkTelegram() async throws {
        _ = try await call { try await $0.unlinkTelegram().noContent }
    }
}
