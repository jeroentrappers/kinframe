import Foundation

// Encouragement to share (docs/spec/family-social.md §9, docs/api/README.md §5.20): freshness rings, photo
// requests, streaks, prompts, On this day, summaries, recaps, milestones and reminder preferences. Nothing here ranks
// people. Unit-tested in EngagementTests.

public typealias Freshness = Components.Schemas.Freshness
public typealias PhotoRequest = Components.Schemas.PhotoRequest
public typealias FeedStreakSummary = Components.Schemas.FeedStreakSummary
public typealias FeedStreak = Components.Schemas.FeedStreak
public typealias FeedEngagement = Components.Schemas.FeedEngagement
public typealias FeedPrompt = Components.Schemas.FeedPrompt
public typealias PromptLibraryEntry = Components.Schemas.PromptLibraryEntry
public typealias OnThisDay = Components.Schemas.OnThisDay
public typealias OnThisDayGroup = Components.Schemas.OnThisDay.GroupsPayloadPayload
public typealias WeeklySummary = Components.Schemas.WeeklySummary
public typealias MonthlyRecap = Components.Schemas.MonthlyRecap
public typealias Milestone = Components.Schemas.Milestone
public typealias MilestoneKey = Components.Schemas.MilestoneKey
public typealias ReminderPreferences = Components.Schemas.ReminderPreferences
public typealias ReminderLevel = Components.Schemas.ReminderLevel
public typealias EmailDigest = Components.Schemas.EmailDigest
public typealias QuietHours = Components.Schemas.QuietHours

extension Components.Schemas.PhotoRequest: Identifiable {}
extension Components.Schemas.FeedPrompt: Identifiable {}
extension Components.Schemas.Milestone: Identifiable {
    public var id: String { key.rawValue }
}
extension Components.Schemas.PromptLibraryEntry: Identifiable {
    public var id: String { key }
}

// MARK: Freshness

public enum FreshnessText {
    /// Words on the ring, not numbers on a scale: "Fresh", "4 days", "2 weeks quiet", "No photos yet".
    public static func label(_ f: Freshness) -> String {
        let days = f.daysQuiet ?? 0
        switch f.state {
        case .never: return L("engagement.never")
        case .fresh: return L("engagement.fresh")
        case .recent: return Lp("engagement.fresh.days", count: max(1, days))
        case .quiet:
            if days >= 14 { return Lp("engagement.fresh.weeksQuiet", count: days / 7) }
            return Lp("engagement.fresh.daysQuiet", count: max(1, days))
        }
    }

    /// The ring's fill: the server's level (1 shared today, 0 after 14 days), never fully empty so it stays visible.
    public static func ring(_ f: Freshness?) -> Double {
        guard let f else { return 0 }
        if f.state == .never { return 0 }
        return max(0.06, min(1, f.level))
    }

    /// Quiet frames get the "Send something" nudge when tapped.
    public static func isPale(_ f: Freshness?) -> Bool {
        guard let f else { return false }
        return f.state == .quiet || f.state == .never
    }

    /// "Anna shared 3 days ago".
    public static func lastShared(_ f: Freshness) -> String? {
        guard let at = f.lastSharedAt else { return nil }
        let when = Format.relative(at, locale: L10n.locale)
        if let by = f.lastSharedBy { return L("engagement.fresh.lastBy", ["name": PersonName.first(by), "when": when]) }
        return L("engagement.fresh.last", ["when": when])
    }
}

// MARK: Photo requests

public enum PhotoRequestText {
    /// "Oma would like new photos" on the strip and frame cards while it's open; "Ben answered" for a day.
    public static func badge(_ r: PhotoRequest) -> String? {
        switch r.state {
        case .open: L("engagement.request.open", ["frame": r.frameName])
        case .answered: r.answeredBy.map { L("engagement.request.answered", ["name": PersonName.first($0)]) }
        case .expired: nil
        }
    }

    public static func isOpen(_ r: PhotoRequest?) -> Bool { r?.state == .open }
}

// MARK: Streak

public enum StreakText {
    /// "🔥 12 weeks" on the family header; nothing while there is no streak yet.
    public static func headline(_ s: FeedStreakSummary) -> String? {
        guard s.currentWeeks > 0 else { return nil }
        return "🔥 " + Lp("engagement.streak.weeks", count: s.currentWeeks)
    }

    /// "2 of 3 photos this week" or "This week's goal is reached".
    public static func week(_ s: FeedStreakSummary) -> String {
        s.met ? L("engagement.streak.met") : L("engagement.streak.progress", ["n": min(s.weekPhotos, s.goal), "goal": s.goal])
    }

    public static func progress(_ s: FeedStreakSummary) -> Double {
        guard s.goal > 0 else { return 0 }
        return min(1, Double(s.weekPhotos) / Double(s.goal))
    }

    public static let goalRange = 1...20
}

// MARK: Prompts

public enum PromptLogic {
    /// The current week's prompt, if there is one and it wasn't skipped.
    public static func current(_ list: [FeedPrompt]) -> FeedPrompt? {
        list.first { $0.status == .current && !$0.title.isEmpty }
    }

    public static func planned(_ list: [FeedPrompt]) -> [FeedPrompt] {
        list.filter { $0.status == .planned || ($0.status == .skipped && $0.weekOf >= (current(list)?.weekOf ?? "")) }
            .sorted { $0.weekOf < $1.weekOf }
    }

    public static func past(_ list: [FeedPrompt]) -> [FeedPrompt] {
        list.filter { $0.status == .past }.sorted { $0.weekOf > $1.weekOf }
    }

    /// "This week: Your view right now".
    public static func title(_ p: FeedPrompt) -> String {
        L("engagement.prompt.thisWeek", ["title": p.title])
    }

    public static let maxTitle = 80
}

// MARK: Weeks

public enum WeekOf {
    /// The Monday (YYYY-MM-DD) of the week before the one `date` is in: the week a summary covers.
    public static func previousMonday(_ date: Date = Date(), calendar: Calendar = WeekOf.calendar) -> String {
        let monday = startOfWeek(date, calendar: calendar)
        let prev = calendar.date(byAdding: .day, value: -7, to: monday) ?? monday
        return iso(prev, calendar: calendar)
    }

    public static func startOfWeek(_ date: Date, calendar: Calendar = WeekOf.calendar) -> Date {
        let comps = calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: date)
        return calendar.date(from: comps) ?? date
    }

    public static func iso(_ date: Date, calendar: Calendar = WeekOf.calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    public static func date(_ iso: String, calendar: Calendar = WeekOf.calendar) -> Date? {
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    /// "Week of 10 November".
    public static func label(_ iso: String) -> String {
        guard let d = date(iso) else { return iso }
        let f = DateFormatter()
        f.locale = L10n.locale
        f.setLocalizedDateFormatFromTemplate("dMMMM")
        return L("engagement.weekOf", ["date": f.string(from: d)])
    }

    /// Monday-first ISO weeks in the device's zone.
    public static var calendar: Calendar {
        var c = Calendar(identifier: .iso8601)
        c.timeZone = .current
        return c
    }
}

// MARK: Summaries and recaps

public enum SummaryText {
    /// "Your photos were seen 58 times on 3 frames".
    public static func seen(_ s: WeeklySummary) -> String {
        Lp("engagement.summary.seen", count: s.seenCount, ["frames": Lp("engagement.summary.frames", count: s.frameCount)])
    }

    /// "Frames loved 4 · 6 reactions · 3 comments", leaving out zeros.
    public static func details(_ s: WeeklySummary) -> String {
        var parts: [String] = []
        if s.loveCount > 0 { parts.append(Lp("engagement.summary.loves", count: s.loveCount)) }
        if s.reactionCount > 0 { parts.append(Lp("engagement.summary.reactions", count: s.reactionCount)) }
        if s.commentCount > 0 { parts.append(Lp("engagement.summary.comments", count: s.commentCount)) }
        return parts.joined(separator: " · ")
    }

    public static func recap(_ r: MonthlyRecap) -> String {
        var parts = [Lp("engagement.recap.photos", count: r.photosShared)]
        if r.videosShared > 0 { parts.append(Lp("engagement.recap.videos", count: r.videosShared)) }
        parts.append(Lp("engagement.recap.sharers", count: r.sharerCount))
        return parts.joined(separator: " · ")
    }
}

// MARK: Milestones

extension Components.Schemas.MilestoneKey {
    public var title: String { L("engagement.milestone.\(rawValue)") }

    public var symbol: String {
        switch self {
        case .firstPhoto: "photo"
        case .photos10, .photos100, .photos1000: "photo.stack"
        case .firstVideo: "video"
        case .yearOfSharing: "calendar"
        case .loves100: "heart"
        }
    }
}

public enum MilestoneLogic {
    /// Cards to show: achieved and not yet dismissed, newest first.
    public static func unseen(_ list: [Milestone]) -> [Milestone] {
        list.filter { !$0.seen }.sorted { $0.achievedAt > $1.achievedAt }
    }
}

// MARK: Reminders

extension Components.Schemas.ReminderLevel {
    public var label: String { L("engagement.level.\(rawValue)") }
    public var hint: String { L("engagement.levelHint.\(rawValue)") }
}

extension Components.Schemas.EmailDigest {
    public var label: String { L("engagement.digest.\(rawValue)") }
}

public enum ReminderLogic {
    public static func isSnoozed(_ p: ReminderPreferences, now: Date = Date()) -> Bool {
        (p.snoozedUntil.map { $0 > now }) ?? false
    }

    /// "08:30" ↔ minutes after midnight, for the quiet-hours pickers.
    public static func minutes(_ hhmm: String) -> Int? {
        let parts = hhmm.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2, (0..<24).contains(parts[0]), (0..<60).contains(parts[1]) else { return nil }
        return parts[0] * 60 + parts[1]
    }

    public static func hhmm(_ minutes: Int) -> String {
        let m = ((minutes % 1440) + 1440) % 1440
        return String(format: "%02d:%02d", m / 60, m % 60)
    }

    public static func date(_ hhmm: String, calendar: Calendar = .current) -> Date {
        let m = minutes(hhmm) ?? 0
        return calendar.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
    }

    public static func hhmm(from date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return hhmm((c.hour ?? 0) * 60 + (c.minute ?? 0))
    }
}

/// The two buttons on reminder notifications (README §6.3) and the update each one sends.
public enum ReminderAction: String, CaseIterable, Sendable {
    case snoozeMonth = "snooze_month"
    case fewerReminders = "fewer_reminders"

    public var update: Components.Schemas.ReminderPreferencesUpdate {
        switch self {
        case .snoozeMonth: .init(snoozeDays: 30)
        case .fewerReminders: .init(level: .fewer)
        }
    }

    public var title: String { L("engagement.action.\(rawValue)") }
}
