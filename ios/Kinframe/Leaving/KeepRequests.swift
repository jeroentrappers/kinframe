import KinframeKit
import SwiftUI

/// "Taken away by Tom" in a frame's trash (owner, admins, `manage_library`) or a family (owner and admins): one row per
/// taking with its count and the day the 30 days end, opening the photos and "Ask Tom to keep them".
struct TakenAwayCard: View {
    @Environment(AppModel.self) private var app
    let api: API
    let scope: LeavingScope
    /// Bumped by the parent to reload (a restore or a purge).
    var reloadKey: Int = 0
    @State private var groups: [TakenAwayGroup] = []
    @State private var open: TakenAwayGroup?

    var body: some View {
        Group {
            if !groups.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    SectionHeader(L("leaving.takenAwayTitle"))
                    ForEach(groups) { g in
                        Button { open = g } label: { row(g) }
                            .buttonStyle(.plain)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .card(padding: 16)
            }
        }
        .task(id: reloadKey) { await load() }
        .onLiveEvent(app.events) { e in
            guard case .leaving(let ev) = e else { return }
            switch ev {
            case .placementUpdated(let frameId, _, _, _) where frameId == scope.frameId: Task { await load() }
            case .keepRequestUpdated(let r) where r.frameId == scope.frameId || (scope.feedId != nil && r.feedId == scope.feedId): Task { await load() }
            default: break
            }
        }
        .sheet(item: $open, onDismiss: { Task { await load() } }) { g in
            TakenAwayGroupSheet(api: api, scope: scope, group: g)
        }
    }

    private func row(_ g: TakenAwayGroup) -> some View {
        HStack(spacing: 12) {
            TakenAwayThumbs(urls: g.previewThumbUrls ?? [])
            VStack(alignment: .leading, spacing: 2) {
                Text(LeavingText.groupTitle(g)).font(.headline).foregroundStyle(Palette.text)
                Text(LeavingText.groupDetail(g)).font(.subheadline).foregroundStyle(Palette.textMuted)
                if let r = g.keepRequest {
                    Text(LeavingText.requestStatus(r, person: g.byName)).font(.footnote).foregroundStyle(Palette.textMuted)
                } else if let why = LeavingText.cannotAsk(g) {
                    Text(why).font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.footnote).foregroundStyle(Palette.textMuted)
        }
        .contentShape(Rectangle())
    }

    private func load() async {
        if let list = try? await api.takenAway(scope) { groups = list }
    }
}

/// Up to three small thumbnails, overlapping.
private struct TakenAwayThumbs: View {
    let urls: [String]

    var body: some View {
        ZStack(alignment: .leading) {
            ForEach(Array(urls.prefix(3).enumerated()), id: \.offset) { i, url in
                RemoteThumb(url: url, cornerRadius: Radius.sm)
                    .frame(width: 44, height: 44)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous).stroke(Palette.surface, lineWidth: 2))
                    .offset(x: CGFloat(i) * 14)
            }
        }
        .frame(width: urls.isEmpty ? 0 : 44 + CGFloat(min(urls.count, 3) - 1) * 14, height: 44, alignment: .leading)
    }
}

/// One taking: the photos (a frame's, from `visibility=taken_away`), until when they wait, and "Ask Tom to keep them"
/// for all or a picked set, with an optional note.
struct TakenAwayGroupSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let scope: LeavingScope
    let group: TakenAwayGroup
    @State private var placements: [Placement] = []
    @State private var picking = false
    @State private var picked: Set<String> = []
    @State private var note = ""
    @State private var busy = false
    @State private var request: KeepRequest?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(LeavingText.groupDetail(group)).foregroundStyle(Palette.textMuted)
                    Text(L("leaving.group.explain", ["name": group.byName, "date": LeavingText.day(group.until)]))
                        .font(.subheadline).foregroundStyle(Palette.textMuted)
                    if !placements.isEmpty {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 4)], spacing: 4) {
                            ForEach(placements) { p in tile(p) }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    } else if let thumbs = group.previewThumbUrls, !thumbs.isEmpty {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 4)], spacing: 4) {
                            ForEach(thumbs, id: \.self) { RemoteThumb(url: $0).aspectRatio(1, contentMode: .fill).clipped() }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    }
                    ask
                }
                .padding(16)
                .frame(maxWidth: PageWidth.text, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .pageBackground()
            .navigationTitle(LeavingText.groupTitle(group))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.close")) { dismiss() } }
            }
            .task {
                request = group.keepRequest
                if let frameId = scope.frameId, let page = try? await api.takenAwayPlacements(frameId: frameId, takingId: group.takingId) {
                    placements = page.placements
                }
            }
        }
    }

    @ViewBuilder private var ask: some View {
        if let r = request {
            Callout(LeavingText.requestStatus(r, person: group.byName), kind: r.status == .declined || r.status == .expired ? .warn : .info)
        } else if group.canAsk {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(L("leaving.askToKeep", ["name": group.byName]))
                Text(L("leaving.ask.body", ["name": group.byName])).font(.subheadline).foregroundStyle(Palette.textMuted)
                if !placements.isEmpty {
                    Toggle(L("leaving.askPick"), isOn: $picking.animation()).tint(Palette.accent)
                    if picking { Text(Lp("leaving.ask.picked", count: picked.count)).font(.footnote).foregroundStyle(Palette.textMuted) }
                }
                TextField(L("leaving.ask.notePlaceholder"), text: $note, axis: .vertical)
                    .lineLimit(1...4)
                    .padding(10)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                Button {
                    Task { await askNow() }
                } label: {
                    Text(L("leaving.askToKeep", ["name": group.byName]))
                }
                .buttonStyle(.kf(.primary))
                .disabled(busy || (picking && picked.isEmpty))
            }
            .card(padding: 16)
        } else if let why = LeavingText.cannotAsk(group) {
            Callout(why)
        }
    }

    private func tile(_ p: Placement) -> some View {
        let on = picked.contains(p.item.id)
        return RemoteThumb(url: p.item.thumbUrl, kind: p.item.kind)
            .aspectRatio(1, contentMode: .fill)
            .clipped()
            .overlay(alignment: .topTrailing) {
                if picking {
                    Image(systemName: on ? "checkmark.circle.fill" : "circle")
                        .font(.title3).foregroundStyle(on ? Palette.accent : .white).shadow(radius: 2).padding(6)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                guard picking else { return }
                if on { picked.remove(p.item.id) } else { picked.insert(p.item.id) }
            }
            .accessibilityAddTraits(picking ? .isButton : [])
            .accessibilityValue(picking && on ? L("leaving.ask.selected") : "")
    }

    private func askNow() async {
        busy = true
        defer { busy = false }
        do {
            request = try await api.askToKeep(scope, takingId: group.takingId, itemIds: picking ? Array(picked) : nil, note: note)
            app.toasts.show(L("leaving.ask.sent", ["name": group.byName]))
        } catch {
            app.toasts.error(error)
        }
    }
}

/// A keep request to you (story 3): the photos asked for, then allow all, allow the ones you pick, or decline. Final.
struct KeepRequestAnswerSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let requestId: String
    let onAnswered: () -> Void
    @State private var request: KeepRequest?
    @State private var error: String?
    @State private var picking = false
    @State private var picked: Set<String> = []
    @State private var busy = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let r = request {
                        content(r)
                    } else if let error {
                        ErrorRetryView(message: error) { Task { await load() } }
                    } else {
                        LoadingView()
                    }
                }
                .padding(16)
                .frame(maxWidth: PageWidth.text, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .pageBackground()
            .navigationTitle(L("leaving.toMe.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.close")) { dismiss() }.disabled(busy) }
            }
            .task { await load() }
        }
    }

    @ViewBuilder private func content(_ r: KeepRequest) -> some View {
        Text(LeavingText.requestToMe(r)).font(.headline).foregroundStyle(Palette.text)
        if let note = r.note, !note.isEmpty { Text("“\(note)”").italic() }
        Text(L("leaving.toMe.until", ["date": LeavingText.day(r.expiresAt)])).font(.subheadline).foregroundStyle(Palette.textMuted)
        let items = r.items ?? []
        if !items.isEmpty {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 4)], spacing: 4) {
                ForEach(items, id: \.item.id) { entry in tile(entry.item, allowed: entry.allowed) }
            }
            .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        }
        if r.status == .open {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("leaving.toMe.explain")).font(.subheadline).foregroundStyle(Palette.textMuted)
                if picking {
                    Text(Lp("leaving.ask.picked", count: picked.count)).font(.footnote).foregroundStyle(Palette.textMuted)
                    Button(L("leaving.toMe.allowPicked")) { Task { await answer(.some(Array(picked))) } }
                        .buttonStyle(.kf(.primary)).disabled(busy || picked.isEmpty)
                    Button(L("common.cancel")) { picking = false; picked = [] }.buttonStyle(.kf(.ghost))
                } else {
                    Button(L("leaving.allowAll")) { Task { await answer(.all) } }.buttonStyle(.kf(.primary)).disabled(busy)
                    if items.count > 1 {
                        Button(L("leaving.allowSome")) { picking = true }.buttonStyle(.kf(.secondary)).disabled(busy)
                    }
                    Button(L("leaving.allowNone"), role: .destructive) { Task { await answer(.none) } }
                        .buttonStyle(.kf(.dangerGhost)).disabled(busy)
                }
            }
        } else {
            Callout(LeavingText.requestStatus(r, person: L("social.you")))
        }
    }

    private func tile(_ item: Item, allowed: Bool?) -> some View {
        let on = picked.contains(item.id)
        return RemoteThumb(url: item.thumbUrl, kind: item.kind)
            .aspectRatio(1, contentMode: .fill)
            .clipped()
            .opacity(allowed == false ? 0.4 : 1)
            .overlay(alignment: .topTrailing) {
                if picking {
                    Image(systemName: on ? "checkmark.circle.fill" : "circle")
                        .font(.title3).foregroundStyle(on ? Palette.accent : .white).shadow(radius: 2).padding(6)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                guard picking else { return }
                if on { picked.remove(item.id) } else { picked.insert(item.id) }
            }
    }

    private func load() async {
        error = nil
        do { request = try await api.keepRequest(requestId) } catch { self.error = errorMessage(error) }
    }

    private func answer(_ a: KeepAnswer) async {
        busy = true
        defer { busy = false }
        do {
            request = try await api.answerKeepRequest(requestId, a)
            app.toasts.show(L(a == .none ? "leaving.toMe.declined" : "leaving.toMe.allowed"))
            onAnswered()
        } catch {
            app.toasts.error(error)
        }
    }
}
