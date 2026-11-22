import SwiftUI
import Lottie

enum InputBarSpec {
    static let height: CGFloat = 120
    static let sideInset: CGFloat = 7
    static let pillHeight: CGFloat = 48
    static let pillRadius: CGFloat = 24
    static let pillGap: CGFloat = 10
    static let actionSize: CGFloat = 48
    static let baseBottom: CGFloat = 8
    static let pillTop: CGFloat = height - baseBottom - pillHeight
    static let recordSize: CGFloat = 82
    static let micGlyph: CGFloat = 24
    static let recordMicGlyph: CGFloat = 34
    static let sendGlyph: CGFloat = 22
    static let dotSize: CGFloat = 10
    static let trashGlyph: CGFloat = 22
    static let trashSlotTop: CGFloat = 13
    static let lockWidth: CGFloat = 36
    static let lockHeight: CGFloat = 56
    static let lockRadius: CGFloat = 18
    static let floatGap: CGFloat = 8
    static let tooltipWidth: CGFloat = 96
    static let tooltipHeight: CGFloat = 32
    static let tooltipGap: CGFloat = 10
    static let pauseSize: CGFloat = 40
    static let resumeSize: CGFloat = 36
    static let liveBarCount = 18
    static let liveBarStep: CGFloat = 6
    static let liveBarWidth: CGFloat = 3
    static let liveBarMin: CGFloat = 4
    static let liveBarMax: CGFloat = 20
    static let timelineWidth: CGFloat = 236
    static let timelineHeight: CGFloat = 32
    static let timelineRadius: CGFloat = 16
    static let playSize: CGFloat = 24
    static let playGlyph: CGFloat = 12
    static let previewBarCount = 32

    static func pillWidth(_ band: CGFloat) -> CGFloat { band - sideInset * 2 - pillGap - actionSize }
    static func circleCenter(_ band: CGFloat) -> CGFloat { band - sideInset - actionSize / 2 }
}

private let growMs = 0.1
private let settleMs = 0.18
private let pauseKeyDelayMs: UInt64 = 560
private let pauseKeyFadeMs = 0.25
private let captureGraceMs: UInt64 = 250
private let binFallbackMs: UInt64 = 870
private let unplayedAlpha: CGFloat = 0.45
private let wobbleSpan: CGFloat = 3.6
private let wobbleMs = 0.5
private let stageMs = 0.16
private let stageDrop: CGFloat = 12
private let lockTilt: CGFloat = 9
private let maxLockRise: CGFloat = 40
private let tooltipFadeAt: Float = 0.8

private struct DiscardVisual: Equatable {
    var phase: VoicePhase
    var scale: Float
    var left: Float
    var token: Int64
}

struct VoiceInputBar: View {
    var meter: VoiceMeter
    var recording: Bool
    var preview: VoicePreview?
    var clipPlayer: ClipPlayer
    var onPressStart: () -> Bool
    var onArmed: (Bool) -> Void
    var onPressEnd: (Bool) -> Void
    var onHold: (Bool) -> Void = { _ in }
    var onPreviewToggle: () -> Void = {}
    var onSendText: (String) -> Void

    @State private var draft = ""
    @State private var editing = false
    @State private var gesture = VoiceGestureState()
    @State private var discard: DiscardVisual?
    @State private var discardToken: Int64 = 0
    @State private var leftShift: CGFloat = 0
    @State private var pauseLive = false
    @FocusState private var focused: Bool

    var body: some View {
        GeometryReader { geo in
            let band = geo.size.width
            let span = VoiceGesture.cancelSpan(bandWidth: Float(band))
            let centerX = InputBarSpec.circleCenter(band)
            let pillW = InputBarSpec.pillWidth(band)

            let frozen = discard
            let phase = frozen?.phase ?? gesture.phase
            let dragging = phase == .holding || phase == .cancelling
            let progress = CGFloat(VoiceGesture.cancelProgress(left: Float(leftShift), span: span))
            let scale = CGFloat(dragging ? (frozen?.scale ?? VoiceGesture.circleScale(progress: Float(progress))) : 1)
            let diameter = dragging ? InputBarSpec.recordSize : InputBarSpec.actionSize
            let inFlight = phase == .locked || phase == .paused
            let draftReady = !draft.trimmingCharacters(in: .whitespaces).isEmpty
            let plane = inFlight || draftReady
            let capturing = (gesture.phase == .idle || gesture.phase == .holding || gesture.phase == .cancelling)
                && (!editing || draft.isEmpty)
            let micRect = CGRect(
                x: centerX - InputBarSpec.actionSize / 2,
                y: InputBarSpec.pillTop,
                width: InputBarSpec.actionSize,
                height: InputBarSpec.actionSize
            )
            let passThrough = (gesture.phase == .idle && draftReady) ? micRect : .zero

            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: InputBarSpec.pillRadius)
                    .fill(Palette.card)
                    .overlay(RoundedRectangle(cornerRadius: InputBarSpec.pillRadius).stroke(Palette.hairSoft, lineWidth: 1))
                    .frame(width: pillW, height: InputBarSpec.pillHeight)
                    .offset(x: InputBarSpec.sideInset, y: InputBarSpec.pillTop)
                    .overlay {
                        phaseContent(progress: Float(progress), pillW: pillW, frozen: frozen)
                            .offset(x: InputBarSpec.sideInset, y: InputBarSpec.pillTop)
                            .frame(width: pillW, height: InputBarSpec.pillHeight)
                    }

                if dragging && frozen == nil {
                    BlobCanvas(meter: meter, progress: Float(progress), centerX: centerX, pillTop: InputBarSpec.pillTop)
                }

                actionCircle(
                    diameter: diameter,
                    scale: scale,
                    dragging: dragging,
                    plane: plane,
                    phase: phase,
                    draftReady: draftReady,
                    centerX: centerX
                )

                let holding = phase == .holding && gesture.raw.left <= 0
                let lockProgress = CGFloat(VoiceGesture.lockProgress(up: gesture.raw.up))
                lockChip(visible: holding, progress: lockProgress, travel: CGFloat(gesture.raw.up), centerX: centerX)
                tooltip(visible: holding, dim: 1 - max(0, min(1, (lockProgress - CGFloat(tooltipFadeAt)) / (1 - CGFloat(tooltipFadeAt)))), centerX: centerX)
                HoldKey(
                    visible: phase == .locked,
                    delayMs: pauseKeyDelayMs,
                    size: InputBarSpec.pauseSize,
                    icon: .pause,
                    label: "暂停收音",
                    centerX: centerX
                ) {
                    dispatch(.pause, span: span)
                }
                HoldKey(
                    visible: phase == .paused,
                    delayMs: 0,
                    size: InputBarSpec.resumeSize,
                    icon: .mic,
                    label: "继续收音",
                    centerX: centerX
                ) {
                    dispatch(.resume, span: span)
                }

                if capturing {
                    VoiceGestureOverlay(
                        span: CGFloat(span),
                        enabled: true,
                        hitRect: micRect,
                        passThrough: passThrough
                    ) { event in
                        dispatch(event, span: span)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .frame(width: band, height: InputBarSpec.height)
            .onChange(of: gesture.shift.left) { _, v in
                withAnimation(dragging ? .linear(duration: 0) : .easeOut(duration: settleMs)) {
                    leftShift = frozen != nil ? CGFloat(frozen!.left) : CGFloat(v)
                }
            }
            .onChange(of: recording) { _, rec in
                Task {
                    let open = gesture.phase
                    if open == .idle || rec { return }
                    try? await Task.sleep(nanoseconds: captureGraceMs * 1_000_000)
                    if !recording && gesture.phase == open { dispatch(.turnEnded, span: span) }
                }
            }
            .onChange(of: discardToken) { _, token in
                guard discard != nil else { return }
                Task {
                    try? await Task.sleep(nanoseconds: binFallbackMs * 1_000_000)
                    discard = nil
                    onPressEnd(true)
                }
            }
        }
        .frame(height: InputBarSpec.height)
    }

    private func dispatch(_ event: VoiceInputEvent, span: Float) {
        if case .down = event, discard != nil {
            discard = nil
            onPressEnd(true)
        }
        let before = gesture
        let reduction = gesture.reduce(event)
        gesture = reduction.state
        if gesture.phase == .paused && before.phase != .paused { onHold(true) }
        if before.phase == .paused && gesture.phase != .paused { onHold(false) }
        for effect in reduction.effects {
            switch effect {
            case .startRecording:
                editing = false
                focused = false
                if !onPressStart() {
                    gesture = gesture.reduce(.turnEnded).state
                }
            case .stageCancel(let staged):
                onArmed(staged)
            case .finish(let cancelled):
                if cancelled {
                    discardToken += 1
                    let progress = VoiceGesture.cancelProgress(left: before.shift.left, span: span)
                    discard = DiscardVisual(
                        phase: before.phase == .paused ? .paused : .cancelling,
                        scale: before.phase == .paused ? 1 : VoiceGesture.circleScale(progress: progress),
                        left: before.phase == .paused ? 0 : before.shift.left,
                        token: discardToken
                    )
                } else {
                    onPressEnd(false)
                }
            case .openDraft:
                if draft.isEmpty {
                    editing = true
                    focused = true
                }
            }
        }
    }

    @ViewBuilder
    private func phaseContent(progress: Float, pillW: CGFloat, frozen: DiscardVisual?) -> some View {
        switch frozen?.phase ?? gesture.phase {
        case .idle:
            draftRow
        case .holding, .cancelling:
            recordingRow(progress: frozen != nil ? 0 : progress, pillW: pillW, binPlaying: frozen != nil)
        case .locked:
            lockedRow
        case .paused:
            pausedRow(binPlaying: frozen != nil)
        }
    }

    private var draftRow: some View {
        HStack {
            if editing {
                TextField("", text: $draft, prompt: Text("说点什么...").foregroundStyle(Palette.ink3))
                    .font(Type.uiFont)
                    .foregroundStyle(Palette.ink)
                    .focused($focused)
                    .onSubmit(sendDraft)
            } else {
                Pressable(shape: AnyShape(Rectangle()), contentDescription: "编辑草稿", onClick: { editing = true; focused = true }) {
                    Text(draft.isEmpty ? "说点什么..." : draft)
                        .font(Type.uiFont)
                        .foregroundStyle(draft.isEmpty ? Palette.ink3 : Palette.ink)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(.leading, 20)
        .padding(.trailing, 12)
    }

    private func recordingRow(progress: Float, pillW: CGFloat, binPlaying: Bool) -> some View {
        let staged = progress < VoiceGesture.releaseCancelBelow
        let sliding = progress < 1 || binPlaying
        let tone = staged ? Palette.clayDeep : Palette.ink3
        return ZStack(alignment: .leading) {
            if sliding {
                trashSlot(playing: binPlaying, tint: tone, left: 16, onClick: nil)
                    .offset(y: InputBarSpec.trashSlotTop)
                MeterClock(meter: meter, staged: staged)
                    .offset(x: 48)
            } else {
                Circle().fill(Palette.clayDeep).frame(width: InputBarSpec.dotSize, height: InputBarSpec.dotSize).offset(x: 16)
                MeterClock(meter: meter, staged: false).offset(x: 36)
            }
            SlideHint(
                progress: progress,
                pillW: pillW,
                leftShift: leftShift,
                tone: tone,
                staged: staged,
                sliding: sliding
            )
            .offset(x: 156)
        }
    }

    private var lockedRow: some View {
        ZStack(alignment: .leading) {
            Pressable(shape: AnyShape(Rectangle()), onClick: { dispatch(.cancel, span: VoiceGesture.cancelSpan) }) {
                Text("取消").font(Type.uiStrong).foregroundStyle(Palette.clayDeep).padding(.horizontal, 2).padding(.vertical, 6)
            }.offset(x: 16)
            Circle().fill(Palette.clayDeep).frame(width: InputBarSpec.dotSize).offset(x: 74)
            MeterClock(meter: meter, staged: false).offset(x: 94)
            LiveBars(meter: meter).frame(width: InputBarSpec.liveBarStep * CGFloat(InputBarSpec.liveBarCount - 1) + InputBarSpec.liveBarWidth, height: InputBarSpec.liveBarMax).offset(x: 162)
        }
    }

    private func pausedRow(binPlaying: Bool) -> some View {
        ZStack(alignment: .leading) {
            trashSlot(playing: binPlaying, tint: Palette.ink3, left: 14, onClick: { dispatch(.cancel, span: VoiceGesture.cancelSpan) })
                .offset(y: InputBarSpec.trashSlotTop)
            PreviewTimeline(preview: preview, meter: meter, clipPlayer: clipPlayer, onToggle: onPreviewToggle)
                .offset(x: 44)
        }
    }

    @ViewBuilder
    private func actionCircle(diameter: CGFloat, scale: CGFloat, dragging: Bool, plane: Bool, phase: VoicePhase, draftReady: Bool, centerX: CGFloat) -> some View {
        let fill: Color = dragging ? Palette.clay : plane ? Palette.ink : Palette.clay
        let glyph: Glyph = dragging || !plane ? .mic : .send
        let glyphSize = dragging ? InputBarSpec.recordMicGlyph : plane ? InputBarSpec.sendGlyph : InputBarSpec.micGlyph
        let label = dragging ? "松开结束" : plane ? "发送" : "按住说话"
        let y = InputBarSpec.pillTop + InputBarSpec.pillHeight / 2 - diameter / 2
        let x = centerX - diameter / 2 - leftShift

        let action: (() -> Void)? = {
            if phase == .locked || phase == .paused { return { dispatch(.send, span: VoiceGesture.cancelSpan) } }
            if phase == .idle && draftReady { return sendDraft }
            return nil
        }()

        Group {
            if let action {
                Pressable(shape: AnyShape(Circle()), resting: fill, contentDescription: label, onClick: action) {
                    GlyphIcon(icon: glyph, tint: .white).frame(width: glyphSize, height: glyphSize)
                }
            } else {
                Circle().fill(fill)
                    .overlay { GlyphIcon(icon: glyph, tint: .white).frame(width: glyphSize, height: glyphSize) }
                    .accessibilityLabel(label)
            }
        }
        .frame(width: diameter, height: diameter)
        .scaleEffect(scale)
        .offset(x: x, y: y)
        .animation(.easeOut(duration: growMs), value: diameter)
    }

    private func sendDraft() {
        let text = draft.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        draft = ""
        editing = false
        focused = false
        onSendText(text)
    }

    @ViewBuilder
    private func trashSlot(playing: Bool, tint: Color, left: CGFloat, onClick: (() -> Void)?) -> some View {
        let size: CGFloat = 45
        Group {
            if playing {
                LottieTrashOnce()
                    .frame(width: size, height: size)
                    .offset(x: (InputBarSpec.trashGlyph - size) / 2 + 7, y: (InputBarSpec.trashGlyph - size) / 2 + 6)
            } else if let onClick {
                Pressable(shape: AnyShape(Rectangle()), contentDescription: "丢掉这段录音", onClick: onClick) {
                    GlyphIcon(icon: .delete, tint: tint).frame(width: InputBarSpec.trashGlyph, height: InputBarSpec.trashGlyph)
                }
            } else {
                GlyphIcon(icon: .delete, tint: tint).frame(width: InputBarSpec.trashGlyph, height: InputBarSpec.trashGlyph)
            }
        }
        .offset(x: left)
    }

    @ViewBuilder
    private func lockChip(visible: Bool, progress: CGFloat, travel: CGFloat, centerX: CGFloat) -> some View {
        if visible {
            let lift = min(travel * 0.45, maxLockRise)
            VStack(spacing: 6) {
                GlyphIcon(icon: .chevUp, tint: Palette.ink3).frame(width: 12, height: 12)
                GlyphIcon(icon: .lockOpen, tint: Palette.clay).frame(width: 16, height: 16)
            }
            .frame(width: InputBarSpec.lockWidth, height: InputBarSpec.lockHeight)
            .background(Palette.card, in: RoundedRectangle(cornerRadius: InputBarSpec.lockRadius))
            .overlay(RoundedRectangle(cornerRadius: InputBarSpec.lockRadius).stroke(Palette.hair, lineWidth: 1))
            .rotationEffect(.degrees(Double(lockTilt * (1 - progress))))
            .scaleEffect(1 - 0.16 * progress)
            .offset(x: centerX - InputBarSpec.lockWidth / 2, y: InputBarSpec.pillTop - InputBarSpec.lockHeight - lift)
        }
    }

    @ViewBuilder
    private func tooltip(visible: Bool, dim: CGFloat, centerX: CGFloat) -> some View {
        if visible {
            Text("上滑以锁定")
                .font(Type.uiFont)
                .foregroundStyle(.white)
                .frame(width: InputBarSpec.tooltipWidth, height: InputBarSpec.tooltipHeight)
                .background(Palette.ink, in: RoundedRectangle(cornerRadius: 6))
                .opacity(dim)
                .offset(
                    x: centerX - InputBarSpec.lockWidth / 2 - InputBarSpec.tooltipGap - InputBarSpec.tooltipWidth,
                    y: InputBarSpec.pillTop - InputBarSpec.lockHeight / 2 - InputBarSpec.tooltipHeight / 2
                )
        }
    }

}

private struct SlideHint: View {
    var progress: Float
    var pillW: CGFloat
    var leftShift: CGFloat
    var tone: Color
    var staged: Bool
    var sliding: Bool

    @State private var stage: CGFloat = 0

    var body: some View {
        let drifting = !sliding && !staged
        DisplayPulse { _ in
            let period = wobbleMs * 2
            let t = Date().timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period)
            let triangle = t < wobbleMs ? t / wobbleMs : 2 - t / wobbleMs
            let wobble = drifting ? (triangle * 2 - 1) * wobbleSpan : 0
            let hintShift = -(pillW / 4) * CGFloat(1 - progress) + leftShift * 0.3 + wobble
            HStack(spacing: 4) {
                GlyphIcon(icon: .chevRight, tint: tone)
                    .frame(width: 14, height: 14)
                    .rotationEffect(.degrees(180))
                    .opacity(1 - stage)
                ZStack(alignment: .leading) {
                    Text("滑动以取消")
                        .font(Type.uiFont)
                        .foregroundStyle(tone)
                        .opacity(1 - stage)
                        .offset(y: stage * stageDrop)
                    Text("松手取消")
                        .font(Type.uiStrong)
                        .foregroundStyle(Palette.clayDeep)
                        .opacity(stage)
                        .offset(y: -(1 - stage) * stageDrop)
                }
            }
            .opacity(Double(VoiceGesture.hintAlpha(progress: progress)))
            .offset(x: hintShift)
        }
        .onAppear { stage = staged ? 1 : 0 }
        .onChange(of: staged) { _, value in
            withAnimation(.linear(duration: stageMs)) { stage = value ? 1 : 0 }
        }
    }
}

private struct HoldKey: View {
    var visible: Bool
    var delayMs: UInt64
    var size: CGFloat
    var icon: Glyph
    var label: String
    var centerX: CGFloat
    var action: () -> Void

    @State private var live = false

    var body: some View {
        Group {
            if visible {
                Pressable(
                    shape: AnyShape(Circle()),
                    resting: Palette.card,
                    border: Palette.hair,
                    enabled: live,
                    contentDescription: label,
                    onClick: action
                ) {
                    GlyphIcon(icon: icon, tint: Palette.clay).frame(width: 18, height: 18)
                }
                .frame(width: size, height: size)
                .offset(x: centerX - size / 2, y: InputBarSpec.pillTop - InputBarSpec.floatGap - size)
                .opacity(live ? 1 : 0)
            }
        }
        .onChange(of: visible) { _, value in
            live = false
            guard value else { return }
            Task {
                if delayMs > 0 {
                    try? await Task.sleep(nanoseconds: delayMs * 1_000_000)
                }
                await MainActor.run {
                    if visible {
                        withAnimation(.easeOut(duration: pauseKeyFadeMs)) { live = true }
                    }
                }
            }
        }
        .onAppear {
            if visible && delayMs == 0 { live = true }
        }
    }
}

private struct LottieTrashOnce: UIViewRepresentable {
    func makeUIView(context: Context) -> LottieAnimationView {
        let view = LottieAnimationView(name: "chat_audio_record_delete")
        view.contentMode = .scaleAspectFit
        view.loopMode = .playOnce
        view.play()
        return view
    }
    func updateUIView(_ uiView: LottieAnimationView, context: Context) {}
}

private struct MeterClock: View {
    var meter: VoiceMeter
    var staged: Bool

    var body: some View {
        DisplayPulse { _ in
            let snap = meter.snapshot()
            Text(formatBarClock(snap.elapsedMs))
                .font(Type.uiStrong)
                .foregroundStyle(staged ? Palette.clayDeep : Palette.ink)
        }
    }
}

private struct LiveBars: View {
    var meter: VoiceMeter
    var body: some View {
        DisplayPulse { _ in
            let snap = meter.snapshot()
            Canvas { ctx, size in
                _ = snap.frame
                let barW = InputBarSpec.liveBarWidth
                let step = InputBarSpec.liveBarStep
                let minH = InputBarSpec.liveBarMin
                let maxH = InputBarSpec.liveBarMax
                let centerY = size.height / 2
                for index in 0..<InputBarSpec.liveBarCount {
                    let at = snap.levels.count - InputBarSpec.liveBarCount + index
                    let level = (at >= 0 && at < snap.levels.count) ? CGFloat(snap.levels[at]) : 0
                    let h = minH + (maxH - minH) * min(max(level, 0), 1)
                    let rect = CGRect(x: CGFloat(index) * step, y: centerY - h / 2, width: barW, height: h)
                    ctx.fill(Path(roundedRect: rect, cornerRadius: barW / 2), with: .color(Palette.clay.opacity(0.75)))
                }
            }
        }
    }
}

private struct PreviewTimeline: View {
    var preview: VoicePreview?
    var meter: VoiceMeter
    var clipPlayer: ClipPlayer
    var onToggle: () -> Void

    var body: some View {
        DisplayPulse { _ in
            previewBody(duration: preview?.durationMs ?? meter.snapshot().elapsedMs)
        }
    }

    private func previewBody(duration: Int64) -> some View {
        let playing = clipPlayer.isPlaying()
        return ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: InputBarSpec.timelineRadius).fill(Palette.ink)
            WaveBars(preview: preview, meter: meter, progress: clipPlayer.progress())
            Pressable(shape: AnyShape(Circle()), resting: Palette.clay, contentDescription: playing ? "暂停试听" : "试听这段录音", onClick: onToggle) {
                GlyphIcon(icon: playing ? .pause : .play, tint: .white)
                    .frame(width: InputBarSpec.playGlyph, height: InputBarSpec.playGlyph)
            }
            .frame(width: InputBarSpec.playSize, height: InputBarSpec.playSize)
            .offset(x: 4, y: 4)
            Text(formatDuration(duration))
                .font(Type.caption)
                .foregroundStyle(Color.white.opacity(0.8))
                .offset(x: 194)
        }
        .frame(width: InputBarSpec.timelineWidth, height: InputBarSpec.timelineHeight)
    }
}

private struct WaveBars: View {
    var preview: VoicePreview?
    var meter: VoiceMeter
    var progress: Float

    var body: some View {
        Canvas { ctx, size in
            let snap = meter.snapshot()
            _ = snap.frame
            let bars = resampleBars(preview: preview, levels: snap.levels)
            let played = CGFloat(progress)
            let left: CGFloat = 36
            let right: CGFloat = 51
            let barW: CGFloat = 3
            let span = size.width - left - right - barW
            let gap = span / CGFloat(max(bars.count - 1, 1))
            let minH: CGFloat = 3
            let maxH: CGFloat = 21
            let centerY = size.height / 2
            for (index, level) in bars.enumerated() {
                let h = minH + (maxH - minH) * CGFloat(min(max(level, 0), 1))
                let playedPart = CGFloat(index) / CGFloat(max(bars.count - 1, 1)) <= played
                let rect = CGRect(x: left + CGFloat(index) * gap, y: centerY - h / 2, width: barW, height: h)
                ctx.fill(Path(roundedRect: rect, cornerRadius: barW / 2), with: .color(playedPart ? Palette.clay : Color.white.opacity(unplayedAlpha)))
            }
        }
    }
}

private struct BlobCanvas: View {
    var meter: VoiceMeter
    var progress: Float
    var centerX: CGFloat
    var pillTop: CGFloat

    @State private var rings = BlobPair()

    var body: some View {
        let reveal = min(max(progress / VoiceGesture.lockableProgress, 0), 1)
        DisplayPulse { _ in
            let snap = meter.snapshot()
            Canvas { ctx, _ in
                _ = snap.frame
                guard reveal > 0 else { return }
                let amp = snap.levels.max() ?? 0
                let center = CGPoint(x: centerX, y: pillTop + InputBarSpec.pillHeight / 2)
                rings.big.draw(
                    into: ctx,
                    center: center,
                    frame: snap.frame,
                    amplitude: amp,
                    reveal: reveal,
                    color: Palette.clay.opacity(0.18)
                )
                rings.tiny.draw(
                    into: ctx,
                    center: center,
                    frame: snap.frame,
                    amplitude: amp,
                    reveal: reveal,
                    color: Palette.clay.opacity(0.28)
                )
            }
        }
        .allowsHitTesting(false)
    }
}

private final class BlobPair {
    let tiny = BlobRing(vertices: 11, minRadius: 47, maxRadius: 56, scaleBase: 0.926)
    let big = BlobRing(vertices: 12, minRadius: 50, maxRadius: 57.2, scaleBase: 0.878)
}

/// 双层 blob：顶点数、半径抖动与缩放照安卓 BlobRing，视觉尺寸对上画板 110 / 94。
private final class BlobRing {
    private let vertices: Int
    private let minRadius: CGFloat
    private let maxRadius: CGFloat
    private let scaleBase: CGFloat
    private var radius: [CGFloat]
    private var angle: [CGFloat]
    private var nextRadius: [CGFloat]
    private var nextAngle: [CGFloat]
    private var progress: [CGFloat]
    private var speed: [CGFloat]
    private let control: CGFloat
    private var smoothed: Float = 0
    private var lastFrame: Int64 = .min

    init(vertices: Int, minRadius: CGFloat, maxRadius: CGFloat, scaleBase: CGFloat) {
        self.vertices = vertices
        self.minRadius = minRadius
        self.maxRadius = maxRadius
        self.scaleBase = scaleBase
        radius = Array(repeating: 0, count: vertices)
        angle = Array(repeating: 0, count: vertices)
        nextRadius = Array(repeating: 0, count: vertices)
        nextAngle = Array(repeating: 0, count: vertices)
        progress = Array(repeating: 0, count: vertices)
        speed = Array(repeating: 0, count: vertices)
        control = CGFloat((4.0 / 3.0) * tan(.pi / Double(2 * vertices)))
        for index in 0..<vertices {
            seed(intoRadius: &radius, intoAngle: &angle, index: index)
            seed(intoRadius: &nextRadius, intoAngle: &nextAngle, index: index)
        }
    }

    private func seed(intoRadius: inout [CGFloat], intoAngle: inout [CGFloat], index: Int) {
        let wobble = 360 / CGFloat(vertices) * 0.05
        intoRadius[index] = minRadius + CGFloat.random(in: 0...1) * (maxRadius - minRadius)
        intoAngle[index] = 360 / CGFloat(vertices) * CGFloat(index) + (CGFloat.random(in: 0...1) - 0.5) * wobble
        speed[index] = 0.017 + 0.003 * CGFloat.random(in: 0...1)
    }

    func draw(
        into ctx: GraphicsContext,
        center: CGPoint,
        frame: Int64,
        amplitude: Float,
        reveal: Float,
        color: Color
    ) {
        smoothed = smoothed * 0.9 + amplitude * 0.1
        if frame != lastFrame {
            lastFrame = frame
            for index in 0..<vertices {
                progress[index] += speed[index] * 0.8 + CGFloat(smoothed) * speed[index] * 8.2 * 1.01
                if progress[index] >= 1 {
                    progress[index] = 0
                    radius[index] = nextRadius[index]
                    angle[index] = nextAngle[index]
                    seed(intoRadius: &nextRadius, intoAngle: &nextAngle, index: index)
                }
            }
        }
        let expand = (scaleBase + 1.4 * CGFloat(smoothed)) * CGFloat(reveal)
        var path = Path()
        for index in 0..<vertices {
            let next = index + 1 < vertices ? index + 1 : 0
            let r1 = lerp(radius[index], nextRadius[index], progress[index]) * expand
            let r2 = lerp(radius[next], nextRadius[next], progress[next]) * expand
            let a1 = lerp(angle[index], nextAngle[index], progress[index])
            let a2 = lerp(angle[next], nextAngle[next], progress[next])
            let handle = control * (min(r1, r2) + (max(r1, r2) - min(r1, r2)) / 2)
            let start = rotate(x: 0, y: -r1, degrees: a1)
            let startControl = rotate(x: handle, y: -r1, degrees: a1)
            let end = rotate(x: 0, y: -r2, degrees: a2)
            let endControl = rotate(x: -handle, y: -r2, degrees: a2)
            if index == 0 {
                path.move(to: CGPoint(x: center.x + start.x, y: center.y + start.y))
            }
            path.addCurve(
                to: CGPoint(x: center.x + end.x, y: center.y + end.y),
                control1: CGPoint(x: center.x + startControl.x, y: center.y + startControl.y),
                control2: CGPoint(x: center.x + endControl.x, y: center.y + endControl.y)
            )
        }
        path.closeSubpath()
        ctx.fill(path, with: .color(color))
    }

    private func lerp(_ from: CGFloat, _ to: CGFloat, _ at: CGFloat) -> CGFloat {
        from * (1 - at) + to * at
    }

    private func rotate(x: CGFloat, y: CGFloat, degrees: CGFloat) -> CGPoint {
        let radians = degrees * .pi / 180
        let c = cos(radians)
        let s = sin(radians)
        return CGPoint(x: x * c - y * s, y: x * s + y * c)
    }
}

private func formatDuration(_ ms: Int64) -> String {
    let seconds = ms / 1000
    return String(format: "%d:%02d", seconds / 60, seconds % 60)
}

private func resampleBars(preview: VoicePreview?, levels: [Float]) -> [Float] {
    if let preview, !preview.waveform.isEmpty {
        return resample(waveAmplitudes(preview.waveform), count: InputBarSpec.previewBarCount)
    }
    return resample(levels, count: InputBarSpec.previewBarCount)
}

private func waveAmplitudes(_ waveform: Data) -> [Float] {
    let bytes = [UInt8](waveform)
    var values = Array(repeating: Float(0), count: 100)
    var bit = 0
    for index in values.indices {
        var value = 0
        for _ in 0..<5 {
            let byteIndex = bit / 8
            guard byteIndex < bytes.count else { return values }
            value = (value << 1) | Int((bytes[byteIndex] >> UInt8(7 - bit % 8)) & 1)
            bit += 1
        }
        values[index] = Float(value) / 31
    }
    return values
}

private func resample(_ values: [Float], count: Int) -> [Float] {
    guard !values.isEmpty else { return Array(repeating: 0, count: count) }
    if values.count == count { return values }
    var out = [Float]()
    let step = Float(values.count - 1) / Float(max(count - 1, 1))
    for index in 0..<count {
        let at = Float(index) * step
        let low = Int(at)
        let high = min(low + 1, values.count - 1)
        let weight = at - Float(low)
        out.append(values[low] * (1 - weight) + values[high] * weight)
    }
    return out
}