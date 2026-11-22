import Foundation

enum IdleCut {
    static let windowMs: Int64 = 30 * 60 * 1000
    static let checkMs: Int64 = 30_000

    static func reached(lastActivityAt: Int64, now: Int64, recording: Bool) -> Bool {
        !recording && now - lastActivityAt >= windowMs
    }
}

func openingOf(loaded: String, greeting: String) -> String {
    let trimmed = loaded.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? greeting : trimmed
}

struct Segment: Equatable {
    var id: String
    var startedAt: Int64
    var lastActivityAt: Int64
    var closed: Bool = false
}

final class SessionLedger {
    private let clock: () -> Int64
    private var opened = 0
    private(set) var current: Segment

    init(clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.clock = clock
        self.current = Segment(id: "", startedAt: 0, lastActivityAt: 0)
        self.current = newSegment()
    }

    @discardableResult
    func begin() -> Segment {
        current = newSegment()
        return current
    }

    func touch() {
        let now = clock()
        if now > current.lastActivityAt {
            current.lastActivityAt = now
        }
    }

    func closeIfOpen() -> Segment? {
        if current.closed { return nil }
        current.closed = true
        return current
    }

    func idleDue(recording: Bool) -> Bool {
        !current.closed && IdleCut.reached(lastActivityAt: current.lastActivityAt, now: clock(), recording: recording)
    }

    /// 接着一条历史会话聊：账本改用那条的标识与起点，收段时写回同一条记录，不另起一行。
    /// 起点取那条会话的开头，轮次与双项均值因此算的是这一整段而非续上的那几轮。
    @discardableResult
    func adopt(id: String, startedAt: Int64) -> Segment {
        let now = clock()
        current = Segment(id: id, startedAt: startedAt, lastActivityAt: now)
        return current
    }

    private func newSegment() -> Segment {
        let now = clock()
        opened += 1
        return Segment(id: "session-\(now)-\(opened)", startedAt: now, lastActivityAt: now)
    }
}

struct SegmentPlan {
    var prompt: String
}
