package com.parley.speaker.domain

enum class AssessorProvider(val label: String) {
    None("未接入"),
    Azure("微软 Azure 语音评测"),
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
 * 文本分析设置。provider 是供应商 id，endpoint 与 auth 留空表示用目录里的默认值，
 * 只有自定义端点需要把这三样手填。
 */
data class AnalysisSettings(
    val apiKey: String = "",
    val model: String = "gemini-flash-latest",
    val provider: String = "gemini",
    val endpoint: String = "",
    val authKind: AuthKind? = null,
    val authName: String = "",
    val authPrefix: String = "",
    val thinking: Boolean = false,
)

data class AssessorSettings(
    val provider: AssessorProvider = AssessorProvider.None,
    val azureKey: String = "",
    val azureRegion: String = "",
)

data class AppSettings(
    val live: LiveSettings = LiveSettings(),
    val analysis: AnalysisSettings = AnalysisSettings(),
    val assessor: AssessorSettings = AssessorSettings(),
    val prompt: String = "",
)
