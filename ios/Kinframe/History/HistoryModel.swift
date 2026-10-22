import KinframeKit
import SwiftUI

/// The sent history, newest first, kept fresh from the user websocket (web: history page + sendsLive.ts).
@MainActor
@Observable
final class HistoryModel {
    enum Filter: String, CaseIterable, Identifiable {
        case all, ongoing, delivered
        var id: String { rawValue }
        var apiValue: Operations.ListMySends.Input.Query.StatePayload {
            switch self {
            case .all: .all
            case .ongoing: .ongoing
            case .delivered: .delivered
            }
        }
    }

    struct Group: Identifiable {
        let bucket: Format.DayBucket
        var sends: [Send]
        var id: Format.DayBucket { bucket }
    }

    private(set) var sends: [Send] = []
    private(set) var cursor: String?
    private(set) var loading = false
    private(set) var loadingMore = false
    private(set) var loaded = false
    var error: String?
    var filter: Filter = .all

    private let api: API
    private var refetches: [String: Task<Void, Never>] = [:]
    private let debounce: Duration

    init(api: API, debounce: Duration = .milliseconds(250)) {
        self.api = api
        self.debounce = debounce
    }

    var groups: [Group] {
        var out: [Group] = []
        for s in sends {
            let b = Format.dayBucket(s.createdAt)
            if out.last?.bucket == b { out[out.count - 1].sends.append(s) } else { out.append(Group(bucket: b, sends: [s])) }
        }
        return out
    }

    func load() async {
        loading = !loaded
        error = nil
        defer { loading = false }
        do {
            let f = filter.apiValue
            let page = try await api.call { try await $0.listMySends(query: .init(limit: 20, state: f)).ok.body.json }
            sends = page.sends
            cursor = page.nextCursor
            loaded = true
        } catch is CancellationError {
        } catch {
            self.error = errorMessage(error)
        }
    }

    func loadMore() async {
        guard let cursor, !loadingMore else { return }
        loadingMore = true
        defer { loadingMore = false }
        do {
            let f = filter.apiValue
            let page = try await api.call { try await $0.listMySends(query: .init(cursor: cursor, limit: 20, state: f)).ok.body.json }
            let known = Set(sends.map(\.id))
            sends.append(contentsOf: page.sends.filter { !known.contains($0.id) })
            self.cursor = page.nextCursor
        } catch {
            self.error = errorMessage(error)
        }
    }

    /// Reactions and replies patch in place; `send.updated` refetches that send (debounced); frame status updates the
    /// frames' online dots.
    func apply(_ event: LiveEvent) {
        switch event {
        case .reaction(let placementId, _, let reaction, _):
            for i in sends.indices where Delivery.apply(reaction: reaction, placementId: placementId, to: &sends[i]) { break }
        case .reply(let placementId, _, let preset):
            for i in sends.indices where Delivery.apply(reply: preset, placementId: placementId, to: &sends[i]) { break }
        case .frameStatus(let frameId, let online, _):
            for i in sends.indices {
                for f in sends[i].frames.indices where sends[i].frames[f].frameId == frameId {
                    sends[i].frames[f].online = online
                }
            }
        case .sendUpdated(let id):
            if sends.contains(where: { $0.id == id }) {
                refetch(id)
            } else {
                // A new send (from this or another device): reload the first page.
                Task { await load() }
            }
        case .joinRequest, .membershipUpdated:
            break
        }
    }

    func refetch(_ id: String) {
        refetches[id]?.cancel()
        let debounce = debounce
        refetches[id] = Task {
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled else { return }
            refetches[id] = nil
            if let fresh = try? await api.call({ try await $0.getSend(path: .init(sendId: id)).ok.body.json }),
               let i = sends.firstIndex(where: { $0.id == id }) {
                sends[i] = fresh
            }
        }
    }

    func replace(_ send: Send) {
        if let i = sends.firstIndex(where: { $0.id == send.id }) { sends[i] = send }
    }

    func remove(_ id: String) {
        sends.removeAll { $0.id == id }
    }
}

/// One send, live (web: history/[id]).
@MainActor
@Observable
final class SendDetailModel {
    private(set) var send: Send?
    var error: String?
    private(set) var savingCaption = false
    private let api: API
    let id: String

    init(api: API, id: String, send: Send? = nil) {
        self.api = api
        self.id = id
        self.send = send
    }

    func load() async {
        do {
            let id = id
            send = try await api.call { try await $0.getSend(path: .init(sendId: id)).ok.body.json }
            error = nil
        } catch is CancellationError {
        } catch {
            if send == nil { self.error = errorMessage(error) }
        }
    }

    func apply(_ event: LiveEvent) {
        guard var s = send else { return }
        switch event {
        case .reaction(let p, _, let r, _):
            if Delivery.apply(reaction: r, placementId: p, to: &s) { send = s }
        case .reply(let p, _, let preset):
            if Delivery.apply(reply: preset, placementId: p, to: &s) { send = s }
        case .sendUpdated(let sendId) where sendId == id:
            Task { await load() }
        case .frameStatus(let frameId, let online, _):
            for f in s.frames.indices where s.frames[f].frameId == frameId { s.frames[f].online = online }
            send = s
        default:
            break
        }
    }

    /// Edit a caption; optimistic, rolled back on failure.
    func saveCaption(itemId: String, caption: String) async throws {
        guard var s = send, let i = s.items.firstIndex(where: { $0.id == itemId }) else { return }
        let before = s.items[i]
        let text = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        s.items[i].caption = text.isEmpty ? nil : text
        send = s
        savingCaption = true
        defer { savingCaption = false }
        do {
            // An empty string clears the caption (the generated client omits nil).
            let updated = try await api.call { try await $0.updateItem(path: .init(itemId: itemId), body: .json(.init(caption: text))).ok.body.json }
            if var cur = send, let j = cur.items.firstIndex(where: { $0.id == itemId }) {
                cur.items[j] = updated
                send = cur
            }
        } catch {
            if var cur = send, let j = cur.items.firstIndex(where: { $0.id == itemId }) {
                cur.items[j] = before
                send = cur
            }
            throw error
        }
    }

    func unsend() async throws {
        let id = id
        _ = try await api.call { try await $0.unsend(path: .init(sendId: id)).noContent }
    }
}
