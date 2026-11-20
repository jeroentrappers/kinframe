import KinframeKit
import SwiftUI

struct FeedDeliveryPill: View {
    let status: FeedDeliveryStatus

    var body: some View {
        let (icon, fg, bg): (String, Color, Color) = switch status {
        case .pending: ("clock", Palette.textMuted, Palette.surface2)
        case .ready: ("paperplane", Palette.warn, Palette.warnSoft)
        case .delivered: ("checkmark", Palette.accentText, Palette.accentSoft)
        case .seen: ("eye", Palette.surface, Palette.accent)
        case .failed: ("exclamationmark.triangle", Palette.danger, Palette.dangerSoft)
        case .awaitingApproval: ("hourglass", Palette.warn, Palette.warnSoft)
        case .suspended: ("pause.fill", Palette.textMuted, Palette.surface2)
        case .removed: ("xmark", Palette.textMuted, Palette.surface2)
        }
        Pill(L("ios.feeds.delivery.\(status.rawValue)"), systemImage: icon, foreground: fg, background: bg)
    }
}
