import SwiftUI

enum SessionDrawerSpec {
    static let widthRatio: CGFloat = 0.82
    static let maxWidth: CGFloat = 320
    static let peek: CGFloat = 48
    static let searchButton: CGFloat = 36
    static let searchIcon: CGFloat = 20
    static let searchFieldHeight: CGFloat = 36
    static let newRowHeight: CGFloat = 52
    static let newGlyph: CGFloat = 24
    static let rowHeight: CGFloat = 44
    static let settingsButton: CGFloat = 44
    static let settingsIcon: CGFloat = 22
    static let swipeGuardBottom: CGFloat = 96
    static let headerHeight: CGFloat = 44
    static let headerGap: CGFloat = 8
    static let listGap: CGFloat = 14
    static let dockPadding: CGFloat = 12
    static let velocityThreshold: CGFloat = 250
    static let openMs = 0.3
    static let closeMs = 0.24
    static let contentScale: CGFloat = 0.94
    static let veilAlpha: CGFloat = 0.6
    static let cardShadow: CGFloat = 20

    static func cardRadius(progress: CGFloat) -> CGFloat {
        let p = min(max(progress, 0), 1)
        if p <= 0.5 {
            return Radius.drawerCardMid * (p / 0.5)
        }
        return Radius.drawerCardMid + (Radius.drawerCard - Radius.drawerCardMid) * ((p - 0.5) / 0.5)
    }
}

func drawerWidth(_ screen: CGFloat) -> CGFloat {
    min(SessionDrawerSpec.maxWidth, screen * SessionDrawerSpec.widthRatio, screen - SessionDrawerSpec.peek)
}

func drawerTarget(progress: CGFloat, velocity: CGFloat, threshold: CGFloat) -> Bool {
    if velocity > threshold { return true }
    if velocity < -threshold { return false }
    return progress > 0.5
}

@MainActor
final class DrawerState: ObservableObject {
    @Published private(set) var fraction: CGFloat = 0
  private var animating = false

    var current: CGFloat { fraction }

    func open() { animateTo(1, duration: SessionDrawerSpec.openMs) }
    func close() { animateTo(0, duration: SessionDrawerSpec.closeMs) }

    func dragTo(_ progress: CGFloat) {
        fraction = min(max(progress, 0), 1)
    }

    private func animateTo(_ target: CGFloat, duration: Double) {
        guard fraction != target else { return }
        withAnimation(.timingCurve(0.16, 1, 0.3, 1, duration: duration)) {
            fraction = target
        }
    }
}