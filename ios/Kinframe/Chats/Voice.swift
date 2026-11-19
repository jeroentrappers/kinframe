import AVFoundation
import KinframeKit
import SwiftUI

/// Records a voice message as AAC-LC in MP4 (`.m4a`), mono, 48 kHz, 64 kbit/s: what the server keeps as its master,
/// so it is stored without transcoding (README §5.19). Stops by itself at the server's limit.
@MainActor
@Observable
final class VoiceRecorder {
    enum State: Equatable { case idle, recording, denied }

    private(set) var state: State = .idle
    private(set) var elapsed: TimeInterval = 0
    /// Loudness 0–100 every 0.1 s, for the live waveform.
    private(set) var levels: [Int] = []
    private var recorder: AVAudioRecorder?
    private var timer: Task<Void, Never>?
    private var file: URL?
    var maxSeconds: Int = 300
    /// Called when the limit stopped the recording.
    var onLimit: (() -> Void)?

    func start() async -> Bool {
        guard state != .recording else { return true }
        guard await AVAudioApplication.requestRecordPermission() else {
            state = .denied
            return false
        }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
        } catch {
            return false
        }
        let url = FileManager.default.temporaryDirectory.appending(path: "voice-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 48_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 64_000,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        guard let r = try? AVAudioRecorder(url: url, settings: settings) else { return false }
        r.isMeteringEnabled = true
        guard r.record() else { return false }
        recorder = r
        file = url
        levels = []
        elapsed = 0
        state = .recording
        timer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                guard let self, let r = self.recorder else { return }
                r.updateMeters()
                self.levels.append(VoiceLogic.level(decibels: r.averagePower(forChannel: 0)))
                self.elapsed = r.currentTime
                if self.elapsed >= Double(self.maxSeconds) {
                    self.onLimit?()
                    return
                }
            }
        }
        return true
    }

    /// Stop and hand over the file and its length; nil when it was too short to send.
    func finish() -> (url: URL, durationMs: Int)? {
        let duration = recorder?.currentTime ?? elapsed
        stopRecorder()
        guard let file, VoiceLogic.isLongEnough(seconds: duration) else {
            discard()
            return nil
        }
        self.file = nil
        return (file, Int(duration * 1000))
    }

    func cancel() {
        stopRecorder()
        discard()
    }

    private func stopRecorder() {
        timer?.cancel()
        timer = nil
        recorder?.stop()
        recorder = nil
        if state == .recording { state = .idle }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func discard() {
        if let file { try? FileManager.default.removeItem(at: file) }
        file = nil
    }
}

/// Plays one voice message at a time, streamed from the server (the item's AAC master).
@MainActor
@Observable
final class VoicePlayback {
    static let shared = VoicePlayback()

    private(set) var playingItem: String?
    private(set) var progress: Double = 0
    private var player: AVPlayer?
    private var observer: Any?
    private var endTask: Task<Void, Never>?

    func toggle(_ clip: VoiceClip, api: API?) {
        if playingItem == clip.itemId {
            stop()
            return
        }
        stop()
        guard let api else { return }
        playingItem = clip.itemId
        Task {
            guard let asset = try? await MotionClip.asset(api: api, itemId: clip.itemId, variant: "master"), playingItem == clip.itemId else {
                if playingItem == clip.itemId { playingItem = nil }
                return
            }
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try? AVAudioSession.sharedInstance().setActive(true)
            let item = AVPlayerItem(asset: asset)
            let p = AVPlayer(playerItem: item)
            player = p
            let total = Double(clip.durationMs ?? 1) / 1000
            observer = p.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] t in
                MainActor.assumeIsolated { self?.progress = min(1, t.seconds / max(total, 0.1)) }
            }
            endTask = Task { [weak self] in
                for await _ in NotificationCenter.default.notifications(named: AVPlayerItem.didPlayToEndTimeNotification, object: item) {
                    self?.stop()
                    break
                }
            }
            p.play()
        }
    }

    func stop() {
        if let observer { player?.removeTimeObserver(observer) }
        observer = nil
        endTask?.cancel()
        endTask = nil
        player?.pause()
        player = nil
        playingItem = nil
        progress = 0
    }
}

/// Bars of a waveform, the played part in the accent colour.
struct WaveformView: View {
    let values: [Double]
    var progress: Double = 0
    var tint: Color = Palette.primary

    var body: some View {
        GeometryReader { geo in
            HStack(alignment: .center, spacing: 2) {
                ForEach(Array(values.enumerated()), id: \.offset) { n, v in
                    Capsule()
                        .fill(Double(n) / Double(max(1, values.count)) < progress ? tint : tint.opacity(0.35))
                        .frame(width: max(2, (geo.size.width - CGFloat(values.count - 1) * 2) / CGFloat(max(1, values.count))),
                               height: max(3, geo.size.height * v))
                }
            }
            .frame(maxHeight: .infinity)
        }
        .accessibilityHidden(true)
    }
}

/// A voice message in a bubble: play/pause, waveform with progress, length.
struct VoiceBubble: View {
    @Environment(AppModel.self) private var app
    let clip: VoiceClip
    var tint: Color = Palette.primary
    private var playback: VoicePlayback { VoicePlayback.shared }

    var body: some View {
        HStack(spacing: 10) {
            Button { playback.toggle(clip, api: app.api) } label: {
                Image(systemName: playback.playingItem == clip.itemId ? "pause.fill" : "play.fill")
                    .font(.body.weight(.bold)).foregroundStyle(tint)
                    .frame(width: 36, height: 36).background(Circle().fill(tint.opacity(0.15)))
            }
            .buttonStyle(.plain)
            .disabled(clip.status != .ready)
            .accessibilityLabel(L(playback.playingItem == clip.itemId ? "chat.pause" : "chat.voice.play"))
            if clip.status == .processing {
                ProgressView().controlSize(.small)
                Text(L("chat.processing")).font(.caption).foregroundStyle(Palette.textMuted)
            } else if clip.status == .failed {
                Text(L("chat.voice.failed")).font(.caption).foregroundStyle(Palette.danger)
            } else {
                WaveformView(values: VoiceLogic.bars(clip.waveform, count: 32),
                             progress: playback.playingItem == clip.itemId ? playback.progress : 0, tint: tint)
                    .frame(width: 140, height: 28)
            }
            Text(VoiceLogic.duration(clip.durationMs)).font(.caption.monospacedDigit()).foregroundStyle(Palette.textMuted)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("chat.voice.a11y", ["duration": VoiceLogic.duration(clip.durationMs)]))
    }
}
