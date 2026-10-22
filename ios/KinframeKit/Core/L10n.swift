import Foundation
import os

/// The app's language: the system's, or one picked in Settings (shared with the share extension).
public enum AppLanguage: String, CaseIterable, Sendable, Identifiable {
    case system, en, nl
    public var id: String { rawValue }

    /// The language's own name, as language pickers show it.
    public var nativeName: String {
        switch self {
        case .system: L("ios.settings.languageSystem")
        case .en: "English"
        case .nl: "Nederlands"
        }
    }
}

/// Localized strings from Localizable.xcstrings (keys mirror web/src/lib/i18n). Placeholders use `{name}`, plurals
/// use `.one` / `.other` keys, exactly like the web catalogs, so wording can be shared.
public enum L10n {
    private static let supported = ["en", "nl"]
    private static let kit = Bundle(for: BundleToken.self)
    private static let state = OSAllocatedUnfairLock(initialState: (override: AppLanguage.system, bundle: Bundle?.none, code: "en"))

    private static let languageKey = "language"

    /// Load the stored language choice. Call once at launch.
    public static func bootstrap() {
        let stored = AppGroup.defaults.string(forKey: languageKey).flatMap(AppLanguage.init(rawValue:)) ?? .system
        apply(stored)
    }

    public static var language: AppLanguage {
        state.withLock { $0.override }
    }

    public static func setLanguage(_ language: AppLanguage) {
        AppGroup.defaults.set(language.rawValue, forKey: languageKey)
        apply(language)
    }

    /// The effective language code, "en" or "nl".
    public static var code: String {
        state.withLock { $0.code }
    }

    public static var locale: Locale {
        language == .system ? .current : Locale(identifier: code)
    }

    private static func apply(_ language: AppLanguage) {
        let code: String
        switch language {
        case .system:
            code = Bundle.preferredLocalizations(from: supported, forPreferences: Locale.preferredLanguages).first ?? "en"
        case .en, .nl:
            code = language.rawValue
        }
        let bundle = kit.path(forResource: code, ofType: "lproj").flatMap(Bundle.init(path:))
        state.withLock { $0 = (language, bundle, code) }
    }

    static func lookup(_ key: String) -> String {
        let bundle = state.withLock { $0.bundle } ?? kit
        let value = bundle.localizedString(forKey: key, value: "\u{1}", table: nil)
        if value != "\u{1}" { return value }
        // Fall back to English, then the key itself.
        if let en = kit.path(forResource: "en", ofType: "lproj").flatMap(Bundle.init(path:)) {
            return en.localizedString(forKey: key, value: key, table: nil)
        }
        return key
    }

    static func fill(_ template: String, _ args: [String: CustomStringConvertible]) -> String {
        var out = template
        for (name, value) in args {
            out = out.replacingOccurrences(of: "{\(name)}", with: value.description)
        }
        return out
    }
}

private final class BundleToken {}

/// Localized string for `key`, with `{name}` placeholders filled from `args`.
public func L(_ key: String, _ args: [String: CustomStringConvertible] = [:]) -> String {
    L10n.fill(L10n.lookup(key), args)
}

/// Plural form: looks up `key.one` or `key.other` and fills `{count}` plus `args`. English and Dutch share these rules.
public func Lp(_ key: String, count: Int, _ args: [String: CustomStringConvertible] = [:]) -> String {
    var all = args
    all["count"] = count
    return L10n.fill(L10n.lookup(count == 1 ? "\(key).one" : "\(key).other"), all)
}
