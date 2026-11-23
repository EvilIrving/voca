import Combine
import Foundation

@MainActor
final class SettingsStore: ObservableObject {
    private let defaults = UserDefaults(suiteName: "com.parley.speaker.parley_secure") ?? .standard

    let defaultPrompt: String = SeedContent.defaultPrompt

    @Published private(set) var settings: AppSettings

    init() {
        settings = Self.read(defaults: defaults, defaultPrompt: SeedContent.defaultPrompt)
    }

    func update(transform: (AppSettings) -> AppSettings) {
        let before = settings
        var next = transform(before)
        next.assessor.provider = next.assessor.resolvedProvider
        next.live = normalizedLive(before: before.live, after: next.live)
        Self.write(next, defaults: defaults)
        settings = next
    }

    private static func seededSettings() -> AppSettings {
        AppSettings(
            live: settled(
                LiveSettings(
                    geminiKey: Secrets.geminiKey,
                    model: Secrets.geminiLiveModel,
                    voice: Secrets.geminiVoice
                )
            ),
            analysis: AnalysisSettings(
                saved: [
                    TextProviderCatalog.geminiId: AnalysisCredentials(
                        apiKey: Secrets.geminiKey,
                        model: Secrets.geminiTextModel
                    ),
                ]
            ),
            assessor: AssessorSettings(
                xfyunAppId: Secrets.xfyunAppId,
                xfyunApiKey: Secrets.xfyunApiKey,
                xfyunApiSecret: Secrets.xfyunApiSecret
            ),
            prompt: SeedContent.defaultPrompt
        )
    }

    private static func read(defaults: UserDefaults, defaultPrompt: String) -> AppSettings {
        if !defaults.bool(forKey: Keys.seeded) {
            let initial = seededSettings()
            write(initial, defaults: defaults)
            defaults.set(true, forKey: Keys.seeded)
            return initial
        }
        return AppSettings(
            live: readLive(defaults),
            analysis: readAnalysis(defaults),
            assessor: readAssessor(defaults),
            prompt: readPrompt(defaults, fallback: defaultPrompt)
        )
    }

    private static func write(_ settings: AppSettings, defaults: UserDefaults) {
        putLive(settings.live, defaults: defaults)
        putAnalysis(settings.analysis, defaults: defaults)
        putAssessor(settings.assessor, defaults: defaults)
        defaults.set(settings.prompt, forKey: Keys.prompt)
    }

    enum Keys {
        static let seeded = "seeded"
        static let prompt = "prompt"
        static let analysisSaved = "analysis_saved"
        static let analysisProvider = "analysis_provider"
        static let analysisThinking = "analysis_thinking"
        static let legacyAnalysisKey = "analysis_api_key"
        static let legacyAnalysisModel = "analysis_model"
        static let legacyAnalysisEndpoint = "analysis_endpoint"
        static let legacyAnalysisAuth = "analysis_auth_kind"
        static let legacyAnalysisAuthName = "analysis_auth_name"
        static let legacyAnalysisAuthPrefix = "analysis_auth_prefix"
        static let assessorProvider = "assessor_provider"
        static let azureKey = "azure_key"
        static let azureRegion = "azure_region"
        static let xfyunAppId = "xfyun_app_id"
        static let xfyunApiKey = "xfyun_api_key"
        static let xfyunApiSecret = "xfyun_api_secret"
        static let liveProvider = "live_provider"
        static let liveKey = "live_api_key"
        static let liveOpenAiKey = "live_openai_key"
        static let liveDoubaoAppId = "live_doubao_app_id"
        static let liveDoubaoAppKey = "live_doubao_app_key"
        static let liveDoubaoAccessKey = "live_doubao_access_key"
        static let liveModel = "live_model"
        static let liveVoice = "live_voice"
    }

    private static func slotKey(_ id: String, _ field: String) -> String { "analysis.\(id).\(field)" }

}

// MARK: - Analysis

func readAnalysis(_ defaults: UserDefaults) -> AnalysisSettings {
    let provider = defaults.string(forKey: SettingsStore.Keys.analysisProvider) ?? TextProviderCatalog.geminiId
    let thinking = defaults.bool(forKey: SettingsStore.Keys.analysisThinking)
    if let savedIds = defaults.array(forKey: SettingsStore.Keys.analysisSaved) as? [String] {
        var saved: [String: AnalysisCredentials] = [:]
        for id in savedIds {
            saved[id] = AnalysisCredentials(
                apiKey: SecureStore.string(for: slotKey(id, "key")) ?? "",
                model: defaults.string(forKey: slotKey(id, "model")) ?? "",
                endpoint: defaults.string(forKey: slotKey(id, "endpoint")) ?? "",
                authKind: defaults.string(forKey: slotKey(id, "auth")).flatMap(authKindFromStored),
                authName: defaults.string(forKey: slotKey(id, "auth_name")) ?? "",
                authPrefix: defaults.string(forKey: slotKey(id, "auth_prefix")) ?? ""
            )
        }
        let filtered = saved.filter { $0.value != AnalysisCredentials() }
        return AnalysisSettings(provider: provider, saved: filtered, thinking: thinking)
    }

    let legacy = AnalysisCredentials(
        apiKey: SecureStore.string(for: SettingsStore.Keys.legacyAnalysisKey) ?? "",
        model: defaults.string(forKey: SettingsStore.Keys.legacyAnalysisModel) ?? "",
        endpoint: defaults.string(forKey: SettingsStore.Keys.legacyAnalysisEndpoint) ?? "",
        authKind: defaults.string(forKey: SettingsStore.Keys.legacyAnalysisAuth).flatMap(authKindFromStored),
        authName: defaults.string(forKey: SettingsStore.Keys.legacyAnalysisAuthName) ?? "",
        authPrefix: defaults.string(forKey: SettingsStore.Keys.legacyAnalysisAuthPrefix) ?? ""
    )
    let migrated: [String: AnalysisCredentials] = legacy == AnalysisCredentials() ? [:] : [provider: legacy]
    let settings = AnalysisSettings(provider: provider, saved: migrated, thinking: thinking)
    putAnalysis(settings, defaults: defaults)
    for key in [
        SettingsStore.Keys.legacyAnalysisKey,
        SettingsStore.Keys.legacyAnalysisModel,
        SettingsStore.Keys.legacyAnalysisEndpoint,
        SettingsStore.Keys.legacyAnalysisAuth,
        SettingsStore.Keys.legacyAnalysisAuthName,
        SettingsStore.Keys.legacyAnalysisAuthPrefix,
    ] {
        defaults.removeObject(forKey: key)
        if key == SettingsStore.Keys.legacyAnalysisKey {
            SecureStore.remove(key)
        }
    }
    return settings
}

func putAnalysis(_ analysis: AnalysisSettings, defaults: UserDefaults) {
    let saved = analysis.saved.filter { $0.value != AnalysisCredentials() }
    defaults.set(Array(saved.keys), forKey: SettingsStore.Keys.analysisSaved)
    for (id, credentials) in saved {
        if credentials.apiKey.isEmpty {
            SecureStore.remove(slotKey(id, "key"))
        } else {
            SecureStore.set(credentials.apiKey, for: slotKey(id, "key"))
        }
        defaults.set(credentials.model, forKey: slotKey(id, "model"))
        defaults.set(credentials.endpoint, forKey: slotKey(id, "endpoint"))
        defaults.set(credentials.authKind.map(authKindStored), forKey: slotKey(id, "auth"))
        defaults.set(credentials.authName, forKey: slotKey(id, "auth_name"))
        defaults.set(credentials.authPrefix, forKey: slotKey(id, "auth_prefix"))
    }
    defaults.set(analysis.provider, forKey: SettingsStore.Keys.analysisProvider)
    defaults.set(analysis.thinking, forKey: SettingsStore.Keys.analysisThinking)
}

// MARK: - Assessor

func readAssessor(_ defaults: UserDefaults) -> AssessorSettings {
    let stored = defaults.string(forKey: SettingsStore.Keys.assessorProvider).flatMap(assessorProviderFromStored)
    var settings = AssessorSettings(
        provider: stored ?? .none,
        azureKey: SecureStore.string(for: SettingsStore.Keys.azureKey) ?? "",
        azureRegion: defaults.string(forKey: SettingsStore.Keys.azureRegion) ?? "",
        xfyunAppId: defaults.string(forKey: SettingsStore.Keys.xfyunAppId) ?? "",
        xfyunApiKey: SecureStore.string(for: SettingsStore.Keys.xfyunApiKey) ?? "",
        xfyunApiSecret: SecureStore.string(for: SettingsStore.Keys.xfyunApiSecret) ?? ""
    )
    settings.provider = settings.resolvedProvider
    return settings
}

func putAssessor(_ settings: AssessorSettings, defaults: UserDefaults) {
    defaults.set(assessorProviderStored(settings.resolvedProvider), forKey: SettingsStore.Keys.assessorProvider)
    if settings.azureKey.isEmpty { SecureStore.remove(SettingsStore.Keys.azureKey) } else { SecureStore.set(settings.azureKey, for: SettingsStore.Keys.azureKey) }
    defaults.set(settings.azureRegion, forKey: SettingsStore.Keys.azureRegion)
    defaults.set(settings.xfyunAppId, forKey: SettingsStore.Keys.xfyunAppId)
    if settings.xfyunApiKey.isEmpty { SecureStore.remove(SettingsStore.Keys.xfyunApiKey) } else { SecureStore.set(settings.xfyunApiKey, for: SettingsStore.Keys.xfyunApiKey) }
    if settings.xfyunApiSecret.isEmpty { SecureStore.remove(SettingsStore.Keys.xfyunApiSecret) } else { SecureStore.set(settings.xfyunApiSecret, for: SettingsStore.Keys.xfyunApiSecret) }
}

// MARK: - Prompt

func readPrompt(_ defaults: UserDefaults, fallback: String) -> String {
    guard let stored = defaults.string(forKey: SettingsStore.Keys.prompt) else { return fallback }
    if stored == SeedContent.defaultPrompt { return fallback }
    return stored
}

// MARK: - Live

func readLive(_ defaults: UserDefaults) -> LiveSettings {
    let provider = defaults.string(forKey: SettingsStore.Keys.liveProvider).flatMap(liveVendorFromStored) ?? .gemini
    return settled(
        LiveSettings(
            provider: provider,
            geminiKey: SecureStore.string(for: SettingsStore.Keys.liveKey) ?? "",
            openAiKey: SecureStore.string(for: SettingsStore.Keys.liveOpenAiKey) ?? "",
            doubaoAppId: defaults.string(forKey: SettingsStore.Keys.liveDoubaoAppId) ?? "",
            doubaoAppKey: SecureStore.string(for: SettingsStore.Keys.liveDoubaoAppKey) ?? "",
            doubaoAccessKey: SecureStore.string(for: SettingsStore.Keys.liveDoubaoAccessKey) ?? "",
            model: defaults.string(forKey: SettingsStore.Keys.liveModel) ?? "",
            voice: defaults.string(forKey: SettingsStore.Keys.liveVoice) ?? ""
        )
    )
}

func putLive(_ live: LiveSettings, defaults: UserDefaults) {
    defaults.set(liveVendorStored(live.provider), forKey: SettingsStore.Keys.liveProvider)
    if live.geminiKey.isEmpty { SecureStore.remove(SettingsStore.Keys.liveKey) } else { SecureStore.set(live.geminiKey, for: SettingsStore.Keys.liveKey) }
    if live.openAiKey.isEmpty { SecureStore.remove(SettingsStore.Keys.liveOpenAiKey) } else { SecureStore.set(live.openAiKey, for: SettingsStore.Keys.liveOpenAiKey) }
    defaults.set(live.doubaoAppId, forKey: SettingsStore.Keys.liveDoubaoAppId)
    if live.doubaoAppKey.isEmpty { SecureStore.remove(SettingsStore.Keys.liveDoubaoAppKey) } else { SecureStore.set(live.doubaoAppKey, for: SettingsStore.Keys.liveDoubaoAppKey) }
    if live.doubaoAccessKey.isEmpty { SecureStore.remove(SettingsStore.Keys.liveDoubaoAccessKey) } else { SecureStore.set(live.doubaoAccessKey, for: SettingsStore.Keys.liveDoubaoAccessKey) }
    defaults.set(live.model, forKey: SettingsStore.Keys.liveModel)
    defaults.set(live.voice, forKey: SettingsStore.Keys.liveVoice)
}

func settled(_ live: LiveSettings) -> LiveSettings {
    var next = live
    if next.model.isEmpty { next.model = LiveDefaults.model(next.provider) }
    if !VoiceCatalog.isKnown(next.provider, id: next.voice) {
        next.voice = LiveDefaults.voice(next.provider)
    }
    return next
}

func normalizedLive(before: LiveSettings, after: LiveSettings) -> LiveSettings {
    if before.provider == after.provider { return settled(after) }
    var cleared = after
    cleared.model = ""
    cleared.voice = ""
    return settled(cleared)
}

private func slotKey(_ id: String, _ field: String) -> String { "analysis.\(id).\(field)" }

private func authKindFromStored(_ name: String) -> AuthKind? {
    switch name {
    case "Bearer": .bearer
    case "Header": .header
    case "Query": .query
    default: AuthKind(rawValue: name.lowercased())
    }
}

private func authKindStored(_ kind: AuthKind) -> String {
    switch kind {
    case .bearer: "Bearer"
    case .header: "Header"
    case .query: "Query"
    }
}

private func liveVendorFromStored(_ name: String) -> LiveVendor? {
    switch name {
    case "Gemini": .gemini
    case "OpenAi": .openAi
    case "Doubao": .doubao
    default: LiveVendor(rawValue: name)
    }
}

private func liveVendorStored(_ vendor: LiveVendor) -> String {
    switch vendor {
    case .gemini: "Gemini"
    case .openAi: "OpenAi"
    case .doubao: "Doubao"
    }
}

private func assessorProviderFromStored(_ name: String) -> AssessorProvider? {
    switch name {
    case "None": .none
    case "Azure": .azure
    case "Xfyun": .xfyun
    default: AssessorProvider(rawValue: name.lowercased())
    }
}

private func assessorProviderStored(_ provider: AssessorProvider) -> String {
    switch provider {
    case .none: "None"
    case .azure: "Azure"
    case .xfyun: "Xfyun"
    }
}