import SwiftUI

enum Glyph {
    case send
    case lockOpen
    case settings
    case mic
    case speaker
    case translate
    case bookmark
    case bulb
    case dismiss
    case chevRight
    case chevDown
    case book
    case search
    case play
    case pause
    case sync
    case copy
    case delete
    case checkMark
    case arrowLeft
    case chevUp
    case edit
    case chatAdd

    var systemName: String {
        switch self {
        case .send: "paperplane.fill"
        case .lockOpen: "lock.open"
        case .settings: "gearshape"
        case .mic: "mic.fill"
        case .speaker: "speaker.wave.2"
        case .translate: "translate"
        case .bookmark: "bookmark"
        case .bulb: "lightbulb"
        case .dismiss: "xmark"
        case .chevRight: "chevron.right"
        case .chevDown: "chevron.down"
        case .book: "book"
        case .search: "magnifyingglass"
        case .play: "play.fill"
        case .pause: "pause.fill"
        case .sync: "arrow.triangle.2.circlepath"
        case .copy: "doc.on.doc"
        case .delete: "trash"
        case .checkMark: "checkmark"
        case .arrowLeft: "chevron.left"
        case .chevUp: "chevron.up"
        case .edit: "pencil"
        case .chatAdd: "plus.bubble"
        }
    }
}

struct GlyphIcon: View {
    var icon: Glyph
    var tint: Color = Palette.ink3

    var body: some View {
        Image(systemName: icon.systemName)
            .resizable()
            .scaledToFit()
            .symbolRenderingMode(.monochrome)
            .foregroundStyle(tint)
            .accessibilityHidden(true)
    }
}
