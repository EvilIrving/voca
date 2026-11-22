import Foundation

/// 接着一条历史会话往下聊时，开场要交给模型的那段材料。
/// 交出去的是一段文字流水而不是录音，早于预算的轮次就此丢掉。

/// 带进新连接的流水上限，与换连接时那份摘要同一量级。
let continuationChars = 2_400

/// 取最近 `continuationChars` 之内的对话流水：从最后一句往前数，装不下就停。
/// 一轮话都没有、或者整段都超预算时返回 nil，调用方照常按新的一段开场。
func continuationHistory(_ turns: [Turn]) -> String? {
    let lines = turns.filter { !$0.text.isEmpty }.map(MemoryRules.transcriptLine)
    guard !lines.isEmpty else { return nil }
    var kept: [String] = []
    var budget = continuationChars
    for line in lines.reversed() {
        if line.count + 1 > budget { break }
        kept.insert(line, at: 0)
        budget -= line.count + 1
    }
    guard !kept.isEmpty else { return nil }
    return "以下是这场对话之前的内容，接着往下聊，不要复述，也不要重新自我介绍。\n" + kept.joined(separator: "\n")
}

/// 没有可跨连接续上的上下文句柄的供应商，这段材料接在系统提示后面一起进 setup。
func promptWithHistory(_ prompt: String, history: String?) -> String {
    let block = history?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    guard !block.isEmpty else { return prompt }
    return prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? block : prompt + "\n\n" + block
}
