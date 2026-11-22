import Foundation
import SwiftUI
import UIKit

/// 录音电平和计时不进 SwiftUI 状态树。采集线程直接写入，输入条用 CADisplayLink 自己来取。
final class VoiceMeter: @unchecked Sendable {
    struct Snapshot {
        var levels: [Float]
        var frame: Int64
        var elapsedMs: Int64
    }

    static let barCount = 34

    private var lock = os_unfair_lock_s()
    private var levels = [Float](repeating: 0, count: barCount)
    private var frame: Int64 = 0
    private var startedAt: Int64 = 0
    private var frozenMs: Int64 = 0
    private var running = false

    func begin() {
        os_unfair_lock_lock(&lock)
        startedAt = monotonicMs()
        frozenMs = 0
        running = true
        levels = [Float](repeating: 0, count: Self.barCount)
        frame += 1
        os_unfair_lock_unlock(&lock)
    }

    func hold() {
        os_unfair_lock_lock(&lock)
        if running {
            frozenMs = monotonicMs() - startedAt
            running = false
        }
        os_unfair_lock_unlock(&lock)
    }

    func resume() {
        os_unfair_lock_lock(&lock)
        startedAt = monotonicMs() - frozenMs
        running = true
        os_unfair_lock_unlock(&lock)
    }

    func reset() {
        os_unfair_lock_lock(&lock)
        running = false
        frozenMs = 0
        startedAt = 0
        levels = [Float](repeating: 0, count: Self.barCount)
        frame += 1
        os_unfair_lock_unlock(&lock)
    }

    func push(_ level: Float) {
        os_unfair_lock_lock(&lock)
        if !levels.isEmpty { levels.removeFirst() }
        levels.append(level)
        frame += 1
        os_unfair_lock_unlock(&lock)
    }

    func snapshot() -> Snapshot {
        os_unfair_lock_lock(&lock)
        let elapsed: Int64
        if running {
            elapsed = max(0, monotonicMs() - startedAt)
        } else {
            elapsed = frozenMs
        }
        let snap = Snapshot(levels: levels, frame: frame, elapsedMs: elapsed)
        os_unfair_lock_unlock(&lock)
        return snap
    }
}

func monotonicMs() -> Int64 {
    Int64(ProcessInfo.processInfo.systemUptime * 1000)
}

/// 输入条专用的 120 帧节拍。一块表驱动所有挂着的岛，不进会话列表。
final class DisplayLinkView: UIView {
    var onTick: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) { fatalError() }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            DisplayPulseHub.shared.remove(self)
        } else {
            DisplayPulseHub.shared.add(self)
        }
    }

    fileprivate func step() {
        onTick?()
    }
}

final class DisplayPulseHub: @unchecked Sendable {
    static let shared = DisplayPulseHub()
    private let views = NSHashTable<DisplayLinkView>.weakObjects()
    private var link: CADisplayLink?
    private let pulse = DisplayLinkPulse()

    private init() {
        pulse.hub = self
    }

    func add(_ view: DisplayLinkView) {
        views.add(view)
        guard link == nil else { return }
        let created = CADisplayLink(target: pulse, selector: #selector(DisplayLinkPulse.fire))
        created.preferredFrameRateRange = CAFrameRateRange(minimum: 80, maximum: 120, preferred: 120)
        created.add(to: .main, forMode: .common)
        link = created
    }

    func remove(_ view: DisplayLinkView) {
        views.remove(view)
        guard views.count == 0 else { return }
        link?.invalidate()
        link = nil
    }

    fileprivate func fire() {
        views.allObjects.forEach { $0.step() }
    }
}

private final class DisplayLinkPulse: NSObject {
    weak var hub: DisplayPulseHub?
    @objc func fire() { hub?.fire() }
}

struct DisplayPulse<Content: View>: View {
    @State private var tick: UInt64 = 0
    @ViewBuilder var content: (UInt64) -> Content

    var body: some View {
        content(tick)
            .background {
                DisplayLinkHost { tick &+= 1 }
                    .frame(width: 0, height: 0)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
    }
}

private struct DisplayLinkHost: UIViewRepresentable {
    var onTick: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onTick: onTick)
    }

    func makeUIView(context: Context) -> DisplayLinkView {
        let view = DisplayLinkView()
        view.isUserInteractionEnabled = false
        view.onTick = { [weak coordinator = context.coordinator] in
            coordinator?.onTick()
        }
        return view
    }

    func updateUIView(_ uiView: DisplayLinkView, context: Context) {
        context.coordinator.onTick = onTick
        uiView.onTick = { [weak coordinator = context.coordinator] in
            coordinator?.onTick()
        }
    }

    final class Coordinator {
        var onTick: () -> Void
        init(onTick: @escaping () -> Void) { self.onTick = onTick }
    }
}
