import SwiftUI
import UIKit

/// kinframe's design tokens: warm, calm, quiet. The same values as web/src/app.css, light and dark.
public enum Palette {
    static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }

    public static let bg = dynamic(0xF8F3EC, 0x191512)
    public static let bgTint = dynamic(0xF3EBE0, 0x1F1A16)
    public static let surface = dynamic(0xFFFDF9, 0x231E1A)
    public static let surface2 = dynamic(0xF4EDE4, 0x2C2621)
    public static let surface3 = dynamic(0xEBE2D6, 0x373029)
    public static let border = dynamic(0xE4D8CA, 0x3A322B)
    public static let borderStrong = dynamic(0xCDBDAB, 0x54493F)
    public static let text = dynamic(0x2A221D, 0xF3EBE3)
    public static let textMuted = dynamic(0x675A50, 0xBDAEA1)
    public static let textFaint = dynamic(0x8B7D71, 0x94867A)

    public static let primary = dynamic(0xA7471E, 0xF09A6D)
    public static let primaryPressed = dynamic(0x8D3B18, 0xF6B08B)
    public static let primarySoft = dynamic(0xF7E4D8, 0x3D2519)
    public static let primarySoftText = dynamic(0x7C3412, 0xF6C1A3)
    public static let onPrimary = dynamic(0xFFFFFF, 0x2A1206)

    public static let accent = dynamic(0x3C6A49, 0x8CC39B)
    public static let accentSoft = dynamic(0xE2EEE3, 0x1F3226)
    public static let accentText = dynamic(0x2F5A3C, 0xA6D6B2)

    public static let warn = dynamic(0x8A5A00, 0xF0C066)
    public static let warnSoft = dynamic(0xFBEFD3, 0x3A2D12)
    public static let danger = dynamic(0xB3261E, 0xF28B82)
    public static let dangerSoft = dynamic(0xFBE4E1, 0x3D1D1A)

    public static let sun = Color(UIColor(hex: 0xE7A23A))
    public static let hill = Color(UIColor(hex: 0x4F7A5A))
    public static let online = Color(UIColor(hex: 0x3FA564))

    /// Calm per-frame tints so frames are recognisable at a glance (web: FrameCard).
    static let tints: [(UInt32, UInt32, UInt32, UInt32)] = [
        (0xF6D9C2, 0xE9B38F, 0x4A3326, 0x6B4630),
        (0xDBE8D6, 0xA9C7A1, 0x2C3A2A, 0x3F5A3B),
        (0xF3E3BD, 0xE2C27A, 0x463B22, 0x6A5730),
        (0xDDE3EF, 0xA8B6D4, 0x2C3242, 0x3F4C6B),
        (0xEFD9E0, 0xD6A7B6, 0x45303A, 0x684453),
        (0xD8E9E7, 0x97C3BD, 0x283B39, 0x3B5F5A),
    ]

    public static func tint(for id: String) -> (Color, Color) {
        let t = tints[id.unicodeScalars.reduce(0) { $0 + Int($1.value) } % tints.count]
        return (dynamic(t.0, t.2), dynamic(t.1, t.3))
    }
}

public enum Radius {
    public static let sm: CGFloat = 8
    public static let md: CGFloat = 12
    public static let lg: CGFloat = 18
    public static let xl: CGFloat = 26
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

extension Font {
    /// Headings use the serif design (New York), the iOS counterpart of the web's Fraunces.
    public static func display(_ style: Font.TextStyle = .title) -> Font {
        .system(style, design: .serif).weight(.semibold)
    }
}

// MARK: Buttons

public enum KFButtonKind: Sendable {
    case primary, soft, secondary, ghost, danger, dangerGhost
}

/// Capsule buttons with at least 44 pt of height (52 for `large`).
public struct KFButtonStyle: ButtonStyle {
    let kind: KFButtonKind
    let large: Bool
    let fill: Bool
    @Environment(\.isEnabled) private var isEnabled

    public init(_ kind: KFButtonKind = .primary, large: Bool = false, fill: Bool = false) {
        self.kind = kind
        self.large = large
        self.fill = fill
    }

    public func makeBody(configuration: Configuration) -> some View {
        let (bg, fg, stroke): (Color, Color, Color) = switch kind {
        case .primary: (configuration.isPressed ? Palette.primaryPressed : Palette.primary, Palette.onPrimary, .clear)
        case .soft: (Palette.primarySoft, Palette.primarySoftText, .clear)
        case .secondary: (configuration.isPressed ? Palette.surface2 : Palette.surface, Palette.text, Palette.borderStrong)
        case .ghost: (configuration.isPressed ? Palette.surface2 : .clear, Palette.text, .clear)
        case .danger: (Palette.danger, .white, .clear)
        case .dangerGhost: (configuration.isPressed ? Palette.dangerSoft : .clear, Palette.danger, .clear)
        }
        configuration.label
            .font(large ? .body.weight(.semibold) : .subheadline.weight(.semibold))
            .lineLimit(1)
            .minimumScaleFactor(0.85)
            .padding(.horizontal, large ? 24 : 16)
            .frame(minHeight: large ? 52 : 44)
            .frame(maxWidth: fill ? .infinity : nil)
            .foregroundStyle(fg)
            .background(Capsule().fill(bg))
            .overlay(Capsule().strokeBorder(stroke, lineWidth: 1))
            .shadow(color: kind == .primary ? .black.opacity(0.08) : .clear, radius: 2, y: 1)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .opacity(isEnabled ? 1 : 0.5)
            .contentShape(Capsule())
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == KFButtonStyle {
    public static func kf(_ kind: KFButtonKind = .primary, large: Bool = false, fill: Bool = false) -> KFButtonStyle {
        KFButtonStyle(kind, large: large, fill: fill)
    }
}

// MARK: Surfaces

public struct CardModifier: ViewModifier {
    var padding: CGFloat?

    public func body(content: Content) -> some View {
        content
            .padding(padding ?? 0)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous).strokeBorder(Palette.border, lineWidth: 1))
            .shadow(color: Color(red: 60 / 255, green: 40 / 255, blue: 20 / 255).opacity(0.06), radius: 3, y: 1)
    }
}

extension View {
    /// The web's `.card`: surface, hairline border, large radius, soft shadow.
    public func card(padding: CGFloat? = 16) -> some View {
        modifier(CardModifier(padding: padding))
    }

    /// The warm page background.
    public func pageBackground() -> some View {
        background(Palette.bg.ignoresSafeArea())
    }
}

/// A small rounded label (web: .badge / .chip).
public struct Pill: View {
    let text: String
    let systemImage: String?
    let foreground: Color
    let background: Color

    public init(_ text: String, systemImage: String? = nil, foreground: Color = Palette.textMuted, background: Color = Palette.surface2) {
        self.text = text
        self.systemImage = systemImage
        self.foreground = foreground
        self.background = background
    }

    public var body: some View {
        HStack(spacing: 4) {
            if let systemImage {
                Image(systemName: systemImage).font(.caption2.weight(.bold)).accessibilityHidden(true)
            }
            Text(text).font(.caption.weight(.semibold))
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 3)
        .foregroundStyle(foreground)
        .background(Capsule().fill(background))
    }
}

/// A notice with an icon (web: .callout).
public struct Callout: View {
    public enum Kind: Sendable { case info, warn, error, ok }
    let kind: Kind
    let text: String

    public init(_ text: String, kind: Kind = .info) {
        self.text = text
        self.kind = kind
    }

    public var body: some View {
        let (icon, bg, fg): (String, Color, Color) = switch kind {
        case .info: ("info.circle", Palette.surface2, Palette.textMuted)
        case .warn: ("exclamationmark.triangle", Palette.warnSoft, Palette.warn)
        case .error: ("exclamationmark.circle", Palette.dangerSoft, Palette.danger)
        case .ok: ("checkmark.circle", Palette.accentSoft, Palette.accentText)
        }
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: icon).foregroundStyle(fg).accessibilityHidden(true)
            Text(text).font(.subheadline).foregroundStyle(Palette.text).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(bg, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// The kinframe mark (web: Logo.svelte): a frame with a sun and hills.
public struct LogoMark: View {
    let size: CGFloat

    public init(size: CGFloat = 30) {
        self.size = size
    }

    public var body: some View {
        Canvas { ctx, s in
            let u = s.width / 64
            ctx.fill(Path(roundedRect: CGRect(x: 4 * u, y: 8 * u, width: 56 * u, height: 48 * u), cornerRadius: 12 * u), with: .color(Palette.primary))
            let inner = Path(roundedRect: CGRect(x: 11 * u, y: 15 * u, width: 42 * u, height: 34 * u), cornerRadius: 6 * u)
            ctx.fill(inner, with: .color(Color(UIColor(hex: 0xFBF1E6))))
            ctx.fill(Path(ellipseIn: CGRect(x: 36 * u, y: 21 * u, width: 10 * u, height: 10 * u)), with: .color(Palette.sun))
            var hills = Path()
            hills.move(to: CGPoint(x: 11 * u, y: 43 * u))
            hills.addLine(to: CGPoint(x: 23 * u, y: 31 * u))
            hills.addLine(to: CGPoint(x: 32 * u, y: 40 * u))
            hills.addLine(to: CGPoint(x: 38 * u, y: 34 * u))
            hills.addLine(to: CGPoint(x: 53 * u, y: 49 * u))
            hills.addLine(to: CGPoint(x: 17 * u, y: 49 * u))
            hills.addQuadCurve(to: CGPoint(x: 11 * u, y: 43 * u), control: CGPoint(x: 11 * u, y: 49 * u))
            ctx.fill(hills, with: .color(Palette.hill))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// The online dot (web: .dot-online), with a soft halo.
public struct StatusDot: View {
    let online: Bool

    public init(online: Bool) {
        self.online = online
    }

    public var body: some View {
        Circle()
            .fill(online ? Palette.online : Palette.textFaint)
            .frame(width: 9, height: 9)
            .background(Circle().fill(online ? Palette.online.opacity(0.22) : .clear).frame(width: 15, height: 15))
            .accessibilityHidden(true)
    }
}
