import SwiftUI

struct BackButton: View {
    var onDismiss: @MainActor () -> Void

    var body: some View {
        Button(action: { onDismiss() }) {
            GlyphIcon(icon: .arrowLeft, tint: Palette.ink2)
                .frame(width: 20, height: 20)
        }
        .buttonStyle(.plain)
        .frame(width: 36, height: 36)
        .accessibilityLabel("返回对话")
    }
}