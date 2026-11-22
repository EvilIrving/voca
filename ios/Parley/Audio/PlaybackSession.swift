import Foundation

/// 一段播放会话：队列、写入线程与退出标记各自成套。
final class PlaybackSession {
    private let write: ([UInt8]) -> Void
    private let onFinished: () -> Void
    private let queue = DispatchQueue(label: "com.parley.audio-out")
    private var pending: [[UInt8]] = []
    private let seam = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var running = false

    init(write: @escaping ([UInt8]) -> Void, onFinished: @escaping () -> Void) {
        self.write = write
        self.onFinished = onFinished
    }

    func start() {
        lock.lock()
        if running {
            lock.unlock()
            return
        }
        running = true
        lock.unlock()
        queue.async { [weak self] in self?.loop() }
    }

    func offer(_ pcm: Data) {
        if pcm.isEmpty { return }
        lock.lock()
        pending.append([UInt8](pcm))
        lock.unlock()
        seam.signal()
    }

    func clear() {
        lock.lock()
        pending.removeAll()
        lock.unlock()
    }

    func finish() {
        lock.lock()
        pending.removeAll()
        pending.append([])
        lock.unlock()
        seam.signal()
    }

    private func loop() {
        defer { onFinished() }
        while true {
            lock.lock()
            let chunk: [UInt8]? = if pending.isEmpty {
                nil
            } else {
                pending.removeFirst()
            }
            lock.unlock()

            if let chunk {
                if chunk.isEmpty { break }
                write(chunk)
                continue
            }

            seam.wait()
        }
        lock.lock()
        running = false
        lock.unlock()
    }
}