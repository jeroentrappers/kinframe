import Foundation

/// A kinframe link shared to the share extension ("Share → kinframe" on a link in Safari or a chat app). The
/// extension can't open the app, so it leaves the link in the App Group and the app opens it the next time it comes
/// to the front. Links of servers other than kinframe.appmire.be reach the app this way (they can't be Universal
/// Links, see ios/README.md).
public enum PendingLink {
    static let key = "share.pendingLink"
    static let atKey = "share.pendingLinkAt"
    /// Older than this, the person has moved on.
    static let lifetime: TimeInterval = 30 * 60

    public static func store(_ url: URL, now: Date = Date(), _ defaults: UserDefaults = AppGroup.defaults) {
        defaults.set(url.absoluteString, forKey: key)
        defaults.set(now.timeIntervalSince1970, forKey: atKey)
    }

    /// The waiting link (once), unless it's too old or not a kinframe link.
    public static func take(now: Date = Date(), _ defaults: UserDefaults = AppGroup.defaults) -> URL? {
        guard let s = defaults.string(forKey: key) else { return nil }
        let at = defaults.double(forKey: atKey)
        defaults.removeObject(forKey: key)
        defaults.removeObject(forKey: atKey)
        guard now.timeIntervalSince1970 - at < lifetime, let url = URL(string: s), DeepLink.parse(url) != nil else { return nil }
        return url
    }
}
