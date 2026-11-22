import Foundation

enum ModelCatalog {
    static let perProvider = 8

    private static let nonChat = [
        "embed", "tts", "whisper", "transcribe", "realtime", "image", "imagen", "audio",
        "dall-e", "moderation", "rerank", "ocr", "sora", "veo",
    ]

    static func isChatModel(_ id: String) -> Bool {
        let name = id.lowercased()
        return !nonChat.contains { name.contains($0) }
    }

    static func available(_ raw: String) -> [TextModel] { available(ModelsJson.parse(raw)) }

    static func available(_ models: [TextModel]) -> [TextModel] {
        var seen = Set<String>()
        return models.filter { isChatModel($0.id) && seen.insert($0.id).inserted }
    }

    static func directory(_ raw: String, key: String) -> [TextModel] {
        guard let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let provider = root[key] as? [String: Any],
              let models = provider["models"] as? [String: Any]
        else { return [] }
        return models.compactMap { id, entry in
            guard let obj = entry as? [String: Any] else { return nil }
            return ModelsJson.model(id: id, entry: obj)
        }
    }

    static func merged(_ available: [TextModel], _ directory: [TextModel]) -> [TextModel] {
        guard !directory.isEmpty else { return available }
        let index = Dictionary(uniqueKeysWithValues: directory.map { ($0.id, $0) })
        return available.map { live in
            guard let ref = index[live.id] else { return live }
            return TextModel(
                id: live.id,
                context: live.context > 0 ? live.context : ref.context,
                maxOutput: live.maxOutput > 0 ? live.maxOutput : ref.maxOutput,
                thinking: live.thinking || ref.thinking,
                defaultEffort: live.defaultEffort ?? ref.defaultEffort,
                effortLevels: live.effortLevels.isEmpty ? ref.effortLevels : live.effortLevels,
                releaseDate: live.releaseDate ?? ref.releaseDate
            )
        }
    }

    static func ordered(_ models: [TextModel]) -> [TextModel] {
        models.sorted {
            if ($0.releaseDate == nil) != ($1.releaseDate == nil) { return $0.releaseDate != nil }
            return ($0.releaseDate ?? "") > ($1.releaseDate ?? "")
        }
    }

    static func visible(_ models: [TextModel], limit: Int = perProvider) -> [TextModel] {
        let sorted = ordered(models)
        let dated = sorted.filter { $0.releaseDate != nil }.prefix(limit)
        let undated = sorted.filter { $0.releaseDate == nil }
        return Array(dated) + undated
    }

    static func pick(_ models: [TextModel], current: String) -> String {
        if models.contains(where: { $0.id == current }) { return current }
        return models.first?.id ?? ""
    }
}