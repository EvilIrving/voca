import Foundation

struct AuthSpec: Equatable {
    var kind: AuthKind
    var name: String = ""
    var prefix: String = ""

    static func bearer() -> AuthSpec { AuthSpec(kind: .bearer, name: "Authorization", prefix: "Bearer ") }
    static func header(name: String, prefix: String = "") -> AuthSpec { AuthSpec(kind: .header, name: name, prefix: prefix) }
    static func query(name: String) -> AuthSpec { AuthSpec(kind: .query, name: name) }
}

struct TextModel: Codable, Equatable, Hashable {
    var id: String
    var context: Int = 0
    var maxOutput: Int = 0
    var thinking: Bool = false
    var defaultEffort: String?
    var effortLevels: [String] = []
    var releaseDate: String?

    var effort: String? { defaultEffort ?? effortLevels.first }
}

struct TextProvider: Equatable {
    var id: String
    var label: String
    var endpoint: String
    var auth: AuthSpec
    var models: [TextModel] = []
    var headers: [String: String] = [:]
    var jsonMode: Bool = true
    var custom: Bool = false

    var chatUrl: String { appendPath(endpoint, "chat/completions") }
    var modelsUrl: String { modelsUrlFrom(endpoint) }
}

func appendPath(_ base: String, _ path: String) -> String {
    var trimmed = base
    while trimmed.hasSuffix("/") { trimmed.removeLast() }
    if trimmed.hasSuffix("/\(path)") { return trimmed }
    return "\(trimmed)/\(path)"
}

func modelsUrlFrom(_ base: String) -> String {
    var trimmed = base
    while trimmed.hasSuffix("/") { trimmed.removeLast() }
    if trimmed.hasSuffix("/chat/completions") {
        trimmed = String(trimmed.dropLast("/chat/completions".count))
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
    }
    return appendPath(trimmed, "models")
}

enum TextProviderCatalog {
    static let customId = "custom"
    static let geminiId = "gemini"

    static let deepseek = TextProvider(
        id: "deepseek",
        label: "DeepSeek",
        endpoint: "https://api.deepseek.com/v1",
        auth: .bearer()
    )

    static let gemini = TextProvider(
        id: geminiId,
        label: "Gemini",
        endpoint: "https://generativelanguage.googleapis.com/v1beta/openai",
        auth: .bearer()
    )

    static let openai = TextProvider(
        id: "openai",
        label: "OpenAI",
        endpoint: "https://api.openai.com/v1",
        auth: .bearer()
    )

    static let anthropic = TextProvider(
        id: "anthropic",
        label: "Anthropic",
        endpoint: "https://api.anthropic.com/v1",
        auth: .header(name: "x-api-key"),
        headers: ["anthropic-version": "2023-06-01"],
        jsonMode: false
    )

    static let kimi = TextProvider(
        id: "kimi",
        label: "Kimi",
        endpoint: "https://api.moonshot.cn/v1",
        auth: .bearer()
    )

    static let glm = TextProvider(
        id: "glm",
        label: "GLM",
        endpoint: "https://open.bigmodel.cn/api/paas/v4",
        auth: .bearer()
    )

    static let custom = TextProvider(
        id: customId,
        label: "自定义端点",
        endpoint: "",
        auth: .bearer(),
        custom: true
    )

    static let all: [TextProvider] = [deepseek, gemini, openai, anthropic, kimi, glm, custom]

    static func of(_ id: String) -> TextProvider? { all.first { $0.id == id } }

    private static let catalogKeys: [String: String] = [
        deepseek.id: "deepseek",
        geminiId: "google",
        openai.id: "openai",
        anthropic.id: "anthropic",
        kimi.id: "moonshotai",
        glm.id: "zhipuai",
    ]

    static func catalogKeyOf(_ providerId: String) -> String? { catalogKeys[providerId] }
}