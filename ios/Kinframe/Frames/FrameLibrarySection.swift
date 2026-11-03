import KinframeKit
import SwiftUI

/// The frame's library (web: FrameLibrary): albums, "On this day", what's on the frame, hidden and trash, with hide /
/// trash / restore and album actions for single items or a selection. Members without see_library see their own
/// photos.
struct FrameLibrarySection: View {
    @Environment(AppModel.self) private var app
    let frame: FrameSummary
    @State private var model: LibraryModel
    @State private var open: Placement?
    @State private var sharing: ShareRequest?
    /// Opened once the photo sheet is gone (one sheet at a time).
    @State private var shareNext: ShareRequest?
    @State private var albumSheet: AlbumSheet?
    @State private var albumNext: AlbumSheet?
    @State private var confirmDelete = false
    @State private var playBusy = false

    init(api: API, frame: FrameSummary) {
        self.frame = frame
        _model = State(initialValue: LibraryModel(api: api, frameId: frame.id))
    }

    private var manage: Bool { frame.can(\.manageLibrary) }
    private var control: Bool { frame.can(\.changeSettings) }
    /// "Share to another frame" needs another frame you can send to.
    private var canShare: Bool { !Sharing.targets(app.frames, excluding: frame.id).isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !model.albums.isEmpty || manage { albumChips }
            if let album = model.album {
                AlbumHeader(album: album, cover: cover(album), playing: model.playingId == album.id, control: control, manage: manage,
                            canArrange: model.placements.count > 1, playBusy: playBusy,
                            togglePlaying: { Task { await togglePlaying(album) } },
                            act: { albumSheet = $0 }, delete: { confirmDelete = true })
            } else {
                if model.view == .visible { MemoriesStrip(memories: model.memories) { open = $0 } }
                filterBar
                if model.view == .visible, let line = countLine { Text(line).font(.footnote.monospacedDigit()).foregroundStyle(Palette.textMuted) }
            }
            if model.album != nil, !model.placements.isEmpty { selectButton.frame(maxWidth: .infinity, alignment: .trailing) }
            if !frame.can(\.seeLibrary) {
                Callout(L("library.ownOnly"))
            }
            if model.album == nil, model.view == .trashed {
                Text(L("library.trashNote")).font(.footnote).foregroundStyle(Palette.textMuted)
            }

            if model.loading {
                LoadingView()
            } else if let error = model.error {
                ErrorRetryView(message: error) { Task { await model.load() } }
            } else if model.placements.isEmpty {
                emptyState
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 4)], spacing: 4) {
                    ForEach(model.placements) { p in
                        tile(p)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                if model.cursor != nil {
                    ProgressView().frame(maxWidth: .infinity).onAppear { Task { await model.loadMore() } }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if model.selecting, !model.selection.isEmpty { bulkBar }
        }
        .task {
            await model.loadAlbums()
            await model.loadMemories()
            if control { await model.loadPlaying() }
            #if DEBUG
            if let pick = UserDefaults.standard.string(forKey: "kfAlbum"), !pick.isEmpty {
                model.select(album: model.albums.first { $0.id == pick || $0.name == pick }?.id ?? model.albums.first?.id)
            }
            #endif
        }
        .task(id: model.key) {
            await model.load()
            #if DEBUG
            debugSheets()
            #endif
        }
        .sheet(item: $open, onDismiss: {
            if let next = shareNext {
                shareNext = nil
                sharing = next
            }
            if let next = albumNext {
                albumNext = nil
                albumSheet = next
            }
        }) { p in
            PlacementSheet(api: model.api, placement: p, manage: manage || p.item.senderId == app.me?.id,
                           share: canShare && !p.trashed ? { shareNext = ShareRequest(source: .init(frameId: frame.id, placementIds: [p.id])); open = nil } : nil,
                           albumActions: albumActions(for: p)) { action in
                await run(action, ids: [p.id])
            }
        }
        .sheet(item: $sharing) { request in
            ShareToFrameSheet(api: model.api, source: request.source) {
                withAnimation { model.selecting = false; model.selection = [] }
            }
        }
        .sheet(item: $albumSheet) { sheet in
            albumSheetView(sheet)
        }
        .alert(L("albums.deleteConfirm", ["name": model.album?.name ?? ""]), isPresented: $confirmDelete) {
            Button(L("albums.delete"), role: .destructive) { Task { await deleteAlbum() } }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(model.album.map { model.playingId == $0.id } == true ? L("albums.deleteBodyPlaying") : L("albums.deleteBody"))
        }
    }

    // MARK: Pieces

    private var albumChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(L("albums.all"), systemImage: "photo.on.rectangle", count: nil, on: model.albumId == nil) { model.select(album: nil) }
                ForEach(model.albums) { a in
                    chip(a.name, systemImage: model.playingId == a.id ? "play.fill" : "rectangle.stack", count: a.placementCount,
                         on: model.albumId == a.id, scheduled: a.schedule?.fromMonthDay != nil) { model.select(album: a.id) }
                }
                if manage {
                    Button {
                        albumSheet = .create
                    } label: {
                        Label(L("albums.new"), systemImage: "plus")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 14)
                            .frame(minHeight: 40)
                            .overlay(Capsule().strokeBorder(Palette.borderStrong, style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Palette.primary)
                }
            }
            .padding(.vertical, 2)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("albums.label"))
    }

    private func chip(_ title: String, systemImage: String, count: Int?, on: Bool, scheduled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: systemImage).font(.caption.weight(.bold))
                Text(title).lineLimit(1)
                if let count { Text("\(count)").monospacedDigit().opacity(0.7) }
                if scheduled { Image(systemName: "calendar").font(.caption).accessibilityLabel(L("albums.scheduled")) }
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .frame(minHeight: 40)
            .foregroundStyle(on ? Palette.onPrimary : Palette.text)
            .background(Capsule().fill(on ? Palette.primary : Palette.surface))
            .overlay(Capsule().strokeBorder(on ? .clear : Palette.border))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private var filterBar: some View {
        HStack {
            Picker(L("library.filter"), selection: $model.view) {
                Text(L("library.visible")).tag(LibraryModel.View.visible)
                Text(withCount(L("library.hidden"), model.counts.hidden)).tag(LibraryModel.View.hidden)
                Text(withCount(L("library.trashTab"), model.counts.trashed)).tag(LibraryModel.View.trashed)
            }
            .pickerStyle(.segmented)
            if !model.placements.isEmpty { selectButton }
        }
    }

    private var selectButton: some View {
        Button(model.selecting ? L("common.done") : L("library.select")) {
            withAnimation { model.selecting.toggle(); model.selection = [] }
        }
        .font(.subheadline.weight(.semibold))
        .frame(minHeight: 44)
    }

    private func withCount(_ label: String, _ n: Int?) -> String {
        guard let n, n > 0 else { return label }
        return "\(label) \(n)"
    }

    private var countLine: String? {
        guard model.counts.photos != nil || model.counts.videos != nil else { return nil }
        var line = Lp("history.photos", count: model.counts.photos ?? 0)
        if let v = model.counts.videos, v > 0 { line += ", " + Lp("history.videos", count: v) }
        return line
    }

    @ViewBuilder
    private var emptyState: some View {
        if model.album != nil {
            EmptyStateView(systemImage: "rectangle.stack", title: L("albums.emptyTitle"), message: manage ? L("albums.emptyBody") : "") {
                if manage {
                    Button(L("albums.pickPhotos")) { model.select(album: nil) }.buttonStyle(.kf(.primary))
                }
            }
        } else {
            switch model.view {
            case .visible:
                EmptyStateView(systemImage: "photo.on.rectangle", title: L("library.emptyTitle"), message: L("library.emptyBody")) {
                    if frame.can(\.send) {
                        Button(L("nav.sendPhotos")) {
                            app.composeFrameIds = [frame.id]
                            app.tab = .send
                        }
                        .buttonStyle(.kf(.primary))
                    }
                }
            case .hidden: EmptyStateView(systemImage: "eye.slash", title: L("library.emptyHidden"), message: "")
            case .trashed: EmptyStateView(systemImage: "trash", title: L("library.emptyTrash"), message: "")
            }
        }
    }

    private func cover(_ album: Album) -> Placement? {
        album.coverPlacementId.flatMap { id in model.placements.first { $0.id == id } } ?? model.placements.first
    }

    private func tile(_ p: Placement) -> some View {
        let selected = model.selection.contains(p.id)
        return Button {
            if model.selecting {
                if selected { model.selection.remove(p.id) } else { model.selection.insert(p.id) }
            } else {
                open = p
            }
        } label: {
            Palette.surface2
                .aspectRatio(1, contentMode: .fit)
                .overlay { RemoteThumb(url: p.item.thumbUrl, kind: p.item.kind) }
                .overlay(alignment: .topTrailing) {
                    if model.selecting {
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(selected ? Palette.primary : .white)
                            .shadow(radius: 2)
                            .padding(6)
                    }
                }
                .overlay(alignment: .bottomLeading) {
                    if p.pendingModeration == true {
                        Pill(L("library.pending"), foreground: Palette.warn, background: Palette.warnSoft).padding(4)
                    } else if let album = model.album, album.coverPlacementId == p.id {
                        Pill(L("albums.cover"), systemImage: "star.fill", foreground: Palette.onPrimary, background: Palette.primary).padding(4)
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    if p.sharedByName != nil {
                        Image(systemName: "arrowshape.turn.up.right.fill")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(5)
                            .background(Circle().fill(.black.opacity(0.45)))
                            .padding(5)
                    }
                }
                .clipped()
                .opacity(model.selecting && !selected ? 0.75 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tileLabel(p))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func tileLabel(_ p: Placement) -> String {
        var parts: [String] = []
        if let caption = p.item.caption, !caption.isEmpty {
            parts.append(caption)
        } else {
            parts.append(L("library.itemBy", ["name": p.item.senderName ?? "?"]))
        }
        if p.item.kind == .live { parts.append(L("ios.live.a11y")) }
        if model.album?.coverPlacementId == p.id { parts.append(L("albums.cover")) }
        if let sharer = p.sharedByName { parts.append(L("share.sharedBy", ["name": sharer])) }
        return parts.joined(separator: ". ")
    }

    private var selectedIds: [String] { model.placements.map(\.id).filter { model.selection.contains($0) } }

    private var bulkBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button {
                    withAnimation { model.selection = []; model.selecting = false }
                } label: {
                    Image(systemName: "xmark").frame(width: 32, height: 32)
                }
                .accessibilityLabel(L("library.clearSelection"))
                Text(Lp("library.selected", count: model.selection.count)).font(.subheadline.weight(.semibold))
                Spacer()
                if model.selection.count < model.placements.count {
                    Button(L("library.selectAll")) { model.selection = Set(model.placements.map(\.id)) }
                        .font(.subheadline.weight(.semibold))
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    if canShare, model.view != .trashed {
                        Button {
                            sharing = ShareRequest(source: .init(frameId: frame.id, placementIds: selectedIds))
                        } label: {
                            Label(L("share.actionShort"), systemImage: "arrowshape.turn.up.right")
                        }
                        .buttonStyle(.kf(.secondary))
                        .accessibilityLabel(L("share.action"))
                    }
                    if let album = model.album {
                        if manage, model.selection.count == 1, let id = selectedIds.first {
                            Button {
                                Task { await setCover(album, id) }
                            } label: {
                                Label(L("albums.makeCover"), systemImage: "star")
                            }
                            .buttonStyle(.kf(.secondary))
                        }
                        if manage {
                            Button(L("albums.remove")) { Task { await removeFromAlbum(album, ids: selectedIds) } }.buttonStyle(.kf(.danger))
                        }
                    } else {
                        if manage, model.view == .visible {
                            Button {
                                albumSheet = .pick(selectedIds)
                            } label: {
                                Label(L("albums.addShort"), systemImage: "rectangle.stack.badge.plus")
                            }
                            .buttonStyle(.kf(.secondary))
                            .accessibilityLabel(L("albums.addToAlbum"))
                        }
                        switch model.view {
                        case .visible:
                            Button(L("library.hide")) { Task { await run(.hide, ids: Array(model.selection)) } }.buttonStyle(.kf(.secondary))
                            Button(L("library.trash")) { Task { await run(.trash, ids: Array(model.selection)) } }.buttonStyle(.kf(.danger))
                        case .hidden:
                            Button(L("library.unhide")) { Task { await run(.unhide, ids: Array(model.selection)) } }.buttonStyle(.kf(.primary))
                        case .trashed:
                            Button(L("library.restore")) { Task { await run(.restore, ids: Array(model.selection)) } }.buttonStyle(.kf(.primary))
                        }
                    }
                }
            }
        }
        .padding(12)
        .background(.bar, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("library.bulk"))
    }

    /// The album actions the photo sheet offers for a placement.
    private func albumActions(for p: Placement) -> PlacementSheet.AlbumActions? {
        guard manage, !p.trashed, p.pendingModeration != true else { return nil }
        if let album = model.album {
            guard model.placements.contains(where: { $0.id == p.id }) else { return nil }
            return .init(isCover: album.coverPlacementId == p.id, inAlbum: true,
                         makeCover: { Task { await setCover(album, p.id) } },
                         remove: { Task { await removeFromAlbum(album, ids: [p.id]) } },
                         add: {})
        }
        guard model.view == .visible else { return nil }
        return .init(isCover: false, inAlbum: false, makeCover: {}, remove: {},
                     add: { albumNext = .pick([p.id]); open = nil })
    }

    // MARK: Album sheets

    enum AlbumSheet: Identifiable {
        case create, rename, schedule, arrange
        case pick([String])

        var id: String {
            switch self {
            case .create: "create"
            case .rename: "rename"
            case .schedule: "schedule"
            case .arrange: "arrange"
            case .pick(let ids): "pick:" + ids.joined(separator: ",")
            }
        }
    }

    @ViewBuilder
    private func albumSheetView(_ sheet: AlbumSheet) -> some View {
        switch sheet {
        case .create:
            AlbumNameSheet(title: L("albums.newTitle"), submit: L("albums.create"), hint: L("albums.newHint"), initial: "") { name in
                let a = try await model.createAlbum(name: name)
                model.select(album: a.id)
                app.toasts.show(L("albums.created", ["name": name]))
            }
        case .rename:
            if let album = model.album {
                AlbumNameSheet(title: L("albums.renameTitle"), submit: L("common.save"), hint: nil, initial: album.name) { name in
                    try await model.rename(album, to: name)
                }
            }
        case .schedule:
            if let album = model.album {
                AlbumScheduleSheet(album: album) { from, to in
                    try await model.setSchedule(album, from: from, to: to)
                    if let from, let to {
                        app.toasts.show(L("albums.scheduleSaved", ["from": AlbumLogic.format(from, locale: L10n.locale),
                                                                   "to": AlbumLogic.format(to, locale: L10n.locale)]))
                    } else {
                        app.toasts.show(L("albums.scheduleCleared"))
                    }
                }
            }
        case .arrange:
            if let album = model.album {
                AlbumArrangeSheet(placements: model.placements) { order in
                    try await model.saveOrder(album, shown: order)
                    app.toasts.show(L("albums.orderSaved"))
                }
            }
        case .pick(let ids):
            AlbumPickerSheet(albums: model.albums, count: ids.count) { choice in
                try await addToAlbum(choice, ids: ids)
            }
        }
    }

    // MARK: Actions

    private func report(_ error: Error) {
        if error is AlbumConflict {
            app.toasts.show(L("albums.conflict"), isError: true)
        } else {
            app.toasts.error(error)
        }
    }

    private func togglePlaying(_ album: Album) async {
        playBusy = true
        defer { playBusy = false }
        do {
            let playing = try await model.togglePlaying(album)
            app.toasts.show(playing ? L("albums.nowPlaying", ["name": album.name, "frame": frame.name]) : L("albums.playingAll", ["frame": frame.name]))
        } catch {
            report(error)
        }
    }

    private func setCover(_ album: Album, _ id: String) async {
        do {
            try await model.setCover(album, placementId: id)
            withAnimation { model.selection = []; model.selecting = false }
            open = nil
            app.toasts.show(L("albums.coverSet"))
        } catch {
            report(error)
        }
    }

    private func deleteAlbum() async {
        guard let album = model.album else { return }
        do {
            try await model.deleteAlbum(album)
            app.toasts.show(L("albums.deleted", ["name": album.name]))
        } catch {
            report(error)
        }
    }

    private func addToAlbum(_ choice: AlbumPickerSheet.Choice, ids: [String]) async throws {
        switch choice {
        case .new(let name):
            let a = try await model.createAlbum(name: name, placementIds: ids)
            app.toasts.show(Lp("albums.added", count: ids.count, ["name": a.name]))
        case .existing(let album):
            let failed = try await model.albumBulk(add: true, albumId: album.id, ids: ids)
            if failed > 0 {
                app.toasts.show(Lp("library.someFailed", count: failed), isError: true)
            } else {
                app.toasts.show(Lp("albums.added", count: ids.count, ["name": album.name]))
            }
        }
        withAnimation { model.selection = []; model.selecting = false }
    }

    /// Takes effect at once, with Undo (adds them back).
    private func removeFromAlbum(_ album: Album, ids: [String]) async {
        open = nil
        do {
            _ = try await model.albumBulk(add: false, albumId: album.id, ids: ids)
            app.toasts.show(Lp("albums.removed", count: ids.count, ["name": album.name]), action: .init(title: L("common.undo")) {
                Task {
                    _ = try? await model.albumBulk(add: true, albumId: album.id, ids: ids)
                    await model.load()
                }
            })
        } catch {
            report(error)
        }
    }

    /// Hide and trash take effect at once, with Undo.
    private func run(_ action: LibraryModel.Action, ids: [String]) async {
        do {
            let failed = try await model.perform(action, on: ids)
            let done = ids.count - failed
            let undo: LibraryModel.Action? = switch action {
            case .hide: .unhide
            case .trash: .restore
            default: nil
            }
            if done > 0 {
                app.toasts.show(Lp("library.done_\(action.rawValue)", count: done), action: undo.map { u in
                    .init(title: L("common.undo")) { Task { _ = try? await model.perform(u, on: ids); await model.load() } }
                })
            }
            if failed > 0 { app.toasts.show(Lp("library.someFailed", count: failed), isError: true) }
        } catch {
            app.toasts.error(error)
        }
    }

    #if DEBUG
    /// `-kfSheet share|photo|albumNew|albumSelect|albumPick|albumSchedule|albumArrange|albumRename|albumPlay`
    /// (with `-kfAlbum <name or id>` to open an album first) for screenshots and manual tests.
    private func debugSheets() {
        if DebugLaunch.takeSheet("share"), canShare {
            model.selecting = true
            model.selection = Set(model.placements.prefix(3).map(\.id))
            sharing = ShareRequest(source: .init(frameId: frame.id, placementIds: model.placements.prefix(3).map(\.id)))
        }
        if DebugLaunch.takeSheet("photo") { open = model.placements.first }
        if DebugLaunch.takeSheet("albumNew") { albumSheet = .create }
        if DebugLaunch.takeSheet("albumSelect") {
            model.selecting = true
            model.selection = Set(model.placements.prefix(2).map(\.id))
        }
        if DebugLaunch.takeSheet("albumPick") {
            model.selecting = true
            model.selection = Set(model.placements.prefix(2).map(\.id))
            albumSheet = .pick(Array(model.placements.prefix(2).map(\.id)))
        }
        if model.album != nil {
            if DebugLaunch.takeSheet("albumSchedule") { albumSheet = .schedule }
            if DebugLaunch.takeSheet("albumArrange") { albumSheet = .arrange }
            if DebugLaunch.takeSheet("albumRename") { albumSheet = .rename }
            if DebugLaunch.takeSheet("albumPlay"), let album = model.album { Task { await togglePlaying(album) } }
        }
    }
    #endif
}

/// One item on the frame, large, with its details and actions.
struct PlacementSheet: View {
    struct AlbumActions {
        var isCover: Bool
        /// Looked at inside an album: make cover and remove; otherwise add to an album.
        var inAlbum: Bool
        var makeCover: () -> Void
        var remove: () -> Void
        var add: () -> Void
    }

    @Environment(\.dismiss) private var dismiss
    let api: API
    let placement: Placement
    let manage: Bool
    /// "Share to another frame", when there is a frame to share to.
    let share: (() -> Void)?
    var albumActions: AlbumActions?
    let act: (LibraryModel.Action) async -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    AsyncImage(url: placement.item.thumbUrl.flatMap(URL.init(string:))) { phase in
                        if let image = phase.image { image.resizable().scaledToFit() } else { Palette.surface2.aspectRatio(1, contentMode: .fit) }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    .overlay(alignment: .topLeading) { if placement.item.kind == .live { LiveBadge().padding(8) } }
                    .livePlayback(api: api, item: placement.item)
                    if let caption = placement.item.caption, !caption.isEmpty {
                        Text(caption).font(.title3).foregroundStyle(Palette.text)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("library.from", ["name": placement.item.senderName ?? "?"]))
                        if let sharer = placement.sharedByName {
                            Label(L("share.sharedBy", ["name": sharer]), systemImage: "arrowshape.turn.up.right")
                        }
                        Text(L("library.received", ["when": Format.dateTime(placement.receivedAt, locale: L10n.locale)]))
                        if let taken = placement.item.capturedAt {
                            Text(L("library.taken", ["when": Format.dateTime(taken, locale: L10n.locale)]))
                        }
                    }
                    .font(.subheadline)
                    .foregroundStyle(Palette.textMuted)
                    if let r = placement.reaction {
                        Text("\(r.emoji) \(L("reaction.\(r.rawValue)"))").chip()
                    }
                    HStack {
                        if let share {
                            Button(action: share) {
                                Label(L("share.actionShort"), systemImage: "arrowshape.turn.up.right")
                            }
                            .buttonStyle(.kf(.secondary))
                            .accessibilityLabel(L("share.action"))
                        }
                        if let a = albumActions {
                            if a.inAlbum {
                                if !a.isCover {
                                    Button(action: a.makeCover) { Label(L("albums.makeCover"), systemImage: "star") }.buttonStyle(.kf(.secondary))
                                }
                            } else {
                                Button(action: a.add) { Label(L("albums.addShort"), systemImage: "rectangle.stack.badge.plus") }
                                    .buttonStyle(.kf(.secondary))
                                    .accessibilityLabel(L("albums.addToAlbum"))
                            }
                        }
                    }
                    if let a = albumActions, a.inAlbum {
                        Button(role: .destructive, action: a.remove) { Label(L("albums.remove"), systemImage: "xmark") }.buttonStyle(.kf(.dangerGhost))
                    } else if manage {
                        HStack {
                            if placement.trashed {
                                button(L("library.restore"), .restore, .primary)
                            } else if placement.hidden {
                                button(L("library.unhide"), .unhide, .primary)
                            } else {
                                button(L("library.hide"), .hide, .secondary)
                                button(L("library.trash"), .trash, .danger)
                            }
                        }
                    }
                }
                .padding(16)
            }
            .pageBackground()
            .navigationTitle(placement.item.kind == .video ? L("ios.library.video") : L("library.photo"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.close")) { dismiss() } } }
        }
        .presentationDetents([.large])
    }

    private func button(_ title: String, _ action: LibraryModel.Action, _ kind: KFButtonKind) -> some View {
        Button(title) {
            Task {
                await act(action)
                dismiss()
            }
        }
        .buttonStyle(.kf(kind))
    }
}
