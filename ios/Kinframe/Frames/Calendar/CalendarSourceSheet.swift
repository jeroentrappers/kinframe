import KinframeKit
import SwiftUI

/// Add a calendar (an ICS link, or a CalDAV account with one or more of its calendars) or edit one
/// (web: CalendarSourceDialog.svelte).
struct CalendarSourceSheet: View {
    @Environment(\.dismiss) private var dismiss
    let api: API
    let frameId: String
    let source: CalendarSource?
    let usedColors: [CalendarColor]
    let onSaved: ([CalendarSource]) -> Void

    enum Kind: String { case ics, caldav }
    @State private var kind: Kind = .ics
    @State private var url = ""
    @State private var username = ""
    @State private var password = ""
    @State private var name = ""
    @State private var color: CalendarColor = .coral
    @State private var enabled = true
    @State private var testing = false
    @State private var result: CalendarTestResult?
    @State private var picked: [String] = []
    @State private var saving = false
    @State private var error: String?
    @State private var urlTouched = false

    init(api: API, frameId: String, source: CalendarSource?, usedColors: [CalendarColor], onSaved: @escaping ([CalendarSource]) -> Void) {
        self.api = api
        self.frameId = frameId
        self.source = source
        self.usedColors = usedColors
        self.onSaved = onSaved
        _kind = State(initialValue: source?.kind == .caldav ? .caldav : .ics)
        _username = State(initialValue: source?.username ?? "")
        _name = State(initialValue: source?.name ?? "")
        _color = State(initialValue: source?.color ?? CalendarLogic.nextColor(usedColors))
        _enabled = State(initialValue: source?.enabled ?? true)
    }

    private var editing: Bool { source != nil }
    private var isLocal: Bool { source?.kind == .local }
    private var urlOk: Bool { CalendarLogic.isCalendarURL(url) }
    private var urlError: Bool { urlTouched && !url.trimmingCharacters(in: .whitespaces).isEmpty && !urlOk }
    private var calendars: [CalendarTestResult.CalendarsPayloadPayload] { result?.ok == true ? (result?.calendars ?? []) : [] }
    private var multi: Bool { kind == .caldav && !calendars.isEmpty }
    private var canTest: Bool { urlOk && (kind == .ics || !username.trimmingCharacters(in: .whitespaces).isEmpty) && !testing }
    private var canSave: Bool {
        if saving { return false }
        if editing { return url.trimmingCharacters(in: .whitespaces).isEmpty || urlOk }
        if !urlOk { return false }
        if kind == .caldav { return !username.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty && (!multi || !picked.isEmpty) }
        return true
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    if !editing {
                        Section(L("cal.type")) {
                            kindRow(.ics, icon: "link", title: L("cal.typeIcs"), hint: L("cal.typeIcsHint"))
                            kindRow(.caldav, icon: "server.rack", title: L("cal.typeCaldav"), hint: L("cal.typeCaldavHint"))
                        }
                    }
                    if !isLocal { connection }
                    if multi { pickSection }
                    if !multi || picked.count == 1 { appearance }
                    if editing {
                        Section { Toggle(L("cal.show"), isOn: $enabled).tint(Palette.accent) }
                    }
                    if let error { Section { Text(error).foregroundStyle(Palette.danger) } }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(editing ? L("cal.editTitle") : L("cal.addTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(editing ? L("common.save") : L("cal.save")) { Task { await save() } }.disabled(!canSave)
                    }
                }
            }
            .onChange(of: url) { result = nil; picked = [] }
            .onChange(of: username) { result = nil; picked = [] }
            .onChange(of: password) { result = nil; picked = [] }
            .onChange(of: kind) { result = nil; picked = [] }
        }
    }

    private func kindRow(_ k: Kind, icon: String, title: String, hint: String) -> some View {
        Button { kind = k } label: {
            HStack(spacing: 12) {
                Image(systemName: icon).frame(width: 24).foregroundStyle(Palette.primary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                    Text(hint).font(.footnote).foregroundStyle(Palette.textMuted)
                }
                Spacer()
                Image(systemName: kind == k ? "checkmark.circle.fill" : "circle").foregroundStyle(kind == k ? Palette.primary : Palette.borderStrong)
            }
        }
        .accessibilityAddTraits(kind == k ? .isSelected : [])
    }

    @ViewBuilder private var connection: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(kind == .caldav ? L("cal.serverUrl") : L("cal.url")).font(.subheadline.weight(.semibold))
                TextField(editing && source?.urlHint != nil ? L("cal.urlKeep", ["hint": source?.urlHint ?? ""])
                          : kind == .caldav ? L("cal.serverUrlPlaceholder") : L("cal.urlPlaceholder"), text: $url)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit { urlTouched = true }
                if urlError {
                    Text(L("cal.urlInvalid")).font(.footnote).foregroundStyle(Palette.danger)
                } else if kind == .ics && !editing {
                    DisclosureGroup(L("cal.typeIcsHint")) { Text(L("cal.urlHelp")).font(.footnote) }
                        .font(.footnote).foregroundStyle(Palette.textMuted)
                }
            }
            if kind == .caldav {
                TextField(L("cal.username"), text: $username)
                    .textContentType(.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField(editing && source?.hasPassword == true ? L("cal.passwordKeep") : L("cal.password"), text: $password)
                    .textContentType(.password)
                Text(L("cal.passwordHelp")).font(.footnote).foregroundStyle(Palette.textMuted)
            }
            if !editing {
                Button {
                    Task { await test() }
                } label: {
                    HStack {
                        if testing { ProgressView(); Text(L("cal.testing")) } else { Label(L("cal.test"), systemImage: "arrow.clockwise") }
                    }
                }
                .disabled(!canTest)
                if let result {
                    Label(testMessage(result), systemImage: result.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .font(.subheadline)
                        .foregroundStyle(result.ok ? Palette.accentText : Palette.danger)
                }
            }
        }
    }

    private var pickSection: some View {
        Section {
            ForEach(calendars, id: \.url) { c in
                let on = picked.contains(c.url)
                Button {
                    picked = on ? picked.filter { $0 != c.url } : picked + [c.url]
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: on ? "checkmark.square.fill" : "square").foregroundStyle(on ? Palette.primary : Palette.borderStrong)
                        Circle().fill(c.color.flatMap(Color.init(hex:)) ?? Palette.textFaint).frame(width: 10, height: 10)
                        Text(c.name).foregroundStyle(Palette.text)
                    }
                }
                .accessibilityAddTraits(on ? .isSelected : [])
            }
            if picked.isEmpty { Text(L("cal.pickNone")).font(.footnote).foregroundStyle(Palette.danger) }
        } header: {
            Text(L("cal.pickCalendars"))
        } footer: {
            Text(L("cal.pickHint"))
        }
    }

    private var appearance: some View {
        Section {
            TextField(isLocal ? L("cal.received") : L("cal.namePlaceholder"), text: $name)
            VStack(alignment: .leading, spacing: 8) {
                Text(L("cal.color")).font(.subheadline.weight(.semibold))
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 6), spacing: 8) {
                    ForEach(CalendarLogic.colors, id: \.self) { c in
                        Button { color = c } label: {
                            Circle().fill(CalendarFormat.color(c))
                                .frame(width: 34, height: 34)
                                .overlay(Circle().strokeBorder(Palette.text, lineWidth: color == c ? 3 : 0))
                                .overlay { if color == c { Image(systemName: "checkmark").font(.caption.weight(.bold)).foregroundStyle(.white) } }
                                .frame(width: 44, height: 44)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(L("cal.colorName", ["name": CalendarFormat.colorName(c)]))
                        .accessibilityAddTraits(color == c ? .isSelected : [])
                    }
                }
            }
            .padding(.vertical, 4)
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 2).fill(CalendarFormat.color(color)).frame(width: 4, height: 22)
                Text(name.trimmingCharacters(in: .whitespaces).isEmpty ? (isLocal ? L("cal.received") : L("cal.namePlaceholder")) : name)
                    .font(.subheadline.weight(.semibold))
            }
            .accessibilityHidden(true)
        } header: {
            Text(L("cal.name"))
        }
    }

    private func testMessage(_ r: CalendarTestResult) -> String {
        if !r.ok { return r.status == .authFailed ? L("cal.testAuth") : L("cal.testFailed", ["error": r.error ?? ""]) }
        let events = r.eventCount.map { Lp("cal.testUpcoming", count: $0) } ?? ""
        if let n = r.calendarName { return L("cal.testOkNamed", ["name": n, "events": events]) }
        return L("cal.testOk", ["events": events])
    }

    private func body(calendarURL: String? = nil, name: String? = nil, color: CalendarColor? = nil, enabled: Bool? = nil) -> Components.Schemas.CalendarSourceCreate {
        var b = Components.Schemas.CalendarSourceCreate(kind: kind == .caldav ? .caldav : .ics, url: url.trimmingCharacters(in: .whitespaces))
        if kind == .caldav {
            b.username = username.trimmingCharacters(in: .whitespaces)
            b.password = password
        }
        b.calendarUrl = calendarURL
        b.name = name
        b.color = color
        b.enabled = enabled
        return b
    }

    private func test() async {
        urlTouched = true
        guard canTest else { return }
        testing = true
        error = nil
        defer { testing = false }
        do {
            let (fid, b) = (frameId, body())
            let r = try await api.call { try await $0.testCalendar(path: .init(frameId: fid), body: .json(b)).ok.body.json }
            result = r
            if r.ok, let first = r.calendars?.first { picked = [first.url] }
            if r.ok, name.trimmingCharacters(in: .whitespaces).isEmpty, let n = r.calendarName { name = n }
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func save() async {
        urlTouched = true
        guard canSave else { return }
        saving = true
        error = nil
        defer { saving = false }
        let fid = frameId
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        do {
            if let source {
                var patch = Components.Schemas.CalendarSourceUpdate(name: trimmedName, color: color, enabled: enabled)
                if !isLocal {
                    let u = url.trimmingCharacters(in: .whitespaces)
                    if !u.isEmpty { patch.url = u }
                    if source.kind == .caldav {
                        let user = username.trimmingCharacters(in: .whitespaces)
                        if !user.isEmpty, user != source.username { patch.username = user }
                        if !password.isEmpty { patch.password = password }
                    }
                }
                let (cid, body) = (source.id, patch)
                let saved = try await api.call { try await $0.updateCalendar(path: .init(frameId: fid, calendarId: cid), body: .json(body)).ok.body.json }
                onSaved([saved])
            } else if multi {
                var used: [CalendarColor?] = usedColors
                var out: [CalendarSource] = []
                for u in picked {
                    guard let cal = calendars.first(where: { $0.url == u }) else { continue }
                    // One picked calendar keeps the colour chosen above; several get their own.
                    let c = picked.count == 1 ? color : CalendarLogic.nextColor(used)
                    used.append(c)
                    let b = body(calendarURL: u, name: picked.count == 1 && !trimmedName.isEmpty ? trimmedName : cal.name, color: c, enabled: true)
                    out.append(try await api.call {
                        try await $0.createCalendar(path: .init(frameId: fid), headers: .init(idempotencyKey: UUID().uuidString), body: .json(b)).created.body.json
                    })
                }
                onSaved(out)
            } else {
                let b = body(name: trimmedName.isEmpty ? nil : trimmedName, color: color, enabled: enabled)
                let saved = try await api.call {
                    try await $0.createCalendar(path: .init(frameId: fid), headers: .init(idempotencyKey: UUID().uuidString), body: .json(b)).created.body.json
                }
                onSaved([saved])
            }
            dismiss()
        } catch {
            self.error = errorMessage(error)
        }
    }
}

extension Color {
    /// "#4F9FDF" (or without the #); nil for anything else.
    init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}
