import KinframeKit
import SwiftUI

/// A contact's profile: the families and frames you share, the milestones they chose to show, a chat, block.
struct PersonView: View {
    @Environment(AppModel.self) private var app
    let userId: String
    var inSheet = false
    @State private var person: Person?
    @State private var error: String?
    @State private var confirmBlock = false

    private var isMe: Bool { userId == app.me?.id }

    var body: some View {
        Group {
            if let p = person {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        HStack(spacing: 14) {
                            PersonChip(person: p.person, size: 72)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(PersonName.display(p.person)).font(.display(.title2))
                                if p.blocked { Pill(L("chat.person.blocked"), foreground: Palette.danger, background: Palette.dangerSoft) }
                            }
                        }
                        if !isMe {
                            HStack {
                                if app.social.features.chats, !p.blocked {
                                    Button {
                                        if let id = p.directConversationId { open(id) } else { Task { await app.startDirectChat(with: userId); close() } }
                                    } label: { Label(L("chat.person.message"), systemImage: "bubble.left") }
                                    .buttonStyle(.kf(.primary))
                                }
                                Button(L(p.blocked ? "chat.unblock" : "chat.blockShort"), role: p.blocked ? nil : .destructive) {
                                    if p.blocked { Task { await setBlocked(false) } } else { confirmBlock = true }
                                }
                                .buttonStyle(.kf(p.blocked ? .secondary : .dangerGhost))
                            }
                        }
                        if !p.feeds.isEmpty {
                            group(L("chat.person.families")) {
                                ForEach(Array(p.feeds.enumerated()), id: \.offset) { _, f in
                                    Button { close(); app.openFamily(f.feedId) } label: { row(f.feedName, "rectangle.stack.badge.person.crop") }
                                        .buttonStyle(.plain)
                                }
                            }
                        }
                        if !p.frames.isEmpty {
                            group(L("chat.person.frames")) {
                                ForEach(Array(p.frames.enumerated()), id: \.offset) { _, f in row(f.frameName, "photo.on.rectangle") }
                            }
                        }
                        if let ms = p.milestones, !ms.isEmpty {
                            group(L(isMe ? "engagement.milestone.mine" : "engagement.milestone.shown")) {
                                ForEach(ms, id: \.self) { k in row(k.title, k.symbol) }
                            }
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: 720)
                    .frame(maxWidth: .infinity)
                }
            } else if let error {
                ErrorRetryView(message: error) { Task { await load() } }.padding(16)
            } else {
                LoadingView()
            }
        }
        .pageBackground()
        .pageTitle(person.map { PersonName.display($0.person) } ?? "")
        .task { await load() }
        .confirmationDialog(L("chat.person.blockConfirm", ["name": person.map { PersonName.display($0.person) } ?? ""]), isPresented: $confirmBlock, titleVisibility: .visible) {
            Button(L("chat.blockShort"), role: .destructive) { Task { await setBlocked(true) } }
            Button(L("common.cancel"), role: .cancel) {}
        } message: {
            Text(L("chat.person.blockBody"))
        }
    }

    private func group<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(title)
            VStack(spacing: 0) { content() }.card(padding: 0)
        }
    }

    private func row(_ title: String, _ symbol: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).foregroundStyle(Palette.textMuted).frame(width: 28)
            Text(title).foregroundStyle(Palette.text)
            Spacer()
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 48)
    }

    private func load() async {
        guard let api = app.api else { return }
        do {
            person = try await api.person(userId)
            error = nil
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func setBlocked(_ on: Bool) async {
        guard let api = app.api else { return }
        do {
            if on { try await api.block(userId) } else { try await api.unblock(userId) }
            person?.blocked = on
            app.toasts.show(L(on ? "chat.blocked" : "chat.person.unblockedToast", ["name": person.map { PersonName.display($0.person) } ?? ""]))
        } catch {
            app.toasts.error(error)
        }
    }

    private func open(_ conversationId: String) {
        close()
        app.openChat(conversationId)
    }

    private func close() {
        if inSheet { app.social.personSheet = nil }
    }
}

/// Settings → Blocked people: unblock.
struct BlockedPeopleView: View {
    @Environment(AppModel.self) private var app
    @State private var list: [Block]?

    var body: some View {
        List {
            if let list {
                if list.isEmpty { Text(L("chat.noBlocked")).foregroundStyle(Palette.textMuted) }
                ForEach(list) { b in
                    HStack(spacing: 12) {
                        PersonChip(person: b.person, size: 36)
                        Text(PersonName.display(b.person))
                        Spacer()
                        Button(L("chat.unblock")) { Task { await unblock(b) } }.buttonStyle(.kf(.secondary))
                    }
                }
            } else {
                LoadingView()
            }
            Section { Text(L("chat.blocked.hint")).font(.footnote).foregroundStyle(Palette.textMuted) }.listRowBackground(Color.clear)
        }
        .listRowBackground(Palette.surface)
        .scrollContentBackground(.hidden)
        .pageBackground()
        .pageTitle(L("chat.blockedPeople"))
        .task { list = (try? await app.api?.blocks()) ?? [] }
    }

    private func unblock(_ b: Block) async {
        do {
            try await app.api?.unblock(b.person.userId)
            list?.removeAll { $0.id == b.id }
        } catch {
            app.toasts.error(error)
        }
    }
}

/// Reports to handle: a family's (owner and admins) or the operator's. Remove acts like a moderator delete.
struct ReportsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let feedId: String?
    @State private var list: [Report]?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if let list {
                    if list.isEmpty { Text(L("chat.report.none")).foregroundStyle(Palette.textMuted).listRowBackground(Color.clear) }
                    ForEach(list) { r in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(ReportText.kind(r) + " · " + r.reason.label).font(.subheadline.weight(.semibold))
                                Spacer()
                                Pill(ReportText.status(r))
                            }
                            if let a = r.author { Text(PersonName.display(a)).font(.footnote).foregroundStyle(Palette.textMuted) }
                            if let e = r.excerpt, !e.isEmpty { Text("“\(e)”").font(.subheadline).foregroundStyle(Palette.text) }
                            if let n = r.note, !n.isEmpty { Text(n).font(.footnote).italic().foregroundStyle(Palette.textMuted) }
                            Text(Format.relative(r.createdAt, locale: L10n.locale)).font(.caption).foregroundStyle(Palette.textFaint)
                            if r.status == .open {
                                HStack {
                                    Button(L("chat.report.remove"), role: .destructive) { Task { await resolve(r, remove: true) } }.buttonStyle(.kf(.danger))
                                    Button(L("chat.report.dismiss")) { Task { await resolve(r, remove: false) } }.buttonStyle(.kf(.secondary))
                                    if let post = r.postId, app.social.features.social {
                                        Button(L("chat.open")) {
                                            dismiss()
                                            app.openSocial(.post(postId: post, commentId: r.targetKind == .comment ? r.targetId : nil))
                                        }
                                        .buttonStyle(.kf(.ghost))
                                    } else if let conv = r.conversationId, app.social.features.chats {
                                        Button(L("chat.open")) {
                                            dismiss()
                                            app.openSocial(.conversation(conversationId: conv, messageId: r.targetId))
                                        }
                                        .buttonStyle(.kf(.ghost))
                                    }
                                }
                            }
                        }
                        .padding(.vertical, 4)
                        .listRowBackground(Palette.surface)
                    }
                } else if let error {
                    ErrorRetryView(message: error) { Task { await load() } }.listRowBackground(Color.clear)
                } else {
                    LoadingView().listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .pageTitle(L(feedId == nil ? "chat.report.operatorTitle" : "chat.operatorReports"))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
            .task { await load() }
            .refreshable { await load() }
        }
    }

    private func load() async {
        guard let api = app.api else { return }
        do {
            if let feedId { list = try await api.feedReports(feedId) } else { list = try await api.operatorReports(all: true) }
            error = nil
            app.social.seenReports()
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func resolve(_ r: Report, remove: Bool) async {
        guard let api = app.api else { return }
        do {
            let updated = try await api.resolveReport(r.id, remove: remove)
            if let i = list?.firstIndex(where: { $0.id == r.id }) { list?[i] = updated }
        } catch {
            app.toasts.error(error)
            await load()
        }
    }
}
