import KinframeKit
import PhotosUI
import SwiftUI

/// A picked photo or video as a file: the original (HEIC, MOV, …), never transcoded.
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
}

struct ComposeTab: View {
    @Environment(AppModel.self) private var app
    @State private var model = ComposeModel(limits: .defaults)
    @State private var picks: [PhotosPickerItem] = []
    @State private var importing = 0
    @State private var editing: Draft.ID?
    @State private var sendingJob: UUID?
    @State private var showCamera = false
    @FocusState private var captionFocused: Bool

    private var sendable: [FrameSummary] { app.frames.filter { $0.can(\.send) } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(L("send.lede"))
                        .foregroundStyle(Palette.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                    if app.framesLoaded, sendable.isEmpty {
                        noFrames
                    } else if let id = sendingJob, let job = app.outbox.job(id) {
                        SendingCard(job: job, onRetry: { app.outbox.retry(id) }, onCancel: {
                            app.outbox.cancel(id)
                            sendingJob = nil
                        })
                    } else {
                        if model.drafts.isEmpty && importing == 0 {
                            pickerHero
                        } else {
                            items
                            captionField
                        }
                        frameList
                    }
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .pageBackground()
            .navigationTitle(L("send.title"))
            .safeAreaInset(edge: .bottom) {
                if sendingJob == nil, !model.drafts.isEmpty || importing > 0 { sendBar }
            }
            .sheet(item: Binding(get: { editing.map(EditingID.init) }, set: { editing = $0?.id })) { item in
                ItemEditorSheet(model: model, draftId: item.id, frames: app.frames.filter { model.frameIds.contains($0.id) })
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { url, name in
                    Task { await add([(url, name)]) }
                }
                .ignoresSafeArea()
            }
        }
        .onChange(of: picks) { _, items in
            guard !items.isEmpty else { return }
            picks = []
            Task { await load(items) }
        }
        .onAppear {
            model.limits = app.limits
            preselect()
            #if DEBUG
            // -kfCompose /a.heic,/b.mp4 stages files, -kfAutoSend YES sends them (screenshots, manual tests).
            if let list = UserDefaults.standard.string(forKey: "kfCompose"), model.drafts.isEmpty {
                UserDefaults.standard.removeObject(forKey: "kfCompose")
                Task {
                    let files = list.split(separator: ",").compactMap { path -> (URL, String)? in
                        let src = URL(fileURLWithPath: String(path))
                        guard let copy = try? PickedMedia.copy(src) else { return nil }
                        return (copy.url, src.lastPathComponent)
                    }
                    await add(files)
                    if let caption = UserDefaults.standard.string(forKey: "kfCaption") { model.applyCaptionToAll(caption) }
                    if UserDefaults.standard.bool(forKey: "kfAutoSend") { send() }
                    if UserDefaults.standard.bool(forKey: "kfEditFirst") { editing = model.drafts.first?.id }
                }
            }
            #endif
        }
        .onChange(of: app.composeFrameIds) { preselect() }
        .onChange(of: app.frames) { preselect() }
        .onChange(of: app.framesLoaded) { preselect() }
        .onChange(of: sendingJob.flatMap { app.outbox.job($0)?.state }) { _, state in
            if case .sent(let sendId) = state {
                finished(sendId)
            }
        }
    }

    private struct EditingID: Identifiable { let id: UUID }

    // MARK: Sections

    private var noFrames: some View {
        EmptyStateView(systemImage: "photo.on.rectangle", title: L("send.noFramesTitle"), message: L("send.noFramesBody")) {
            Button(L("frames.add")) { app.tab = .frames }
                .buttonStyle(.kf(.primary, large: true))
        }
        .card(padding: 8)
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
            PhotosPicker(selection: $picks, maxSelectionCount: max(1, model.room), selectionBehavior: .ordered, matching: .any(of: [.images, .videos]), preferredItemEncoding: .current) {
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
                    PhotosPicker(selection: $picks, maxSelectionCount: model.room, selectionBehavior: .ordered, matching: .any(of: [.images, .videos]), preferredItemEncoding: .current) {
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

    private var frameList: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("send.to"))
            VStack(spacing: 0) {
                ForEach(Array(sendable.enumerated()), id: \.element.id) { n, frame in
                    if n > 0 { Divider().overlay(Palette.border).padding(.leading, 60) }
                    let on = model.frameIds.contains(frame.id)
                    Button {
                        withAnimation(.snappy) { model.toggle(frame: frame.id) }
                        UISelectionFeedbackGenerator().selectionChanged()
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: on ? "checkmark.circle.fill" : "circle")
                                .font(.title2)
                                .foregroundStyle(on ? Palette.primary : Palette.borderStrong)
                                .frame(width: 30)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(frame.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                                OnlineStatus(online: frame.online, lastSeen: frame.lastSeenAt)
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .frame(minHeight: 60)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .card(padding: 0)
            if sendable.contains(where: { model.frameIds.contains($0.id) && !$0.online }) {
                Text(L("send.offlineNote")).font(.footnote).foregroundStyle(Palette.textMuted)
            }
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
                Label(Lp("send.sendN", count: model.drafts.count), systemImage: "paperplane.fill")
            }
            .buttonStyle(.kf(.primary, large: true, fill: true))
            .disabled(!model.canSend || importing > 0)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .frame(maxWidth: 720)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }

    private var blockerText: String? {
        switch model.blocker {
        case .noFrames: L("send.pickFrame")
        case .overLimit(let max): L("send.overLimit", ["max": max, "n": model.drafts.count - max])
        case .captionTooLong: L("send.charsLeft", ["n": 0, "max": Format.captionMax])
        default: nil
        }
    }

    // MARK: Actions

    private func preselect() {
        // Wait for the frame list: until then the wanted frames can't be checked.
        guard app.framesLoaded else { return }
        if !app.composeFrameIds.isEmpty {
            model.frameIds = app.composeFrameIds.filter { id in sendable.contains { $0.id == id } }
            app.composeFrameIds = []
        } else if model.frameIds.isEmpty, sendable.count == 1, let only = sendable.first {
            model.frameIds = [only.id]
        }
    }

    private func load(_ items: [PhotosPickerItem]) async {
        let take = Array(items.prefix(model.room))
        let over = items.count - take.count
        importing += take.count
        var files: [(URL, String)] = []
        await withTaskGroup(of: PickedMedia?.self) { group in
            for item in take {
                group.addTask { try? await item.loadTransferable(type: PickedMedia.self) }
            }
            for await media in group {
                importing -= 1
                if let media { files.append((media.url, media.filename)) }
            }
        }
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
        let result = await model.add(files: files.map { (url: $0.0, filename: $0.1) })
        if result.overLimit > 0 {
            app.toasts.show(L("send.overLimit", ["max": model.limits.maxItemsPerSend, "n": result.overLimit]), isError: true)
        }
        if result.unsupported > 0 { app.toasts.show(Lp("send.skipped", count: result.unsupported)) }
    }

    private func send() {
        guard let server = app.server, model.canSend else { return }
        do {
            let job = try model.makeJob(server: server.apiBase, frames: app.frames, store: app.outbox.store)
            try app.outbox.enqueue(job)
            withAnimation { sendingJob = job.id }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        } catch {
            app.toasts.error(error)
        }
    }

    private func finished(_ sendId: String) {
        sendingJob = nil
        app.toasts.show(L("send.sent"))
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        app.historyPath = NavigationPath([HistoryRoute(sendId: sendId)])
        app.tab = .history
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
            }
            if captioned { Image(systemName: "text.bubble.fill").font(.caption2) }
            if draft.fit == .fit { Image(systemName: "rectangle.arrowtriangle.2.inward").font(.caption2) }
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
                    Text(job.frameNames.joined(separator: ", ")).font(.subheadline).foregroundStyle(Palette.textMuted).lineLimit(1)
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
            L("send.uploading", ["done": job.uploadedCount, "total": job.items.count, "pct": Int((job.progress * 100).rounded())])
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
