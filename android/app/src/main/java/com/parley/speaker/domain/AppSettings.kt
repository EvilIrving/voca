package com.parley.speaker.domain

enum class AssessorProvider(val label: String) {
    None("未选择"),
    Azure("微软 Azure 语音评测"),
    Xfyun("讯飞评测流式版"),
}

/** 文本分析请求把密钥放在哪里，三档对应 Bearer 头、自定义头与查询参数。 */
enum class AuthKind { Bearer, Header, Query }

/**
 * 实时对话设置按供应商分档：三家的凭据形态不同，模型与音色跟着当前供应商走。
 * 齐备条件也各不相同，Gemini 与 OpenAI 各认一把密钥，豆包认 AppID、AppKey、AccessKey 三件套。
 */
data class LiveSettings(
    val provider: LiveVendor = LiveVendor.Gemini,
    val geminiKey: String = "",
    val openAiKey: String = "",
    val doubaoAppId: String = "",
    val doubaoAppKey: String = "",
    val doubaoAccessKey: String = "",
    val model: String = LiveDefaults.model(LiveVendor.Gemini),
    val voice: String = LiveDefaults.voice(LiveVendor.Gemini),
) {
    fun ready(vendor: LiveVendor): Boolean = when (vendor) {
        LiveVendor.Gemini -> geminiKey.isNotBlank()
        LiveVendor.OpenAi -> openAiKey.isNotBlank()
        LiveVendor.Doubao ->
            doubaoAppId.isNotBlank() && doubaoAppKey.isNotBlank() && doubaoAccessKey.isNotBlank()
    }

    /** 当前选中的这家能不能建连接。界面与建连判定都读这一格，不再各自数密钥。 */
    val ready: Boolean get() = ready(provider)
}

/**
 * 三家的默认模型与默认音色。音色统一取 [VoiceCatalog] 的默认项，模型在这里定：
 * 豆包的端到端接口在 StartSession 里不带模型名，这一格留空，界面按音色显示。
 */
object LiveDefaults {
    fun model(vendor: LiveVendor): String = when (vendor) {
        LiveVendor.Gemini -> "gemini-3.8-live"
        LiveVendor.OpenAi -> "gpt-realtime-2.1"
        LiveVendor.Doubao -> ""
    }

    fun voice(vendor: LiveVendor): String = VoiceCatalog.defaultId(vendor)
}

/**
 * 一次设置变更对进行中会话的影响。凭据或供应商换了才重建连接；
 * 模型、音色与角色提示词只在会话开始时进 setup，改了留到下一次开新会话，不掐断正在说的一轮。
 */
enum class LiveChange { None, Rebuild, Voice, Model, Prompt }

fun liveChange(before: AppSettings, after: AppSettings): LiveChange = when {
    before.live.provider != after.live.provider -> LiveChange.Rebuild
    before.live.credentials() != after.live.credentials() -> LiveChange.Rebuild
    before.live.voice != after.live.voice -> LiveChange.Voice
    before.live.model != after.live.model -> LiveChange.Model
    before.prompt != after.prompt -> LiveChange.Prompt
    else -> LiveChange.None
}

/** 建连接真正吃的那几格，逐格比一次。 */
private fun LiveSettings.credentials(): List<String> =
    listOf(geminiKey, openAiKey, doubaoAppId, doubaoAppKey, doubaoAccessKey)

/**
 * 一家供应商自己的一份配置：密钥、挑好的模型、自定义端点与鉴权落点。
 * endpoint 与 auth 留空表示用目录里的默认值，只有自定义端点需要把这几样手填。
 */
data class AnalysisCredentials(
    val apiKey: String = "",
    val model: String = "",
    val endpoint: String = "",
    val authKind: AuthKind? = null,
    val authName: String = "",
    val authPrefix: String = "",
) {
    val ready: Boolean get() = apiKey.isNotBlank()
}

/**
 * 文本分析设置。provider 是当前生效的那家，saved 按供应商 id 各存一份，
 * 配第二家不会把第一家顶掉，换回来时密钥与挑好的模型都还在。
 * 下面那几个读的是当前这家的格子，请求装配与界面取的都是同一份。
 */
data class AnalysisSettings(
    val provider: String = "gemini",
    val saved: Map<String, AnalysisCredentials> = emptyMap(),
    val thinking: Boolean = false,
) {
    fun of(id: String): AnalysisCredentials = saved[id] ?: AnalysisCredentials()

    val active: AnalysisCredentials get() = of(provider)

    /** 只动一家那几格，别家的原样留着。 */
    fun amend(id: String, transform: (AnalysisCredentials) -> AnalysisCredentials): AnalysisSettings =
        copy(saved = saved + (id to transform(of(id))))

    fun select(id: String): AnalysisSettings = copy(provider = id)

    val apiKey: String get() = active.apiKey
    val model: String get() = active.model
    val endpoint: String get() = active.endpoint
    val authKind: AuthKind? get() = active.authKind
    val authName: String get() = active.authName
    val authPrefix: String get() = active.authPrefix
}

/**
 * 两家的凭据各占一组，齐备即启用。Azure 认密钥与区域两项；讯飞认 AppID、APIKey、APISecret 三件套。
 * resolvedProvider 是唯一判定点：显式选中的那家参数齐备就照办，否则按齐备情况退回推导，
 * 两家都齐备又没有显式选择时 Azure 在前，因此同一时刻只有一家生效。
 */
data class AssessorSettings(
    val provider: AssessorProvider = AssessorProvider.None,
    val azureKey: String = "",
    val azureRegion: String = "",
    val xfyunAppId: String = "",
    val xfyunApiKey: String = "",
    val xfyunApiSecret: String = "",
) {
    val azureReady: Boolean get() = azureKey.isNotBlank() && azureRegion.isNotBlank()

    val xfyunReady: Boolean
        get() = xfyunAppId.isNotBlank() && xfyunApiKey.isNotBlank() && xfyunApiSecret.isNotBlank()

    val resolvedProvider: AssessorProvider
        get() = when {
            provider == AssessorProvider.Xfyun && xfyunReady -> AssessorProvider.Xfyun
            azureReady -> AssessorProvider.Azure
            xfyunReady -> AssessorProvider.Xfyun
            else -> AssessorProvider.None
        }
}

data class AppSettings(
    val live: LiveSettings = LiveSettings(),
    val analysis: AnalysisSettings = AnalysisSettings(),
    val assessor: AssessorSettings = AssessorSettings(),
    val prompt: String = "",
)
