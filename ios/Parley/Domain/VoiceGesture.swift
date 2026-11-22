import Foundation

enum VoicePhase: Equatable {
    case idle, holding, locked, paused, cancelling
}

struct Shift: Equatable {
    var left: Float = 0
    var up: Float = 0
    static let none = Shift()
}

enum VoiceGesture {
    static let holdMs: Int64 = 190
    static let doubleTouchMs: Int64 = 400
    static let cancelSpanRatio: Float = 0.35
    static let cancelSpanMax: Float = 140
    static let cancelSpan: Float = 126
    static let lockSpan: Float = 96
    static let lockableProgress: Float = 0.7
    static let releaseCancelBelow: Float = 0.45
    static let shrinkFloor: Float = 0.7
    private static let follow: Float = 0.3
    private static let levelFollow: Float = 0.1

    static func cancelSpan(bandWidth: Float) -> Float {
        min(bandWidth * cancelSpanRatio, cancelSpanMax)
    }

    static func startsRecordingAfter(heldMs: Int64) -> Bool { heldMs >= holdMs }

    static func pressGap(atMs: Int64, lastPressAtMs: Int64) -> Int64 {
        lastPressAtMs == Int64.min ? Int64.max : atMs - lastPressAtMs
    }

    static func ignoredAsDoubleTouch(gapMs: Int64) -> Bool { gapMs >= 0 && gapMs < doubleTouchMs }

    static func cancelProgress(left: Float, span: Float = cancelSpan) -> Float {
        min(max(1 - left / span, 0), 1)
    }

    static func cancelsOnRelease(progress: Float) -> Bool { progress < releaseCancelBelow }
    static func cancelsWhileDragging(left: Float, span: Float = cancelSpan) -> Bool { left >= span }
    static func locksOnRelease(up: Float, progress: Float) -> Bool {
        up >= lockSpan && progress >= lockableProgress
    }
    static func locksWhileDragging(up: Float, progress: Float) -> Bool {
        up >= lockSpan && progress >= lockableProgress
    }
    static func lockProgress(up: Float) -> Float { min(max(up / lockSpan, 0), 1) }
    static func circleScale(progress: Float) -> Float {
        min(max(shrinkFloor + (1 - shrinkFloor) * progress, shrinkFloor), 1)
    }
    static func hintAlpha(progress: Float) -> Float { min(max(progress, 0), 1) }
    static func follow(_ current: Float, _ target: Float) -> Float {
        max(0, current * (1 - follow) + target * follow)
    }
    static func smoothLevel(current: Float, input: Float) -> Float {
        current * (1 - levelFollow) + input * levelFollow
    }
}

func formatBarClock(_ ms: Int64) -> String {
    let total = ms / 1000
    return String(format: "%d:%02d,%d", total / 60, total % 60, (ms % 1000) / 100)
}

enum VoiceInputEvent {
    case down(atMs: Int64)
    case held(atMs: Int64)
    case move(left: Float, up: Float, leftSpeed: Float, upSpeed: Float, span: Float)
    case up(leftSpeed: Float, upSpeed: Float, span: Float)
    case send
    case cancel
    case pause
    case resume
    case turnEnded
}

enum VoiceEffect: Equatable {
    case startRecording
    case stageCancel(Bool)
    case finish(cancelled: Bool)
    case openDraft
}

struct VoiceGestureState: Equatable {
    var phase: VoicePhase = .idle
    var pressed: Bool = false
    var pressedAtMs: Int64 = 0
    var lastPressAtMs: Int64 = Int64.min
    var raw: Shift = .none
    var shift: Shift = .none
    var cancelStaged: Bool = false

    var cancelling: Bool { phase == .cancelling || cancelStaged }
}

struct VoiceReduction {
    var state: VoiceGestureState
    var effects: [VoiceEffect] = []
}

extension VoiceGestureState {
    func reduce(_ event: VoiceInputEvent) -> VoiceReduction {
        switch event {
        case .down(let atMs): return down(atMs)
        case .held(let atMs): return held(atMs)
        case .move(let left, let up, _, _, let span): return move(left: left, up: up, span: span)
        case .up(_, _, let span): return up(span: span)
        case .send: return finish(cancelled: false)
        case .cancel: return finish(cancelled: true)
        case .pause:
            return phase == .locked ? VoiceReduction(state: copy(phase: .paused, pressed: false)) : VoiceReduction(state: self)
        case .resume:
            return phase == .paused ? VoiceReduction(state: copy(phase: .locked)) : VoiceReduction(state: self)
        case .turnEnded:
            return phase == .idle ? VoiceReduction(state: self) : VoiceReduction(state: VoiceGestureState(lastPressAtMs: lastPressAtMs))
        }
    }

    private func down(_ atMs: Int64) -> VoiceReduction {
        if pressed { return VoiceReduction(state: self) }
        let usable = phase == .idle && !VoiceGesture.ignoredAsDoubleTouch(gapMs: VoiceGesture.pressGap(atMs: atMs, lastPressAtMs: lastPressAtMs))
        return VoiceReduction(state: copy(
            pressed: usable,
            pressedAtMs: atMs,
            lastPressAtMs: atMs,
            raw: .none,
            shift: .none,
            cancelStaged: false
        ))
    }

    private func held(_ atMs: Int64) -> VoiceReduction {
        if !pressed || phase != .idle { return VoiceReduction(state: self) }
        if !VoiceGesture.startsRecordingAfter(heldMs: atMs - pressedAtMs) { return VoiceReduction(state: self) }
        return VoiceReduction(state: copy(phase: .holding), effects: [.startRecording])
    }

    private func move(left: Float, up: Float, span: Float) -> VoiceReduction {
        if !pressed || phase == .idle { return VoiceReduction(state: self) }
        let raw = Shift(left: max(0, left), up: max(0, up))
        let followed = Shift(
            left: VoiceGesture.follow(shift.left, raw.left),
            up: VoiceGesture.follow(shift.up, raw.up)
        )
        if phase != .holding {
            return VoiceReduction(state: copy(raw: raw, shift: followed))
        }
        let progress = VoiceGesture.cancelProgress(left: raw.left, span: span)
        let lockNow = VoiceGesture.locksWhileDragging(up: raw.up, progress: progress)
        let cancelNow = !lockNow && VoiceGesture.cancelsWhileDragging(left: raw.left, span: span)
        let staged = !lockNow && VoiceGesture.cancelsOnRelease(progress: progress)
        let next: VoiceGestureState
        if lockNow {
            next = copy(phase: .locked, raw: raw, shift: followed, cancelStaged: false)
        } else if cancelNow {
            next = copy(phase: .cancelling, raw: raw, shift: followed, cancelStaged: true)
        } else {
            next = copy(raw: raw, shift: followed, cancelStaged: staged)
        }
        var effects: [VoiceEffect] = []
        if !cancelNow && staged != cancelStaged { effects.append(.stageCancel(staged)) }
        if cancelNow { effects.append(.finish(cancelled: true)) }
        return VoiceReduction(state: next, effects: effects)
    }

    private func up(span: Float) -> VoiceReduction {
        if !pressed { return VoiceReduction(state: self) }
        let released = copy(pressed: false, raw: .none, shift: .none)
        switch phase {
        case .idle:
            return VoiceReduction(state: released, effects: [.openDraft])
        case .holding:
            let progress = VoiceGesture.cancelProgress(left: raw.left, span: span)
            let lock = VoiceGesture.locksOnRelease(up: raw.up, progress: progress)
            let cancel = !lock && VoiceGesture.cancelsOnRelease(progress: progress)
            let settle = released.copy(cancelStaged: false)
            if lock {
                return VoiceReduction(
                    state: settle.copy(phase: .locked),
                    effects: cancelStaged ? [.stageCancel(false)] : []
                )
            }
            if cancel {
                return VoiceReduction(state: settle.copy(phase: .idle), effects: [.finish(cancelled: true)])
            }
            return VoiceReduction(state: settle.copy(phase: .idle), effects: [.finish(cancelled: false)])
        case .cancelling:
            return VoiceReduction(state: released.copy(phase: .idle, cancelStaged: false))
        case .locked, .paused:
            return VoiceReduction(state: released)
        }
    }

    private func finish(cancelled: Bool) -> VoiceReduction {
        switch phase {
        case .locked, .paused:
            return VoiceReduction(
                state: copy(phase: .idle, pressed: false, raw: nil, shift: nil, cancelStaged: false),
                effects: [.finish(cancelled: cancelled)]
            )
        default:
            return VoiceReduction(state: self)
        }
    }

    private func copy(
        phase: VoicePhase? = nil,
        pressed: Bool? = nil,
        pressedAtMs: Int64? = nil,
        lastPressAtMs: Int64? = nil,
        raw: Shift? = nil,
        shift: Shift? = nil,
        cancelStaged: Bool? = nil
    ) -> VoiceGestureState {
        VoiceGestureState(
            phase: phase ?? self.phase,
            pressed: pressed ?? self.pressed,
            pressedAtMs: pressedAtMs ?? self.pressedAtMs,
            lastPressAtMs: lastPressAtMs ?? self.lastPressAtMs,
            raw: raw ?? self.raw,
            shift: shift ?? self.shift,
            cancelStaged: cancelStaged ?? self.cancelStaged
        )
    }
}
