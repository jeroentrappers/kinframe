import KinframeKit
import SwiftUI

/// Settings → Memories (only with discovery `leaving`).
struct LeavingSettingsSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        if app.leavingEnabled, let api = app.api {
            Section {
                NavigationLink { MemoriesSettingsView(api: api) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("leaving.memoriesTitle"))
                        Text(L("leaving.memories.linkHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                }
            }
        }
    }
}

/// Leave people out of your memories (On this day, the monthly recap, suggestions, the weekly summary) and the memories
/// you hid. Private: nobody is told, and frames are not affected (README §5.21 "Memories").
struct MemoriesSettingsView: View {
    @Environment(AppModel.self) private var app
    let api: API
    @State private var prefs: MemoryPreferences?
    @State private var error: String?
    @State private var adding = false

    var body: some View {
        Form {
            if let prefs {
                Section {
                    ForEach(prefs.hiddenPeople, id: \.userId) { p in
                        HStack(spacing: 12) {
                            AvatarView(name: p.displayName, url: p.avatarUrl, size: 32)
                            Text(PersonName.display(p))
                            Spacer()
                            Button(L("leaving.include")) { Task { await unhide(person: p.userId) } }
                                .buttonStyle(.borderless)
                        }
                    }
                    Button { adding = true } label: { Label(L("leaving.memories.add"), systemImage: "person.badge.minus") }
                } header: {
                    Text(L("leaving.leaveOutTitle"))
                } footer: {
                    Text(L("leaving.memories.peopleHint"))
                }
                .listRowBackground(Palette.surface)
                Section {
                    if prefs.hiddenMemories.isEmpty {
                        Text(L("leaving.memories.noneHidden")).foregroundStyle(Palette.textMuted)
                    } else {
                        Text(Lp("leaving.memories.hiddenCount", count: prefs.hiddenMemories.count))
                        Button(L("leaving.showAllAgain")) { Task { await save(memories: []) } }
                    }
                } header: {
                    Text(L("leaving.hiddenTitle"))
                } footer: {
                    Text(L("leaving.memories.hiddenHint"))
                }
                .listRowBackground(Palette.surface)
            } else if let error {
                Section { ErrorRetryView(message: error) { Task { await load() } } }
            } else {
                Section { LoadingView() }
            }
        }
        .scrollContentBackground(.hidden)
        .pageBackground()
        .navigationTitle(L("leaving.memoriesTitle"))
        .task { await load() }
        .sheet(isPresented: $adding) {
            HidePersonPicker(api: api, hidden: Set(prefs?.hiddenPeople.map(\.userId) ?? [])) { id in
                Task { await save(people: (prefs?.hiddenPeople.map(\.userId) ?? []) + [id]) }
            }
        }
    }

    private func load() async {
        error = nil
        do { prefs = try await api.memoryPreferences() } catch { self.error = errorMessage(error) }
    }

    private func unhide(person id: String) async {
        await save(people: (prefs?.hiddenPeople.map(\.userId) ?? []).filter { $0 != id })
    }

    private func save(people: [String]? = nil, memories: [String]? = nil) async {
        do {
            prefs = try await api.updateMemoryPreferences(hiddenPeople: people, hiddenMemories: memories)
        } catch {
            app.toasts.error(error)
        }
    }
}

/// People you share (or shared) a family or chat with, to leave out of your memories.
private struct HidePersonPicker: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let api: API
    let hidden: Set<String>
    let pick: (String) -> Void
    @State private var people: [PersonRef] = []
    @State private var loading = true
    @State private var query = ""

    private var shown: [PersonRef] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return people.filter { !hidden.contains($0.userId) && (q.isEmpty || $0.displayName.lowercased().contains(q)) }
    }

    var body: some View {
        NavigationStack {
            List {
                if loading {
                    LoadingView()
                } else if shown.isEmpty {
                    Text(L("leaving.memories.nobody")).foregroundStyle(Palette.textMuted)
                } else {
                    ForEach(shown, id: \.userId) { p in
                        Button {
                            pick(p.userId)
                            dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                AvatarView(name: p.displayName, url: p.avatarUrl, size: 32)
                                Text(PersonName.display(p)).foregroundStyle(Palette.text)
                            }
                        }
                    }
                }
            }
            .searchable(text: $query)
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("leaving.memories.add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } } }
            .task { await load() }
        }
    }

    /// Contacts when the server has chats; otherwise the members of your families.
    private func load() async {
        defer { loading = false }
        var byId: [String: PersonRef] = [:]
        if let contacts = try? await api.contacts() {
            for c in contacts { byId[c.person.userId] = c.person }
        }
        if byId.isEmpty {
            for f in app.feeds {
                for m in (try? await api.feedMembers(f.id)) ?? [] where m.userId != app.me?.id {
                    byId[m.userId] = PersonRef(userId: m.userId, displayName: m.displayName, avatarUrl: m.avatarUrl)
                }
            }
        }
        people = byId.values.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }
}

/// "Don't show this memory again" on a memory card: adds the posts to the hidden memories.
struct HideMemoryButton: View {
    @Environment(AppModel.self) private var app
    let postIds: [String]
    let hidden: () -> Void

    var body: some View {
        if app.leavingEnabled, let api = app.api, !postIds.isEmpty {
            Menu {
                Button(L("leaving.hideMemory"), systemImage: "eye.slash") { Task { await hide(api) } }
            } label: {
                Image(systemName: "ellipsis.circle").font(.title3).foregroundStyle(Palette.textMuted)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel(L("leaving.hideMemory"))
        }
    }

    private func hide(_ api: API) async {
        do {
            let current = try await api.memoryPreferences()
            let ids = current.hiddenMemories + postIds.filter { !current.hiddenMemories.contains($0) }
            _ = try await api.updateMemoryPreferences(hiddenMemories: ids)
            hidden()
            app.toasts.show(L("leaving.memoryHidden"))
        } catch {
            app.toasts.error(error)
        }
    }
}
