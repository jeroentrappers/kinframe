import KinframeKit
import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var app
    @State private var languageVersion = L10n.code
    #if DEBUG
    @State private var sharePreview: ShareModel?
    #endif

    var body: some View {
        ZStack {
            switch app.phase {
            case .launching:
                LaunchView()
            case .onboarding:
                OnboardingFlow()
                    .transition(.opacity)
            case .main:
                MainTabs()
                    .transition(.opacity)
            }
            // Below the server's minimum build nothing else is usable (README §5.15).
            if case .blocking(let storeURL) = app.appUpdate {
                AppUpdateView(storeURL: storeURL)
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: app.phase)
        .animation(.easeInOut(duration: 0.25), value: app.appUpdate.isBlocking)
        .onChange(of: app.appUpdate.isBlocking) { _, blocked in
            if blocked {
                app.activeLink = nil
                app.serverSwitch = nil
            }
        }
        .overlay(alignment: .bottom) { ToastOverlay(center: app.toasts) }
        .tint(Palette.primary)
        .id(languageVersion)
        .task { await app.launch() }
        #if DEBUG
        // -kfSharePreview /a.jpg,/b.mp4: show the share extension's composer in the app (screenshots).
        // With -kfComposeFrames <ids> and -kfAutoSend YES it also picks those frames and sends.
        .sheet(item: $sharePreview) { model in
            ShareRootView(model: model).task {
                await model.start()
                if let ids = UserDefaults.standard.string(forKey: "kfComposeFrames") {
                    model.compose.frameIds = Set(ids.split(separator: ",").map(String.init))
                }
                if UserDefaults.standard.bool(forKey: "kfAutoSend") { await model.send() }
            }
        }
        .task {
            try? await Task.sleep(for: .seconds(2))
            if let list = UserDefaults.standard.string(forKey: "kfSharePreview") {
                sharePreview = ShareModel(context: nil, preview: list.split(separator: ",").map { URL(fileURLWithPath: String($0)) })
            }
        }
        #endif
        .onReceive(NotificationCenter.default.publisher(for: .kinframeLanguageChanged)) { _ in
            languageVersion = L10n.code + UUID().uuidString
        }
        .alert(
            L("ios.switch.title"),
            isPresented: Binding(get: { app.serverSwitch != nil }, set: { if !$0 { app.serverSwitch = nil } }),
            presenting: app.serverSwitch
        ) { request in
            Button(L("ios.switch.confirm")) { Task { await app.confirmSwitch(request) } }
            Button(L("common.cancel"), role: .cancel) { app.serverSwitch = nil }
        } message: { request in
            Text(L("ios.switch.body", ["server": ServerAddress.display(request.server)]))
        }
    }
}

extension Notification.Name {
    static let kinframeLanguageChanged = Notification.Name("kinframeLanguageChanged")
}

private struct LaunchView: View {
    var body: some View {
        LogoMark(size: 72)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color("LaunchBackground").ignoresSafeArea())
    }
}

/// A frame to open in the Frames tab, optionally on a section.
struct FrameRoute: Hashable {
    var id: String
    var section: FrameSection = .overview
    /// Open this album in the photos (a photo source's notification).
    var albumId: String? = nil
    /// The account (`AccountRecord.id`) the screen talks to; nil is the primary account.
    var account: String? = nil
}

enum FrameSection: String, Hashable, CaseIterable {
    case overview, photos, people, calendar, settings
}

struct MainTabs: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts

    var body: some View {
        @Bindable var app = app
        @Bindable var accounts = accounts
        TabView(selection: $app.tab) {
            // Family social: Home (timeline and strip) and Chats, each only when the server has it.
            // Each tab shows when any account has it (docs/spec/multi-server.md §2.2).
            if accounts.features.home {
                HomeTab()
                    .tabItem { Label(L("social.homeTitle"), systemImage: "house") }
                    .tag(AppModel.Tab.home)
            }
            if accounts.features.chats {
                ChatsTab()
                    .tabItem { Label(L("chat.title"), systemImage: "bubble.left.and.bubble.right") }
                    .badge(accounts.unreadChats)
                    .tag(AppModel.Tab.chats)
            }
            FramesTab()
                .tabItem { Label(L("nav.frames"), systemImage: "photo.on.rectangle") }
                .tag(AppModel.Tab.frames)
            // Only when a server has feeds (discovery `feeds`).
            if accounts.feedsEnabled {
                FeedsTab()
                    .tabItem { Label(L("ios.feeds.title"), systemImage: "rectangle.stack.badge.person.crop") }
                    .tag(AppModel.Tab.feeds)
            }
            ComposeTab()
                .tabItem { Label(L("nav.send"), systemImage: "paperplane") }
                .tag(AppModel.Tab.send)
            HistoryTab()
                .tabItem { Label(L("nav.history"), systemImage: "clock.arrow.circlepath") }
                .tag(AppModel.Tab.history)
            SettingsTab()
                .tabItem { Label(L("nav.settings"), systemImage: "gearshape") }
                .tag(AppModel.Tab.settings)
        }
        .adaptiveTabStyle()
        .onAppear { app.consumePendingLink() }
        .onChange(of: accounts.feedsEnabled) { _, on in
            if !on, app.tab == .feeds { app.tab = .frames }
        }
        .onChange(of: accounts.features) { _, f in
            if !f.home, app.tab == .home { app.tab = .frames }
            if !f.chats, app.tab == .chats { app.tab = .frames }
        }
        // Home is the start once a server has family social (unless something else was opened already).
        .task(id: accounts.features.home) {
            if accounts.features.home, app.tab == .frames, app.framesPath.isEmpty { app.tab = .home }
        }
        .sheet(item: Binding(get: { app.social.sheet }, set: { app.social.sheet = $0 })) { sheet in
            switch sheet {
            case .freshSend(let frameId, let requestId): FreshSendSheet(frameId: frameId, requestId: requestId)
            case .weeklySummary(let week): WeeklySummarySheet(weekOf: week)
            case .operatorReports: ReportsView(feedId: nil)
            case .feedReports(let feedId): ReportsView(feedId: feedId)
            }
        }
        .sheet(item: Binding(get: { app.social.personSheet }, set: { app.social.personSheet = $0 })) { route in
            NavigationStack {
                PersonView(userId: route.userId, inSheet: true)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { app.social.personSheet = nil } } }
            }
        }
        .sheet(item: $app.activeLink) { link in
            DeepLinkSheet(link: link)
        }
        // Several servers (docs/spec/multi-server.md §2.1): the other accounts' sheets, "Add a server", and links of
        // servers the app isn't signed in to.
        .background(SecondaryAccountPresenters())
        .background {
            Color.clear.sheet(item: $accounts.adding) { request in
                AddServerSheet(request: request)
            }
        }
        .background {
            Color.clear.sheet(item: $accounts.remoteFollow) { request in
                if let api = request.model.api {
                    RemoteFollowSheet(api: api, inviteURL: request.inviteURL).environment(request.model)
                }
            }
        }
        .alert(
            L("servers.account.foreignTitle"),
            isPresented: Binding(get: { accounts.foreignLink != nil }, set: { if !$0 { accounts.foreignLink = nil } }),
            presenting: accounts.foreignLink
        ) { request in
            Button(L("servers.account.foreignSignIn")) {
                accounts.foreignLink = nil
                accounts.startAdding(server: request.server, link: request.link)
            }
            // A family invite can also be followed by a frame you manage here, without an account there.
            if let m = accounts.followAccount(for: request.link), let url = Accounts.inviteURL(request.link, server: request.server) {
                Button(L("servers.account.foreignFollow")) {
                    accounts.foreignLink = nil
                    accounts.remoteFollow = .init(model: m, inviteURL: url)
                }
            }
            Button(L("common.cancel"), role: .cancel) { accounts.foreignLink = nil }
        } message: { request in
            Text(L(request.link.isInvite || request.link.isFeedInvite ? "servers.account.foreignFamily" : "servers.account.foreignFrame",
                   ["server": ServerAddress.display(request.server)]))
        }
        // "Add from Google Photos": waiting for Google, progress and the result (docs/spec/photo-sources.md §7.3).
        // The other accounts' imports show through `SecondaryAccountPresenters`.
        .sheet(isPresented: Binding(get: { app.googleImport.presented }, set: { if !$0 { app.googleImport.close() } })) {
            GoogleImportSheet(controller: app.googleImport)
        }
        // Leaving: a keep request to answer, over whatever is on screen (a notification, a toast).
        .background {
            Color.clear.sheet(item: Binding(get: { app.leaving.answering }, set: { app.leaving.answering = $0 })) { ref in
                if let api = app.api {
                    KeepRequestAnswerSheet(api: api, requestId: ref.id) { app.historyVersion += 1 }
                }
            }
        }
        .overlay(alignment: .top) {
            VStack(spacing: 6) {
                if case .banner(let build, let storeURL) = app.appUpdate {
                    AppUpdateBanner(recommendedBuild: build, storeURL: storeURL)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
                if app.unreachable {
                    OfflineBanner()
                }
            }
            .animation(.easeInOut(duration: 0.2), value: app.appUpdate)
        }
    }
}

private extension View {
    /// iPad: the tab bar can also be shown as a sidebar (iOS 18+). iPhone keeps the tab bar.
    @ViewBuilder
    func adaptiveTabStyle() -> some View {
        if #available(iOS 18.0, *) {
            tabViewStyle(.sidebarAdaptable)
        } else {
            self
        }
    }
}

extension DeepLink: @retroactive Identifiable {
    public var id: String {
        switch self {
        case .frameCode(_, let code): "f/\(code)"
        case .invite(_, let token): "i/\(token)"
        case .feedInvite(_, let token): "fi/\(token)"
        }
    }
}

/// Frame codes, frame invites and feed invites from links.
struct DeepLinkSheet: View {
    @Environment(AppModel.self) private var app
    let link: DeepLink

    var body: some View {
        NavigationStack {
            if let api = app.api {
                switch link {
                case .frameCode(_, let code):
                    AddFrameView(api: api, prefill: code)
                case .invite(_, let token):
                    InviteView(api: api, token: token)
                case .feedInvite(_, let token):
                    FeedInviteView(api: api, source: .token(token))
                }
            }
        }
    }
}

private struct OfflineBanner: View {
    var body: some View {
        Label(L("boot.unreachableTitle"), systemImage: "wifi.slash")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Palette.warn)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Capsule().fill(Palette.warnSoft))
            .padding(.top, 4)
            .accessibilityLabel(L("boot.unreachableTitle") + ". " + L("boot.unreachableBody"))
    }
}

#if DEBUG
extension ShareModel: @retroactive Identifiable {}
#endif
