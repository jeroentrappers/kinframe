import Foundation

public typealias CalendarSource = Components.Schemas.CalendarSource
public typealias CalendarEvent = Components.Schemas.CalendarEvent
public typealias CalendarCountdown = Components.Schemas.Countdown
public typealias CalendarView = Components.Schemas.CalendarView
public typealias CalendarColor = Components.Schemas.CalendarColor
public typealias CalendarTestResult = Components.Schemas.CalendarTestResult
public typealias EventInvitation = Components.Schemas.EventInvitation
public typealias WeatherDay = Components.Schemas.Weather.DailyPayloadPayload
public typealias CalendarSchedule = Components.Schemas.CalendarSchedule

extension Components.Schemas.CalendarSource: Identifiable {}
extension Components.Schemas.EventInvitation: Identifiable {}
extension Components.Schemas.CalendarEvent: Identifiable {}

/// The frame's calendars (web: lib/calendar.ts, docs/api/README.md §5.8), free of UI so it can be unit tested. Plain
/// dates are "YYYY-MM-DD" strings, like the API and the web.
public enum CalendarLogic {
    // MARK: Palette

    /// The shared 12-colour palette: (light, dark) as 0xRRGGBB.
    public static let palette: [CalendarColor: (light: UInt32, dark: UInt32)] = [
        .coral: (0xE5726A, 0xF0958E), .tangerine: (0xEE8F4F, 0xF4AE7E), .amber: (0xDDA731, 0xE9C26A),
        .olive: (0x9AA63E, 0xBBC575), .mint: (0x4FB28A, 0x82CDAE), .sage: (0x7F9C77, 0xA8C0A1),
        .teal: (0x349E9A, 0x6EC1BD), .sky: (0x4F9FDF, 0x87BFEB), .ocean: (0x4767C8, 0x8197DD),
        .lavender: (0x8E7BD0, 0xB3A6E2), .plum: (0xAC5F9F, 0xCA92C1), .rose: (0xDA6C92, 0xE79AB5),
    ]

    public static let colors: [CalendarColor] = [.coral, .tangerine, .amber, .olive, .mint, .sage, .teal, .sky, .ocean, .lavender, .plum, .rose]

    /// The first palette colour not used yet (the server's rule for new sources), cycling when all are taken.
    public static func nextColor(_ used: [CalendarColor?]) -> CalendarColor {
        if let free = colors.first(where: { c in !used.contains(c) }) { return free }
        return colors[used.count % colors.count]
    }

    // MARK: Plain dates

    static let utc: Foundation.Calendar = {
        var c = Foundation.Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    /// Midnight UTC of a plain date (for formatting it without shifting the day).
    public static func utcDate(_ day: String) -> Date? { parse(day) }

    static func parse(_ day: String) -> Date? {
        let p = day.split(separator: "-").compactMap { Int($0) }
        guard p.count == 3 else { return nil }
        return utc.date(from: DateComponents(year: p[0], month: p[1], day: p[2], hour: 0))
    }

    static func format(_ date: Date) -> String {
        let c = utc.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 1970, c.month ?? 1, c.day ?? 1)
    }

    public static func isPlainDate(_ s: String) -> Bool {
        s.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil && parse(s) != nil
    }

    public static func addDays(_ day: String, _ n: Int) -> String {
        guard let d = parse(day), let r = utc.date(byAdding: .day, value: n, to: d) else { return day }
        return format(r)
    }

    /// Whole days from a to b (b − a).
    public static func daysBetween(_ a: String, _ b: String) -> Int {
        guard let x = parse(a), let y = parse(b) else { return 0 }
        return Int(((y.timeIntervalSince(x)) / 86_400).rounded())
    }

    /// 0 = Monday … 6 = Sunday.
    public static func weekdayIndex(_ day: String) -> Int {
        guard let d = parse(day) else { return 0 }
        return (utc.component(.weekday, from: d) + 5) % 7
    }

    public enum FirstDay: String, Sendable {
        case mon, sun, sat
        var offset: Int { switch self { case .mon: 0; case .sun: 6; case .sat: 5 } }
    }

    public static func startOfWeek(_ day: String, first: FirstDay = .mon) -> String {
        addDays(day, -((weekdayIndex(day) - first.offset + 7) % 7))
    }

    /// ISO 8601 week number.
    public static func isoWeek(_ day: String) -> Int {
        var iso = Foundation.Calendar(identifier: .iso8601)
        iso.timeZone = TimeZone(identifier: "UTC")!
        guard let d = parse(day) else { return 1 }
        return iso.component(.weekOfYear, from: d)
    }

    /// Full weeks covering a month (`month` 1–12): 4 to 6 rows of 7 dates.
    public static func monthGrid(year: Int, month: Int, first: FirstDay = .mon) -> [[String]] {
        let firstOfMonth = String(format: "%04d-%02d-01", year, month)
        guard let start = parse(firstOfMonth), let next = utc.date(byAdding: .month, value: 1, to: start) else { return [] }
        let last = addDays(format(next), -1)
        var weeks: [[String]] = []
        var d = startOfWeek(firstOfMonth, first: first)
        while d <= last {
            weeks.append((0..<7).map { addDays(d, $0) })
            d = addDays(d, 7)
        }
        return weeks
    }

    /// Weekday order for a first day of the week, as `day.*` keys.
    public static func weekdayOrder(_ first: FirstDay = .mon) -> [String] {
        let all = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
        return (0..<7).map { all[(first.offset + $0) % 7] }
    }

    // MARK: Time zones

    /// The calendar date of an instant in a time zone.
    public static func localDate(_ date: Date, _ timeZone: String) -> String {
        var c = Foundation.Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: timeZone) ?? TimeZone(identifier: "UTC")!
        let p = c.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", p.year ?? 1970, p.month ?? 1, p.day ?? 1)
    }

    /// "09:30" (or "9:30 AM") in the frame's time zone.
    public static func localTime(_ date: Date, _ timeZone: String, locale: Locale, clock24: Bool) -> String {
        let f = DateFormatter()
        f.locale = locale
        f.timeZone = TimeZone(identifier: timeZone) ?? TimeZone(identifier: "UTC")
        f.dateFormat = clock24 ? "HH:mm" : "h:mm a"
        return f.string(from: date)
    }

    public static func today(_ timeZone: String, now: Date = Date()) -> String {
        localDate(now, timeZone)
    }

    // MARK: Events

    /// First and last calendar day (inclusive) an event covers in a time zone.
    public static func span(_ ev: CalendarEvent, _ tz: String) -> (String, String) {
        if ev.allDay {
            let start = ev.startDate ?? ev.startAt.map { localDate($0, tz) } ?? "1970-01-01"
            let end = ev.endDate ?? start
            return (start, end < start ? start : end)
        }
        guard let startAt = ev.startAt else { return ("1970-01-01", "1970-01-01") }
        let start = localDate(startAt, tz)
        guard let endAt = ev.endAt, endAt > startAt else { return (start, start) }
        // An event ending exactly at midnight does not touch the next day.
        let end = localDate(endAt.addingTimeInterval(-0.001), tz)
        return (start, end < start ? start : end)
    }

    public static func isMultiDay(_ ev: CalendarEvent, _ tz: String) -> Bool {
        let (a, b) = span(ev, tz)
        return a != b
    }

    /// All-day and multi-day events first (earlier start, then longer), then timed events by start, then title.
    public static func ordered(_ a: CalendarEvent, _ b: CalendarEvent, _ tz: String) -> Bool {
        let topA = a.allDay || isMultiDay(a, tz), topB = b.allDay || isMultiDay(b, tz)
        if topA != topB { return topA }
        let (sa, ea) = span(a, tz), (sb, eb) = span(b, tz)
        if topA {
            if sa != sb { return sa < sb }
            if ea != eb { return ea > eb }
        } else if a.startAt != b.startAt {
            return (a.startAt ?? .distantPast) < (b.startAt ?? .distantPast)
        }
        return a.title.localizedCompare(b.title) == .orderedAscending
    }

    /// Events per local day between from and to (inclusive), each day sorted.
    public static func byDay(_ events: [CalendarEvent], _ tz: String, from: String, to: String) -> [String: [CalendarEvent]] {
        var out: [String: [CalendarEvent]] = [:]
        for ev in events {
            let (s, e) = span(ev, tz)
            var d = s < from ? from : s
            let end = e > to ? to : e
            var guardCount = 0
            while d <= end && guardCount < 400 {
                out[d, default: []].append(ev)
                d = addDays(d, 1)
                guardCount += 1
            }
        }
        return out.mapValues { $0.sorted { ordered($0, $1, tz) } }
    }

    /// Agenda: today up to the end of the window, days with events (today always). Multi-day events show once, on
    /// their first day in the list.
    public static func agenda(_ byDay: [String: [CalendarEvent]], today: String, until: String, tz: String, maxDays: Int = 60) -> [(day: String, events: [CalendarEvent])] {
        var days: [(String, [CalendarEvent])] = []
        var d = today
        while d <= until && days.count < maxDays {
            let events = (byDay[d] ?? []).filter { ev in
                let (s, e) = span(ev, tz)
                return s == e || d == (s > today ? s : today)
            }
            if d == today || !events.isEmpty { days.append((d, events)) }
            d = addDays(d, 1)
        }
        return days.map { (day: $0.0, events: $0.1) }
    }

    /// Whether an event is over at `now`.
    public static func isPast(_ ev: CalendarEvent, _ tz: String, now: Date = Date()) -> Bool {
        if ev.allDay { return span(ev, tz).1 < today(tz, now: now) }
        return (ev.endAt ?? ev.startAt ?? .distantFuture) <= now
    }

    /// Days left until a countdown's day (0 = today), in the frame's time zone.
    public static func daysUntil(allDay: Bool, startAt: Date?, startDate: String?, _ tz: String, now: Date = Date()) -> Int {
        let day = allDay ? (startDate ?? today(tz, now: now)) : startAt.map { localDate($0, tz) } ?? today(tz, now: now)
        return daysBetween(today(tz, now: now), day)
    }

    // MARK: Weather

    public enum WeatherKind: String, Sendable {
        case clear, partly, cloudy, fog, drizzle, rain, snow, showers, storm

        public var symbol: String {
            switch self {
            case .clear: "sun.max.fill"
            case .partly: "cloud.sun.fill"
            case .cloudy: "cloud.fill"
            case .fog: "cloud.fog.fill"
            case .drizzle: "cloud.drizzle.fill"
            case .rain: "cloud.rain.fill"
            case .showers: "cloud.heavyrain.fill"
            case .snow: "cloud.snow.fill"
            case .storm: "cloud.bolt.rain.fill"
            }
        }
    }

    /// A coarse icon for a WMO weather code.
    public static func weatherKind(_ code: Int) -> WeatherKind {
        if code <= 1 { return .clear }
        if code == 2 { return .partly }
        if code == 3 { return .cloudy }
        if code == 45 || code == 48 { return .fog }
        if (51...57).contains(code) { return .drizzle }
        if (61...67).contains(code) || (80...82).contains(code) { return code >= 80 ? .showers : .rain }
        if (71...77).contains(code) || code == 85 || code == 86 { return .snow }
        if code >= 95 { return .storm }
        return .cloudy
    }

    public static func temperature(_ c: Double, unit: String = "c") -> String {
        "\(Int((unit == "f" ? c * 1.8 + 32 : c).rounded()))°"
    }

    // MARK: Sending an event

    public enum FormError: String, Sendable, Equatable {
        case title, start, end, order
    }

    public struct EventForm: Sendable, Equatable {
        public var title = ""
        public var allDay = false
        public var start = Date()
        public var end = Date()
        public var startDate = ""
        public var endDate = ""
        public var location = ""
        public var notes = ""

        public init() {}
    }

    /// Validate the send-event form: the first problem, or the request body (without the photo).
    public static func invitation(_ f: EventForm) -> Result<Components.Schemas.EventInvitationCreate, FormErrorBox> {
        let title = f.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else { return .failure(.init(.title)) }
        let location = f.location.trimmingCharacters(in: .whitespacesAndNewlines)
        let notes = f.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        var body = Components.Schemas.EventInvitationCreate(title: title)
        body.location = location.isEmpty ? nil : location
        body.notes = notes.isEmpty ? nil : notes
        if f.allDay {
            guard isPlainDate(f.startDate) else { return .failure(.init(.start)) }
            let end = f.endDate.isEmpty ? f.startDate : f.endDate
            guard isPlainDate(end) else { return .failure(.init(.end)) }
            guard end >= f.startDate else { return .failure(.init(.order)) }
            body.allDay = true
            body.startDate = f.startDate
            body.endDate = end
            return .success(body)
        }
        guard f.end > f.start else { return .failure(.init(.order)) }
        body.allDay = false
        body.startAt = f.start
        body.endAt = f.end
        return .success(body)
    }

    public struct FormErrorBox: Error, Equatable {
        public var error: FormError
        init(_ e: FormError) { error = e }
    }

    /// A sensible default: next full hour, one hour long.
    public static func defaultEventTimes(now: Date = Date(), calendar: Foundation.Calendar = .current) -> (start: Date, end: Date, date: String) {
        var c = calendar.dateComponents([.year, .month, .day, .hour], from: now)
        c.minute = 0
        let hour = calendar.date(from: c) ?? now
        let start = calendar.date(byAdding: .hour, value: 1, to: hour) ?? now
        let p = calendar.dateComponents([.year, .month, .day], from: start)
        return (start, start.addingTimeInterval(3600), String(format: "%04d-%02d-%02d", p.year ?? 1970, p.month ?? 1, p.day ?? 1))
    }

    /// A plain date for a picked day in the user's calendar.
    public static func plainDate(_ date: Date, calendar: Foundation.Calendar = .current) -> String {
        let p = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", p.year ?? 1970, p.month ?? 1, p.day ?? 1)
    }

    /// The day a plain date stands for, at noon in the user's calendar (for date pickers).
    public static func pickerDate(_ day: String, calendar: Foundation.Calendar = .current) -> Date {
        let p = day.split(separator: "-").compactMap { Int($0) }
        guard p.count == 3 else { return Date() }
        return calendar.date(from: DateComponents(year: p[0], month: p[1], day: p[2], hour: 12)) ?? Date()
    }

    // MARK: Sources

    /// An ICS or CalDAV address: http(s) or webcal with a host.
    public static func isCalendarURL(_ s: String) -> Bool {
        s.trimmingCharacters(in: .whitespaces).range(of: #"^(https?|webcal)://[^\s/]+\S*$"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    /// A new timed-view schedule: 07:00–09:00 on every day.
    public static func newTimedView(id: String = UUID().uuidString.lowercased()) -> CalendarSchedule {
        CalendarSchedule(id: id, from: "07:00", to: "09:00", days: [.mon, .tue, .wed, .thu, .fri, .sat, .sun], enabled: true)
    }
}
