import KinframeKit
import SwiftUI

/// How the frame shows its calendar: the full-screen view, fading, week numbers, the sidebar and countdown widgets, the
/// sync window and timed views (web: CalendarSettings.svelte). Saved as a merge patch like the other frame settings.
struct CalendarSettingsForm: View {
    @Environment(AppModel.self) private var app
    @Bindable var model: FrameSettingsModel
    let frameName: String
    var onSaved: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("cal.settingsTitle"))
            if model.conflict { Callout(L("settingsForm.conflict"), kind: .warn) }
            VStack(spacing: 0) {
                row {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L("cal.setView"))
                        Picker(L("cal.setView"), selection: model.string("calendar.view", "week")) {
                            Text(L("cal.setViewFour")).tag("four_day")
                            Text(L("cal.setViewWeek")).tag("week")
                            Text(L("cal.setViewMonth")).tag("month")
                        }
                        .pickerStyle(.segmented)
                    }
                    .padding(.vertical, 10)
                }
                row { toggle("calendar.start_on_today", true, L("cal.setStartToday"), hint: L("cal.setStartTodayHint")) }
                row { toggle("calendar.fade_past", true, L("cal.setFadePast")) }
                row { toggle("calendar.week_numbers", false, L("cal.setWeekNumbers")) }
                row { toggle("widgets.calendar_sidebar", false, L("cal.setSidebar"), hint: L("cal.setSidebarHint")) }
                row { toggle("widgets.countdowns", true, L("cal.setCountdowns"), hint: L("cal.setCountdownsHint")) }
                row(last: true) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L("cal.setWindow"))
                        Stepper(value: model.int("calendar.past_days", 30), in: 1...365) {
                            LabeledContent(L("cal.setPast"), value: "\(model.int("calendar.past_days", 30).wrappedValue)").font(.subheadline)
                        }
                        Stepper(value: model.int("calendar.future_days", 30), in: 7...365) {
                            LabeledContent(L("cal.setFuture"), value: "\(model.int("calendar.future_days", 30).wrappedValue)").font(.subheadline)
                        }
                    }
                    .padding(.vertical, 10)
                }
            }
            .card(padding: 0)
            timed
            if model.dirty { saveBar }
        }
    }

    // MARK: Timed views

    private var timedViews: [CalendarSchedule] {
        get { (try? model.value("calendar.timed_views")?.decode([CalendarSchedule].self)) ?? [] }
        nonmutating set { model.set("calendar.timed_views", try? JSONValue.from(newValue)) }
    }

    private var timed: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(L("cal.timedTitle")).font(.headline)
                Spacer()
                Button {
                    withAnimation { timedViews = timedViews + [CalendarLogic.newTimedView()] }
                } label: { Label(L("cal.timedAdd"), systemImage: "plus") }
                .buttonStyle(.kf(.soft))
            }
            .padding(.top, 6)
            Text(L("cal.timedHint")).font(.footnote).foregroundStyle(Palette.textMuted)
            if timedViews.isEmpty {
                Text(L("cal.timedNone")).font(.subheadline).foregroundStyle(Palette.textMuted).frame(maxWidth: .infinity, alignment: .leading).card(padding: 16)
            }
            ForEach(Array(timedViews.enumerated()), id: \.element.id) { n, s in
                TimedViewCard(schedule: s, number: n + 1) { updated in
                    var all = timedViews
                    if let updated { all[n] = updated } else { all.remove(at: n) }
                    withAnimation { timedViews = all }
                }
            }
        }
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
        .background(Palette.surface2, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
    }

    private func save() async {
        do {
            switch try await model.save() {
            case .saved, .merged:
                app.toasts.show(L("cal.settingsSaved"))
                onSaved()
            case .review: break
            }
        } catch {
            app.toasts.error(error)
        }
    }

    private func toggle(_ path: String, _ fallback: Bool, _ title: String, hint: String? = nil) -> some View {
        Toggle(isOn: model.bool(path, fallback)) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let hint { Text(hint).font(.footnote).foregroundStyle(Palette.textMuted) }
            }
        }
        .tint(Palette.accent)
        .padding(.vertical, hint == nil ? 0 : 8)
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

/// One timed view: on/off, from and until, and the days.
private struct TimedViewCard: View {
    let schedule: CalendarSchedule
    let number: Int
    let change: (CalendarSchedule?) -> Void

    private static let days: [CalendarSchedule.DaysPayloadPayload] = [.mon, .tue, .wed, .thu, .fri, .sat, .sun]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Toggle(L("cal.timedN", ["n": number]), isOn: Binding(get: { schedule.enabled }, set: { v in var s = schedule; s.enabled = v; change(s) }))
                    .font(.headline)
                    .tint(Palette.accent)
                Button(role: .destructive) { change(nil) } label: { Image(systemName: "trash").frame(width: 44, height: 44) }
                    .foregroundStyle(Palette.danger)
                    .accessibilityLabel(L("cal.timedRemove"))
            }
            HStack {
                time(L("cal.timedFrom"), schedule.from) { var s = schedule; s.from = $0; change(s) }
                Spacer()
                time(L("cal.timedTo"), schedule.to) { var s = schedule; s.to = $0; change(s) }
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
