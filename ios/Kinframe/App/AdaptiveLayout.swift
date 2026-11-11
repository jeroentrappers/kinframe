import SwiftUI

/// Widths for larger screens: iPad, iPhone Duo unfolded, and iPhone in landscape.
///
/// Layouts follow the space they get, not the device or size class: the iPhone Duo's inner screen is about 670 pt wide
/// (two columns fit) while it may still report a compact size class, and iPad split view can make any width.
enum PageWidth {
    /// Reading width for text, forms and single-column pages.
    static let text: CGFloat = 720
    /// Grids of cards (frames, history) and photo libraries.
    static let wide: CGFloat = 1200
    /// From here a page may show two columns side by side.
    static let twoColumns: CGFloat = 640
    /// Minimum card width in grids: two cards fit on the Duo's inner screen and iPad mini, one on any iPhone.
    static let card: CGFloat = 300
}

extension View {
    /// Centers the page content in a column of at most `maxWidth`, with the usual 16 pt gutters.
    func pageColumn(_ maxWidth: CGFloat = PageWidth.text) -> some View {
        padding(16)
            .frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
    }

    /// Calls `action` with the view's width whenever it changes (layout decisions such as one or two columns).
    func onWidthChange(_ action: @escaping (CGFloat) -> Void) -> some View {
        background {
            GeometryReader { proxy in
                Color.clear
                    .onAppear { action(proxy.size.width) }
                    .onChange(of: proxy.size.width) { _, width in action(width) }
            }
        }
    }

    /// Keeps a `Form` or `List` at reading width on wide screens by growing its side margins.
    func readableFormWidth(_ maxWidth: CGFloat = PageWidth.text) -> some View {
        modifier(ReadableFormWidth(maxWidth: maxWidth))
    }
}

private struct ReadableFormWidth: ViewModifier {
    let maxWidth: CGFloat
    @State private var width: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .contentMargins(.horizontal, max(0, (width - maxWidth) / 2), for: .scrollContent)
            .onWidthChange { width = $0 }
    }
}

/// Card grid columns: one column on phones, more as the width allows.
func cardColumns(spacing: CGFloat = 16) -> [GridItem] {
    [GridItem(.adaptive(minimum: PageWidth.card), spacing: spacing, alignment: .top)]
}
