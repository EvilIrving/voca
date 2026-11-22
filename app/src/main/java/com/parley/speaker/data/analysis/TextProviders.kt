package com.parley.speaker.data.analysis

import com.parley.speaker.domain.AuthKind

/**
 * 鉴权在请求上的落点。目录给每个供应商定一份默认值，自定义端点可以三样都手填。
 */
data class AuthSpec(
    val kind: AuthKind,
    val name: String = "",
    val prefix: String = "",
) {
    companion object {
        fun bearer(): AuthSpec = AuthSpec(AuthKind.Bearer, "Authorization", "Bearer ")
        fun header(name: String, prefix: String = ""): AuthSpec = AuthSpec(AuthKind.Header, name, prefix)
        fun query(name: String): AuthSpec = AuthSpec(AuthKind.Query, name)
    }
}

/**
 * 一个模型的元数据。context 与 maxOutput 为 0 表示还没同步到真实值，界面据此显示待同步。
 * thinking 表示请求可以带思考参数，effort 是对应的 reasoning_effort 档位。
 */
data class TextModel(
    val id: String,
    val context: Int = 0,
    val maxOutput: Int = 0,
    val thinking: Boolean = false,
    val effort: String? = null,
    val legacy: Boolean = false,
)

/** 一个供应商的接入参数。endpoint 是基地址，请求地址由 [chatUrl] 补路径得到。 */
data class TextProvider(
    val id: String,
    val label: String,
    val endpoint: String,
    val auth: AuthSpec,
    val models: List<TextModel> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val jsonMode: Boolean = true,
    val custom: Boolean = false,
) {
    val chatUrl: String get() = appendPath(endpoint, "chat/completions")
    val modelsUrl: String get() = appendPath(endpoint, "models")
}

/** 端点可能已经是完整的 chat 地址，重复补路径会拼出 chat/completions/chat/completions。 */
internal fun appendPath(base: String, path: String): String {
    val trimmed = base.trimEnd('/')
    return if (trimmed.endsWith("/$path")) trimmed else "$trimmed/$path"
}

/**
 * 六家预置供应商加一个自定义端点。模型清单只是出厂种子，真实元数据由 models.json 同步覆盖。
 */
object TextProviderCatalog {

    const val CustomId = "custom"
    const val GeminiId = "gemini"

    val deepseek = TextProvider(
        id = "deepseek",
        label = "DeepSeek",
        endpoint = "https://api.deepseek.com/v1",
        auth = AuthSpec.bearer(),
        // 官方通用接口的现行标识是 deepseek-flash，旧 deepseek-chat 留作历史项。
        models = listOf(
            TextModel(id = "deepseek-flash", thinking = true),
            TextModel(id = "deepseek-chat", legacy = true),
        ),
    )

    val gemini = TextProvider(
        id = GeminiId,
        label = "Gemini",
        endpoint = "https://generativelanguage.googleapis.com/v1beta/openai",
        auth = AuthSpec.bearer(),
        models = listOf(
            TextModel(id = "gemini-flash-latest"),
            TextModel(id = "gemini-4-argon"),
        ),
    )

    val openai = TextProvider(
        id = "openai",
        label = "OpenAI",
        endpoint = "https://api.openai.com/v1",
        auth = AuthSpec.bearer(),
        models = listOf(TextModel(id = "gpt-4o")),
    )

    val anthropic = TextProvider(
        id = "anthropic",
        label = "Anthropic",
        endpoint = "https://api.anthropic.com/v1",
        auth = AuthSpec.header("x-api-key"),
        models = listOf(TextModel(id = "claude-sonnet-4-5")),
        headers = mapOf("anthropic-version" to "2023-06-01"),
        jsonMode = false,
    )

    val kimi = TextProvider(
        id = "kimi",
        label = "Kimi",
        endpoint = "https://api.moonshot.cn/v1",
        auth = AuthSpec.bearer(),
        models = listOf(TextModel(id = "kimi-k2-turbo-preview", thinking = true)),
    )

    val glm = TextProvider(
        id = "glm",
        label = "GLM",
        endpoint = "https://open.bigmodel.cn/api/paas/v4",
        auth = AuthSpec.bearer(),
        models = listOf(TextModel(id = "glm-4.6", thinking = true)),
    )

    val custom = TextProvider(
        id = CustomId,
        label = "自定义端点",
        endpoint = "",
        auth = AuthSpec.bearer(),
        custom = true,
    )

    val all: List<TextProvider> = listOf(deepseek, gemini, openai, anthropic, kimi, glm, custom)

    fun of(id: String): TextProvider? = all.firstOrNull { it.id == id }

    fun modelOf(provider: TextProvider, modelId: String): TextModel? =
        provider.models.firstOrNull { it.id == modelId }

    fun seeds(): List<TextModel> = all.flatMap { it.models }
}
