import SwiftUI

struct VocabScreen: View {
    var cards: [VocabCard]
    var onDismiss: @MainActor () -> Void
    var onSpeak: @MainActor (VocabCard) -> Void = { _ in }

    @State private var filter: VocabFilter = .all
    @State private var query = ""

    var body: some View {
        let page = vocabPage(cards: cards, todayStart: startOfLocalDay(), filter: filter, query: query)
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
                            VocabCardView(card: card) { onSpeak(card) }
                        }
                    }
                }
                .padding(.horizontal, Spacing.screen)
                .padding(.vertical, Spacing.screen)
            }
        }
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
            HStack {
                Text(card.meaning).font(Type.ui(11.5)).foregroundStyle(Palette.ink).lineLimit(1)
                Spacer()
                HStack(spacing: 6) {
                    ForEach(Array(card.tags().enumerated()), id: \.offset) { _, tag in
                        Text(tag.0)
                            .font(Type.caption)
                            .foregroundStyle(tag.1 == .type ? Palette.clayDeep : Palette.olive)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(tag.1 == .type ? Palette.clayTint : Palette.oliveTint, in: RoundedRectangle(cornerRadius: Radius.chip))
                    }
                }
            }
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