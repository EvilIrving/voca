import SwiftUI

enum ParleyFont {
    static let newsreader = "NewsreaderRoman-Regular"
    static let newsreaderMedium = "NewsreaderRoman-Medium"
    static let newsreaderItalic = "NewsreaderItalic-Regular"
    static let noto = "NotoSerifSC-Regular"
    static let inter = "Inter"
    static let mono = "IBMPlexMono-Regular"
}

enum Type {
    static func display(_ size: CGFloat = 20) -> Font {
        .custom(ParleyFont.newsreaderMedium, size: size)
    }

    static func prose(_ size: CGFloat = 15) -> Font {
        .custom(ParleyFont.newsreader, size: size)
    }

    static func proseItalic(_ size: CGFloat = 15) -> Font {
        .custom(ParleyFont.newsreaderItalic, size: size)
    }

    static let hero = Font.custom(ParleyFont.newsreaderMedium, size: 28)
    static let heroTagline = Font.custom(ParleyFont.newsreaderItalic, size: 16)
    static let heroCn = Font.custom(ParleyFont.noto, size: 13)

    static func ui(_ size: CGFloat = 13, weight: Font.Weight = .regular) -> Font {
        .custom(ParleyFont.inter, size: size).weight(weight)
    }

    static func mono(_ size: CGFloat = 12) -> Font {
        .custom(ParleyFont.mono, size: size)
    }

    static let display = Font.custom(ParleyFont.newsreaderMedium, size: 20)
    static let proseFont = Font.custom(ParleyFont.newsreader, size: 15)
    static let proseItalic = Font.custom(ParleyFont.newsreaderItalic, size: 15)
    static let uiFont = Font.custom(ParleyFont.inter, size: 13)
    static let uiStrong = Font.custom(ParleyFont.inter, size: 14).weight(.medium)
    static let title = Font.custom(ParleyFont.inter, size: 16).weight(.medium)
    static let caption = Font.custom(ParleyFont.inter, size: 11)
    static let monoLarge = Font.custom(ParleyFont.mono, size: 26)
    static let clock = Font.custom(ParleyFont.mono, size: 10.5)
    static let translation = Font.custom(ParleyFont.inter, size: 12.5)
    static let promptProse = Font.custom(ParleyFont.newsreader, size: 13)

    static var ink: Color { Palette.ink }
    static var ink2: Color { Palette.ink2 }
    static var ink3: Color { Palette.ink3 }
    static var ink4: Color { Palette.ink4 }

    static func captionInk3() -> (Font, Color) { (caption, Palette.ink3) }
    static func captionInk4() -> (Font, Color) { (caption, Palette.ink4) }
    static func captionClay() -> (Font, Color) { (caption, Palette.clayDeep) }
    static func uiInk() -> (Font, Color) { (uiFont, Palette.ink) }
    static func uiStrongInk2() -> (Font, Color) { (uiStrong, Palette.ink2) }
    static func uiStrongOnDark() -> (Font, Color) { (uiStrong, .white) }
    static func uiStrongClay() -> (Font, Color) { (uiStrong, Palette.clayDeep) }
    static func uiStrongDanger() -> (Font, Color) { (uiStrong, Palette.danger) }
    static func uiInk4() -> (Font, Color) { (uiFont, Palette.ink4) }
}
