import AVKit
import KinframeKit
import SwiftUI

// Pieces the Home, thread and chat screens share: reaction rows and pickers, mention text and fields, the freshness
// ring, the media pager and the report sheet.

// MARK: Reactions

/// The reaction row under a post, comment or message: "❤️😂 Oma's frame, Anna and 3 others", tap for who reacted.
struct ReactionSummaryRow: View {
    @Environment(AppModel.self) private var app
    let summary: ReactionSummary
    let kind: ReactionTargetKind
    let targetId: String
    var postId: String? = nil
    var compact = false
    @State private var showing = false

    var body: some View {
        if summary.total > 0 {
            Button { showing = true } label: {
                HStack(spacing: 4) {
                    Text(ReactionRow.emojis(summary).joined()).font(compact ? .caption : .subheadline)
                    Text(compact ? "\(summary.total)" : ReactionRow.names(summary, me: app.me?.id))
                        .font(compact ? .caption.weight(.semibold) : .footnote)
                        .foregroundStyle(Palette.textMuted)
                        .lineLimit(1)
                }
                .padding(.horizontal, compact ? 6 : 0)
                .padding(.vertical, compact ? 2 : 0)
                .background { if compact { Capsule().fill(Palette.surface2) } }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("social.reactions.a11y", ["names": ReactionRow.names(summary, me: app.me?.id)]))
            .accessibilityHint(L("social.reactions.who"))
            .sheet(isPresented: $showing) {
                ReactionListSheet(kind: kind, targetId: targetId, postId: postId)
            }
        }
    }
}

/// "React" with the six reactions; the caller's current one is marked and tapping it again removes it.
struct ReactButton: View {
    let mine: PersonReaction?
    var label = true
    let react: (PersonReaction?) -> Void

    var body: some View {
        Menu {
            ForEach(PersonReaction.ordered, id: \.self) { r in
                Button {
                    react(r == mine ? nil : r)
                } label: {
                    Text("\(r.emoji)  \(r.label)" + (r == mine ? "  ✓" : ""))
                }
            }
            if mine != nil {
                Button(role: .destructive) { react(nil) } label: { Label(L("social.reaction.remove"), systemImage: "xmark") }
            }
        } label: {
            HStack(spacing: 5) {
                if let mine { Text(mine.emoji) } else { Image(systemName: "heart") }
                if label { Text(mine?.label ?? L("social.reaction.react")).font(.subheadline.weight(.medium)) }
            }
            .foregroundStyle(mine != nil ? Palette.primary : Palette.textMuted)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        } primaryAction: {
            react(mine == nil ? .love : nil)
        }
        .accessibilityLabel(mine.map { L("social.reaction.yours", ["emoji": $0.label]) } ?? L("social.reaction.react"))
        .accessibilityHint(L("social.reaction.hint"))
    }
}

/// The reactions as context-menu buttons (long-press on a comment or message).
struct ReactionMenuItems: View {
    let mine: PersonReaction?
    let react: (PersonReaction?) -> Void

    var body: some View {
        ControlGroup {
            ForEach(PersonReaction.ordered.prefix(4), id: \.self) { r in
                Button { react(r == mine ? nil : r) } label: { Text(r.emoji) }
                    .accessibilityLabel(r.label)
            }
        }
        ControlGroup {
            ForEach(PersonReaction.ordered.suffix(2), id: \.self) { r in
                Button { react(r == mine ? nil : r) } label: { Text(r.emoji) }
                    .accessibilityLabel(r.label)
            }
            if mine != nil {
                Button { react(nil) } label: { Image(systemName: "xmark") }
                    .accessibilityLabel(L("social.reaction.remove"))
            }
        }
    }
}

/// Who reacted: people and frames, newest first.
struct ReactionListSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let kind: ReactionTargetKind
    let targetId: String
    var postId: String?
    @State private var list: ReactionList?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if let list {
                    List {
                        if !list.frames.isEmpty {
                            Section(L("social.reactions.frames")) {
                                ForEach(Array(list.frames.enumerated()), id: \.offset) { _, f in
                                    HStack(spacing: 12) {
                                        Image(systemName: "photo.on.rectangle").frame(width: 36, height: 36)
                                            .background(Circle().fill(Palette.surface2)).foregroundStyle(Palette.textMuted)
                                        Text(f.frameName)
                                        Spacer()
                                        Text(PersonReaction(f.reaction).emoji).font(.title3)
                                    }
                                }
                            }
                            .listRowBackground(Palette.surface)
                        }
                        if !list.people.isEmpty {
                            Section(L("social.reactions.people")) {
                                ForEach(Array(list.people.enumerated()), id: \.offset) { _, p in
                                    HStack(spacing: 12) {
                                        AvatarView(name: PersonName.display(p.person), url: p.person.avatarUrl, size: 36)
                                        Text(PersonName.display(p.person, me: app.me?.id, you: true))
                                        Spacer()
                                        Text(p.emoji.emoji).font(.title3)
                                    }
                                    .accessibilityElement(children: .combine)
                                }
                            }
                            .listRowBackground(Palette.surface)
                        }
                    }
                    .scrollContentBackground(.hidden)
                } else if let error {
                    ErrorRetryView(message: error) { Task { await load() } }.padding(16)
                } else {
                    LoadingView()
                }
            }
            .pageBackground()
            .navigationTitle(L("social.reactions.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
            .task { await load() }
        }
        .presentationDetents([.medium, .large])
    }

    private func load() async {
        guard let api = app.api else { return }
        do {
            list = try await api.reactions(kind, targetId, postId: postId)
            error = nil
        } catch {
            self.error = errorMessage(error)
        }
    }
}

// MARK: Mentions

/// A comment or message body: mentions in bold (tap opens the person), links tappable.
struct MentionText: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openURL) private var openURL
    let body_: String
    let mentions: [Mention]?

    init(_ body: String, mentions: [Mention]?) {
        body_ = body
        self.mentions = mentions
    }

    var body: some View {
        Text(Mentions.attributed(body_, mentions: mentions))
            .tint(Palette.primary)
            .environment(\.openURL, OpenURLAction { url in
                if url.scheme == "kinframe-person" {
                    let id = url.absoluteString.replacingOccurrences(of: "kinframe-person:", with: "")
                    app.social.personSheet = PersonRoute(userId: id, account: app.accountId)
                    return .handled
                }
                return .systemAction
            })
    }
}

/// A text field with @mention suggestions above the keyboard: type "@" and pick a person.
struct MentionField: View {
    let placeholder: String
    @Binding var text: String
    @Binding var picks: [MentionCandidate]
    let candidates: [MentionCandidate]
    let me: String?
    var maxLength: Int
    var focused: FocusState<Bool>.Binding

    private var suggestions: [MentionCandidate] {
        guard let q = Mentions.activeQuery(text) else { return [] }
        return Mentions.filter(candidates, query: q, excluding: me)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !suggestions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(suggestions) { c in
                            Button {
                                text = Mentions.complete(text, with: c)
                                if !picks.contains(c) { picks.append(c) }
                            } label: {
                                HStack(spacing: 6) {
                                    AvatarView(name: c.name, url: c.avatarUrl, size: 24)
                                    Text(c.name).font(.subheadline.weight(.medium))
                                }
                                .padding(.horizontal, 10)
                                .frame(minHeight: 36)
                                .background(Capsule().fill(Palette.surface2))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(L("social.mention.pick", ["name": c.name]))
                        }
                    }
                }
                .transition(.opacity)
            }
            TextField(placeholder, text: $text, axis: .vertical)
                .lineLimit(1...6)
                .focused(focused)
                .onChange(of: text) {
                    if text.unicodeScalars.count > maxLength {
                        text = String(String.UnicodeScalarView(text.unicodeScalars.prefix(maxLength)))
                    }
                }
        }
        .animation(.easeOut(duration: 0.15), value: suggestions.map(\.id))
    }
}

// MARK: Freshness

/// A ring around a frame's or family's picture: full and warm when shared today, thinner and paler over 14 days.
struct FreshnessRing<Content: View>: View {
    let freshness: Freshness?
    var size: CGFloat = 64
    @ViewBuilder var content: Content

    var body: some View {
        let level = FreshnessText.ring(freshness)
        ZStack {
            Circle().stroke(Palette.border, lineWidth: 3)
            Circle()
                .trim(from: 0, to: level)
                .stroke(level > 0.5 ? Palette.primary : Palette.primary.opacity(0.35 + level), style: StrokeStyle(lineWidth: 2 + 2 * level, lineCap: .round))
                .rotationEffect(.degrees(-90))
            content
                .frame(width: size - 10, height: size - 10)
                .clipShape(Circle())
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

// MARK: Media

/// The photos of a post, large, swiped through one by one; videos play in place with the controls.
struct MediaPager: View {
    @Environment(AppModel.self) private var app
    let items: [Item]
    var height: CGFloat = 340
    @Binding var index: Int
    @State private var playing: Item?

    var body: some View {
        TabView(selection: $index) {
            ForEach(Array(items.enumerated()), id: \.element.id) { n, item in
                ItemThumb(item: item)
                    .livePlayback(api: app.api, item: item)
                    .overlay {
                        if item.kind == .video, item.status == .ready {
                            Button { playing = item } label: {
                                Image(systemName: "play.fill").font(.title2).foregroundStyle(.white)
                                    .frame(width: 60, height: 60).background(Circle().fill(.black.opacity(0.45)))
                            }
                            .accessibilityLabel(L("social.media.play"))
                        }
                    }
                    .tag(n)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: items.count > 1 ? .automatic : .never))
        .frame(height: height)
        .clipped()
        .sheet(item: $playing) { item in
            VideoSheet(itemId: item.id)
        }
    }
}

/// Plays one item's master (a video of a post or a chat).
struct VideoSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let itemId: String
    @State private var player: AVPlayer?
    @State private var failed = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let player {
                    VideoPlayer(player: player).ignoresSafeArea(edges: .bottom)
                } else if failed {
                    Callout(L("social.media.failed"), kind: .error).padding()
                } else {
                    ProgressView().tint(.white)
                }
            }
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
            .task {
                guard let api = app.api else { return }
                do {
                    let asset = try await MotionClip.asset(api: api, itemId: itemId, variant: "master")
                    let p = AVPlayer(playerItem: AVPlayerItem(asset: asset))
                    player = p
                    p.play()
                } catch {
                    failed = true
                }
            }
            .onDisappear { player?.pause() }
        }
    }
}

// MARK: Reports

/// Report a comment, message or post: a reason and an optional note. The reported person never learns who.
struct ReportSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let kind: ReportTargetKind
    let targetId: String
    var inFamily: Bool
    @State private var reason: ReportReason = .unkind
    @State private var note = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(L("chat.report.reason"), selection: $reason) {
                        ForEach(ReportReason.allCases, id: \.self) { r in Text(r.label).tag(r) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text(L("chat.report.reason"))
                } footer: {
                    Text(L(inFamily ? "chat.report.toFamily" : "chat.report.toOperator"))
                }
                .listRowBackground(Palette.surface)
                Section(L("chat.report.note")) {
                    TextField(L("chat.report.notePlaceholder"), text: $note, axis: .vertical)
                        .lineLimit(2...5)
                        .onChange(of: note) { if note.count > 500 { note = String(note.prefix(500)) } }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("chat.report.title.\(kind.rawValue)"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("chat.report.send")) { Task { await send() } }.fontWeight(.semibold).disabled(busy)
                }
            }
        }
    }

    private func send() async {
        guard let api = app.api else { return }
        busy = true
        defer { busy = false }
        do {
            _ = try await api.report(kind, targetId, reason: reason, note: note)
            app.toasts.show(L("chat.report.sent"))
            dismiss()
        } catch {
            app.toasts.error(error)
        }
    }
}

/// What a report sheet is about.
struct ReportTarget: Identifiable, Hashable {
    var kind: ReportTargetKind
    var id: String
    var inFamily: Bool
}

// MARK: People

/// A person's picture and name, or "Former member".
struct PersonChip: View {
    let person: PersonRef?
    var size: CGFloat = 32

    var body: some View {
        AvatarView(name: PersonName.display(person), url: person?.formerMember == true ? nil : person?.avatarUrl, size: size)
    }
}

/// The plain statement that there is no end-to-end encryption (decision B).
struct OperatorNote: View {
    let server: String

    var body: some View {
        Label(L("chat.operatorNote", ["server": server]), systemImage: "lock.open")
            .font(.footnote)
            .foregroundStyle(Palette.textMuted)
            .fixedSize(horizontal: false, vertical: true)
    }
}

