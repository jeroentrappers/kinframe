import KinframeKit
import SwiftUI

/// Deletes the account (`DELETE /v1/me`), optionally with everything the user sent. Owners hand over or remove their
/// frames first; the server refuses otherwise.
struct DeleteAccountSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var removeItems = false
    /// Leaving (README §5.21): take the photos (default) or leave them to the families.
    @State private var photos: AccountPhotos = .take
    @State private var deleting = false

    private var owned: [FrameSummary] { app.frames.filter { $0.role == .owner } }

    var body: some View {
        NavigationStack {
            Form {
                if owned.isEmpty {
                    Section {
                        Text(L("account.deleteBody"))
                        if app.leavingEnabled {
                            Picker(L("leaving.account.photos"), selection: $photos) {
                                Text(L("leaving.account.take")).tag(AccountPhotos.take)
                                Text(L("leaving.account.leave")).tag(AccountPhotos.leave)
                            }
                            .pickerStyle(.inline)
                        } else {
                            Toggle(L("account.deleteItems"), isOn: $removeItems)
                        }
                    } footer: {
                        if app.leavingEnabled {
                            Text(photos == .take ? L("leaving.account.takeHint") : L("leaving.account.leaveHint"))
                        } else if !removeItems {
                            Text(L("account.deleteItemsHint"))
                        }
                    }
                    .listRowBackground(Palette.surface)
                    Section {
                        Button(role: .destructive) {
                            Task { await delete() }
                        } label: {
                            HStack {
                                Text(L("account.delete"))
                                if deleting {
                                    Spacer()
                                    ProgressView()
                                }
                            }
                        }
                        .disabled(deleting)
                    }
                    .listRowBackground(Palette.surface)
                } else {
                    Section {
                        Text(L("account.deleteOwner", ["frames": owned.map(\.name).formatted(.list(type: .and).locale(L10n.locale))]))
                    }
                    .listRowBackground(Palette.surface)
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("account.deleteTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("common.cancel")) { dismiss() }.disabled(deleting)
                }
            }
            .task { await app.loadFrames() }
        }
        .interactiveDismissDisabled(deleting)
    }

    private func delete() async {
        deleting = true
        do {
            try await app.deleteAccount(photos: app.leavingEnabled ? photos : (removeItems ? .take : .leave))
        } catch {
            deleting = false
            app.toasts.error(error)
        }
    }
}
