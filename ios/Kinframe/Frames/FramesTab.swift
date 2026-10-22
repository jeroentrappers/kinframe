import KinframeKit
import SwiftUI

struct FramesTab: View {
    @Environment(AppModel.self) private var app
    @State private var adding = false

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.framesPath) {
            ScrollView {
                LazyVStack(spacing: 16) {
                    if !app.framesLoaded, app.frames.isEmpty {
                        if let error = app.framesError {
                            ErrorRetryView(message: error) { Task { await app.loadFrames() } }
                        } else {
                            ForEach(0..<2, id: \.self) { _ in FrameCardSkeleton() }
                        }
                    } else if app.frames.isEmpty {
                        EmptyStateView(title: L("frames.emptyTitle"), message: L("frames.emptyBody")) {
                            Button(L("frames.emptyCta")) { adding = true }
                                .buttonStyle(.kf(.primary, large: true))
                        }
                        .card(padding: 8)
                    } else {
                        Text(summary)
                            .font(.subheadline)
                            .foregroundStyle(Palette.textMuted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, -8)
                        ForEach(app.frames) { frame in
                            NavigationLink(value: FrameRoute(id: frame.id)) {
                                FrameCard(frame: frame)
                            }
                            .buttonStyle(CardButtonStyle())
                            .accessibilityHint(L("frames.open", ["name": frame.name]))
                        }
                    }
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .refreshable { await app.loadFrames() }
            .pageBackground()
            .navigationTitle(L("frames.title"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        adding = true
                    } label: {
                        Image(systemName: "plus").font(.body.weight(.semibold))
                    }
                    .accessibilityLabel(L("frames.add"))
                }
            }
            .navigationDestination(for: FrameRoute.self) { route in
                FrameDetailView(route: route)
            }
            .sheet(isPresented: $adding) {
                if let api = app.api {
                    NavigationStack { AddFrameView(api: api) }
                }
            }
        }
    }

    private var summary: String {
        Lp("frames.summary", count: app.frames.count, ["online": app.frames.filter(\.online).count])
    }
}

struct CardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// A frame in the list: a calm tinted cover with a miniature frame, then name, role, place and status.
struct FrameCard: View {
    @Environment(AppModel.self) private var app
    let frame: FrameSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            cover
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text(frame.name)
                        .font(.display(.title3))
                        .foregroundStyle(Palette.text)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    RoleBadge(role: frame.role)
                }
                if let location = frame.location, !location.isEmpty {
                    Label(location, systemImage: "mappin")
                        .font(.subheadline)
                        .foregroundStyle(Palette.textMuted)
                        .labelStyle(TightLabelStyle())
                }
                HStack {
                    OnlineStatus(online: frame.online, lastSeen: frame.lastSeenAt)
                    Spacer()
                    if frame.can(\.send) {
                        Button {
                            app.composeFrameIds = [frame.id]
                            app.tab = .send
                        } label: {
                            Label(L("nav.send"), systemImage: "paperplane")
                        }
                        .buttonStyle(.kf(.soft))
                    }
                }
                if frame.needsUpdate == true {
                    Label(L("frames.needsUpdate"), systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(Palette.warn)
                }
            }
            .padding(16)
        }
        .card(padding: 0)
        .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    private var cover: some View {
        let (a, b) = Palette.tint(for: frame.id)
        return ZStack(alignment: .topTrailing) {
            RadialGradient(colors: [a, b], center: UnitPoint(x: 0.2, y: 0), startRadius: 0, endRadius: 420)
            FrameIllustration(portrait: frame.screen?.orientation == .portrait)
                .frame(height: 74)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            if frame.online {
                HStack(spacing: 6) {
                    StatusDot(online: true)
                    Text(L("frame.online")).font(.caption.weight(.semibold)).foregroundStyle(Palette.text)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Capsule().fill(Palette.surface.opacity(0.9)))
                .padding(10)
                .accessibilityHidden(true)
            }
        }
        .frame(height: 128)
        .clipped()
    }
}

struct TightLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 5) {
            configuration.icon.font(.footnote)
            configuration.title
        }
    }
}

private struct FrameCardSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Rectangle().fill(Palette.surface2).frame(height: 128)
            RoundedRectangle(cornerRadius: 6).fill(Palette.surface2).frame(width: 160, height: 18).padding(.horizontal, 16)
            RoundedRectangle(cornerRadius: 6).fill(Palette.surface2).frame(width: 110, height: 14).padding([.horizontal, .bottom], 16)
        }
        .card(padding: 0)
        .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .redacted(reason: .placeholder)
        .accessibilityLabel(L("common.loading"))
    }
}
