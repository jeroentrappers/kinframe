import Foundation

// Birthdays in feeds (docs/spec/birthdays.md, docs/api/README.md §5.13): short names for the generated schemas and
// the pure rules the screens share (dates and the 29 February rule, sorting, ages, who may add, suggest, edit and see
// the year, greetings, push tags). Unit-tested in BirthdayTests.

public typealias BirthdayPerson = Components.Schemas.BirthdayPerson
public typealias BirthdayStatus = Components.Schemas.BirthdayStatus
public typealias FeedBirthdayGreeting = Components.Schemas.FeedBirthdayGreeting

extension Components.Schemas.BirthdayPerson: Identifiable {}
extension Components.Schemas.FeedBirthdayGreeting: Identifiable {}

// MARK: Dates

/// A calendar day without a time or zone ("2027-03-12"), as `next_birthday` and greeting dates are sent.
public struct LocalDate: Hashable, Comparable, Sendable, CustomStringConvertible {
    public var year: Int
    public var month: Int
    public var day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// "2027-03-12"; nil for anything else.
    public init?(iso: String) {
        let parts = iso.prefix(10).split(separator: "-")
        guard parts.count == 3, let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), d >= 1, d <= BirthdayDates.daysIn(month: m, year: y) else { return nil }
        self.init(year: y, month: m, day: d)
    }

    /// Today on this device's calendar (the zone the morning notification also uses, `User.timezone`).
    public static func today(in zone: TimeZone = .current, now: Date = Date()) -> LocalDate {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let c = cal.dateComponents([.year, .month, .day], from: now)
        return LocalDate(year: c.year ?? 2000, month: c.month ?? 1, day: c.day ?? 1)
    }

    public var iso: String { String(format: "%04d-%02d-%02d", year, month, day) }
    public var description: String { iso }

    /// Days since 1970-01-01 (proleptic Gregorian), for day differences without a calendar or zone.
    public var ordinal: Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    public func days(until other: LocalDate) -> Int { other.ordinal - ordinal }

    public static func < (a: LocalDate, b: LocalDate) -> Bool {
        (a.year, a.month, a.day) < (b.year, b.month, b.day)
    }
}

public enum BirthdayDates {
    public static let minYear = 1900

    public static func isLeap(_ year: Int) -> Bool {
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    }

    /// Days of a month; February has 29 without a year (a birthday on 29 February is fine).
    public static func daysIn(month: Int, year: Int?) -> Int {
        switch month {
        case 2: year.map { isLeap($0) ? 29 : 28 } ?? 29
        case 4, 6, 9, 11: 30
        default: 31
        }
    }

    public enum Problem: Equatable, Sendable {
        /// 30 February, 31 April, or 29 February with a year that isn't a leap year (field `day`).
        case invalidDay
        /// Before 1900 or after this year (field `birth_year`).
        case invalidYear
    }

    /// The rules the server checks (`422 validation_failed`), so the form can say so before sending.
    public static func problem(day: Int, month: Int, year: Int?, currentYear: Int) -> Problem? {
        if let year, year < minYear || year > currentYear { return .invalidYear }
        guard (1...12).contains(month), day >= 1, day <= daysIn(month: month, year: year) else { return .invalidDay }
        return nil
    }

    /// The birthday in a given year: 29 February falls on 28 February in years that aren't leap years.
    public static func occurrence(day: Int, month: Int, in year: Int) -> LocalDate {
        if month == 2, day == 29, !isLeap(year) { return LocalDate(year: year, month: 2, day: 28) }
        return LocalDate(year: year, month: month, day: day)
    }

    /// The next birthday on or after `today`. With a year of birth, the date of birth itself is not a birthday: the
    /// first one is a year later.
    public static func next(day: Int, month: Int, birthYear: Int?, from today: LocalDate) -> LocalDate {
        var year = today.year
        if occurrence(day: day, month: month, in: year) < today { year += 1 }
        if let birthYear, year <= birthYear { year = birthYear + 1 }
        return occurrence(day: day, month: month, in: year)
    }

    /// The age on an occurrence; nil without a year.
    public static func age(birthYear: Int?, on date: LocalDate) -> Int? {
        birthYear.map { date.year - $0 }
    }
}

// MARK: The list

public extension Components.Schemas.BirthdayPerson {
    /// The next birthday, worked out on the device (the server's `next_birthday` uses `User.timezone`, which may
    /// lag behind a trip).
    func next(from today: LocalDate) -> LocalDate {
        BirthdayDates.next(day: day, month: month, birthYear: birthYear, from: today)
    }

    /// The age on the next birthday, when the year is visible to the caller (the server leaves it null otherwise).
    func nextAge(from today: LocalDate) -> Int? {
        BirthdayDates.age(birthYear: birthYear, on: next(from: today))
    }

    func isToday(_ today: LocalDate) -> Bool { next(from: today) == today }

    /// `pending` or `approved`: counts toward the limit and the uniqueness rule, unlike a declined suggestion.
    var isLive: Bool { status == .pending || status == .approved }
}

public enum BirthdayList {
    /// By the next birthday, nearest first (today first); then by name.
    public static func sorted(_ people: [BirthdayPerson], today: LocalDate) -> [BirthdayPerson] {
        people.sorted { a, b in
            let na = a.next(from: today), nb = b.next(from: today)
            if na != nb { return na < nb }
            let c = a.displayName.localizedCaseInsensitiveCompare(b.displayName)
            return c != .orderedSame ? c == .orderedAscending : a.id < b.id
        }
    }

    /// The screen's three groups: suggestions waiting, the list, and declined suggestions (kept 30 days).
    public struct Sections: Equatable, Sendable {
        public var pending: [BirthdayPerson]
        public var approved: [BirthdayPerson]
        public var rejected: [BirthdayPerson]
    }

    public static func sections(_ people: [BirthdayPerson], today: LocalDate) -> Sections {
        let all = sorted(people, today: today)
        return Sections(pending: all.filter { $0.status == .pending },
                        approved: all.filter { $0.status == .approved },
                        rejected: all.filter { $0.status == .rejected })
    }

    /// "today 🎈", "tomorrow", "in 5 days".
    public static func when(_ next: LocalDate, today: LocalDate) -> String {
        let n = today.days(until: next)
        switch n {
        case ...0: return L("ios.bday.today")
        case 1: return L("ios.bday.tomorrow")
        default: return Lp("ios.bday.inDays", count: n)
        }
    }

    /// "12 March" or "12 March 1946" in the app's language. 29 February always reads as 29 February.
    public static func dateText(day: Int, month: Int, year: Int?, locale: Locale = L10n.locale) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let shownYear = year ?? 2000
        guard let date = cal.date(from: DateComponents(year: shownYear, month: month, day: day)) else { return "\(day)/\(month)" }
        let f = DateFormatter()
        f.calendar = cal
        f.timeZone = cal.timeZone
        f.locale = locale
        f.setLocalizedDateFormatFromTemplate(year == nil ? "dMMMM" : "dMMMMy")
        return f.string(from: date)
    }

    /// The line under a name: "12 March · in 5 days · turns 80".
    public static func subtitle(_ p: BirthdayPerson, today: LocalDate, locale: Locale = L10n.locale) -> String {
        let next = p.next(from: today)
        var parts = [dateText(day: p.day, month: p.month, year: p.birthYear, locale: locale)]
        if p.status == .approved || p.status == .pending { parts.append(when(next, today: today)) }
        if let age = p.nextAge(from: today), age > 0 { parts.append(L("ios.bday.turns", ["age": age])) }
        return parts.joined(separator: " · ")
    }

    /// Whether "Add my birthday" makes sense: nobody on the list (or waiting) is linked to me yet.
    public static func canAddMine(_ people: [BirthdayPerson], me: String?) -> Bool {
        guard let me else { return false }
        return !people.contains { $0.isLive && $0.userId == me }
    }
}

// MARK: Who may do what

/// What the signed-in member may do with a feed's birthdays (docs/spec/birthdays.md §4.1). The server's `can_edit`
/// and `can_decide` on each entry win; these rules fill in what the entry doesn't say.
public struct BirthdayAbilities: Equatable, Sendable {
    public let role: FeedRole
    public let postersCanAdd: Bool

    public init(role: FeedRole, postersCanAdd: Bool = false) {
        self.role = role
        self.postersCanAdd = postersCanAdd
    }

    public init(_ feed: Feed) {
        self.init(role: feed.role, postersCanAdd: feed.value2.postersCanAddBirthdays ?? false)
    }

    public var isAdmin: Bool { role == .owner || role == .admin }
    /// New entries are `approved` at once; otherwise they are suggestions an admin decides.
    public var addsDirectly: Bool { isAdmin || postersCanAdd }
    public var suggests: Bool { !addsDirectly }
    /// The feed setting `posters_can_add_birthdays`.
    public var canChangeSetting: Bool { isAdmin }

    public func canDecide(_ p: BirthdayPerson) -> Bool {
        p.status == .pending && (p.canDecide ?? isAdmin)
    }

    /// Edit, and with it remove (withdraw a suggestion, remove yourself): the same people may do both.
    public func canEdit(_ p: BirthdayPerson) -> Bool {
        p.canEdit ?? isAdmin
    }

    public enum Fields: Equatable, Sendable {
        /// Name, date, year, show age, photo, link.
        case all
        /// The linked member's own entry: year, show age, photo and unlinking.
        case privacy
    }

    /// Which fields the edit form offers. `addedByMe`: I created the entry, directly or as a suggestion.
    public func fields(_ p: BirthdayPerson, me: String?, addedByMe: Bool) -> Fields {
        if isAdmin { return .all }
        if p.status == .pending, addedByMe { return .all }
        if postersCanAdd, addedByMe { return .all }
        if let me, p.userId == me { return .privacy }
        return .all
    }

    /// Who sees `birth_year` (§9): owner and admins, whoever added the entry and the linked member; everyone in the
    /// feed (and the frames) once `show_age` is on.
    public static func yearVisible(role: FeedRole, isOwnEntry: Bool, addedByMe: Bool, showAge: Bool) -> Bool {
        showAge || role == .owner || role == .admin || isOwnEntry || addedByMe
    }
}

/// "Added by me": I created the entry, directly or as a suggestion (`created_by_id`; null when that account is gone).
public func birthdayAddedBy(_ p: BirthdayPerson, me: String?) -> Bool {
    guard let me, let by = p.createdById else { return false }
    return by == me
}

// MARK: The form

/// What the add and edit form holds, checked like the server checks it, turned into request bodies.
public struct BirthdayDraft: Equatable, Sendable {
    public var name: String
    public var day: Int
    public var month: Int
    public var year: Int?
    public var showAge: Bool
    public var userId: String?

    public static let maxName = 40

    public init(name: String = "", day: Int = 1, month: Int = 1, year: Int? = nil, showAge: Bool = false, userId: String? = nil) {
        self.name = name
        self.day = day
        self.month = month
        self.year = year
        self.showAge = showAge
        self.userId = userId
    }

    public init(_ p: BirthdayPerson) {
        self.init(name: p.displayName, day: p.day, month: p.month, year: p.birthYear, showAge: p.showAge, userId: p.userId)
    }

    public var trimmedName: String {
        String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxName))
    }

    /// Show age needs a year.
    public var effectiveShowAge: Bool { showAge && year != nil }

    public enum Problem: Equatable, Sendable {
        case noName
        case date(BirthdayDates.Problem)
    }

    public func problem(currentYear: Int) -> Problem? {
        if trimmedName.isEmpty { return .noName }
        return BirthdayDates.problem(day: day, month: month, year: year, currentYear: currentYear).map(Problem.date)
    }

    public var createBody: Components.Schemas.BirthdayPersonCreate {
        .init(displayName: trimmedName, day: day, month: month, birthYear: year, showAge: effectiveShowAge, userId: userId)
    }

    /// `PATCH` body with only what changed against `original`, as raw JSON: clearing the year and unlinking need an
    /// explicit null, which the generated `BirthdayPersonUpdate` leaves out. `.privacy` sends only the linked
    /// member's fields (and unlinking only, never linking someone else).
    public func patch(from original: BirthdayDraft, fields: BirthdayAbilities.Fields = .all) -> [String: JSONValue] {
        var out: [String: JSONValue] = [:]
        if fields == .all {
            if trimmedName != original.trimmedName { out["display_name"] = .string(trimmedName) }
            if day != original.day { out["day"] = .number(Double(day)) }
            if month != original.month { out["month"] = .number(Double(month)) }
        }
        if year != original.year { out["birth_year"] = year.map { .number(Double($0)) } ?? .null }
        if effectiveShowAge != original.effectiveShowAge { out["show_age"] = .bool(effectiveShowAge) }
        if userId != original.userId {
            switch fields {
            case .all: out["user_id"] = userId.map { .string($0) } ?? .null
            case .privacy: if userId == nil { out["user_id"] = .null }
            }
        }
        return out
    }
}

// MARK: Greetings

public enum BirthdayGreetings {
    /// Greetings for this person's occurrence on `date`, oldest first ("Opa's frame 10:14 · Kitchen 11:02").
    public static func forPerson(_ greetings: [FeedBirthdayGreeting], birthdayId: String, date: LocalDate) -> [FeedBirthdayGreeting] {
        greetings.filter { $0.birthdayId == birthdayId && LocalDate(iso: $0.date) == date }.sorted { $0.createdAt < $1.createdAt }
    }

    public static func line(_ greetings: [FeedBirthdayGreeting], zone: TimeZone = .current, locale: Locale = L10n.locale) -> String {
        let f = DateFormatter()
        f.locale = locale
        f.timeZone = zone
        f.setLocalizedDateFormatFromTemplate("jmm")
        return greetings.map { "\($0.frameName) \(f.string(from: $0.createdAt))" }.joined(separator: " · ")
    }

    /// Adds or replaces one (websocket `birthday.greeting`), newest first.
    public static func merge(_ g: FeedBirthdayGreeting, into list: [FeedBirthdayGreeting]) -> [FeedBirthdayGreeting] {
        ([g] + list.filter { $0.id != g.id }).sorted { $0.createdAt > $1.createdAt }
    }
}

// MARK: Push

/// Notification grouping for birthday pushes (docs/api/README.md §6.3): greetings for the same person and day
/// replace each other (`birthday:<birthday_id>:<date>`, the body counts the frames), the morning notice is one per
/// day.
public enum BirthdayPush {
    /// The replace-key of a birthday push, nil for other pushes. Greeting pushes carry it as a top-level `tag`; else
    /// it's built from `ids` (`birthday_id`, `date`), with `received` as the date when the payload has none.
    public static func tag(_ payload: [AnyHashable: Any], received: LocalDate) -> String? {
        let ids = (payload["ids"] as? [String: Any]) ?? [:]
        func value(_ name: String) -> String? { (ids[name] as? String) ?? (payload[name] as? String) }
        let date = value("date").flatMap(LocalDate.init(iso:)) ?? received
        switch (payload["key"] as? String) ?? (payload["type"] as? String) ?? "" {
        case "birthday_greeting":
            if let tag = payload["tag"] as? String, tag.hasPrefix("birthday:") { return tag }
            return value("birthday_id").map { "birthday:\($0):\(date.iso)" }
        case "birthday_today":
            return "birthday_today:\(date.iso)"
        default:
            return nil
        }
    }

    /// A delivered notification as the tidy-up sees it.
    public struct Delivered: Sendable {
        public var id: String
        public var tag: String?
        public var date: Date

        public init(id: String, tag: String?, date: Date) {
            self.id = id
            self.tag = tag
            self.date = date
        }
    }

    /// The delivered notifications a newer one with the same tag replaces (all but the newest of each tag).
    public static func superseded(_ delivered: [Delivered]) -> [String] {
        var newest: [String: Delivered] = [:]
        for d in delivered {
            guard let tag = d.tag else { continue }
            if let n = newest[tag], n.date >= d.date { continue }
            newest[tag] = d
        }
        return delivered.filter { d in d.tag.map { newest[$0]?.id != d.id } ?? false }.map(\.id)
    }
}

// MARK: Time zone

public enum UserTimeZone {
    /// The IANA zone to send with `PATCH /v1/me {timezone}` when the server's differs (nil: nothing to do).
    public static func update(server: String?, device: TimeZone = .current) -> String? {
        let id = device.identifier
        guard !id.isEmpty, id != server else { return nil }
        return id
    }
}
