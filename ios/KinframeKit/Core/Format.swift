import Foundation

/// Pure formatting helpers, mirroring web/src/lib/format.ts. Unit-tested in FormatTests.
public enum Format {
    public static let codeLength = 8
    public static let captionMax = 280

    /// Keep only ASCII digits, at most 8. Accepts pasted text like "Code: 4821-3907".
    public static func normalizeCode(_ input: String) -> String {
        String(input.unicodeScalars.filter { $0.value >= 48 && $0.value <= 57 }.prefix(codeLength).map(Character.init))
    }

    /// "48213907" → "4821 3907"; partial input stays readable ("482" → "482", "48213" → "4821 3").
    public static func formatCode(_ input: String) -> String {
        let digits = normalizeCode(input)
        guard digits.count > 4 else { return digits }
        return "\(digits.prefix(4)) \(digits.dropFirst(4))"
    }

    public static func isCompleteCode(_ input: String) -> Bool {
        normalizeCode(input).count == codeLength
    }

    public static func bytes(_ bytes: Int64, locale: Locale = .current) -> String {
        guard bytes >= 0 else { return "–" }
        let units = ["B", "KB", "MB", "GB", "TB"]
        var value = Double(bytes)
        var unit = 0
        while value >= 1024, unit < units.count - 1 {
            value /= 1024
            unit += 1
        }
        let digits = unit == 0 || value >= 100 ? 0 : 1
        let number = value.formatted(.number.precision(.fractionLength(0...digits)).locale(locale))
        return "\(number) \(units[unit])"
    }

    /// 75.4 → "1:15", 3725 → "1:02:05".
    public static func duration(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let s = Int(seconds.rounded())
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        let mm = h > 0 ? String(format: "%02d", m) : String(m)
        return (h > 0 ? "\(h):" : "") + mm + ":" + String(format: "%02d", sec)
    }

    /// 30 → "30 s", 300 → "5 min", 3600 → "1 h".
    public static func interval(_ seconds: Int) -> String {
        if seconds < 60 { return "\(seconds) s" }
        if seconds < 3600 { return "\(Int((Double(seconds) / 60).rounded())) min" }
        return "\(Int((Double(seconds) / 3600).rounded())) h"
    }

    /// "just now", "5 minutes ago", "yesterday", … in the given locale.
    public static func relative(_ date: Date?, locale: Locale = .current, now: Date = Date()) -> String {
        guard let date else { return "" }
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = locale
        formatter.dateTimeStyle = .named
        if abs(date.timeIntervalSince(now)) < 45 {
            return formatter.localizedString(fromTimeInterval: 0)
        }
        return formatter.localizedString(for: date, relativeTo: now)
    }

    public static func dateTime(_ date: Date?, locale: Locale = .current) -> String {
        guard let date else { return "" }
        return date.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(locale))
    }

    public enum DayBucket: Equatable, Hashable, Sendable {
        case today, yesterday
        case day(Date)
    }

    /// Group label for history: today, yesterday or the start of that day.
    public static func dayBucket(_ date: Date, now: Date = Date(), calendar: Calendar = .current) -> DayBucket {
        let start = calendar.startOfDay(for: date)
        let days = calendar.dateComponents([.day], from: start, to: calendar.startOfDay(for: now)).day ?? 0
        switch days {
        case 0: return .today
        case 1: return .yesterday
        default: return .day(start)
        }
    }

    /// Characters left in a caption. Swift counts grapheme clusters, so an emoji counts as one.
    public static func captionRemaining(_ caption: String, max: Int = captionMax) -> Int {
        max - caption.count
    }

    public static func initials(_ name: String?) -> String {
        let parts = (name ?? "").split(whereSeparator: \.isWhitespace)
        guard let first = parts.first?.first else { return "?" }
        let last = parts.count > 1 ? parts.last?.first.map(String.init) ?? "" : ""
        return (String(first) + last).uppercased()
    }

    /// Wi-Fi RSSI (dBm) → 0…4 bars.
    public static func signalBars(_ rssi: Int?) -> Int {
        guard let rssi else { return 0 }
        if rssi >= -55 { return 4 }
        if rssi >= -67 { return 3 }
        if rssi >= -75 { return 2 }
        if rssi >= -85 { return 1 }
        return 0
    }

    public static func uptime(_ seconds: Int) -> String {
        let d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60
        if d > 0 { return "\(d)d \(h)h" }
        if h > 0 { return "\(h)h \(m)m" }
        return "\(m)m"
    }
}
