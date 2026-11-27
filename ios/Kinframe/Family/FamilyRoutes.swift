import KinframeKit
import SwiftUI

/// The composer (`+`), docs/spec/simple-app.md §3.2: the current family by default, or one frame only (from that
/// frame's screen), or a family with this week's prompt.
struct ComposeRequest: Identifiable, Equatable {
    let id = UUID()
    var frameId: String?
    var feedId: String?
    var promptId: String?
    /// The account the frame or family belongs to.
    var account: String?
}

/// The Family profile's segments (§3.4).
enum FamilySegment: String, Hashable, CaseIterable {
    case photos, people, frames

    var title: String {
        switch self {
        case .photos: L("simple.family.tabPhotos")
        case .people: L("simple.family.tabPeople")
        case .frames: L("simple.family.tabFrames")
        }
    }
}

/// A family's profile, pushed from the list of families (several families).
struct FamilyProfileRoute: Hashable {
    var feedId: String
    var account: String? = nil
}

/// A page of a family pushed in Family: its settings (the gear) and the pages behind them.
struct FamilyRoute: Hashable {
    enum Page: String, Hashable {
        /// Settings: This family and App.
        case settings
        /// Name, cover, approvals, ways to add photos, leave or delete.
        case details
        case circles
        case birthdays
        /// Leave the family.
        case leave
    }

    var feedId: String
    var page: Page
    var account: String? = nil
}

/// Settings › App › Sent history (the old History screen).
struct SentHistoryRoute: Hashable {
    var account: String? = nil
}

/// Settings › App › Account: profile, language, notifications, servers (the old Settings screen).
struct AppSettingsRoute: Hashable {
    var account: String? = nil
}

/// Settings › App › My photo sources, pushed after `AppSettingsRoute` (a photo source's notification).
struct MyPhotoSourcesRoute: Hashable {
    var account: String? = nil
}

/// A frame's screen (`page` nil) or one of the pages behind its rows (§3.3).
struct FrameRoute: Hashable {
    var id: String
    var page: FramePage? = nil
    /// Open this album in the photos (a photo source's notification).
    var albumId: String? = nil
    /// The account (`AccountRecord.id`) the screen talks to; nil is the primary account.
    var account: String? = nil
}

enum FramePage: String, Hashable, CaseIterable {
    /// Photos on this frame (the library, albums inside).
    case photos
    /// Calendar, weather and add-ons.
    case calendar
    /// Frame settings and help.
    case settings
    /// People and admins of the frame (inside settings).
    case people
}
