import KinframeKit
import SwiftUI

/// Opens the transfer flow with a member already picked ("Make owner" in People).
struct TransferRequest: Identifiable {
    let id = UUID()
    var preselect: String?
}

/// Hand the frame to another member (owner only), optionally leaving right after (web: TransferDialog): pick the new
/// owner (admins first), then confirm what happens. Opened from "Leave frame" the owner always leaves.
struct TransferSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let frame: FrameSummary
    var preselect: String?
    /// Opened from "Leave frame": the owner leaves right after handing over.
    var leaving = false
    /// Called after the transfer; `left` tells whether the owner also left.
    var onDone: (_ left: Bool) -> Void = { _ in }

    @State private var members: [Member]?
    @State private var error: String?
    @State private var choice: String?
    @State private var confirming = false
    @State private var leaveAfter = false
    @State private var removeMine = false
    @State private var busy = false

    private var candidates: [Member] { TransferLogic.candidates(members ?? [], me: app.me?.id) }
    private var chosen: Member? { candidates.first { $0.userId == choice } }

    var body: some View {
        NavigationStack {
            List {
                Group {
                    if let error {
                        Callout(error, kind: .error)
                    } else if members == nil {
                        HStack { Spacer(); ProgressView(); Spacer() }
                    } else if candidates.isEmpty {
                        Text(leaving ? L("transfer.noOneLeave") : L("transfer.noOne")).foregroundStyle(Palette.textMuted)
                    } else if confirming, let chosen {
                        confirm(chosen)
                    } else {
                        choose
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if confirming {
                        Button(L("transfer.back")) { withAnimation { confirming = false } }.disabled(busy)
                    } else {
                        Button(L("common.cancel")) { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView()
                    } else if confirming {
                        Button(leaveAfter ? L("transfer.doLeave") : L("transfer.do")) { Task { await transfer() } }
                            .fontWeight(.semibold)
                    } else if !candidates.isEmpty {
                        Button(L("transfer.continue")) { withAnimation { confirming = true } }.disabled(chosen == nil)
                    }
                }
            }
            .task { await load() }
        }
    }

    private var title: String {
        return leaving ? L("transfer.leaveTitle", ["name": frame.name]) : L("transfer.title")
    }

    @ViewBuilder
    private var choose: some View {
        Section {
            Text(leaving ? L("transfer.leaveBody") : L("transfer.body")).font(.subheadline).foregroundStyle(Palette.textMuted)
        }
        Section(L("transfer.pick")) {
            ForEach(candidates) { m in
                Button {
                    choice = m.userId
                } label: {
                    HStack {
                        AvatarView(name: m.displayName, url: m.avatarUrl, size: 32)
                        Text(m.displayName).foregroundStyle(Palette.text)
                        RoleBadge(role: m.role)
                        Spacer()
                        if choice == m.userId { Image(systemName: "checkmark").foregroundStyle(Palette.primary) }
                    }
                }
                .accessibilityAddTraits(choice == m.userId ? .isSelected : [])
            }
        }
        Section {
            if !leaving {
                Toggle(L("transfer.alsoLeave"), isOn: $leaveAfter.animation()).tint(Palette.accent)
            }
            if leaveAfter {
                Toggle(L("overview.removeMine"), isOn: $removeMine).tint(Palette.accent)
            }
        }
    }

    @ViewBuilder
    private func confirm(_ m: Member) -> some View {
        Section {
            Text(L("transfer.confirmTitle", ["name": m.displayName]))
                .font(.display(.title3))
                .foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
            fact("checkmark.circle", L("transfer.factOwner", ["name": m.displayName, "frame": frame.name]))
            fact("checkmark.circle", leaveAfter ? L("transfer.factLeave") : L("transfer.factAdmin"))
            fact("info.circle", L("transfer.factUndo", ["name": m.displayName]))
        }
        Section {
            Button(leaveAfter ? L("transfer.doLeave") : L("transfer.do")) { Task { await transfer() } }
                .buttonStyle(.kf(.primary, fill: true))
                .disabled(busy)
        }
    }

    private func fact(_ symbol: String, _ text: String) -> some View {
        Label {
            Text(text).foregroundStyle(Palette.text)
        } icon: {
            Image(systemName: symbol).foregroundStyle(Palette.accent)
        }
    }

    private func load() async {
        leaveAfter = leaving
        let id = frame.id
        do {
            members = try await api.call { try await $0.listMembers(path: .init(frameId: id)).ok.body.json }
            choice = TransferLogic.suggestion(candidates, preselect: preselect)
            #if DEBUG
            if UserDefaults.standard.bool(forKey: "kfTransferConfirm"), choice != nil { confirming = true }
            #endif
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func transfer() async {
        guard let chosen else { return }
        busy = true
        defer { busy = false }
        let id = frame.id, uid = chosen.userId
        do {
            _ = try await api.call { try await $0.transferOwnership(path: .init(frameId: id), body: .json(.init(userId: uid))).noContent }
        } catch {
            app.toasts.error(error)
            return
        }
        var left = false
        if leaveAfter {
            do {
                _ = try await api.call { try await $0.leaveFrame(path: .init(frameId: id), body: .json(.init(removeMyItems: removeMine))).noContent }
                left = true
            } catch {
                // The transfer itself worked; the error says why leaving didn't.
                app.toasts.error(error)
            }
        }
        dismiss()
        if left {
            app.removeFrame(id)
            app.framesPath = NavigationPath()
            app.toasts.show(L("transfer.doneLeft", ["name": chosen.displayName, "frame": frame.name]))
        } else {
            app.toasts.show(L("transfer.done", ["name": chosen.displayName]))
            if let f = try? await api.call({ try await $0.getFrame(path: .init(frameId: id)).ok.body.json }) {
                app.updateFrame(f.summary)
            }
        }
        onDone(left)
    }
}

/// Move the frame to a new device with the code it shows (web: ReplaceDeviceDialog). Photos, albums, settings and
/// people move over; the old frame goes back to its setup screen.
@MainActor
@Observable
final class ReplaceDeviceModel: CodeEntryModel {
    private(set) var digits = ""
    private(set) var busy = false
    private(set) var done = false
    var error: String?
    private let api: API
    let frameId: String

    init(api: API, frameId: String) {
        self.api = api
        self.frameId = frameId
    }

    var formatted: String { Format.formatCode(digits) }
    var isComplete: Bool { Format.isCompleteCode(digits) }
    var isBusy: Bool { busy }
    var canSubmit: Bool { isComplete && !busy && !done }

    func setCode(_ input: String) {
        let next = Format.normalizeCode(input)
        guard next != digits else { return }
        digits = next
        error = nil
    }

    /// `POST /v1/frames/{id}/replace-device {code}`; the updated frame, or nil with `error` set.
    func submit() async -> FrameDetail? {
        guard canSubmit else { return nil }
        busy = true
        defer { busy = false }
        let id = frameId, code = digits
        do {
            let frame = try await api.call { try await $0.replaceDevice(path: .init(frameId: id), body: .json(.init(code: code))).ok.body.json }
            done = true
            return frame
        } catch {
            self.error = errorMessage(error)
            return nil
        }
    }
}

struct ReplaceDeviceSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let frame: FrameSummary
    @State private var model: ReplaceDeviceModel
    var onReplaced: () -> Void = {}

    init(api: API, frame: FrameSummary, onReplaced: @escaping () -> Void = {}) {
        self.frame = frame
        self.onReplaced = onReplaced
        _model = State(initialValue: ReplaceDeviceModel(api: api, frameId: frame.id))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if model.done {
                        VStack(alignment: .leading, spacing: 12) {
                            Image(systemName: "checkmark.circle.fill").font(.largeTitle).foregroundStyle(Palette.accent).accessibilityHidden(true)
                            Text(L("replace.doneTitle")).font(.display(.title2)).accessibilityAddTraits(.isHeader)
                            Text(L("replace.doneBody", ["name": frame.name])).foregroundStyle(Palette.textMuted)
                            Button(L("common.done")) { dismiss() }.buttonStyle(.kf(.primary, fill: true))
                        }
                        .card(padding: 20)
                    } else {
                        Text(L("replace.body")).foregroundStyle(Palette.textMuted)
                        VStack(alignment: .leading, spacing: 10) {
                            step(1, L("replace.step1"))
                            step(2, L("replace.step2"))
                            step(3, L("replace.step3", ["name": frame.name]))
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L("replace.codeLabel")).font(.subheadline.weight(.semibold))
                            CodeField(model: model, onComplete: { Task { await submit() } })
                            if let error = model.error {
                                Label(error, systemImage: "exclamationmark.circle.fill")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(Palette.danger)
                            }
                        }
                        Callout(L("replace.warning"), kind: .warn)
                        Button {
                            Task { await submit() }
                        } label: {
                            HStack(spacing: 8) {
                                if model.busy { ProgressView().tint(Palette.onPrimary) }
                                Text(L("replace.do"))
                            }
                        }
                        .buttonStyle(.kf(.primary, large: true, fill: true))
                        .disabled(!model.canSubmit)
                    }
                }
                .padding(20)
            }
            .scrollDismissesKeyboard(.interactively)
            .pageBackground()
            .navigationTitle(L("replace.title", ["name": frame.name]))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !model.done {
                    ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                }
            }
        }
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(n)")
                .font(.footnote.weight(.bold).monospacedDigit())
                .foregroundStyle(Palette.onPrimary)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Palette.primary))
                .accessibilityHidden(true)
            Text(text).foregroundStyle(Palette.text).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func submit() async {
        if let f = await model.submit() {
            app.updateFrame(f.summary)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onReplaced()
        }
    }
}
