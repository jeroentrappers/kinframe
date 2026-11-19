import Foundation
import OpenAPIRuntime

/// Encouragement to share (tag `engagement` in openapi.yaml): family engagement settings, streaks, prompts, On this
/// day, summaries, recaps, milestones, reminder preferences and photo requests.
extension API {
    // MARK: Family settings and streak

    public func feedEngagement(_ feedId: String) async throws -> FeedEngagement {
        try await call { try await $0.getFeedEngagement(path: .init(feedId: feedId)).ok.body.json }
    }

    public func updateFeedEngagement(_ feedId: String, _ update: Components.Schemas.FeedEngagementUpdate) async throws -> FeedEngagement {
        try await call { try await $0.updateFeedEngagement(path: .init(feedId: feedId), body: .json(update)).ok.body.json }
    }

    public func feedStreak(_ feedId: String) async throws -> FeedStreak {
        try await call { try await $0.getFeedStreak(path: .init(feedId: feedId)).ok.body.json }
    }

    // MARK: Prompts

    public func feedPrompts(_ feedId: String) async throws -> [FeedPrompt] {
        try await call { try await $0.listFeedPrompts(path: .init(feedId: feedId)).ok.body.json }
    }

    public enum PromptChoice: Sendable, Equatable {
        case library(String)
        case own(String)
        case skip
    }

    public func setFeedPrompt(_ feedId: String, weekOf: String, _ choice: PromptChoice) async throws -> FeedPrompt {
        let body: Components.Schemas.FeedPromptSet = switch choice {
        case .library(let key): .init(libraryKey: key)
        case .own(let title): .init(title: String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(PromptLogic.maxTitle)))
        case .skip: .init(skip: true)
        }
        return try await call { try await $0.setFeedPrompt(path: .init(feedId: feedId, weekOf: weekOf), body: .json(body)).ok.body.json }
    }

    public func resetFeedPrompt(_ feedId: String, weekOf: String) async throws {
        _ = try await call { try await $0.resetFeedPrompt(path: .init(feedId: feedId, weekOf: weekOf)).noContent }
    }

    public func promptLibrary() async throws -> [PromptLibraryEntry] {
        try await call { try await $0.listPromptLibrary().ok.body.json }
    }

    // MARK: Home cards

    public func onThisDay() async throws -> OnThisDay {
        try await call { try await $0.getOnThisDay().ok.body.json }
    }

    public func weeklySummary(_ weekOf: String) async throws -> WeeklySummary {
        try await call { try await $0.getWeeklySummary(path: .init(weekOf: weekOf)).ok.body.json }
    }

    public func feedRecap(_ feedId: String, month: String) async throws -> MonthlyRecap {
        try await call { try await $0.getFeedRecap(path: .init(feedId: feedId, month: month)).ok.body.json }
    }

    // MARK: Milestones

    public func milestones() async throws -> [Milestone] {
        try await call { try await $0.listMyMilestones().ok.body.json }
    }

    public func updateMilestone(_ key: MilestoneKey, shown: Bool? = nil, seen: Bool = false) async throws -> Milestone {
        try await call {
            try await $0.updateMyMilestone(path: .init(milestoneKey: key), body: .json(.init(shown: shown, seen: seen ? true : nil))).ok.body.json
        }
    }

    // MARK: Reminders

    public func reminderPreferences() async throws -> ReminderPreferences {
        try await call { try await $0.getReminderPreferences().ok.body.json }
    }

    public func updateReminderPreferences(_ update: Components.Schemas.ReminderPreferencesUpdate) async throws -> ReminderPreferences {
        try await call { try await $0.updateReminderPreferences(body: .json(update)).ok.body.json }
    }

    // MARK: Photo requests

    public func photoRequest(_ id: String) async throws -> PhotoRequest {
        try await call { try await $0.getPhotoRequest(path: .init(requestId: id)).ok.body.json }
    }

    public func framePhotoRequests(_ frameId: String) async throws -> [PhotoRequest] {
        try await call { try await $0.listFramePhotoRequests(path: .init(frameId: frameId)).ok.body.json }
    }
}
