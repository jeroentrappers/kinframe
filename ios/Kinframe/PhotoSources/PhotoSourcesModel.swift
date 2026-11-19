import KinframeKit
import SwiftUI

/// The photo sources of one album or feed (or the ones you set up, for Settings), kept fresh from
/// `photo_source.updated` / `photo_source.removed`.
@MainActor
@Observable
final class PhotoSourcesModel {
    enum Scope: Equatable {
        case target(SourceTargetRef)
        case mine
    }

    let api: API
    let scope: Scope
    private(set) var sources: [PhotoSource] = []
    private(set) var loaded = false
    var error: String?
    /// Sources with a request in flight (sync, pause, …), for spinners on their buttons.
    private(set) var busy: Set<String> = []

    init(api: API, scope: Scope) {
        self.api = api
        self.scope = scope
    }

    var target: SourceTargetRef? {
        if case .target(let t) = scope { return t }
        return nil
    }

    func load() async {
        do {
            switch scope {
            case .target(let t): sources = try await api.photoSources(for: t)
            case .mine: sources = try await api.myPhotoSources()
            }
            error = nil
        } catch is CancellationError {
        } catch {
            if !loaded { self.error = errorMessage(error) }
        }
        loaded = true
    }

    private func belongs(_ s: PhotoSource) -> Bool {
        switch scope {
        case .mine: return s.isOwner == true
        case .target(.album(_, let albumId)): return s.target.albumId == albumId
        case .target(.feed(let feedId)): return s.target.feedId == feedId
        }
    }

    func apply(_ event: LiveEvent) {
        switch event {
        case .photoSourceUpdated(let s):
            if let i = sources.firstIndex(where: { $0.id == s.id }) {
                if belongs(s) { sources[i] = s } else { sources.remove(at: i) }
            } else if belongs(s) {
                sources.append(s)
            }
        case .photoSourceRemoved(let id, _, _):
            sources.removeAll { $0.id == id }
        default:
            break
        }
    }

    func upsert(_ s: PhotoSource) {
        if let i = sources.firstIndex(where: { $0.id == s.id }) { sources[i] = s } else { sources.append(s) }
    }

    func isBusy(_ s: PhotoSource) -> Bool { busy.contains(s.id) }

    private func run(_ s: PhotoSource, _ work: () async throws -> PhotoSource?) async throws {
        busy.insert(s.id)
        defer { busy.remove(s.id) }
        if let updated = try await work() { upsert(updated) }
    }

    func syncNow(_ s: PhotoSource, confirmRemovals: Bool = false) async throws {
        try await run(s) { try await api.syncPhotoSource(s.id, confirmRemovals: confirmRemovals) }
    }

    func setPaused(_ s: PhotoSource, _ paused: Bool) async throws {
        try await run(s) { try await api.setPhotoSourcePaused(s.id, paused) }
    }

    func takeOver(_ s: PhotoSource, apiKey: String? = nil) async throws {
        try await run(s) { try await api.takeOverPhotoSource(s.id, apiKey: apiKey) }
    }

    func delete(_ s: PhotoSource, removeItems: Bool) async throws {
        try await run(s) {
            try await api.deletePhotoSource(s.id, removeItems: removeItems)
            return nil
        }
        sources.removeAll { $0.id == s.id }
    }
}
