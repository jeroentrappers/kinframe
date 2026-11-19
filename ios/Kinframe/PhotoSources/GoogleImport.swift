import AuthenticationServices
import KinframeKit
import SwiftUI
import UIKit

/// "Add from Google Photos" (docs/spec/photo-sources.md §7.3, iOS row): Google's consent in an
/// `ASWebAuthenticationSession` that ends at `app.kinframe:/google-import`, then Google's picker opened with
/// `UIApplication.open` (the Google Photos app when installed, Safari otherwise), then progress by polling and the
/// websocket until done. The rules are `GoogleImportFlow` in KinframeKit; this performs its effects. One import at a
/// time, app-wide, so a relaunch, a notification or the websocket can find it again.
@MainActor
@Observable
final class GoogleImportController {
    private(set) var flow = GoogleImportFlow()
    /// Where the photos go, and its name for the sheet's title.
    private(set) var target: SourceTargetRef?
    private(set) var targetName = ""
    var presented = false
    var error: String?
    /// Called when an import added photos, so the album or feed on screen reloads.
    var onFinished: ((SourceTargetRef?) -> Void)?

    private var api: API?
    private var poller: Task<Void, Never>?
    private var webSession: ASWebAuthenticationSession?
    private let anchor = WebAuthAnchor()
    /// `onFinished` ran for this import.
    private var reported = false

    func configure(api: API?) {
        self.api = api
        if api == nil { reset() }
    }

    var active: Bool { !(flow.phase == .idle || flow.isFinished) }

    /// Start an import into an album or a feed.
    func start(_ target: SourceTargetRef, name: String) {
        guard !active else {
            presented = true
            return
        }
        self.target = target
        targetName = name
        error = nil
        reported = false
        presented = true
        perform(flow.handle(.start))
    }

    /// After a relaunch: pick up an import that was waiting for the picker or copying.
    func resumeIfNeeded() async {
        guard let api, !active, let imports = try? await api.googleImports(), let open = GoogleImportFlow.resumable(imports) else { return }
        target = Self.ref(open.target)
        targetName = open.target.albumName ?? open.target.feedName ?? ""
        flow = GoogleImportFlow()
        perform(flow.handle(.update(open)))
        presented = true
    }

    /// The tapped `google_import_done` notification.
    func show(importId: String) async {
        guard let api, let imp = try? await api.googleImport(importId) else { return }
        if flow.importId != importId {
            flow = GoogleImportFlow()
            target = Self.ref(imp.target)
            targetName = imp.target.albumName ?? imp.target.feedName ?? ""
        }
        perform(flow.handle(.update(imp)))
        presented = true
    }

    func handle(_ event: LiveEvent) {
        guard case .googleImportUpdated(let imp) = event, imp.id == flow.importId else { return }
        perform(flow.handle(.update(imp)))
    }

    /// `app.kinframe:/google-import?…` reached the app through `onOpenURL` (the browser session normally takes it).
    func handleReturn(_ url: URL) -> Bool {
        guard let r = GoogleImportFlow.parseReturn(url) else { return false }
        webSession?.cancel()
        webSession = nil
        perform(flow.handle(.consentReturned(importId: r.importId, status: r.status)))
        return true
    }

    func appBecameActive() {
        perform(flow.handle(.appActive))
    }

    func cancel() {
        perform(flow.handle(.cancel))
    }

    func reopenPicker() {
        perform(flow.reopenPicker())
    }

    func close() {
        presented = false
        if flow.isFinished { reset() }
    }

    /// Unsend what the import added (its sends), from the summary's Undo.
    func undo() async {
        guard let api else { return }
        for id in flow.sendIds {
            _ = try? await api.call { try await $0.unsend(path: .init(sendId: id)).noContent }
        }
        onFinished?(target)
        reset()
        presented = false
    }

    private func reset() {
        poller?.cancel()
        poller = nil
        webSession?.cancel()
        webSession = nil
        flow = GoogleImportFlow()
        target = nil
        error = nil
        reported = false
    }

    // MARK: Effects

    private func perform(_ effects: [GoogleImportFlow.Effect]) {
        for effect in effects {
            switch effect {
            case .post: Task { await post() }
            case .openConsent(let url): openConsent(url)
            case .openPicker(let url): UIApplication.shared.open(url)
            case .refresh: Task { await refresh() }
            case .cancelOnServer: Task { await cancelOnServer() }
            }
        }
        updatePolling()
        if !reported, case .finished(let outcome) = flow.phase, case .done(let imported, _, _, _) = outcome, imported > 0 {
            reported = true
            onFinished?(target)
        }
    }

    private func post() async {
        guard let api, let target else { return }
        do {
            let imp = try await api.startGoogleImport(target)
            perform(flow.handle(.update(imp)))
        } catch {
            self.error = errorMessage(error)
            perform(flow.handle(.startFailed))
        }
    }

    private func refresh() async {
        guard let api, let id = flow.importId, let imp = try? await api.googleImport(id) else { return }
        perform(flow.handle(.update(imp)))
    }

    private func cancelOnServer() async {
        guard let api, let id = flow.importId else { return }
        _ = try? await api.cancelGoogleImport(id)
    }

    private func updatePolling() {
        if flow.needsPolling {
            guard poller == nil else { return }
            poller = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(GoogleImportFlow.pollSeconds))
                    guard let self, !Task.isCancelled else { return }
                    await self.refresh()
                }
            }
        } else {
            poller?.cancel()
            poller = nil
        }
    }

    /// Google refuses embedded web views, so consent runs in a real browser session; it ends when Google's redirect
    /// reaches `app.kinframe:/google-import`.
    private func openConsent(_ url: URL) {
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: GoogleImportFlow.callbackScheme) { [weak self] callback, error in
            let returned = callback.flatMap(GoogleImportFlow.parseReturn)
            let dismissed = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
            Task { @MainActor in
                guard let self else { return }
                self.webSession = nil
                if let returned {
                    self.perform(self.flow.handle(.consentReturned(importId: returned.importId, status: returned.status)))
                } else if dismissed {
                    self.perform(self.flow.handle(.consentDismissed))
                } else {
                    self.perform(self.flow.handle(.appActive))
                }
            }
        }
        session.presentationContextProvider = anchor
        // Google remembers the account: the next import only asks which one to use (spec §7.4).
        session.prefersEphemeralWebBrowserSession = false
        webSession = session
        if !session.start() {
            webSession = nil
            perform(flow.handle(.consentDismissed))
        }
    }

    private static func ref(_ t: PhotoSourceTarget) -> SourceTargetRef? {
        switch t.kind {
        case .album:
            guard let frame = t.frameId, let album = t.albumId else { return nil }
            return .album(frameId: frame, albumId: album)
        case .feed:
            return t.feedId.map { .feed($0) }
        }
    }
}

/// The window the browser session sheet is shown over.
final class WebAuthAnchor: NSObject, ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            return scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first.map { UIWindow(windowScene: $0) } ?? UIWindow()
        }
    }
}

/// The import's sheet: waiting for Google, progress, the result, cancel and undo.
struct GoogleImportSheet: View {
    @Environment(AppModel.self) private var app
    let controller: GoogleImportController

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Image(systemName: icon)
                    .font(.system(size: 44, weight: .regular))
                    .foregroundStyle(Palette.primary)
                    .accessibilityHidden(true)
                    .padding(.top, 24)
                content
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 24)
            .frame(maxWidth: .infinity)
            .pageBackground()
            .navigationTitle(L("ios.gi.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if controller.flow.canCancel {
                        Button(L("ios.gi.cancel"), role: .destructive) { controller.cancel() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(controller.flow.isFinished || controller.flow.phase == .idle ? L("common.done") : L("ios.gi.hide")) {
                        controller.close()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(false)
    }

    private var icon: String {
        switch controller.flow.phase {
        case .finished(.done): "checkmark.circle"
        case .finished(.failed), .finished(.expired): "exclamationmark.triangle"
        case .finished(.cancelled): "xmark.circle"
        default: "photo.on.rectangle.angled"
        }
    }

    @ViewBuilder
    private var content: some View {
        let into = controller.targetName.isEmpty ? "" : L("ios.gi.into", ["name": controller.targetName])
        switch controller.flow.phase {
        case .idle:
            if let error = controller.error {
                Callout(error, kind: .error)
            }
        case .starting:
            ProgressView()
            Text(L("ios.gi.starting")).foregroundStyle(Palette.textMuted)
        case .consent:
            ProgressView()
            Text(L("ios.gi.consent")).multilineTextAlignment(.center).foregroundStyle(Palette.text)
            Text(into).font(.footnote).foregroundStyle(Palette.textMuted)
        case .picking(let url):
            ProgressView()
            Text(L("ios.gi.waiting")).font(.headline).multilineTextAlignment(.center)
            Text(L("ios.gi.waitingHint")).font(.subheadline).multilineTextAlignment(.center).foregroundStyle(Palette.textMuted)
            if url != nil {
                Button { controller.reopenPicker() } label: { Label(L("ios.gi.openAgain"), systemImage: "arrow.up.forward.app") }
                    .buttonStyle(.kf(.secondary))
            }
        case .importing(let picked, let done):
            Text(Lp("ios.gi.adding", count: picked)).font(.headline).multilineTextAlignment(.center)
            ProgressView(value: Double(done), total: Double(max(picked, 1)))
                .tint(Palette.accent)
            Text(L("ios.gi.progress", ["done": "\(done)", "total": "\(picked)"])).font(.subheadline.monospacedDigit()).foregroundStyle(Palette.textMuted)
            Text(L("ios.gi.canLeave")).font(.footnote).foregroundStyle(Palette.textFaint).multilineTextAlignment(.center)
        case .finished(let outcome):
            let line = GoogleImportText.outcome(outcome)
            Text(line.text).font(.headline).multilineTextAlignment(.center)
            Text(into).font(.footnote).foregroundStyle(Palette.textMuted)
            switch outcome {
            case .done(let imported, _, _, _) where imported > 0 && !controller.flow.sendIds.isEmpty:
                Button(L("common.undo")) { Task { await controller.undo() } }.buttonStyle(.kf(.ghost))
            case .failed, .expired:
                if let target = controller.target {
                    Button(L("ios.gi.tryAgain")) { controller.start(target, name: controller.targetName) }.buttonStyle(.kf(.primary))
                }
            default:
                EmptyView()
            }
        }
    }
}
