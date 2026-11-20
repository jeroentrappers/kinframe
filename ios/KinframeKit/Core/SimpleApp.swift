import Foundation

// The simple family app (docs/spec/simple-app.md): one current family, the composer's destination, the simple view.
// Free of UI so it can be unit tested.

/// The family the app shows (Home, Family, the composer's default): an account and one of its families.
public struct FamilyChoice: Codable, Hashable, Sendable {
    public var account: String
    public var feedId: String

    public init(account: String, feedId: String) {
        self.account = account
        self.feedId = feedId
    }
}

public enum HomeFilter {
    private static let key = "homeFilter"

    /// The family chip chosen on Home while you're still in it; nil is "All".
    public static func resolve(stored: FamilyChoice?, available: [FamilyChoice]) -> FamilyChoice? {
        guard let stored, available.count > 1, available.contains(stored) else { return nil }
        return stored
    }

    /// Remembered per device (§3.1).
    public static func load(_ defaults: UserDefaults = .standard) -> FamilyChoice? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(FamilyChoice.self, from: $0) }
    }

    public static func save(_ choice: FamilyChoice?, _ defaults: UserDefaults = .standard) {
        if let choice, let data = try? JSONEncoder().encode(choice) {
            defaults.set(data, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}

/// The home family (§3): the family the person posts to most; ties go to one they created, then the oldest.
public enum HomeFamily {
    public struct Candidate: Equatable, Sendable {
        public var choice: FamilyChoice
        public var posts: Int
        public var isOwner: Bool
        /// When the person joined (or the family was made); nil is unknown, counted as newest.
        public var since: Date?

        public init(choice: FamilyChoice, posts: Int, isOwner: Bool, since: Date?) {
            self.choice = choice
            self.posts = posts
            self.isOwner = isOwner
            self.since = since
        }
    }

    public static func pick(_ candidates: [Candidate]) -> FamilyChoice? {
        candidates.enumerated().min { a, b in
            let (x, y) = (a.element, b.element)
            if x.posts != y.posts { return x.posts > y.posts }
            if x.isOwner != y.isOwner { return x.isOwner }
            let (dx, dy) = (x.since ?? .distantFuture, y.since ?? .distantFuture)
            if dx != dy { return dx < dy }
            return a.offset < b.offset
        }?.element.choice
    }

    /// The families in list order: the home family first, the others as they came.
    public static func ordered<T>(_ list: [T], home: FamilyChoice?, choice: (T) -> FamilyChoice) -> [T] {
        guard let home, let i = list.firstIndex(where: { choice($0) == home }) else { return list }
        var out = list
        out.insert(out.remove(at: i), at: 0)
        return out
    }
}

/// How often the person posted to each family on this device, and in what Home showed of theirs.
public enum FamilyPostCounts {
    private static let key = "familyPostCounts"

    private static func id(_ c: FamilyChoice) -> String { c.account + "|" + c.feedId }

    public static func load(_ defaults: UserDefaults = .standard) -> [String: Int] {
        defaults.dictionary(forKey: key) as? [String: Int] ?? [:]
    }

    public static func count(_ c: FamilyChoice, in counts: [String: Int]) -> Int { counts[id(c)] ?? 0 }

    /// A post was shared to these families.
    public static func posted(_ families: [FamilyChoice], _ defaults: UserDefaults = .standard) {
        var all = load(defaults)
        for c in families { all[id(c), default: 0] += 1 }
        defaults.set(all, forKey: key)
    }

    /// Home showed this many of the person's own posts in a family: never count fewer.
    public static func observed(_ counts: [FamilyChoice: Int], _ defaults: UserDefaults = .standard) {
        var all = load(defaults)
        var changed = false
        for (c, n) in counts where n > (all[id(c)] ?? 0) {
            all[id(c)] = n
            changed = true
        }
        if changed { defaults.set(all, forKey: key) }
    }
}

public enum HomeTimeline {
    /// Family posts only (a send to one frame isn't in a family), and a post sent to several families once: the
    /// first of a send's posts stands for it.
    public static func families(_ posts: [TimelinePost]) -> [TimelinePost] {
        var seen = Set<String>()
        return posts.filter { p in
            guard p.kind == .feed else { return false }
            guard let send = p.sendId else { return true }
            return seen.insert(send).inserted
        }
    }
}

/// Normal or simple view (§6), a per-device setting.
public enum ViewMode: String, Sendable, CaseIterable {
    case normal, simple

    private static let key = "viewMode"
    private static let askedKey = "viewModeAsked"

    public static func load(_ defaults: UserDefaults = .standard) -> ViewMode {
        defaults.string(forKey: key).flatMap(ViewMode.init(rawValue:)) ?? .normal
    }

    public static func save(_ mode: ViewMode, _ defaults: UserDefaults = .standard) {
        defaults.set(mode.rawValue, forKey: key)
        defaults.set(true, forKey: askedKey)
    }

    /// The question after joining a family is asked once per device.
    public static func asked(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: askedKey)
    }
}

public enum SimpleDefaults {
    /// What comes along when a frame starts showing a family (§5): the last 50 posts' photos, no dialog.
    public static let addFrameHistory = HistoryChoice.lastN(50)
}

/// The composer's one destination row (§3.2).
public enum ComposeDestination: Equatable, Sendable {
    /// One or more families, each whole or one of its circles.
    case families([FamilyTarget])
    /// A direct send to one frame (only from that frame's screen).
    case frame(frameId: String)

    public struct FamilyTarget: Equatable, Hashable, Sendable {
        public var choice: FamilyChoice
        public var circleId: String?

        public init(_ choice: FamilyChoice, circleId: String? = nil) {
            self.choice = choice
            self.circleId = circleId
        }
    }

    /// Tick or untick a family in "Change" (at least one stays).
    public func toggling(_ c: FamilyChoice) -> ComposeDestination {
        guard case .families(let list) = self else { return .families([FamilyTarget(c)]) }
        if list.contains(where: { $0.choice == c }) {
            let rest = list.filter { $0.choice != c }
            return rest.isEmpty ? self : .families(rest)
        }
        return .families(list + [FamilyTarget(c)])
    }

    /// The whole family, or one of its circles, for a ticked family.
    public func with(circle: String?, for c: FamilyChoice) -> ComposeDestination {
        guard case .families(let list) = self else { return self }
        return .families(list.map { $0.choice == c ? FamilyTarget(c, circleId: circle) : $0 })
    }
}

public enum FamilyText {
    /// "3 families · 9 frames" for several families.
    public static func destination(families: Int, frames: Int) -> String {
        Lp("simple.compose.families", count: families) + " · " + Lp("simple.family.frames", count: frames)
    }

    /// "Peeters family · everyone and its 3 frames", or "Peeters family · The cousins".
    public static func destination(family: String, frames: Int, circle: String? = nil) -> String {
        if let circle { return L("simple.compose.toCircle", ["family": family, "circle": circle]) }
        return frames == 0
            ? L("simple.compose.toFamilyNoFrames", ["family": family])
            : Lp("simple.compose.toFamily", count: frames, ["family": family])
    }

    /// "Posts · people · frames" counts of the profile header.
    public static func counts(posts: Int?, people: Int, frames: Int) -> String {
        [posts.map { Lp("simple.family.posts", count: $0) }, Lp("simple.family.people", count: people),
         Lp("simple.family.frames", count: frames)].compactMap { $0 }.joined(separator: " · ")
    }

    /// "Oma, Lotte, Jan and 3 others": the member names line, the caller last as "you".
    public static func memberNames(_ names: [String], max: Int = 4) -> String {
        guard names.count > max else { return ListText.join(names) }
        return L("simple.family.namesMore", ["names": names.prefix(max).joined(separator: ", "), "n": names.count - max])
    }

    /// "Shows Trappers", "Shows Trappers and 4 other families", or "Doesn't show a family yet".
    public static func shows(_ families: [String]) -> String {
        guard let first = families.first else { return L("simple.frame.showsNone") }
        if families.count == 1 { return L("simple.frame.shows", ["family": first]) }
        return Lp("simple.frame.showsMore", count: families.count - 1, ["family": first])
    }

    /// "<surname> family" from a display name with a surname ("Jan Peeters" → "Peeters family"); nil without one.
    public static func suggestedName(for displayName: String?) -> String? {
        let parts = (displayName ?? "").split(whereSeparator: \.isWhitespace).map(String.init)
        guard parts.count >= 2, let surname = parts.last, surname.count > 1 else { return nil }
        return L("simple.start.familyName", ["surname": surname])
    }
}

public enum FamilyPhotos {
    /// The family's posts for the Photos grid, newest first; "Only mine" keeps the caller's own posts.
    public static func filter<T>(_ posts: [T], onlyMine: Bool, me: String?, poster: (T) -> String) -> [T] {
        guard onlyMine else { return posts }
        guard let me else { return [] }
        return posts.filter { poster($0) == me }
    }
}

/// "Add a frame" to a family (§5, §7): the outcome of one subscription request.
public enum AddFrameOutcome: Equatable, Sendable {
    /// Shown at once.
    case showing
    /// Waiting for an admin of the family (a frame managed by someone who isn't an admin there, on older rules).
    case waiting
    case failed

    public init(_ status: SubscriptionStatus) {
        switch status {
        case .active, .paused: self = .showing
        case .pending: self = .waiting
        case .rejected, .removed: self = .failed
        }
    }

    public func message(frame: String, family: String) -> String {
        switch self {
        case .showing: L("simple.addFrame.showing", ["frame": frame, "family": family])
        case .waiting: L("simple.addFrame.waiting", ["frame": frame, "family": family])
        case .failed: L("simple.addFrame.failed", ["frame": frame, "family": family])
        }
    }
}

public enum SimpleViewLogic {
    /// The post after (+1) or before (-1) `index`, kept inside the list.
    public static func step(_ index: Int, by delta: Int, count: Int) -> Int {
        guard count > 0 else { return 0 }
        return min(max(0, index + delta), count - 1)
    }
}
