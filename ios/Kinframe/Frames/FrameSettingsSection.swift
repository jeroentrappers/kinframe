import KinframeKit
import SwiftUI

/// Slideshow, screen, sleep times and location & weather for a frame (web: FrameSettingsForm).
struct FrameSettingsSection: View {
    @Environment(AppModel.self) private var app
    let frame: FrameSummary
    @State private var model: FrameSettingsModel
    @State private var pickingPlace = false

    init(api: API, frame: FrameSummary) {
        self.frame = frame
        _model = State(initialValue: FrameSettingsModel(api: api, frameId: frame.id))
    }

    static let intervals = [5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600, 21600, 86400]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if !model.loaded {
                if let error = model.error {
                    ErrorRetryView(message: error) { Task { await model.load() } }
                } else {
                    LoadingView()
                }
            } else {
                if !frame.online { Callout(L("settingsForm.offlineNote")) }
                if model.conflict { Callout(L("settingsForm.conflict"), kind: .warn) }
                slideshow
                display
                sleep
                weather
            }
        }
        .safeAreaInset(edge: .bottom) {
            if model.dirty { saveBar }
        }
        .task { await model.load() }
        .sheet(isPresented: $pickingPlace) {
            LocationPicker(api: model.api) { place in
                model.setLocation(place)
            }
        }
    }

    private var slideshow: some View {
        group(L("settingsForm.slideshow")) {
            row {
                LabeledContent(L("settingsForm.interval")) {
                    Picker(L("settingsForm.interval"), selection: model.int("slideshow.interval_s", 30)) {
                    ForEach(Self.intervals, id: \.self) { Text(Format.interval($0)).tag($0) }
                    let current = model.int("slideshow.interval_s", 30).wrappedValue
                    if !Self.intervals.contains(current) { Text(Format.interval(current)).tag(current) }
                }
                    .labelsHidden()
                }
            }
            row {
                LabeledContent(L("settingsForm.order")) {
                    Picker(L("settingsForm.order"), selection: model.string("slideshow.order", "received")) {
                    Text(L("settingsForm.orderReceived")).tag("received")
                    Text(L("settingsForm.orderTaken")).tag("taken")
                    Text(L("settingsForm.orderShuffle")).tag("shuffle")
                }
                    .labelsHidden()
                }
            }
            row { Toggle(L("settingsForm.reverse"), isOn: model.bool("slideshow.reverse", false)).tint(Palette.accent) }
            row {
                LabeledContent(L("settingsForm.transition")) {
                    Picker(L("settingsForm.transition"), selection: model.string("slideshow.transition", "crossfade")) {
                    Text(L("settingsForm.crossfade")).tag("crossfade")
                    Text(L("settingsForm.slide")).tag("slide")
                    Text(L("settingsForm.kenburns")).tag("kenburns")
                }
                    .labelsHidden()
                }
            }
            row {
                Toggle(isOn: model.bool("slideshow.fill", true)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("settingsForm.fill"))
                        Text(L("settingsForm.fillHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                }
                .tint(Palette.accent)
            }
            row {
                LabeledContent(L("settingsForm.background")) {
                    Picker(L("settingsForm.background"), selection: model.string("slideshow.background", "blur")) {
                    Text(L("settingsForm.bgBlur")).tag("blur")
                    Text(L("settingsForm.bgBlack")).tag("black")
                    Text(L("settingsForm.bgPalette")).tag("palette")
                }
                    .labelsHidden()
                }
            }
            row { Toggle(L("settingsForm.showCaption"), isOn: model.bool("widgets.caption", true)).tint(Palette.accent) }
            row(last: true) { Toggle(L("settingsForm.showClock"), isOn: model.bool("widgets.clock", true)).tint(Palette.accent) }
        }
    }

    private var display: some View {
        group(L("settingsForm.display")) {
            row(last: true) {
                VStack(alignment: .leading, spacing: 6) {
                    let b = model.int("display.brightness", 204)
                    HStack {
                        Text(L("settingsForm.brightness"))
                        Spacer()
                        Text("\(Int((Double(b.wrappedValue) / 255 * 100).rounded())) %").monospacedDigit().foregroundStyle(Palette.textMuted)
                    }
                    HStack {
                        Image(systemName: "sun.min").foregroundStyle(Palette.textMuted)
                        Slider(value: Binding(get: { Double(b.wrappedValue) }, set: { b.wrappedValue = Int($0.rounded()) }), in: 15...255)
                            .tint(Palette.primary)
                            .accessibilityLabel(L("settingsForm.brightness"))
                        Image(systemName: "sun.max").foregroundStyle(Palette.textMuted)
                    }
                }
                .padding(.vertical, 8)
            }
        }
    }

    private var sleep: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("settingsForm.sleep"), trailing: AnyView(
                Button {
                    var s = model.schedules
                    s.append(SleepSchedule(id: UUID().uuidString.lowercased(), sleepAt: "23:00", wakeAt: "07:00", days: [.mon, .tue, .wed, .thu, .fri, .sat, .sun], enabled: true, mode: .off))
                    withAnimation { model.schedules = s }
                } label: {
                    Label(L("settingsForm.addSchedule"), systemImage: "plus")
                }
                .buttonStyle(.kf(.soft))
            ))
            Text(L("settingsForm.sleepHint")).font(.footnote).foregroundStyle(Palette.textMuted)
            if model.schedules.isEmpty {
                Text(L("settingsForm.noSchedules")).foregroundStyle(Palette.textMuted).card(padding: 16)
            }
            ForEach(Array(model.schedules.enumerated()), id: \.element.id) { n, schedule in
                ScheduleCard(schedule: schedule, number: n + 1) { updated in
                    var s = model.schedules
                    if let updated { s[n] = updated } else { s.remove(at: n) }
                    withAnimation { model.schedules = s }
                }
            }
        }
    }

    private var weather: some View {
        group(L("settingsForm.weatherTitle")) {
            row {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("settingsForm.weatherHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                    if let place = model.location {
                        HStack {
                            Label(place.name, systemImage: "mappin.and.ellipse").font(.body.weight(.semibold))
                            Spacer()
                            Button(L("location.change")) { pickingPlace = true }.buttonStyle(.kf(.ghost))
                            Button(L("location.remove")) { model.setLocation(nil) }.buttonStyle(.kf(.dangerGhost))
                        }
                    } else {
                        Button {
                            pickingPlace = true
                        } label: {
                            Label(L("location.search"), systemImage: "magnifyingglass")
                        }
                        .buttonStyle(.kf(.secondary))
                        .disabled(app.discovery?.weather == false)
                    }
                }
                .padding(.vertical, 8)
            }
            row {
                Toggle(isOn: model.bool("widgets.weather", false)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("settingsForm.showWeather"))
                        Text(model.location == nil ? L("settingsForm.needsLocation") : L("settingsForm.showWeatherHint"))
                            .font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                }
                .tint(Palette.accent)
                .disabled(model.location == nil)
            }
            row(last: true) {
                Picker(L("settingsForm.temperatureUnit"), selection: model.string("locale.temperature_unit", "c")) {
                    Text(L("settingsForm.celsius")).tag("c")
                    Text(L("settingsForm.fahrenheit")).tag("f")
                }
                .pickerStyle(.segmented)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            Text(L("settingsForm.weatherCredit")).font(.caption2).foregroundStyle(Palette.textFaint).offset(y: 18)
        }
        .padding(.bottom, 18)
    }

    private var saveBar: some View {
        HStack(spacing: 10) {
            Text(L("settingsForm.unsaved")).font(.subheadline.weight(.semibold))
            Spacer()
            Button(L("settingsForm.discard")) { withAnimation { model.discard() } }.buttonStyle(.kf(.ghost))
            Button {
                Task { await save() }
            } label: {
                if model.saving { ProgressView().tint(Palette.onPrimary) } else { Text(L("common.save")) }
            }
            .buttonStyle(.kf(.primary))
            .disabled(model.saving)
        }
        .padding(12)
        .background(.bar, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
    }

    private func save() async {
        do {
            switch try await model.save() {
            case .saved: app.toasts.show(L("settingsForm.saved", ["name": frame.name]))
            case .merged: app.toasts.show(L("settingsForm.mergedSaved"))
            case .review: break
            }
        } catch {
            app.toasts.error(error)
        }
    }

    private func group(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title)
            VStack(spacing: 0) { content() }.card(padding: 0)
        }
    }

    private func row(last: Bool = false, @ViewBuilder content: () -> some View) -> some View {
        VStack(spacing: 0) {
            content()
                .padding(.horizontal, 16)
                .frame(minHeight: 50)
            if !last { Divider().overlay(Palette.border).padding(.leading, 16) }
        }
    }
}

/// One sleep time: on/off, sleep and wake times, days and screen-off or dim.
private struct ScheduleCard: View {
    let schedule: SleepSchedule
    let number: Int
    let change: (SleepSchedule?) -> Void

    private static let days: [SleepSchedule.DaysPayloadPayload] = [.mon, .tue, .wed, .thu, .fri, .sat, .sun]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Toggle(L("settingsForm.scheduleN", ["n": number]), isOn: Binding(get: { schedule.enabled }, set: { v in var s = schedule; s.enabled = v; change(s) }))
                    .font(.headline)
                    .tint(Palette.accent)
                Button(role: .destructive) { change(nil) } label: { Image(systemName: "trash").frame(width: 44, height: 44) }
                    .foregroundStyle(Palette.danger)
                    .accessibilityLabel(L("settingsForm.removeSchedule"))
            }
            HStack {
                time(L("settingsForm.sleepAt"), schedule.sleepAt) { var s = schedule; s.sleepAt = $0; change(s) }
                Spacer()
                time(L("settingsForm.wakeAt"), schedule.wakeAt) { var s = schedule; s.wakeAt = $0; change(s) }
            }
            HStack(spacing: 6) {
                ForEach(Self.days, id: \.self) { d in
                    let on = schedule.days.contains(d)
                    Button {
                        var s = schedule
                        if on { s.days.removeAll { $0 == d } } else { s.days = Self.days.filter { s.days.contains($0) || $0 == d } }
                        change(s)
                    } label: {
                        Text(L("day.\(d.rawValue)"))
                            .font(.caption.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 36)
                            .foregroundStyle(on ? Palette.onPrimary : Palette.textMuted)
                            .background(Capsule().fill(on ? Palette.primary : Palette.surface2))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            Picker("", selection: Binding(get: { schedule.mode ?? .off }, set: { v in var s = schedule; s.mode = v; change(s) })) {
                Text(L("settingsForm.modeOff")).tag(SleepSchedule.ModePayload.off)
                Text(L("settingsForm.modeDim")).tag(SleepSchedule.ModePayload.dim)
            }
            .pickerStyle(.segmented)
        }
        .card(padding: 16)
        .opacity(schedule.enabled ? 1 : 0.7)
    }

    private func time(_ label: String, _ value: String, set: @escaping (String) -> Void) -> some View {
        let parts = value.split(separator: ":").compactMap { Int($0) }
        let date = Calendar.current.date(from: DateComponents(hour: parts.first ?? 0, minute: parts.count > 1 ? parts[1] : 0)) ?? Date()
        return DatePicker(label, selection: Binding(get: { date }, set: { d in
            let c = Calendar.current.dateComponents([.hour, .minute], from: d)
            set(String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0))
        }), displayedComponents: .hourAndMinute)
        .font(.subheadline)
        .fixedSize()
    }
}

/// Place search through the server (`GET /v1/geocode`, Open-Meteo).
struct LocationPicker: View {
    @Environment(\.dismiss) private var dismiss
    let api: API
    let pick: (Place) -> Void
    @State private var query = ""
    @State private var results: [Place] = []
    @State private var searching = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if let error {
                    Text(error).foregroundStyle(Palette.danger)
                } else if !searching, results.isEmpty, query.count >= 2 {
                    Text(L("location.noResults", ["q": query])).foregroundStyle(Palette.textMuted)
                }
                ForEach(Array(results.enumerated()), id: \.offset) { _, place in
                    Button {
                        pick(place)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(place.name).foregroundStyle(Palette.text)
                            Text([place.admin1 ?? nil, place.country ?? nil].compactMap { $0 }.joined(separator: ", "))
                                .font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                    }
                }
                Section {
                    Text(L("settingsForm.weatherCredit")).font(.caption).foregroundStyle(Palette.textFaint)
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("location.placeholder"))
            .overlay { if searching { ProgressView() } }
            .navigationTitle(L("location.search"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } } }
            .task(id: query) { await search() }
        }
    }

    private func search() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { results = []; return }
        try? await Task.sleep(for: .milliseconds(350))
        guard !Task.isCancelled else { return }
        searching = true
        defer { searching = false }
        do {
            let lang = L10n.code
            results = try await api.call { try await $0.geocode(query: .init(q: String(q.prefix(100)), lang: lang)).ok.body.json }
            error = nil
        } catch let e as APIError {
            error = switch e.code {
            case "rate_limited": L("location.errorBusy")
            case "upstream_unavailable": L("location.errorUpstream")
            case "feature_disabled": L("location.errorDisabled")
            default: e.status == 503 ? L("location.errorDisabled") : e.message
            }
        } catch {}
    }
}
