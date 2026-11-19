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
}

enum FrameSection: String, Hashable, CaseIterable {
    case overview, photos, people, calendar, settings
}

struct MainTabs: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var app = app
        TabView(selection: $app.tab) {
            FramesTab()
                .tabItem { Label(L("nav.frames"), systemImage: "photo.on.rectangle") }
                .tag(AppModel.Tab.frames)
            // Only when the server has feeds (discovery `feeds`).
            if app.feedsEnabled {
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
        .onChange(of: app.feedsEnabled) { _, on in
            if !on, app.tab == .feeds { app.tab = .frames }
        }
        .sheet(item: $app.activeLink) { link in
            DeepLinkSheet(link: link)
        }
        // "Add from Google Photos": waiting for Google, progress and the result (docs/spec/photo-sources.md §7.3).
        .sheet(isPresented: Binding(get: { app.googleImport.presented }, set: { if !$0 { app.googleImport.close() } })) {
            GoogleImportSheet(controller: app.googleImport)
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
private struct DeepLinkSheet: View {
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
