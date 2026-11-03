import KinframeKit
import SwiftUI

/// Calendar section of a frame page (web: FrameCalendar.svelte).
struct FrameCalendarSection: View {
    @Environment(AppModel.self) private var app
    @State private var model: FrameCalendarModel
    @State private var settings: FrameSettingsModel
    @State private var editing: SourceSheet?
    @State private var removing: CalendarSource?
    @State private var sendingEvent = false

    struct SourceSheet: Identifiable {
        var source: CalendarSource?
        var id: String { source?.id ?? "new" }
    }

    init(api: API, frame: FrameSummary) {
        _model = State(initialValue: FrameCalendarModel(api: api, frame: frame))
        _settings = State(initialValue: FrameSettingsModel(api: api, frameId: frame.id))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let error = model.error {
                ErrorRetryView(message: error) { Task { await model.load() } }
            } else {
                if model.manage { preview }
                if model.manage { sources.id("calendars") }
                if model.mayPut || model.manage { sentEvents.id("sentEvents") }
                if model.manage, settings.loaded {
                    CalendarSettingsForm(model: settings, frameName: model.frame.name) { Task { try? await model.loadView() } }
                        .id("calendarSettings")
                }
            }
        }
        .task {
            await model.load()
            if model.manage { await settings.load() }
            #if DEBUG
            if DebugLaunch.takeSheet("calendarAdd") { editing = SourceSheet(source: nil) }
            if DebugLaunch.takeSheet("calendarEvent") { sendingEvent = true }
            #endif
        }
        .onDisappear { model.stop() }
        .onLiveEvent(app.events) { _ = model.apply($0) }
        .sheet(item: $editing) { sheet in
            CalendarSourceSheet(api: model.api, frameId: model.frame.id, source: sheet.source, usedColors: model.usedColors) { saved in
                model.saved(saved)
                app.toasts.show(sheet.source == nil ? Lp("cal.added", count: saved.count) : L("cal.saved"))
            }
        }
        .sheet(isPresented: $sendingEvent) {
            SendEventSheet(api: model.api, frame: model.frame, senderName: app.me?.displayName ?? "") { inv in
                model.sent(inv)
                app.toasts.show(L("cal.evSent", ["frame": model.frame.name]))
            }
        }
        .alert(removing.map { L("cal.removeTitle", ["name": model.label($0)]) } ?? "", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), presenting: removing) { s in
            Button(L("cal.remove"), role: .destructive) {
                Task {
                    do {
                        try await model.remove(s)
                        app.toasts.show(L("cal.removed", ["name": model.label(s)]))
                    } catch { app.toasts.error(error) }
                }
            }
            Button(L("common.cancel"), role: .cancel) {}
        } message: { s in
            Text(s.kind == .local ? L("cal.removeReceivedBody") : L("cal.removeBody"))
        }
    }

    // MARK: Preview

    private var preview: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("cal.previewTitle"))
            if let view = model.view {
                CalendarPreview(model: model, view: view,
                                firstDay: CalendarLogic.FirstDay(rawValue: settings.string("locale.first_day_of_week", "mon").wrappedValue) ?? .mon,
                                weekNumbers: settings.bool("calendar.week_numbers", false).wrappedValue,
                                fadePast: settings.bool("calendar.fade_past", true).wrappedValue,
                                unit: settings.string("locale.temperature_unit", "c").wrappedValue,
                                clock24: settings.bool("widgets.clock_24h", true).wrappedValue)
                    .card(padding: 14)
            } else {
                LoadingView().frame(height: 200).card(padding: 0)
            }
        }
    }

    // MARK: Calendars

    private var sources: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("cal.sourcesTitle"), trailing: AnyView(
                Button { editing = SourceSheet(source: nil) } label: { Label(L("cal.add"), systemImage: "plus") }
                    .buttonStyle(.kf(.soft))
            ))
            Text(L("cal.sourcesHint")).font(.footnote).foregroundStyle(Palette.textMuted)
            if let list = model.sources {
                if list.isEmpty {
                    Text(L("cal.none")).font(.subheadline).foregroundStyle(Palette.textMuted).frame(maxWidth: .infinity, alignment: .leading).card(padding: 16)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(list.enumerated()), id: \.element.id) { n, s in
                            sourceRow(s)
                            if n < list.count - 1 { Divider().overlay(Palette.border).padding(.leading, 52) }
                        }
                    }
                    .card(padding: 0)
                }
            } else {
                LoadingView().frame(height: 90).card(padding: 0)
            }
        }
    }

    private func sourceRow(_ s: CalendarSource) -> some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle().fill(CalendarFormat.color(s.color))
                if s.kind == .local { Image(systemName: "heart.fill").font(.caption2).foregroundStyle(.white) }
            }
            .frame(width: 24, height: 24)
            .padding(.top, 2)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(model.label(s)).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                Text(subtitle(s)).font(.caption).foregroundStyle(Palette.textMuted).lineLimit(1)
                HStack(spacing: 4) {
                    switch s.status {
                    case .syncing: ProgressView().controlSize(.mini)
                    case .failed, .authFailed: Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.danger)
                    case .ok: Image(systemName: "checkmark").foregroundStyle(Palette.accentText)
                    default: EmptyView()
                    }
                    Text(status(s))
                    if s.status == .ok, let n = s.eventCount { Text("· " + Lp("cal.events", count: n)).foregroundStyle(Palette.textMuted) }
                }
                .font(.caption)
                .foregroundStyle(s.status == .failed || s.status == .authFailed ? Palette.danger : Palette.textMuted)
                if s.status == .failed || s.status == .authFailed, let help = s.status == .authFailed ? L("cal.authHelp") : s.lastError {
                    Text(help).font(.caption).foregroundStyle(Palette.danger)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            Toggle(L("cal.show"), isOn: Binding(get: { s.enabled }, set: { on in
                Task { do { try await model.setEnabled(s, on) } catch { app.toasts.error(error) } }
            }))
            .labelsHidden()
            .tint(Palette.accent)
            .accessibilityLabel("\(L("cal.show")): \(model.label(s))")
            Menu {
                if s.kind != .local {
                    Button {
                        Task {
                            do {
                                try await model.syncNow(s)
                                app.toasts.show(L("cal.syncStarted", ["name": model.label(s)]))
                            } catch { app.toasts.error(error) }
                        }
                    } label: { Label(L("cal.syncNow"), systemImage: "arrow.clockwise") }
                    .disabled(s.status == .syncing || !s.enabled)
                }
                Button { editing = SourceSheet(source: s) } label: { Label(L("common.edit"), systemImage: "pencil") }
                Button(role: .destructive) { removing = s } label: { Label(L("cal.remove"), systemImage: "trash") }
            } label: {
                Image(systemName: "ellipsis.circle").font(.title3).frame(width: 44, height: 44)
            }
            .accessibilityLabel(L("cal.edit", ["name": model.label(s)]))
        }
        .padding(.leading, 14)
        .padding(.vertical, 10)
        .opacity(s.enabled ? 1 : 0.6)
    }

    private func subtitle(_ s: CalendarSource) -> String {
        switch s.kind {
        case .local: L("cal.receivedHint")
        case .caldav: "\(L("cal.kind.caldav")) · \(s.username ?? "")"
        case .ics: s.urlHint ?? L("cal.kind.ics")
        }
    }

    private func status(_ s: CalendarSource) -> String {
        if s.status == .ok || (s.status == .disabled && s.lastSyncedAt != nil) {
            return s.lastSyncedAt.map { L("cal.lastSynced", ["when": Format.relative($0, locale: L10n.locale)]) } ?? L("cal.neverSynced")
        }
        return L("cal.status.\(s.status.rawValue)")
    }

    // MARK: Sent events

    private var sentEvents: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(L("cal.sentTitle"), trailing: model.mayPut ? AnyView(
                Button { sendingEvent = true } label: { Label(L("cal.sendButton"), systemImage: "paperplane") }
                    .buttonStyle(.kf(.soft))
            ) : nil)
            Text(L("cal.sendHint")).font(.footnote).foregroundStyle(Palette.textMuted)
            if let list = model.invitations {
                if list.isEmpty {
                    Text(L("cal.sentNone")).font(.subheadline).foregroundStyle(Palette.textMuted).frame(maxWidth: .infinity, alignment: .leading).card(padding: 16)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(list.enumerated()), id: \.element.id) { n, inv in
                            invitationRow(inv)
                            if n < list.count - 1 { Divider().overlay(Palette.border).padding(.leading, 16) }
                        }
                    }
                    .card(padding: 0)
                }
            } else {
                LoadingView().frame(height: 60).card(padding: 0)
            }
        }
    }

    private func invitationRow(_ inv: EventInvitation) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(inv.title).font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                Text(CalendarFormat.when(inv) + (inv.senderId == app.me?.id ? "" : " · " + L("cal.sentBy", ["name": inv.senderName])))
                    .font(.caption).foregroundStyle(Palette.textMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            InvitationBadge(status: inv.status)
            if model.canWithdraw(inv, me: app.me?.id) {
                Button(L("cal.withdraw")) {
                    Task {
                        do {
                            try await model.withdraw(inv)
                            app.toasts.show(L("cal.withdrawn"))
                        } catch { app.toasts.error(error) }
                    }
                }
                .buttonStyle(.kf(.ghost))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .accessibilityElement(children: .contain)
    }
}

struct InvitationBadge: View {
    let status: EventInvitation.StatusPayload

    var body: some View {
        let (fg, bg): (Color, Color) = switch status {
        case .pending: (Palette.warn, Palette.warnSoft)
        case .accepted: (Palette.accentText, Palette.accentSoft)
        case .declined: (Palette.danger, Palette.dangerSoft)
        case .withdrawn: (Palette.textMuted, Palette.surface2)
        }
        Pill(L("cal.inv.\(status.rawValue)"), foreground: fg, background: bg)
    }
}
