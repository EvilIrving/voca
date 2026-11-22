package com.parley.speaker.domain

enum class AssessorProvider(val label: String) {
    None("未接入"),
    Azure("微软 Azure 语音评测"),
}

/** 文本分析请求把密钥放在哪里，三档对应 Bearer 头、自定义头与查询参数。 */
enum class AuthKind { Bearer, Header, Query }

data class LiveSettings(
    val apiKey: String = "",
    val model: String = "gemini-3.8-live",
    val voice: String = "Aoede",
)

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
