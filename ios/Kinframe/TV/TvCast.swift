import KinframeKit
import SwiftUI
import UIKit

/// Showing a frame's photos on a TV over AirPlay (web: tvCast.svelte.ts). The phone is mirrored to an Apple TV
/// (Screen Mirroring); while a session runs, the external display gets its own window with the slideshow (or, for a
/// mirror, the frame's live TV page) and this phone is the remote. Without a session the app creates no window there,
/// so iOS simply mirrors the screen.
@MainActor
@Observable
final class TvCast {
    static let shared = TvCast()

    enum Status: Equatable {
        case idle, connecting, showing
    }

    enum Mode: Equatable {
        case show, mirror
    }

    private(set) var status: Status = .idle
    private(set) var mode: Mode?
    private(set) var frameId = ""
    private(set) var frameName = ""
    private(set) var slides: [TvSlide] = []
    private(set) var index = 0
    private(set) var playing = true
    private(set) var interval = TvLogic.defaultInterval
    /// The mirror's TV page, shown in a web view on the external screen.
    private(set) var mirrorURL: URL?
    /// An external display (AirPlay or a cable) is connected.
    private(set) var screenConnected = false
    /// The external screen's name, when the system knows it.
    private(set) var screenName: String?
    /// Bumped on every slide change, so videos restart.
    private(set) var tick = 0

    private var api: API?
    private var link: TvLink?
    private var show: TvSlideshow?
    private var timer: Task<Void, Never>?
    /// Called when a session starts or stops, so the external scene adds or removes its window.
    var onChange: (() -> Void)?

    var current: TvSlide? { slides.indices.contains(index) ? slides[index] : nil }
    var linkId: String? { link?.id }
    var isLive: Bool { status != .idle }

    // MARK: External screen

    func screenDidConnect(name: String?) {
        screenConnected = true
        screenName = name
    }

    func screenDidDisconnect() {
        screenConnected = false
        screenName = nil
    }

    // MARK: Sessions

    /// Show `scope` of a frame at its own pace, from a `cast` link that ends with the session.
    func start(api: API, frameId: String, frameName: String, scope: TvScope, interval: Int) async throws {
        await stop()
        self.api = api
        self.frameId = frameId
        self.frameName = frameName
        self.interval = interval
        mode = .show
        playing = true
        status = .connecting
        do {
            let body = TvLogic.linkBody(scope: scope, interval: interval, purpose: .cast)
            let link = try await api.call { try await $0.createTvLink(path: .init(frameId: frameId), body: .json(body)).created.body.json }
            self.link = link
            let show = try await fetch(link.token)
            guard !show.slides.isEmpty else { throw APIError(status: 404, code: "not_found") }
            apply(show, index: 0)
            status = .showing
            onChange?()
            schedule()
        } catch {
            await stop()
            throw error
        }
    }

    /// The TV shows what the frame shows, live: the frame's mirror page on the external screen.
    func startMirror(api: API, frameId: String, frameName: String) async throws {
        await stop()
        self.api = api
        self.frameId = frameId
        self.frameName = frameName
        mode = .mirror
        status = .connecting
        do {
            let body = TvLogic.linkBody(scope: .all, interval: TvLogic.defaultInterval, purpose: .mirror)
            let link = try await api.call { try await $0.createTvLink(path: .init(frameId: frameId), body: .json(body)).created.body.json }
            self.link = link
            mirrorURL = TvLogic.pageURL(link, web: nil)
            status = .showing
            onChange?()
        } catch {
            await stop()
            throw error
        }
    }

    func stop() async {
        timer?.cancel()
        timer = nil
        let link = link, api = api, frameId = frameId
        self.link = nil
        show = nil
        slides = []
        index = 0
        mirrorURL = nil
        mode = nil
        let wasLive = status != .idle
        status = .idle
        if wasLive { onChange?() }
        // The session's link was only for this session.
        if let link, let api {
            _ = try? await api.call { try await $0.revokeTvLink(path: .init(frameId: frameId, linkId: link.id)).noContent }
        }
    }

    func next() { go(1) }
    func previous() { go(-1) }

    func toggle() {
        playing.toggle()
        if playing { schedule() } else { timer?.cancel() }
    }

    func setInterval(_ seconds: Int) {
        interval = seconds
        if playing { schedule() }
    }

    // MARK: Slides

    private func fetch(_ token: String) async throws -> TvSlideshow {
        guard let api else { throw APIError.unexpected }
        return try await api.call { try await $0.getTvSlideshow(path: .init(token: token)).ok.body.json }
    }

    private func apply(_ show: TvSlideshow, index: Int) {
        self.show = show
        frameName = show.frameName
        slides = show.slides
        self.index = min(max(0, index), max(0, show.slides.count - 1))
        tick += 1
    }

    private func go(_ dir: Int) {
        guard !slides.isEmpty else { return }
        index = TvLogic.step(index, count: slides.count, by: dir)
        tick += 1
        Task { await refreshIfNeeded() }
        if playing { schedule() }
    }

    /// Signed URLs expire: refetch after `refresh_after`, staying on the same photo.
    private func refreshIfNeeded() async {
        guard let show, let link, TvLogic.needsRefresh(show) else { return }
        guard let fresh = try? await fetch(link.token) else { return }
        let place = TvLogic.keepPlace(previous: current?.placementId, in: fresh.slides, fallback: index)
        apply(fresh, index: place)
    }

    private func schedule() {
        timer?.cancel()
        guard playing, mode == .show, !slides.isEmpty else { return }
        let wait = TvLogic.dwell(current, interval: interval)
        timer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait))
            guard !Task.isCancelled else { return }
            self?.next()
        }
    }
}
