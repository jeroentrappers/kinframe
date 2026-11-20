import KinframeKit
import SwiftUI

/// A part of a family a notification or toast opens (Family's segments and settings pages).
enum FeedSection: String, Hashable, CaseIterable {
    case posts, members, frames, birthdays, settings
    /// Family social: the family's circles.
    case circles

    init(_ hint: PushRoute.FeedSectionHint) {
        switch hint {
        case .posts: self = .posts
        case .members: self = .members
        case .frames: self = .frames
        case .birthdays: self = .birthdays
        case .settings: self = .settings
        }
    }
}

/// A feed's cover (one of its own photos), or its initials on a warm tint.
struct FeedCover: View {
    let url: String?
    let name: String
    var size: CGFloat = 56
    /// Round (the family profile, §3.4) rather than a rounded square.
    var round = false

    private var corner: CGFloat { round ? size / 2 : size * 0.22 }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: corner, style: .continuous).fill(Palette.tint(for: name).1)
            Text(Format.initials(name))
                .font(.system(size: size * 0.34, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.text)
            if let url, let u = URL(string: url) {
                AsyncImage(url: u) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() }
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        .accessibilityHidden(true)
    }
}

struct FeedRoleBadge: View {
    let role: FeedRole

    var body: some View {
        switch role {
        case .owner: Pill(L("role.owner"), foreground: Palette.primarySoftText, background: Palette.primarySoft)
        case .admin: Pill(L("role.admin"), foreground: Palette.accentText, background: Palette.accentSoft)
        case .poster: Pill(L("ios.feeds.rolePoster"))
        case .member: Pill(L("social.role.member"))
        }
    }
}
