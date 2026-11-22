package com.parley.speaker.data.realtime

/**
 * 供应商的报错原文不往界面上走。原文要么是一整段英文协议话，要么直接是整段 JSON，
 * 落进轻提示的胶囊里既读不懂也读不完；这里按错误码与关键词认类别，换成产品自己那一句。
 * 认不出就返回 null，由调用方用自己那句，原文只进日志。
 */
internal object ProviderFault {

    const val Quota = "额度已用完，请充值"
    const val Busy = "请求太频繁，请稍后重试"
    const val Key = "apikey 无效，请在设置中检查"
    const val Region = "当前地区不可用，请检查网络"
    const val Config = "模型或音色不可用，请在设置中更换"

    private val Rules: List<Pair<String, List<String>>> = listOf(
        // 额度排在限流前面：超支上限与配额用尽同样回 429，只有原文能分开这两件事。
        Quota to listOf(
            "spending cap", "quota", "resource_exhausted", "insufficient", "billing",
            "credit", "balance", "payment", "额度", "余额", "欠费",
        ),
        Busy to listOf("rate limit", "ratelimit", "too many requests", "concurrency", "429", "并发", "限流"),
        Key to listOf(
            "api key", "api_key", "apikey", "unauthenticated", "unauthorized", "forbidden",
            "permission", "401", "403", "密钥", "鉴权",
        ),
        Region to listOf("location", "region", "country", "地区", "区域"),
        Config to listOf(
            "invalid_request", "invalid argument", "invalid voice", "invalid model",
            "model not found", "not found", "does not exist", "unsupported", "unknown name",
            "400", "模型", "音色",
        ),
    )

    /** 错误码、状态串、原文随便给几段，认得出类别就给产品那一句。 */
    fun message(vararg hints: String?): String? {
        val hay = hints.joinToString(" ") { it.orEmpty() }.lowercase()
        if (hay.isBlank()) return null
        return Rules.firstOrNull { (_, keys) -> keys.any { hay.contains(it) } }?.first
    }
}
