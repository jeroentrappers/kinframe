import KinframeKit
import SwiftUI

/// What the frame's calendar shows: countdowns, an agenda or a month, with the weather; tapping an event shows it
/// and lets managers count down to it (web: CalendarPreview.svelte).
struct CalendarPreview: View {
    @Environment(AppModel.self) private var app
    let model: FrameCalendarModel
    let view: CalendarView
    var firstDay: CalendarLogic.FirstDay = .mon
    var weekNumbers = false
    var fadePast = true
    var unit = "c"
    var clock24 = true

    enum Mode: Hashable { case agenda, month }
    @State private var mode: Mode = .agenda
    @State private var cursor: (y: Int, m: Int)?
    @State private var detail: CalendarEvent?

    private var cal: Components.Schemas.Calendar { view.value1 }
    private var tz: String { cal.timezone.isEmpty ? "UTC" : cal.timezone }
    private var today: String { CalendarLogic.today(tz) }
    private var ym: (y: Int, m: Int) { cursor ?? (Int(today.prefix(4)) ?? 2026, Int(today.dropFirst(5).prefix(2)) ?? 1) }
    private var byDay: [String: [CalendarEvent]] {
        CalendarLogic.byDay(cal.events, tz, from: CalendarLogic.addDays(cal.window.from, -7), to: CalendarLogic.addDays(cal.window.to, 7))
    }
    private var weather: [String: WeatherDay] {
        Dictionary((view.value2.weather?.daily ?? []).map { ($0.date, $0) }, uniquingKeysWith: { a, _ in a })
    }
    private var colors: [String: CalendarColor] { Dictionary(cal.sources.map { ($0.id, $0.color) }, uniquingKeysWith: { a, _ in a }) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker(L("cal.previewTitle"), selection: $mode) {
                Text(L("cal.viewAgenda")).tag(Mode.agenda)
                Text(L("cal.viewMonth")).tag(Mode.month)
            }
            .pickerStyle(.segmented)
            if !cal.countdowns.isEmpty { countdowns }
            switch mode {
            case .agenda: agenda
            case .month: month
            }
            HStack(spacing: 12) {
                Label(L("cal.timesIn", ["tz": tz]), systemImage: "clock")
                Spacer()
                if view.value2.weather != nil {
                    Link(destination: URL(string: "https://open-meteo.com/")!) { Label(L("cal.weatherCredit"), systemImage: "cloud") }
                }
            }
            .font(.caption2)
            .foregroundStyle(Palette.textFaint)
            .labelStyle(TightLabelStyle())
        }
        #if DEBUG
        .task {
            if DebugLaunch.takeSheet("calendarMonth") { mode = .month }
            if DebugLaunch.takeSheet("calendarDetail") { detail = cal.events.first { $0.senderName != nil } ?? cal.events.first }
        }
        #endif
        .sheet(item: $detail) { ev in
            EventDetailSheet(model: model, event: ev, color: colors[ev.sourceId], tz: tz, clock24: clock24)
                .presentationDetents([.medium, .large])
        }
    }

    // MARK: Countdowns

    private var countdowns: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(cal.countdowns, id: \.eventId) { c in
                    let n = CalendarLogic.daysUntil(allDay: c.allDay, startAt: c.startAt, startDate: c.startDate, tz)
                    HStack(spacing: 6) {
                        Text(n <= 0 ? L("cal.isToday") : Lp("cal.inDays", count: n)).font(.caption.weight(.bold).monospacedDigit())
                        Text(c.title).font(.caption).lineLimit(1)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .foregroundStyle(Palette.text)
                    .background(Capsule().fill(CalendarFormat.color(c.color).opacity(0.22)))
                    .overlay(Capsule().strokeBorder(CalendarFormat.color(c.color)))
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .accessibilityLabel(L("cal.countdowns"))
    }

    // MARK: Agenda

    private var agenda: some View {
        let days = CalendarLogic.agenda(byDay, today: today, until: cal.window.to, tz: tz)
        return VStack(alignment: .leading, spacing: 14) {
            ForEach(days, id: \.day) { d in
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .center) {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(d.day == today ? L("cal.today") : CalendarFormat.day(d.day, "EEEE").capitalized(with: L10n.locale))
                                .font(.subheadline.weight(.bold))
                                .foregroundStyle(d.day == today ? Palette.primary : Palette.text)
                            Text(CalendarFormat.day(d.day, "dMMMM")).font(.caption).foregroundStyle(Palette.textMuted)
                        }
                        Spacer()
                        if let w = weather[d.day] { WeatherBadge(day: w, unit: unit) }
                    }
                    .accessibilityElement(children: .combine)
                    if d.events.isEmpty {
                        Text(L("cal.previewEmpty")).font(.caption).foregroundStyle(Palette.textMuted)
                    } else {
                        ForEach(d.events, id: \.id) { ev in eventRow(ev, day: d.day) }
                    }
                }
            }
        }
    }

    private func eventRow(_ ev: CalendarEvent, day: String) -> some View {
        Button { detail = ev } label: {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 2).fill(CalendarFormat.color(colors[ev.sourceId])).frame(width: 4)
                Text(timeLabel(ev, day: day, agendaRow: true))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Palette.textMuted)
                    .frame(width: 92, alignment: .leading)
                VStack(alignment: .leading, spacing: 1) {
                    Text(ev.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(2)
                    if let loc = ev.location, !loc.isEmpty {
                        Label(loc, systemImage: "mappin").labelStyle(TightLabelStyle()).font(.caption2).foregroundStyle(Palette.textMuted).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                if ev.senderName != nil { Image(systemName: "heart.fill").font(.caption).foregroundStyle(Palette.primary) }
                if ev.countdown == true { Image(systemName: "star.fill").font(.caption).foregroundStyle(Palette.warn) }
            }
            .padding(.vertical, 6)
            .padding(.trailing, 8)
            .frame(minHeight: 44)
            .background(CalendarFormat.color(colors[ev.sourceId]).opacity(ev.allDay ? 0.18 : 0.08), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .opacity(fadePast && CalendarLogic.isPast(ev, tz) ? 0.5 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityHint(ev.senderName.map { L("cal.sentBy", ["name": $0]) } ?? "")
    }

    private func timeLabel(_ ev: CalendarEvent, day: String, agendaRow: Bool = false) -> String {
        let (s, e) = CalendarLogic.span(ev, tz)
        if agendaRow, s != e { return "\(CalendarFormat.day(s, "dMMM")) – \(CalendarFormat.day(e, "dMMM"))" }
        if ev.allDay { return L("cal.allDay") }
        if s != e, day != s {
            if day == e, let end = ev.endAt { return "→ " + CalendarLogic.localTime(end, tz, locale: L10n.locale, clock24: clock24) }
            return L("cal.allDay")
        }
        guard let start = ev.startAt else { return "" }
        let st = CalendarLogic.localTime(start, tz, locale: L10n.locale, clock24: clock24)
        if let end = ev.endAt, s == e { return "\(st) – \(CalendarLogic.localTime(end, tz, locale: L10n.locale, clock24: clock24))" }
        return st
    }

    // MARK: Month

    private var month: some View {
        let weeks = CalendarLogic.monthGrid(year: ym.y, month: ym.m, first: firstDay)
        let start = String(format: "%04d-%02d-01", ym.y, ym.m)
        let next = ym.m == 12 ? String(format: "%04d-01-01", ym.y + 1) : String(format: "%04d-%02d-01", ym.y, ym.m + 1)
        return VStack(spacing: 6) {
            HStack {
                Button { step(-1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                    .disabled(start <= cal.window.from)
                    .accessibilityLabel(L("cal.prev"))
                Text(CalendarFormat.day(start, "MMMMyyyy").capitalized(with: L10n.locale))
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .accessibilityAddTraits(.updatesFrequently)
                Button { step(1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
                    .disabled(next > cal.window.to)
                    .accessibilityLabel(L("cal.next"))
                Button(L("cal.today")) { cursor = nil }.buttonStyle(.kf(.ghost))
            }
            HStack(spacing: 2) {
                ForEach(CalendarLogic.weekdayOrder(firstDay), id: \.self) { k in
                    Text(L("day.\(k)")).font(.caption2.weight(.semibold)).foregroundStyle(Palette.textMuted).frame(maxWidth: .infinity)
                }
            }
            ForEach(weeks, id: \.first) { week in
                HStack(spacing: 2) {
                    ForEach(Array(week.enumerated()), id: \.element) { i, d in
                        dayCell(d, showWeek: weekNumbers && i == 0)
                    }
                }
            }
        }
    }

    private func dayCell(_ d: String, showWeek: Bool) -> some View {
        let events = byDay[d] ?? []
        let outside = Int(d.dropFirst(5).prefix(2)) != ym.m
        return VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 2) {
                Text("\(Int(d.suffix(2)) ?? 0)")
                    .font(.caption2.weight(d == today ? .bold : .regular).monospacedDigit())
                    .foregroundStyle(d == today ? Palette.onPrimary : Palette.text)
                    .padding(.horizontal, 4)
                    .background(Capsule().fill(d == today ? Palette.primary : .clear))
                if showWeek { Text(L("cal.weekShort", ["n": CalendarLogic.isoWeek(d)])).font(.system(size: 8)).foregroundStyle(Palette.textFaint) }
                Spacer(minLength: 0)
                if let w = weather[d] { Image(systemName: CalendarLogic.weatherKind(w.code).symbol).symbolRenderingMode(.multicolor).font(.system(size: 9)) }
            }
            ForEach(events.prefix(3), id: \.id) { ev in
                Button { detail = ev } label: {
                    Text(ev.title)
                        .font(.system(size: 9, weight: .medium))
                        .lineLimit(1)
                        .padding(.horizontal, 2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .foregroundStyle(Palette.text)
                        .background(CalendarFormat.color(colors[ev.sourceId]).opacity(ev.allDay || CalendarLogic.isMultiDay(ev, tz) ? 0.35 : 0.15),
                                    in: RoundedRectangle(cornerRadius: 3))
                }
                .buttonStyle(.plain)
            }
            if events.count > 3 { Text(L("cal.more", ["count": events.count - 3])).font(.system(size: 8)).foregroundStyle(Palette.textMuted) }
            Spacer(minLength: 0)
        }
        .padding(3)
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
        .background(Palette.surface2.opacity(outside ? 0.3 : 0.7), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
        .opacity(outside ? 0.5 : (fadePast && d < today ? 0.7 : 1))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(CalendarFormat.day(d, "EEEEdMMMM"))
    }

    private func step(_ dir: Int) {
        var (y, m) = ym
        m += dir
        if m == 0 { (y, m) = (y - 1, 12) }
        if m == 13 { (y, m) = (y + 1, 1) }
        cursor = (y, m)
    }
}

/// The day's weather: icon, high and low, chance of rain.
struct WeatherBadge: View {
    let day: WeatherDay
    let unit: String

    var body: some View {
        let kind = CalendarLogic.weatherKind(day.code)
        HStack(spacing: 6) {
            Image(systemName: kind.symbol).symbolRenderingMode(.multicolor).font(.title3)
            VStack(alignment: .trailing, spacing: 0) {
                Text(CalendarLogic.temperature(day.maxC, unit: unit)).font(.caption.weight(.bold))
                Text(CalendarLogic.temperature(day.minC, unit: unit)).font(.caption2).foregroundStyle(Palette.textMuted)
            }
            .monospacedDigit()
            if let p = day.precipProb, p >= 10 {
                Text(L("cal.rain", ["pct": p])).font(.caption2.monospacedDigit()).foregroundStyle(Palette.textMuted)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("cal.weatherDay", ["kind": L("cal.wx.\(kind.rawValue)"),
                                                 "min": CalendarLogic.temperature(day.minC, unit: unit),
                                                 "max": CalendarLogic.temperature(day.maxC, unit: unit)]))
    }
}

/// One event: calendar, when, where, who sent it, notes, and the countdown switch for managers.
struct EventDetailSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let model: FrameCalendarModel
    let event: CalendarEvent
    let color: CalendarColor?
    let tz: String
    let clock24: Bool
    @State private var countdown: Bool
    @State private var busy = false

    init(model: FrameCalendarModel, event: CalendarEvent, color: CalendarColor?, tz: String, clock24: Bool) {
        self.model = model
        self.event = event
        self.color = color
        self.tz = tz
        self.clock24 = clock24
        _countdown = State(initialValue: event.countdown ?? false)
    }

    var body: some View {
        NavigationStack {
            List {
                Group {
                    Section {
                        Label { Text(model.label(sourceId: event.sourceId)) } icon: { Circle().fill(CalendarFormat.color(color)).frame(width: 12, height: 12) }
                        Label(when, systemImage: "clock")
                        if event.recurring == true { Label(L("cal.recurring"), systemImage: "arrow.clockwise").foregroundStyle(Palette.textMuted) }
                        if let loc = event.location, !loc.isEmpty { Label(loc, systemImage: "mappin") }
                        if let who = event.senderName { Label(L("cal.sentBy", ["name": who]), systemImage: "heart") }
                    }
                    if let notes = event.notes, !notes.isEmpty {
                        Section { Text(notes).font(.subheadline) }
                    }
                    if model.manage {
                        Section {
                            Toggle(L("cal.countdownToggle"), isOn: Binding(get: { countdown }, set: { on in Task { await set(on) } }))
                                .tint(Palette.accent)
                                .disabled(busy)
                        }
                    }
                }
                .listRowBackground(Palette.surface)
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(event.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
        }
    }

    private var when: String {
        let (s, e) = CalendarLogic.span(event, tz)
        let first = CalendarFormat.day(s, "EEEEdMMMM")
        if event.allDay { return s != e ? "\(first) – \(CalendarFormat.day(e, "EEEEdMMMM"))" : first }
        guard let start = event.startAt else { return first }
        let st = CalendarLogic.localTime(start, tz, locale: L10n.locale, clock24: clock24)
        if let end = event.endAt {
            let et = CalendarLogic.localTime(end, tz, locale: L10n.locale, clock24: clock24)
            return s == e ? "\(first), \(st) – \(et)" : "\(first), \(st) – \(CalendarFormat.day(e, "EEEEdMMMM")), \(et)"
        }
        return "\(first), \(st)"
    }

    private func set(_ on: Bool) async {
        busy = true
        defer { busy = false }
        do {
            try await model.setCountdown(event, on)
            countdown = on
            app.toasts.show(on ? L("cal.countdownOn") : L("cal.countdownOff"))
        } catch {
            app.toasts.error(error)
        }
    }
}
