import AVKit
import KinframeKit
import SwiftUI
import UIKit
import WebKit

/// The external display (an Apple TV through Screen Mirroring, or a cable). It gets a window only while a TV session
/// runs; otherwise iOS mirrors the phone's screen as usual.
final class TvSceneDelegate: NSObject, UIWindowSceneDelegate {
    var window: UIWindow?
    private weak var scene: UIWindowScene?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        self.scene = scene
        MainActor.assumeIsolated {
            let cast = TvCast.shared
            cast.screenDidConnect(name: nil)
            cast.onChange = { [weak self] in self?.update() }
            update()
        }
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        MainActor.assumeIsolated {
            TvCast.shared.screenDidDisconnect()
            TvCast.shared.onChange = nil
        }
        window = nil
    }

    @MainActor
    private func update() {
        guard let scene else { return }
        if TvCast.shared.isLive {
            guard window == nil else { return }
            let w = UIWindow(windowScene: scene)
            w.rootViewController = UIHostingController(rootView: TvStageView(cast: .shared))
            w.backgroundColor = .black
            w.overrideUserInterfaceStyle = .dark
            w.isHidden = false
            window = w
        } else {
            window?.isHidden = true
            window = nil
        }
    }
}

/// Full screen on the TV: the slideshow (photo or video, caption and byline), or the frame's mirror page.
struct TvStageView: View {
    let cast: TvCast
    /// The mirror page didn't load (an ended link, or a server without the web app).
    @State private var pageFailed = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch cast.mode {
            case .mirror?:
                if let url = cast.mirrorURL {
                    if pageFailed {
                        VStack(spacing: 10) {
                            Text(L("tv.invalidTitle")).font(.system(size: 34, weight: .semibold, design: .serif))
                            Text(L("tv.invalidBody")).font(.title3).opacity(0.8).multilineTextAlignment(.center)
                        }
                        .minimumScaleFactor(0.4)
                        .foregroundStyle(.white)
                        .padding(48)
                    } else {
                        TvWebView(url: url) { pageFailed = true }.ignoresSafeArea()
                    }
                }
            case .show?:
                if let slide = cast.current {
                    TvSlideView(slide: slide, tick: cast.tick)
                        .id(cast.tick)
                        .transition(.opacity)
                    caption(slide)
                }
            case nil:
                ProgressView().tint(.white)
            }
            if cast.status == .connecting {
                ProgressView().tint(.white).controlSize(.large)
            }
        }
        .animation(.easeInOut(duration: 0.8), value: cast.tick)
        .environment(\.colorScheme, .dark)
    }

    private func caption(_ slide: TvSlide) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Spacer()
            if let caption = slide.caption, !caption.isEmpty {
                Text(caption).font(.system(size: 34, weight: .semibold, design: .serif))
            }
            Text(TvLogic.byline(slide, locale: L10n.locale)).font(.title3).opacity(0.85)
        }
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.6), radius: 8)
        .padding(48)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        .background(alignment: .bottom) {
            LinearGradient(colors: [.clear, .black.opacity(0.55)], startPoint: .top, endPoint: .bottom).frame(height: 260)
        }
        .ignoresSafeArea()
    }
}

/// One slide: a photo with its fit and focal point, or a video playing to the end.
private struct TvSlideView: View {
    let slide: TvSlide
    let tick: Int

    var body: some View {
        GeometryReader { geo in
            if slide.kind == .video, let url = URL(string: slide.url) {
                TvVideo(url: url)
            } else {
                AsyncImage(url: URL(string: slide.url)) { phase in
                    if let image = phase.image {
                        let fill = slide.fit != .fit
                        ZStack {
                            if !fill {
                                image.resizable().scaledToFill().blur(radius: 40).opacity(0.5)
                                    .frame(width: geo.size.width, height: geo.size.height).clipped()
                            }
                            image.resizable()
                                .aspectRatio(contentMode: fill ? .fill : .fit)
                                .frame(width: geo.size.width, height: geo.size.height, alignment: alignment)
                                .clipped()
                        }
                    } else if phase.error != nil {
                        Image(systemName: "photo").font(.largeTitle).foregroundStyle(.white.opacity(0.4))
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
            }
        }
        .ignoresSafeArea()
    }

    /// Where a filled photo is anchored, from its focal point.
    private var alignment: Alignment {
        guard let f = slide.focalPoint else { return .center }
        let h: HorizontalAlignment = f.x < 0.34 ? .leading : f.x > 0.66 ? .trailing : .center
        let v: VerticalAlignment = f.y < 0.34 ? .top : f.y > 0.66 ? .bottom : .center
        return Alignment(horizontal: h, vertical: v)
    }
}

/// A video on the TV, with sound, from its signed URL.
private struct TvVideo: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.showsPlaybackControls = false
        vc.videoGravity = .resizeAspect
        vc.player = AVPlayer(url: url)
        vc.player?.play()
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ vc: AVPlayerViewController, coordinator: ()) {
        vc.player?.pause()
        vc.player = nil
    }
}

/// The frame's live mirror page (the web's TV page), on the external screen.
struct TvWebView: UIViewRepresentable {
    let url: URL
    var failed: () -> Void = {}

    func makeCoordinator() -> Coordinator { Coordinator(failed: failed) }

    final class Coordinator: NSObject, WKNavigationDelegate {
        let failed: () -> Void
        init(failed: @escaping () -> Void) { self.failed = failed }

        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse) async -> WKNavigationResponsePolicy {
            if let http = navigationResponse.response as? HTTPURLResponse, http.statusCode >= 400, navigationResponse.isForMainFrame {
                await MainActor.run { failed() }
                return .cancel
            }
            return .allow
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            failed()
        }
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let view = WKWebView(frame: .zero, configuration: config)
        view.isOpaque = false
        view.backgroundColor = .black
        view.scrollView.isScrollEnabled = false
        view.navigationDelegate = context.coordinator
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        if view.url != url, !view.isLoading { view.load(URLRequest(url: url)) }
    }
}
