import Foundation

/// 取消掉的那一轮还会从服务端吐出识别文本与音频，协议里没有轮次号可以对照，
/// 唯一能用的信号是时间窗口与收尾标记：取消那一刻起的这段时间里到达的内容都算被取消那轮的。
final class CancelWindow {
    private let windowMs: Int64
    private let now: () -> Int64

    private var ids = Set<String>()
    private var idOrder = [String]()
    private var owner: String?
    private var until: Int64 = 0

    private static let maxIds = 4

    init(windowMs: Int64, now: @escaping () -> Int64) {
        self.windowMs = windowMs
        self.now = now
    }

    func cancel(turnId: String) {
        if !ids.contains(turnId) {
            idOrder.append(turnId)
            ids.insert(turnId)
        }
        while idOrder.count > Self.maxIds {
            let dropped = idOrder.removeFirst()
            ids.remove(dropped)
        }
        owner = turnId
        until = now() + windowMs
    }

    func boundary() {
        owner = nil
        until = 0
    }

    func tag() -> String? {
        guard let current = owner else { return nil }
        if now() >= until {
            owner = nil
            return nil
        }
        return current
    }

    func blacklisted(turnId: String?) -> Bool {
        guard let turnId else { return false }
        return ids.contains(turnId)
    }
}