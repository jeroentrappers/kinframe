import KinframeKit
import SwiftUI

/// A post's thread to open in Home (from a notification, a shared post card, a mention).
struct PostRoute: Hashable {
    var postId: String
    var commentId: String? = nil
}

/// A conversation to open in Chats.
struct ChatRoute: Hashable {
    var id: String
    var messageId: String? = nil
}

/// A contact's profile.
struct PersonRoute: Hashable, Identifiable {
    var userId: String
    var id: String { userId }
}

/// A circle of a family.
struct CircleRoute: Hashable {
    var feedId: String
    var circleId: String
}

/// Sheets family social opens from anywhere (notifications, toasts, the Home strip).
enum SocialSheet: Identifiable, Equatable {
    /// "Send something to Oma's frame".
    case freshSend(frameId: String, requestId: String?)
    case weeklySummary(weekOf: String)
    case operatorReports
    case feedReports(feedId: String)

    var id: String {
        switch self {
        case .freshSend(let f, _): "fresh-\(f)"
        case .weeklySummary(let w): "summary-\(w)"
        case .operatorReports: "operator-reports"
        case .feedReports(let f): "reports-\(f)"
        }
    }
}

extension AppModel {
    // MARK: Live events

    func handleSocial(_ e: SocialEvent) {
        social.handle(e)
        switch e {
        case .photoRequestCreated(let r):
            setPhotoRequest(r)
            guard r.state == .open else { break }
            toasts.show(L("engagement.request.toast", ["frame": r.frameName]), action: .init(title: L("engagement.request.send")) { [weak self] in
                self?.social.sheet = .freshSend(frameId: r.frameId, requestId: r.id)
            })
        case .photoRequestUpdated(let r):
            setPhotoRequest(r)
        case .streakUpdated(let feedId, let s):
            if let i = feeds.firstIndex(where: { $0.id == feedId }) { feeds[i].streak = s }
        case .milestoneAchieved(let m):
            toasts.show(L("engagement.milestone.toast", ["title": m.key.title]))
        case .reportCreated(let r):
            toasts.show(L("chat.report.toastNew"), action: .init(title: L("common.review")) { [weak self] in
                if let feedId = r?.feedId { self?.social.sheet = .feedReports(feedId: feedId) } else { self?.social.sheet = .operatorReports }
            })
        case .conversationRemoved(let id):
            if chatsPath.count > 0, social.openConversationId == id { chatsPath = NavigationPath() }
        default:
            break
        }
    }

    private func setPhotoRequest(_ r: PhotoRequest) {
        guard let i = frames.firstIndex(where: { $0.id == r.frameId }) else { return }
        frames[i].photoRequest = r.state == .expired ? nil : r
        if let f = r.freshness { frames[i].freshness = f }
    }

    // MARK: Routes

    /// Where a tapped family-social notification (or a link inside the app) leads.
    func openSocial(_ route: SocialRoute) {
        let f = social.features
        switch route {
        case .post(let postId, let commentId):
            guard f.social else { return }
            tab = .home
            homePath = NavigationPath([PostRoute(postId: postId, commentId: commentId)])
        case .conversation(let id, let messageId):
            guard f.chats else { return }
            tab = .chats
            chatsPath = NavigationPath([ChatRoute(id: id, messageId: messageId)])
        case .reports(let feedId):
            social.sheet = feedId.map { .feedReports(feedId: $0) } ?? .operatorReports
        case .frameNudge(let frameId, let requestId):
            if f.home { tab = .home }
            social.sheet = .freshSend(frameId: frameId, requestId: requestId)
        case .feedStreak(let feedId):
            openFeed(feedId)
        case .birthdayReminder(let feedId, _):
            openFeed(feedId, section: birthdaysEnabled ? .birthdays : .posts)
        case .weeklySummary(let weekOf):
            if f.home { tab = .home }
            social.sheet = .weeklySummary(weekOf: weekOf ?? WeekOf.previousMonday())
        case .home:
            if f.home {
                tab = .home
                homePath = NavigationPath()
            }
        }
    }

    /// Open a chat (after starting one, or from a profile or a family).
    func openChat(_ id: String) {
        guard social.features.chats else { return }
        tab = .chats
        chatsPath = NavigationPath([ChatRoute(id: id)])
    }

    /// Start (or find) the direct chat with someone and open it.
    func startDirectChat(with userId: String) async {
        guard let api else { return }
        do {
            let c = try await api.createConversation(direct: userId)
            social.update(c)
            openChat(c.id)
        } catch {
            toasts.error(error)
        }
    }

    /// The caller's role in a family, when they are in it.
    func feedRole(_ feedId: String?) -> FeedRole? {
        feedId.flatMap { feed($0)?.role }
    }
}
