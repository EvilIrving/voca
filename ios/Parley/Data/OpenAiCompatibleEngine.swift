import Foundation
import os

let charsPerToken = 2
private let analysisMaxOutput = 4096
private let systemRole =
    "你是英语口语教练，只输出调用方要求的 JSON，不输出解释、代码块标记或多余字段。"

struct TextEngineConfig {
    var provider: TextProvider
    var model: String
    var endpoint: String
    var auth: AuthSpec
    var apiKey: String
    var thinking: Bool = false
    var headers: [String: String]
    var modelMeta: TextModel?

    init(
        provider: TextProvider,
        model: String,
        endpoint: String,
        auth: AuthSpec,
        apiKey: String,
        thinking: Bool = false,
        headers: [String: String]? = nil,
        modelMeta: TextModel? = nil
    ) {
        self.provider = provider
        self.model = model
        self.endpoint = endpoint
        self.auth = auth
        self.apiKey = apiKey
        self.thinking = thinking
        self.headers = headers ?? provider.headers
        self.modelMeta = modelMeta
    }

    var chatUrl: String { appendPath(endpoint, "chat/completions") }
    var ready: Bool { !apiKey.isEmpty && !endpoint.isEmpty && !model.isEmpty }

    static func of(
        provider: TextProvider,
        model: String,
        apiKey: String,
        thinking: Bool = false,
        meta: TextModel? = nil
    ) -> TextEngineConfig {
        TextEngineConfig(
            provider: provider,
            model: model.isEmpty ? (meta?.id ?? "") : model,
            endpoint: provider.endpoint,
            auth: provider.auth,
            apiKey: apiKey,
            thinking: thinking,
            modelMeta: meta
        )
    }

    static func of(settings: AnalysisSettings, models: [TextModel] = []) -> TextEngineConfig {
        let provider = TextProviderCatalog.of(settings.provider) ?? TextProviderCatalog.gemini
        let model = settings.model.isEmpty ? (models.first?.id ?? "") : settings.model
        return TextEngineConfig(
            provider: provider,
            model: model,
            endpoint: settings.endpoint.isEmpty ? provider.endpoint : settings.endpoint,
            auth: settings.active.authSpec(provider: provider),
            apiKey: settings.apiKey,
            thinking: settings.thinking,
            modelMeta: models.first { $0.id == model }
        )
    }

    var maxOutputTokens: Int {
        guard let out = modelMeta?.maxOutput, out > 0 else { return 0 }
        return min(out, analysisMaxOutput)
    }

    func promptWithinContext(_ prompt: String) -> String {
        guard let context = modelMeta?.context, context > 0 else { return prompt }
        let budget = max(context - maxOutputTokens, context / 2) * charsPerToken
        if prompt.count <= budget { return prompt }
        return String(prompt.prefix(budget))
    }

    func signed(_ raw: String) -> URL? {
        guard var components = URLComponents(string: raw) else { return nil }
        if auth.kind == .query {
            let name = auth.name.isEmpty ? "key" : auth.name
            var items = components.queryItems ?? []
            items.append(URLQueryItem(name: name, value: apiKey))
            components.queryItems = items
        }
        return components.url
    }

    func authHeader() -> (String, String)? {
        switch auth.kind {
        case .bearer:
            let name = auth.name.isEmpty ? "Authorization" : auth.name
            let prefix = auth.prefix.isEmpty ? "Bearer " : auth.prefix
            return (name, prefix + apiKey)
        case .header:
            let name = auth.name.isEmpty ? "x-api-key" : auth.name
            return (name, auth.prefix + apiKey)
        case .query:
            return nil
        }
    }

    func request(prompt: String, structured: Bool) -> URLRequest? {
        guard let url = signed(chatUrl) else { return nil }
        var messages: [[String: Any]] = [
            ["role": "system", "content": systemRole],
            ["role": "user", "content": promptWithinContext(prompt)],
        ]
        var body: [String: Any] = [
            "model": model,
            "messages": messages,
            "temperature": 0.3,
            "stream": false,
        ]
        if maxOutputTokens > 0 { body["max_tokens"] = maxOutputTokens }
        if structured, provider.jsonMode {
            body["response_format"] = ["type": "json_object"]
        }
        if thinking, modelMeta?.thinking != false {
            var thinkingObj: [String: Any] = ["type": "enabled"]
            body["thinking"] = thinkingObj
            if let effort = modelMeta?.effort { body["reasoning_effort"] = effort }
        }
        return signedRequest(url: url, method: "POST", json: body)
    }

    func modelsRequest() -> URLRequest? {
        guard let url = signed(modelsUrlFrom(endpoint)) else { return nil }
        return signedRequest(url: url, method: "GET", json: nil)
    }

    func probeRequest() -> URLRequest? {
        let trimmed = endpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !trimmed.hasSuffix("/chat/completions") { return modelsRequest() }
        guard let url = signed(chatUrl) else { return nil }
        let body: [String: Any] = [
            "model": model,
            "messages": [["role": "user", "content": "hi"]],
            "max_tokens": 1,
            "stream": false,
        ]
        return signedRequest(url: url, method: "POST", json: body)
    }

    private func signedRequest(url: URL, method: String, json: [String: Any]?) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        if let (name, value) = authHeader() {
            request.setValue(value, forHTTPHeaderField: name)
        }
        if let json {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONSerialization.data(withJSONObject: json)
        }
        return request
    }
}

extension AnalysisCredentials {
    func authSpec(provider: TextProvider) -> AuthSpec {
        guard let authKind else { return provider.auth }
        switch authKind {
        case .bearer: return .bearer()
        case .header:
            return .header(name: authName.isEmpty ? provider.auth.name : authName, prefix: authPrefix)
        case .query:
            return .query(name: authName.isEmpty ? "key" : authName)
        }
    }
}

struct OpenAiCompatibleEngine: TurnAnalyzer {
    private let config: TextEngineConfig
    private let session: URLSession

    init(config: TextEngineConfig, session: URLSession = OpenAiCompatibleEngine.defaultSession) {
        self.config = config
        self.session = session
    }

    func analyze(text: String, turnId: String) async -> TurnAnalysis? {
        guard config.ready, !text.isEmpty, let request = config.request(prompt: analysisPrompt(text), structured: true) else { return nil }
        guard let raw = await post(request), let content = replyText(raw) else { return nil }
        return parseAnalysisReport(content, original: text, turnId: turnId)
    }

    func translate(text: String) async -> String? {
        guard config.ready, !text.isEmpty, let request = config.request(prompt: translatePrompt(text), structured: false) else { return nil }
        guard let raw = await post(request), let content = replyText(raw)?.trimmingCharacters(in: .whitespacesAndNewlines), !content.isEmpty else { return nil }
        return content
    }

    func name(transcript: String) async -> String? {
        guard config.ready, !transcript.isEmpty, let request = config.request(prompt: sessionNamePrompt(transcript), structured: false) else { return nil }
        guard var content = await post(request).flatMap(replyText)?.trimmingCharacters(in: .whitespacesAndNewlines), !content.isEmpty else { return nil }
        for trim in ["\"", "「", "」", "。", "\n"] { content = content.trimmingCharacters(in: CharacterSet(charactersIn: trim)) }
        return String(content.prefix(Self.maxNameChars))
    }

    func memorize(memory: String, material: String) async -> String? {
        guard config.ready, !material.isEmpty, let request = config.request(prompt: memoryPrompt(memory: memory, material: material), structured: false) else { return nil }
        guard var content = await post(request).flatMap(replyText).flatMap(parseMemory) else { return nil }
        for trim in ["\"", "「", "」", "\n", "`"] { content = content.trimmingCharacters(in: CharacterSet(charactersIn: trim)) }
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private func post(_ request: URLRequest) async -> String? {
        do {
            let (data, response) = try await session.data(for: request)
            let text = String(data: data, encoding: .utf8) ?? ""
            guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
                if let http = response as? HTTPURLResponse {
                    Logger.turnAnalyzer.warning("\(config.provider.id, privacy: .public) \(http.statusCode): \(text.prefix(200), privacy: .public)")
                }
                return nil
            }
            return text
        } catch {
            Logger.turnAnalyzer.warning("\(config.provider.id, privacy: .public) request failed: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    static let defaultSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 45
        configuration.timeoutIntervalForResource = 45
        configuration.waitsForConnectivity = true
        return URLSession(configuration: configuration)
    }()

    private static let maxNameChars = 12
}

private enum Logger {
    static let turnAnalyzer = os.Logger(subsystem: "com.parley.speaker", category: "TurnAnalyzer")
}

func analysisPrompt(_ text: String) -> String {
    """
    学生说了一句话，可能中英混说。只输出一个 JSON 对象。

    硬性要求：
    1. vocab 至少一条、最多三条，取学生这句话里真实出现的表达，或它对应的地道英文说法；不许留空数组。
    2. 学生用中文说的部分，把最自然的英文说法收进 vocab。
    3. 每条 highlight 必须是同一条 example 的子串，逐字一致。
    4. phonetic 写在两个斜杠之间，meaning 用中文，只写释义本身，不加括号注释，不写语体、场景与用法说明。

    {"score": 0 到 100 的整数,
     "note": "一句中文点评，指出最关键的一处问题，不超过二十字",
     "idiomatic": {"text": "母语者日常会这么说的英文", "gloss": "对应中文"},
     "advanced": {"text": "更讲究的一种英文说法", "gloss": "对应中文"},
     "vocab": [{"word": "地道搭配或生词", "phonetic": "/音标/", "meaning": "中文释义",
                "example": "含该搭配的英文例句", "highlight": "example 里要突出的片段"}]}

    学生原句：\(text)
    """
}

func translatePrompt(_ text: String) -> String {
    """
    把下面这句话译成自然的中文，只输出译文，不要引号，不要解释：
    \(text)
    """
}

func sessionNamePrompt(_ transcript: String) -> String {
    """
    给下面这段英语口语练习取一个能指认内容的中文短语，不超过十个字，只输出短语本身，不要引号，不要解释：
    \(transcript)
    """
}

func memoryPrompt(memory: String, material: String) -> String {
    """
    你在维护一场英语口语练习的记忆。下面是现在的记忆与刚刚发生的一轮对话。
    把这一轮的新内容并进记忆，只输出改写后的记忆正文。

    硬性要求：
    1. 输出的是整段记忆，不是追加的片段；不写标题、编号、条目、时间戳，也不写修改说明。
    2. 被新内容推翻的说法直接改写或删掉，前后矛盾时以最近一次为准。
    3. 只留后面还用得上的事：身份、水平、想练的方向、提过的人与地点、正在准备的事、卡住过的说法。
    4. 用连贯的中文叙述，不分行，不超过 \(MemoryRules.maxChars) 个字符。
    5. 只给正文本身，不要 JSON、不要字段名、不要引号，也不要代码块围栏。

    现在的记忆：
    \(memory)

    刚刚这一轮：
    \(material)
    """
}