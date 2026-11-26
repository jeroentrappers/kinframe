import KinframeKit
import SwiftUI

/// "Which server?" first, when more than one account could take what is being added (docs/spec/multi-server.md
/// §2.2): a frame code, a family code, a new family. Each frame and family lives on one server, so the code is
/// redeemed (or the family created) there. With one account the content opens at once.
struct ServerChoiceSheet<Content: View>: View {
    @Environment(\.dismiss) private var dismiss
    /// The accounts that can take it, in account order.
    let accounts: [AppModel]
    /// What the choice is for ("Enter a code", "New family").
    let title: String
    let message: String
    @ViewBuilder let content: (AppModel, API) -> Content
    @State private var chosen: AppModel?

    private var picked: AppModel? { chosen ?? (accounts.count == 1 ? accounts.first : nil) }

    var body: some View {
        if let m = picked, let api = m.api {
            content(m, api).environment(m)
        } else {
            NavigationStack {
                List {
                    Section {
                        ForEach(accounts, id: \.accountId) { m in
                            Button { chosen = m } label: { ServerChoiceRow(model: m) }
                                .accessibilityHint(L("servers.choose.hint", ["server": m.serverName]))
                        }
                    } header: {
                        Text(L("servers.choose.title"))
                    } footer: {
                        Text(message)
                    }
                }
                .pageTitle(title)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(L("common.close")) { dismiss() } }
                }
            }
        }
    }
}

private struct ServerChoiceRow: View {
    let model: AppModel

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "server.rack").foregroundStyle(Palette.primary).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.serverName).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                if let host = model.server.map({ ServerAddress.display($0.url) }), host != model.serverName {
                    Text(host).font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
            Spacer()
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Palette.textFaint)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
