package com.parley.speaker.domain

/**
 * 测试里拼一份只有一家的文本分析设置，形状与存储在设备上的那份一致：
 * 选定那家独自一格，别家留空。多个供应商的场景直接把 [AnalysisSettings.amend] 往上叠。
 */
internal fun analysisOf(
    provider: String = "gemini",
    apiKey: String = "",
    model: String = "",
    endpoint: String = "",
    authKind: AuthKind? = null,
    authName: String = "",
    authPrefix: String = "",
    thinking: Boolean = false,
): AnalysisSettings = AnalysisSettings(
    provider = provider,
    saved = mapOf(provider to AnalysisCredentials(apiKey, model, endpoint, authKind, authName, authPrefix)),
    thinking = thinking,
)
