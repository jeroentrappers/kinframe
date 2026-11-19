import Foundation

// Several accounts in one app (docs/spec/multi-server.md §2.2): what the app shows follows every account, each item
// follows its own account. Pure rules, unit-tested in MultiAccountTests.

extension SocialFeatures {
    /// What any of these accounts has: the tabs show when one of them has the area (each item inside still checks
    /// its own account's features).
    public static func any(_ list: [SocialFeatures]) -> SocialFeatures {
        SocialFeatures(social: list.contains(where: \.social), chats: list.contains(where: \.chats),
                       voiceMessages: list.contains(where: \.voiceMessages), linkPreviews: list.contains(where: \.linkPreviews),
                       engagement: list.contains(where: \.engagement), emailDigest: list.contains(where: \.emailDigest))
    }
}

extension Limits {
    /// Limits that every one of these servers accepts (the smallest of each), for a send that goes to several servers
    /// at once: the composer checks against this, so no server's upload fails over a limit. Empty: the defaults.
    public static func strictest(_ list: [Limits]) -> Limits {
        guard var out = list.first else { return .defaults }
        for l in list.dropFirst() {
            out.maxItemsPerSend = min(out.maxItemsPerSend, l.maxItemsPerSend)
            out.maxVideoSeconds = min(out.maxVideoSeconds, l.maxVideoSeconds)
            out.maxUploadBytes = min(out.maxUploadBytes, l.maxUploadBytes)
            out.trashRetentionDays = [out.trashRetentionDays, l.trashRetentionDays].compactMap { $0 }.min()
            out.maxFeedsPerUser = min(out.maxFeedsPerUser, l.maxFeedsPerUser)
            out.maxSubscriptionsPerFeed = min(out.maxSubscriptionsPerFeed, l.maxSubscriptionsPerFeed)
            out.maxFeedBackfillItems = min(out.maxFeedBackfillItems, l.maxFeedBackfillItems)
            out.maxBirthdaysPerFeed = min(out.maxBirthdaysPerFeed, l.maxBirthdaysPerFeed)
            out.maxPhotoSourcesPerTarget = min(out.maxPhotoSourcesPerTarget, l.maxPhotoSourcesPerTarget)
            out.maxPhotoSourcesPerUser = min(out.maxPhotoSourcesPerUser, l.maxPhotoSourcesPerUser)
            out.maxPhotoSourceItems = min(out.maxPhotoSourceItems, l.maxPhotoSourceItems)
            out.maxGoogleImportItems = min(out.maxGoogleImportItems, l.maxGoogleImportItems)
            out.maxEmailAddressesPerTarget = min(out.maxEmailAddressesPerTarget, l.maxEmailAddressesPerTarget)
            out.maxEmailSendersPerAddress = min(out.maxEmailSendersPerAddress, l.maxEmailSendersPerAddress)
            out.maxTelegramChatsPerTarget = min(out.maxTelegramChatsPerTarget, l.maxTelegramChatsPerTarget)
            out.maxCommentChars = min(out.maxCommentChars, l.maxCommentChars)
            out.maxMessageChars = min(out.maxMessageChars, l.maxMessageChars)
            out.maxMentions = min(out.maxMentions, l.maxMentions)
            out.maxMessageItems = min(out.maxMessageItems, l.maxMessageItems)
            out.maxGroupMembers = min(out.maxGroupMembers, l.maxGroupMembers)
            out.maxCirclesPerFeed = min(out.maxCirclesPerFeed, l.maxCirclesPerFeed)
            out.maxVoiceSeconds = min(out.maxVoiceSeconds, l.maxVoiceSeconds)
            out.messageEditHours = min(out.messageEditHours, l.messageEditHours)
        }
        return out
    }
}

/// The limits the composer checks a send against: those of the servers it goes to; before any recipient is chosen,
/// those of every server it could go to.
public enum ComposeLimits {
    /// `servers`: each server's limits and whether one of its recipients is chosen.
    public static func effective(_ servers: [(limits: Limits, chosen: Bool)]) -> Limits {
        let chosen = servers.filter(\.chosen).map(\.limits)
        return Limits.strictest(chosen.isEmpty ? servers.map(\.limits) : chosen)
    }
}

/// A frame asked for photos (`frame_request`) or is getting quiet (`reminder_quiet`) and the request reached you
/// through a family, not as a member of the frame (README §5.20, web `familiesToPreselect`).
public enum FrameRequestTargets {
    public enum Target: Equatable, Sendable {
        /// You're on the frame and may send to it.
        case frame(String)
        /// Post to these families: their posts reach the frame.
        case families([String])
        /// Nothing you can send to reaches it.
        case none
    }

    /// The families to preselect: those reaching the frame (`PhotoRequest.feed_ids`, or the families with an active
    /// subscription to it) that you can post to, in your order.
    public static func familiesToPreselect(reaching: [String], postable: [String]) -> [String] {
        let reach = Set(reaching)
        return postable.filter(reach.contains)
    }

    /// Where "Send photos" goes for `frameId`: the frame when you can send to it, else the families reaching it.
    public static func target(frameId: String, sendableFrames: [String], reaching: [String], postable: [String]) -> Target {
        if sendableFrames.contains(frameId) { return .frame(frameId) }
        let families = familiesToPreselect(reaching: reaching, postable: postable)
        return families.isEmpty ? .none : .families(families)
    }
}
