import AVFoundation
import Foundation

/// 二十四千赫兹单声道十六位 PCM 流式播放；flush 清空队列并立刻停播当前缓冲。
final class AudioPlayer: @unchecked Sendable {
    static let sampleRate = 24_000

    private let lock = NSLock()
    private var output: PlayOutput?
    private let avSession = AVAudioSession.sharedInstance()
    private var interruptionObserver: NSObjectProtocol?

    init() {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: avSession,
            queue: nil,
        ) { [weak self] note in
            self?.handleInterruption(note)
        }
    }

    deinit {
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
        }
    }

    func enqueue(_ pcm: Data) {
        if pcm.isEmpty { return }
        let current = lock.withLock { output ?? startLocked() }
        current?.playback.offer(pcm)
    }

    func flush() {
        lock.withLock {
            guard let current = output else { return }
            current.playback.clear()
            current.node.stop()
            if current.engine.isRunning {
                current.node.play()
            }
        }
    }

    func stop() {
        let stopped = lock.withLock { () -> PlayOutput? in
            let current = output
            output = nil
            return current
        }
        stopped?.playback.finish()
    }

    private func startLocked() -> PlayOutput? {
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Double(Self.sampleRate),
            channels: 1,
            interleaved: true,
        ) else { return nil }

        configureSession()

        let engine = AVAudioEngine()
        let node = AVAudioPlayerNode()
        let play = PlayOutput(engine: engine, node: node, format: format)

        play.playback = PlaybackSession(
            write: { [weak play] bytes in
                guard let play else { return }
                play.schedule(bytes)
            },
            onFinished: { [weak self, weak play] in
                guard let self, let play else { return }
                self.finish(play)
            },
        )

        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        do {
            try engine.start()
            node.play()
        } catch {
            return nil
        }
        play.playback.start()
        output = play
        return play
    }

    private func finish(_ finished: PlayOutput) {
        lock.withLock {
            if output === finished {
                output = nil
            }
        }
        finished.teardown()
    }

    private func configureSession() {
        try? avSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
        try? avSession.setActive(true)
    }

    private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let kind = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: kind) else { return }
        lock.withLock {
            guard let current = output else { return }
            switch type {
            case .began:
                current.node.pause()
            case .ended:
                if let options = info[AVAudioSessionInterruptionOptionKey] as? UInt,
                   AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume) {
                    current.node.play()
                }
            @unknown default:
                break
            }
        }
    }
}

private final class PlayOutput {
    let engine: AVAudioEngine
    let node: AVAudioPlayerNode
    let format: AVAudioFormat
    var playback: PlaybackSession!

    init(engine: AVAudioEngine, node: AVAudioPlayerNode, format: AVAudioFormat) {
        self.engine = engine
        self.node = node
        self.format = format
    }

    func schedule(_ bytes: [UInt8]) {
        let frameCount = AVAudioFrameCount(bytes.count / 2)
        guard frameCount > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else { return }
        buffer.frameLength = frameCount
        bytes.withUnsafeBytes { raw in
            guard let base = raw.baseAddress, let dst = buffer.int16ChannelData?[0] else { return }
            let src = base.assumingMemoryBound(to: Int16.self)
            dst.update(from: src, count: Int(frameCount))
        }
        node.scheduleBuffer(buffer)
        if !node.isPlaying {
            node.play()
        }
    }

    func teardown() {
        node.stop()
        engine.stop()
        engine.reset()
    }
}

private extension NSLock {
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}