import SwiftUI

private let listBottomPadding: CGFloat = InputBarSpec.rowHeight + 18

enum TopBarSpec {
    static let iconButton: CGFloat = 32
    static let icon: CGFloat = 20
    static let nameMax: CGFloat = 108
    static let nameMin: CGFloat = 76
    static let badge: CGFloat = 16
}

struct ChatScreen: View {
    var state: ChatState
    var turns: [Turn]
    var onVocabClick: @MainActor () -> Void
    var onSessionNameTap: @MainActor () -> Void = {}
    var onReviewClick: @MainActor (Turn) -> Void
    var onRetryAnalysis: @MainActor (String) -> Void
    var vocabToday: Int
    var meter: VoiceMeter
    var onRecordPressStart: @MainActor () -> Bool
    var onRecordArmed: @MainActor (Bool) -> Void
    var onRecordPressEnd: @MainActor (Bool) -> Void
    var sessionName: String = ""
    var continuing: Bool = false
    var onExitSession: @MainActor () -> Void = {}
    var onNewSession: @MainActor () -> Void = {}
    var onSendText: @MainActor (String) -> Void = { _ in }
    var onSaveTurn: @MainActor (String, Bool) -> Void = { _, _ in }
    var onPlayTurn: @MainActor (String) -> Void = { _ in }
    var onTranslateTurn: @MainActor (String) async -> String? = { _ in nil }
    var onCopyTurn: @MainActor (String) -> Void = { _ in }
    var onDeleteTurn: @MainActor (String) -> Void = { _ in }
    var voicePreview: VoicePreview? = nil
    var clipPlayer: ClipPlayer = ClipPlayer()
    var onRecordHold: @MainActor (Bool) -> Void = { _ in }
    var onPreviewToggle: @MainActor () -> Void = {}

    @State private var translations: [String: String] = [:]
    @State private var expanded: [String: Bool] = [:]
    @State private var asking: Set<String> = []
    @State private var followTail = true

    var body: some View {
        VStack(spacing: 0) {
            topBar
            // 列表与输入条叠在同一块里：输入条只占底下胶囊那一条，上方那段留白归列表，
            // 滚到末尾时气泡停在胶囊上沿外十八。
            ZStack(alignment: .bottom) {
                if turns.isEmpty {
                    NewSessionHero().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: Spacing.bubble) {
                                ForEach(turns) { turn in
                                    row(for: turn)
                                        .id(turn.id)
                                }
                                if let hint = state.hint {
                                    HintCard(text: hint).id("hint")
                                }
                            }
                            .padding(.horizontal, Spacing.screen)
                            .padding(.top, Spacing.bubble)
                            .padding(.bottom, listBottomPadding)
                        }
                        .onScrollGeometryChange(for: Bool.self) { geo in
                            geo.contentOffset.y + geo.containerSize.height >= geo.contentSize.height - 48
                        } action: { _, atBottom in
                            if atBottom { followTail = true }
                        }
                        .simultaneousGesture(
                            DragGesture(minimumDistance: 8).onChanged { _ in followTail = false }
                        )
                        .onChange(of: scrollSignature) { _, _ in
                            guard followTail, let last = turns.last?.id else { return }
                            var transaction = Transaction()
                            transaction.animation = nil
                            withTransaction(transaction) { proxy.scrollTo(last, anchor: .bottom) }
                        }
                    }
                }
                VoiceInputBar(
                    meter: meter,
                    recording: state.recording,
                    preview: voicePreview,
                    clipPlayer: clipPlayer,
                    onPressStart: onRecordPressStart,
                    onArmed: onRecordArmed,
                    onPressEnd: onRecordPressEnd,
                    onHold: onRecordHold,
                    onPreviewToggle: onPreviewToggle,
                    onSendText: onSendText
                )
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Palette.paper)
    }

    private var scrollSignature: String {
        let tail = turns.last
        return "\(turns.count)_\(tail?.id ?? "")_\(tail?.text.count ?? 0)_\(state.hint != nil)"
    }

    @ViewBuilder
    private func row(for turn: Turn) -> some View {
        switch turn.speaker {
        case .echo:
            EchoRow(
                turn: turn,
                translation: translations[turn.id] ?? turn.translation,
                expanded: expanded[turn.id] == true,
                translating: asking.contains(turn.id),
                onPlay: { onPlayTurn(turn.id) },
                onTranslate: { translate(turn) },
                onSave: { onSaveTurn(turn.id, !turn.saved) },
                onCopy: { onCopyTurn(turn.id) },
                onRetry: {
                    if let id = retryTargetOf(turns, echoTurnId: turn.id) { onRetryAnalysis(id) }
                },
                onDelete: { onDeleteTurn(turn.id) }
            )
        case .user:
            UserBubble(
                turn: turn,
                onOpenReview: { onReviewClick(turn) },
                onRetryAnalysis: { onRetryAnalysis(turn.id) }
            )
        }
    }

    private func translate(_ turn: Turn) {
        if expanded[turn.id] == true {
            expanded[turn.id] = false
        } else if let existing = translations[turn.id] {
            expanded[turn.id] = true
            _ = existing
        } else if asking.contains(turn.id) {
            return
        } else {
            asking.insert(turn.id)
            Task {
                let translated = await onTranslateTurn(turn.text)
                asking.remove(turn.id)
                if let translated {
                    translations[turn.id] = translated
                    expanded[turn.id] = true
                }
            }
        }
    }

    private var topBar: some View {
        HStack {
            if continuing {
                BackButton(onDismiss: onExitSession)
            } else if showNewSessionAction(continuing: continuing, turnCount: turns.count) {
                ParleyIconButton(icon: .chatAdd, contentDescription: "新会话", onClick: { onNewSession() }, size: TopBarSpec.iconButton, iconSize: TopBarSpec.icon, tint: Palette.ink2)
            }
            Spacer().frame(width: 4)
            if !sessionName.isEmpty {
                Pressable(shape: AnyShape(Capsule()), resting: .clear, pressing: .clear, contentDescription: "会话记录", onClick: onSessionNameTap) {
                    Text(sessionName).font(Type.uiStrong).foregroundStyle(Palette.ink).lineLimit(1)
                }
                .frame(minWidth: TopBarSpec.nameMin, maxWidth: TopBarSpec.nameMax)
            }
            Spacer()
            ZStack(alignment: .topTrailing) {
                ParleyIconButton(icon: .book, contentDescription: "生词库", onClick: onVocabClick, size: TopBarSpec.iconButton, iconSize: TopBarSpec.icon, tint: Palette.ink2)
                if vocabToday > 0 {
                    Text(vocabToday > 99 ? "99" : "\(vocabToday)")
                        .font(.system(size: 9))
                        .foregroundStyle(.white)
                        .frame(width: TopBarSpec.badge, height: TopBarSpec.badge)
                        .background(Palette.clay, in: Circle())
                        .offset(x: 4, y: -4)
                }
            }
        }
        .padding(.horizontal, Spacing.screen)
        .frame(height: 44)
    }
}

struct NewSessionHero: View {
    var body: some View {
        VStack {
            Spacer().frame(maxHeight: .infinity).layoutPriority(0.38)
            VStack(spacing: 8) {
                Text("Parley").font(Type.hero).foregroundStyle(Palette.ink)
                Text("Speak freely, learn naturally.").font(Type.heroTagline).foregroundStyle(Palette.ink2).multilineTextAlignment(.center)
                Text("自由开口，自然习得").font(Type.heroCn).foregroundStyle(Palette.ink4)
            }
            Spacer().frame(maxHeight: .infinity).layoutPriority(0.62)
            DoubleChevronDown()
            // 箭头压在胶囊行上沿外一百：输入条浮在列表底上，不再占掉一百二。
            Spacer().frame(height: InputBarSpec.rowHeight + 100)
        }
    }
}

private struct DoubleChevronDown: View {
    var body: some View {
        DisplayPulse { _ in
            let period = 1.3
            let t = Date().timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period)
            let triangle = t < 0.65 ? t / 0.65 : 2 - t / 0.65
            let eased = triangle * triangle * (3 - 2 * triangle)
            let bounceOffset = eased * 6
            let topAlpha = 0.35 + eased * 0.4
            let bottomAlpha = 0.85 + eased * 0.15
            Canvas { ctx, size in
                drawChevron(ctx: &ctx, y: bounceOffset + 3, alpha: topAlpha, size: size)
                drawChevron(ctx: &ctx, y: bounceOffset + 11, alpha: bottomAlpha, size: size)
            }
        }
        .frame(width: 20, height: 32)
        .allowsHitTesting(false)
    }

    private func drawChevron(ctx: inout GraphicsContext, y: CGFloat, alpha: CGFloat, size: CGSize) {
        var path = Path()
        path.move(to: CGPoint(x: 3, y: y + 3))
        path.addLine(to: CGPoint(x: 10, y: y + 10))
        path.addLine(to: CGPoint(x: 17, y: y + 3))
        ctx.stroke(path, with: .color(Palette.clay.opacity(alpha)), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
    }
}

private struct EchoRow: View {
    var turn: Turn
    var translation: String?
    var expanded: Bool
    var translating: Bool
    var onPlay: () -> Void
    var onTranslate: () -> Void
    var onSave: () -> Void
    var onCopy: () -> Void
    var onRetry: () -> Void
    var onDelete: () -> Void

    @State private var menuOpen = false

    var body: some View {
        VStack {
            EchoTurn(turn: turn, translation: translation, expanded: expanded, onLongPress: { menuOpen = true })
            if menuOpen {
                MessageMenu(
                    actions: messageMenu(MessageMenuState(
                        text: turn.text,
                        saved: turn.saved,
                        expanded: expanded,
                        translating: translating,
                        retryable: true
                    )),
                    onPick: { command in
                        menuOpen = false
                        switch command {
                        case .speak: onPlay()
                        case .translate: onTranslate()
                        case .save: onSave()
                        case .copy: onCopy()
                        case .retry: onRetry()
                        case .delete: onDelete()
                        }
                    },
                    onDismiss: { menuOpen = false }
                )
            }
        }
    }
}