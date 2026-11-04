import Foundation
import Security

/// Storage shared by the app and the share extension through the App Group.
public enum AppGroup {
    public static let identifier: String = {
        (Bundle.main.object(forInfoDictionaryKey: "KinframeAppGroup") as? String).flatMap { $0.isEmpty || $0.hasPrefix("$(") ? nil : $0 }
            ?? "group.app.kinframe.family"
    }()

    /// Shared user defaults. Falls back to the standard defaults when the group isn't available (unsigned builds).
    public static var defaults: UserDefaults {
        UserDefaults(suiteName: identifier) ?? .standard
    }

    /// Root of the shared container, or Application Support when the group isn't available.
    public static var container: URL {
        if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier) {
            return url
        }
        let fallback = URL.applicationSupportDirectory.appending(path: "kinframe-group")
        try? FileManager.default.createDirectory(at: fallback, withIntermediateDirectories: true)
        return fallback
    }
}

/// Generic-password Keychain items. Items go to the default access group, which the app and the share extension
/// share through their `keychain-access-groups` entitlement.
public enum Keychain {
    static let service = "app.kinframe.family"

    public static func data(_ account: String) -> Data? {
        var query = base(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    public static func set(_ data: Data?, for account: String) {
        let query = base(account)
        guard let data else {
            SecItemDelete(query as CFDictionary)
            return
        }
        let attrs: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        if SecItemUpdate(query as CFDictionary, attrs as CFDictionary) == errSecItemNotFound {
            SecItemAdd(query.merging(attrs) { $1 } as CFDictionary, nil)
        }
    }

    public static func string(_ account: String) -> String? {
        data(account).flatMap { String(data: $0, encoding: .utf8) }
    }

    public static func set(_ string: String?, for account: String) {
        set(string.map { Data($0.utf8) }, for: account)
    }

    private static func base(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
