import AVKit
import ImageIO
import KinframeKit
import SwiftUI

/// Framing and caption for one item (web: ItemEditor): fill or fit, the focus point (tap the photo), landscape and
/// portrait previews, the caption, and for videos the trim window and sound.
struct ItemEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var model: ComposeModel
    let draftId: UUID
    let frames: [FrameSummary]
    @State private var preview: UIImage?

    private var draft: Draft? { model.drafts.first { $0.id == draftId } }
    private var number: Int { (model.drafts.firstIndex { $0.id == draftId } ?? 0) + 1 }

    /// The selected frames' screen shape; 16:10 when unknown.
    private var screenAspect: Double {
        if let s = frames.compactMap(\.screen).first, s.height > 0 { return Double(s.width) / Double(s.height) }
        return 16.0 / 10
    }

    var body: some View {
        NavigationStack {
            if let draft {
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        FocalPicker(image: preview, aspect: draft.aspect ?? 1, focal: binding(\.focal, default: CGPoint(x: 0.5, y: 0.5)), enabled: draft.fit == .fill)
                            .frame(maxWidth: .infinity)

                        fitPicker(draft)

                        VStack(alignment: .leading, spacing: 8) {
                            Text(L("send.previewsLabel")).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                            HStack(alignment: .bottom, spacing: 16) {
                                FramePreviewBox(image: preview, imageAspect: draft.aspect ?? 1, focal: draft.focal, fit: draft.fit,
                                                boxAspect: max(screenAspect, 1 / screenAspect), label: L("send.landscape"))
                                FramePreviewBox(image: preview, imageAspect: draft.aspect ?? 1, focal: draft.focal, fit: draft.fit,
                                                boxAspect: min(screenAspect, 1 / screenAspect), label: L("send.portrait"))
                                    .frame(width: 104)
                            }
                        }

                        if draft.kind == .video, let duration = draft.info?.duration, duration > 0 {
                            VideoTrimSection(model: model, draft: draft, duration: duration)
                        }

                        captionSection(draft)
                    }
                    .padding(20)
                    .frame(maxWidth: 640)
                    .frame(maxWidth: .infinity)
                }
                .scrollDismissesKeyboard(.interactively)
                .pageBackground()
                .navigationTitle(L("send.adjustTitle"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L("common.done")) { dismiss() }.fontWeight(.semibold)
                    }
                    ToolbarItem(placement: .destructiveAction) {
                        Button(role: .destructive) {
                            model.remove(draftId)
                            dismiss()
                        } label: {
                            Image(systemName: "trash")
                        }
                        .accessibilityLabel(L("send.removeItem", ["n": number]))
                    }
                }
                .task(id: draft.trim?.startMs) { await loadPreview(draft) }
            }
        }
        .presentationDragIndicator(.visible)
    }

    private func binding<T>(_ keyPath: WritableKeyPath<Draft, T>, default value: T) -> Binding<T> {
        Binding(
            get: { draft?[keyPath: keyPath] ?? value },
            set: { value in model.update(draftId) { $0[keyPath: keyPath] = value } }
        )
    }

    private func fitPicker(_ draft: Draft) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("send.fitLegend")).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
            Picker(L("send.fitLegend"), selection: binding(\.fit, default: Fit.fill)) {
                Text(L("send.fill")).tag(Fit.fill)
                Text(L("send.fit")).tag(Fit.fit)
            }
            .pickerStyle(.segmented)
            Text(draft.fit == .fill ? L("send.fillHint") + " " + L("send.focalHint") : L("send.fitHint"))
                .font(.footnote)
                .foregroundStyle(Palette.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func captionSection(_ draft: Draft) -> some View {
        let text = model.caption(of: draft)
        let left = Format.captionRemaining(text)
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L("send.caption")).font(.subheadline.weight(.semibold))
                Spacer()
                Text(L("send.charsLeft", ["n": left, "max": Format.captionMax]))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(left < 0 ? Palette.danger : Palette.textFaint)
            }
            TextField(L("send.captionPlaceholder"), text: Binding(
                get: { model.caption(of: draft) },
                set: { value in model.update(draftId) { $0.caption = value } }
            ), axis: .vertical)
            .lineLimit(2...5)
            .padding(12)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(left < 0 ? Palette.danger : Palette.borderStrong))
            .accessibilityLabel(L("send.captionFor", ["n": number]))
            if model.drafts.count > 1, !text.isEmpty {
                Button {
                    model.applyCaptionToAll(text)
                } label: {
                    Label(L("send.applyAll", ["n": model.drafts.count]), systemImage: "square.on.square")
                }
                .buttonStyle(.kf(.soft))
            }
        }
    }

    private func loadPreview(_ draft: Draft) async {
        let url = draft.file
        if draft.kind == .video {
            let at = Double(draft.trim?.startMs ?? 0) / 1000
            if let data = await MediaProbe.videoFrame(AVURLAsset(url: url), at: at, maxSize: 1400) { preview = UIImage(data: data) }
        } else if preview == nil {
            preview = await Task.detached { () -> UIImage? in
                guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
                let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 1600]
                return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary).map { UIImage(cgImage: $0) }
            }.value
        }
        if preview == nil, let data = draft.info?.thumbnail { preview = UIImage(data: data) }
    }
}

/// The photo with a marker: tap or drag to set the point the frame keeps in view when it crops.
struct FocalPicker: View {
    let image: UIImage?
    let aspect: Double
    @Binding var focal: CGPoint
    let enabled: Bool

    var body: some View {
        GeometryReader { geo in
            let size = fitted(in: geo.size)
            ZStack(alignment: .topLeading) {
                Group {
                    if let image {
                        Image(uiImage: image).resizable().scaledToFill()
                    } else {
                        Rectangle().fill(Palette.surface2).overlay(ProgressView())
                    }
                }
                .frame(width: size.width, height: size.height)
                .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))

                if enabled {
                    Circle()
                        .strokeBorder(.white, lineWidth: 3)
                        .background(Circle().fill(Palette.primary.opacity(0.35)))
                        .frame(width: 40, height: 40)
                        .shadow(color: .black.opacity(0.4), radius: 4)
                        .position(x: focal.x * size.width, y: focal.y * size.height)
                        .allowsHitTesting(false)
                        .animation(.snappy(duration: 0.2), value: focal)
                }
            }
            .frame(width: size.width, height: size.height)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                guard enabled else { return }
                focal = Framing.clampFocal(CGPoint(x: g.location.x / size.width, y: g.location.y / size.height))
            })
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(height: 300)
        .accessibilityElement()
        .accessibilityLabel(L("send.focalLabel"))
        .accessibilityValue(L("send.focalValue", ["x": Int(focal.x * 100), "y": Int(focal.y * 100)]))
        .accessibilityHint(enabled ? L("send.focalHint") : "")
        .accessibilityAction(named: L("ios.focal.up")) { nudge(0, -0.1) }
        .accessibilityAction(named: L("ios.focal.down")) { nudge(0, 0.1) }
        .accessibilityAction(named: L("ios.focal.left")) { nudge(-0.1, 0) }
        .accessibilityAction(named: L("ios.focal.right")) { nudge(0.1, 0) }
    }

    private func nudge(_ dx: Double, _ dy: Double) {
        guard enabled else { return }
        focal = Framing.clampFocal(CGPoint(x: focal.x + dx, y: focal.y + dy))
    }

    private func fitted(in box: CGSize) -> CGSize {
        let a = max(0.2, aspect)
        if box.width / box.height > a { return CGSize(width: box.height * a, height: box.height) }
        return CGSize(width: box.width, height: box.width / a)
    }
}

/// How the item will look on a frame of the given shape (web: FramePreview).
struct FramePreviewBox: View {
    let image: UIImage?
    let imageAspect: Double
    let focal: CGPoint
    let fit: Fit
    let boxAspect: Double
    let label: String

    var body: some View {
        VStack(spacing: 6) {
            GeometryReader { geo in
                let w = geo.size.width, h = geo.size.height
                ZStack(alignment: .topLeading) {
                    Color.black
                    if let image {
                        if fit == .fit {
                            Image(uiImage: image).resizable().scaledToFill().frame(width: w, height: h).blur(radius: 14).opacity(0.75).clipped()
                            Image(uiImage: image).resizable().scaledToFit().frame(width: w, height: h)
                        } else {
                            let pos = Framing.coverPosition(focal: focal, imageAspect: imageAspect, boxAspect: boxAspect)
                            let iw = imageAspect >= boxAspect ? h * imageAspect : w
                            let ih = imageAspect >= boxAspect ? h : w / imageAspect
                            Image(uiImage: image).resizable()
                                .frame(width: iw, height: ih)
                                .offset(x: -(iw - w) * pos.x, y: -(ih - h) * pos.y)
                        }
                    }
                }
                .frame(width: w, height: h)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                .animation(.snappy(duration: 0.2), value: focal)
                .animation(.snappy(duration: 0.2), value: fit)
            }
            .aspectRatio(boxAspect, contentMode: .fit)
            .padding(6)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(red: 0.16, green: 0.13, blue: 0.11)))
            .shadow(color: .black.opacity(0.15), radius: 6, y: 3)
            Text(label).font(.caption).foregroundStyle(Palette.textMuted)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }
}

/// Trim window (min 3 s, max the server's limit), a filmstrip, preview playback and the sound toggle.
struct VideoTrimSection: View {
    @Bindable var model: ComposeModel
    let draft: Draft
    let duration: Double
    @State private var strip: [UIImage] = []
    @State private var player: AVPlayer?
    @State private var playing = false

    private var trim: Trim { draft.trim ?? Trim(startMs: 0, endMs: Int(duration * 1000)) }
    private var maxSeconds: Int { model.limits.maxVideoSeconds }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(L("ios.trim.title")).font(.subheadline.weight(.semibold))
                Spacer()
                Text("\(Format.duration(Double(trim.startMs) / 1000)) – \(Format.duration(Double(trim.endMs) / 1000)) · \(Format.duration(trim.seconds))")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(trim.seconds > Double(maxSeconds) + 0.5 ? Palette.danger : Palette.textMuted)
            }
            TrimBar(strip: strip, duration: duration, trim: trim) { start, end in
                let t = ComposeRules.clampTrim(start: start, end: end, duration: duration, maxSeconds: maxSeconds)
                model.update(draft.id) { $0.trim = (t.startMs == 0 && Double(t.endMs) >= duration * 1000 - 1) ? nil : t }
            }
            .frame(height: 56)
            Text(L("ios.trim.hint", ["min": Int(ComposeRules.minVideoSeconds), "max": Format.duration(Double(maxSeconds))]))
                .font(.footnote)
                .foregroundStyle(Palette.textMuted)
            HStack {
                Button {
                    togglePlay()
                } label: {
                    Label(playing ? L("ios.trim.stop") : L("ios.trim.play"), systemImage: playing ? "stop.fill" : "play.fill")
                }
                .buttonStyle(.kf(.secondary))
                Spacer()
            }
            if let player {
                VideoPlayer(player: player)
                    .frame(height: 200)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    .accessibilityHidden(true)
            }
            Toggle(isOn: Binding(get: { draft.includeAudio }, set: { v in model.update(draft.id) { $0.includeAudio = v } })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("ios.trim.audio")).font(.body)
                    if draft.info?.hasAudio == false {
                        Text(L("ios.trim.noAudio")).font(.footnote).foregroundStyle(Palette.textMuted)
                    }
                }
            }
            .tint(Palette.accent)
            .disabled(draft.info?.hasAudio == false)
            .frame(minHeight: 44)
        }
        .task { await loadStrip() }
        .onDisappear { player?.pause() }
    }

    private func togglePlay() {
        if playing {
            player?.pause()
            playing = false
            return
        }
        let p = player ?? AVPlayer(url: draft.file)
        player = p
        p.isMuted = !draft.includeAudio
        let start = CMTime(seconds: Double(trim.startMs) / 1000, preferredTimescale: 600)
        p.currentItem?.forwardPlaybackEndTime = CMTime(seconds: Double(trim.endMs) / 1000, preferredTimescale: 600)
        p.seek(to: start, toleranceBefore: .zero, toleranceAfter: .zero)
        p.play()
        playing = true
    }

    private func loadStrip() async {
        guard strip.isEmpty else { return }
        let asset = AVURLAsset(url: draft.file)
        var images: [UIImage] = []
        for i in 0..<8 {
            if let data = await MediaProbe.videoFrame(asset, at: duration * (Double(i) + 0.5) / 8, maxSize: 160), let img = UIImage(data: data) {
                images.append(img)
            }
        }
        strip = images
    }
}

/// A filmstrip with two handles.
struct TrimBar: View {
    let strip: [UIImage]
    let duration: Double
    let trim: Trim
    let onChange: (Double, Double) -> Void
    @State private var dragStart: Double?

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let s = CGFloat(Double(trim.startMs) / 1000 / duration) * w
            let e = CGFloat(Double(trim.endMs) / 1000 / duration) * w
            ZStack(alignment: .leading) {
                HStack(spacing: 0) {
                    ForEach(Array(strip.enumerated()), id: \.offset) { _, img in
                        Image(uiImage: img).resizable().scaledToFill().frame(width: w / CGFloat(max(1, strip.count)), height: geo.size.height).clipped()
                    }
                }
                .frame(width: w, height: geo.size.height)
                .background(Palette.surface3)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                // Dim what's cut.
                Rectangle().fill(.black.opacity(0.55)).frame(width: max(0, s))
                Rectangle().fill(.black.opacity(0.55)).frame(width: max(0, w - e)).offset(x: e)
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .strokeBorder(Palette.sun, lineWidth: 3)
                    .frame(width: max(12, e - s))
                    .offset(x: s)
                    .gesture(DragGesture().onChanged { g in
                        let base = dragStart ?? Double(trim.startMs) / 1000
                        if dragStart == nil { dragStart = base }
                        let delta = Double(g.translation.width / w) * duration
                        let len = trim.seconds
                        let start = min(max(0, base + delta), duration - len)
                        onChange(start, start + len)
                    }.onEnded { _ in dragStart = nil })
                handle(x: s, height: geo.size.height) { x in onChange(Double(x / w) * duration, Double(trim.endMs) / 1000) }
                handle(x: e, height: geo.size.height) { x in onChange(Double(trim.startMs) / 1000, Double(x / w) * duration) }
            }
            .coordinateSpace(name: "trim")
        }
        .accessibilityElement()
        .accessibilityLabel(L("ios.trim.title"))
        .accessibilityValue("\(Format.duration(Double(trim.startMs) / 1000)) – \(Format.duration(Double(trim.endMs) / 1000))")
        .accessibilityAdjustableAction { direction in
            let step = 1.0
            let s = Double(trim.startMs) / 1000, e = Double(trim.endMs) / 1000
            switch direction {
            case .increment: onChange(s + step, e + step)
            case .decrement: onChange(s - step, e - step)
            @unknown default: break
            }
        }
    }

    private func handle(x: CGFloat, height: CGFloat, move: @escaping (CGFloat) -> Void) -> some View {
        Capsule()
            .fill(Palette.sun)
            .frame(width: 12, height: height + 8)
            .overlay(Capsule().fill(.white).frame(width: 3, height: 18))
            .frame(width: 44, height: height + 8)
            .contentShape(Rectangle())
            .offset(x: x - 22)
            .gesture(DragGesture(coordinateSpace: .named("trim")).onChanged { g in move(g.location.x) })
    }
}
