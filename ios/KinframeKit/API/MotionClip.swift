import AVFoundation
import Foundation

/// The motion clip of a Live Photo, `GET /v1/items/{id}/media/motion`. The server either redirects to a short-lived
/// signed URL (played without our token) or streams the clip itself (played with it).
public enum MotionClip {
    /// `variant`: `motion` for Live Photo clips; `master` plays a chat video or a voice message's recording.
    public static func asset(api: API, itemId: String, variant: String = "motion") async throws -> AVURLAsset {
        let url = api.url("v1/items/\(itemId)/media/\(variant)")
        var request = URLRequest(url: url)
        request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        request.identifyApp()
        guard let token = try await api.token() else { throw APIError.unauthorized }
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await api.session.data(for: request, delegate: NoRedirect())
        guard let http = response as? HTTPURLResponse else { throw APIError.network }
        switch http.statusCode {
        case 300..<400:
            guard let location = http.value(forHTTPHeaderField: "Location"), let target = URL(string: location, relativeTo: url) else {
                throw APIError.network
            }
            return AVURLAsset(url: target.absoluteURL)
        case 200..<300:
            return AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["Authorization": "Bearer \(token)", AppIdentity.headerName: AppIdentity.headerValue]])
        default:
            throw APIError.from(status: http.statusCode, body: data)
        }
    }
}

/// Stops at the redirect so the signed URL can be played without our Authorization header.
private final class NoRedirect: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        nil
    }
}
