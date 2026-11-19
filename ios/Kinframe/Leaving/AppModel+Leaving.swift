import KinframeKit
import SwiftUI

extension AppModel {
    // MARK: Live events (README §6.2)

    func handleLeaving(_ e: LeavingEvent) {
        guard leavingEnabled else { return }
        switch e {
        case .placementUpdated(let frameId, let ids, let reason, _):
            // The push says the same to managers without an open websocket; here a toast with the way to the trash.
            guard reason == .takenAway, !ids.isEmpty, let f = frame(frameId), f.can(\.manageLibrary) else { break }
            toasts.show(Lp("leaving.live.takenAway", count: ids.count, ["frame": f.name]), action: .init(title: L("common.review")) { [weak self] in
                self?.openLeaving(.takenAway(frameId: frameId, feedId: nil, takingId: nil))
            })
        case .keepRequestUpdated(let r):
            if r.person?.userId == me?.id, r.status == .open, abs(r.askedAt.timeIntervalSinceNow) < 300 {
                toasts.show(LeavingText.requestToMe(r), action: .init(title: L("common.review")) { [weak self] in
                    self?.openLeaving(.keepRequest(requestId: r.id))
                })
            } else if r.askedBy?.userId == me?.id, r.status != .open {
                toasts.show(LeavingText.requestStatus(r, person: r.person.map { PersonName.display($0) } ?? "?"))
            }
        }
    }

    // MARK: Notifications (README §6.3)

    func openLeaving(_ route: LeavingRoute) {
        switch route {
        case .takenAway(let frameId, let feedId, _), .keepAnswered(_, let frameId, let feedId):
            if let frameId {
                leaving.trashFrameId = frameId
                tab = .frames
                framesPath = NavigationPath([FrameRoute(id: frameId, section: .photos)])
            } else if let feedId {
                openFeed(feedId, section: .members)
            }
        case .keepRequest(let requestId):
            tab = .history
            historyPath = NavigationPath()
            leaving.openRequestId = requestId
        case .ownership(let frameId, let feedId):
            if let frameId {
                Task { await loadFrames() }
                tab = .frames
                framesPath = NavigationPath([FrameRoute(id: frameId)])
            } else if let feedId {
                Task { await loadFeeds() }
                openFeed(feedId)
            }
        }
    }
}
