import KinframeKit
import SwiftUI

/// The frame's library (web: FrameLibrary): what's on the frame, hidden and trash, and its albums.
@MainActor
@Observable
final class LibraryModel {
    enum View: String, CaseIterable, Identifiable {
        case visible, hidden, trashed
        var id: String { rawValue }
        var api: Operations.ListPlacements.Input.Query.VisibilityPayload {
            switch self {
            case .visible: .visible
            case .hidden: .hidden
            case .trashed: .trashed
            }
        }
    }

    enum Action: String {
        case hide, unhide, trash, restore
        var api: Operations.BulkUpdatePlacements.Input.Body.JsonPayload.ActionPayload {
            switch self {
            case .hide: .hide
            case .unhide: .unhide
            case .trash: .trash
            case .restore: .restore
            }
        }
    }

    typealias Counts = Operations.ListPlacements.Output.Ok.Body.JsonPayload.CountsPayload

    let api: API
    let frameId: String
    var view: View = .visible
    private(set) var placements: [Placement] = []
    private(set) var cursor: String?
    private(set) var loading = false
    private(set) var counts = Counts()
    var error: String?
    var selecting = false
    var selection: Set<String> = []

    // Albums
    private(set) var albums: [Album] = []
    /// The album being looked at; nil is "All photos".
    var albumId: String?
    /// The album the frame plays (`slideshow.album_id`), when we may see the settings.
    private(set) var playingId: String?
    private var settings: FrameSettingsModel?

    init(api: API, frameId: String) {
        self.api = api
        self.frameId = frameId
    }

    var album: Album? { albums.first { $0.id == albumId } }

    /// What `load()` depends on.
    var key: String { "\(view.rawValue)|\(albumId ?? "")" }

    func load() async {
        loading = placements.isEmpty
        defer { loading = false }
        let id = frameId, v = view.api
        do {
            if let albumId {
                // Albums are shown whole, in their order, so fetch every page.
                var all: [Placement] = []
                var next: String?
                repeat {
                    let cursor = next
                    let page = try await api.call {
                        try await $0.listPlacements(path: .init(frameId: id), query: .init(cursor: cursor, limit: 200, albumId: albumId)).ok.body.json
                    }
                    all += page.placements
                    next = page.nextCursor
                } while next != nil && all.count < 2000
                guard albumId == self.albumId else { return }
                placements = AlbumLogic.ordered(all, by: album?.placementIds ?? [])
                cursor = nil
            } else {
                let page = try await api.call { try await $0.listPlacements(path: .init(frameId: id), query: .init(limit: 60, visibility: v)).ok.body.json }
                guard self.albumId == nil, v == view.api else { return }
                placements = page.placements
                cursor = page.nextCursor
                counts = page.counts
            }
            error = nil
        } catch is CancellationError {
        } catch {
            self.error = errorMessage(error)
        }
    }

    func loadMore() async {
        guard let cursor, albumId == nil else { return }
        let id = frameId, v = view.api
        if let page = try? await api.call({ try await $0.listPlacements(path: .init(frameId: id), query: .init(cursor: cursor, limit: 60, visibility: v)).ok.body.json }) {
            placements.append(contentsOf: page.placements)
            self.cursor = page.nextCursor
        }
    }

    func select(album id: String?) {
        guard id != albumId else { return }
        albumId = id
        if id != nil { view = .visible }
        placements = []
        selection = []
        selecting = false
    }

    /// Apply to the given placements; returns how many failed. They leave this view right away.
    func perform(_ action: Action, on ids: [String]) async throws -> Int {
        let frameId = frameId
        let result = try await api.call {
            try await $0.bulkUpdatePlacements(path: .init(frameId: frameId), body: .json(.init(placementIds: ids, action: action.api))).ok.body.json
        }
        let ok = Set(result.ok ?? [])
        placements.removeAll { ok.contains($0.id) }
        selection.subtract(ok)
        if selection.isEmpty { selecting = false }
        let n = ok.count
        switch action {
        case .hide: counts.hidden = (counts.hidden ?? 0) + n
        case .unhide: counts.hidden = max(0, (counts.hidden ?? 0) - n)
        case .trash:
            counts.trashed = (counts.trashed ?? 0) + n
            if view == .hidden { counts.hidden = max(0, (counts.hidden ?? 0) - n) }
        case .restore: counts.trashed = max(0, (counts.trashed ?? 0) - n)
        }
        return result.failed?.count ?? 0
    }

    // MARK: On this day

    /// Photos taken on today's date in earlier years; quietly empty when the request fails.
    private(set) var memories: [Placement] = []

    func loadMemories() async {
        let id = frameId, day = AlbumLogic.todayMonthDay()
        memories = (try? await api.call {
            try await $0.listPlacements(path: .init(frameId: id), query: .init(limit: 24, onThisDay: day, sort: .takenDesc)).ok.body.json.placements
        }) ?? []
    }

    // MARK: Albums

    func loadAlbums() async {
        let id = frameId
        do {
            albums = try await api.call { try await $0.listAlbums(path: .init(frameId: id)).ok.body.json }
            if let albumId, !albums.contains(where: { $0.id == albumId }) { select(album: nil) }
        } catch is CancellationError {
        } catch {
            albums = []
        }
    }

    /// Which album the frame plays; only for members who may change settings.
    func loadPlaying() async {
        let s = FrameSettingsModel(api: api, frameId: frameId)
        await s.load()
        guard s.loaded else { return }
        settings = s
        playingId = Self.albumId(in: s)
    }

    private static func albumId(in s: FrameSettingsModel) -> String? {
        if case .string(let id)? = s.value("slideshow.album_id") { return id }
        return nil
    }

    /// Play the album on the frame, or go back to all photos. Returns whether it now plays the album.
    func togglePlaying(_ album: Album) async throws -> Bool {
        if settings == nil { await loadPlaying() }
        guard let settings else { throw APIError.unexpected }
        let next = playingId == album.id ? nil : album.id
        settings.set("slideshow.album_id", next.map(JSONValue.string) ?? .null)
        do {
            try await settings.save()
        } catch {
            settings.discard()
            throw error
        }
        playingId = Self.albumId(in: settings)
        return playingId == album.id
    }

    private func upsert(_ a: Album) {
        if let i = albums.firstIndex(where: { $0.id == a.id }) {
            albums[i] = a
        } else {
            albums.append(a)
            albums.sort { $0.name.localizedCompare($1.name) == .orderedAscending }
        }
    }

    func createAlbum(name: String, placementIds: [String] = []) async throws -> Album {
        let id = frameId
        let body = Components.Schemas.AlbumWrite(name: name, placementIds: placementIds.isEmpty ? nil : placementIds)
        let a = try await api.call { try await $0.createAlbum(path: .init(frameId: id), body: .json(body)).created.body.json }
        upsert(a)
        return a
    }

    /// PATCH an album with its version. After a `version_conflict` the albums and photos are reloaded and
    /// `AlbumConflict` is thrown.
    @discardableResult
    func writeAlbum(_ album: Album, _ body: [String: JSONValue]) async throws -> Album {
        do {
            let a = try await api.updateAlbum(frameId: frameId, album: album, body)
            upsert(a)
            return a
        } catch let e as APIError where e.code == "version_conflict" || e.status == 412 {
            await loadAlbums()
            await load()
            throw AlbumConflict()
        }
    }

    func rename(_ album: Album, to name: String) async throws {
        try await writeAlbum(album, ["name": .string(name)])
    }

    func setCover(_ album: Album, placementId: String) async throws {
        try await writeAlbum(album, ["cover_placement_id": .string(placementId)])
    }

    func setSchedule(_ album: Album, from: String?, to: String?) async throws {
        if let from, let to {
            try await writeAlbum(album, ["schedule": .object(["from_month_day": .string(from), "to_month_day": .string(to)])])
        } else {
            try await writeAlbum(album, ["schedule": .null])
        }
    }

    /// Save the arranged order: the shown photos first, then members this view doesn't show.
    func saveOrder(_ album: Album, shown: [String]) async throws {
        let ids = AlbumLogic.arrangedIds(shown: shown, album: album.placementIds ?? [])
        let a = try await writeAlbum(album, ["placement_ids": .array(ids.map(JSONValue.string))])
        placements = AlbumLogic.ordered(placements, by: a.placementIds ?? ids)
    }

    func deleteAlbum(_ album: Album) async throws {
        let id = frameId, albumId = album.id
        _ = try await api.call { try await $0.deleteAlbum(path: .init(frameId: id, albumId: albumId)).noContent }
        albums.removeAll { $0.id == albumId }
        if playingId == albumId { playingId = nil }
        select(album: nil)
    }

    /// Add placements to an album (`add_to_album`, or remove them again for Undo); returns how many failed.
    func albumBulk(add: Bool, albumId: String, ids: [String]) async throws -> Int {
        let frameId = frameId
        let result = try await api.call {
            try await $0.bulkUpdatePlacements(path: .init(frameId: frameId), body: .json(.init(
                placementIds: ids, action: add ? .addToAlbum : .removeFromAlbum, albumId: albumId))).ok.body.json
        }
        if !add, albumId == self.albumId {
            let ok = Set(result.ok ?? [])
            placements.removeAll { ok.contains($0.id) }
            selection.subtract(ok)
            if selection.isEmpty { selecting = false }
        }
        await loadAlbums()
        return result.failed?.count ?? 0
    }

    /// Placements in the order the arrange sheet shows (the current album order).
    func move(from: IndexSet, to: Int) {
        placements.move(fromOffsets: from, toOffset: to)
    }
}

/// Someone else changed the album: the latest version is loaded, try again.
struct AlbumConflict: Error, LocalizedError {
    var errorDescription: String? { L("albums.conflict") }
}
