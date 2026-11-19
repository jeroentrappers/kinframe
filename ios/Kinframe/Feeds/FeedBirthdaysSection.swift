import KinframeKit
import PhotosUI
import SwiftUI
import UIKit

/// A feed's birthdays (docs/spec/birthdays.md): the list sorted by the next birthday, today's greetings from the
/// frames, suggestions waiting for an admin, declined suggestions, and the member's own "Birthday notifications".
/// Owner and admins (and posters when the feed allows it) add people directly; other posters suggest them.
struct FeedBirthdaysSection: View {
    @Environment(AppModel.self) private var app
    let model: FeedDetailModel
    @State private var editing: BirthdaySheet.Mode?
    @State private var showDeclined = false
    @State private var busy: String?
    @State private var notifications = true

    private var abilities: BirthdayAbilities? { model.birthdayAbilities }
    private var today: LocalDate { LocalDate.today() }
    private var sections: BirthdayList.Sections { BirthdayList.sections(model.birthdays, today: today) }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            actions
            if !sections.pending.isEmpty { pending }
            list
            if !todaysGreetings.isEmpty || !model.greetings.isEmpty { greetings }
            if !sections.rejected.isEmpty { declined }
            notificationSwitch
        }
        .sheet(item: $editing) { mode in
            BirthdaySheet(model: model, mode: mode)
        }
        .onAppear { notifications = model.feed?.value2.birthdayNotifications ?? true }
        .onChange(of: model.feed?.value2.birthdayNotifications) { notifications = model.feed?.value2.birthdayNotifications ?? true }
        .task {
            if !model.birthdaysLoaded { await model.loadBirthdays() }
        }
    }

    // MARK: Add

    @ViewBuilder
    private var actions: some View {
        if let a = abilities {
            VStack(alignment: .leading, spacing: 8) {
                FlowLayout(spacing: 8) {
                    Button { editing = .add(mine: false) } label: {
                        Label(a.addsDirectly ? L("ios.bday.add") : L("ios.bday.suggest"), systemImage: "gift")
                    }
                    .buttonStyle(.kf(.primary))
                    if BirthdayList.canAddMine(model.birthdays, me: app.me?.id) {
                        Button { editing = .add(mine: true) } label: {
                            Label(a.addsDirectly ? L("ios.bday.addMine") : L("ios.bday.suggestMine"), systemImage: "person.crop.circle.badge.plus")
                        }
                        .buttonStyle(.kf(.secondary))
                    }
                }
                if a.suggests {
                    Text(L("ios.bday.suggestHint")).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    // MARK: Suggestions

    private var pending: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("ios.bday.pendingTitle"))
            ForEach(sections.pending) { p in
                VStack(alignment: .leading, spacing: 10) {
                    row(p)
                    if abilities?.canDecide(p) != true {
                        Text(waitingText).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                    FlowLayout(spacing: 8) {
                        if abilities?.canDecide(p) == true {
                            Button(L("ios.bday.approve")) { Task { await decide(p, true) } }.buttonStyle(.kf(.primary))
                            Button(L("ios.bday.reject")) { Task { await decide(p, false) } }.buttonStyle(.kf(.ghost))
                        }
                        if abilities?.canEdit(p) == true {
                            // Admins may correct a suggestion before approving it; the suggester edits or withdraws it.
                            Button(L("ios.bday.edit")) { editing = .edit(p) }.buttonStyle(.kf(.secondary))
                        }
                    }
                    .disabled(busy == p.id)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .card(padding: 14)
            }
        }
    }

    private var waitingText: String {
        if let owner = model.feed?.value2.ownerName { return L("ios.bday.waitingFor", ["name": owner]) }
        return L("ios.bday.waiting")
    }

    private func decide(_ p: BirthdayPerson, _ approve: Bool) async {
        busy = p.id
        defer { busy = nil }
        do {
            _ = try await model.decide(p, approve: approve)
            app.toasts.show(approve ? L("ios.bday.approved", ["name": p.displayName]) : L("ios.bday.rejected", ["name": p.displayName]))
        } catch let e as APIError where e.code == "already_decided" {
            app.toasts.show(L("ios.bday.alreadyDecided"), isError: true)
            await model.loadBirthdays()
        } catch let e as APIError where e.code == "duplicate_birthday" {
            app.toasts.show(L("ios.bday.duplicate", ["name": p.displayName]), isError: true)
        } catch {
            app.toasts.error(error)
        }
    }

    // MARK: The list

    @ViewBuilder
    private var list: some View {
        if sections.approved.isEmpty {
            if model.birthdaysLoaded && sections.pending.isEmpty {
                EmptyStateView(systemImage: "gift", title: L("ios.bday.emptyTitle"), message: L("ios.bday.emptyBody")) { EmptyView() }
                    .card(padding: 0)
            } else if !model.birthdaysLoaded {
                ProgressView().frame(maxWidth: .infinity).padding(24)
            }
        } else {
            VStack(spacing: 0) {
                ForEach(Array(sections.approved.enumerated()), id: \.element.id) { n, p in
                    if n > 0 { Divider().overlay(Palette.border).padding(.leading, 70) }
                    Button {
                        if abilities?.canEdit(p) == true { editing = .edit(p) }
                    } label: {
                        row(p).padding(.horizontal, 14).padding(.vertical, 10).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(abilities?.canEdit(p) != true)
                    .accessibilityHint(abilities?.canEdit(p) == true ? L("ios.bday.edit") : "")
                }
            }
            .card(padding: 0)
        }
    }

    private func row(_ p: BirthdayPerson) -> some View {
        let isToday = p.status == .approved && p.isToday(today)
        return HStack(alignment: .center, spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                AvatarView(name: p.displayName, url: p.photoUrl, size: 44)
                if isToday {
                    Text("🎈").font(.caption).accessibilityHidden(true)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(p.displayName).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                    if p.userId != nil, p.userId == app.me?.id {
                        Text("(\(L("people.you")))").font(.subheadline).foregroundStyle(Palette.textMuted)
                    }
                }
                Text(BirthdayList.subtitle(p, today: today))
                    .font(.footnote)
                    .foregroundStyle(isToday ? Palette.primary : Palette.textMuted)
                ForEach(details(p), id: \.self) { line in
                    Text(line).font(.caption).foregroundStyle(Palette.textFaint).fixedSize(horizontal: false, vertical: true)
                }
                if isToday {
                    let todays = BirthdayGreetings.forPerson(model.greetings, birthdayId: p.id, date: today)
                    if !todays.isEmpty {
                        Text("🎉 " + BirthdayGreetings.line(todays)).font(.footnote).foregroundStyle(Palette.text)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            Spacer(minLength: 0)
            if abilities?.canEdit(p) == true, p.status == .approved {
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Palette.textFaint).accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// Linked member, who suggested it, and whether the age stays off the frames.
    private func details(_ p: BirthdayPerson) -> [String] {
        var out: [String] = []
        if let name = p.linkedMemberName, p.userId != app.me?.id { out.append(L("ios.bday.linked", ["name": name])) }
        if p.status != .approved, p.suggested == true {
            out.append(L("ios.bday.suggestedBy", ["name": p.createdByName ?? L("ios.bday.formerMember")]))
        }
        if p.birthYear != nil, !p.showAge { out.append(L("ios.bday.ageHidden")) }
        return out
    }

    // MARK: Greetings

    private var todaysGreetings: [FeedBirthdayGreeting] {
        model.greetings.filter { LocalDate(iso: $0.date) == today }
    }

    private var greetings: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(todaysGreetings.isEmpty ? L("ios.bday.greetingsTitle") : L("ios.bday.greetingsToday"))
            VStack(alignment: .leading, spacing: 8) {
                ForEach((todaysGreetings.isEmpty ? model.greetings : todaysGreetings).prefix(10)) { g in
                    HStack(alignment: .firstTextBaseline) {
                        Text(L("ios.bday.greeting", ["frame": g.frameName, "name": g.birthdayName]))
                            .font(.subheadline)
                            .foregroundStyle(Palette.text)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer()
                        Text(g.createdAt, format: LocalDate(iso: g.date) == today ? Date.FormatStyle.dateTime.hour().minute()
                                                                                   : Date.FormatStyle.dateTime.day().month())
                            .font(.footnote)
                            .foregroundStyle(Palette.textMuted)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card(padding: 14)
        }
    }

    // MARK: Declined

    private var declined: some View {
        DisclosureGroup(isExpanded: $showDeclined) {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(sections.rejected) { p in
                    HStack {
                        row(p)
                        if abilities?.canEdit(p) == true {
                            Button(L("ios.bday.remove"), role: .destructive) { Task { await remove(p) } }.buttonStyle(.kf(.dangerGhost))
                        }
                    }
                    .opacity(0.75)
                }
                Text(L("ios.bday.declinedHint")).font(.footnote).foregroundStyle(Palette.textMuted)
            }
            .padding(.top, 8)
        } label: {
            Text("\(L("ios.bday.declinedTitle")) (\(sections.rejected.count))").font(.subheadline).foregroundStyle(Palette.textMuted)
        }
    }

    private func remove(_ p: BirthdayPerson) async {
        do {
            try await model.removeBirthday(p)
        } catch {
            app.toasts.error(error)
        }
    }

    // MARK: Notifications

    private var notificationSwitch: some View {
        Toggle(isOn: Binding(get: { notifications }, set: { on in
            notifications = on
            Task { await setNotifications(on) }
        })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("ios.bday.notifications"))
                Text(L("ios.bday.notificationsHint")).font(.footnote).foregroundStyle(Palette.textMuted).fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Palette.accent)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .card(padding: 0)
    }

    private func setNotifications(_ on: Bool) async {
        let feedName = model.feed?.name ?? ""
        do {
            try await model.setBirthdayNotifications(on)
            app.toasts.show(L(on ? "ios.bday.notificationsOn" : "ios.bday.notificationsOff", ["feed": feedName]))
        } catch {
            notifications = !on
            app.toasts.error(error)
        }
    }
}

/// Add, suggest or edit a birthday person: name, day and month, an optional year, "Show age", a link to a feed member
/// and a photo (square, uploaded like an avatar). Editing also removes the entry (or withdraws a suggestion), with
/// Undo instead of a confirmation.
struct BirthdaySheet: View {
    enum Mode: Identifiable {
        case add(mine: Bool)
        case edit(BirthdayPerson)

        var id: String {
            switch self {
            case .add(let mine): mine ? "add-mine" : "add"
            case .edit(let p): p.id
            }
        }
    }

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let model: FeedDetailModel
    let mode: Mode
    @State private var draft = BirthdayDraft()
    @State private var original = BirthdayDraft()
    @State private var photoPick: PhotosPickerItem?
    /// A photo picked before the entry exists; uploaded right after it's created.
    @State private var pendingPhoto: Data?
    @State private var person: BirthdayPerson?
    @State private var saving = false
    @State private var problem: String?

    private let currentYear = LocalDate.today().year
    private var abilities: BirthdayAbilities { model.birthdayAbilities ?? BirthdayAbilities(role: .poster) }
    private var me: String? { app.me?.id }

    private var fields: BirthdayAbilities.Fields {
        guard let person else { return .all }
        return abilities.fields(person, me: me, addedByMe: birthdayAddedBy(person, me: me))
    }

    private var title: String {
        switch mode {
        case .add: abilities.addsDirectly ? L("ios.bday.addTitle") : L("ios.bday.suggestTitle")
        case .edit(let p): L("ios.bday.editTitle", ["name": p.displayName])
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                if fields == .privacy {
                    Section { Text(L("ios.bday.privacyOnly")).font(.subheadline).foregroundStyle(Palette.textMuted) }
                }
                if fields == .all { nameSection }
                dateSection
                yearSection
                if fields == .all { linkSection }
                photoSection
                if let problem {
                    Section { Callout(problem, kind: .error).listRowInsets(EdgeInsets()) }
                }
                if person != nil { removeSection }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(person == nil && abilities.suggests ? L("ios.bday.suggest") : L("common.save")) { Task { await save() } }
                        .fontWeight(.semibold)
                        .disabled(saving || draft.trimmedName.isEmpty || (person != nil && draft == original && pendingPhoto == nil))
                }
            }
            .onAppear(perform: start)
            .onChange(of: photoPick) { _, item in
                guard let item else { return }
                photoPick = nil
                Task { await pickPhoto(item) }
            }
        }
    }

    // MARK: Fields

    private var nameSection: some View {
        Section {
            TextField(L("ios.bday.namePlaceholder"), text: $draft.name)
                .textInputAutocapitalization(.words)
                .onChange(of: draft.name) { if draft.name.count > BirthdayDraft.maxName { draft.name = String(draft.name.prefix(BirthdayDraft.maxName)) } }
        } header: {
            Text(L("ios.bday.name"))
        } footer: {
            Text(L("ios.bday.nameHint"))
        }
    }

    private var dateSection: some View {
        Section {
            Picker(L("ios.bday.day"), selection: $draft.day) {
                ForEach(1...31, id: \.self) { Text("\($0)").tag($0) }
            }
            Picker(L("ios.bday.month"), selection: $draft.month) {
                ForEach(1...12, id: \.self) { m in Text(monthName(m)).tag(m) }
            }
        } header: {
            Text(L("ios.bday.date"))
        } footer: {
            if draft.day == 29, draft.month == 2 { Text(L("ios.bday.leapNote")) }
        }
        .disabled(fields == .privacy)
    }

    private var yearSection: some View {
        Section {
            Picker(L("ios.bday.year"), selection: $draft.year) {
                Text(L("ios.bday.yearNone")).tag(Int?.none)
                ForEach(Array((BirthdayDates.minYear...currentYear).reversed()), id: \.self) { y in Text(String(y)).tag(Int?.some(y)) }
            }
            Toggle(isOn: $draft.showAge) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("ios.bday.showAge"))
                    Text(L("ios.bday.showAgeHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
            .tint(Palette.accent)
            .disabled(draft.year == nil)
        } footer: {
            if draft.year != nil {
                Text(draft.effectiveShowAge ? L("ios.bday.yearHintShown") : L("ios.bday.yearHint"))
            }
        }
    }

    @ViewBuilder
    private var linkSection: some View {
        let others = Set(model.birthdays.filter { $0.isLive && $0.id != person?.id }.compactMap(\.userId))
        let members = model.members.filter { !others.contains($0.userId) }
        if !members.isEmpty {
            Section {
                Picker(L("ios.bday.link"), selection: $draft.userId) {
                    Text(L("ios.bday.linkNone")).tag(String?.none)
                    ForEach(members) { m in Text(m.displayName).tag(String?.some(m.userId)) }
                }
                .onChange(of: draft.userId) { old, new in
                    // Prefill the name from the member when it was empty or still the previous member's name.
                    guard let new, let m = members.first(where: { $0.userId == new }) else { return }
                    let previous = members.first { $0.userId == old }?.displayName
                    if draft.trimmedName.isEmpty || draft.trimmedName == previous { draft.name = String(m.displayName.prefix(BirthdayDraft.maxName)) }
                }
            } footer: {
                Text(L("ios.bday.linkHint"))
            }
        }
    }

    private var photoSection: some View {
        Section {
            HStack(spacing: 14) {
                if let data = pendingPhoto, let image = UIImage(data: data) {
                    Image(uiImage: image).resizable().scaledToFill().frame(width: 56, height: 56).clipShape(Circle())
                } else {
                    AvatarView(name: draft.trimmedName.isEmpty ? "?" : draft.trimmedName, url: person?.photoUrl, size: 56)
                }
                VStack(alignment: .leading, spacing: 8) {
                    PhotosPicker(selection: $photoPick, matching: .images) { Text(L("ios.bday.choosePhoto")) }
                        .buttonStyle(.kf(.soft))
                    if person?.hasOwnPhoto == true || pendingPhoto != nil {
                        Button(L("ios.bday.removePhoto"), role: .destructive) { Task { await removePhoto() } }
                            .buttonStyle(.kf(.dangerGhost))
                    }
                }
            }
            .padding(.vertical, 4)
            if fields == .privacy, me != nil, draft.userId == me {
                Button(L("ios.bday.unlinkMe")) { draft.userId = nil }
            }
        } header: {
            Text(L("ios.bday.photo"))
        } footer: {
            if person?.hasOwnPhoto != true, pendingPhoto == nil, draft.userId != nil { Text(L("ios.bday.photoFallback")) }
        }
    }

    private var removeSection: some View {
        Section {
            Button(role: .destructive) { Task { await remove() } } label: {
                Label(person?.status == .pending ? L("ios.bday.withdraw") : L("ios.bday.remove"), systemImage: "trash")
            }
            .disabled(saving)
        }
    }

    private func monthName(_ m: Int) -> String {
        let f = DateFormatter()
        f.locale = L10n.locale
        return f.standaloneMonthSymbols[m - 1].capitalized(with: L10n.locale)
    }

    // MARK: Actions

    private func start() {
        switch mode {
        case .add(let mine):
            var d = BirthdayDraft()
            if mine, let me = app.me {
                d.name = String(me.displayName.prefix(BirthdayDraft.maxName))
                d.userId = me.id
            }
            let t = LocalDate.today()
            d.day = t.day
            d.month = t.month
            draft = d
            original = d
        case .edit(let p):
            person = p
            draft = BirthdayDraft(p)
            original = draft
        }
    }

    private func message(for problem: BirthdayDraft.Problem) -> String {
        switch problem {
        case .noName: L("ios.bday.noName")
        case .date(.invalidDay): L("ios.bday.invalidDay")
        case .date(.invalidYear): L("ios.bday.invalidYear", ["year": currentYear])
        }
    }

    private func message(for error: Error) -> String {
        guard let e = error as? APIError else { return errorMessage(error) }
        switch e.code {
        case "duplicate_birthday":
            return L("ios.bday.duplicate", ["name": draft.trimmedName])
        case "limit_reached":
            return L("ios.bday.limitReached", ["max": app.limits.maxBirthdaysPerFeed])
        case "already_decided":
            return L("ios.bday.alreadyDecided")
        case "validation_failed":
            if e.fieldErrors.contains(where: { $0.field == "birth_year" }) { return message(for: .date(.invalidYear)) }
            if e.fieldErrors.contains(where: { $0.field == "day" || $0.field == "month" }) { return message(for: .date(.invalidDay)) }
            return e.message
        default:
            return e.message
        }
    }

    private func save() async {
        if let p = draft.problem(currentYear: currentYear) {
            problem = message(for: p)
            return
        }
        problem = nil
        saving = true
        defer { saving = false }
        do {
            if let person {
                let updated = try await model.updateBirthday(person, patch: draft.patch(from: original, fields: fields))
                if let jpeg = pendingPhoto { _ = try await model.setPhoto(updated, jpeg: jpeg) }
                app.toasts.show(L("common.saved"))
            } else {
                var created = try await model.addBirthday(draft)
                if let jpeg = pendingPhoto {
                    // The entry exists already; a failed photo upload shouldn't lose it.
                    do { created = try await model.setPhoto(created, jpeg: jpeg) } catch { app.toasts.error(error) }
                }
                app.toasts.show(created.status == .pending ? L("ios.bday.suggested") : L("ios.bday.added", ["name": created.displayName]))
            }
            dismiss()
        } catch {
            problem = message(for: error)
        }
    }

    private func pickPhoto(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data),
              let jpeg = squarePhotoJPEG(image, side: 512) else { return }
        guard let person else {
            pendingPhoto = jpeg
            return
        }
        saving = true
        defer { saving = false }
        do {
            self.person = try await model.setPhoto(person, jpeg: jpeg)
            app.toasts.show(L("ios.bday.photoSaved"))
        } catch {
            problem = message(for: error)
        }
    }

    private func removePhoto() async {
        if pendingPhoto != nil {
            pendingPhoto = nil
            return
        }
        guard let person else { return }
        do {
            self.person = try await model.removePhoto(person)
        } catch {
            problem = message(for: error)
        }
    }

    /// Remove at once and offer Undo, which creates the entry again (with its photo, which is fetched first).
    private func remove() async {
        guard let person else { return }
        saving = true
        defer { saving = false }
        var fetched: Data?
        if person.hasOwnPhoto == true, let s = person.photoUrl, let url = URL(string: s) {
            fetched = try? await URLSession.shared.data(from: url).0
        }
        let photo = fetched
        let restore = BirthdayDraft(person)
        let wasPending = person.status == .pending
        do {
            try await model.removeBirthday(person)
            dismiss()
            let model = model
            let toasts = app.toasts
            toasts.show(wasPending ? L("ios.bday.withdrawn") : L("ios.bday.removed", ["name": person.displayName]),
                        action: .init(title: L("common.undo")) {
                Task {
                    do {
                        let again = try await model.addBirthday(restore)
                        if let photo { _ = try? await model.setPhoto(again, jpeg: photo) }
                    } catch {
                        toasts.error(error)
                    }
                }
            })
        } catch {
            problem = message(for: error)
        }
    }
}

/// A centred square JPEG of `side` points, for photos the server crops to 256×256 (avatars, birthday photos).
func squarePhotoJPEG(_ image: UIImage, side: CGFloat) -> Data? {
    let s = min(image.size.width, image.size.height)
    guard s > 0 else { return nil }
    let scale = side / s
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format)
    let out = renderer.image { _ in
        let w = image.size.width * scale, h = image.size.height * scale
        image.draw(in: CGRect(x: (side - w) / 2, y: (side - h) / 2, width: w, height: h))
    }
    return out.jpegData(compressionQuality: 0.85)
}
