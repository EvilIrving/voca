import SwiftUI

private let disabledAlpha: CGFloat = 0.4
let bubbleWidth: CGFloat = 258
let floatingShadowInset: CGFloat = 20

func highlightedText(_ text: String, highlight: String) -> AttributedString {
    var out = AttributedString(text)
    guard let range = text.range(of: highlight, options: .caseInsensitive) else { return out }
    if let attrRange = Range(range, in: out) {
        out[attrRange].foregroundColor = Palette.clayDeep
    }
    return out
}

struct Scrim: View {
    var onDismiss: @MainActor () -> Void

    var body: some View {
        Color.clear
            .contentShape(Rectangle())
            .onTapGesture { onDismiss() }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct Pressable<Content: View>: View {
    var shape: AnyShape = AnyShape(Rectangle())
    var resting: Color = .clear
    var pressing: Color?
    var border: Color?
    var enabled: Bool = true
    var contentDescription: String?
    var onLongPress: (() -> Void)?
    var longClickLabel: String?
    var onClick: @MainActor () -> Void
    @ViewBuilder var content: () -> Content

    @GestureState private var held = false

    private var pressColor: Color { pressing ?? resting.pressedTone() }

    var body: some View {
        Button(action: { onClick() }) {
            ZStack {
                (held ? pressColor : resting)
                content().opacity(enabled ? 1 : disabledAlpha)
            }
            .clipShape(shape)
            .overlay {
                if let border {
                    shape.stroke(border, lineWidth: 1)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .simultaneousGesture(
            onLongPress == nil ? nil : LongPressGesture(minimumDuration: 0.5).onEnded { _ in onLongPress?() }
        )
        .accessibilityLabel(contentDescription ?? "")
    }
}

struct ParleyIconButton: View {
    var icon: Glyph
    var contentDescription: String
    var onClick: @MainActor () -> Void
    var size: CGFloat = 36
    var iconSize: CGFloat = 20
    var enabled: Bool = true
    var tint: Color = Palette.ink3
    var container: Color = .clear
    var borderColor: Color?

    var body: some View {
        Pressable(
            shape: AnyShape(Circle()),
            resting: container,
            border: borderColor,
            enabled: enabled,
            contentDescription: contentDescription,
            onClick: onClick
        ) {
            GlyphIcon(icon: icon, tint: tint)
                .frame(width: iconSize, height: iconSize)
        }
        .frame(width: size, height: size)
    }
}

struct EchoTurn: View {
    var turn: Turn
    var translation: String?
    var expanded: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(turn.text)
                .font(Type.proseFont)
                .foregroundStyle(Palette.ink)
                .lineSpacing(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 14)
                .padding(.trailing, 14)
                .padding(.top, 13)
                .padding(.bottom, 12)
                .background(Palette.card, in: bubbleShape(tailLeft: true))
                .overlay { bubbleShape(tailLeft: true).stroke(Palette.hair, lineWidth: 1) }
                .frame(width: bubbleWidth, alignment: .leading)

            if expanded, let translation {
                Spacer().frame(height: Spacing.iconText)
                Text(translation)
                    .font(Type.translation)
                    .foregroundStyle(Palette.ink3)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .frame(width: bubbleWidth, alignment: .leading)
                    .background(Palette.tint, in: RoundedRectangle(cornerRadius: Radius.chip))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct UserBubble: View {
    var turn: Turn
    var onOpenReview: () -> Void
    var onRetryAnalysis: () -> Void

    var body: some View {
        VStack(alignment: .trailing, spacing: 0) {
            Text(turn.text)
                .font(Type.proseFont)
                .foregroundStyle(Palette.ink)
                .lineSpacing(8)
                .padding(.leading, 14)
                .padding(.trailing, 14)
                .padding(.top, 13)
                .padding(.bottom, 12)
                .frame(maxWidth: 268, alignment: .leading)
                .background(Palette.tint, in: bubbleShape(tailLeft: false))

            ScoreLine(turn: turn, onOpenReview: onOpenReview, onRetryAnalysis: onRetryAnalysis)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}

private struct ScoreLine: View {
    var turn: Turn
    var onOpenReview: () -> Void
    var onRetryAnalysis: () -> Void

    var body: some View {
        let failed = turn.analysis == .failed
        let review = turn.review
        if review == nil && !failed { EmptyView() } else {
            HStack {
                Pressable(
                    shape: AnyShape(Capsule()),
                    resting: Palette.card,
                    border: Palette.hair,
                    onClick: {
                        if failed && review == nil { onRetryAnalysis() } else { onOpenReview() }
                    }
                ) {
                    HStack(spacing: 10) {
                        if let review {
                            if let pron = review.pronunciation {
                                Text("发音 \(pron.score)")
                                    .font(Type.caption)
                                    .foregroundStyle(Palette.clayDeep)
                            }
                            Text("语法 \(review.grammar.score)")
                                .font(Type.caption)
                                .foregroundStyle(Palette.olive)
                        } else {
                            Text("分析失败 · 重试")
                                .font(Type.caption)
                                .foregroundStyle(Palette.danger)
                        }
                    }
                    .padding(.horizontal, 11)
                }
                .frame(height: 27)
            }
            .padding(.top, 6)
        }
    }
}

struct HintCard: View {
    var text: String

    var body: some View {
        HStack(alignment: .center, spacing: Spacing.iconText) {
            GlyphIcon(icon: .bulb, tint: Palette.olive)
                .frame(width: 16, height: 16)
            Text(text)
                .font(Type.proseItalic)
                .font(.system(size: 14))
                .lineSpacing(7)
                .foregroundStyle(Palette.ink)
                .lineLimit(2)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Palette.oliveTint, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

struct WordChip: View {
    var word: String
    var onClick: (() -> Void)?

    var body: some View {
        let label = Text(word)
            .font(Type.mono())
            .foregroundStyle(Palette.clayDeep)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)

        if let onClick {
            Pressable(shape: AnyShape(RoundedRectangle(cornerRadius: Radius.chip)), resting: Palette.clayTint, onClick: onClick) { label }
        } else {
            label.background(Palette.clayTint, in: RoundedRectangle(cornerRadius: Radius.chip))
        }
    }
}

struct SectionLabel: View {
    var icon: Glyph
    var text: String

    var body: some View {
        HStack(spacing: Spacing.iconText) {
            GlyphIcon(icon: icon, tint: Palette.ink3).frame(width: 14, height: 14)
            Text(text).font(Type.caption).foregroundStyle(Palette.ink2)
        }
    }
}

struct SoftCard<Content: View>: View {
    var color: Color = Palette.tint
    var radius: CGFloat = Radius.card
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .padding(14)
            .background(color, in: RoundedRectangle(cornerRadius: radius))
    }
}

struct FlagChip: View {
    var label: String
    var selected: Bool
    var onClick: @MainActor () -> Void

    var body: some View {
        Pressable(
            shape: AnyShape(Capsule()),
            resting: selected ? Palette.clay : Palette.card,
            border: selected ? nil : Palette.hairSoft,
            onClick: onClick
        ) {
            Text(label)
                .font(Type.caption)
                .foregroundStyle(selected ? .white : Palette.ink3)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
        }
    }
}

struct SheetHandle: View {
    var width: CGFloat = 36

    var body: some View {
        RoundedRectangle(cornerRadius: Radius.pill)
            .fill(Palette.hairStrong)
            .frame(width: width, height: 4)
    }
}

struct FloatingPanel<Content: View>: View {
    var radius: CGFloat = Radius.card
    var color: Color = Palette.card
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .background(color, in: RoundedRectangle(cornerRadius: radius))
            .shadow(color: Palette.shade, radius: 16, x: 0, y: 4)
    }
}

struct MenuItem {
    var label: String
    var detail: String?
    var selected: Bool = false
    var enabled: Bool = true
    var onClick: @MainActor () -> Void
}

struct ParleyMenu: View {
    var items: [MenuItem]
    var width: CGFloat = 232

    var body: some View {
        FloatingPanel {
            VStack(spacing: 0) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    Pressable(
                        shape: AnyShape(RoundedRectangle(cornerRadius: Radius.chip)),
                        resting: item.selected ? Palette.clayTint : .clear,
                        enabled: item.enabled,
                        onClick: item.onClick
                    ) {
                        HStack(alignment: .center) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.label).font(Type.uiStrong).foregroundStyle(Palette.ink)
                                if let detail = item.detail {
                                    Text(detail).font(Type.clock).foregroundStyle(Palette.ink3)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if item.selected {
                                GlyphIcon(icon: .checkMark, tint: Palette.clayDeep)
                                    .frame(width: 13, height: 13)
                            }
                        }
                        .padding(.horizontal, 10)
                        .frame(height: item.detail == nil ? 44 : nil)
                        .padding(.vertical, item.detail == nil ? 0 : 8)
                    }
                }
            }
            .padding(6)
        }
        .frame(width: width)
    }
}

struct AnyShape: Shape, @unchecked Sendable {
    private let path: (CGRect) -> Path

    init<S: Shape>(_ shape: S) {
        path = { rect in shape.path(in: rect) }
    }

    func path(in rect: CGRect) -> Path { path(rect) }
}

private func bubbleShape(tailLeft: Bool) -> AnyShape {
    UnevenRoundedRectangle(
        topLeadingRadius: tailLeft ? Radius.bubbleTail : Radius.bubble,
        bottomLeadingRadius: Radius.bubble,
        bottomTrailingRadius: Radius.bubble,
        topTrailingRadius: tailLeft ? Radius.bubble : Radius.bubbleTail
    )
    .eraseToAnyShape()
}

private extension Shape {
    func eraseToAnyShape() -> AnyShape { AnyShape(self) }
}