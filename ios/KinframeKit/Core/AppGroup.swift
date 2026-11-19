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
            #if DEBUG
            // Unsigned debug builds on the Mac get the path but may not write there.
            if FileManager.default.isWritableFile(atPath: url.path) { return url }
            #else
            return url
            #endif
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

    #if DEBUG
    /// Unsigned debug builds (Mac Catalyst screenshots) can't use the keychain: keep items in memory instead.
    nonisolated(unsafe) private static var memory: [String: Data] = [:]
    #endif

    public static func data(_ account: String) -> Data? {
        var query = base(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        #if DEBUG
        if status != errSecSuccess, let kept = memory[account] { return kept }
        #endif
        guard status == errSecSuccess else { return nil }
        return out as? Data
    }

    public static func set(_ data: Data?, for account: String) {
        let query = base(account)
        guard let data else {
            #if DEBUG
            memory[account] = nil
            #endif
            SecItemDelete(query as CFDictionary)
            return
        }
        let attrs: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        var status = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(attrs) { $1 } as CFDictionary, nil)
        }
        #if DEBUG
        if status != errSecSuccess { memory[account] = data }
        #endif
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
