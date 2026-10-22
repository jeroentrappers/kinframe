import KinframeKit
import SwiftUI

/// App-wide state: the server, the session, the frame list, live events and the upload queue.
@MainActor
@Observable
final class AppModel {
    enum Phase: Equatable {
        case launching
        case onboarding
        case main
    }

    enum Tab: Hashable {
        case frames, send, history, settings
    }

    /// A deep link that arrived for another server, waiting for the person to confirm switching.
    struct ServerSwitch: Identifiable {
        let link: DeepLink
        let server: URL
        var id: String { server.absoluteString }
    }

    var phase: Phase = .launching
    var server: ServerConfig?
    var me: User?
    var frames: [FrameSummary] = []
    var framesLoaded = false
    var framesError: String?
    var liveStatus: LiveSocket.Status = .idle
    var unreachable = false

    var tab: Tab = .frames
    var framesPath = NavigationPath()
    var historyPath = NavigationPath()
    /// Frame code or invite being handled (sheet), from a deep link.
    var pendingLink: DeepLink?
    var activeLink: DeepLink?
    var serverSwitch: ServerSwitch?
    /// Frames preselected for the next compose.
    var composeFrameIds: Set<String> = []
    /// Bumped when history should reload (a send was created, live events).
    var historyVersion = 0
    var expired = false

    let auth = AuthService()
    let toasts = ToastCenter()
    let outbox: OutboxMonitor
    private(set) var api: API?
    private let live = LiveSocket()
    /// Live events for screens that care (history, frame detail).
    let events = EventHub()

    init() {
        L10n.bootstrap()
        outbox = OutboxMonitor()
        auth.onSignedOut = { [weak self] in self?.sessionEnded() }
        live.onEvent = { [weak self] in self?.handle($0) }
        live.onStatus = { [weak self] in self?.liveStatus = $0 }
        outbox.onSent = { [weak self] send in
            self?.historyVersion += 1
            self?.events.post(.sendUpdated(sendId: send.id))
        }
        // Set up the session right away: the system may launch the app in the background just to finish uploads.
        server = ServerStore.load()
        auth.configure(server?.discovery)
        if let server, auth.isSignedIn {
            prepare(server)
        }
    }

    var discovery: Discovery? { server?.discovery }
    var limits: Limits { discovery?.limits ?? .defaults }

    // MARK: Launch

    func launch() async {
        guard phase == .launching else { return }
        #if DEBUG
        await DebugLaunch.beforeLaunch(self)
        defer { DebugLaunch.afterLaunch(self) }
        #endif
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
        }
    }

    /// Re-read discovery so limits and auth settings stay current.
    func refreshDiscovery() async {
        guard let server else { return }
        if let fresh = try? await DiscoveryClient.fetch(server.url) {
            let updated = ServerConfig(url: server.url, discovery: fresh)
            if updated != server {
                self.server = updated
                ServerStore.save(updated)
                auth.configure(fresh)
            }
            unreachable = false
        }
    }

    // MARK: Server and session

    /// Remember a validated server (onboarding). Signs out of the previous one.
    func choose(server url: URL, discovery: Discovery) async {
        if let current = server, current.url != url {
            await signOut(keepServer: false)
        }
        let config = ServerConfig(url: url, discovery: discovery)
        server = config
        ServerStore.save(config)
        auth.configure(discovery)
    }

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
        outbox.configure(server: server.apiBase, tokens: auth)
    }

    private func connect(_ server: ServerConfig) {
        if api == nil || api?.baseURL != server.apiBase { prepare(server) }
        if let api { live.start(api: api) }
        Task { await loadFrames() }
    }

    func signOut(keepServer: Bool = true) async {
        live.stop()
        PushRegistrar.shared.unregister(api: api)
        await auth.signOut()
        api = nil
        me = nil
        frames = []
        framesLoaded = false
        framesPath = NavigationPath()
        historyPath = NavigationPath()
        tab = .frames
        outbox.configure(server: nil, tokens: nil)
        Onboarding.reset()
        if !keepServer {
            server = nil
            ServerStore.save(nil)
        }
        phase = .onboarding
    }

    /// The refresh token was rejected: back to sign-in, keeping the server.
    private func sessionEnded() {
        guard phase == .main else { return }
        expired = true
        Task { await signOut() }
    }

    func loadMe() async {
        guard let api else { return }
        do {
            me = try await api.call { try await $0.getMe().ok.body.json }
            unreachable = false
        } catch let e as APIError where e.code == "network" {
            unreachable = true
        } catch {}
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

    // MARK: Foreground

    func didBecomeActive() {
        guard phase != .launching else { return }
        live.kick()
        outbox.kick()
        Task {
            await refreshDiscovery()
            await loadFrames()
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
                self.framesPath = NavigationPath([FrameRoute(id: frameId, section: .people)])
            })
        case .membershipUpdated:
            Task { await loadFrames() }
        case .reaction(_, _, let reaction, let by):
            if let reaction, let by {
                toasts.show("\(reaction.emoji) \(by)")
            }
        case .sendUpdated, .reply:
            break
        }
        events.post(event)
    }

    // MARK: Deep links

    func open(_ url: URL) {
        guard let link = DeepLink.parse(url) else { return }
        if link.needsServerSwitch(from: server?.url), let target = link.server {
            serverSwitch = ServerSwitch(link: link, server: target)
            return
        }
        route(link)
    }

    private func route(_ link: DeepLink) {
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
        step = ServerStore.load() == nil ? .welcome : .signIn
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
