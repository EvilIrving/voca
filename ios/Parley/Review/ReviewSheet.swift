import SwiftUI

private let reviewScrim = Color(hex: 0x141413, alpha: 0.18)

struct ReviewSheet: View {
    var turn: Turn
    var onDismiss: @MainActor () -> Void
    var onSaveToVocab: @MainActor () -> Void
    var onContinue: @MainActor () -> Void
    var onReplay: @MainActor () -> Void = {}
    var onSpeak: @MainActor (String) -> Void = { _ in }

    @State private var register: Register = .idiomatic

    var body: some View {
        if let review = turn.review {
            let rewrite = review.rewrites.first(where: { $0.register == register }) ?? review.rewrites.first
            ZStack(alignment: .bottom) {
                reviewScrim.ignoresSafeArea().onTapGesture(perform: onDismiss)
                FloatingPanel(radius: Radius.sheet) {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            SheetHandle().frame(maxWidth: .infinity)
                            Spacer().frame(height: 14)
                            HStack {
                                Text("本轮评测").font(Type.display).foregroundStyle(Palette.ink)
                                Spacer()
                                ParleyIconButton(icon: .dismiss, contentDescription: "关闭", onClick: onDismiss)
                            }
                            Spacer().frame(height: 14)
                            SoftCard {
                                HStack(spacing: Spacing.block) {
                                    ParleyIconButton(icon: .play, contentDescription: "复听原文", onClick: onReplay, size: 32, iconSize: 12, tint: Palette.ink, borderColor: Palette.hairStrong)
                                    Text(review.original).font(Type.proseFont).foregroundStyle(Palette.ink).lineSpacing(8)
                                    Spacer()
                                }
                            }
                            Spacer().frame(height: 20)
                            ScoreRow(pronunciation: review.pronunciation, grammar: review.grammar)

                            if let issue = review.pronunciation?.phonemes.first {
                                Spacer().frame(height: 22)
                                SectionLabel(icon: .mic, text: "音素建议")
                                Spacer().frame(height: 10)
                                HStack(spacing: Spacing.block) {
                                    WordChip(word: "\(issue.word)  \(issue.phonetic)", onClick: nil)
                                    Text(issue.note).font(Type.caption).foregroundStyle(Palette.ink2)
                                    Spacer()
                                }
                            }

                            if let rewrite {
                                Spacer().frame(height: 22)
                                SectionLabel(icon: .bulb, text: "母语者表达")
                                Spacer().frame(height: 10)
                                HStack(spacing: 8) {
                                    ForEach([Register.idiomatic, .advanced], id: \.rawValue) { option in
                                        FlagChip(label: option.label, selected: register == option) { register = option }
                                    }
                                }
                                Spacer().frame(height: 12)
                                SoftCard {
                                    VStack(alignment: .leading, spacing: 0) {
                                        Text(rewrite.text).font(Type.proseFont).foregroundStyle(Palette.ink)
                                        Spacer().frame(height: 4)
                                        Text(rewrite.gloss).font(Type.caption).foregroundStyle(Palette.ink3)
                                        Spacer().frame(height: 10)
                                        ParleyIconButton(icon: .speaker, contentDescription: "朗读改写", onClick: { onSpeak(rewrite.text) }, size: 18, iconSize: 18, tint: Palette.ink3)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }

                            Spacer().frame(height: 22)
                            HStack(spacing: Spacing.block) {
                                ReviewAction(label: "继续对话", primary: false, action: onContinue)
                                ReviewAction(label: "收进生词本", primary: true, action: onSaveToVocab).frame(maxWidth: .infinity)
                            }
                            Spacer().frame(height: 24)
                        }
                        .padding(.horizontal, Spacing.screen)
                        .padding(.top, 10)
                    }
                }
                .frame(height: 602)
                .clipShape(UnevenRoundedRectangle(topLeadingRadius: Radius.sheet, topTrailingRadius: Radius.sheet))
            }
            .ignoresSafeArea(edges: .bottom)
        }
    }
}

private struct ScoreRow: View {
    var pronunciation: PronunciationReview?
    var grammar: GrammarReview

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            if let pronunciation {
                ScoreColumn(label: "发音", score: pronunciation.score, note: pronunciation.note, color: Palette.clayDeep)
                Rectangle().fill(Palette.hair).frame(width: 1, height: 54)
            }
            ScoreColumn(label: "语法", score: grammar.score, note: grammar.note, color: Palette.olive)
        }
    }
}

private struct ScoreColumn: View {
    var label: String
    var score: Int
    var note: String
    var color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(label).font(Type.caption).foregroundStyle(Palette.ink3)
            Spacer().frame(height: 2)
            HStack(alignment: .bottom, spacing: 4) {
                Text("\(score)").font(Type.prose(27)).foregroundStyle(color)
                Text("分").font(Type.caption).foregroundStyle(Palette.ink3).padding(.bottom, 5)
            }
            Spacer().frame(height: 4)
            Text(note).font(Type.caption).foregroundStyle(Palette.ink3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ReviewAction: View {
    var label: String
    var primary: Bool
    var action: () -> Void

    var body: some View {
        Pressable(shape: AnyShape(Capsule()), resting: primary ? Palette.clay : Palette.card, border: primary ? nil : Palette.hairSoft, onClick: action) {
            Text(label)
                .font(Type.uiStrong)
                .foregroundStyle(primary ? Color.white : Palette.ink2)
                .padding(.horizontal, 18)
                .padding(.vertical, 13)
                .frame(maxWidth: .infinity)
        }
    }
}