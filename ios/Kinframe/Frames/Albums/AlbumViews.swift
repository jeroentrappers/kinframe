import KinframeKit
import SwiftUI

/// The album being looked at: cover, name, count, playing and schedule badges, and its actions (web: album-head).
struct AlbumHeader: View {
    let album: Album
    let cover: Placement?
    let playing: Bool
    let control: Bool
    let manage: Bool
    let canArrange: Bool
    let playBusy: Bool
    let togglePlaying: () -> Void
    let act: (FrameLibrarySection.AlbumSheet) -> Void
    let delete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 14) {
                Group {
                    if let cover {
                        RemoteThumb(url: cover.item.thumbUrl, kind: cover.item.kind, cornerRadius: 12)
                    } else {
                        RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Palette.surface2)
                            .overlay { Image(systemName: "rectangle.stack").font(.title2).foregroundStyle(Palette.textFaint) }
                    }
                }
                .frame(width: 76, height: 76)
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(album.name).font(.display(.title2)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
                    Text(Lp("albums.count", count: album.placementCount)).font(.subheadline.monospacedDigit()).foregroundStyle(Palette.textMuted)
                    FlowLayout(spacing: 6) {
                        if playing {
                            Pill(L("albums.playingBadge"), systemImage: "play.fill", foreground: Palette.onPrimary, background: Palette.primary)
                        }
                        if let from = album.schedule?.fromMonthDay, let to = album.schedule?.toMonthDay {
                            let on = AlbumLogic.covers(album.schedule)
                            Pill(L("albums.scheduleShort", ["from": AlbumLogic.format(from, locale: L10n.locale), "to": AlbumLogic.format(to, locale: L10n.locale)]),
                                 systemImage: "calendar", foreground: on ? Palette.accentText : Palette.textMuted, background: on ? Palette.accentSoft : Palette.surface2)
                                .accessibilityLabel(L("albums.scheduled"))
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                if control {
                    Button(action: togglePlaying) {
                        HStack(spacing: 6) {
                            if playBusy { ProgressView() } else { Image(systemName: playing ? "xmark" : "play.fill") }
                            Text(playing ? L("albums.stopPlaying") : L("albums.play"))
                        }
                    }
                    .buttonStyle(.kf(playing ? .secondary : .primary))
                    .disabled(playBusy)
                }
                Spacer()
                if manage {
                    Menu {
                        Button { act(.arrange) } label: { Label(L("albums.arrange"), systemImage: "arrow.up.arrow.down") }
                            .disabled(!canArrange)
                        Button { act(.schedule) } label: { Label(L("albums.schedule"), systemImage: "calendar") }
                        Button { act(.rename) } label: { Label(L("albums.rename"), systemImage: "pencil") }
                        Button(role: .destructive, action: delete) { Label(L("albums.delete"), systemImage: "trash") }
                    } label: {
                        Image(systemName: "ellipsis.circle").font(.title2).frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(L("ios.albums.more"))
                }
            }
        }
        .card(padding: 16)
    }
}

/// Name a new album, or rename one (web: AlbumNameDialog).
struct AlbumNameSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let submit: String
    let hint: String?
    let initial: String
    let save: (String) async throws -> Void
    @State private var name = ""
    @State private var busy = false
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L("albums.namePlaceholder"), text: $name)
                        .focused($focused)
                        .submitLabel(.done)
                        .onSubmit { Task { await go() } }
                        .onChange(of: name) { _, v in if v.count > AlbumLogic.nameMax { name = String(v.prefix(AlbumLogic.nameMax)) } }
                } header: {
                    Text(L("albums.name"))
                } footer: {
                    if let hint { Text(hint) }
                }
                if let error {
                    Section { Text(error).foregroundStyle(Palette.danger) }
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else {
                        Button(submit) { Task { await go() } }
                            .fontWeight(.semibold)
                            .disabled(AlbumLogic.cleanName(name) == nil || AlbumLogic.cleanName(name) == AlbumLogic.cleanName(initial))
                    }
                }
            }
            .onAppear { name = initial; focused = true }
        }
        .presentationDetents([.medium])
    }

    private func go() async {
        guard let clean = AlbumLogic.cleanName(name), clean != AlbumLogic.cleanName(initial), !busy else { return }
        busy = true
        defer { busy = false }
        do {
            try await save(clean)
            dismiss()
        } catch {
            self.error = error is AlbumConflict ? L("albums.conflict") : errorMessage(error)
        }
    }
}

/// Add photos to an album: an existing one or a new one (web: AlbumPickerDialog).
struct AlbumPickerSheet: View {
    enum Choice {
        case existing(Album)
        case new(String)
    }

    @Environment(\.dismiss) private var dismiss
    let albums: [Album]
    let count: Int
    let add: (Choice) async throws -> Void
    @State private var choice: String?
    @State private var newName = ""
    @State private var busy = false
    @State private var error: String?

    private var valid: Bool {
        guard let choice else { return false }
        return choice == "new" ? AlbumLogic.cleanName(newName) != nil : true
    }

    var body: some View {
        NavigationStack {
            List {
                Section(L("albums.pick")) {
                    ForEach(albums) { a in
                        row(a.name, systemImage: "rectangle.stack", count: a.placementCount, id: a.id)
                    }
                    row(L("albums.new"), systemImage: "plus", count: nil, id: "new")
                    if choice == "new" {
                        TextField(L("albums.namePlaceholder"), text: $newName)
                            .accessibilityLabel(L("albums.name"))
                            .onChange(of: newName) { _, v in if v.count > AlbumLogic.nameMax { newName = String(v.prefix(AlbumLogic.nameMax)) } }
                    }
                }
                .listRowBackground(Palette.surface)
                if let error {
                    Section { Text(error).foregroundStyle(Palette.danger) }.listRowBackground(Palette.surface)
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(Lp("albums.addTitle", count: count))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else {
                        Button(L("albums.add")) { Task { await go() } }.fontWeight(.semibold).disabled(!valid)
                    }
                }
            }
            .onAppear { choice = albums.first?.id ?? "new" }
        }
    }

    private func row(_ title: String, systemImage: String, count: Int?, id: String) -> some View {
        Button {
            choice = id
        } label: {
            HStack(spacing: 12) {
                Image(systemName: systemImage).foregroundStyle(Palette.primary).frame(width: 24)
                Text(title).foregroundStyle(Palette.text)
                Spacer()
                if let count { Text("\(count)").monospacedDigit().foregroundStyle(Palette.textMuted) }
                Image(systemName: choice == id ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(choice == id ? Palette.primary : Palette.borderStrong)
            }
            .frame(minHeight: 36)
        }
        .accessibilityAddTraits(choice == id ? .isSelected : [])
    }

    private func go() async {
        guard valid, let choice, !busy else { return }
        busy = true
        defer { busy = false }
        do {
            if choice == "new", let name = AlbumLogic.cleanName(newName) {
                try await add(.new(name))
            } else if let album = albums.first(where: { $0.id == choice }) {
                try await add(.existing(album))
            }
            dismiss()
        } catch {
            self.error = errorMessage(error)
        }
    }
}

/// Play an album by itself every year between two days (web: AlbumScheduleDialog).
struct AlbumScheduleSheet: View {
    @Environment(\.dismiss) private var dismiss
    let album: Album
    let save: (String?, String?) async throws -> Void
    @State private var enabled = false
    @State private var fromM = 12
    @State private var fromD = 1
    @State private var toM = 12
    @State private var toD = 31
    @State private var busy = false
    @State private var error: String?

    private var from: String { AlbumLogic.monthDay(fromM, fromD) }
    private var to: String { AlbumLogic.monthDay(toM, toD) }
    private let months = AlbumLogic.monthNames(locale: L10n.locale)

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle(L("albums.scheduleOn"), isOn: $enabled.animation()).tint(Palette.accent)
                } footer: {
                    Text(L("albums.scheduleBody"))
                }
                if enabled {
                    Section(L("albums.from")) {
                        dayMonth(day: $fromD, month: $fromM, dayLabel: L("albums.fromDay"), monthLabel: L("albums.fromMonth"))
                    }
                    Section(L("albums.to")) {
                        dayMonth(day: $toD, month: $toM, dayLabel: L("albums.toDay"), monthLabel: L("albums.toMonth"))
                    }
                    Section {
                        Label {
                            Text(L("albums.scheduleSummary", ["from": AlbumLogic.format(from, locale: L10n.locale), "to": AlbumLogic.format(to, locale: L10n.locale)])
                                 + (AlbumLogic.wraps(from: from, to: to) ? " " + L("albums.scheduleWraps") : ""))
                        } icon: {
                            Image(systemName: "calendar")
                        }
                        .font(.subheadline)
                        .foregroundStyle(Palette.textMuted)
                    }
                }
                if let error {
                    Section { Text(error).foregroundStyle(Palette.danger) }
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("albums.scheduleTitle", ["name": album.name]))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else { Button(L("common.save")) { Task { await go() } }.fontWeight(.semibold) }
                }
            }
            .onAppear(perform: reset)
            .onChange(of: fromM) { _, m in fromD = AlbumLogic.clampDay(fromD, month: m) }
            .onChange(of: toM) { _, m in toD = AlbumLogic.clampDay(toD, month: m) }
        }
    }

    private func dayMonth(day: Binding<Int>, month: Binding<Int>, dayLabel: String, monthLabel: String) -> some View {
        HStack {
            Picker(dayLabel, selection: day) {
                ForEach(1...AlbumLogic.daysInMonth(month.wrappedValue), id: \.self) { Text("\($0)").tag($0) }
            }
            .labelsHidden()
            .accessibilityLabel(dayLabel)
            Picker(monthLabel, selection: month) {
                ForEach(1...12, id: \.self) { m in Text(months.indices.contains(m - 1) ? months[m - 1] : "\(m)").tag(m) }
            }
            .labelsHidden()
            .accessibilityLabel(monthLabel)
            Spacer()
        }
    }

    private func reset() {
        if let f = AlbumLogic.parseMonthDay(album.schedule?.fromMonthDay), let t = AlbumLogic.parseMonthDay(album.schedule?.toMonthDay) {
            enabled = true
            (fromM, fromD, toM, toD) = (f.month, f.day, t.month, t.day)
        } else {
            // Suggest the month we're in, which is usually what people schedule for.
            let m = Foundation.Calendar.current.component(.month, from: Date())
            enabled = false
            (fromM, fromD, toM, toD) = (m, 1, m, AlbumLogic.daysInMonth(m))
        }
    }

    private func go() async {
        busy = true
        defer { busy = false }
        do {
            try await save(enabled ? from : nil, enabled ? to : nil)
            dismiss()
        } catch {
            self.error = error is AlbumConflict ? L("albums.conflict") : errorMessage(error)
        }
    }
}

/// Change an album's order by dragging (web: arrange mode).
struct AlbumArrangeSheet: View {
    @Environment(\.dismiss) private var dismiss
    let placements: [Placement]
    let save: ([String]) async throws -> Void
    @State private var order: [Placement] = []
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(order.enumerated()), id: \.element.id) { i, p in
                        HStack(spacing: 12) {
                            Text("\(i + 1)").font(.subheadline.monospacedDigit()).foregroundStyle(Palette.textMuted).frame(minWidth: 24)
                            RemoteThumb(url: p.item.thumbUrl, kind: p.item.kind, cornerRadius: 8).frame(width: 56, height: 56)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.item.caption.flatMap { $0.isEmpty ? nil : $0 } ?? L("library.from", ["name": p.item.senderName ?? "?"]))
                                    .lineLimit(1).foregroundStyle(Palette.text)
                                if let taken = p.item.capturedAt {
                                    Text(Format.dateTime(taken, locale: L10n.locale)).font(.caption).foregroundStyle(Palette.textMuted)
                                }
                            }
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel(L("albums.moveItem", ["n": i + 1, "total": order.count]))
                    }
                    .onMove { order.move(fromOffsets: $0, toOffset: $1) }
                } footer: {
                    Text(L("ios.albums.arrangeHint"))
                }
                .listRowBackground(Palette.surface)
                if let error {
                    Section { Text(error).foregroundStyle(Palette.danger) }
                }
            }
            .environment(\.editMode, .constant(.active))
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(L("albums.arrange"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else {
                        Button(L("albums.saveOrder")) { Task { await go() } }
                            .fontWeight(.semibold)
                            .disabled(order.map(\.id) == placements.map(\.id))
                    }
                }
            }
            .onAppear { order = placements }
        }
    }

    private func go() async {
        busy = true
        defer { busy = false }
        do {
            try await save(order.map(\.id))
            dismiss()
        } catch {
            self.error = error is AlbumConflict ? L("albums.conflict") : errorMessage(error)
        }
    }
}

/// "On this day": photos taken on today's date in earlier years (web: Memories). Hidden when there are none.
struct MemoriesStrip: View {
    let memories: [Placement]
    let open: (Placement) -> Void

    var body: some View {
        if !memories.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Label(L("memories.title"), systemImage: "sparkles")
                        .font(.headline).foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                    Text(Lp("memories.subtitle", count: memories.count, ["date": Date().formatted(.dateTime.day().month(.wide).locale(L10n.locale))]))
                        .font(.footnote).foregroundStyle(Palette.textMuted)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(memories) { p in
                            Button { open(p) } label: {
                                RemoteThumb(url: p.item.thumbUrl, kind: p.item.kind, cornerRadius: 10)
                                    .frame(width: 104, height: 104)
                                    .overlay(alignment: .bottomLeading) {
                                        let years = AlbumLogic.yearsAgo(p.item.capturedAt)
                                        if years > 0 {
                                            Text(Lp("memories.yearsAgo", count: years))
                                                .font(.caption2.weight(.bold)).foregroundStyle(.white)
                                                .padding(.horizontal, 6).padding(.vertical, 3)
                                                .background(Capsule().fill(.black.opacity(0.55)))
                                                .padding(6)
                                        }
                                    }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel((p.item.caption ?? L("library.photo")) + ", " + Lp("memories.yearsAgo", count: max(1, AlbumLogic.yearsAgo(p.item.capturedAt))))
                        }
                    }
                }
            }
            .card(padding: 14)
        }
    }
}
