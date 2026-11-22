import Foundation

enum ModelsJson {
    static func parse(_ raw: String) -> [TextModel] {
        guard let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data)
        else { return [] }
        return entries(root).compactMap { id, entry in
            guard let obj = entry as? [String: Any] else { return nil }
            return model(id: id, entry: obj)
        }
    }

    private static func entries(_ root: Any) -> [(String, Any)] {
        if let array = root as? [[String: Any]] { return arrayEntries(array) }
        guard let obj = root as? [String: Any] else { return [] }
        if let boxed = (obj["data"] ?? obj["models"]) {
            if let array = boxed as? [[String: Any]] { return arrayEntries(array) }
            if let map = boxed as? [String: Any] {
                return map.compactMap { key, value in
                    guard let entry = value as? [String: Any] else { return nil }
                    return (key, entry)
                }
            }
        }
        return mapEntries(obj)
    }

    private static func arrayEntries(_ array: [[String: Any]]) -> [(String, Any)] {
        array.compactMap { entry in
            guard let id = text(entry["id"]) else { return nil }
            return (id, entry)
        }
    }

    private static func mapEntries(_ root: [String: Any]) -> [(String, Any)] {
        guard !root.isEmpty, root.values.allSatisfy({ $0 is [String: Any] }) else { return [] }
        return root.map { ($0.key, $0.value) }
    }

    static func model(id: String, entry: [String: Any]) -> TextModel? {
        let modelId = normalize(id.isEmpty ? (text(entry["id"]) ?? "") : id)
        guard !modelId.isEmpty else { return nil }
        let limit = entry["limit"] as? [String: Any]
        let top = entry["top_provider"] as? [String: Any]
        return TextModel(
            id: modelId,
            context: int(entry["context_window"])
                ?? int(entry["context_length"])
                ?? int(entry["context"])
                ?? int(entry["input_token_limit"])
                ?? int(entry["max_context_length"])
                ?? int(limit?["context"])
                ?? 0,
            maxOutput: int(entry["max_output_tokens"])
                ?? int(entry["max_tokens"])
                ?? int(entry["max_completion_tokens"])
                ?? int(entry["output_token_limit"])
                ?? int(limit?["output"])
                ?? int(top?["max_completion_tokens"])
                ?? 0,
            thinking: thinking(entry) ?? false,
            defaultEffort: defaultEffort(entry),
            effortLevels: effortLevels(entry),
            releaseDate: text(entry["release_date"]) ?? text(entry["released"]) ?? text(entry["releaseDate"])
        )
    }

    static func normalize(_ id: String) -> String {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasPrefix("models/") ? String(trimmed.dropFirst("models/".count)) : trimmed
    }

    private static func defaultEffort(_ entry: [String: Any]) -> String? {
        text(entry["reasoning_effort"])
            ?? text((entry["effort"] as? [String: Any])?["default"] as Any)
            ?? text((entry["reasoning"] as? [String: Any])?["effort"] as Any)
            ?? text((entry["thinking"] as? [String: Any])?["effort"] as Any)
    }

    private static func effortLevels(_ entry: [String: Any]) -> [String] {
        let holders: [[String: Any]] = [entry, entry["effort"] as? [String: Any], entry["reasoning"] as? [String: Any], entry["thinking"] as? [String: Any]].compactMap { $0 }
        for holder in holders {
            for key in ["supported_levels", "effort_levels", "levels", "supported_efforts"] {
                if let values = strings(holder[key]), !values.isEmpty { return values }
            }
        }
        return []
    }

    private static func thinking(_ entry: [String: Any]) -> Bool? {
        if let f = flag(entry["reasoning"]) { return f }
        if let f = flag(entry["supports_reasoning"]) { return f }
        if let f = flag(entry["thinking"]) { return f }
        if entry["thinking"] is [String: Any] { return flag((entry["thinking"] as? [String: Any])?["enabled"]) ?? true }
        if entry["reasoning"] is [String: Any] { return flag((entry["reasoning"] as? [String: Any])?["enabled"]) ?? true }
        if let caps = entry["capabilities"] as? [String: Any], let f = flag(caps["reasoning"]) { return f }
        if !effortLevels(entry).isEmpty { return true }
        if let params = strings(entry["supported_parameters"]),
           params.contains("reasoning") || params.contains("include_reasoning") { return true }
        return nil
    }

    private static func strings(_ value: Any?) -> [String]? {
        guard let array = value as? [Any] else { return nil }
        let out = array.compactMap { text($0) }
        return out.isEmpty ? nil : out
    }

    private static func text(_ value: Any?) -> String? {
        guard let raw = value as? String else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func int(_ value: Any?) -> Int? {
        if let n = value as? Int { return n }
        if let n = value as? Double { return Int(n) }
        if let s = value as? String, let n = Int(s) { return n }
        return nil
    }

    private static func flag(_ value: Any?) -> Bool? {
        guard let text = (value as? String)?.lowercased() else { return nil }
        switch text {
        case "true", "1", "yes": return true
        case "false", "0", "no": return false
        default: return nil
        }
    }
}