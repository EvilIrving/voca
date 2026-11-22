import SwiftUI

enum MessageCommand { case speak, translate, save, copy, retry, delete }

struct MessageAction: Identifiable {
    var command: MessageCommand
    var label: String
    var enabled: Bool = true
    var id: String { label }
}

struct MessageMenuState {
    var text: String = ""
    var saved: Bool = false
    var expanded: Bool = false
    var translating: Bool = false
    var retryable: Bool = false
}

func messageMenu(_ state: MessageMenuState) -> [MessageAction] {
    var list: [MessageAction] = [
        MessageAction(command: .speak, label: "朗读"),
        MessageAction(
            command: .translate,
            label: state.expanded ? "收起译文" : "翻译",
            enabled: !state.text.isEmpty && !state.translating
        ),
        MessageAction(command: .save, label: state.saved ? "取消收藏" : "收藏"),
        MessageAction(command: .copy, label: "复制", enabled: !state.text.isEmpty),
    ]
    if state.retryable { list.append(MessageAction(command: .retry, label: "重试")) }
    list.append(MessageAction(command: .delete, label: "删除"))
    return list
}

func retryTargetOf(_ turns: [Turn], echoTurnId: String) -> String? {
    guard let index = turns.lastIndex(where: { $0.id == echoTurnId }), index > 0 else { return nil }
    let user = turns[index - 1]
    guard user.speaker == .user else { return nil }
    let needs = user.analysis == .failed || user.analysis == .none
    return needs ? user.id : nil
}

enum MessageMenuSpec {
    static let width: CGFloat = 240
    static let gap: CGFloat = 6
}

struct MessageMenu: View {
    var actions: [MessageAction]
    var onPick: @MainActor (MessageCommand) -> Void
    var onDismiss: @MainActor () -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            Scrim(onDismiss: onDismiss)
            VStack {
                ParleyMenu(
                    items: actions.map { action in
                        MenuItem(label: action.label, enabled: action.enabled) {
                            onPick(action.command)
                        }
                    },
                    width: MessageMenuSpec.width
                )
            }
            .padding(floatingShadowInset)
        }
    }
}