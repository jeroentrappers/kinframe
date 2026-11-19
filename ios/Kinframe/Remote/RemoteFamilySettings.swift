import KinframeKit
import SwiftUI

/// Family settings (owner and admins): "Allow frames on other servers" (README §5.22, off by default; turning it off
/// ends every remote subscription) and the servers blocked for this family. Saves on change, like the family social
/// switches. Only when discovery says `remote_families`.
struct RemoteFamilySettings: View {
    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    @State private var blocked: [BlockedServer] = []
    @State private var confirmOff = false
    @State private var blocking = false
    @State private var saving = false

    private var allowed: Bool { model.feed?.value2.allowRemoteFrames ?? false }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(L("servers.remote.title"))
            VStack(spacing: 0) {
                Toggle(isOn: Binding(get: { allowed }, set: { on in
                    if on { Task { await setAllowed(true) } } else { confirmOff = true }
                })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("servers.remote.allow"))
                        Text(L("servers.remote.allowHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .tint(Palette.accent)
                .disabled(saving)
                .padding(14)
            }
            .card(padding: 0)

            SectionHeader(L("servers.remote.blocked.title"), trailing: AnyView(
                Button { blocking = true } label: {
                    Label(L("servers.remote.blocked.add"), systemImage: "nosign").font(.subheadline.weight(.semibold)).frame(minHeight: 44)
                }
            ))
            VStack(alignment: .leading, spacing: 0) {
                if blocked.isEmpty {
                    Text(L("servers.remote.blocked.none")).font(.subheadline).foregroundStyle(Palette.textMuted).padding(14)
                }
                ForEach(Array(blocked.enumerated()), id: \.element.id) { n, b in
                    if n > 0 { Divider().overlay(Palette.border) }
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(b.serverName.map { "\($0) · \(b.host)" } ?? b.host).font(.body.weight(.semibold))
                            let line = [b.reason, b.createdByName.map { L("servers.remote.blocked.by", ["name": $0]) }]
                                .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
                            if !line.isEmpty { Text(line).font(.footnote).foregroundStyle(Palette.textMuted) }
                        }
                        Spacer()
                        Button(L("servers.remote.blocked.unblock")) { Task { await unblock(b) } }
                            .buttonStyle(.kf(.secondary))
                    }
                    .padding(14)
                }
            }
            .card(padding: 0)
            Text(L("servers.remote.blocked.hint")).font(.footnote).foregroundStyle(Palette.textMuted)
        }
        .task { await load() }
        .confirmationDialog(L("servers.remote.allowOffTitle"), isPresented: $confirmOff, titleVisibility: .visible) {
            Button(L("servers.remote.allowOff"), role: .destructive) { Task { await setAllowed(false) } }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(L("servers.remote.allowOffBody", ["feed": model.feed?.name ?? ""]))
        }
        .sheet(isPresented: $blocking) {
            RemoteBlockServerSheet(api: model.api, feedId: model.id) { b in
                blocked.insert(b, at: 0)
                Task { await model.loadSubscriptions() }
            }
        }
    }

    private func load() async {
        blocked = (try? await model.api.feedBlockedServers(model.id)) ?? blocked
    }

    private func setAllowed(_ on: Bool) async {
        saving = true
        defer { saving = false }
        do {
            _ = try await model.api.setAllowRemoteFrames(feedId: model.id, on)
            await model.refreshFeed()
            if !on { await model.loadSubscriptions() }
            app.toasts.show(L("common.saved"))
        } catch {
            app.toasts.error(error)
        }
    }

    private func unblock(_ b: BlockedServer) async {
        do {
            try await model.api.unblockFeedServer(model.id, id: b.id)
            blocked.removeAll { $0.id == b.id }
            app.toasts.show(L("servers.remote.blocked.unblocked", ["host": b.host]))
        } catch {
            app.toasts.error(error)
        }
    }
}

/// Block a server for a family: its frames' subscriptions end and new requests from it are refused. Opened from the
/// blocked list (type the host) or a remote subscription row (host filled in).
struct RemoteBlockServerSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let feedId: String
    var host: String = ""
    let done: (BlockedServer) -> Void
    @State private var typed = ""
    @State private var reason = ""
    @State private var busy = false
    @State private var invalid = false

    var body: some View {
        NavigationStack {
            Form {
                Section(L("servers.remote.blocked.host")) {
                    TextField(L("servers.remote.blocked.hostPlaceholder"), text: $typed)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if invalid { Text(L("servers.remote.blocked.invalid")).font(.footnote).foregroundStyle(Palette.danger) }
                }
                Section(L("servers.remote.blocked.reason")) {
                    TextField("", text: $reason, axis: .vertical)
                        .onChange(of: reason) { if reason.count > 300 { reason = String(reason.prefix(300)) } }
                }
                Section { } footer: { Text(L("servers.remote.blocked.hint")) }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("servers.remote.blocked.add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("servers.remote.blocked.do"), role: .destructive) { Task { await block() } }.disabled(busy)
                }
            }
            .onAppear { if typed.isEmpty { typed = host } }
        }
    }

    private func block() async {
        guard let h = BlockedHost.normalize(typed) else {
            invalid = true
            return
        }
        invalid = false
        busy = true
        defer { busy = false }
        do {
            let b = try await api.blockFeedServer(feedId, host: h, reason: reason)
            app.toasts.show(L("servers.remote.blocked.done", ["host": b.host]))
            done(b)
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }
}
