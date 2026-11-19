import KinframeKit
import SwiftUI

/// The Settings sections family social adds: chats (read receipts, blocked people), reminders and the email digest,
/// milestones, and for operators the reports that belong to no family.
struct SocialSettingsSections: View {
    @Environment(AppModel.self) private var app
    @State private var receipts = true

    private var features: SocialFeatures { app.social.features }

    var body: some View {
        if features.chats {
            Section {
                Toggle(isOn: Binding(get: { app.me?.showReadReceipts ?? true }, set: { on in Task { await setReceipts(on) } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("chat.readReceipts"))
                        Text(L("chat.settings.readReceiptsHint")).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                }
                .tint(Palette.accent)
                NavigationLink { BlockedPeopleView() } label: { Text(L("chat.blockedPeople")) }
            } header: {
                Text(L("chat.title"))
            } footer: {
                OperatorNote(server: app.server.map { ServerAddress.display($0.url) } ?? "")
            }
        }
        if features.engagement {
            Section(L("engagement.settings.title")) {
                NavigationLink { ReminderSettingsView() } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("engagement.remindersTitle"))
                        if let r = app.social.reminders {
                            Text(ReminderLogic.isSnoozed(r) ? L("engagement.reminders.snoozedShort") : r.level.label)
                                .font(.footnote).foregroundStyle(Palette.textMuted)
                        }
                    }
                }
                NavigationLink { MilestonesView() } label: { Text(L("engagement.milestone.title")) }
            }
        }
        if app.me?.isOperator == true, features.chats {
            Section {
                Button { app.social.sheet = .operatorReports } label: {
                    HStack {
                        Text(L("chat.report.operatorTitle"))
                        Spacer()
                        if app.social.newReports > 0 { Pill("\(app.social.newReports)", foreground: Palette.onPrimary, background: Palette.primary) }
                    }
                }
            }
        }
    }

    private func setReceipts(_ on: Bool) async {
        guard let api = app.api else { return }
        do {
            app.me = try await api.setShowReadReceipts(on)
        } catch {
            app.toasts.error(error)
        }
    }
}

/// Reminders (README §5.20): normal, fewer or off; frame requests, the weekly summary and birthday reminders;
/// snooze; quiet hours; the email digest (only when the server can send mail). Saved on change.
struct ReminderSettingsView: View {
    @Environment(AppModel.self) private var app

    private var prefs: ReminderPreferences? { app.social.reminders }

    var body: some View {
        Form {
            if let p = prefs {
                if p.autoReduced == true {
                    Section {
                        Callout(L("engagement.reminders.autoReduced"))
                        Button(L("common.dismiss")) { Task { await update(.init(autoReducedSeen: true)) } }
                    }
                    .listRowBackground(Palette.surface)
                }
                Section {
                    Picker(L("engagement.reminderLevel"), selection: Binding(get: { p.level }, set: { v in Task { await update(.init(level: v)) } })) {
                        ForEach(ReminderLevel.allCases, id: \.self) { l in Text(l.label).tag(l) }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text(L("engagement.reminderLevel"))
                } footer: {
                    Text(p.level.hint)
                }
                .listRowBackground(Palette.surface)
                Section {
                    toggle(p.frameRequests, L("engagement.frameRequests"), L("engagement.reminders.frameRequestsHint")) { .init(frameRequests: $0) }
                    toggle(p.weeklySummary, L("engagement.reminders.summary"), L("engagement.reminders.summaryHint")) { .init(weeklySummary: $0) }
                    toggle(p.birthdayReminders, L("engagement.birthdayReminders"), L("engagement.reminders.birthdaysHint")) { .init(birthdayReminders: $0) }
                }
                .listRowBackground(Palette.surface)
                Section {
                    if ReminderLogic.isSnoozed(p), let until = p.snoozedUntil {
                        Text(L("engagement.reminders.snoozedUntil", ["date": until.formatted(date: .long, time: .omitted)]))
                        Button(L("engagement.reminders.endSnooze")) { Task { await update(.init(snoozeDays: 0)) } }
                    } else {
                        Button(L("engagement.reminders.snooze")) { Task { await update(.init(snoozeDays: 30)) } }
                    }
                } footer: {
                    Text(L("engagement.reminders.snoozeHint"))
                }
                .listRowBackground(Palette.surface)
                Section {
                    DatePicker(L("engagement.quietFrom"), selection: Binding(get: { ReminderLogic.date(p.quietHours.start) }, set: { d in
                        Task { await update(.init(quietHours: .init(start: ReminderLogic.hhmm(from: d), end: p.quietHours.end))) }
                    }), displayedComponents: .hourAndMinute)
                    DatePicker(L("engagement.reminders.quietTo"), selection: Binding(get: { ReminderLogic.date(p.quietHours.end) }, set: { d in
                        Task { await update(.init(quietHours: .init(start: p.quietHours.start, end: ReminderLogic.hhmm(from: d)))) }
                    }), displayedComponents: .hourAndMinute)
                } header: {
                    Text(L("engagement.quietHours"))
                } footer: {
                    Text(L("engagement.quietHoursHint"))
                }
                .listRowBackground(Palette.surface)
                if app.social.features.emailDigest || p.emailDigestAvailable == true {
                    Section {
                        Picker(L("engagement.digest.title"), selection: Binding(get: { p.emailDigest }, set: { v in Task { await update(.init(emailDigest: v)) } })) {
                            ForEach(EmailDigest.allCases, id: \.self) { d in Text(d.label).tag(d) }
                        }
                    } footer: {
                        Text(L("engagement.emailDigestHint"))
                    }
                    .listRowBackground(Palette.surface)
                }
            } else {
                LoadingView().listRowBackground(Color.clear)
            }
        }
        .scrollContentBackground(.hidden)
        .pageBackground()
        .navigationTitle(L("engagement.remindersTitle"))
        .task { await app.social.loadReminders() }
    }

    private func toggle(_ value: Bool, _ title: String, _ hint: String,
                        _ body: @escaping (Bool) -> Components.Schemas.ReminderPreferencesUpdate) -> some View {
        Toggle(isOn: Binding(get: { value }, set: { v in Task { await update(body(v)) } })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(hint).font(.footnote).foregroundStyle(Palette.textMuted)
            }
        }
        .tint(Palette.accent)
    }

    private func update(_ u: Components.Schemas.ReminderPreferencesUpdate) async {
        guard let api = app.api else { return }
        do {
            app.social.setReminders(try await api.updateReminderPreferences(u))
        } catch {
            app.toasts.error(error)
        }
    }
}

/// Your milestones, private unless you show them on your profile.
struct MilestonesView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Form {
            Section {
                if app.social.milestones.isEmpty {
                    Text(L("engagement.milestone.none")).foregroundStyle(Palette.textMuted)
                }
                ForEach(app.social.milestones.sorted { $0.achievedAt > $1.achievedAt }) { m in
                    Toggle(isOn: Binding(get: { m.shown }, set: { on in Task { await set(m, shown: on) } })) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(m.key.title)
                                Text(m.achievedAt.formatted(date: .long, time: .omitted)).font(.footnote).foregroundStyle(Palette.textMuted)
                            }
                        } icon: {
                            Image(systemName: m.key.symbol)
                        }
                    }
                    .tint(Palette.accent)
                }
            } footer: {
                Text(L("engagement.milestone.hint"))
            }
            .listRowBackground(Palette.surface)
        }
        .scrollContentBackground(.hidden)
        .pageBackground()
        .navigationTitle(L("engagement.milestone.title"))
        .task { await app.social.loadMilestones() }
    }

    private func set(_ m: Milestone, shown: Bool) async {
        guard let api = app.api else { return }
        do { app.social.setMilestone(try await api.updateMilestone(m.key, shown: shown)) } catch { app.toasts.error(error) }
    }
}
