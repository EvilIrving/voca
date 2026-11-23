import SwiftUI

enum MessageCommand { case speak, translate, save, copy, retry, delete }

struct MessageAction: Identifiable {
    var command: MessageCommand
    var icon: Glyph
    var label: String
    var enabled: Bool = true
    var id: String { label }
}

struct MessageActionsState {
    var text: String = ""
    var saved: Bool = false
    var expanded: Bool = false
    var translating: Bool = false
    var retryable: Bool = false
}

func messageActions(_ state: MessageActionsState) -> [MessageAction] {
    [
        MessageAction(command: .speak, icon: .speaker, label: "朗读"),
        MessageAction(
            command: .translate,
            icon: .translate,
            label: state.expanded ? "收起译文" : "翻译",
            enabled: !state.text.isEmpty && !state.translating
        ),
        MessageAction(command: .save, icon: .bookmark, label: state.saved ? "取消收藏" : "收进生词本"),
        MessageAction(command: .copy, icon: .copy, label: "复制原文", enabled: !state.text.isEmpty),
        MessageAction(command: .retry, icon: .sync, label: "重新分析", enabled: state.retryable),
        MessageAction(command: .delete, icon: .delete, label: "删除"),
    ]
}

func retryTargetOf(_ turns: [Turn], echoTurnId: String) -> String? {
    guard let index = turns.lastIndex(where: { $0.id == echoTurnId }), index > 0 else { return nil }
    let user = turns[index - 1]
    guard user.speaker == .user else { return nil }
    let needs = user.analysis == .failed || user.analysis == .none
    return needs ? user.id : nil
}

enum MessageActionsSpec {
    static let button: CGFloat = 28
    static let icon: CGFloat = 15
    static let gap: CGFloat = 4
    static let radius: CGFloat = 6
    static let bubbleGap: CGFloat = 4
}

struct MessageActionBar: View {
    var actions: [MessageAction]
    var onPick: @MainActor (MessageCommand) -> Void

    var body: some View {
        HStack(spacing: MessageActionsSpec.gap) {
            ForEach(actions) { action in
                Pressable(
                    shape: AnyShape(RoundedRectangle(cornerRadius: MessageActionsSpec.radius)),
                    enabled: action.enabled,
                    contentDescription: action.label,
                    onClick: { onPick(action.command) }
                ) {
                    GlyphIcon(
                        icon: action.icon,
                        tint: action.command == .delete ? Palette.ink4 : Palette.ink3
                    )
                    .frame(width: MessageActionsSpec.icon, height: MessageActionsSpec.icon)
                }
                .frame(width: MessageActionsSpec.button, height: MessageActionsSpec.button)
            }
        }
    }
}
