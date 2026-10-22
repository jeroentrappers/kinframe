import KinframeKit
import SwiftUI

/// One send: delivery per frame, each item with its caption (editable) and status, and unsend with undo.
struct SendDetailView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State var model: SendDetailModel
    var onRemoved: () -> Void
    @State private var editing: Item?
    @State private var removed = false
    @State private var commitUnsend: Task<Void, Never>?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let error = model.error {
                    Callout(error, kind: .error)
                } else if removed {
                    removedCard
                } else if let send = model.send {
                    header(send)
                    delivery(send)
                    ForEach(Array(send.items.enumerated()), id: \.element.id) { n, item in
                        itemRow(item, n: n + 1, send: send)
                    }
                } else {
                    LoadingView()
                }
            }
            .padding(16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .navigationTitle(L("detail.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if model.send != nil, !removed {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) {
                        unsend()
                    } label: {
                        Label(L("detail.unsend"), systemImage: "trash")
                    }
                    .tint(Palette.danger)
                }
            }
        }
        .task { await model.load() }
        .refreshable { await model.load() }
        .onLiveEvent(app.events) { model.apply($0) }
        .sheet(item: $editing) { item in
            CaptionEditor(item: item, number: (model.send?.items.firstIndex { $0.id == item.id } ?? 0) + 1) { text in
                do {
                    try await model.saveCaption(itemId: item.id, caption: text)
                    app.toasts.show(L("detail.captionSaved"))
                    return true
                } catch {
                    app.toasts.error(error)
                    return false
                }
            }
        }
    }

    private func header(_ send: Send) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(kindSummary(send.items)).font(.display(.title)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
            Text(L("detail.sentAt", ["when": Format.dateTime(send.createdAt, locale: L10n.locale)])).foregroundStyle(Palette.textMuted)
        }
    }

    private func delivery(_ send: Send) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Delivery.frames(of: send)) { d in
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        StatusDot(online: d.online)
                        Text(d.frameName).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                        Spacer()
                        StagePill(stage: d.stage)
                    }
                    Text(L("detail.counts", ["seen": d.counts.seen, "delivered": d.counts.arrived, "total": d.counts.total])
                        + (!d.online && d.stage != .seen ? " · " + L("detail.offlineWaiting") : ""))
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(Palette.textMuted)
                    ProgressView(value: d.progress).tint(Palette.accent).accessibilityHidden(true)
                    ReactionChips(reactions: d.reactions, replies: d.replies)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .card(padding: 16)
        .accessibilityLabel(L("detail.deliveryLabel"))
    }

    private func itemRow(_ item: Item, n: Int, send: Send) -> some View {
        HStack(alignment: .top, spacing: 14) {
            ItemThumb(item: item)
                .frame(width: 96, height: 96)
                .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                .overlay(alignment: .bottomLeading) {
                    if item.kind == .video, let ms = item.durationMs {
                        Text(Format.duration(Double(ms) / 1000)).font(.caption2.weight(.bold).monospacedDigit())
                            .foregroundStyle(.white).padding(.horizontal, 5).padding(.vertical, 2)
                            .background(Capsule().fill(.black.opacity(0.5))).padding(5)
                    }
                }
                .accessibilityLabel(item.caption ?? L("detail.itemAlt", ["n": n]))
            VStack(alignment: .leading, spacing: 8) {
                if item.status == .failed {
                    Text(failureMessage(item.failureCode))
                        .font(.subheadline.weight(.medium)).foregroundStyle(Palette.danger)
                }
                if let caption = item.caption, !caption.isEmpty {
                    Text(caption).foregroundStyle(Palette.text)
                } else {
                    Text(L("detail.noCaption")).italic().foregroundStyle(Palette.textFaint)
                }
                HStack(spacing: 8) {
                    if item.captionEdited == true { Pill(L("detail.edited")) }
                    Spacer()
                    Button {
                        editing = item
                    } label: {
                        Label(L("common.edit"), systemImage: "pencil")
                    }
                    .buttonStyle(.kf(.ghost))
                    .accessibilityLabel(L("detail.editCaptionN", ["n": n]))
                }
                ForEach(send.frames, id: \.frameId) { f in
                    if let p = f.placements.first(where: { $0.itemId == item.id }) {
                        HStack(spacing: 6) {
                            Text(f.frameName).font(.caption).foregroundStyle(Palette.textMuted).lineLimit(1)
                            Spacer()
                            StagePill(stage: Stage(status: p.status, itemStatus: item.status))
                            if let r = p.reaction { Text(r.emoji).accessibilityLabel(L("reaction.\(r.rawValue)")) }
                        }
                    }
                }
            }
        }
        .card(padding: 12)
    }

    private var removedCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: "checkmark.circle.fill").font(.largeTitle).foregroundStyle(Palette.accent).accessibilityHidden(true)
            Text(L("detail.removedTitle")).font(.display(.title2))
            Text(L("detail.removedBody")).foregroundStyle(Palette.textMuted)
            HStack {
                Button(L("detail.removeNow")) { commitNow() }.buttonStyle(.kf(.secondary))
                Button(L("history.title")) { dismiss() }.buttonStyle(.kf(.primary))
            }
        }
        .card(padding: 20)
    }

    /// Undo instead of "are you sure": remove from view now, delete on the server when the toast goes away.
    private func unsend() {
        withAnimation { removed = true }
        app.toasts.show(L("detail.unsent"), action: .init(title: L("common.undo")) {
            commitUnsend?.cancel()
            commitUnsend = nil
            withAnimation { removed = false }
        })
        commitUnsend = Task {
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled else { return }
            await commit()
        }
    }

    private func commitNow() {
        commitUnsend?.cancel()
        commitUnsend = Task { await commit() }
        app.toasts.dismiss()
    }

    private func commit() async {
        do {
            try await model.unsend()
            onRemoved()
        } catch {
            withAnimation { removed = false }
            app.toasts.error(error)
        }
    }
}

private struct CaptionEditor: View {
    @Environment(\.dismiss) private var dismiss
    let item: Item
    let number: Int
    let save: (String) async -> Bool
    @State private var text = ""
    @State private var saving = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                TextField(L("send.captionPlaceholder"), text: $text, axis: .vertical)
                    .lineLimit(3...8)
                    .focused($focused)
                    .padding(12)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Format.captionRemaining(text) < 0 ? Palette.danger : Palette.borderStrong))
                    .accessibilityLabel(L("send.captionFor", ["n": number]))
                Text(L("send.charsLeft", ["n": Format.captionRemaining(text), "max": Format.captionMax]))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Format.captionRemaining(text) < 0 ? Palette.danger : Palette.textMuted)
                Spacer()
            }
            .padding(16)
            .pageBackground()
            .navigationTitle(L("detail.editCaptionN", ["n": number]))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("common.save")) {
                        saving = true
                        Task {
                            if await save(text) { dismiss() }
                            saving = false
                        }
                    }
                    .fontWeight(.semibold)
                    .disabled(saving || Format.captionRemaining(text) < 0)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear {
            text = item.caption ?? ""
            focused = true
        }
    }
}

extension Components.Schemas.Item: Identifiable {}

/// Why an item failed (web: failureMessage).
func failureMessage(_ code: String?) -> String {
    let key = "failure.\(code ?? "unknown")"
    return L10n.has(key) ? L(key) : L("failure.unknown")
}
