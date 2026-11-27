import KinframeKit
import SwiftUI

/// A photo drawn so that `crop` (fractions of the photo, docs/spec/framing.md §3) fills the view.
struct CroppedPhoto: View {
    let image: Image
    let crop: CGRect

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width / max(0.0001, crop.width)
            let h = geo.size.height / max(0.0001, crop.height)
            image.resizable()
                .frame(width: w, height: h)
                .offset(x: -crop.minX * w, y: -crop.minY * h)
                .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
        }
        .clipped()
    }
}

/// Zoom and position for one screen orientation (docs/spec/framing.md §4): the frame shape as a window over the
/// photo. Drag to move, pinch or the slider to zoom 1×–4×; the window can't leave the photo (§3). Reset removes the
/// view (the focal point at 1×), Done keeps it.
struct FramingEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    let image: UIImage?
    let photoAspect: Double
    /// The window's shape: the preview's 16:10 (or the frames' own) landscape or portrait shape.
    let boxAspect: Double
    let orientation: ScreenOrientation
    /// The photo's size, for the sharpness warning.
    let pixelSize: CGSize?
    let focal: CGPoint
    let initial: FramingView?
    let onDone: (FramingView?) -> Void

    @State private var view: FramingView?
    @State private var loaded = false
    @State private var dragStart: FramingView?
    @State private var pinchStart: Double?

    /// The view being edited, or the focal point at 1× when there is none.
    private var current: FramingView {
        Framing.clamped(view ?? FramingView(x: focal.x, y: focal.y, zoom: 1), screenAspect: boxAspect, photoAspect: photoAspect)
    }

    private var name: String { orientation == .landscape ? L("send.landscape") : L("send.portrait") }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    window
                    zoomSlider
                    if let s = pixelSize, Framing.mayLookSoft(photoWidth: Int(s.width), photoHeight: Int(s.height), screenAspect: boxAspect, zoom: current.zoom) {
                        Callout(L("ios.framing.soft"), kind: .warn)
                    }
                    Text(L("ios.framing.editorHint"))
                        .font(.footnote)
                        .foregroundStyle(Palette.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                    Button(L("ios.framing.reset")) { set(nil) }
                        .buttonStyle(.kf(.ghost))
                        .disabled(view == nil)
                }
                .padding(20)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .pageBackground()
            .pageTitle(orientation == .landscape ? L("ios.framing.editLandscape") : L("ios.framing.editPortrait"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("common.done")) {
                        onDone(view)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .presentationDragIndicator(.visible)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            view = initial.map { Framing.clamped($0, screenAspect: boxAspect, photoAspect: photoAspect) }
        }
    }

    private var window: some View {
        GeometryReader { geo in
            let size = fitted(in: geo.size)
            ZStack {
                Color.black
                if let image {
                    CroppedPhoto(image: Image(uiImage: image),
                                 crop: Framing.unitCrop(screenAspect: boxAspect, photoAspect: photoAspect, view: current))
                } else {
                    ProgressView().tint(.white)
                }
            }
            .frame(width: size.width, height: size.height)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .padding(8)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(red: 0.16, green: 0.13, blue: 0.11)))
            .shadow(color: .black.opacity(0.18), radius: 8, y: 4)
            .contentShape(Rectangle())
            .gesture(drag(size).simultaneously(with: pinch))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(height: orientation == .landscape ? 300 : 420)
        .accessibilityElement()
        .accessibilityLabel(orientation == .landscape ? L("ios.framing.editLandscape") : L("ios.framing.editPortrait"))
        .accessibilityValue(L("ios.framing.value", ["orientation": name, "zoom": Framing.zoomText(current.zoom),
                                                    "x": Int((current.x * 100).rounded()), "y": Int((current.y * 100).rounded())]))
        .accessibilityHint(L("ios.framing.editorHint"))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: zoom(by: 0.5)
            case .decrement: zoom(by: -0.5)
            @unknown default: break
            }
        }
        .accessibilityAction(named: L("ios.framing.zoomIn")) { zoom(by: 0.5) }
        .accessibilityAction(named: L("ios.framing.zoomOut")) { zoom(by: -0.5) }
        .accessibilityAction(named: L("ios.focal.left")) { move(-0.1, 0) }
        .accessibilityAction(named: L("ios.focal.right")) { move(0.1, 0) }
        .accessibilityAction(named: L("ios.focal.up")) { move(0, -0.1) }
        .accessibilityAction(named: L("ios.focal.down")) { move(0, 0.1) }
        .accessibilityAction(named: L("ios.framing.reset")) { set(nil) }
    }

    private var zoomSlider: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(L("ios.framing.zoom")).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                Spacer()
                Text(Framing.zoomText(current.zoom)).font(.subheadline.monospacedDigit()).foregroundStyle(Palette.textMuted)
            }
            Slider(value: Binding(get: { current.zoom }, set: { z in set(zoom: z) }), in: Framing.minZoom...Framing.maxZoom) {
                Text(L("ios.framing.zoom"))
            } minimumValueLabel: {
                Text(Framing.zoomText(Framing.minZoom)).font(.caption)
            } maximumValueLabel: {
                Text(Framing.zoomText(Framing.maxZoom)).font(.caption)
            }
            .tint(Palette.accent)
        }
    }

    /// The photo follows the finger: the window's centre moves the other way, by the visible part's share.
    private func drag(_ size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { g in
                let base = dragStart ?? current
                if dragStart == nil { dragStart = base }
                let r = Framing.unitCrop(screenAspect: boxAspect, photoAspect: photoAspect, view: base)
                let dx = -Double(g.translation.width / max(1, size.width)) * Double(r.width)
                let dy = -Double(g.translation.height / max(1, size.height)) * Double(r.height)
                set(FramingView(x: base.x + dx, y: base.y + dy, zoom: base.zoom))
            }
            .onEnded { _ in dragStart = nil }
    }

    private var pinch: some Gesture {
        MagnifyGesture()
            .onChanged { g in
                let start = pinchStart ?? current.zoom
                if pinchStart == nil { pinchStart = start }
                set(zoom: start * Double(g.magnification))
            }
            .onEnded { _ in pinchStart = nil }
    }

    private func set(zoom z: Double) {
        let c = current
        set(FramingView(x: c.x, y: c.y, zoom: z))
    }

    private func zoom(by step: Double) {
        set(zoom: current.zoom + step)
    }

    private func move(_ dx: Double, _ dy: Double) {
        set(Framing.moved(current, dx: dx, dy: dy, screenAspect: boxAspect, photoAspect: photoAspect))
    }

    private func set(_ v: FramingView?) {
        view = v.map { Framing.clamped($0, screenAspect: boxAspect, photoAspect: photoAspect) }
    }

    private func fitted(in box: CGSize) -> CGSize {
        let a = max(0.2, boxAspect)
        let w = max(1, box.width - 16), h = max(1, box.height - 16)
        if w / h > a { return CGSize(width: h * a, height: h) }
        return CGSize(width: w, height: w / a)
    }
}
