import KinframeKit
import SwiftUI

/// An invite link (`/i/<token>`): preview, then join (web: i/[token]).
struct InviteView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let token: String
    @State private var preview: InvitePreview?
    @State private var result: RedeemResult?
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                FrameIllustration().frame(height: 110).frame(maxWidth: .infinity).padding(.top, 8)
                if let error {
                    Text(L("invite.invalidTitle")).font(.display(.title))
                    Callout(error, kind: .error)
                    Button(L("common.close")) { dismiss() }.buttonStyle(.kf(.secondary, fill: true))
                } else if let result {
                    outcome(result)
                } else if let p = preview {
                    Text(L("invite.from", ["name": p.invitedByName])).font(.subheadline).foregroundStyle(Palette.textMuted)
                    Text(L("invite.join", ["name": p.frameName])).font(.display(.largeTitle)).accessibilityAddTraits(.isHeader)
                    if let loc = p.frameLocation, !loc.isEmpty {
                        Label(loc, systemImage: "mappin").foregroundStyle(Palette.textMuted)
                    }
                    if p.alreadyMember == true {
                        Callout(L("invite.already"), kind: .ok)
                        Button(L("add.viewFrame")) { dismiss() }.buttonStyle(.kf(.primary, large: true, fill: true))
                    } else {
                        Text(p.requiresApproval ? L("invite.needsApproval", ["owner": p.ownerName ?? ""]) : L("invite.instant"))
                            .foregroundStyle(Palette.textMuted)
                        Button {
                            Task { await accept() }
                        } label: {
                            HStack { if busy { ProgressView().tint(Palette.onPrimary) }; Text(L("invite.accept")) }
                        }
                        .buttonStyle(.kf(.primary, large: true, fill: true))
                        .disabled(busy)
                        Button(L("invite.notNow")) { dismiss() }.buttonStyle(.kf(.ghost, fill: true))
                    }
                } else {
                    LoadingView()
                }
            }
            .padding(20)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .navigationTitle(L("invite.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("common.close")) { dismiss() } } }
        .task { await load() }
    }

    @ViewBuilder
    private func outcome(_ r: RedeemResult) -> some View {
        switch r.status {
        case .pending:
            Text(L("invite.pendingTitle")).font(.display(.title))
            Text(L("invite.pendingBody", ["owner": r.frame.ownerName ?? ""])).foregroundStyle(Palette.textMuted)
            Button(L("common.done")) { dismiss() }.buttonStyle(.kf(.primary, large: true, fill: true))
        default:
            Text(L("add.joinedTitle", ["name": r.frame.name])).font(.display(.title))
            Text(L("invite.joinedBody")).foregroundStyle(Palette.textMuted)
            Button(L("add.sendFirst")) {
                app.composeFrameIds = [r.frame.id]
                app.tab = .send
                dismiss()
            }
            .buttonStyle(.kf(.primary, large: true, fill: true))
        }
    }

    private func load() async {
        let token = token
        do {
            preview = try await api.call { try await $0.previewInvite(path: .init(token: token)).ok.body.json }
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func accept() async {
        busy = true
        defer { busy = false }
        let token = token
        do {
            let r = try await api.call { try await $0.acceptInvite(path: .init(token: token)).ok.body.json }
            result = r
            if r.status != .pending { app.updateFrame(r.frame) }
        } catch {
            self.error = errorMessage(error)
        }
    }
}
