import KinframeKit
import SwiftUI

/// The frame's library (web: FrameLibrary): what's on the frame, hidden and trash, with hide / trash / restore for
/// single items or a selection. Members without see_library see their own photos.
@MainActor
@Observable
final class LibraryModel {
    enum View: String, CaseIterable, Identifiable {
        case visible, hidden, trashed
        var id: String { rawValue }
        var api: Operations.ListPlacements.Input.Query.VisibilityPayload {
            switch self {
            case .visible: .visible
            case .hidden: .hidden
            case .trashed: .trashed
            }
        }
    }

    enum Action: String {
        case hide, unhide, trash, restore
        var api: Operations.BulkUpdatePlacements.Input.Body.JsonPayload.ActionPayload {
            switch self {
            case .hide: .hide
            case .unhide: .unhide
            case .trash: .trash
            case .restore: .restore
            }
        }
    }

    let api: API
    let frameId: String
    var view: View = .visible
    private(set) var placements: [Placement] = []
    private(set) var cursor: String?
    private(set) var loading = false
    var error: String?
    var selecting = false
    var selection: Set<String> = []

    init(api: API, frameId: String) {
        self.api = api
        self.frameId = frameId
    }

    func load() async {
        loading = placements.isEmpty
        defer { loading = false }
        let id = frameId, v = view.api
        do {
            let page = try await api.call { try await $0.listPlacements(path: .init(frameId: id), query: .init(limit: 60, visibility: v)).ok.body.json }
            placements = page.placements
            cursor = page.nextCursor
            error = nil
        } catch is CancellationError {
        } catch {
            self.error = errorMessage(error)
        }
    }

    func loadMore() async {
        guard let cursor else { return }
        let id = frameId, v = view.api
        if let page = try? await api.call({ try await $0.listPlacements(path: .init(frameId: id), query: .init(cursor: cursor, limit: 60, visibility: v)).ok.body.json }) {
            placements.append(contentsOf: page.placements)
            self.cursor = page.nextCursor
        }
    }

    /// Apply to the given placements; returns how many failed. They leave this view right away.
    func perform(_ action: Action, on ids: [String]) async throws -> Int {
        let frameId = frameId
        let result = try await api.call {
            try await $0.bulkUpdatePlacements(path: .init(frameId: frameId), body: .json(.init(placementIds: ids, action: action.api))).ok.body.json
        }
        let ok = Set(result.ok ?? [])
        placements.removeAll { ok.contains($0.id) }
        selection.subtract(ok)
        if selection.isEmpty { selecting = false }
        return result.failed?.count ?? 0
    }
}

struct FrameLibrarySection: View {
    @Environment(AppModel.self) private var app
    let frame: FrameSummary
    @State private var model: LibraryModel
    @State private var open: Placement?

    init(api: API, frame: FrameSummary) {
        self.frame = frame
        _model = State(initialValue: LibraryModel(api: api, frameId: frame.id))
    }

    private var manage: Bool { frame.can(\.manageLibrary) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Picker(L("library.filter"), selection: $model.view) {
                    Text(L("library.visible")).tag(LibraryModel.View.visible)
                    Text(L("library.hidden")).tag(LibraryModel.View.hidden)
                    Text(L("library.trashTab")).tag(LibraryModel.View.trashed)
                }
                .pickerStyle(.segmented)
                if !model.placements.isEmpty {
                    Button(model.selecting ? L("common.done") : L("library.select")) {
                        withAnimation { model.selecting.toggle(); model.selection = [] }
                    }
                    .font(.subheadline.weight(.semibold))
                    .frame(minHeight: 44)
                }
            }
            if !frame.can(\.seeLibrary) {
                Callout(L("library.ownOnly"))
            }
            if model.view == .trashed {
                Text(L("library.trashNote")).font(.footnote).foregroundStyle(Palette.textMuted)
            }

            if model.loading {
                LoadingView()
            } else if let error = model.error {
                ErrorRetryView(message: error) { Task { await model.load() } }
            } else if model.placements.isEmpty {
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
        .task(id: model.view) { await model.load() }
        .sheet(item: $open) { p in
            PlacementSheet(placement: p, manage: manage || p.item.senderId == app.me?.id) { action in
                await run(action, ids: [p.id])
            }
        }
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
                .overlay { RemoteThumb(url: p.item.thumbUrl, kind: p.item.kind == .video ? .video : .photo) }
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
                    }
                }
                .clipped()
                .opacity(model.selecting && !selected ? 0.75 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("library.itemBy", ["name": p.item.senderName ?? "?"]))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var bulkBar: some View {
        HStack(spacing: 8) {
            Text(Lp("library.selected", count: model.selection.count)).font(.subheadline.weight(.semibold))
            Spacer()
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
        .padding(12)
        .background(.bar, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("library.bulk"))
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
}

/// One item on the frame, large, with its details and actions.
private struct PlacementSheet: View {
    @Environment(\.dismiss) private var dismiss
    let placement: Placement
    let manage: Bool
    let act: (LibraryModel.Action) async -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    AsyncImage(url: placement.item.thumbUrl.flatMap(URL.init(string:))) { phase in
                        if let image = phase.image { image.resizable().scaledToFit() } else { Palette.surface2.aspectRatio(1, contentMode: .fit) }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    if let caption = placement.item.caption, !caption.isEmpty {
                        Text(caption).font(.title3).foregroundStyle(Palette.text)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("library.from", ["name": placement.item.senderName ?? "?"]))
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
                    if manage {
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
