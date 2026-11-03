import KinframeKit
import SwiftUI

/// The frame's Calendar section (web: FrameCalendar.svelte): calendars synced by the server, what the frame shows
/// (agenda, month, countdowns), events sent to the frame, and the calendar settings.
@MainActor
@Observable
final class FrameCalendarModel {
    let api: API
    let frame: FrameSummary
    private(set) var sources: [CalendarSource]?
    private(set) var view: CalendarView?
    private(set) var invitations: [EventInvitation]?
    var error: String?
    private var polling: Task<Void, Never>?

    init(api: API, frame: FrameSummary) {
        self.api = api
        self.frame = frame
    }

    /// Calendars, preview and settings need change_settings.
    var manage: Bool { frame.can(\.changeSettings) }
    /// Sending events needs send.
    var mayPut: Bool { frame.can(\.send) }

    var usedColors: [CalendarColor] { (sources ?? []).map(\.color) }

    func load() async {
        error = nil
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                if manage {
                    group.addTask { try await self.loadSources() }
                    group.addTask { try await self.loadView() }
                }
                if mayPut || manage { group.addTask { try await self.loadInvitations() } }
                try await group.waitForAll()
            }
        } catch is CancellationError {
        } catch {
            self.error = errorMessage(error)
        }
    }

    func loadSources() async throws {
        let id = frame.id
        sources = try await api.call { try await $0.listCalendars(path: .init(frameId: id)).ok.body.json }
        pollWhileSyncing()
    }

    func loadView() async throws {
        let id = frame.id
        view = try await api.call { try await $0.getFrameCalendar(path: .init(frameId: id)).ok.body.json }
    }

    func loadInvitations() async throws {
        let id = frame.id
        invitations = try await api.call { try await $0.listEventInvitations(path: .init(frameId: id)).ok.body.json }
    }

    /// While something syncs, check back every few seconds (the server finishes within a minute).
    private func pollWhileSyncing() {
        guard (sources ?? []).contains(where: { $0.status == .syncing }), polling == nil else { return }
        polling = Task { [weak self] in
            for _ in 0..<20 {
                try? await Task.sleep(for: .seconds(3))
                guard let self, !Task.isCancelled else { return }
                let id = self.frame.id
                guard let list = try? await self.api.call({ try await $0.listCalendars(path: .init(frameId: id)).ok.body.json }) else { continue }
                self.sources = list
                if !list.contains(where: { $0.status == .syncing }) {
                    try? await self.loadView()
                    break
                }
            }
            self?.polling = nil
        }
    }

    func stop() {
        polling?.cancel()
        polling = nil
    }

    func label(_ s: CalendarSource) -> String {
        s.kind == .local && s.name.isEmpty ? L("cal.received") : s.name
    }

    func label(sourceId: String) -> String {
        guard let s = view?.value1.sources.first(where: { $0.id == sourceId }) else { return "" }
        return s.kind == .local && s.name.isEmpty ? L("cal.received") : s.name
    }

    // MARK: Sources

    func saved(_ list: [CalendarSource]) {
        var all = sources ?? []
        for s in list {
            if let i = all.firstIndex(where: { $0.id == s.id }) { all[i] = s } else { all.append(s) }
        }
        sources = all
        pollWhileSyncing()
        Task { try? await loadView() }
    }

    func syncNow(_ s: CalendarSource) async throws {
        let (fid, cid) = (frame.id, s.id)
        let updated = try await api.call { try await $0.syncCalendar(path: .init(frameId: fid, calendarId: cid)).accepted.body.json }
        saved([updated])
    }

    func setEnabled(_ s: CalendarSource, _ on: Bool) async throws {
        let (fid, cid) = (frame.id, s.id)
        let updated = try await api.call {
            try await $0.updateCalendar(path: .init(frameId: fid, calendarId: cid), body: .json(.init(enabled: on))).ok.body.json
        }
        saved([updated])
    }

    func remove(_ s: CalendarSource) async throws {
        let (fid, cid) = (frame.id, s.id)
        _ = try await api.call { try await $0.deleteCalendar(path: .init(frameId: fid, calendarId: cid)).noContent }
        sources = (sources ?? []).filter { $0.id != s.id }
        try? await loadView()
    }

    // MARK: Countdowns

    func setCountdown(_ ev: CalendarEvent, _ on: Bool) async throws {
        let (fid, eid) = (frame.id, ev.id)
        if on {
            _ = try await api.call { try await $0.setCountdown(path: .init(frameId: fid, eventId: eid)).noContent }
        } else {
            _ = try await api.call { try await $0.clearCountdown(path: .init(frameId: fid, eventId: eid)).noContent }
        }
        if var v = view {
            for i in v.value1.events.indices where v.value1.events[i].id == ev.id { v.value1.events[i].countdown = on }
            view = v
        }
        try? await loadView()
    }

    // MARK: Sent events

    func sent(_ inv: EventInvitation) {
        invitations = [inv] + (invitations ?? [])
    }

    func withdraw(_ inv: EventInvitation) async throws {
        let (fid, iid) = (frame.id, inv.id)
        do {
            _ = try await api.call { try await $0.withdrawEventInvitation(path: .init(frameId: fid, invitationId: iid)).noContent }
            invitations = (invitations ?? []).map { var x = $0; if x.id == inv.id { x.status = .withdrawn }; return x }
        } catch {
            try? await loadInvitations()
            throw error
        }
    }

    /// Withdrawing: your own pending invitations, or any when you manage the frame.
    func canWithdraw(_ inv: EventInvitation, me: String?) -> Bool {
        inv.status == .pending && (inv.senderId == me || frame.isManager)
    }

    /// A frame decided on an invitation: update the list. Returns the text of the toast the app shows for it.
    @discardableResult
    func apply(_ event: LiveEvent) -> String? {
        guard case .invitationDecided(let frameId, let inv) = event, frameId == frame.id else { return nil }
        if var list = invitations {
            if let i = list.firstIndex(where: { $0.id == inv.id }) { list[i] = inv } else { list.insert(inv, at: 0) }
            invitations = list
        }
        if manage, inv.status == .accepted {
            Task { try? await loadSources(); try? await loadView() }
        }
        switch inv.status {
        case .accepted: return L("cal.decidedAccepted", ["frame": frame.name, "title": inv.title])
        case .declined: return L("cal.decidedDeclined", ["frame": frame.name, "title": inv.title])
        default: return nil
        }
    }
}

// MARK: Formatting shared by the calendar views

enum CalendarFormat {
    /// A palette colour that follows light and dark mode.
    static func color(_ c: CalendarColor?) -> Color {
        let p = CalendarLogic.palette[c ?? .sky] ?? CalendarLogic.palette[.sky]!
        return Color(UIColor { trait in
            let v = trait.userInterfaceStyle == .dark ? p.dark : p.light
            return UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
        })
    }

    static func colorName(_ c: CalendarColor) -> String {
        c.rawValue.prefix(1).uppercased() + c.rawValue.dropFirst()
    }

    /// A plain date ("2026-11-03") formatted with a template, in the app's language.
    static func day(_ day: String, _ template: String) -> String {
        guard let d = CalendarLogic.utcDate(day) else { return day }
        let f = DateFormatter()
        f.locale = L10n.locale
        f.timeZone = TimeZone(identifier: "UTC")
        f.setLocalizedDateFormatFromTemplate(template)
        return f.string(from: d)
    }

    /// "Tue 4 Nov · 10:00" or "Tue 4 Nov · All day" for a sent invitation, in the sender's time zone.
    static func when(_ inv: EventInvitation) -> String {
        if inv.allDay, let s = inv.startDate {
            let start = day(s, "EEEdMMM")
            if let e = inv.endDate, e != s { return "\(start) – \(day(e, "EEEdMMM"))" }
            return "\(start) · \(L("cal.allDay"))"
        }
        guard let start = inv.startAt else { return "" }
        let f = DateFormatter()
        f.locale = L10n.locale
        f.setLocalizedDateFormatFromTemplate("EEEdMMM")
        return "\(f.string(from: start)) · \(start.formatted(Date.FormatStyle(date: .omitted, time: .shortened).locale(L10n.locale)))"
    }
}
