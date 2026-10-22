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
        }
        .animation(.easeInOut(duration: 0.25), value: app.phase)
        .overlay(alignment: .bottom) { ToastOverlay(center: app.toasts) }
        .tint(Palette.primary)
        .id(languageVersion)
        .task { await app.launch() }
        #if DEBUG
        // -kfSharePreview /a.jpg,/b.mp4: show the share extension's composer in the app (screenshots).
        .sheet(item: $sharePreview) { model in ShareRootView(model: model).task { await model.start() } }
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
}

enum FrameSection: String, Hashable, CaseIterable {
    case overview, photos, people, settings
}

struct MainTabs: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var app = app
        TabView(selection: $app.tab) {
            FramesTab()
                .tabItem { Label(L("nav.frames"), systemImage: "photo.on.rectangle") }
                .tag(AppModel.Tab.frames)
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
        .onAppear { app.consumePendingLink() }
        .sheet(item: $app.activeLink) { link in
            DeepLinkSheet(link: link)
        }
        .overlay(alignment: .top) {
            if app.unreachable {
                OfflineBanner()
            }
        }
    }
}

extension DeepLink: @retroactive Identifiable {
    public var id: String {
        switch self {
        case .frameCode(_, let code): "f/\(code)"
        case .invite(_, let token): "i/\(token)"
        }
    }
}

/// Frame codes and invites from links.
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
