import Foundation

/// 取消掉的那一轮还会继续从服务端回吐识别文本与音频，界面按 turnId 丢弃这些迟到的内容，
/// 而 turnId 只有适配器能打上，所以每个适配器都要有一份「现在到达的东西算哪一轮」的记账。
final class LateTurn {
    private let windowMs: Int64
    private let now: () -> Int64

    private var owner: String?
    private var until: Int64 = 0
    private var responses = [String]()
    private var responseSet = Set<String>()

    init(windowMs: Int64, now: @escaping () -> Int64) {
        self.windowMs = windowMs
        self.now = now
    }

    func cancel(turnId: String) {
        owner = turnId
        until = now() + windowMs
        responses.removeAll()
        responseSet.removeAll()
    }

    func nextTurn() {
        if responses.isEmpty { clear() }
    }

    func bind(responseId: String) {
        if active() {
            if !responseSet.contains(responseId) {
                responses.append(responseId)
                responseSet.insert(responseId)
            }
        }
    }

    func tag(responseId: String?) -> String? {
        guard let current = owner else { return nil }
        if now() >= until {
            clear()
            return nil
        }
        if responseId == nil { return current }
        if responses.isEmpty {
            responses.append(responseId!)
            responseSet.insert(responseId!)
            return current
        }
        return responseSet.contains(responseId!) ? current : nil
    }

    func close() { clear() }

    private func active() -> Bool {
        guard owner != nil else { return false }
        if now() >= until {
            clear()
            return false
        }
        return true
    }

    private func clear() {
        owner = nil
        until = 0
        responses.removeAll()
        responseSet.removeAll()
    }
}