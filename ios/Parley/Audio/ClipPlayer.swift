import AVFoundation
import Foundation

/// 暂停态试听：播刚录下的 OGG。
final class ClipPlayer: @unchecked Sendable {
    private let lock = NSLock()
    private var player: AVAudioPlayer?
    private var loaded: String?

    func prepare(path: String) async -> Bool {
        await withCheckedContinuation { continuation in
            lock.lock()
            defer { lock.unlock() }
            if loaded != path || player == nil {
                releaseLocked()
                let created: AVAudioPlayer?
                do {
                    let url = URL(fileURLWithPath: path)
                    let item = try AVAudioPlayer(contentsOf: url)
                    item.prepareToPlay()
                    created = item
                } catch {
                    created = nil
                }
                guard let created else {
                    continuation.resume(returning: false)
                    return
                }
                player = created
                loaded = path
            }
            guard let current = player else {
                continuation.resume(returning: false)
                return
            }
            if !current.isPlaying {
                current.currentTime = 0
            }
            continuation.resume(returning: true)
        }
    }

    func start() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let current = player else { return false }
        return current.play()
    }

    func pause() {
        lock.lock()
        defer { lock.unlock() }
        player?.pause()
    }

    func isPlaying() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return player?.isPlaying ?? false
    }

    func progress() -> Float {
        lock.lock()
        defer { lock.unlock() }
        guard let current = player else { return 0 }
        let duration = current.duration
        if duration <= 0 { return 0 }
        return Float(current.currentTime / duration).clamped(to: 0 ... 1)
    }

    func release() {
        lock.lock()
        defer { lock.unlock() }
        releaseLocked()
    }

    private func releaseLocked() {
        player?.stop()
        player = nil
        loaded = nil
    }
}

private extension Float {
    func clamped(to range: ClosedRange<Float>) -> Float {
        min(max(self, range.lowerBound), range.upperBound)
    }
}