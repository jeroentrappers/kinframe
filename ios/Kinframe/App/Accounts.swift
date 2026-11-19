import KinframeKit
import SwiftUI

/// Every account the app is signed in to (docs/spec/multi-server.md §2, README §5.22 "Accounts in the apps"): one
/// `AppModel` per server, each with its own session, websocket, push registration, caches and discovery. The first is
/// primary: it opens first, owns navigation and gets shares by default. Merged lists (Home, Chats, frames, families,
/// history, recipients) read across `active`; detail screens get the model of their own account.
@MainActor
@Observable
final class Accounts {
    private(set) var models: [AppModel]
    let toasts = ToastCenter()
    let outbox: OutboxMonitor
    /// "Add a server" (Settings → Accounts, or a link of a server the app isn't signed in to).
    var adding: AddServerRequest?
    /// A link of a server without an account here: asks first ("This family is on kinframe.janssens.be…").
    var foreignLink: ForeignLink?

    struct AddServerRequest: Identifiable {
        let id = UUID()
        let model: AppModel
        /// Prefilled from a link; nil asks for the address.
        var server: URL?
        /// Opened once signed in.
        var link: DeepLink?
    }

    struct ForeignLink: Identifiable {
        let link: DeepLink
        let server: URL
        var id: String { server.absoluteString + "|" + link.id }
    }

    init() {
        let outbox = OutboxMonitor()
        self.outbox = outbox
        let records = AccountStore.load()
        let toasts = toasts
        let primary = AppModel(accountId: records.first?.id ?? AccountRecord.legacyId, toasts: toasts, outbox: outbox)
        models = [primary] + records.dropFirst().map { AppModel(accountId: $0.id, toasts: toasts, outbox: outbox, host: primary) }
        for m in models { m.accounts = self }
        syncOutbox()
    }

    var primary: AppModel { models[0] }
    /// Signed in and connected (merged lists use these).
    var active: [AppModel] { models.filter { $0.api != nil } }
    /// The server chip shows when there is more than one account.
    var several: Bool { models.count > 1 }

    /// The model of a route's account; nil or unknown ids are the primary account.
    func model(for id: String?) -> AppModel {
        guard let id else { return primary }
        return models.first { $0.accountId == id } ?? primary
    }

    func model(server url: URL) -> AppModel? {
        models.first { m in
            guard let s = m.server else { return false }
            return AccountServers.matches(AccountRecord(id: m.accountId, server: s), url)
        }
    }

    /// The upload queue runs each job with the session of its server.
    func syncOutbox() {
        var sessions: [URL: TokenProvider] = [:]
        for m in models where m.api != nil {
            if let s = m.server { sessions[s.apiBase] = m.auth }
        }
        outbox.configure(sessions: sessions)
    }

    // MARK: Launch and foreground

    func launch() async {
        await withTaskGroup(of: Void.self) { group in
            for m in models.dropFirst() { group.addTask { await m.resumeAsSecondary() } }
        }
        syncOutbox()
        for m in models.dropFirst() { PushRegistrar.shared.registerNewAccount(m) }
    }

    func didBecomeActive() {
        for m in models { m.didBecomeActive() }
    }

    // MARK: Adding and removing

    /// Start "Add a server": a new model whose sign-in runs in the add sheet.
    func startAdding(server: URL? = nil, link: DeepLink? = nil) {
        let m = AppModel(accountId: AccountRecord.newId(), toasts: toasts, outbox: outbox, host: primary)
        m.accounts = self
        adding = AddServerRequest(model: m, server: server, link: link)
    }

    /// An account already on that server (same `server_id`, or the same address).
    func existing(_ url: URL, discovery: Discovery?) -> AppModel? {
        let records = models.compactMap { m in m.server.map { AccountRecord(id: m.accountId, server: $0) } }
        guard let r = AccountStore.existing(url, discovery: discovery, in: records) else { return nil }
        return models.first { $0.accountId == r.id }
    }

    /// The new account signed in: keep it and connect it.
    func finishAdding(_ request: AddServerRequest) {
        let m = request.model
        adding = nil
        guard m.server != nil, !models.contains(where: { $0 === m }) else { return }
        models.append(m)
        m.phase = .main
        syncOutbox()
        PushRegistrar.shared.registerNewAccount(m)
        toasts.show(L("servers.account.added", ["server": m.serverName]))
        if let link = request.link { m.route(link) }
    }

    /// The add sheet closed without signing in: forget the half-made account.
    func cancelAdding(_ request: AddServerRequest) {
        adding = nil
        if !models.contains(where: { $0 === request.model }) { AccountStore.remove(request.model.accountId) }
    }

    /// Sign out of one account; the others stay. Signing out of the last one goes back to onboarding.
    func signOut(_ m: AppModel) async {
        guard models.count > 1 else {
            await m.signOut()
            return
        }
        let wasPrimary = m === primary
        await m.signOut(keepServer: false)
        models.removeAll { $0 === m }
        if wasPrimary { rehost() }
        syncOutbox()
    }

    /// Make another account primary: it opens first and gets shares by default.
    func makePrimary(_ m: AppModel) {
        guard let i = models.firstIndex(where: { $0 === m }), i != 0 else { return }
        models.insert(models.remove(at: i), at: 0)
        AccountStore.makePrimary(m.accountId)
        rehost()
    }

    /// The primary model owns navigation; the others forward to it.
    private func rehost() {
        let p = primary
        let tab = models.dropFirst().first?.tab ?? p.tab
        p.host = nil
        for m in models.dropFirst() { m.host = p }
        p.tab = tab
        p.framesPath = NavigationPath()
        p.feedsPath = NavigationPath()
        p.historyPath = NavigationPath()
        p.homePath = NavigationPath()
        p.chatsPath = NavigationPath()
        if p.phase != .main, p.auth.isSignedIn { p.phase = .main }
    }

    /// A secondary account's session ended: it stays listed with "Sign in again".
    func sessionEnded(_ m: AppModel) {
        syncOutbox()
    }

    /// Sign in again to an account whose session ended (Settings → Accounts).
    func signInAgain(_ m: AppModel) {
        guard let server = m.server else { return }
        let req = AddServerRequest(model: m, server: server.url, link: nil)
        adding = req
    }

    // MARK: Links of other servers

    /// A link for a server other than the primary one: the account there handles it; without one, ask to add that
    /// server. False when the primary account handles it as usual.
    func route(_ link: DeepLink) -> Bool {
        guard let target = link.server, let primaryServer = primary.server else { return false }
        if AccountServers.matches(AccountRecord(id: primary.accountId, server: primaryServer), target) { return false }
        if let m = model(server: target), m !== primary {
            if m.api != nil {
                m.route(link)
            } else {
                signInAgain(m)
            }
            return true
        }
        guard primary.phase == .main else { return false }
        foreignLink = ForeignLink(link: link, server: target)
        return true
    }

    // MARK: Merged lists

    struct Owned<T> {
        let model: AppModel
        let value: T
    }

    var frames: [Owned<FrameSummary>] {
        active.flatMap { m in m.frames.map { Owned(model: m, value: $0) } }
    }

    var feeds: [Owned<FeedSummary>] {
        active.flatMap { m in m.feeds.map { Owned(model: m, value: $0) } }
    }

    /// Chats of all accounts by last message.
    var conversations: [Owned<Conversation>] {
        AccountMerge.interleave(active.map { m in m.social.conversations.map { Owned(model: m, value: $0) } }) { $0.value.lastActivityAt }
    }

    /// Unread chats over all accounts (the Chats tab's badge).
    var unreadChats: Int {
        active.reduce(0) { $0 + ChatList.unreadChats($1.social.conversations) }
    }

    /// The app icon's badge: the sum of every account's `getBadge` total.
    var badgeTotal: Int {
        active.reduce(0) { $0 + ($1.social.badgeCount) }
    }

    var framesLoaded: Bool { active.allSatisfy(\.framesLoaded) }
    var feedsLoaded: Bool { active.allSatisfy(\.feedsLoaded) }

    func loadFrames() async {
        await withTaskGroup(of: Void.self) { g in for m in active { g.addTask { await m.loadFrames() } } }
    }

    func loadFeeds() async {
        await withTaskGroup(of: Void.self) { g in for m in active { g.addTask { await m.loadFeeds() } } }
    }
}

/// The small server label in merged lists when there are several accounts ("Janssens").
struct ServerChip: View {
    @Environment(Accounts.self) private var accounts
    let model: AppModel

    var body: some View {
        if accounts.several {
            Text(model.serverName)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Palette.textMuted)
                .lineLimit(1)
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(Capsule().fill(Palette.surface2))
                .accessibilityLabel(L("servers.account.chip", ["server": model.serverName]))
        }
    }
}

extension View {
    /// The screen of a route talks to the route's own account.
    func account(_ id: String?, in accounts: Accounts) -> some View {
        environment(accounts.model(for: id))
    }
}
