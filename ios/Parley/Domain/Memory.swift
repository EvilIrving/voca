import Foundation

enum MemoryRules {
    static let maxChars = 800

    static func systemPromptOf(rolePrompt: String, memory: String) -> String {
        let role = rolePrompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let remembered = memoryBlock(memory)
        if remembered.isEmpty { return role }
        if role.isEmpty { return remembered }
        return "\(role)\n\n\(remembered)"
    }

    private static func memoryBlock(_ memory: String) -> String {
        let text = memory.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return "" }
        return "以下是这场对话已经记住的事，当作你自己的记忆，不要向对方复述：\n\(text)"
    }

    static func admitMemory(current: String, candidate: String?) -> String? {
        let next = candidate?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if next.isEmpty || next == current.trimmingCharacters(in: .whitespacesAndNewlines) { return nil }
        if next.count > maxChars { return nil }
        return next
    }

    static func memoryMaterial(turns: [Turn], turnId: String) -> String? {
        guard let index = turns.firstIndex(where: { $0.id == turnId }) else { return nil }
        let start = max(index - 1, 0)
        let end = min(index + 2, turns.count)
        let text = turns[start..<end]
            .filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map(transcriptLine)
            .joined(separator: "\n")
        return text.isEmpty ? nil : text
    }

    static func transcriptLine(_ turn: Turn) -> String {
        "\(turn.speaker == .user ? "学生" : "助教")：\(turn.text)"
    }
}
