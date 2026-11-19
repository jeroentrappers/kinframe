import Foundation
import OpenAPIRuntime

/// Photo sources and Google Photos imports (tag `photo-sources` in openapi.yaml), as one-line calls for the screens.
/// Errors arrive as `APIError` like every `call`; `SourceErrors.line` turns the photo-source problems into text.
extension API {
    // MARK: Lists

    /// An album's sources (album managers: owner, admin, manage library).
    public func albumPhotoSources(frameId: String, albumId: String) async throws -> [PhotoSource] {
        try await call { try await $0.listAlbumPhotoSources(path: .init(frameId: frameId, albumId: albumId)).ok.body.json }
    }

    /// A feed's sources (feed owner and admins).
    public func feedPhotoSources(_ feedId: String) async throws -> [PhotoSource] {
        try await call { try await $0.listFeedPhotoSources(path: .init(feedId: feedId)).ok.body.json }
    }

    public func photoSources(for target: SourceTargetRef) async throws -> [PhotoSource] {
        switch target {
        case .album(let frameId, let albumId): try await albumPhotoSources(frameId: frameId, albumId: albumId)
        case .feed(let feedId): try await feedPhotoSources(feedId)
        }
    }

    /// The sources the caller set up, everywhere (Settings → Photo sources).
    public func myPhotoSources() async throws -> [PhotoSource] {
        try await call { try await $0.listMyPhotoSources().ok.body.json }
    }

    public func photoSource(_ id: String) async throws -> PhotoSource {
        try await call { try await $0.getPhotoSource(path: .init(sourceId: id)).ok.body.json }
    }

    // MARK: Connecting

    /// Try a connection without saving it. Failures come back as `ok: false` with `error_code`, not as errors.
    public func testPhotoSource(_ connection: SourceConnection) async throws -> PhotoSourceTestResult {
        try await call { try await $0.testPhotoSource(body: .json(connection.body)).ok.body.json }
    }

    /// The albums an Immich API key can read, own ones first.
    public func immichAlbums(serverURL: String, apiKey: String) async throws -> ImmichAlbumList {
        let body = Components.Schemas.ImmichConnection(serverUrl: SourceInput.cleanServer(serverURL),
                                                       apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines))
        return try await call { try await $0.listImmichAlbums(body: .json(body)).ok.body.json }
    }

    /// Tests again on the server (`422 source_test_failed`), then starts the first run.
    public func createPhotoSource(_ draft: SourceDraft) async throws -> PhotoSource {
        let key = UUID().uuidString.lowercased()
        return try await call {
            try await $0.createPhotoSource(headers: .init(idempotencyKey: key), body: .json(draft.createBody)).created.body.json
        }
    }

    // MARK: Changing

    /// `PATCH` as raw JSON (`SourcePatch.body`): removing a link password is an explicit null.
    public func updatePhotoSource(_ id: String, patch: [String: JSONValue]) async throws -> PhotoSource {
        let payload = try JSONEncoder().encode(JSONValue.object(patch))
        let data = try await raw("PATCH", "v1/photo-sources/\(id)", body: payload)
        return try OutboxRunner.decoder.decode(PhotoSource.self, from: data)
    }

    public func setPhotoSourcePaused(_ id: String, _ paused: Bool) async throws -> PhotoSource {
        try await updatePhotoSource(id, patch: ["paused": .bool(paused)])
    }

    /// Become the owner after `owner_lost_access` (an `immich_api` source needs a new key with it).
    public func takeOverPhotoSource(_ id: String, apiKey: String? = nil) async throws -> PhotoSource {
        var patch: [String: JSONValue] = ["take_over": .bool(true)]
        if let apiKey, !apiKey.isEmpty { patch["api_key"] = .string(apiKey) }
        return try await updatePhotoSource(id, patch: patch)
    }

    /// Sync now (at most once per 2 minutes; `429` otherwise). `confirmRemovals` applies a held-back mass removal.
    public func syncPhotoSource(_ id: String, confirmRemovals: Bool = false) async throws -> PhotoSource {
        try await call {
            try await $0.syncPhotoSource(path: .init(sourceId: id),
                                         body: confirmRemovals ? .json(.init(confirmRemovals: true)) : nil).accepted.body.json
        }
    }

    /// Stop syncing; `removeItems` also takes the imported photos away (in the background).
    public func deletePhotoSource(_ id: String, removeItems: Bool) async throws {
        _ = try await call { try await $0.deletePhotoSource(path: .init(sourceId: id), query: .init(removeItems: removeItems)).noContent }
    }

    /// Delete an album; with `removeSourceItems` the photos its sources imported go too.
    public func deleteAlbum(frameId: String, albumId: String, removeSourceItems: Bool) async throws {
        _ = try await call {
            try await $0.deleteAlbum(path: .init(frameId: frameId, albumId: albumId),
                                     query: .init(removeSourceItems: removeSourceItems ? true : nil)).noContent
        }
    }

    // MARK: Google Photos

    /// Start "Add from Google Photos" into an album or a feed; the browser comes back to `app.kinframe:/google-import`.
    public func startGoogleImport(_ target: SourceTargetRef) async throws -> GoogleImport {
        let key = UUID().uuidString.lowercased()
        var body = Components.Schemas.GoogleImportCreate(returnUri: GoogleImportFlow.returnURI)
        switch target {
        case .album(_, let albumId): body.albumId = albumId
        case .feed(let feedId): body.feedId = feedId
        }
        let payload = body
        return try await call { try await $0.startGoogleImport(headers: .init(idempotencyKey: key), body: .json(payload)).created.body.json }
    }

    public func googleImport(_ id: String) async throws -> GoogleImport {
        try await call { try await $0.getGoogleImport(path: .init(importId: id)).ok.body.json }
    }

    /// The caller's imports of the last 7 days, newest first.
    public func googleImports() async throws -> [GoogleImport] {
        try await call { try await $0.listMyGoogleImports().ok.body.json }
    }

    public func cancelGoogleImport(_ id: String) async throws -> GoogleImport {
        try await call { try await $0.cancelGoogleImport(path: .init(importId: id)).ok.body.json }
    }
}
