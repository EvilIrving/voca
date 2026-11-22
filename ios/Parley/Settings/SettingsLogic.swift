import SwiftUI

extension LinkStatus {
    var uiLabel: String {
        switch self {
        case .connected: "已连通"
        case .configured: "已配置"
        case .failed: "连接失败"
        default: "未配置"
        }
    }

    var uiTint: Color {
        switch self {
        case .connected: Palette.olive
        case .configured: Palette.ink2
        case .failed: Palette.danger
        default: Palette.ink4
        }
    }

    var linked: Bool { self == .connected || self == .configured }
}

extension AuthKind {
    var label: String {
        switch self {
        case .bearer: "Bearer"
        case .header: "请求头"
        case .query: "查询参数"
        }
    }
}

let settingsProbePending = "正在自检"
let analysisUnpicked = "未选择，点开挑一个"

func injectedKey(_ providerId: String) -> String {
    providerId == TextProviderCatalog.geminiId ? "analysis" : providerId
}

func hostOf(_ provider: TextProvider) -> String {
    let rest = provider.endpoint.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")
    let host = rest.split(separator: "/").first.map(String.init) ?? ""
    return host.isEmpty ? "未填写" : host
}

func analysisVerdict(_ settings: AnalysisSettings) -> String {
    let config = TextEngineConfig.of(settings: settings)
    let plan = AnalysisProbe.plan(config: config)
    if settings.apiKey.isEmpty { return "未填密钥" }
    if config.endpoint.isEmpty { return "未填端点" }
    if plan.status == .failed { return "端点无效" }
    return "配置可用"
}

struct AnalysisRow {
    var provider: TextProvider
    var detail: String
    var status: LinkStatus
}

func analysisRow(_ analysis: AnalysisSettings, provider: TextProvider, lines: [ProviderLine]) -> AnalysisRow {
    let own = analysis.of(provider.id)
    let detail: String
    if !own.ready {
        detail = hostOf(provider)
    } else if own.model.isEmpty {
        detail = analysisUnpicked
    } else if !provider.models.isEmpty && !provider.models.contains(where: { $0.id == own.model }) {
        detail = analysisUnpicked
    } else {
        detail = own.model
    }
    let status = lines.first { $0.key == injectedKey(provider.id) }?.status ?? .unconfigured
    return AnalysisRow(provider: provider, detail: detail, status: status)
}

func modelsToLoad(_ analysis: AnalysisSettings, providerId: String) -> [String] {
    analysis.of(providerId).ready ? [providerId] : []
}

func formatProbeElapsed(_ ms: Int64) -> String {
    String(format: "%.1f 秒", Double(ms) / 1000)
}

func formatTokens(_ value: Int) -> String {
    if value >= 1_000_000 { return "\(value / 1_000_000)M" }
    if value >= 1_000 { return "\(value / 1_000)K" }
    return "\(value)"
}

func metaLine(_ model: TextModel) -> String {
    var parts: [String] = []
    if model.context > 0 { parts.append("\(formatTokens(model.context)) 上下文") }
    if model.maxOutput > 0 { parts.append("\(formatTokens(model.maxOutput)) 输出") }
    if model.thinking { parts.append("思考") }
    return parts.joined(separator: " · ")
}

extension AppSettings {
    func liveSaved(vendor: LiveVendor, values: [String: String], voice: String) -> AppSettings {
        let trimmed = values.mapValues { $0.trimmingCharacters(in: .whitespaces) }
        var nextLive = live
        switch vendor {
        case .gemini: nextLive.geminiKey = trimmed["geminiKey"] ?? ""
        case .openAi: nextLive.openAiKey = trimmed["openAiKey"] ?? ""
        case .doubao:
            nextLive.doubaoAppId = trimmed["doubaoAppId"] ?? ""
            nextLive.doubaoAppKey = trimmed["doubaoAppKey"] ?? ""
            nextLive.doubaoAccessKey = trimmed["doubaoAccessKey"] ?? ""
        }
        nextLive.provider = vendor
        nextLive.voice = voice
        return AppSettings(live: nextLive, analysis: analysis, assessor: assessor, prompt: prompt)
    }

    func withAnalysisCredentials(
        provider: TextProvider,
        apiKey: String,
        model: String,
        endpoint: String,
        authKind: AuthKind?,
        authName: String,
        authPrefix: String
    ) -> AppSettings {
        var next = analysis
        var cred = analysis.of(provider.id)
        cred.apiKey = apiKey.trimmingCharacters(in: .whitespaces)
        cred.model = model.trimmingCharacters(in: .whitespaces).isEmpty ? cred.model : model.trimmingCharacters(in: .whitespaces)
        if provider.custom {
            cred.endpoint = endpoint.trimmingCharacters(in: .whitespaces)
            cred.authKind = authKind
            cred.authName = authName.trimmingCharacters(in: .whitespaces)
            cred.authPrefix = authPrefix.trimmingCharacters(in: .whitespaces)
        }
        next.saved[provider.id] = cred
        next.provider = provider.id
        return AppSettings(live: live, analysis: next, assessor: assessor, prompt: prompt)
    }

    func withAnalysisModel(provider: TextProvider, modelId: String) -> AppSettings? {
        guard provider.models.contains(where: { $0.id == modelId }) else { return nil }
        var next = analysis
        var cred = analysis.of(provider.id)
        cred.model = modelId
        next.saved[provider.id] = cred
        next.provider = provider.id
        return AppSettings(live: live, analysis: next, assessor: assessor, prompt: prompt)
    }
}

extension AssessorSettings {
    func withAzure(key: String, region: String) -> AssessorSettings {
        var next = copy(azureKey: key.trimmingCharacters(in: .whitespaces), azureRegion: region.trimmingCharacters(in: .whitespaces))
        next.provider = azureReady ? .azure : next.resolvedProvider
        return next
    }

    func withXfyun(appId: String, apiKey: String, apiSecret: String) -> AssessorSettings {
        var next = copy(
            xfyunAppId: appId.trimmingCharacters(in: .whitespaces),
            xfyunApiKey: apiKey.trimmingCharacters(in: .whitespaces),
            xfyunApiSecret: apiSecret.trimmingCharacters(in: .whitespaces)
        )
        next.provider = xfyunReady ? .xfyun : next.resolvedProvider
        return next
    }

    private func copy(
        azureKey: String? = nil,
        azureRegion: String? = nil,
        xfyunAppId: String? = nil,
        xfyunApiKey: String? = nil,
        xfyunApiSecret: String? = nil
    ) -> AssessorSettings {
        AssessorSettings(
            provider: provider,
            azureKey: azureKey ?? self.azureKey,
            azureRegion: azureRegion ?? self.azureRegion,
            xfyunAppId: xfyunAppId ?? self.xfyunAppId,
            xfyunApiKey: xfyunApiKey ?? self.xfyunApiKey,
            xfyunApiSecret: xfyunApiSecret ?? self.xfyunApiSecret
        )
    }
}

struct LiveField {
    var key: String
    var label: String
    var value: String
    var secret: Bool = false
}

func liveFields(_ live: LiveSettings, vendor: LiveVendor) -> [LiveField] {
    switch vendor {
    case .gemini:
        [LiveField(key: "geminiKey", label: "密钥", value: live.geminiKey, secret: true)]
    case .openAi:
        [LiveField(key: "openAiKey", label: "密钥", value: live.openAiKey, secret: true)]
    case .doubao:
        [
            LiveField(key: "doubaoAppId", label: "AppID", value: live.doubaoAppId),
            LiveField(key: "doubaoAppKey", label: "AppKey", value: live.doubaoAppKey, secret: true),
            LiveField(key: "doubaoAccessKey", label: "AccessKey", value: live.doubaoAccessKey, secret: true),
        ]
    }
}

func currentVoice(_ live: LiveSettings, vendor: LiveVendor) -> String {
    if live.provider == vendor && VoiceCatalog.isKnown(vendor, id: live.voice) { return live.voice }
    return LiveDefaults.voice(vendor)
}

func voiceStyle(_ vendor: LiveVendor, id: String) -> String? {
    VoiceCatalog.voices(vendor).first { $0.id == id }?.style
}

func showNewSessionAction(continuing: Bool, turnCount: Int) -> Bool {
    !continuing && turnCount > 0
}