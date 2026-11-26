import KinframeKit
import SwiftUI

/// "Add a family from another server" (docs/spec/multi-server.md §3.1): paste or open a subscribe invite of a family
/// on another kinframe server, check it with this server (`previewRemoteInvite`), then confirm with both servers
/// named, the history option, birthdays and what happens to the copies when it ends (`createRemoteSubscription`).
///
/// - `frame`: the frame to follow with (a frame's settings). Nil: pick one of the frames you manage here.
/// - `inviteURL`: an invite link that was opened; nil shows the field to paste one.
struct RemoteFollowSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    var frame: FrameSummary?
    var inviteURL: URL?
    var done: ((RemoteSubscription) -> Void)?

    @State private var typed = ""
    @State private var link: URL?
    @State private var preview: RemoteInvitePreview?
    @State private var chosen: String?
    @State private var history: HistoryChoice = .none
    @State private var birthdays = true
    @State private var onEnd: RemoteOnEnd = .remove
    @State private var busy = false
    @State private var error: String?

    init(api: API, frame: FrameSummary? = nil, inviteURL: URL? = nil, done: ((RemoteSubscription) -> Void)? = nil) {
        self.api = api
        self.frame = frame
        self.inviteURL = inviteURL
        self.done = done
        _chosen = State(initialValue: frame?.id)
        _typed = State(initialValue: inviteURL?.absoluteString ?? "")
    }

    private var frames: [FrameSummary] { frame.map { [$0] } ?? SubscriptionLogic.manageable(app.frames) }
    private var chosenFrame: FrameSummary? { frames.first { $0.id == chosen } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let preview {
                        confirm(preview)
                    } else {
                        entry
                    }
                    if let error { Callout(error, kind: .error) }
                }
                .padding(16)
                .frame(maxWidth: 620)
                .frame(maxWidth: .infinity)
            }
            .pageBackground()
            .pageTitle(preview.map { L("servers.remote.confirmTitle", ["feed": $0.feedName]) } ?? L("remote.add"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                if preview != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L("servers.remote.follow")) { Task { await follow() } }
                            .fontWeight(.semibold)
                            .disabled(chosenFrame == nil || busy)
                    }
                }
            }
            .task {
                if inviteURL != nil, preview == nil { await check() }
            }
        }
    }

    // MARK: Entering the link

    private var entry: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("remote.addBody")).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            SectionHeader(L("remote.inviteLink"))
            HStack(spacing: 8) {
                TextField(L("servers.remote.urlPlaceholder"), text: $typed)
                    .textFieldStyle(KFFieldStyle())
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .onSubmit { Task { await check() } }
                Button(L("servers.remote.paste")) {
                    if let s = UIPasteboard.general.string { typed = s }
                }
                .buttonStyle(.kf(.secondary))
            }
            Button {
                Task { await check() }
            } label: {
                HStack { if busy { ProgressView().tint(Palette.onPrimary) }; Text(L("servers.remote.check")) }
            }
            .buttonStyle(.kf(.primary, large: true, fill: true))
            .disabled(busy || typed.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    // MARK: Confirmation

    @ViewBuilder
    private func confirm(_ p: RemoteInvitePreview) -> some View {
        HStack(spacing: 14) {
            FeedCover(url: p.coverUrl, name: p.feedName, size: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text(p.feedName).font(.display(.title2)).accessibilityAddTraits(.isHeader)
                Label(RemoteInvite.host(p.server), systemImage: "globe").font(.subheadline).foregroundStyle(Palette.textMuted)
            }
        }
        if frame == nil { framePicker }
        if let f = chosenFrame {
            Text(RemoteSubscriptionLogic.confirmation(p, frameName: f.name)).fixedSize(horizontal: false, vertical: true)
        }
        Callout(L("remote.previewOperator", ["server": p.thisServerHost ?? p.thisServerName]))
        Text(p.requiresApproval == true ? L("remote.needsApproval") : L("servers.remote.instant"))
            .font(.footnote).foregroundStyle(Palette.textMuted)
        HistoryPicker(choice: $history, maxAll: app.limits.maxFeedBackfillItems)
        if app.birthdaysEnabled { BirthdaysSwitch(on: $birthdays) }
        RemoteOnEndPicker(onEnd: $onEnd)
    }

    @ViewBuilder
    private var framePicker: some View {
        SectionHeader(L("servers.remote.pickFrame"))
        if frames.isEmpty {
            Callout(L("servers.remote.noFrames"), kind: .warn)
        } else {
            VStack(spacing: 0) {
                ForEach(Array(frames.enumerated()), id: \.element.id) { n, f in
                    if n > 0 { Divider().overlay(Palette.border).padding(.leading, 56) }
                    let on = chosen == f.id
                    Button { chosen = f.id } label: {
                        HStack(spacing: 12) {
                            Image(systemName: on ? "largecircle.fill.circle" : "circle")
                                .font(.title2)
                                .foregroundStyle(on ? Palette.primary : Palette.borderStrong)
                            Text(f.name).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                            Spacer()
                        }
                        .padding(.horizontal, 14)
                        .frame(minHeight: 56)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .card(padding: 0)
        }
    }

    // MARK: Actions

    private func check() async {
        error = nil
        guard let url = RemoteInvite.normalize(typed) else {
            error = L("remote.linkInvalid")
            return
        }
        busy = true
        defer { busy = false }
        do {
            preview = try await api.previewRemoteInvite(url)
            link = url
            if chosen == nil, frames.count == 1 { chosen = frames.first?.id }
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func follow() async {
        guard let link, let f = chosenFrame, let p = preview else { return }
        busy = true
        defer { busy = false }
        do {
            let s = try await api.createRemoteSubscription(frameId: f.id, inviteURL: link, history: history,
                                                           birthdays: app.birthdaysEnabled ? birthdays : true, onEnd: onEnd)
            app.toasts.show(s.status == .pending ? L("remote.requested", ["feed": p.feedName, "frame": f.name])
                                                 : L("remote.followed", ["feed": p.feedName, "frame": f.name]))
            done?(s)
            dismiss()
        } catch {
            self.error = errorMessage(error)
        }
    }
}

/// "When it ends": remove the copied photos (default) or keep the ones already on the frame.
struct RemoteOnEndPicker: View {
    @Binding var onEnd: RemoteOnEnd

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(L("remote.onEnd"))
            VStack(alignment: .leading, spacing: 10) {
                Picker(L("remote.onEnd"), selection: $onEnd) {
                    Text(L("remote.onEnd.remove")).tag(RemoteOnEnd.remove)
                    Text(L("remote.onEnd.keep")).tag(RemoteOnEnd.keep)
                }
                .pickerStyle(.inline)
                .labelsHidden()
                Text(L("servers.remote.onEnd.hint")).font(.footnote).foregroundStyle(Palette.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .card(padding: 14)
        }
    }
}
