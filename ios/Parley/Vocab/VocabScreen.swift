import SwiftUI

private let snapTranslation: CGFloat = 40
private let snapVelocity: CGFloat = 650
private let fullSwipeFraction: CGFloat = 0.75
private let fullSwipeVelocity: CGFloat = 1000
private let deleteBlockWidth: CGFloat = 80
private let rowSpring = Animation.spring(response: 0.22, dampingFraction: 0.86)

struct VocabScreen: View {
    var cards: [VocabCard]
    var onDismiss: @MainActor () -> Void
    var onSpeak: @MainActor (VocabCard) -> Void = { _ in }
    var onDelete: @MainActor (String) -> Void = { _ in }
    var onRestore: @MainActor (VocabCard) -> Void = { _ in }

    @State private var filter: VocabFilter = .all
    @State private var query = ""
    @State private var openWord: String?

    @State private var undoCard: VocabCard?
    @State private var undoTask: Task<Void, Never>?

    private func scheduleDelete(_ card: VocabCard) {
        if openWord == card.word { openWord = nil }
        onDelete(card.word)
        undoCard = card
        undoTask?.cancel()
        undoTask = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            undoCard = nil
        }
    }

    private func performUndo() {
        undoTask?.cancel()
        undoTask = nil
        guard let restored = undoCard else { return }
        undoCard = nil
        onRestore(restored)
    }

    var body: some View {
        let page = vocabPage(cards: cards, todayStart: startOfLocalDay(), filter: filter, query: query)
        ZStack(alignment: .top) {
            VStack(spacing: 0) {
                HStack(spacing: 4) {
                    BackButton(onDismiss: onDismiss)
                    Text("生词库").font(Type.display).foregroundStyle(Palette.ink)
                    Spacer()
                }
                .padding(.horizontal, Spacing.screen)
                .frame(height: 44)

                Spacer().frame(height: 12)
                HStack(spacing: Spacing.iconText) {
                    GlyphIcon(icon: .search, tint: Palette.ink4).frame(width: 16, height: 16)
                    ZStack(alignment: .leading) {
                        if query.isEmpty {
                            Text("搜索词或音标").font(Type.uiFont).foregroundStyle(Palette.ink4)
                        }
                        TextField("", text: $query)
                            .font(Type.uiFont)
                            .foregroundStyle(Palette.ink)
                            .textInputAutocapitalization(.never)
                    }
                }
                .padding(.horizontal, 12)
                .frame(height: 40)
                .background(Palette.card, in: RoundedRectangle(cornerRadius: Radius.control))
                .overlay(RoundedRectangle(cornerRadius: Radius.control).stroke(Palette.hairSoft, lineWidth: 1))
                .padding(.horizontal, Spacing.screen)

                Spacer().frame(height: 14)
                HStack(spacing: 8) {
                    ForEach(VocabFilter.allCases, id: \.self) { option in
                        FlagChip(label: "\(option.label) \(page.counts[option])", selected: filter == option) {
                            filter = option
                        }
                    }
                    Spacer()
                }
                .padding(.horizontal, Spacing.screen)

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: Spacing.block) {
                        if page.sections.isEmpty {
                            Text(emptyHint(query: query, filter: filter))
                                .font(Type.caption)
                                .foregroundStyle(Palette.ink4)
                                .padding(.top, Spacing.screen)
                        }
                        ForEach(page.sections, id: \.bucket) { section in
                            Text(section.bucket.label).font(Type.caption).foregroundStyle(Palette.ink3)
                            ForEach(section.cards) { card in
                                SwipeableVocabCard(
                                    card: card,
                                    openWord: $openWord,
                                    onDelete: { scheduleDelete(card) },
                                    onSpeak: { onSpeak(card) }
                                )
                            }
                        }
                    }
                    .padding(.horizontal, Spacing.screen)
                    .padding(.vertical, Spacing.screen)
                }
            }

            // 乐观删除撤销胶囊浮层（5 秒窗口）
            if undoCard != nil {
                Button(action: { performUndo() }) {
                    HStack(spacing: 12) {
                        Text("已删除生词")
                            .font(Type.caption)
                            .foregroundStyle(Palette.ink2)
                        Text("撤销")
                            .font(Type.caption.weight(.bold))
                            .foregroundStyle(Palette.clay)
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 9)
                    .background(Palette.card, in: RoundedRectangle(cornerRadius: Radius.pill))
                    .overlay(RoundedRectangle(cornerRadius: Radius.pill).stroke(Palette.hairSoft, lineWidth: 1))
                    .shadow(color: Color.black.opacity(0.08), radius: 6, x: 0, y: 3)
                }
                .buttonStyle(.plain)
                .padding(.top, 52)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.18), value: undoCard?.word)
        .background(Palette.paper)
    }
}

func emptyHint(query: String, filter: VocabFilter) -> String {
    if !query.trimmingCharacters(in: .whitespaces).isEmpty { return "\(filter.label)里没有匹配的词" }
    switch filter {
    case .today: return "今天还没有新词"
    case .due: return "没有待复习的词"
    case .all: return "还没有生词，对话里出现的地道搭配会自己进来"
    }
}

private struct SwipeableVocabCard: View {
    let card: VocabCard
    @Binding var openWord: String?
    let onDelete: @MainActor () -> Void
    let onSpeak: @MainActor () -> Void

    @State private var dragOffset: CGFloat = 0
    @State private var isDragging = false
    @State private var cardWidth: CGFloat = 360

    private var isOpen: Bool { openWord == card.word }

    private var displayOffset: CGFloat {
        if isDragging {
            let base: CGFloat = isOpen ? -deleteBlockWidth : 0
            return min(0, max(-deleteBlockWidth * 1.5, base + dragOffset))
        }
        return isOpen ? -deleteBlockWidth : 0
    }

    var body: some View {
        ZStack(alignment: .trailing) {
            // 底层红色删除按钮
            Button(action: onDelete) {
                HStack(spacing: 4) {
                    GlyphIcon(icon: .delete, tint: .white)
                        .frame(width: 16, height: 16)
                    Text("删除")
                        .font(Type.caption.weight(.bold))
                        .foregroundStyle(.white)
                }
                .frame(width: deleteBlockWidth)
                .frame(maxHeight: .infinity)
                .background(Palette.danger)
            }
            .buttonStyle(.plain)
            .clipShape(RoundedRectangle(cornerRadius: Radius.vocabCard))

            // 顶层生词内容卡片
            VocabCardView(card: card, onSpeak: onSpeak)
                .offset(x: displayOffset)
                .background(
                    GeometryReader { proxy in
                        Color.clear.onAppear { cardWidth = proxy.size.width }
                    }
                )
                .gesture(
                    DragGesture(minimumDistance: 10)
                        .onChanged { value in
                            // 只响应向左或处于打开态时的手势
                            if abs(value.translation.width) > abs(value.translation.height) {
                                isDragging = true
                                dragOffset = value.translation.width
                            }
                        }
                        .onEnded { value in
                            isDragging = false
                            let translation = value.translation.width
                            let velocity = value.velocity.width
                            let base: CGFloat = isOpen ? -deleteBlockWidth : 0
                            let currentTotal = base + translation

                            let magnitude = abs(currentTotal)
                            let speed = velocity < 0 ? abs(velocity) : 0

                            withAnimation(rowSpring) {
                                if magnitude >= cardWidth * fullSwipeFraction || speed >= fullSwipeVelocity {
                                    onDelete()
                                } else if magnitude >= snapTranslation || speed >= snapVelocity {
                                    openWord = card.word
                                } else {
                                    if openWord == card.word { openWord = nil }
                                }
                                dragOffset = 0
                            }
                        }
                )
        }
        .clipShape(RoundedRectangle(cornerRadius: Radius.vocabCard))
        .onChange(of: openWord) { _, newWord in
            if newWord != card.word && !isDragging {
                withAnimation(rowSpring) { dragOffset = 0 }
            }
        }
    }
}

private struct VocabCardView: View {
    var card: VocabCard
    var onSpeak: @MainActor () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Text(card.word).font(Type.prose(19).weight(.medium)).foregroundStyle(Palette.ink)
                Text(card.phonetic).font(Type.mono(10.5)).foregroundStyle(Palette.ink3)
                Spacer()
                ParleyIconButton(icon: .speaker, contentDescription: "朗读", onClick: onSpeak, size: 32, iconSize: 16, tint: Palette.ink2)
            }
            Spacer().frame(height: 3)
            Text(card.meaning).font(Type.ui(11.5)).foregroundStyle(Palette.ink).lineLimit(1)
            Spacer().frame(height: 6)
            Text(highlightedText(card.example, highlight: card.highlight))
                .font(Type.proseItalic(13.5))
                .foregroundStyle(Palette.ink)
                .lineSpacing(6.5)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(Palette.card, in: RoundedRectangle(cornerRadius: Radius.vocabCard))
        .overlay(RoundedRectangle(cornerRadius: Radius.vocabCard).stroke(Palette.hairSoft, lineWidth: 1))
    }
}
