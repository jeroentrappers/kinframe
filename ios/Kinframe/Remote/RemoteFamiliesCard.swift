import KinframeKit
import SwiftUI

/// Frame settings → Families on other servers (README §5.22): the families on other kinframe servers this frame
/// follows, with their status, copy progress and last sync, the on-end choice and unsubscribe; and "Add a family from
/// another server". Only when discovery says `remote_families`.
struct RemoteFamiliesCard: View {
    @Environment(AppModel.self) private var app
    let api: API
    let frame: FrameSummary
    @State private var list: [RemoteSubscription] = []
    @State private var loaded = false
    @State private var adding = false
    @State private var busy: String?
    @State private var unsubscribing: RemoteSubscription?
    @State private var showEnded = false

    private var canManage: Bool { frame.can(\.changeSettings) }
    private var current: [RemoteSubscription] { list.filter { RemoteSubscriptionLogic.isLive($0.status) } }
    private var ended: [RemoteSubscription] { list.filter { !RemoteSubscriptionLogic.isLive($0.status) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("servers.remote.title"), trailing: canManage ? AnyView(addButton) : nil)
            if loaded, current.isEmpty {
                Text(L("servers.remote.none"))
                    .font(.subheadline)
                    .foregroundStyle(Palette.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .card(padding: 14)
            }
            ForEach(current) { row($0) }
            if !ended.isEmpty {
                DisclosureGroup(isExpanded: $showEnded) {
                    ForEach(ended) { row($0) }
                } label: {
                    Text(Lp("servers.remote.ended", count: ended.count)).font(.subheadline).foregroundStyle(Palette.textMuted)
                }
            }
        }
        .task { await reload() }
        .onLiveEvent(app.events) { event in
            if case .remoteSubscriptionUpdated(let frameId, let s) = event, frameId == frame.id {
                if let s { upsert(s) } else { Task { await reload() } }
            }
        }
        .sheet(isPresented: $adding) {
            RemoteFollowSheet(api: api, frame: frame) { upsert($0) }
        }
        .confirmationDialog(unsubscribing.map { L("servers.remote.unsubscribeTitle", ["feed": $0.feedName]) } ?? "",
                            isPresented: Binding(get: { unsubscribing != nil }, set: { if !$0 { unsubscribing = nil } }),
                            titleVisibility: .visible, presenting: unsubscribing) { s in
            Button(L("servers.remote.unsubscribeRemove"), role: .destructive) { Task { await unsubscribe(s, .remove) } }
            Button(L("servers.remote.unsubscribeKeep")) { Task { await unsubscribe(s, .keep) } }
            Button(L("common.cancel"), role: .cancel) {}
        }
    }

    private var addButton: some View {
        Button { adding = true } label: {
            Label(L("servers.remote.add"), systemImage: "globe")
                .font(.subheadline.weight(.semibold))
                .frame(minHeight: 44)
        }
        .accessibilityLabel(L("servers.remote.add"))
    }

    private func row(_ s: RemoteSubscription) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                FeedCover(url: s.coverUrl, name: s.feedName, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.feedName).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                    Label(RemoteInvite.host(s.server), systemImage: "globe").font(.footnote).foregroundStyle(Palette.textMuted)
                    Text(detail(s)).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                RemoteStatusPill(status: s.status)
            }
            if s.status == .error, let e = s.lastError, !e.isEmpty {
                Callout(e, kind: .warn)
            }
            if let p = RemoteSubscriptionLogic.backfillProgress(s) {
                ProgressView(value: p) {
                    Text(L("servers.remote.backfill", ["placed": s.backfill?.placed ?? 0, "total": s.backfill?.total ?? 0]))
                        .font(.caption).foregroundStyle(Palette.textMuted)
                }
                .tint(Palette.accent)
            }
            if canManage, RemoteSubscriptionLogic.isLive(s.status) {
                Picker(L("servers.remote.onEnd.title"), selection: Binding(get: { s.onEnd }, set: { v in Task { await setOnEnd(s, v) } })) {
                    Text(L("servers.remote.onEnd.remove")).tag(RemoteOnEnd.remove)
                    Text(L("servers.remote.onEnd.keep")).tag(RemoteOnEnd.keep)
                }
                .font(.subheadline)
                .disabled(busy == s.id)
                Button(role: .destructive) { unsubscribing = s } label: {
                    Label(s.status == .pending ? L("servers.remote.withdraw") : L("servers.remote.unsubscribe"), systemImage: "minus.circle")
                }
                .buttonStyle(.kf(.dangerGhost))
                .disabled(busy == s.id)
            }
        }
        .card(padding: 14)
        .opacity(RemoteSubscriptionLogic.isLive(s.status) ? 1 : 0.7)
        .accessibilityElement(children: .contain)
    }

    private func detail(_ s: RemoteSubscription) -> String {
        var parts = [RemoteSubscriptionLogic.statusText(s)]
        if s.status == .active || s.status == .error {
            if let n = s.itemCount { parts.append(Lp("servers.remote.copied", count: n)) }
            if let at = s.lastSyncAt { parts.append(L("servers.remote.lastSync", ["when": Format.relative(at)])) }
            parts.append(HistoryChoice(history: s.history, count: s.historyCount).label)
        }
        return parts.joined(separator: " · ")
    }

    private func upsert(_ s: RemoteSubscription) {
        list = RemoteSubscriptionLogic.sorted(list.filter { $0.id != s.id } + [s])
    }

    private func reload() async {
        if let fresh = try? await api.remoteSubscriptions(frameId: frame.id) {
            list = RemoteSubscriptionLogic.sorted(fresh)
        }
        loaded = true
    }

    private func setOnEnd(_ s: RemoteSubscription, _ v: RemoteOnEnd) async {
        guard v != s.onEnd else { return }
        busy = s.id
        defer { busy = nil }
        do {
            upsert(try await api.updateRemoteSubscription(frameId: frame.id, id: s.id, onEnd: v))
            app.toasts.show(L("common.saved"))
        } catch {
            app.toasts.error(error)
        }
    }

    private func unsubscribe(_ s: RemoteSubscription, _ placements: RemoteOnEnd) async {
        busy = s.id
        defer { busy = nil }
        do {
            try await api.deleteRemoteSubscription(frameId: frame.id, id: s.id, placements: placements)
            app.toasts.show(L("servers.remote.unsubscribed", ["feed": s.feedName, "frame": frame.name]))
            await reload()
        } catch {
            app.toasts.error(error)
        }
    }
}

struct RemoteStatusPill: View {
    let status: RemoteSubscriptionStatus

    var body: some View {
        let (key, icon, fg, bg): (String, String, Color, Color) = switch status {
        case .pending: ("ios.feeds.status.pending", "hourglass", Palette.warn, Palette.warnSoft)
        case .active: ("ios.feeds.status.active", "checkmark", Palette.accentText, Palette.accentSoft)
        case .error: ("servers.remote.pill.error", "exclamationmark.triangle", Palette.warn, Palette.warnSoft)
        case .rejected: ("ios.feeds.status.rejected", "xmark", Palette.danger, Palette.dangerSoft)
        case .removed: ("ios.feeds.status.removed", "minus", Palette.textMuted, Palette.surface2)
        }
        Pill(L(key), systemImage: icon, foreground: fg, background: bg)
    }
}
