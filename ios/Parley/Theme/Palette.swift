import SwiftUI

enum Palette {
    static let paper = Color(hex: 0xFAF9F5)
    static let card = Color.white
    static let tint = Color(hex: 0xF0EEE6)
    static let hair = Color(hex: 0xE8E3D6)
    static let hairStrong = Color(hex: 0xD8D2C0)
    static let hairSoft = hair.opacity(0.5)

    static let ink = Color(hex: 0x141413)
    static let ink2 = Color(hex: 0x3B3934)
    static let ink3 = Color(hex: 0x6C6860)
    static let ink4 = Color(hex: 0x9C978C)

    static let clay = Color(hex: 0xD97757)
    static let clayDeep = Color(hex: 0xB85B3C)
    static let clayTint = Color(hex: 0xF7ECE5)
    static let clayTintPressed = Color(hex: 0xF2E1D6)

    static let olive = Color(hex: 0x75855A)
    static let oliveTint = Color(hex: 0xEEF1E5)

    static let danger = Color(hex: 0xA8503C)
    static let shade = Color(red: 58 / 255, green: 47 / 255, blue: 38 / 255, opacity: 0.13)
}

enum Radius {
    static let bubbleTail: CGFloat = 5
    static let chip: CGFloat = 8
    static let drawerRow: CGFloat = 10
    static let control: CGFloat = 12
    static let card: CGFloat = 14
    static let vocabCard: CGFloat = 16
    static let bubble: CGFloat = 18
    static let sheet: CGFloat = 26
    static let drawerCardMid: CGFloat = 26
    static let drawerCard: CGFloat = 32
    static let pill: CGFloat = 999
}

enum Spacing {
    static let hairline: CGFloat = 2
    static let iconText: CGFloat = 8
    static let cardInner: CGFloat = 8
    static let block: CGFloat = 12
    static let bubble: CGFloat = 18
    static let screen: CGFloat = 20
    static let drawer: CGFloat = 18
    static let sheet: CGFloat = 24
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }

    func pressedTone() -> Color {
        if self == Palette.clay { return Palette.clayDeep }
        if self == Palette.clayTint { return Palette.clayTintPressed }
        if self == Palette.tint { return Palette.hair }
        return Palette.tint
    }
}
