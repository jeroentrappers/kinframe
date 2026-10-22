import KinframeKit
import SwiftUI

struct RoleBadge: View {
    let role: Role

    var body: some View {
        switch role {
        case .owner: Pill(L("role.owner"), foreground: Palette.primarySoftText, background: Palette.primarySoft)
        case .admin: Pill(L("role.admin"), foreground: Palette.accentText, background: Palette.accentSoft)
        case .member: Pill(L("role.member"))
        }
    }
}

/// "Online", or "Last seen 5 minutes ago".
struct OnlineStatus: View {
    let online: Bool
    let lastSeen: Date?

    var body: some View {
        HStack(spacing: 8) {
            StatusDot(online: online)
            Text(text)
                .font(.subheadline)
                .foregroundStyle(online ? Palette.accentText : Palette.textMuted)
        }
        .accessibilityElement(children: .combine)
    }

    var text: String {
        if online { return L("frame.online") }
        guard let lastSeen else { return L("frame.offline") }
        return L("frame.lastSeen", ["when": Format.relative(lastSeen, locale: L10n.locale)])
    }
}

struct EmptyStateView<Actions: View>: View {
    let systemImage: String?
    let title: String
    let message: String
    @ViewBuilder var actions: Actions

    init(systemImage: String? = nil, title: String, message: String, @ViewBuilder actions: () -> Actions = { EmptyView() }) {
        self.systemImage = systemImage
        self.title = title
        self.message = message
        self.actions = actions()
    }

    var body: some View {
        VStack(spacing: 14) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(Palette.primarySoftText)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(Palette.primarySoft))
                    .accessibilityHidden(true)
            } else {
                FrameIllustration().frame(height: 110)
            }
            Text(title)
                .font(.display(.title2))
                .foregroundStyle(Palette.text)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(message)
                .foregroundStyle(Palette.textMuted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            actions.padding(.top, 6)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 32)
        .frame(maxWidth: .infinity)
    }
}

/// Initials or the avatar image.
struct AvatarView: View {
    let name: String?
    let url: String?
    var size: CGFloat = 36

    var body: some View {
        ZStack {
            Circle().fill(Palette.tint(for: name ?? "?").1)
            Text(Format.initials(name))
                .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.text)
            if let url, let u = URL(string: url) {
                AsyncImage(url: u) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() }
                }
                .clipShape(Circle())
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A section heading inside scrolling pages (web: .section-title).
struct SectionHeader: View {
    let title: String
    var trailing: AnyView?

    init(_ title: String, trailing: AnyView? = nil) {
        self.title = title
        self.trailing = trailing
    }

    var body: some View {
        HStack {
            Text(title).font(.display(.title3)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
            Spacer()
            trailing
        }
        .padding(.top, 8)
    }
}

/// A thumbnail from a signed URL, with a quiet placeholder while loading.
struct RemoteThumb: View {
    let url: String?
    var kind: Components.Schemas.MediaKind? = nil
    var cornerRadius: CGFloat = 0

    var body: some View {
        ZStack {
            Rectangle().fill(Palette.surface2)
            if let url, let u = URL(string: url) {
                AsyncImage(url: u, transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
                    switch phase {
                    case .success(let image): image.resizable().scaledToFill()
                    case .failure: Image(systemName: "photo").foregroundStyle(Palette.textFaint)
                    default: Color.clear
                    }
                }
            } else {
                Image(systemName: kind == .video ? "video" : "photo").foregroundStyle(Palette.textFaint)
            }
            if kind == .video {
                Image(systemName: "play.fill")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(6)
                    .background(Circle().fill(.black.opacity(0.45)))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                    .padding(6)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .accessibilityHidden(true)
    }
}

struct LoadingView: View {
    var body: some View {
        ProgressView()
            .controlSize(.large)
            .frame(maxWidth: .infinity, minHeight: 200)
            .accessibilityLabel(L("common.loading"))
    }
}

struct ErrorRetryView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Callout(message, kind: .error)
            Button(L("common.tryAgain"), action: retry).buttonStyle(.kf(.secondary))
        }
        .padding(.vertical, 12)
    }
}
