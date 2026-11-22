import SwiftUI
import UIKit

struct VoiceGestureOverlay: UIViewRepresentable {
    var span: CGFloat
    var enabled: Bool
    var hitRect: CGRect
    var passThrough: CGRect
    var onEvent: (VoiceInputEvent) -> Void

    func makeUIView(context: Context) -> VoiceGestureView {
        let view = VoiceGestureView()
        view.span = span
        view.hitRect = hitRect
        view.passThrough = passThrough
        view.onEvent = onEvent
        view.isUserInteractionEnabled = enabled
        return view
    }

    func updateUIView(_ uiView: VoiceGestureView, context: Context) {
        uiView.span = span
        uiView.hitRect = hitRect
        uiView.passThrough = passThrough
        uiView.onEvent = onEvent
        uiView.isUserInteractionEnabled = enabled
        if !enabled {
            uiView.cancelActiveTracking()
        }
    }
}

/// 对齐 Telegram iOS `TGModernConversationInputMicButton`：UIControl 跟手、
/// UIPanGestureRecognizer 取速度、CADisplayLink 每帧把目标位移按 0.7/0.3 吃进绘制。
/// 阈值仍走 VoiceGesture，不把源码里的 57 / −150 / −400 搬进来。
final class VoiceGestureView: UIControl, UIGestureRecognizerDelegate {
    var span: CGFloat = CGFloat(VoiceGesture.cancelSpan)
    var hitRect: CGRect = .zero
    var passThrough: CGRect = .zero
    var onEvent: ((VoiceInputEvent) -> Void)?

    private var trackingTouch: UITouch?
    private var downPoint: CGPoint = .zero
    private var targetLeft: CGFloat = 0
    private var targetUp: CGFloat = 0
    private var lastVelocity = CGPoint.zero
    private var holdDispatched = false
    private var holdTimer: Timer?
    private var downAt: TimeInterval = 0
    private var displayLink: CADisplayLink?
    private let tick = DisplayTick()
    private let pan = UIPanGestureRecognizer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isMultipleTouchEnabled = false
        isExclusiveTouch = false
        clipsToBounds = false
        tick.owner = self
        pan.addTarget(self, action: #selector(handlePan))
        pan.delegate = self
        pan.cancelsTouchesInView = false
        addGestureRecognizer(pan)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil {
            holdTimer?.invalidate()
            holdTimer = nil
            displayLink?.invalidate()
            displayLink = nil
        }
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if !isUserInteractionEnabled { return nil }
        if !bounds.contains(point) { return nil }
        if trackingTouch != nil { return self }
        if passThrough.contains(point) { return nil }
        if hitRect.contains(point) { return self }
        return nil
    }

    override func beginTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        guard trackingTouch == nil else { return false }
        trackingTouch = touch
        holdDispatched = false
        downPoint = touch.location(in: self)
        targetLeft = 0
        targetUp = 0
        lastVelocity = .zero
        downAt = CACurrentMediaTime()
        onEvent?(.down(atMs: nowMs()))
        holdTimer?.invalidate()
        let timer = Timer(
            timeInterval: Double(VoiceGesture.holdMs) / 1000,
            target: self,
            selector: #selector(holdFired),
            userInfo: nil,
            repeats: false
        )
        RunLoop.main.add(timer, forMode: .common)
        holdTimer = timer
        return true
    }

    override func continueTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        guard touch === trackingTouch else { return false }
        if !holdDispatched, CACurrentMediaTime() - downAt >= Double(VoiceGesture.holdMs) / 1000 {
            holdFired()
        }
        sample(touch)
        return true
    }

    override func endTracking(_ touch: UITouch?, with event: UIEvent?) {
        finish(touch)
        super.endTracking(touch, with: event)
    }

    override func cancelTracking(with event: UIEvent?) {
        finish(trackingTouch)
        super.cancelTracking(with: event)
    }

    func cancelActiveTracking() {
        guard trackingTouch != nil else { return }
        finish(trackingTouch)
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }

    @objc private func holdFired() {
        guard trackingTouch != nil else { return }
        holdDispatched = true
        onEvent?(.held(atMs: nowMs()))
        startDisplayLink()
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        let velocity = gesture.velocity(in: self)
        lastVelocity = CGPoint(x: max(0, -velocity.x), y: max(0, -velocity.y))
    }

    fileprivate func displayTick() {
        guard holdDispatched, trackingTouch != nil else { return }
        emitMove()
    }

    private func sample(_ touch: UITouch) {
        let point = touch.location(in: self)
        targetLeft = max(0, downPoint.x - point.x)
        targetUp = max(0, downPoint.y - point.y)
        if holdDispatched && displayLink == nil {
            emitMove()
        }
    }

    private func emitMove() {
        onEvent?(
            .move(
                left: Float(targetLeft),
                up: Float(targetUp),
                leftSpeed: Float(lastVelocity.x),
                upSpeed: Float(lastVelocity.y),
                span: Float(span)
            )
        )
    }

    private func finish(_ touch: UITouch?) {
        holdTimer?.invalidate()
        holdTimer = nil
        stopDisplayLink()
        if trackingTouch != nil {
            onEvent?(
                .up(
                    leftSpeed: Float(lastVelocity.x),
                    upSpeed: Float(lastVelocity.y),
                    span: Float(span)
                )
            )
        }
        trackingTouch = nil
        holdDispatched = false
        lastVelocity = .zero
        targetLeft = 0
        targetUp = 0
        _ = touch
    }

    private func startDisplayLink() {
        if displayLink != nil { return }
        let link = CADisplayLink(target: tick, selector: #selector(DisplayTick.step))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 80, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }
}

@MainActor
private final class DisplayTick: NSObject {
    weak var owner: VoiceGestureView?
    @objc func step() { owner?.displayTick() }
}

private func nowMs() -> Int64 {
    Int64(CACurrentMediaTime() * 1000)
}
