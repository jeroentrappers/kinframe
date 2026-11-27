import KinframeKit
import Photos
import PhotosUI
import SwiftUI

/// A picked photo or video as a file: the original (HEIC, MOV, …). The upload queue prepares it before sending.
struct PickedMedia: Transferable {
    let url: URL
    let filename: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { try copy($0.file) }
        FileRepresentation(importedContentType: .image) { try copy($0.file) }
    }

    static func copy(_ source: URL) throws -> PickedMedia {
        let dir = FileManager.default.temporaryDirectory.appending(path: "picked", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appending(path: "\(UUID().uuidString)-\(source.lastPathComponent)")
        try FileManager.default.copyItem(at: source, to: dest)
        return PickedMedia(url: dest, filename: source.lastPathComponent)
    }

    /// The motion clip of a Live Photo, written next to the picked files; nil for anything else.
    static func motionClip(_ item: PhotosPickerItem) async -> URL? {
        guard item.supportedContentTypes.contains(where: { $0.conforms(to: .livePhoto) }),
              let live = try? await item.loadTransferable(type: PHLivePhoto.self) else { return nil }
        let resources = PHAssetResource.assetResources(for: live)
        guard let clip = resources.first(where: { $0.type == .pairedVideo }) ?? resources.first(where: { $0.type == .fullSizePairedVideo }) else { return nil }
        let dir = FileManager.default.temporaryDirectory.appending(path: "picked", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let ext = (clip.originalFilename as NSString).pathExtension
        let dest = dir.appending(path: "\(UUID().uuidString)-motion.\(ext.isEmpty ? "mov" : ext.lowercased())")
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        do {
            try await PHAssetResourceManager.default().writeData(for: clip, toFile: dest, options: options)
            return dest
        } catch {
            return nil
        }
    }
}

/// The composer (`+`, docs/spec/simple-app.md §3.2): photos first, a caption, the trim/fit/focal tools, then one
/// destination row: the home family ("Trappers · everyone and its 2 frames", or Home's chosen family), changeable to
/// several families at once ("3 families · 9 frames") or a circle per family. Sent to one frame only when opened from that frame's screen.
struct ComposerSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(Accounts.self) private var accounts
    @Environment(\.dismiss) private var dismiss
    let request: ComposeRequest
    @State private var model = ComposeModel(limits: .defaults)
    @State private var destination: ComposeDestination?
    @State private var picks: [PhotosPickerItem] = []
    @State private var importing = 0
    @State private var editing: Draft.ID?
    @State private var sendingJob: UUID?
    @State private var showCamera = false
    @State private var changing = false
    /// Circle names of the chosen circles, by circle id (for the row).
    @State private var circleNames: [String: String] = [:]
    @FocusState private var captionFocused: Bool

    /// Families you may post to (not those where you are `member`), of every account.
    private var postable: [Accounts.Owned<FeedSummary>] { accounts.feeds.filter { $0.value.role != .member } }

    /// The accounts the destination belongs to (one family post per server, one job each).
    private var owners: [AppModel] {
        switch destination {
        case .frame(let id)?: [accounts.active.first { m in m.frames.contains { $0.id == id } } ?? app]
        case .families(let list)?:
            accounts.active.filter { m in list.contains { $0.choice.account == m.accountId } }
        case nil: [app]
        }
    }

    private var owner: AppModel { owners.first ?? app }

    /// The chosen families with their circle.
    private var families: [(Accounts.Owned<FeedSummary>, String?)] {
        guard case .families(let list)? = destination else { return [] }
        return list.compactMap { t in accounts.family(t.choice).map { ($0, t.circleId) } }
    }

    /// The strictest limits of the servers it goes to.
    private var limits: Limits {
        ComposeLimits.effective(owners.map { ($0.limits, true) })
    }

    private var frame: FrameSummary? {
        if case .frame(let id)? = destination { return owner.frame(id) }
        return nil
    }


    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if let id = sendingJob, let job = app.outbox.job(id) {
                        SendingCard(job: job, onRetry: { app.outbox.retry(id) }, onCancel: {
                            app.outbox.cancel(id)
                            sendingJob = nil
                        })
                    } else if destination == nil {
                        Callout(L("simple.compose.noFamily"), kind: .info)
                    } else {
                        if model.drafts.isEmpty && importing == 0 {
                            pickerHero
                        } else {
                            items
                            captionField
                        }
                        destinationRow
                    }
                }
                .pageColumn(PageWidth.text)
            }
            .scrollDismissesKeyboard(.interactively)
            .pageBackground()
            .pageTitle(frame.map { L("simple.compose.toFrameTitle", ["frame": $0.name]) } ?? L("simple.compose.title"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
            }
            .safeAreaInset(edge: .bottom) {
                if sendingJob == nil, destination != nil, !model.drafts.isEmpty || importing > 0 { sendBar }
            }
            .sheet(item: Binding(get: { editing.map(EditingID.init) }, set: { editing = $0?.id })) { item in
                ItemEditorSheet(model: model, draftId: item.id, frames: frame.map { [$0] } ?? [],
                                // Zoom only when every server it goes to stores framing (docs/spec/framing.md §7).
                                framingEnabled: owners.allSatisfy { $0.discovery?.framing == true })
            }
            .sheet(isPresented: $changing) {
                DestinationSheet(current: destination, postable: postable) { d, names in
                    destination = d
                    circleNames.merge(names) { $1 }
                }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { url, name in
                    Task { await add([(url, name)]) }
                }
                .ignoresSafeArea()
            }
        }
        .interactiveDismissDisabled(!model.drafts.isEmpty && sendingJob == nil)
        .onChange(of: picks) { _, items in
            guard !items.isEmpty else { return }
            picks = []
            Task { await load(items) }
        }
        .onAppear {
            if destination == nil { destination = initialDestination() }
            apply()
            #if DEBUG
            // -kfCompose /a.heic,/b.mp4 stages files, -kfAutoSend YES sends them (screenshots, manual tests).
            if let list = UserDefaults.standard.string(forKey: "kfCompose"), model.drafts.isEmpty {
                UserDefaults.standard.removeObject(forKey: "kfCompose")
                Task {
                    // "/still.heic+/clip.mov" stages a Live Photo.
                    let files = list.split(separator: ",").compactMap { entry -> (URL, String, URL?)? in
                        let parts = entry.split(separator: "+").map { URL(fileURLWithPath: String($0)) }
                        guard let src = parts.first, let copy = try? PickedMedia.copy(src) else { return nil }
                        let motion = parts.count > 1 ? (try? PickedMedia.copy(parts[1]))?.url : nil
                        return (copy.url, src.lastPathComponent, motion)
                    }
                    await add(files)
                    if let caption = UserDefaults.standard.string(forKey: "kfCaption") { model.applyCaptionToAll(caption) }
                    if UserDefaults.standard.bool(forKey: "kfAutoSend") { send() }
                    if UserDefaults.standard.bool(forKey: "kfEditFirst") { editing = model.drafts.first?.id }
                }
            }
            #endif
        }
        .onChange(of: destination) { apply() }
        .onChange(of: limits) { _, limits in model.limits = limits }
        .onChange(of: sendingJob.flatMap { app.outbox.job($0)?.state }) { _, state in
            if case .sent = state { finished() }
        }
    }

    private struct EditingID: Identifiable { let id: UUID }

    /// The frame it was opened for, else the requested family, else Home's chosen family, else the home family.
    private func initialDestination() -> ComposeDestination? {
        if let id = request.frameId { return .frame(frameId: id) }
        let account = request.account ?? app.accountId
        let wanted = [request.feedId.map { FamilyChoice(account: account, feedId: $0) }, accounts.homeFilter, accounts.homeFamily?.choice]
        for c in wanted.compactMap({ $0 }) where postable.contains(where: { $0.choice == c }) {
            return .families([.init(c)])
        }
        return postable.first.map { .families([.init($0.choice)]) }
    }

    /// The destination as the send's recipients.
    private func apply() {
        model.limits = limits
        switch destination {
        case .frame(let id)?:
            model.frameIds = [id]
            model.feedIds = []
            model.circleByFeed = [:]
            model.promptId = nil
        case .families(let list)?:
            model.frameIds = []
            model.feedIds = Set(list.map(\.choice.feedId))
            model.circleByFeed = Dictionary(list.compactMap { t in t.circleId.map { (t.choice.feedId, $0) } }) { a, _ in a }
            model.promptId = list.count == 1 && request.feedId == list[0].choice.feedId ? request.promptId : nil
        case nil:
            model.frameIds = []
            model.feedIds = []
        }
    }

    // MARK: Sections

    /// "Peeters family · everyone and its 3 frames" with Change, or "Only to Oma's frame".
    private var destinationRow: some View {
        HStack(spacing: 12) {
            if let frame {
                FrameThumb(id: frame.id, size: 40).clipShape(RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("simple.compose.frameOnly", ["frame": frame.name])).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                    Text(L("simple.compose.frameOnlyHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                }
                Spacer()
            } else if let first = families.first {
                FeedCover(url: first.0.value.coverUrl, name: first.0.value.name, size: 40, round: true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("simple.compose.to")).font(.caption.weight(.semibold)).foregroundStyle(Palette.textFaint).textCase(.uppercase)
                    Text(families.count == 1
                         ? FamilyText.destination(family: first.0.value.name, frames: first.0.value.subscriptionCount,
                                                  circle: first.1.flatMap { circleNames[$0] })
                         : FamilyText.destination(families: families.count, frames: families.reduce(0) { $0 + $1.0.value.subscriptionCount }))
                        .font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                    if families.count > 1 {
                        Text(ListText.join(families.map(\.0.value.name))).font(.footnote).foregroundStyle(Palette.textMuted).lineLimit(2)
                    }
                }
                Spacer()
                Button(L("simple.compose.change")) { changing = true }.buttonStyle(.kf(.soft))
            }
        }
        .padding(14)
        .card(padding: 0)
        .accessibilityElement(children: .combine)
    }

    private var pickerHero: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle().fill(Palette.primarySoft).frame(width: 96, height: 96)
                Image(systemName: "photo.stack").font(.system(size: 38, weight: .medium)).foregroundStyle(Palette.primarySoftText)
            }
            .accessibilityHidden(true)
            Text(L("ios.send.pickTitle")).font(.display(.title2)).foregroundStyle(Palette.text).multilineTextAlignment(.center)
            Text(L("ios.send.pickBody", ["max": model.limits.maxItemsPerSend]))
                .foregroundStyle(Palette.textMuted)
                .multilineTextAlignment(.center)
            PhotosPicker(selection: $picks, maxSelectionCount: max(1, model.room), selectionBehavior: .ordered, matching: .any(of: [.images, .videos, .livePhotos]), preferredItemEncoding: .current) {
                Label(L("send.choose"), systemImage: "photo.on.rectangle.angled")
            }
            .buttonStyle(.kf(.primary, large: true, fill: true))
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                Button {
                    showCamera = true
                } label: {
                    Label(L("ios.send.camera"), systemImage: "camera")
                }
                .buttonStyle(.kf(.secondary, large: true, fill: true))
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .card(padding: 0)
    }

    private var items: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(Lp("send.count", count: model.drafts.count, ["max": model.limits.maxItemsPerSend]) + " · " + Format.bytes(model.totalBytes, locale: L10n.locale))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Palette.textMuted)
                Spacer()
                Menu {
                    Button(role: .destructive) {
                        withAnimation { model.clear() }
                    } label: {
                        Label(L("send.clear"), systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle").font(.title3).frame(width: 44, height: 44)
                }
                .accessibilityLabel(L("send.clear"))
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 104, maximum: 160), spacing: 10)], spacing: 10) {
                ForEach(Array(model.drafts.enumerated()), id: \.element.id) { n, draft in
                    DraftTile(draft: draft, number: n + 1, issue: model.issue(draft), maxSize: model.limits.maxUploadBytes,
                              maxVideo: model.limits.maxVideoSeconds, captioned: !model.caption(of: draft).isEmpty,
                              edit: { editing = draft.id },
                              remove: { withAnimation(.snappy) { model.remove(draft.id) } })
                }
                ForEach(0..<importing, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: Radius.md, style: .continuous)
                        .fill(Palette.surface2)
                        .aspectRatio(1, contentMode: .fit)
                        .overlay(ProgressView())
                }
                if model.room > 0 {
                    PhotosPicker(selection: $picks, maxSelectionCount: model.room, selectionBehavior: .ordered, matching: .any(of: [.images, .videos, .livePhotos]), preferredItemEncoding: .current) {
                        Palette.primarySoft.opacity(0.6)
                            .aspectRatio(1, contentMode: .fit)
                            .overlay {
                                VStack(spacing: 6) {
                                    Image(systemName: "plus").font(.title2.weight(.semibold))
                                    Text(L("send.addMore")).font(.footnote.weight(.semibold))
                                }
                                .foregroundStyle(Palette.primarySoftText)
                            }
                            .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.primary.opacity(0.35), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5])))
                    }
                    .buttonStyle(.plain)
                }
            }
            if model.issueCount > 0 {
                Callout(Lp("send.issues", count: model.issueCount), kind: .error)
            }
        }
    }

    private var captionField: some View {
        @Bindable var model = model
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(model.drafts.count > 1 ? L("ios.send.captionAll") : L("send.caption")).font(.subheadline.weight(.semibold))
                Spacer()
                let left = Format.captionRemaining(model.sharedCaption)
                Text(L("send.charsLeft", ["n": left, "max": Format.captionMax]))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(left < 0 ? Palette.danger : left < 30 ? Palette.warn : Palette.textFaint)
                    .opacity(captionFocused || left < 30 ? 1 : 0)
            }
            TextField(L("send.captionPlaceholder"), text: Binding(get: { model.sharedCaption }, set: { model.applyCaptionToAll($0) }), axis: .vertical)
                .lineLimit(1...4)
                .focused($captionFocused)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(captionFocused ? Palette.primary : Palette.borderStrong, lineWidth: captionFocused ? 2 : 1))
                .accessibilityLabel(L("send.caption"))
        }
    }

    private var sendBar: some View {
        VStack(spacing: 8) {
            if let hint = blockerText {
                Text(hint).font(.footnote).foregroundStyle(Palette.textMuted)
            }
            Button {
                send()
            } label: {
                Label(L("simple.compose.share"), systemImage: "paperplane.fill")
            }
            .buttonStyle(.kf(.primary, large: true, fill: true))
            .disabled(!model.canSend || importing > 0)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .frame(maxWidth: PageWidth.text)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }

    private var blockerText: String? {
        switch model.blocker {
        case .overLimit(let max): L("send.overLimit", ["max": max, "n": model.drafts.count - max])
        case .tooManyFeeds(let max): L("ios.feeds.tooManyFeeds", ["max": max])
        case .captionTooLong: L("send.charsLeft", ["n": 0, "max": Format.captionMax])
        default: nil
        }
    }

    // MARK: Actions

    private func load(_ items: [PhotosPickerItem]) async {
        let take = Array(items.prefix(model.room))
        let over = items.count - take.count
        importing += take.count
        var loaded: [Int: (URL, String, URL?)] = [:]
        await withTaskGroup(of: (Int, PickedMedia?, URL?).self) { group in
            for (n, item) in take.enumerated() {
                group.addTask {
                    guard let media = try? await item.loadTransferable(type: PickedMedia.self) else { return (n, nil, nil) }
                    return (n, media, await PickedMedia.motionClip(item))
                }
            }
            for await (n, media, motion) in group {
                importing -= 1
                if let media { loaded[n] = (media.url, media.filename, motion) }
            }
        }
        let files = loaded.keys.sorted().compactMap { loaded[$0] }
        // Keep the order they were picked in.
        await add(files)
        if over > 0 {
            app.toasts.show(L("send.overLimit", ["max": model.limits.maxItemsPerSend, "n": over]), isError: true)
        }
        if files.count < take.count {
            app.toasts.show(Lp("send.skipped", count: take.count - files.count))
        }
    }

    private func add(_ files: [(URL, String)]) async {
        await add(files.map { ($0.0, $0.1, nil) })
    }

    private func add(_ files: [(URL, String, URL?)]) async {
        let result = await model.add(media: files.map { (url: $0.0, filename: $0.1, motion: $0.2) })
        if result.overLimit > 0 {
            app.toasts.show(L("send.overLimit", ["max": model.limits.maxItemsPerSend, "n": result.overLimit]), isError: true)
        }
        if result.unsupported > 0 { app.toasts.show(Lp("send.skipped", count: result.unsupported)) }
    }

    private func send() {
        guard model.canSend else { return }
        do {
            // One job per server with chosen families; the first prepares the media for all (one upload each).
            let targets = owners.compactMap { m in
                m.server.map { ComposeModel.ServerTargets(server: $0.apiBase, serverName: m.serverName, frames: m.frames,
                                                          feeds: m.feeds.filter { $0.role != .member }) }
            }
            let jobs = try model.makeJobs(targets, store: app.outbox.store)
            guard let job = jobs.first else { return }
            try app.outbox.enqueue(jobs)
            withAnimation { sendingJob = job.id }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        } catch {
            app.toasts.error(error)
        }
    }

    /// Shared: back to Home, where the post comes in on top (a frame-only send stays on the frame's screen).
    private func finished() {
        sendingJob = nil
        app.toasts.show(L("simple.compose.shared"))
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        if frame == nil { app.tab = .home }
        dismiss()
    }
}

/// "Change": the person's families as checkboxes (one post to several families), and per ticked family its circles
/// as an alternative to the whole family. Single frames aren't offered here.
private struct DestinationSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var current: ComposeDestination?
    let postable: [Accounts.Owned<FeedSummary>]
    let choose: (ComposeDestination, _ circleNames: [String: String]) -> Void
    @State private var circles: [String: [FamilyCircle]] = [:]

    init(current: ComposeDestination?, postable: [Accounts.Owned<FeedSummary>],
         choose: @escaping (ComposeDestination, [String: String]) -> Void) {
        _current = State(initialValue: current)
        self.postable = postable
        self.choose = choose
    }

    private var chosen: [ComposeDestination.FamilyTarget] {
        if case .families(let list)? = current { return list }
        return []
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(postable, id: \.value.id) { owned in
                    let target = chosen.first { $0.choice == owned.choice }
                    Section {
                        Button {
                            current = (current ?? .families([])).toggling(owned.choice)
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: target != nil ? "checkmark.circle.fill" : "circle")
                                    .font(.title2).foregroundStyle(target != nil ? Palette.primary : Palette.borderStrong)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(owned.value.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                    Text(Lp("simple.family.frames", count: owned.value.subscriptionCount)).font(.footnote).foregroundStyle(Palette.textMuted)
                                }
                                Spacer()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(target != nil ? .isSelected : [])
                        if let target, let list = circles[owned.value.id], !CircleLogic.audience(list).isEmpty {
                            row(L("social.compose.wholeFamily"), detail: nil, on: target.circleId == nil) {
                                current = current?.with(circle: nil, for: owned.choice)
                            }
                            ForEach(CircleLogic.audience(list)) { c in
                                row(c.name, detail: (c.framesReached ?? []).isEmpty ? nil : ListText.join((c.framesReached ?? []).map(\.frameName)),
                                    on: target.circleId == c.id) {
                                    current = current?.with(circle: c.id, for: owned.choice)
                                }
                            }
                        }
                    }
                    .listRowBackground(Palette.surface)
                    .task(id: target != nil) {
                        guard target != nil, circles[owned.value.id] == nil else { return }
                        circles[owned.value.id] = await owned.model.social.circles(of: owned.value.id)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .pageTitle(L("simple.compose.change"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("common.done")) {
                        if let current {
                            var names: [String: String] = [:]
                            for list in circles.values { for c in list { names[c.id] = c.name } }
                            choose(current, names)
                        }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(chosen.isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ title: String, detail: String?, on: Bool, action: @escaping () -> Void) -> some View {
        Button {
            action()
        } label: {
            HStack {
                Image(systemName: on ? "largecircle.fill.circle" : "circle").foregroundStyle(on ? Palette.primary : Palette.borderStrong)
                    .padding(.leading, 34)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(Palette.text)
                    if let detail { Text(detail).font(.footnote).foregroundStyle(Palette.textMuted) }
                }
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// One picked item in the grid.
struct DraftTile: View {
    let draft: Draft
    let number: Int
    let issue: ItemIssue?
    let maxSize: Int64
    let maxVideo: Int
    let captioned: Bool
    let edit: () -> Void
    let remove: () -> Void

    var body: some View {
        Button(action: edit) {
            Palette.surface2
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    if let data = draft.info?.thumbnail, let image = UIImage(data: data) {
                        Image(uiImage: image).resizable().scaledToFill()
                    } else if draft.loading {
                        ProgressView()
                    } else {
                        Image(systemName: draft.kind == .video ? "video" : "photo").foregroundStyle(Palette.textFaint)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            .overlay(alignment: .bottomLeading) { badges }
            .overlay(alignment: .topLeading) { if draft.isLive { LiveBadge().padding(6) } }
            .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(issue != nil ? Palette.danger : .clear, lineWidth: 3))
        }
        .buttonStyle(CardButtonStyle())
        .overlay(alignment: .topTrailing) {
            Button(action: remove) {
                Image(systemName: "xmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 26, height: 26)
                    .background(Circle().fill(.black.opacity(0.55)))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(L("send.removeItem", ["n": number]))
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("send.adjustItem", ["n": number]))
    }

    private var badges: some View {
        HStack(spacing: 4) {
            if draft.kind == .video {
                let seconds = draft.trim?.seconds ?? draft.info?.duration ?? 0
                Label(Format.duration(seconds), systemImage: draft.trim != nil ? "scissors" : "play.fill")
                    .labelStyle(TightLabelStyle())
                    .font(.caption2.weight(.bold).monospacedDigit())
                    .accessibilityLabel(draft.trim != nil ? L("trim.trimmedTo", ["length": Format.duration(seconds)]) : Format.duration(seconds))
            }
            if captioned { Image(systemName: "text.bubble.fill").font(.caption2) }
            if draft.fit == .fit {
                Label(L("send.fitShort"), systemImage: "rectangle.arrowtriangle.2.inward")
                    .labelStyle(TightLabelStyle())
                    .font(.caption2.weight(.bold))
            }
            if let issue {
                Image(systemName: "exclamationmark.triangle.fill").font(.caption2)
                    .accessibilityLabel(issue == .tooLarge ? L("send.issueTooLarge", ["max": Format.bytes(maxSize)]) : L("send.issueTooLong", ["max": Format.duration(Double(maxVideo))]))
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
        .background(Capsule().fill(.black.opacity(0.5)))
        .padding(6)
        .opacity(draft.kind == .video || captioned || issue != nil || draft.fit == .fit ? 1 : 0)
    }
}

/// Upload progress of a queued send. It keeps going if the app is left.
struct SendingCard: View {
    let job: OutboxJob
    var onRetry: () -> Void
    var onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                ThumbStack(job: job)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline).foregroundStyle(Palette.text)
                    Text((job.serverName.map { L("servers.account.sending", ["server": $0]) + " · " } ?? "") + job.recipientNames.joined(separator: ", ")).font(.subheadline).foregroundStyle(Palette.textMuted).lineLimit(1)
                }
                Spacer()
            }
            ProgressView(value: job.progress)
                .tint(Palette.primary)
                .accessibilityLabel(title)
            if case .failed(let code) = job.state {
                Callout(APIError(status: 0, code: code).message, kind: .error)
                HStack {
                    Button(L("common.tryAgain"), action: onRetry).buttonStyle(.kf(.primary))
                    Button(L("common.cancel"), action: onCancel).buttonStyle(.kf(.ghost))
                }
            } else {
                Text(L("ios.send.background")).font(.footnote).foregroundStyle(Palette.textMuted)
                Button(L("common.cancel"), action: onCancel).buttonStyle(.kf(.ghost))
            }
        }
        .card(padding: 18)
    }

    private var title: String {
        switch job.state {
        case .uploading:
            if let p = job.preparing {
                L("ios.send.preparing", ["n": p.index + 1, "total": job.items.count, "pct": Int((p.fraction * 100).rounded())])
            } else {
                L("send.uploading", ["done": job.uploadedCount, "total": job.items.count, "pct": Int((job.progress * 100).rounded())])
            }
        case .sending, .sent:
            L("send.finishing")
        case .failed:
            L("stage.failed")
        }
    }
}

/// Up to three thumbnails of a queued job, fanned out.
struct ThumbStack: View {
    @Environment(AppModel.self) private var app
    let job: OutboxJob

    var body: some View {
        ZStack {
            ForEach(Array(job.items.prefix(3).enumerated().reversed()), id: \.offset) { n, item in
                thumb(item)
                    .frame(width: 48, height: 48)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Palette.surface, lineWidth: 2))
                    .rotationEffect(.degrees(Double(n) * 6))
                    .offset(x: CGFloat(n) * 6)
            }
        }
        .frame(width: 64, height: 56)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func thumb(_ item: OutboxItem) -> some View {
        if let name = item.thumbnail, let image = UIImage(contentsOfFile: app.outbox.store.directory(job.id).appending(path: name).path) {
            Image(uiImage: image).resizable().scaledToFill()
        } else {
            Rectangle().fill(Palette.surface2)
        }
    }
}
