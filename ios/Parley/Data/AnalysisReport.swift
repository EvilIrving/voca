import Foundation

func replyText(_ raw: String) -> String? {
    guard let data = raw.data(using: .utf8),
          let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return nil }
    if let choices = root["choices"] as? [[String: Any]],
       let choice = choices.first {
        if let message = choice["message"] as? [String: Any], let content = contentText(message["content"]) {
            return content
        }
        if let text = textValue(choice["text"]) { return text }
    }
    if let candidates = root["candidates"] as? [[String: Any]],
       let candidate = candidates.first,
       let content = candidate["content"] as? [String: Any],
       let parts = content["parts"] as? [[String: Any]] {
        let text = parts.compactMap { part -> String? in
            if flagValue(part["thought"]) == true { return nil }
            return textValue(part["text"])
        }.joined()
        if !text.isEmpty { return text }
    }
    return nil
}

private func contentText(_ value: Any?) -> String? {
    if let text = textValue(value) { return text }
    if let parts = value as? [[String: Any]] {
        let joined = parts.compactMap { textValue($0["text"]) }.joined()
        return joined.isEmpty ? nil : joined
    }
    return nil
}

func parseAnalysisReport(_ raw: String, original: String, turnId: String) -> TurnAnalysis? {
    guard let report = objectOf(raw), let score = scoreValue(report) else { return nil }
    let rewrites = [
        rewriteOf(register: .idiomatic, text: textValue((report["idiomatic"] as? [String: Any])?["text"]), gloss: textValue((report["idiomatic"] as? [String: Any])?["gloss"])),
        rewriteOf(register: .advanced, text: textValue((report["advanced"] as? [String: Any])?["text"]), gloss: textValue((report["advanced"] as? [String: Any])?["gloss"])),
    ].compactMap { $0 }
    return TurnAnalysis(
        review: reviewOf(original: original, score: score, note: textValue(report["note"]) ?? "", rewrites: rewrites),
        vocab: vocabCards(report["vocab"], turnId: turnId)
    )
}

func parseMemory(_ raw: String) -> String? {
    let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if text.hasPrefix("{"), let report = objectOf(text) {
        for key in ["memory", "text", "content"] {
            if let unwrapped = textValue(report[key]) { return unwrapped }
        }
    }
    return text.isEmpty ? nil : text
}

func vocabCards(_ items: Any?, turnId: String) -> [VocabCard] {
    guard let array = items as? [[String: Any]] else { return [] }
    var seen = Set<String>()
  return array.compactMap { item -> VocabCard? in
        guard let word = textValue(item["word"]) else { return nil }
        let lower = word.lowercased()
        guard seen.insert(lower).inserted else { return nil }
        let example = textValue(item["example"]) ?? ""
        return VocabCard(
            id: "\(turnId)-\(word)",
            word: word,
            phonetic: textValue(item["phonetic"]) ?? "",
            meaning: (textValue(item["meaning"]) ?? "").withoutAnnotation(),
            example: example,
            highlight: highlightOf(textValue(item["highlight"]), word: word, example: example),
            repeats: 1
        )
    }
}

func highlightOf(_ highlight: String?, word: String, example: String) -> String {
    let candidates = [highlight, word].compactMap { $0 }.filter { !$0.isEmpty }
    if let shown = candidates.first(where: { example.range(of: $0, options: .caseInsensitive) != nil }) {
        return shown
    }
    if let highlight, !highlight.isEmpty { return highlight }
    return word
}

private func objectOf(_ raw: String) -> [String: Any]? {
    guard let start = raw.firstIndex(of: "{"), let end = raw.lastIndex(of: "}") else { return nil }
    let slice = raw[start ... end]
    guard let data = String(slice).data(using: .utf8) else { return nil }
    return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
}

private func scoreValue(_ report: [String: Any]) -> Int? {
    if let s = parseScore(report["score"]) { return s }
    if let grammar = report["grammar"] as? [String: Any], let s = parseScore(grammar["score"]) { return s }
    return nil
}

private func parseScore(_ value: Any?) -> Int? {
    if let n = value as? Int { return n }
    if let n = value as? Double { return Int(n) }
    if let s = value as? String, let d = Double(s) { return Int(d) }
    return nil
}

private func textValue(_ value: Any?) -> String? {
    guard let raw = value as? String else { return nil }
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
}

private func flagValue(_ value: Any?) -> Bool? {
    guard let raw = value as? String else { return nil }
    return Bool(raw)
}