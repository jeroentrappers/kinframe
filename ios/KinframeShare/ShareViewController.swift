import KinframeKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Share → kinframe": a compact composer (frames and a caption) for photos and videos shared from Photos and other
/// apps. The files go into the App Group's upload queue and the upload starts on a background session, so the sheet
/// closes right away; the app finishes the send when the system wakes it.
final class ShareViewController: UIViewController {
    private var model: ShareModel!

    override func viewDidLoad() {
        super.viewDidLoad()
        L10n.bootstrap()
        model = ShareModel(context: extensionContext)
        let host = UIHostingController(rootView: ShareRootView(model: model))
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        host.didMove(toParent: self)
        Task { await model.start() }
    }
}

@MainActor
@Observable
final class ShareModel {
    enum Phase: Equatable {
        case loading, signedOut, noFrames, ready, sending, failed(String)
    }

    private weak var context: NSExtensionContext?
    private(set) var phase: Phase = .loading
    private(set) var frames: [FrameSummary] = []
    let compose: ComposeModel
    private let auth = AuthService()
    private var server: ServerConfig?
    private static let lastFramesKey = "share.lastFrames"

    init(context: NSExtensionContext?) {
        self.context = context
        let stored = ServerStore.load()
        server = stored
        compose = ComposeModel(limits: stored?.discovery.limits ?? .defaults)
        auth.configure(stored?.discovery)
    }

    func start() async {
        async let files: Void = loadAttachments()
        guard let server, auth.isSignedIn else {
            await files
            phase = .signedOut
            return
        }
        let api = API(baseURL: server.apiBase, tokens: auth)
        let all = (try? await api.call { try await $0.listMyFrames().ok.body.json }) ?? []
        frames = all.filter { $0.can(\.send) }
        await files
        if frames.isEmpty {
            phase = .noFrames
            return
        }
        let last = Set(AppGroup.defaults.stringArray(forKey: Self.lastFramesKey) ?? [])
        compose.frameIds = frames.count == 1 ? [frames[0].id] : Set(frames.map(\.id)).intersection(last)
        phase = .ready
    }

    /// Copy every shared image and movie into the App Group (provider URLs are only valid during the callback).
    private func loadAttachments() async {
        let providers = (context?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        var files: [(url: URL, filename: String)] = []
        for provider in providers {
            if let file = await Self.load(provider) { files.append(file) }
        }
        await compose.add(files: files)
    }

    private static func load(_ provider: NSItemProvider) async -> (url: URL, filename: String)? {
        let types = [UTType.movie, .image].filter { provider.hasItemConformingToTypeIdentifier($0.identifier) }
        guard let type = types.first else { return nil }
        let dir = FileManager.default.temporaryDirectory.appending(path: "shared-\(UUID().uuidString)", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let fromFile: (url: URL, filename: String)? = await withCheckedContinuation { cont in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                guard let url else { return cont.resume(returning: nil) }
                let dest = dir.appending(path: url.lastPathComponent)
                guard (try? FileManager.default.copyItem(at: url, to: dest)) != nil else { return cont.resume(returning: nil) }
                cont.resume(returning: (dest, provider.suggestedName.map { "\($0).\(url.pathExtension)" } ?? url.lastPathComponent))
            }
        }
        if let fromFile { return fromFile }
        // Some apps share images only as data.
        let data: Data? = await withCheckedContinuation { cont in
            _ = provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in cont.resume(returning: data) }
        }
        guard let data, let image = UIImage(data: data), let jpeg = image.jpegData(compressionQuality: 0.95) else { return nil }
        let dest = dir.appending(path: "\(provider.suggestedName ?? "Photo").jpg")
        guard (try? jpeg.write(to: dest)) != nil else { return nil }
        return (dest, dest.lastPathComponent)
    }

    /// Queue the send in the App Group and start uploading on a background session the app can pick up.
    func send() async {
        guard let server, compose.canSend else { return }
        phase = .sending
        do {
            let store = OutboxStore()
            let job = try compose.makeJob(server: server.apiBase, frames: frames, store: store)
            AppGroup.defaults.set(job.frameIds, forKey: Self.lastFramesKey)
            let transport = BackgroundUploadTransport(identifier: "app.kinframe.family.share.\(UUID().uuidString)")
            let runner = OutboxRunner(store: store, transport: transport, owner: "share-\(UUID().uuidString)")
            transport.setOrphanHandler { tag, response, error in
                Task { await runner.handleOrphan(tag: tag, response: response, error: error) }
            }
            await runner.configure(server: server.apiBase, tokens: auth)
            await runner.kick()
            // Give the first requests a moment to start; the system carries them on after the sheet closes.
            try? await Task.sleep(for: .milliseconds(800))
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            context?.completeRequest(returningItems: nil)
        } catch {
            phase = .failed(errorMessage(error))
        }
    }

    func cancel() {
        compose.clear()
        context?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
    }
}

struct ShareRootView: View {
    @Bindable var model: ShareModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    thumbnails
                    switch model.phase {
                    case .loading:
                        LoadingIndicator()
                    case .signedOut:
                        Callout(L("ios.share.signedOut"), kind: .warn)
                    case .noFrames:
                        Callout(L("send.noFramesBody"), kind: .warn)
                    case .failed(let message):
                        Callout(message, kind: .error)
                    case .ready, .sending:
                        frames
                        caption
                        if model.compose.issueCount > 0 {
                            Callout(Lp("send.issues", count: model.compose.issueCount), kind: .error)
                        }
                    }
                }
                .padding(16)
            }
            .background(Palette.bg.ignoresSafeArea())
            .navigationTitle(L("send.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("common.cancel")) { model.cancel() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.phase == .sending {
                        ProgressView()
                    } else {
                        Button(L("send.sendButton")) { Task { await model.send() } }
                            .fontWeight(.semibold)
                            .disabled(model.phase != .ready || !model.compose.canSend)
                    }
                }
            }
        }
        .tint(Palette.primary)
    }

    private var thumbnails: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.compose.drafts) { d in
                    Palette.surface2
                        .frame(width: 84, height: 84)
                        .overlay {
                            if let data = d.info?.thumbnail, let image = UIImage(data: data) {
                                Image(uiImage: image).resizable().scaledToFill()
                            } else {
                                ProgressView()
                            }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                        .overlay(alignment: .bottomLeading) {
                            if d.kind == .video, let s = d.trim?.seconds ?? d.info?.duration {
                                Text(Format.duration(s)).font(.caption2.weight(.bold)).foregroundStyle(.white)
                                    .padding(4).background(Capsule().fill(.black.opacity(0.5))).padding(4)
                            }
                        }
                        .accessibilityHidden(true)
                }
            }
        }
        .accessibilityLabel(Lp("send.count", count: model.compose.drafts.count, ["max": model.compose.limits.maxItemsPerSend]))
    }

    private var frames: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("send.to")).font(.headline)
            VStack(spacing: 0) {
                ForEach(model.frames) { frame in
                    let on = model.compose.frameIds.contains(frame.id)
                    Button {
                        model.compose.toggle(frame: frame.id)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: on ? "checkmark.circle.fill" : "circle")
                                .font(.title2)
                                .foregroundStyle(on ? Palette.primary : Palette.borderStrong)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(frame.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                HStack(spacing: 6) {
                                    StatusDot(online: frame.online)
                                    Text(frame.online ? L("frame.online") : L("frame.offline")).font(.footnote).foregroundStyle(Palette.textMuted)
                                }
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 14)
                        .frame(minHeight: 56)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .card(padding: 0)
        }
    }

    private var caption: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("send.caption")).font(.headline)
            TextField(L("send.captionPlaceholder"), text: Binding(get: { model.compose.sharedCaption }, set: { model.compose.applyCaptionToAll($0) }), axis: .vertical)
                .lineLimit(2...4)
                .padding(12)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.borderStrong))
            let left = Format.captionRemaining(model.compose.sharedCaption)
            Text(L("send.charsLeft", ["n": left, "max": Format.captionMax]))
                .font(.caption.monospacedDigit())
                .foregroundStyle(left < 0 ? Palette.danger : Palette.textFaint)
        }
    }
}

private struct LoadingIndicator: View {
    var body: some View {
        ProgressView().frame(maxWidth: .infinity, minHeight: 120)
    }
}
