import AVFoundation
import KinframeKit
import SwiftUI

extension View {
    /// Plays a Live Photo's motion clip, muted, while the view is pressed (like the Photos app). Does nothing for other
    /// items.
    func livePlayback(api: API?, item: Item) -> some View {
        modifier(LivePlayback(api: api, itemId: item.id, enabled: item.kind == .live && item.motion?.status != .processing))
    }
}

private struct LivePlayback: ViewModifier {
    let api: API?
    let itemId: String
    let enabled: Bool
    @State private var player: AVPlayer?
    @State private var pressing = false
    @State private var load: Task<Void, Never>?

    func body(content: Content) -> some View {
        if enabled, let api {
            content
                .overlay {
                    if let player {
                        PlayerLayerView(player: player)
                            .allowsHitTesting(false)
                            .transition(.opacity)
                    }
                }
                .onLongPressGesture(minimumDuration: 0.25, maximumDistance: 30) {
                } onPressingChanged: { down in
                    pressing = down
                    if down { start(api) } else { stop() }
                }
                .accessibilityAction(named: Text(L("ios.live.play"))) { start(api) }
                .onDisappear { stop() }
        } else {
            content
        }
    }

    private func start(_ api: API) {
        load?.cancel()
        load = Task {
            guard let asset = try? await MotionClip.asset(api: api, itemId: itemId), !Task.isCancelled else { return }
            let p = AVPlayer(playerItem: AVPlayerItem(asset: asset))
            p.isMuted = true
            p.actionAtItemEnd = .pause
            withAnimation(.easeOut(duration: 0.15)) { player = p }
            p.play()
            // Back to the still once the clip has played.
            for await _ in NotificationCenter.default.notifications(named: AVPlayerItem.didPlayToEndTimeNotification, object: p.currentItem) {
                break
            }
            if !pressing { stop() }
        }
    }

    private func stop() {
        load?.cancel()
        load = nil
        player?.pause()
        withAnimation(.easeOut(duration: 0.2)) { player = nil }
    }
}

/// An AVPlayerLayer without controls.
private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerUIView {
        let view = PlayerUIView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: PlayerUIView, context: Context) {
        view.playerLayer.player = player
    }

    final class PlayerUIView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}
