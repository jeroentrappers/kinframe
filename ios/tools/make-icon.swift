// Renders the app icon from the kinframe logo (web/src/lib/components/Logo.svelte): a warm frame with a sun and
// hills. Usage: swift ios/tools/make-icon.swift <output dir>
import AppKit

func hex(_ v: UInt32) -> CGColor {
    CGColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
}

func render(dark: Bool, to url: URL) {
    let size = 1024
    let cs = CGColorSpace(name: CGColorSpace.sRGB)!
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    // Flip so y grows downward like the SVG.
    ctx.translateBy(x: 0, y: CGFloat(size))
    ctx.scaleBy(x: 1, y: -1)
    let s = CGFloat(size) / 64

    // Background: the logo's outer frame, full bleed.
    let top = dark ? hex(0x2C2621) : hex(0xBD5A2C)
    let bottom = dark ? hex(0x191512) : hex(0x963C16)
    let gradient = CGGradient(colorsSpace: cs, colors: [top, bottom] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: size), options: [])

    // The photo inside the frame.
    let inner = CGRect(x: 10 * s, y: 13 * s, width: 44 * s, height: 38 * s)
    let photo = CGPath(roundedRect: inner, cornerWidth: 7 * s, cornerHeight: 7 * s, transform: nil)
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: 0.8 * s), blur: 2.5 * s, color: CGColor(gray: 0, alpha: 0.25))
    ctx.addPath(photo)
    ctx.setFillColor(dark ? hex(0xF3E6D6) : hex(0xFBF1E6))
    ctx.fillPath()
    ctx.restoreGState()

    ctx.saveGState()
    ctx.addPath(photo)
    ctx.clip()
    ctx.setFillColor(hex(0xE7A23A))
    ctx.fillEllipse(in: CGRect(x: 36 * s, y: 19 * s, width: 11 * s, height: 11 * s))
    let hills = CGMutablePath()
    hills.move(to: CGPoint(x: 10 * s, y: 44 * s))
    hills.addLine(to: CGPoint(x: 23 * s, y: 31 * s))
    hills.addLine(to: CGPoint(x: 32 * s, y: 40 * s))
    hills.addLine(to: CGPoint(x: 38.5 * s, y: 33.5 * s))
    hills.addLine(to: CGPoint(x: 56 * s, y: 51 * s))
    hills.addLine(to: CGPoint(x: 10 * s, y: 51 * s))
    hills.closeSubpath()
    ctx.addPath(hills)
    ctx.setFillColor(hex(0x4F7A5A))
    ctx.fillPath()
    ctx.restoreGState()

    let image = ctx.makeImage()!
    let rep = NSBitmapImageRep(cgImage: image)
    try! rep.representation(using: .png, properties: [:])!.write(to: url)
}

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
render(dark: false, to: out.appending(path: "AppIcon.png"))
render(dark: true, to: out.appending(path: "AppIcon-dark.png"))
