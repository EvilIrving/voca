import SwiftUI

let configRowHeight: CGFloat = 54
let optionRowHeight: CGFloat = 44
let menuWidth: CGFloat = 320

struct MenuOption: Identifiable {
    var id: String
    var note: String?
    var group: String = ""
}

struct MenuSpec {
    var options: [MenuOption]
    var selected: String
}

func voiceMenu(vendor: LiveVendor, selected: String) -> MenuSpec {
    MenuSpec(
        options: VoiceCatalog.voices(vendor).map { MenuOption(id: $0.id, note: $0.style, group: $0.group) },
        selected: selected
    )
}

func modelChoices(provider: TextProvider, analysis: AnalysisSettings) -> MenuSpec {
    MenuSpec(
        options: provider.models.map { MenuOption(id: $0.id, note: metaLine($0).isEmpty ? nil : metaLine($0)) },
        selected: analysis.of(provider.id).model
    )
}

struct ModelMenu: View {
    var spec: MenuSpec
    var onPick: @MainActor (String) -> Void
    var onDismiss: @MainActor () -> Void

    var body: some View {
        ZStack {
            Scrim(onDismiss: onDismiss)
            FloatingPanel {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(spec.options.enumerated()), id: \.element.id) { index, option in
                            let newGroup = !option.group.isEmpty && option.group != spec.options[safe: index - 1]?.group
                            if newGroup {
                                Text(option.group)
                                    .font(Type.caption)
                                    .foregroundStyle(Palette.ink4)
                                    .padding(.leading, 10)
                                    .padding(.top, 6)
                                    .padding(.bottom, 2)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            menuRow(option, selected: option.id == spec.selected)
                        }
                    }
                    .padding(6)
                }
                .frame(maxHeight: 340)
            }
            .frame(width: menuWidth)
            .padding(floatingShadowInset)
        }
    }

    private func menuRow(_ option: MenuOption, selected: Bool) -> some View {
        Pressable(shape: AnyShape(RoundedRectangle(cornerRadius: Radius.chip)), resting: selected ? Palette.clayTint : .clear, onClick: { onPick(option.id) }) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(option.id).font(Type.uiStrong).foregroundStyle(Palette.ink).lineLimit(1)
                    if let note = option.note {
                        Text(note).font(Type.clock).foregroundStyle(Palette.ink3).lineLimit(1)
                    }
                }
                Spacer()
                if selected {
                    GlyphIcon(icon: .checkMark, tint: Palette.clayDeep).frame(width: 13, height: 13)
                }
            }
            .padding(.horizontal, 10)
            .frame(height: optionRowHeight)
        }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        guard index >= 0 && index < count else { return nil }
        return self[index]
    }
}