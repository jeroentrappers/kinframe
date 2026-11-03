import Foundation

public typealias Album = Components.Schemas.Album

extension Components.Schemas.Album: Identifiable {}

/// Albums, their yearly schedules and "On this day" (web: lib/parity.ts), free of UI so it can be unit tested.
/// Month-days are "MM-DD" strings, like the API.
public enum AlbumLogic {
    public static let nameMax = 60

    /// Put `items` in the order of `ids` (an album's placement_ids); unknown items go last, in their order.
    public static func ordered<T: Identifiable>(_ items: [T], by ids: [String]) -> [T] where T.ID == String {
        let pos = Dictionary(ids.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
        return items.enumerated()
            .sorted { (pos[$0.element.id] ?? Int.max, $0.offset) < (pos[$1.element.id] ?? Int.max, $1.offset) }
            .map(\.element)
    }

    /// The album's new order: the arranged (shown) ones first, then members this view doesn't show (hidden ones).
    public static func arrangedIds(shown: [String], album: [String]) -> [String] {
        let set = Set(shown)
        return shown + album.filter { !set.contains($0) }
    }

    /// Days in a month (1–12), counting 29 February so a schedule can include it.
    public static func daysInMonth(_ month: Int) -> Int {
        switch month {
        case 2: 29
        case 4, 6, 9, 11: 30
        default: 31
        }
    }

    /// The day, kept valid when the month changes (31 → 30 in April).
    public static func clampDay(_ day: Int, month: Int) -> Int {
        min(max(1, day), daysInMonth(month))
    }

    /// "MM-DD" for a month (1–12) and day.
    public static func monthDay(_ month: Int, _ day: Int) -> String {
        String(format: "%02d-%02d", month, day)
    }

    /// Parse "MM-DD", or nil when it isn't a real day.
    public static func parseMonthDay(_ md: String?) -> (month: Int, day: Int)? {
        guard let md, md.range(of: #"^\d{2}-\d{2}$"#, options: .regularExpression) != nil else { return nil }
        let p = md.split(separator: "-").compactMap { Int($0) }
        guard p.count == 2, (1...12).contains(p[0]), p[1] >= 1, p[1] <= daysInMonth(p[0]) else { return nil }
        return (p[0], p[1])
    }

    /// Today's "MM-DD" in the user's calendar (for "On this day").
    public static func todayMonthDay(_ now: Date = Date(), calendar: Foundation.Calendar = .current) -> String {
        let c = calendar.dateComponents([.month, .day], from: now)
        return monthDay(c.month ?? 1, c.day ?? 1)
    }

    /// Whether a yearly schedule covers a day; ranges may wrap the new year (12-20 → 01-06).
    public static func covers(from: String?, to: String?, today: String) -> Bool {
        guard let from, let to, parseMonthDay(from) != nil, parseMonthDay(to) != nil else { return false }
        return from <= to ? (today >= from && today <= to) : (today >= from || today <= to)
    }

    public static func covers(_ schedule: Album.SchedulePayload?, now: Date = Date()) -> Bool {
        covers(from: schedule?.fromMonthDay, to: schedule?.toMonthDay, today: todayMonthDay(now))
    }

    /// The range runs over New Year.
    public static func wraps(from: String, to: String) -> Bool { from > to }

    /// "1 December" in the given locale.
    public static func format(_ md: String, locale: Locale) -> String {
        guard let p = parseMonthDay(md) else { return md }
        var cal = Foundation.Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        guard let date = cal.date(from: DateComponents(year: 2024, month: p.month, day: p.day, hour: 12)) else { return md }
        let f = DateFormatter()
        f.locale = locale
        f.timeZone = cal.timeZone
        f.setLocalizedDateFormatFromTemplate("dMMMM")
        return f.string(from: date)
    }

    /// Month names for the schedule pickers, January first.
    public static func monthNames(locale: Locale) -> [String] {
        let f = DateFormatter()
        f.locale = locale
        return f.standaloneMonthSymbols ?? f.monthSymbols
    }

    /// Whole years between a capture date and now (for "3 years ago").
    public static func yearsAgo(_ date: Date?, now: Date = Date(), calendar: Foundation.Calendar = .current) -> Int {
        guard let date else { return 0 }
        var utc = Foundation.Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        return calendar.component(.year, from: now) - utc.component(.year, from: date)
    }

    /// A trimmed album name, or nil when empty.
    public static func cleanName(_ s: String) -> String? {
        let t = String(s.trimmingCharacters(in: .whitespacesAndNewlines).prefix(nameMax))
        return t.isEmpty ? nil : t
    }

    /// The languages a frame can show (web: LOCALE_IDS / NATIVE_NAMES), sorted by their own name.
    public static let frameLanguages: [(id: String, name: String)] = [
        ("en", "English"), ("nl", "Nederlands"), ("ar", "العربية"), ("bg", "Български"), ("cs", "Čeština"), ("da", "Dansk"),
        ("de", "Deutsch"), ("el", "Ελληνικά"), ("es", "Español"), ("fi", "Suomi"), ("fr", "Français"), ("he", "עברית"),
        ("hi", "हिन्दी"), ("hr", "Hrvatski"), ("hu", "Magyar"), ("id", "Bahasa Indonesia"), ("it", "Italiano"), ("ja", "日本語"),
        ("ko", "한국어"), ("lt", "Lietuvių"), ("lv", "Latviešu"), ("nb", "Norsk bokmål"), ("pl", "Polski"), ("pt", "Português"),
        ("ro", "Română"), ("ru", "Русский"), ("sk", "Slovenčina"), ("sl", "Slovenščina"), ("sr", "Српски"), ("sv", "Svenska"),
        ("th", "ไทย"), ("tr", "Türkçe"), ("uk", "Українська"), ("vi", "Tiếng Việt"), ("zh-CN", "简体中文"), ("zh-TW", "繁體中文"),
    ].sorted { $0.1.compare($1.1, locale: Locale(identifier: "und")) == .orderedAscending }
}

extension JSONValue {
    /// The string, when this is one.
    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }
}
