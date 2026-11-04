import KinframeKit
import SwiftUI

/// "Share to another frame" (README §5.3, web: ShareDialog): pick frames you can send to, with a dry run marking the
/// ones that already have the photos, then share without uploading again. Undo purges exactly what was created.
@MainActor
@Observable
final class ShareToFrameModel {
    struct Target: Identifiable {
        let frame: FrameSummary
        let coverage: Sharing.Coverage
        var id: String { frame.id }
    }

    enum Outcome {
        case shared(frames: [String], created: [Placement])
        case nothingNew
    }

    let api: API
    let source: Sharing.Source
    private(set) var targets: [Target]?
    var chosen: Set<String> = []
    private(set) var busy = false
    var error: String?

    init(api: API, source: Sharing.Source) {
        self.api = api
        self.source = source
    }

    func load(frames: [FrameSummary]) async {
        error = nil
        let candidates = Sharing.targets(frames, excluding: source.frameId).sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        var coverage: [String: Sharing.Coverage] = [:]
        if !candidates.isEmpty {
            let source = source, ids = candidates.map(\.id)
            // A failed dry run only loses the "Already there" marks.
            if let result = try? await api.call({
                try await $0.sharePlacements(path: .init(frameId: source.frameId),
                                             body: .json(.init(placementIds: source.placementIds, frameIds: ids, dryRun: true))).ok.body.json
            }) {
                coverage = Sharing.coverage(skipped: result.skipped.map { ($0.placementId, $0.frameId) }, frameIds: ids,
                                            selectionSize: source.placementIds.count)
            }
        }
        targets = candidates.map { Target(frame: $0, coverage: coverage[$0.id] ?? .none) }
    }

    func toggle(_ id: String) {
        guard let t = targets?.first(where: { $0.id == id }), t.coverage != .all else { return }
        if chosen.contains(id) { chosen.remove(id) } else { chosen.insert(id) }
    }

    func share() async throws -> Outcome {
        busy = true
        defer { busy = false }
        let source = source
        let ids = (targets ?? []).map(\.id).filter { chosen.contains($0) }
        let key = UUID().uuidString.lowercased()
        let result = try await api.call {
            try await $0.sharePlacements(path: .init(frameId: source.frameId), headers: .init(idempotencyKey: key),
                                         body: .json(.init(placementIds: source.placementIds, frameIds: ids))).ok.body.json
        }
        if result.placements.isEmpty { return .nothingNew }
        let names = (targets ?? []).filter { t in result.placements.contains { $0.frameId == t.id } }.map(\.frame.name)
        return .shared(frames: names, created: result.placements)
    }

    /// The sharer may purge the placements they created.
    static func undo(api: API, created: [Placement]) async throws {
        for (frameId, placements) in Dictionary(grouping: created, by: \.frameId) {
            let ids = placements.map(\.id)
            _ = try await api.call {
                try await $0.bulkUpdatePlacements(path: .init(frameId: frameId), body: .json(.init(placementIds: ids, action: .purge))).ok
            }
        }
    }
}

struct ShareToFrameSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var model: ShareToFrameModel
    /// Called after a share went through (e.g. to end the library's selection).
    var onShared: () -> Void = {}

    init(api: API, source: Sharing.Source, onShared: @escaping () -> Void = {}) {
        _model = State(initialValue: ShareToFrameModel(api: api, source: source))
        self.onShared = onShared
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(L("share.title")).font(.display(.title2)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
                    if let targets = model.targets {
                        if targets.isEmpty {
                            Text(L("share.noFrames")).foregroundStyle(Palette.textMuted)
                        } else {
                            Text(L("share.hint")).font(.footnote).foregroundStyle(Palette.textMuted)
                                .fixedSize(horizontal: false, vertical: true)
                            VStack(spacing: 8) {
                                ForEach(targets) { row($0) }
                            }
                            .accessibilityElement(children: .contain)
                            .accessibilityLabel(L("share.pick"))
                        }
                    } else {
                        LoadingView()
                    }
                }
                .padding(16)
            }
            .pageBackground()
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task { await share() }
                    } label: {
                        if model.busy { ProgressView() } else { Text(L("share.submit")) }
                    }
                    .fontWeight(.semibold)
                    .disabled(model.chosen.isEmpty || model.busy)
                }
            }
            .task { await model.load(frames: app.frames) }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ t: ShareToFrameModel.Target) -> some View {
        let on = model.chosen.contains(t.id)
        let full = t.coverage == .all
        return Button {
            withAnimation(.snappy) { model.toggle(t.id) }
            UISelectionFeedbackGenerator().selectionChanged()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: on || full ? "checkmark.circle.fill" : "circle")
                    .font(.title2)
                    .foregroundStyle(on ? Palette.primary : full ? Palette.textFaint : Palette.borderStrong)
                    .frame(width: 30)
                Text(t.frame.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(full ? Palette.textFaint : Palette.text)
                    .lineLimit(1)
                Spacer()
                switch t.coverage {
                case .all: Pill(L("share.alreadyThere"))
                case .some: Pill(L("share.partly"), foreground: Palette.warn, background: Palette.warnSoft)
                case .none: EmptyView()
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(minHeight: 56)
            .background(on ? Palette.primarySoft.opacity(0.5) : Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(on ? Palette.primary : Palette.border, lineWidth: on ? 1.5 : 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(full)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func share() async {
        do {
            switch try await model.share() {
            case .nothingNew:
                app.toasts.show(L("share.nothingNew"))
            case .shared(let frames, let created):
                let api = model.api, toasts = app.toasts
                app.toasts.show(L("share.done", ["frames": frames.joined(separator: ", ")]), action: .init(title: L("common.undo")) {
                    Task {
                        do {
                            try await ShareToFrameModel.undo(api: api, created: created)
                            toasts.show(L("share.undone"))
                        } catch {
                            toasts.error(error)
                        }
                    }
                })
                onShared()
            }
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }
}

/// What a share sheet is opened for (`.sheet(item:)`).
struct ShareRequest: Identifiable {
    let source: Sharing.Source
    var id: String { source.frameId + ":" + source.placementIds.joined(separator: ",") }
}
