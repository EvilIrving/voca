import Foundation

actor MemoryLedger {
    private(set) var text: String
    private let persist: @Sendable (String) async -> Void
    private var stream: AsyncStream<String>
    private var continuation: AsyncStream<String>.Continuation?

    init(seed: String = "", persist: @escaping @Sendable (String) async -> Void = { _ in }) {
        text = seed
        self.persist = persist
        var cont: AsyncStream<String>.Continuation!
        stream = AsyncStream(String.self, bufferingPolicy: .bufferingNewest(1)) { continuation in
            cont = continuation
        }
        continuation = cont
    }

    func offer(turnId: String) {
        continuation?.yield(turnId)
    }

    func drain(
        materialOf: @Sendable (String) async -> String?,
        rewrite: @Sendable (String, String) async -> String?
    ) async {
        for await turnId in stream {
            guard let material = await materialOf(turnId) else { continue }
            guard let rewritten = await rewrite(text, material) else { continue }
            guard let next = MemoryRules.admitMemory(current: text, candidate: rewritten) else { continue }
            text = next
            await persist(next)
        }
    }

    func seedWith(value: String) async {
        guard text.isEmpty else { return }
        text = value
        if !value.isEmpty { await persist(value) }
    }
}