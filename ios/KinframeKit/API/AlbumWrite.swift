import Foundation

extension API {
    /// `PATCH /v1/frames/{frameId}/albums/{albumId}` with `If-Match`. Sent as raw JSON because clearing a schedule
    /// needs an explicit `"schedule": null`, which the generated `AlbumWrite` can't express (it leaves nils out).
    public func updateAlbum(frameId: String, album: Album, _ body: [String: JSONValue]) async throws -> Album {
        let url = url("v1/frames/\(frameId)/albums/\(album.id)")
        let payload = try JSONEncoder().encode(JSONValue.object(body))
        var refreshed = false
        while true {
            var request = URLRequest(url: url)
            request.httpMethod = "PATCH"
            request.httpBody = payload
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.setValue("\"\(album.version)\"", forHTTPHeaderField: "If-Match")
            request.setValue(L10n.code, forHTTPHeaderField: "Accept-Language")
            guard let token = try await tokens.accessToken() else { throw APIError.unauthorized }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let data: Data, response: URLResponse
            do {
                (data, response) = try await session.data(for: request)
            } catch {
                throw APIError.wrap(error)
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status == 401, !refreshed, await tokens.refreshAfterUnauthorized() != nil {
                refreshed = true
                continue
            }
            guard (200..<300).contains(status) else { throw APIError.from(status: status, body: data) }
            return try JSONDecoder().decode(Album.self, from: data)
        }
    }
}
