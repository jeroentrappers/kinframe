import KinframeKit
import SwiftUI

/// Short confirmations at the bottom of the screen, with an optional action such as Undo (product spec: undo
/// instead of "are you sure").
@MainActor
@Observable
final class ToastCenter {
    struct Action {
        let title: String
        let run: () -> Void
    }

    struct Toast: Identifiable {
        let id = UUID()
        let message: String
        let isError: Bool
        let action: Action?
    }

    private(set) var current: Toast?
    private var dismissal: Task<Void, Never>?

    func show(_ message: String, isError: Bool = false, action: Action? = nil) {
        current = Toast(message: message, isError: isError, action: action)
        UIAccessibility.post(notification: .announcement, argument: message)
        dismissal?.cancel()
        let id = current?.id
        dismissal = Task {
            try? await Task.sleep(for: .seconds(action == nil ? 3.5 : 6))
            guard !Task.isCancelled, self.current?.id == id else { return }
            withAnimation(.easeOut(duration: 0.2)) { self.current = nil }
        }
    }

    func error(_ error: Error) {
        guard !(error is CancellationError) else { return }
        show(errorMessage(error), isError: true)
    }

    func dismiss() {
        withAnimation(.easeOut(duration: 0.2)) { current = nil }
    }
}

struct ToastOverlay: View {
    let center: ToastCenter

    /// Use as `.overlay(alignment: .bottom)` so only the toast itself takes touches.
    var body: some View {
        ZStack {
            if let toast = center.current {
                HStack(spacing: 12) {
                    if toast.isError {
                        Image(systemName: "exclamationmark.circle.fill").foregroundStyle(Palette.danger).accessibilityHidden(true)
                    }
                    Text(toast.message)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Palette.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let action = toast.action {
                        Button(action.title) {
                            action.run()
                            center.dismiss()
                        }
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(Palette.primary)
                        .frame(minHeight: 44)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .frame(minHeight: 52)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous).strokeBorder(Palette.border))
                .shadow(color: .black.opacity(0.14), radius: 18, y: 8)
                .padding(.horizontal, 16)
                .padding(.bottom, 64)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .onTapGesture { center.dismiss() }
                .id(toast.id)
            }
        }
        .animation(.spring(duration: 0.3), value: center.current?.id)
    }
}
