import CoreGraphics
import Foundation

/// Zoom and position per screen orientation (docs/spec/framing.md). `Framing` (no prefix) is the focal point maths
/// in ComposeRules.swift; these are the API's objects.
public typealias ItemFraming = Components.Schemas.Framing
public typealias FramingView = Components.Schemas.FramingView

/// A screen's orientation for picking a view: landscape when width ≥ height (a square screen is landscape, §2).
public enum ScreenOrientation: String, CaseIterable, Identifiable, Sendable {
    case landscape, portrait

    public var id: String { rawValue }

    public init(width: Double, height: Double) {
        self = width >= height ? .landscape : .portrait
    }

    public init(_ size: CGSize) {
        self.init(width: size.width, height: size.height)
    }
}

extension Components.Schemas.Framing {
    public subscript(_ o: ScreenOrientation) -> FramingView? {
        get { o == .landscape ? landscape : portrait }
        set { if o == .landscape { landscape = newValue } else { portrait = newValue } }
    }

    /// Neither view set.
    public var isEmpty: Bool { landscape == nil && portrait == nil }
}

extension Framing {
    public static let minZoom = 1.0
    public static let maxZoom = 4.0
    /// The master rendition's long edge (§3.3) and the visible width under which the editor warns (§4).
    public static let masterLongEdge = 2560.0
    public static let sharpPixels = 960.0

    /// A view chosen for a screen and whether the person chose it (§3.1 steps 1 and 3), which makes the frame fill
    /// (§3.2) and keeps Ken Burns inside it (§3.4).
    public struct Choice: Equatable, Sendable {
        public var view: FramingView
        public var chosen: Bool

        public init(view: FramingView, chosen: Bool) {
            self.view = view
            self.chosen = chosen
        }
    }

    /// The view a screen of orientation `o` uses (§3.1): the placement's framing override, its focal override at
    /// 1×, the item's framing, the item's focal point at 1×, else the centre.
    public static func view(for o: ScreenOrientation, framingOverride: ItemFraming? = nil, focalOverride: FocalPoint? = nil,
                            framing: ItemFraming?, focal: FocalPoint?) -> Choice {
        if let v = framingOverride?[o] { return Choice(view: v, chosen: true) }
        if let f = focalOverride { return Choice(view: FramingView(x: f.x, y: f.y, zoom: 1), chosen: false) }
        if let v = framing?[o] { return Choice(view: v, chosen: true) }
        if let f = focal { return Choice(view: FramingView(x: f.x, y: f.y, zoom: 1), chosen: false) }
        return Choice(view: FramingView(x: 0.5, y: 0.5, zoom: 1), chosen: false)
    }

    /// The part of the photo drawn to fill the screen (§3), in the photo's own units: `screen` W×H, `photo` w×h
    /// (after rotation), view {x, y, zoom}. With zoom 1 and the focal point this is the plain cover crop.
    public static func cropRect(screen: CGSize, photo: CGSize, view: FramingView) -> CGRect {
        let w = Double(photo.width), h = Double(photo.height)
        let sw = Double(screen.width), sh = Double(screen.height)
        guard w > 0, h > 0, sw > 0, sh > 0 else { return CGRect(x: 0, y: 0, width: max(0, w), height: max(0, h)) }
        let zoom = clampZoom(view.zoom)
        let s = max(sw / w, sh / h)
        let cw = min(w, sw / (s * zoom))
        let ch = min(h, sh / (s * zoom))
        let left = min(max(view.x * w - cw / 2, 0), w - cw)
        let top = min(max(view.y * h - ch / 2, 0), h - ch)
        return CGRect(x: left, y: top, width: cw, height: ch)
    }

    /// `cropRect` as fractions of the photo (0…1 from its top-left), for drawing at any size.
    public static func unitCrop(screenAspect: Double, photoAspect: Double, view: FramingView) -> CGRect {
        let a = max(0.01, photoAspect)
        let r = cropRect(screen: CGSize(width: max(0.01, screenAspect), height: 1), photo: CGSize(width: a, height: 1), view: view)
        return CGRect(x: r.minX / a, y: r.minY, width: r.width / a, height: r.height)
    }

    public static func clampZoom(_ z: Double) -> Double {
        guard z.isFinite else { return minZoom }
        return min(maxZoom, max(minZoom, z))
    }

    /// A view as the editor keeps it: zoom in 1…4 and the centre moved so the window stays on the photo (the §3
    /// clamp), rounded to 3 decimals like the server does.
    public static func clamped(_ v: FramingView, screenAspect: Double, photoAspect: Double) -> FramingView {
        let zoom = clampZoom(v.zoom)
        let r = unitCrop(screenAspect: screenAspect, photoAspect: photoAspect, view: FramingView(x: v.x, y: v.y, zoom: zoom))
        func round3(_ d: Double) -> Double { (min(1, max(0, d)) * 1000).rounded() / 1000 }
        return FramingView(x: round3(Double(r.midX)), y: round3(Double(r.midY)), zoom: (zoom * 1000).rounded() / 1000)
    }

    /// Moves the centre by a fraction of the visible part (accessibility: 10 %, §4), clamped.
    public static func moved(_ v: FramingView, dx: Double, dy: Double, screenAspect: Double, photoAspect: Double) -> FramingView {
        let r = unitCrop(screenAspect: screenAspect, photoAspect: photoAspect, view: v)
        let start = FramingView(x: Double(r.midX), y: Double(r.midY), zoom: v.zoom)
        return clamped(FramingView(x: start.x + dx * Double(r.width), y: start.y + dy * Double(r.height), zoom: v.zoom),
                       screenAspect: screenAspect, photoAspect: photoAspect)
    }

    /// Tapping the focal point moves both views' centres there and keeps their zoom (§4).
    public static func recentred(_ f: ItemFraming?, at p: CGPoint) -> ItemFraming? {
        guard var f else { return nil }
        for o in ScreenOrientation.allCases {
            if let v = f[o] { f[o] = FramingView(x: Double(p.x), y: Double(p.y), zoom: v.zoom) }
        }
        return f
    }

    /// The framing to send: only the orientations the person changed, nil when none (§4).
    public static func sendable(_ f: ItemFraming?) -> ItemFraming? {
        guard let f, !f.isEmpty else { return nil }
        return f
    }

    /// The focal point to send with a framing: the landscape view's centre, else the portrait one's, else the
    /// tapped point (§2.1, §4).
    public static func focal(for f: ItemFraming?, tapped: CGPoint) -> CGPoint {
        if let v = f?.landscape ?? f?.portrait { return clampFocal(CGPoint(x: v.x, y: v.y)) }
        return clampFocal(tapped)
    }

    /// The soft warning under the editor (§4): fewer than 960 master pixels across the screen's long side. The
    /// master is the photo scaled down to a 2560 px long edge (never up).
    public static func mayLookSoft(photoWidth: Int, photoHeight: Int, screenAspect: Double, zoom: Double) -> Bool {
        guard photoWidth > 0, photoHeight > 0, screenAspect > 0 else { return false }
        let w = Double(photoWidth), h = Double(photoHeight)
        let m = min(1, masterLongEdge / max(w, h))
        let r = cropRect(screen: CGSize(width: screenAspect, height: 1), photo: CGSize(width: w * m, height: h * m),
                         view: FramingView(x: 0.5, y: 0.5, zoom: zoom))
        let across = screenAspect >= 1 ? r.width : r.height
        return Double(across) < sharpPixels
    }

    /// "Landscape: 2×, centred 40%, 30%" (the editor's accessibility value, §4).
    public static func zoomText(_ zoom: Double) -> String {
        let z = (clampZoom(zoom) * 10).rounded() / 10
        return z == z.rounded() ? "\(Int(z))×" : String(format: "%.1f×", z)
    }
}
