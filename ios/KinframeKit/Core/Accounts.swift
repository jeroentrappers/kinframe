import Foundation

/// One signed-in server (docs/spec/multi-server.md §2.1, README §5.22 "Accounts in the apps"): the server's address
/// and discovery document. The session (Logto or dev token) lives in the Keychain under the account's id, the caches
/// with the app. Shared with the share extension through the App Group.
public struct AccountRecord: Codable, Equatable, Sendable, Identifiable {
    /// Local and stable. The account that existed before several accounts were possible keeps `AccountRecord.legacyId`,
    /// so its Keychain items (stored under the old, unprefixed names) stay where they are.
    public var id: String
    public var server: ServerConfig
    public var addedAt: Date

    public init(id: String = AccountRecord.newId(), server: ServerConfig, addedAt: Date = Date()) {
        self.id = id
        self.server = server
        self.addedAt = addedAt
    }

    public static let legacyId = "default"

    public static func newId() -> String {
        "a-" + UUID().uuidString.lowercased().prefix(13).replacingOccurrences(of: "-", with: "")
    }

    public var isLegacy: Bool { id == Self.legacyId }

    /// The server's label: discovery `server_name`, else its host.
    public var serverName: String { AccountServers.name(server) }

    /// Keychain and defaults names for this account's session: the legacy account keeps the old names.
    public func key(_ name: String) -> String {
        isLegacy ? name : "\(name).\(id)"
    }
}

/// Which server a push payload, a link or a stored item belongs to.
public enum AccountServers {
    /// The chip text: discovery `server_name`, else the host.
    public static func name(_ server: ServerConfig) -> String {
        if let n = server.discovery.serverName?.trimmingCharacters(in: .whitespaces), !n.isEmpty { return n }
        return ServerAddress.display(server.url)
    }

    /// The same server, also when one side is a base URL from discovery or a push payload and the other the address
    /// the person typed (scheme and trailing slashes don't matter; hosts compare case-insensitively).
    public static func same(_ a: URL, _ b: URL) -> Bool {
        normalized(a) == normalized(b)
    }

    static func normalized(_ url: URL) -> String {
        let host = (url.host() ?? "").lowercased()
        let port = url.port.map { ":\($0)" } ?? ""
        return host + port
    }

    /// Does this account belong to `serverURL` (a payload's `server`, a link's host)? Matches the address, the API
    /// base and discovery `base_url`.
    public static func matches(_ record: AccountRecord, _ serverURL: URL) -> Bool {
        if same(record.server.url, serverURL) || same(record.server.apiBase, serverURL) { return true }
        if let base = record.server.discovery.baseURL.flatMap(URL.init(string:)), same(base, serverURL) { return true }
        return false
    }

    /// The account a push payload belongs to: its `server` field (README §6.3), else `fallback` (the account whose
    /// push endpoint received it).
    public static func account(forPush payload: [AnyHashable: Any], in records: [AccountRecord], fallback: String?) -> AccountRecord? {
        if let s = payload["server"] as? String, let url = URL(string: s), let r = records.first(where: { matches($0, url) }) {
            return r
        }
        if let fallback, let r = records.first(where: { $0.id == fallback }) { return r }
        return records.first
    }
}

/// The account list in the App Group's defaults (`accounts`, the first one is primary). Migrates a single-server
/// install (`server`) into the first account without signing anyone out.
public enum AccountStore {
    private static let key = "accounts"
    /// The single-server key of earlier versions (ServerStore). Still written for the primary account so a share
    /// extension of an older build (during an update) keeps working.
    static let legacyServerKey = "server"

    public static func load(_ defaults: UserDefaults = AppGroup.defaults) -> [AccountRecord] {
        if let data = defaults.data(forKey: key), let list = try? JSONDecoder().decode([AccountRecord].self, from: data) {
            return list
        }
        // First run with several accounts: the existing server becomes the first account, with the old session.
        guard let data = defaults.data(forKey: legacyServerKey),
              let server = try? JSONDecoder().decode(ServerConfig.self, from: data) else { return [] }
        let migrated = [AccountRecord(id: AccountRecord.legacyId, server: server)]
        save(migrated, defaults)
        return migrated
    }

    public static func save(_ list: [AccountRecord], _ defaults: UserDefaults = AppGroup.defaults) {
        defaults.set(try? JSONEncoder().encode(list), forKey: key)
        defaults.set(list.first.flatMap { try? JSONEncoder().encode($0.server) }, forKey: legacyServerKey)
    }

    public static var primary: AccountRecord? { load().first }

    public static func record(_ id: String) -> AccountRecord? {
        load().first { $0.id == id }
    }

    /// Insert or replace (by id), keeping the order; a new one goes last unless `primary`.
    public static func upsert(_ record: AccountRecord, primary: Bool = false, _ defaults: UserDefaults = AppGroup.defaults) {
        var list = load(defaults)
        if let i = list.firstIndex(where: { $0.id == record.id }) {
            list[i] = record
            if primary, i != 0 { list.insert(list.remove(at: i), at: 0) }
        } else if primary {
            list.insert(record, at: 0)
        } else {
            list.append(record)
        }
        save(list, defaults)
    }

    public static func remove(_ id: String, _ defaults: UserDefaults = AppGroup.defaults) {
        save(load(defaults).filter { $0.id != id }, defaults)
    }

    public static func makePrimary(_ id: String, _ defaults: UserDefaults = AppGroup.defaults) {
        var list = load(defaults)
        guard let i = list.firstIndex(where: { $0.id == id }), i != 0 else { return }
        list.insert(list.remove(at: i), at: 0)
        save(list, defaults)
    }

    /// An account already on this server: same `server_id` (the same server under another address), else the same
    /// address.
    public static func existing(_ url: URL, discovery: Discovery?, in list: [AccountRecord]) -> AccountRecord? {
        if let sid = discovery?.serverId, let r = list.first(where: { $0.server.discovery.serverId == sid }) { return r }
        return list.first { AccountServers.matches($0, url) }
    }
}

/// Merging lists from several accounts (Home, Chats, history): newest first, every source's order kept.
public enum AccountMerge {
    /// Interleave already sorted (newest first) lists by `date`. Stable: equal dates keep the source order.
    public static func interleave<T>(_ lists: [[T]], by date: (T) -> Date) -> [T] {
        var out: [T] = []
        out.reserveCapacity(lists.reduce(0) { $0 + $1.count })
        var idx = Array(repeating: 0, count: lists.count)
        while true {
            var best: Int?
            for (l, list) in lists.enumerated() where idx[l] < list.count {
                if let b = best {
                    if date(list[idx[l]]) > date(lists[b][idx[b]]) { best = l }
                } else {
                    best = l
                }
            }
            guard let b = best else { break }
            out.append(lists[b][idx[b]])
            idx[b] += 1
        }
        return out
    }

    /// When paging several timelines: items newer than the oldest of the last pages that still have more, so a
    /// server whose next page would be newer isn't skipped. Sources without more pages don't limit.
    public static func safeCutoff(oldest: [Date?], hasMore: [Bool]) -> Date? {
        zip(oldest, hasMore).compactMap { $1 ? $0 : nil }.max()
    }

    /// Paged lists of several accounts merged strictly by time: newest first, and only as far back as every source
    /// has loaded (`safeCutoff`), so an item of one server never shows below an older one of another server whose
    /// next page isn't loaded yet. `hasMore[i]` says whether `lists[i]` has more pages.
    public static func merged<T>(_ lists: [[T]], hasMore: [Bool], by date: (T) -> Date) -> [T] {
        let all = interleave(lists, by: date)
        guard let cutoff = safeCutoff(oldest: lists.map { $0.map(date).min() }, hasMore: hasMore) else { return all }
        return all.filter { date($0) >= cutoff }
    }

    /// The sources to page next ("Load more"): those whose oldest loaded item is the cutoff, i.e. the ones holding the
    /// merged list back. Paging them moves the cutoff (or ends them); the others wait their turn.
    public static func toLoad(oldest: [Date?], hasMore: [Bool]) -> [Int] {
        guard let cutoff = safeCutoff(oldest: oldest, hasMore: hasMore) else { return [] }
        return oldest.indices.filter { hasMore[$0] && oldest[$0] == cutoff }
    }

    /// `toLoad` for lists (their oldest item by `date`).
    public static func toLoad<T>(_ lists: [[T]], hasMore: [Bool], by date: (T) -> Date) -> [Int] {
        toLoad(oldest: lists.map { $0.map(date).min() }, hasMore: hasMore)
    }
}
