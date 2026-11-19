import KinframeKit
import SwiftUI

/// The photo sources of an album (album managers) or a feed (owner and admins), or the ones you set up: each with its
/// status, last and next sync, counts, warnings and the fix for an error as a button; Sync now, pause, resume, edit,
/// take over and stop syncing (keeping or removing the imported photos).
struct PhotoSourcesCard: View {
    @Environment(AppModel.self) private var app
    let model: PhotoSourcesModel
    /// Offer "Add" (album managers, feed owner/admins, while under the limit).
    var canAdd = true
    /// Show where each source syncs to (Settings → Photo sources).
    var showTarget = false
    var title: String = L("ios.ps.title")
    var footer: String?
    let add: () -> Void
    let edit: (PhotoSource, PhotoSourceSheet.Focus) -> Void

    @State private var stopping: PhotoSource?
    @State private var confirmingRemovals: PhotoSource?

    private var atLimit: Bool { model.target != nil && model.sources.count >= app.limits.maxPhotoSourcesPerTarget }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title, trailing: canAdd && model.target != nil && !atLimit ? AnyView(
                Button(action: add) { Label(L("ios.ps.add"), systemImage: "plus") }.buttonStyle(.kf(.soft))
            ) : nil)
            if !model.loaded {
                ProgressView().frame(maxWidth: .infinity)
            } else if let error = model.error {
                ErrorRetryView(message: error) { Task { await model.load() } }
            } else if model.sources.isEmpty {
                Text(model.target?.isFeed == true ? L("ios.ps.emptyFeed") : (model.target == nil ? L("ios.ps.emptyMine") : L("ios.ps.emptyAlbum")))
                    .font(.subheadline).foregroundStyle(Palette.textMuted)
            } else {
                ForEach(model.sources) { s in
                    PhotoSourceRow(source: s, busy: model.isBusy(s), showTarget: showTarget, limits: app.limits,
                                   act: { act($0, on: s) })
                    if s.id != model.sources.last?.id { Divider() }
                }
            }
            if atLimit, canAdd {
                Text(L("ios.ps.limit", ["count": "\(app.limits.maxPhotoSourcesPerTarget)"])).font(.footnote).foregroundStyle(Palette.textMuted)
            }
            if let footer { Text(footer).font(.footnote).foregroundStyle(Palette.textMuted) }
        }
        .card(padding: 16)
        .confirmationDialog(L("ios.ps.stopTitle", ["name": stopping?.name ?? ""]), isPresented: Binding(get: { stopping != nil }, set: { if !$0 { stopping = nil } }),
                            titleVisibility: .visible, presenting: stopping) { s in
            Button(Lp("ios.ps.stopKeep", count: s.counts.imported)) { Task { await stop(s, removeItems: false) } }
            if s.counts.imported > 0 {
                Button(Lp("ios.ps.stopRemove", count: s.counts.imported), role: .destructive) { Task { await stop(s, removeItems: true) } }
            }
            Button(L("common.cancel"), role: .cancel) {}
        } message: { s in
            Text(s.isFeedTarget ? L("ios.ps.stopBodyFeed") : L("ios.ps.stopBodyAlbum"))
        }
        .alert(Lp("ios.ps.massTitle", count: confirmingRemovals?.pendingRemovals ?? 0, ["service": confirmingRemovals?.serviceLabel ?? ""]),
               isPresented: Binding(get: { confirmingRemovals != nil }, set: { if !$0 { confirmingRemovals = nil } }), presenting: confirmingRemovals) { s in
            Button(L("ios.ps.massRemove"), role: .destructive) { Task { await perform(s) { try await model.syncNow(s, confirmRemovals: true) } } }
            Button(L("ios.ps.massKeep")) {
                guard let api = app.api else { return }
                Task { await perform(s) { model.upsert(try await api.updatePhotoSource(s.id, patch: ["on_upstream_delete": .string(SourceDeletePolicy.keep.rawValue)])) } }
            }
            Button(L("common.cancel"), role: .cancel) {}
        } message: { _ in
            Text(L("ios.ps.massBody"))
        }
        .onLiveEvent(app.events) { model.apply($0) }
    }

    private func act(_ action: PhotoSourceRow.Action, on s: PhotoSource) {
        switch action {
        case .syncNow:
            Task { await perform(s) { try await model.syncNow(s) } }
        case .pause:
            Task { await perform(s, done: L("ios.ps.paused", ["name": s.name])) { try await model.setPaused(s, true) } }
        case .resume:
            Task { await perform(s, done: L("ios.ps.resumed", ["name": s.name])) { try await model.setPaused(s, false) } }
        case .edit:
            edit(s, .settings)
        case .stop:
            stopping = s
        case .fix(let fix):
            switch fix {
            case .syncNow: Task { await perform(s) { try await model.syncNow(s) } }
            case .confirmRemovals: confirmingRemovals = s
            case .takeOver:
                if s.kind == .immichApi {
                    edit(s, .takeOver)
                } else {
                    Task { await perform(s, done: L("ios.ps.tookOver", ["name": s.name])) { try await model.takeOver(s) } }
                }
            case .replaceLink, .enterPassword, .replaceKey, .pickAlbum: edit(s, .connection(fix))
            case .none: break
            }
        }
    }

    private func perform(_ s: PhotoSource, done: String? = nil, _ work: () async throws -> Void) async {
        do {
            try await work()
            if let done { app.toasts.show(done) }
        } catch let e as APIError where e.code == "rate_limited" {
            app.toasts.show(L("ios.ps.syncWait"), isError: true)
        } catch let e as APIError where e.code == "invalid_transition" {
            app.toasts.show(L("ios.ps.syncPaused"), isError: true)
        } catch {
            app.toasts.error(error)
        }
    }

    private func stop(_ s: PhotoSource, removeItems: Bool) async {
        await perform(s, done: removeItems ? L("ios.ps.stoppedRemoved", ["name": s.name]) : L("ios.ps.stopped", ["name": s.name])) {
            try await model.delete(s, removeItems: removeItems)
        }
    }
}

/// One source: provider, name, status line (with a spinner while a run is in progress), next sync, counts, warnings,
/// the best-effort note for iCloud, the fix button and the actions menu.
struct PhotoSourceRow: View {
    enum Action {
        case syncNow, pause, resume, edit, stop
        case fix(SourceFix)
    }

    let source: PhotoSource
    let busy: Bool
    var showTarget = false
    var limits: Limits = .defaults
    let act: (Action) -> Void

    private var canManage: Bool { source.canManage ?? true }

    var body: some View {
        let headline = SourceStatusText.headline(source)
        let fix = SourceStatusText.fix(source)
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: source.kind == .icloudLink ? "icloud" : "photo.stack")
                    .font(.title3)
                    .foregroundStyle(Palette.primary)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(Palette.primarySoft))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(source.name).font(.headline).foregroundStyle(Palette.text)
                    Text(subtitle).font(.footnote).foregroundStyle(Palette.textMuted)
                    HStack(spacing: 6) {
                        if source.running || busy { ProgressView().controlSize(.mini) }
                        Text(headline.text).font(.subheadline.weight(.medium)).foregroundStyle(color(headline.tone))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                    if source.state == .active, let err = SourceStatusText.error(source) {
                        Text(err.text).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                    if let next = SourceStatusText.next(source) {
                        Text(next.text).font(.footnote).foregroundStyle(Palette.textFaint)
                    }
                    Text(SourceStatusText.counts(source).map(\.text).joined(separator: " · "))
                        .font(.footnote.monospacedDigit()).foregroundStyle(Palette.textMuted)
                }
                Spacer(minLength: 0)
                if canManage { menu }
            }
            ForEach(SourceStatusText.warnings(source, limits: limits), id: \.key) { w in
                Callout(w.text, kind: .warn)
            }
            if source.isBestEffort, source.state != .paused {
                Text(L("ios.ps.bestEffortShort")).font(.caption).foregroundStyle(Palette.textFaint)
            }
            if canManage, source.state == .needsAttention || source.state == .error, let key = SourceStatusText.fixKey(fix) {
                Button { act(.fix(fix)) } label: { Text(L(key)) }
                    .buttonStyle(.kf(fix == .confirmRemovals ? .danger : .primary))
                    .disabled(busy)
            }
        }
        .padding(.vertical, 4)
    }

    private var subtitle: String {
        var parts: [String] = [providerName]
        if showTarget { parts.append(SourceStatusText.target(source.target).text) }
        if let owner = source.owner, source.isOwner != true { parts.append(L("ios.ps.setUpBy", ["name": owner.displayName])) }
        return parts.joined(separator: " · ")
    }

    private var providerName: String {
        switch source.kind {
        case .immichLink: L("ios.ps.provider.immichLink")
        case .immichApi: L("ios.ps.provider.immichApi")
        case .icloudLink: L("ios.ps.provider.icloudLink")
        case nil: source.provider
        }
    }

    private func color(_ tone: SourceLine.Tone) -> Color {
        switch tone {
        case .ok: Palette.accentText
        case .muted, .paused: Palette.textMuted
        case .error: Palette.danger
        case .attention: Palette.warn
        }
    }

    private var menu: some View {
        Menu {
            if source.state != .paused {
                Button { act(.syncNow) } label: { Label(L("ios.ps.syncNow"), systemImage: "arrow.clockwise") }
                    .disabled(source.running || busy)
                Button { act(.pause) } label: { Label(L("ios.ps.pause"), systemImage: "pause") }
            } else {
                Button { act(.resume) } label: { Label(L("ios.ps.resume"), systemImage: "play") }
            }
            Button { act(.edit) } label: { Label(L("ios.ps.edit"), systemImage: "slider.horizontal.3") }
            if source.isOwner != true {
                Button { act(.fix(.takeOver)) } label: { Label(L("ios.ps.fix.takeOver"), systemImage: "person.crop.circle.badge.checkmark") }
            }
            Button(role: .destructive) { act(.stop) } label: { Label(L("ios.ps.stop"), systemImage: "xmark.circle") }
        } label: {
            Image(systemName: "ellipsis.circle").font(.title3).frame(width: 44, height: 44)
        }
        .accessibilityLabel(L("ios.ps.actions", ["name": source.name]))
    }
}
