import Foundation

/// 每轮助手音频落成裸 PCM，二十四千赫兹单声道十六位。
final class TurnAudioStore: @unchecked Sendable {
    static let directory = "turns"
    private static let keepDays = 7
    private static let dayMs: Int64 = 24 * 60 * 60 * 1000
    private static let unsafe = try! NSRegularExpression(pattern: "[^A-Za-z0-9_-]")

    private let root: URL
    private let queue = DispatchQueue(label: "com.parley.turn-audio")

    init(container: URL) {
        root = container.appendingPathComponent(Self.directory, isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    convenience init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        self.init(container: base)
    }

    func append(turnId: String, pcm: Data) async {
        if pcm.isEmpty { return }
        await withCheckedContinuation { continuation in
            queue.async {
                let url = self.fileOf(turnId)
                if FileManager.default.fileExists(atPath: url.path),
                   let handle = try? FileHandle(forWritingTo: url) {
                    try? handle.seekToEnd()
                    try? handle.write(contentsOf: pcm)
                    try? handle.close()
                } else {
                    FileManager.default.createFile(atPath: url.path, contents: pcm)
                }
                continuation.resume()
            }
        }
    }

    func path(turnId: String) -> String {
        fileOf(turnId).path
    }

    func read(turnId: String) async -> Data? {
        await withCheckedContinuation { continuation in
            queue.async {
                let url = self.fileOf(turnId)
                let data = try? Data(contentsOf: url)
                continuation.resume(returning: (data == nil || data?.isEmpty == true) ? nil : data)
            }
        }
    }

    func prune(days: Int = keepDays) async {
        await withCheckedContinuation { continuation in
            queue.async {
                let cutoff = Date().timeIntervalSince1970 * 1000 - Double(days) * Double(Self.dayMs)
                guard let items = try? FileManager.default.contentsOfDirectory(
                    at: self.root,
                    includingPropertiesForKeys: [.contentModificationDateKey],
                ) else {
                    continuation.resume()
                    return
                }
                for url in items {
                    let values = try? url.resourceValues(forKeys: [.contentModificationDateKey])
                    if let date = values?.contentModificationDate,
                       date.timeIntervalSince1970 * 1000 < cutoff {
                        try? FileManager.default.removeItem(at: url)
                    }
                }
                continuation.resume()
            }
        }
    }

    func delete(turnId: String) async {
        await withCheckedContinuation { continuation in
            queue.async {
                try? FileManager.default.removeItem(at: self.fileOf(turnId))
                continuation.resume()
            }
        }
    }

    private func fileOf(_ turnId: String) -> URL {
        root.appendingPathComponent("\(safeName(turnId)).pcm")
    }

    private func safeName(_ turnId: String) -> String {
        let range = NSRange(turnId.startIndex ..< turnId.endIndex, in: turnId)
        return Self.unsafe.stringByReplacingMatches(in: turnId, range: range, withTemplate: "_")
    }
}