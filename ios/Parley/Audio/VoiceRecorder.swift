import AVFoundation
import Foundation

/// 四十八千赫兹采集、Opus 写 OGG、十六千赫兹上行；pause/resume 接同一只文件。
final class VoiceRecorder: @unchecked Sendable {
    static let directory = "voice"
    static let sampleRate = 48_000
    static let channels = 1
    static let bitRate = 32_000
    static let frameMs = 20
    static let frameSamples = sampleRate * frameMs / 1000
    static let uplinkSamples = 16_000 * frameMs / 1000
    static let waveformSamples = sampleRate / 10

    private static let holdIdleWaits = 2
    private static let idleWaits = 50
    private static let dbFloor = 55.0
    private static let dbSpan = 35.0
    private static let attack: Float = 0.6
    private static let release: Float = 0.15

    private nonisolated(unsafe) static weak var active: VoiceRecorder?

    static func isCapturing() -> Bool {
        active?.isRunning == true
    }

    private let lock = NSLock()
    private var session: RecordSession?
    private var engine: AVAudioEngine?
    private var capturing = false
    private var onFrame: (@Sendable ([Int16]) -> Void)?
    private var onLevel: (@Sendable (Float) -> Void)?
    private var smoothed: Float = 0
    private var captureDone: DispatchWorkItem?

    var isRunning: Bool {
        lock.withLock { capturing }
    }

  /// 打断播放后立即开录：`interruptPlayer` 会在采集配置前 `flush`。
    func start(
        cacheDirectory: URL,
        interruptPlayer player: AudioPlayer? = nil,
        onFrame: @escaping @Sendable ([Int16]) -> Void,
        onLevel: @escaping @Sendable (Float) -> Void,
    ) -> Bool {
        player?.flush()
        return lock.withLock {
            if session != nil || capturing { return false }

            let voiceDir = cacheDirectory.appendingPathComponent(Self.directory, isDirectory: true)
            do {
                try FileManager.default.createDirectory(at: voiceDir, withIntermediateDirectories: true)
            } catch {
                return false
            }

            let file = voiceDir.appendingPathComponent("\(UUID().uuidString).ogg")
            let writer: OggOpusWriter
            do {
                writer = try OggOpusWriter(fileURL: file)
                try writer.writeHeaders()
            } catch {
                return false
            }

            let encoder = OpusEncoder(sampleRate: Double(Self.sampleRate), bitRate: Self.bitRate)
            if encoder == nil {
                // 编码器不可用时仍推上行
            }

            let av = AVAudioEngine()
            do {
                try activateSession()
            } catch {
                writer.close()
                try? FileManager.default.removeItem(at: file)
                return false
            }

            let owner = RecordSession(
                file: file,
                writer: writer,
                encoder: encoder,
                resampler: Resampler(),
            )
            session = owner
            Self.active = self
            engine = av
            self.onFrame = onFrame
            self.onLevel = onLevel
            capturing = true
            smoothed = 0

            if !installTap(engine: av, owner: owner) {
                teardownEngine()
                writer.close()
                try? FileManager.default.removeItem(at: file)
                session = nil
                engine = nil
                capturing = false
                return false
            }

            do {
                try av.start()
            } catch {
                removeTap(engine: av)
                teardownEngine()
                writer.close()
                try? FileManager.default.removeItem(at: file)
                session = nil
                engine = nil
                capturing = false
                return false
            }
            return true
        }
    }

    func hold() async -> VoiceClip? {
        let owner = lock.withLock { session }
        guard let owner else { return nil }
        await stopCapture(owner: owner)
        return lock.withLock {
            guard let current = session, current === owner else { return nil }
            if owner.encoder == nil || owner.broken {
                abandon(owner)
                return nil
            }
            return clipOf(owner)
        }
    }

    func resume() -> Bool {
        lock.withLock {
            guard let owner = session, !capturing, engine != nil, !owner.broken else { return false }
            guard let av = engine else { return false }
            do {
                try activateSession()
            } catch {
                return false
            }
            if !installTap(engine: av, owner: owner) {
                return false
            }
            capturing = true
            smoothed = 0
            do {
                try av.start()
            } catch {
                removeTap(engine: av)
                capturing = false
                return false
            }
            return true
        }
    }

    func finish() async -> VoiceClip? {
        let owner = lock.withLock { session }
        guard let owner else { return nil }
        await stopCapture(owner: owner)
        return lock.withLock {
            guard let current = session, current === owner else { return nil }
            let clip: VoiceClip?
            if owner.encoder == nil || owner.broken {
                clip = nil
            } else {
                do {
                    try owner.writer.finish()
                } catch {}
                clip = clipOf(owner)
            }
            closeSession(owner)
            if clip == nil {
                try? FileManager.default.removeItem(at: owner.file)
            }
            session = nil
            onFrame = nil
            onLevel = nil
            Self.active = nil
            return clip
        }
    }

    func cancel() {
        let owner = lock.withLock { () -> RecordSession? in
            guard let held = session else { return nil }
            session = nil
            capturing = false
            onFrame = nil
            onLevel = nil
            if let av = engine {
                removeTap(engine: av)
                av.stop()
            }
            engine = nil
            captureDone?.cancel()
            captureDone = nil
            return held
        }
        guard let owner else { return }
        closeSession(owner)
        try? FileManager.default.removeItem(at: owner.file)
    }

    private func installTap(engine: AVAudioEngine, owner: RecordSession) -> Bool {
        let input = engine.inputNode
        let bus = 0
        removeTap(engine: engine)

        guard let target = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Double(Self.sampleRate),
            channels: 1,
            interleaved: true,
        ) else { return false }

        let source = input.inputFormat(forBus: bus)
        let converter: AVAudioConverter? = if source == target {
            nil
        } else {
            AVAudioConverter(from: source, to: target)
        }

        input.installTap(onBus: bus, bufferSize: AVAudioFrameCount(Self.frameSamples), format: nil) { [weak self] buffer, _ in
            guard let self else { return }
            self.process(buffer: buffer, sourceFormat: source, targetFormat: target, converter: converter, owner: owner)
        }
        return true
    }

    private func process(
        buffer: AVAudioPCMBuffer,
        sourceFormat: AVAudioFormat,
        targetFormat: AVAudioFormat,
        converter: AVAudioConverter?,
        owner: RecordSession,
    ) {
        let frame: [Int16]
        if let converter {
            guard let converted = convert(buffer: buffer, converter: converter, to: targetFormat) else { return }
            frame = converted
        } else if let channel = buffer.int16ChannelData?[0], buffer.format == targetFormat {
            frame = Array(UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength)))
        } else if let floats = buffer.floatChannelData?[0] {
            frame = (0 ..< Int(buffer.frameLength)).map {
                let sample = Int((floats[$0] * 32_768).rounded())
                return Int16(truncatingIfNeeded: sample.clamped(to: Int(Int16.min) ... Int(Int16.max)))
            }
        } else {
            return
        }

        if frame.isEmpty { return }

        var uplink: [Int16]?
        var level: Float = 0
        var frameSink: (([Int16]) -> Void)?
        var levelSink: (@Sendable (Float) -> Void)?

        lock.withLock {
            guard capturing, let current = session, current === owner else { return }
            encodeFrame(owner: owner, frame: frame)
            uplink = passThrough(owner: owner, frame: frame)
            level = levelOf(owner: owner, samples: frame.count, squares: frame.map { Double($0) * Double($0) }.reduce(0, +))
            frameSink = onFrame
            levelSink = onLevel
        }

        if let uplink, let frameSink {
            frameSink(uplink)
        }
        if let levelSink {
            let value = level
            Task { @MainActor in
                levelSink(value)
            }
        }
    }

    private func convert(buffer: AVAudioPCMBuffer, converter: AVAudioConverter, to format: AVAudioFormat) -> [Int16]? {
        let capacity = AVAudioFrameCount(
            Double(buffer.frameLength) * format.sampleRate / buffer.format.sampleRate,
        ) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return nil }
        var error: NSError?
        let status = converter.convert(to: out, error: &error) { _, outStatus in
            outStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, out.frameLength > 0, let channel = out.int16ChannelData?[0] else { return nil }
        return Array(UnsafeBufferPointer(start: channel, count: Int(out.frameLength)))
    }

    private func encodeFrame(owner: RecordSession, frame: [Int16]) {
        guard let encoder = owner.encoder, !owner.broken else { return }
        owner.encodeCarry.append(contentsOf: frame)
        while owner.encodeCarry.count >= Self.frameSamples {
            let slice = Array(owner.encodeCarry.prefix(Self.frameSamples))
            owner.encodeCarry.removeFirst(Self.frameSamples)
            do {
                if let packet = try encoder.encode(samples: slice) {
                    try owner.writer.writePacket(packet, granulePosition: owner.fedSamples)
                }
            } catch {
                owner.broken = true
                return
            }
            owner.fedSamples += Int64(Self.frameSamples)
        }
    }

    private func passThrough(owner: RecordSession, frame: [Int16]) -> [Int16]? {
        let resampled = owner.resampler.process(frame)
        if resampled.isEmpty { return nil }
        var read = 0
        var ready: [Int16]?
        while read < resampled.count {
            let take = min(Self.uplinkSamples - owner.carryFill, resampled.count - read)
            for i in 0 ..< take {
                owner.carry[owner.carryFill + i] = resampled[read + i]
            }
            owner.carryFill += take
            read += take
            if owner.carryFill == Self.uplinkSamples {
                if ready == nil { ready = Array(owner.carry) }
                owner.carryFill = 0
            }
        }
        return ready
    }

    private func levelOf(owner: RecordSession, samples: Int, squares: Double) -> Float {
        let rms = sqrt(squares / Double(max(samples, 1)))
        let db = 20.0 * log10(max(rms, 1.0) / 32_768.0)
        let target = Float(((db + Self.dbFloor) / Self.dbSpan).clamped(to: 0 ... 1))
        smoothed += (target - smoothed) * (target > smoothed ? Self.attack : Self.release)
        owner.windowSquares += squares
        owner.windowCount += samples
        if owner.windowCount >= Self.waveformSamples {
            owner.levels.append(Float(sqrt(owner.windowSquares / Double(owner.windowCount))))
            owner.windowSquares = 0
            owner.windowCount = 0
        }
        return smoothed
    }

    private func clipOf(_ owner: RecordSession) -> VoiceClip {
        VoiceClip(
            path: owner.file.path,
            durationMs: owner.fedSamples * 1000 / Int64(Self.sampleRate),
            waveform: Waveform.pack(Waveform.peaks(owner.levels)),
        )
    }

    private func stopCapture(owner: RecordSession) async {
        let av = lock.withLock { () -> AVAudioEngine? in
            guard let current = session, current === owner else { return nil }
            capturing = false
            let engine = self.engine
            if let engine {
                removeTap(engine: engine)
                engine.pause()
            }
            return engine
        }
        guard av != nil else { return }
        try? await Task.sleep(nanoseconds: 30_000_000)
    }

    private func abandon(_ owner: RecordSession) {
        closeSession(owner)
        try? FileManager.default.removeItem(at: owner.file)
        session = nil
        onFrame = nil
        onLevel = nil
    }

    private func closeSession(_ owner: RecordSession) {
        owner.encoder = nil
        owner.writer.close()
        teardownEngine()
    }

    private func teardownEngine() {
        if let av = engine {
            removeTap(engine: av)
            av.stop()
        }
        engine = nil
        if session == nil {
            Self.active = nil
        }
    }

    private func removeTap(engine: AVAudioEngine) {
        engine.inputNode.removeTap(onBus: 0)
    }

    private func activateSession() throws {
        let av = AVAudioSession.sharedInstance()
        try av.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
        try av.setPreferredSampleRate(Double(Self.sampleRate))
        try av.setActive(true)
    }
}

private final class RecordSession {
    let file: URL
    let writer: OggOpusWriter
    var encoder: OpusEncoder?
    let resampler: Resampler
    var fedSamples: Int64 = 0
    var broken = false
    var levels: [Float] = []
    var carry = [Int16](repeating: 0, count: VoiceRecorder.uplinkSamples)
    var carryFill = 0
    var windowSquares = 0.0
    var windowCount = 0
    var encodeCarry = [Int16]()

    init(file: URL, writer: OggOpusWriter, encoder: OpusEncoder?, resampler: Resampler) {
        self.file = file
        self.writer = writer
        self.encoder = encoder
        self.resampler = resampler
    }

}

private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}

private extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}

private extension NSLock {
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}