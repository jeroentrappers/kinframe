import KinframeKit
import SwiftUI

/// One account's state (docs/spec/multi-server.md §2.1): its server, session, frame and family lists, live events and
/// caches. The primary account's model also holds the app's navigation; the other accounts' models (`host` set)
/// forward tabs, paths and preselections to it, so a screen of any account can open things the usual way. Screens
/// get the model of the account they show through the environment (`Accounts.model(for:)`).
@MainActor
@Observable
final class AppModel {
    enum Phase: Equatable {
        case launching
        case onboarding
        case main
    }

    enum Tab: Hashable {
        case home, chats, frames, feeds, send, history, settings
    }

    /// A deep link that arrived for another server, waiting for the person to confirm switching.
    struct ServerSwitch: Identifiable {
        let link: DeepLink
        let server: URL
        var id: String { server.absoluteString }
    }

    /// The account (`AccountRecord.id`) this model belongs to.
    let accountId: String
    /// The primary account's model, which owns navigation; nil for the primary itself.
    weak var host: AppModel?
    /// All accounts (merged lists, adding a server).
    weak var accounts: Accounts?
    var isPrimary: Bool { host == nil }

    var phase: Phase = .launching
    var server: ServerConfig?
    var me: User?
    var frames: [FrameSummary] = []
    var framesLoaded = false
    var framesError: String?
    /// Feeds you're a member of (any role), when the server has feeds.
    var feeds: [FeedSummary] = []
    var feedsLoaded = false
    var liveStatus: LiveSocket.Status = .idle
    var unreachable = false
    /// The server answered `426 app_update_required` and discovery couldn't be re-read since: block.
    var updateRequiredByServer = false
    /// The recommended build whose "new version" banner was dismissed.
    var dismissedUpdateBuild: Int? = AppUpdateDismissals().dismissedBuild

    var tab: Tab {
        get { host?.tab ?? own_tab }
        set { if let host { host.tab = newValue } else { own_tab = newValue } }
    }
    private var own_tab: Tab = .frames
    var framesPath: NavigationPath {
        get { host?.framesPath ?? own_framesPath }
        set { if let host { host.framesPath = newValue } else { own_framesPath = newValue } }
    }
    private var own_framesPath: NavigationPath = NavigationPath()
    var historyPath: NavigationPath {
        get { host?.historyPath ?? own_historyPath }
        set { if let host { host.historyPath = newValue } else { own_historyPath = newValue } }
    }
    private var own_historyPath: NavigationPath = NavigationPath()
    var feedsPath: NavigationPath {
        get { host?.feedsPath ?? own_feedsPath }
        set { if let host { host.feedsPath = newValue } else { own_feedsPath = newValue } }
    }
    private var own_feedsPath: NavigationPath = NavigationPath()
    /// Family social: Home (timeline) and Chats.
    var homePath: NavigationPath {
        get { host?.homePath ?? own_homePath }
        set { if let host { host.homePath = newValue } else { own_homePath = newValue } }
    }
    private var own_homePath: NavigationPath = NavigationPath()
    var chatsPath: NavigationPath {
        get { host?.chatsPath ?? own_chatsPath }
        set { if let host { host.chatsPath = newValue } else { own_chatsPath = newValue } }
    }
    private var own_chatsPath: NavigationPath = NavigationPath()
    /// Frame code or invite being handled (sheet), from a deep link.
    var pendingLink: DeepLink?
    var activeLink: DeepLink?
    var serverSwitch: ServerSwitch? {
        get { host?.serverSwitch ?? own_serverSwitch }
        set { if let host { host.serverSwitch = newValue } else { own_serverSwitch = newValue } }
    }
    private var own_serverSwitch: ServerSwitch? = nil
    /// Frames preselected for the next compose.
    var composeFrameIds: Set<String> {
        get { host?.composeFrameIds ?? own_composeFrameIds }
        set { if let host { host.composeFrameIds = newValue } else { own_composeFrameIds = newValue } }
    }
    private var own_composeFrameIds: Set<String> = []
    /// Feeds preselected for the next compose ("Post" in a feed).
    var composeFeedIds: Set<String> {
        get { host?.composeFeedIds ?? own_composeFeedIds }
        set { if let host { host.composeFeedIds = newValue } else { own_composeFeedIds = newValue } }
    }
    private var own_composeFeedIds: Set<String> = []
    /// The weekly prompt the next compose answers ("Answer" on a prompt card).
    var composePromptId: String? {
        get { host?.composePromptId ?? own_composePromptId }
        set { if let host { host.composePromptId = newValue } else { own_composePromptId = newValue } }
    }
    private var own_composePromptId: String? = nil
    /// A feed post to open from a notification (its receipts).
    var pendingFeedPost: FeedPostRoute? {
        get { host?.pendingFeedPost ?? own_pendingFeedPost }
        set { if let host { host.pendingFeedPost = newValue } else { own_pendingFeedPost = newValue } }
    }
    private var own_pendingFeedPost: FeedPostRoute? = nil
    /// Bumped when history should reload (a send was created, live events).
    var historyVersion: Int {
        get { host?.historyVersion ?? own_historyVersion }
        set { if let host { host.historyVersion = newValue } else { own_historyVersion = newValue } }
    }
    private var own_historyVersion: Int = 0
    var expired = false

    let auth: AuthService
    let toasts: ToastCenter
    /// "Add from Google Photos", app-wide (one import at a time; a relaunch or a notification finds it again).
    let googleImport = GoogleImportController()
    /// The last status seen per photo source the caller owns, to say once when one starts needing them.
    private var sourceStates: [String: String] = [:]
    let outbox: OutboxMonitor
    private(set) var api: API?
    private let live = LiveSocket()
    /// Discovery on returning to the foreground: at most hourly. After a 426: at most once a minute.
    private var activeRefresh = DiscoveryRefreshThrottle(interval: 3600)
    private var updateRefresh = DiscoveryRefreshThrottle(interval: 60)
    /// Live events for screens that care (history, frame detail).
    let events = EventHub()
    /// Family social: chats, badge, reminders, milestones (Social/SocialStore.swift).
    let social = SocialStore()

    init(accountId: String = AccountRecord.legacyId, toasts: ToastCenter = ToastCenter(), outbox: OutboxMonitor? = nil, host: AppModel? = nil) {
        L10n.bootstrap()
        self.accountId = accountId
        self.toasts = toasts
        self.host = host
        auth = AuthService(account: accountId)
        self.outbox = outbox ?? OutboxMonitor()
        social.app = self
        auth.onSignedOut = { [weak self] in self?.sessionEnded() }
        live.onEvent = { [weak self] in self?.handle($0) }
        live.onStatus = { [weak self] in self?.liveStatus = $0 }
        // An import is a send: history shows it.
        googleImport.onFinished = { [weak self] _ in self?.historyVersion += 1 }
        self.outbox.onSent = { [weak self] send in
            self?.historyVersion += 1
            self?.events.post(.sendUpdated(sendId: send.id))
        }
        // A 426 from any request (API, uploads, websocket, wherever it ran): re-read discovery (README §5.15).
        NotificationCenter.default.addObserver(forName: AppUpdateSignal.notification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.serverRequiresUpdate() }
        }
        // Set up the session right away: the system may launch the app in the background just to finish uploads.
        server = AccountStore.record(accountId)?.server
        auth.configure(server?.discovery)
        if let server, auth.isSignedIn {
            prepare(server)
        }
    }

    var discovery: Discovery? { server?.discovery }
    var limits: Limits { discovery?.limits ?? .defaults }
    /// Feeds are shown only when discovery says the server has them.
    var feedsEnabled: Bool { discovery?.feeds == true }
    /// Birthdays in feeds: only when discovery says `birthdays` (together with `feeds`).
    var birthdaysEnabled: Bool { feedsEnabled && discovery?.birthdays == true }
    /// Leaving with a choice, taking away, keep requests, memories (README §5.21): only when discovery says `leaving`.
    var leavingEnabled: Bool { discovery?.leaving == true }
    /// Following families on other servers and allowing remote frames (README §5.22): discovery `remote_families`.
    var remoteFamiliesEnabled: Bool { discovery?.remoteFamilies == true }
    /// Photo sources and Google Photos import, as far as discovery offers them.
    var photoSources: PhotoSourceFeatures { PhotoSourceFeatures(discovery) }
    /// The update page or banner this build gets under the server's `app_versions`.
    var appUpdate: AppUpdatePrompt {
        AppUpdatePolicy.prompt(discovery?.appVersions, build: .current, dismissedBuild: dismissedUpdateBuild,
                               updateRequired: updateRequiredByServer)
    }

    // MARK: Launch

    func launch() async {
        guard phase == .launching else { return }
        #if DEBUG
        await DebugLaunch.beforeLaunch(self)
        defer { DebugLaunch.afterLaunch(self) }
        #endif
        _ = activeRefresh.shouldRefresh(force: true)
        // The other accounts connect alongside (docs/spec/multi-server.md §2.1).
        if let accounts { Task { await accounts.launch() } }
        if let server, auth.isSignedIn {
            connect(server)
            phase = .main
            await refreshDiscovery()
            await loadMe()
            if me?.displayName == PLACEHOLDER_NAME || Onboarding.step == .firstFrame {
                phase = .onboarding
            }
        } else {
            phase = .onboarding
            // A chosen server whose sign-in isn't done yet still decides whether this build may be used.
            if server != nil { await refreshDiscovery() }
        }
    }

    /// Another account (not primary) at launch: connect it in the background, no onboarding.
    func resumeAsSecondary() async {
        guard let server, auth.isSignedIn else { return }
        connect(server)
        phase = .main
        await refreshDiscovery()
        await loadMe()
    }

    /// Re-read discovery so limits, auth settings and the app version policy stay current. True when it was read.
    @discardableResult
    func refreshDiscovery() async -> Bool {
        guard let server else { return false }
        guard let fresh = try? await DiscoveryClient.fetch(server.url) else { return false }
        // `self.server` may have changed while fetching (sign-out, another server).
        guard self.server?.url == server.url, let current = self.server else { return true }
        let updated = ServerConfig(url: current.url, discovery: fresh)
        if updated != current {
            self.server = updated
            saveServer(updated)
            auth.configure(fresh)
        }
        unreachable = false
        // Discovery decides now (below the minimum blocks through `app_versions`).
        updateRequiredByServer = false
        return true
    }

    // MARK: App version (README §5.15)

    /// A request got `426 app_update_required`: re-read discovery, which says whether to block. If it can't be read,
    /// block anyway (the server just said this build is no longer served).
    private func serverRequiresUpdate() {
        guard updateRefresh.shouldRefresh() else {
            if discovery?.appVersions == nil { updateRequiredByServer = true }
            return
        }
        Task {
            if await !refreshDiscovery() { updateRequiredByServer = true }
        }
    }

    /// "Check again" on the update page.
    func checkForAppUpdate() async {
        _ = activeRefresh.shouldRefresh(force: true)
        let wasBlocked = appUpdate.isBlocking
        if await refreshDiscovery() {
            if wasBlocked, !appUpdate.isBlocking, auth.isSignedIn {
                // Probably a lowered minimum: catch up on what failed while blocked.
                await loadFrames()
                await loadFeeds()
            }
        }
    }

    /// Hide the "new version" banner until the server recommends a newer build.
    func dismissAppUpdate(_ recommendedBuild: Int) {
        AppUpdateDismissals().dismiss(recommendedBuild: recommendedBuild)
        dismissedUpdateBuild = recommendedBuild
    }

    // MARK: Server and session

    /// Remember a validated server (onboarding). Signs out of the previous one.
    func choose(server url: URL, discovery: Discovery) async {
        if let current = server, current.url != url {
            await signOut(keepServer: false)
        }
        let config = ServerConfig(url: url, discovery: discovery)
        server = config
        saveServer(config)
        auth.configure(discovery)
    }

    /// Keep this account's record (address and discovery) in the shared account list.
    private func saveServer(_ config: ServerConfig) {
        var record = AccountStore.record(accountId) ?? AccountRecord(id: accountId, server: config)
        record.server = config
        AccountStore.upsert(record, primary: isPrimary && AccountStore.load().isEmpty)
    }

    /// The server's label for the chip in merged lists (only shown with several accounts).
    var serverName: String { server.map(AccountServers.name) ?? "" }

    /// After a successful sign-in: connect and decide the next onboarding step.
    func didSignIn(idTokenName: String?) async {
        guard let server else { return }
        expired = false
        connect(server)
        await loadMe()
        if me?.displayName == PLACEHOLDER_NAME, let name = idTokenName?.trimmingCharacters(in: .whitespaces), !name.isEmpty {
            await updateName(String(name.prefix(40)))
        }
        await loadFrames()
        if let me, me.locale == nil {
            _ = try? await api?.call { try await $0.updateMe(body: .json(.init(locale: L10n.code))).ok }
        }
    }

    /// API client and upload queue for the session (no network yet).
    private func prepare(_ server: ServerConfig) {
        let api = API(baseURL: server.apiBase, tokens: auth)
        self.api = api
        googleImport.configure(api: api)
        accounts?.syncOutbox()
    }

    private func connect(_ server: ServerConfig) {
        if api == nil || api?.baseURL != server.apiBase { prepare(server) }
        if let api { live.start(api: api) }
        Task {
            await loadFrames()
            await loadFeeds()
            if photoSources.googleImport { await googleImport.resumeIfNeeded() }
            await social.refresh()
        }
    }

    func signOut(keepServer: Bool = true) async {
        live.stop()
        googleImport.configure(api: nil)
        sourceStates = [:]
        PushRegistrar.shared.unregister(api: api, account: accountId)
        await auth.signOut()
        api = nil
        me = nil
        frames = []
        framesLoaded = false
        feeds = []
        feedsLoaded = false
        framesPath = NavigationPath()
        historyPath = NavigationPath()
        feedsPath = NavigationPath()
        homePath = NavigationPath()
        chatsPath = NavigationPath()
        social.reset()
        tab = .frames
        accounts?.syncOutbox()
        if !keepServer {
            server = nil
            AccountStore.remove(accountId)
        }
        Onboarding.reset()
        phase = .onboarding
    }

    /// `DELETE /v1/me`, then sign out. The server refuses the old token from now on, so sign-out must not call the
    /// API again (push endpoints are already gone with the account).
    func deleteAccount(removeItems: Bool) async throws {
        guard let api else { return }
        _ = try await api.call { try await $0.deleteMe(query: .init(removeItems: removeItems)).noContent }
        self.api = nil
        await signOut()
        toasts.show(L("account.deleted"))
    }

    /// The refresh token was rejected: back to sign-in, keeping the server.
    private func sessionEnded() {
        guard phase == .main else { return }
        expired = true
        // Another account's session: say so; Settings → Accounts offers to sign in again. The others carry on.
        if !isPrimary {
            live.stop()
            api = nil
            toasts.show(L("servers.account.expired", ["server": serverName]))
            accounts?.sessionEnded(self)
            return
        }
        Task { await signOut() }
    }

    func loadMe() async {
        guard let api else { return }
        do {
            me = try await api.call { try await $0.getMe().ok.body.json }
            unreachable = false
            await syncTimeZone()
        } catch let e as APIError where e.code == "network" {
            unreachable = true
        } catch {}
    }

    /// `User.timezone` follows the device (the birthday morning notification comes at 08:00 in it): sent when it
    /// differs, so also after a trip.
    func syncTimeZone() async {
        guard let api, let me, let zone = UserTimeZone.update(server: me.timezone) else { return }
        if let user = try? await api.setTimeZone(zone) { self.me = user }
    }

    func updateName(_ name: String) async {
        guard let api else { return }
        if let user = try? await api.call({ try await $0.updateMe(body: .json(.init(displayName: name))).ok.body.json }) {
            me = user
        }
    }

    // MARK: Frames

    func loadFrames() async {
        guard let api else { return }
        do {
            frames = try await api.call { try await $0.listMyFrames().ok.body.json }
            framesLoaded = true
            framesError = nil
            unreachable = false
        } catch is CancellationError {
        } catch {
            framesError = errorMessage(error)
            if (error as? APIError)?.code == "network" { unreachable = true }
        }
    }

    func frame(_ id: String) -> FrameSummary? {
        frames.first { $0.id == id }
    }

    func updateFrame(_ summary: FrameSummary) {
        if let i = frames.firstIndex(where: { $0.id == summary.id }) {
            frames[i] = summary
        } else {
            frames.append(summary)
        }
    }

    func removeFrame(_ id: String) {
        frames.removeAll { $0.id == id }
    }

    // MARK: Feeds

    func loadFeeds() async {
        guard let api else { return }
        guard feedsEnabled else {
            feeds = []
            return
        }
        do {
            feeds = try await api.myFeeds()
            feedsLoaded = true
        } catch is CancellationError {
        } catch {
            if (error as? APIError)?.code == "network" { unreachable = true }
        }
    }

    func feed(_ id: String) -> FeedSummary? {
        feeds.first { $0.id == id }
    }

    func updateFeed(_ summary: FeedSummary) {
        if let i = feeds.firstIndex(where: { $0.id == summary.id }) {
            feeds[i] = summary
        } else {
            feeds.insert(summary, at: 0)
        }
    }

    func removeFeed(_ id: String) {
        feeds.removeAll { $0.id == id }
    }

    /// Open a feed in the Feeds tab, optionally on a section.
    func openFeed(_ id: String, section: FeedSection = .posts) {
        guard feedsEnabled else { return }
        tab = .feeds
        feedsPath = NavigationPath([FeedRoute(id: id, section: section, account: accountId)])
    }

    /// Where a tapped notification leads.
    func open(_ route: PushRoute) {
        switch route {
        case .feed(let feedId, let section):
            openFeed(feedId, section: FeedSection(section))
        case .feedPost(let feedId, let postId):
            openFeed(feedId)
            pendingFeedPost = FeedPostRoute(feedId: feedId, postId: postId, account: accountId)
        case .frameFeeds(let frameId):
            tab = .frames
            framesPath = NavigationPath([FrameRoute(id: frameId, section: .settings, account: accountId)])
        case .framePeople(let frameId):
            tab = .frames
            framesPath = NavigationPath([FrameRoute(id: frameId, section: .people, account: accountId)])
        case .send(let sendId):
            tab = .history
            historyPath = NavigationPath([HistoryRoute(sendId: sendId, account: accountId)])
        case .feeds:
            guard feedsEnabled else { return }
            tab = .feeds
            feedsPath = NavigationPath()
        case .photoSource(_, let frameId, let albumId, let feedId):
            openPhotoSourceTarget(frameId: frameId, albumId: albumId, feedId: feedId)
        case .googleImport(let importId):
            Task { await googleImport.show(importId: importId) }
        case .emailAddress(let addressId, let frameId, let feedId):
            openChannel(.emailAddress(addressId), frameId: frameId, feedId: feedId)
        case .telegramChat(let chatId, let frameId, let feedId):
            openChannel(.telegramChat(chatId), frameId: frameId, feedId: feedId)
        case .social(let r):
            openSocial(r)
        }
    }

    /// An inbox address or a Telegram chat to open once its frame's or feed's settings are on screen (from a
    /// notification or a toast). The card that lists it opens it and clears this.
    var channelFocus: ChannelFocus?

    /// Open the settings of the frame or feed an address or chat fills, with it on top. Without ids (a toast), its
    /// target is looked up first.
    func openChannel(_ focus: ChannelFocus, frameId: String?, feedId: String?) {
        if let feedId {
            guard feedsEnabled else { return }
            channelFocus = focus
            openFeed(feedId, section: .settings)
        } else if let frameId {
            channelFocus = focus
            tab = .frames
            framesPath = NavigationPath([FrameRoute(id: frameId, section: .settings, account: accountId)])
        } else if let api {
            Task {
                let target: ChannelTarget?
                switch focus {
                case .emailAddress(let id): target = (try? await api.emailAddress(id))?.channelTarget
                case .telegramChat(let id): target = (try? await api.telegramChat(id))?.channelTarget
                }
                switch target {
                case .frame(let id)?: openChannel(focus, frameId: id, feedId: nil)
                case .feed(let id)?: openChannel(focus, frameId: nil, feedId: id)
                case nil: break
                }
            }
        }
    }

    /// Where a source's album or feed is: the frame's photos (with the album open) or the feed's settings, else
    /// Settings → Photo sources.
    func openPhotoSourceTarget(frameId: String?, albumId: String?, feedId: String?) {
        if let feedId, feedsEnabled {
            openFeed(feedId, section: .settings)
        } else if let frameId {
            tab = .frames
            framesPath = NavigationPath([FrameRoute(id: frameId, section: .photos, albumId: albumId, account: accountId)])
        } else {
            tab = .settings
            showMyPhotoSources = true
        }
    }

    /// Settings opens "My photo sources" (from a notification).
    var showMyPhotoSources = false

    // MARK: Foreground

    func didBecomeActive() {
        guard phase != .launching else { return }
        live.kick()
        outbox.kick()
        googleImport.appBecameActive()
        PushRegistrar.shared.tidyBirthdayNotifications()
        // Discovery at most hourly, unless the app is blocked or nagging: then updating may have changed the answer.
        let rereadDiscovery = activeRefresh.shouldRefresh(force: appUpdate != .none)
        Task {
            if rereadDiscovery { await refreshDiscovery() }
            await loadFrames()
            await loadFeeds()
            await syncTimeZone()
            await social.refresh()
        }
    }

    // MARK: Live events

    private func handle(_ event: LiveEvent) {
        switch event {
        case .frameStatus(let frameId, let online, let lastSeenAt):
            if let i = frames.firstIndex(where: { $0.id == frameId }) {
                frames[i].online = online
                frames[i].lastSeenAt = lastSeenAt ?? frames[i].lastSeenAt
            }
        case .joinRequest(let frameId, let name):
            let frameName = frame(frameId)?.name ?? ""
            toasts.show(L("live.joinRequest", ["name": name ?? "?", "frame": frameName]), action: .init(title: L("common.review")) { [weak self] in
                guard let self else { return }
                self.tab = .frames
                self.framesPath = NavigationPath([FrameRoute(id: frameId, section: .people, account: self.accountId)])
            })
        case .membershipUpdated:
            Task { await loadFrames() }
        case .reaction(_, _, let reaction, let by):
            if let reaction, let by {
                toasts.show("\(reaction.emoji) \(by)")
            }
        case .invitationDecided(let frameId, let inv):
            // Tell the sender when a frame accepted or declined their event, wherever they are in the app.
            if inv.senderId == me?.id, inv.status == .accepted || inv.status == .declined {
                let frameName = frame(frameId)?.name ?? ""
                toasts.show(L(inv.status == .accepted ? "cal.decidedAccepted" : "cal.decidedDeclined", ["frame": frameName, "title": inv.title]))
            }
        case .feedReaction(_, _, _, let reaction, let by):
            if let reaction, let by {
                toasts.show("\(reaction.emoji) \(by)")
            }
        case .feedJoinRequest(let feedId, let name):
            let feedName = feed(feedId)?.name ?? ""
            toasts.show(L("ios.feeds.live.joinRequest", ["name": name ?? "?", "feed": feedName]), action: .init(title: L("common.review")) { [weak self] in
                self?.openFeed(feedId, section: .members)
            })
        case .subscriptionRequest(let feedId, let sub):
            let feedName = sub?.feed.name ?? feed(feedId)?.name ?? ""
            toasts.show(L("ios.feeds.live.subscriptionRequest", ["frame": sub?.frame.name ?? "?", "feed": feedName]),
                        action: .init(title: L("common.review")) { [weak self] in
                self?.openFeed(feedId, section: .frames)
            })
        case .subscriptionUpdated(_, _, let sub):
            // The frame side hears how its request went: a first activation just now is an approval (a resume keeps
            // the first `activated_at`).
            if let sub, sub.canManage == true {
                let justActivated = sub.status == .active && (sub.activatedAt.map { abs($0.timeIntervalSinceNow) < 120 } ?? false)
                if sub.status == .rejected || justActivated {
                    toasts.show(L(justActivated ? "ios.feeds.live.approved" : "ios.feeds.live.rejected", ["feed": sub.feed.name, "frame": sub.frame.name]))
                }
            }
            Task { await loadFeeds() }
        case .feedMembershipUpdated:
            Task {
                await loadFeeds()
                // Family chats follow family membership (README §5.18).
                if social.features.chats { await social.loadConversations() }
            }
        case .birthdaySuggested(let feedId, let b):
            guard let b else { break }
            toasts.show(L("ios.bday.live.suggested", ["name": b.createdByName ?? L("ios.bday.formerMember"), "person": b.displayName]),
                        action: .init(title: L("common.review")) { [weak self] in
                self?.openFeed(feedId, section: .birthdays)
            })
        case .birthdayDecided(let feedId, let b):
            guard let b, b.status == .approved || b.status == .rejected else { break }
            toasts.show(L(b.status == .approved ? "ios.bday.live.approved" : "ios.bday.live.rejected", ["person": b.displayName]),
                        action: .init(title: L("ios.bday.tab")) { [weak self] in
                self?.openFeed(feedId, section: .birthdays)
            })
        case .birthdayGreeting(let feedId, let g):
            // One greeting is recorded in every feed that lists the person: say it once.
            guard let g, feed(feedId) != nil, shownGreetings.insert("\(g.frameId)|\(g.birthdayName)|\(g.date)").inserted else { break }
            toasts.show(L("ios.bday.greeting", ["frame": g.frameName, "name": g.birthdayName]))
        case .birthdayToday(_, let refs):
            Task { await announceBirthdays(refs) }
        case .photoSourceUpdated(let s):
            noteSourceState(s)
        case .googleImportUpdated:
            googleImport.handle(event)
        case .emailSenderPending(let addressId, let pending):
            // Someone new is waiting for a manager's OK (the push says the same without an open websocket).
            guard let pending, pending.messages.count <= 1, shownPendingSenders.insert(pending.id).inserted else { break }
            toasts.show(EmailText.pending(pending).text, action: .init(title: L("common.review")) { [weak self] in
                self?.openChannel(.emailAddress(addressId), frameId: nil, feedId: nil)
            })
        case .telegramChatUpdated(let c):
            noteChatState(c)
        case .social(let e):
            handleSocial(e)
        case .sendUpdated, .reply, .feedReply, .feedPostUpdated, .birthdayUpdated, .birthdayRemoved, .photoSourceRemoved,
             .emailAddressUpdated, .emailAddressRemoved, .emailSenderDecided, .telegramChatRemoved, .telegramCodeUpdated,
             .telegramLinkUpdated:
            break
        }
        events.post(event)
    }

    private var shownGreetings: Set<String> = []
    private var shownPendingSenders: Set<String> = []
    private var chatStates: [String: String] = [:]

    /// A Telegram chat you own just started needing you: say so once, with the way to it.
    private func noteChatState(_ c: TelegramChat) {
        guard c.isOwner == true else { return }
        let before = chatStates[c.id]
        chatStates[c.id] = c.status
        guard let before, before != c.status, c.state == .needsAttention else { return }
        let t = c.target
        toasts.show(L("ios.tg.live.attention", ["name": c.title]), action: .init(title: L("common.review")) { [weak self] in
            self?.openChannel(.telegramChat(c.id), frameId: t.frameId, feedId: t.feedId)
        })
    }

    /// A source you set up just started needing you: say so once, with the way to it (no toasts for new photos).
    private func noteSourceState(_ s: PhotoSource) {
        guard s.isOwner == true else { return }
        let before = sourceStates[s.id]
        sourceStates[s.id] = s.status
        guard let before, before != s.status, s.state == .needsAttention else { return }
        let t = s.target
        toasts.show(L("ios.ps.live.attention", ["name": s.name]), action: .init(title: L("common.review")) { [weak self] in
            self?.openPhotoSourceTarget(frameId: t.frameId, albumId: t.albumId, feedId: t.feedId)
        })
    }

    /// The morning notice while the app is open: "Birthday today 🎈 Oma, Lotte".
    private func announceBirthdays(_ refs: [LiveEvent.BirthdayRef]) async {
        guard let api, !refs.isEmpty else { return }
        var names: [String] = []
        for r in refs.prefix(4) {
            if let b = try? await api.feedBirthday(r.feedId, birthdayId: r.birthdayId), !names.contains(b.displayName) { names.append(b.displayName) }
        }
        guard !names.isEmpty else { return }
        let feedIds = Set(refs.map(\.feedId))
        toasts.show(L("ios.bday.live.today", ["names": names.joined(separator: ", ")]), action: .init(title: L("ios.bday.tab")) { [weak self] in
            if feedIds.count == 1, let id = feedIds.first { self?.openFeed(id, section: .birthdays) } else { self?.open(.feeds) }
        })
    }

    // MARK: Deep links

    func open(_ url: URL) {
        // `app.kinframe:/google-import?…`: the end of Google's consent, when it reached the app instead of the session.
        if googleImport.handleReturn(url) { return }
        guard let link = DeepLink.parse(url) else { return }
        // A link of another server: the account signed in there handles it, else that server is added first
        // (docs/spec/multi-server.md §2.1).
        if let accounts, isPrimary, accounts.route(link) { return }
        if link.needsServerSwitch(from: server?.url), let target = link.server {
            serverSwitch = ServerSwitch(link: link, server: target)
            return
        }
        route(link)
    }

    func route(_ link: DeepLink) {
        if phase == .main, api != nil {
            activeLink = link
        } else {
            // Handled after sign-in.
            pendingLink = link
        }
    }

    /// Called when onboarding finished or main appeared.
    func consumePendingLink() {
        guard phase == .main, let link = pendingLink else { return }
        pendingLink = nil
        activeLink = link
    }

    /// The person agreed to switch to the link's server.
    func confirmSwitch(_ request: ServerSwitch) async {
        serverSwitch = nil
        do {
            let discovery = try await DiscoveryClient.fetch(request.server)
            await signOut(keepServer: false)
            await choose(server: request.server, discovery: discovery)
            pendingLink = request.link
            Onboarding.step = .signIn
            phase = .onboarding
        } catch {
            toasts.show(L("ios.server.unreachable"))
        }
    }

    func finishOnboarding() {
        Onboarding.step = .done
        phase = .main
        consumePendingLink()
    }
}

/// Name the server gives new accounts when the token carried none (web: PLACEHOLDER_NAME).
let PLACEHOLDER_NAME = "New member"

/// Where onboarding resumes: persisted per step (product spec §4.1).
enum Onboarding {
    enum Step: String {
        case welcome, server, signIn, name, firstFrame, done
    }

    private static let key = "onboardingStep"

    static var step: Step {
        get { UserDefaults.standard.string(forKey: key).flatMap(Step.init(rawValue:)) ?? .welcome }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: key) }
    }

    static func reset() {
        step = AccountStore.load().isEmpty ? .welcome : .signIn
    }
}

/// Fan-out of live events to the screens that are open.
@MainActor
final class EventHub {
    private var handlers: [UUID: (LiveEvent) -> Void] = [:]

    func subscribe(_ handler: @escaping (LiveEvent) -> Void) -> UUID {
        let id = UUID()
        handlers[id] = handler
        return id
    }

    func unsubscribe(_ id: UUID) {
        handlers[id] = nil
    }

    func post(_ event: LiveEvent) {
        for h in handlers.values { h(event) }
    }
}

extension View {
    /// Run `handler` for live events while this view is on screen.
    func onLiveEvent(_ hub: EventHub, perform handler: @escaping (LiveEvent) -> Void) -> some View {
        modifier(LiveEventModifier(hub: hub, handler: handler))
    }
}

private struct LiveEventModifier: ViewModifier {
    let hub: EventHub
    let handler: (LiveEvent) -> Void
    @State private var token: UUID?

    func body(content: Content) -> some View {
        content
            .onAppear { token = hub.subscribe(handler) }
            .onDisappear { token.map(hub.unsubscribe) }
    }
}
